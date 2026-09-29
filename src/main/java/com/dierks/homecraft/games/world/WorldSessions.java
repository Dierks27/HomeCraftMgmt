package com.dierks.homecraft.games.world;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * World sessions: taking a player into a world game and bringing them back exactly as they were
 * (spec §7, R2, R3.7).
 *
 * <p>The order of every step is the design. Entering: close the inventory, teleport, THEN save
 * the player's state (after the teleport, so Multiverse-Inventories has already swapped in the
 * Games world's inventory), THEN clear, save and give the kit. Leaving: bank anything that turned
 * up during the game, restore in place (overwriting), mark the row RETURN in the same tick, THEN
 * teleport back, THEN mark the row DONE and only then hand the banked things over (what doesn't fit
 * stays in the row until the player makes room and types {@code /hcm leave}). A RETURN row is
 * never applied twice, and the row is finished only after the player is home — so a quit, a crash,
 * a stop or a failed write at any point leaves something the next join can finish, and nothing
 * can be duplicated.
 * The state machine itself is {@link SessionCore}; this class is the games' face of it.
 *
 * <p>World sessions never change attributes, walk or fly speed, invulnerability or collisions.
 * At most one session per player; a player with any saved-state row (even one still sending them
 * back) can't enter another.
 *
 * <p>The guards ({@link KitGuardListener}, {@link GamesWorldGuard}) are registered by
 * {@link #start()} — the service's start should call it, and the worlds-up pass and every entry
 * call it too, so a session is never unguarded — and removed by {@link #stop()}. Recovery that
 * must work with the games off is {@link SessionRecoveryListener}'s, which shares the same state
 * machine (and also ends every session in place when the plugin disables).
 */
public final class WorldSessions {

    private final GamesService games;
    private BukkitPort port;
    private KitGuardListener guard;
    private GamesWorldGuard worldGuard;
    private BukkitTask watchdog;

    public WorldSessions(GamesService games) {
        this.games = games;
    }

    /**
     * Whether there is a server to hold sessions on. The framework's own tests run the service
     * without a plugin; there, nobody is ever in a world game and every call here is a no-op.
     */
    private boolean live() {
        return games.plugin() != null;
    }

    /** The plugin's one state machine (shared with the recovery listener), built on first use. */
    BukkitPort port() {
        if (port == null) {
            port = BukkitPort.of(games.plugin());
        }
        return port;
    }

    private SessionCore<Player, ItemStack> core() {
        return port().core();
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Register the session guard and the Games-world guard, and start the watchdog that ends the
     * sessions of a game that has been switched off. Safe to call again.
     */
    public void start() {
        if (guard != null || !live()) {
            return;
        }
        var plugin = games.plugin();
        try {
            guard = new KitGuardListener(games, this, port());
            worldGuard = new GamesWorldGuard(plugin);
            plugin.getServer().getPluginManager().registerEvents(guard, plugin);
            plugin.getServer().getPluginManager().registerEvents(worldGuard, plugin);
            watchdog = plugin.getServer().getScheduler().runTaskTimer(plugin,
                    () -> port().safely("the session watchdog", this::watch), 20L, 20L);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Games: could not start the world-session guard", e);
        }
    }

    /**
     * The whole module stops: end every session — in place with no teleport while the server is
     * stopping or the plugin disabling (RETURN, sent home at the next start or join), otherwise
     * (games switched off by a reload) the full leave with a synchronous teleport — and remove the
     * guards. A single game switched off is {@link #leaveAll} with GAME_OFF instead.
     */
    public void stop() {
        if (port != null) {
            port.safely("ending the world sessions", () -> port.core().stop());
        }
        if (guard != null) {
            HandlerList.unregisterAll(guard);
            guard = null;
        }
        if (worldGuard != null) {
            HandlerList.unregisterAll(worldGuard);
            worldGuard = null;
        }
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    /**
     * Once the worlds are up (the enable's one-tick pass): send online players with a RETURN row
     * home, restore any ACTIVE row (a /reload), and sweep leftover game entities.
     */
    public void worldsReady() {
        if (!live()) {
            return;
        }
        start();
        BukkitPort p = port();
        p.safely("the worlds-up pass", () -> {
            p.core().worldsReady(new ArrayList<>(games.plugin().getServer().getOnlinePlayers()));
            int swept = WorldEntities.sweepAll(owner -> p.core().session(owner) != null);
            if (swept > 0) {
                games.plugin().getLogger().info("Games: removed " + swept + " leftover game entit"
                        + (swept == 1 ? "y." : "ies."));
            }
        });
    }

    /** A game that's closed can't keep players: end its sessions (GAME_OFF) within a second. */
    private void watch() {
        SessionCore<Player, ItemStack> core = core();
        if (core.none()) {
            return;
        }
        for (Player p : core.players()) {
            Session s = core.session(p.getUniqueId());
            if (s == null || s.phase() != Session.Phase.ACTIVE) {
                continue;
            }
            Game g = games.game(s.gameId());
            if (g == null || !games.enabled(g)) {
                core.leave(p, EndReason.GAME_OFF);
            }
        }
    }

    // ---- the games' API -------------------------------------------------------------------------

    /**
     * Take the player into {@code game} at {@code start}. Refused (false, player told) when the
     * game is closed or a scheduled restart is minutes away ({@link #entryRefusal}), when they
     * are already in a session or have a saved-state row, are dead, asleep, riding, gliding,
     * falling, burning, in water or lava, or were hurt in the last 5 seconds. A refusal before the
     * state machine is reached takes and moves nothing.
     *
     * @param ref     the course (or other) the session is for
     * @param onReady run once the player is in, saved, cleared: give the kit and start here
     * @return whether the entry started (it may still be called off on arrival; the player is told)
     */
    public boolean enter(Player player, Game game, String ref, Location start, Consumer<Player> onReady) {
        if (player == null || game == null || start == null || start.getWorld() == null) {
            return false;
        }
        start(); // the guard is up before anyone is in a session
        Refusal refusal = entryRefusal(game);
        if (refusal != null) {
            games.tell(player, refusal);
            return false;
        }
        String[] why = {SessionCore.CANT_START};
        port().safely("starting a game",
                () -> why[0] = core().enter(player, game.id(), ref, BukkitPort.place(start), hooks(game, onReady)));
        if (why[0] == null) {
            return true;
        }
        games.tell(player, SessionCore.IN_SESSION.equals(why[0]) ? Refusal.IN_SESSION : Refusal.of(why[0]));
        return false;
    }

    /**
     * Why nobody may enter {@code game} right now, before anything is taken or moved: it is
     * closed, or the server restarts in a few minutes and would cut the run off (the restart
     * hold, which covers every course and round of golf). {@code null} = go ahead. Needs no
     * server, so the framework's tests run it.
     */
    public Refusal entryRefusal(Game game) {
        if (game == null || !games.enabled(game)) {
            return Refusal.CLOSED;
        }
        return games.restartRefusal();
    }

    /** End the player's session: bank what turned up, restore, send them back (R2.3). */
    public void leave(Player player, EndReason reason) {
        if (player == null || reason == null || !live()) {
            return;
        }
        BukkitPort p = port();
        p.safely("leaving a game", () -> p.core().leave(player, reason));
    }

    /** End every session of {@code game} (it was switched off). */
    public void leaveAll(Game game, EndReason reason) {
        if (game == null || port == null) {
            return;
        }
        SessionCore<Player, ItemStack> core = core();
        for (Player p : core.players()) {
            Session s = core.session(p.getUniqueId());
            if (s != null && game.id().equals(s.gameId())) {
                leave(p, reason);
            }
        }
    }

    /**
     * The player's live session, or {@code null} when not in one. A session on its way in or out
     * is still returned (check {@link Session#phase()}); a saved-state row alone is not a session.
     */
    public Session session(Player player) {
        return player == null || !live() ? null : core().session(player.getUniqueId());
    }

    /**
     * Whether the player is back from any world game: no session, nothing on the way, and no
     * saved-state row still sending them back. A "Play again" button can wait for this ({@link
     * #enter} refuses until then anyway).
     */
    public boolean home(Player player) {
        return player != null && (!live() || core().home(player.getUniqueId()));
    }

    /** Every live session (for {@code /hcm games status}). */
    public List<Session> sessions() {
        return port == null ? List.of() : core().sessions();
    }

    /**
     * Teleport a session player as part of the game (a checkpoint, "go to my ball"), within the
     * session world. The exact destination is recorded so the guard knows this teleport is ours
     * (R2.8), and it becomes the player's safe point for a fall into the void. Same-tick when the
     * chunk is loaded. The player is taken off any vehicle first.
     *
     * @return whether the teleport was made (or, for an unloaded chunk, started)
     */
    public boolean teleport(Player player, Location to) {
        if (player == null) {
            return false;
        }
        Place place = BukkitPort.place(to);
        boolean[] made = {false};
        port().safely("a game's teleport", () -> made[0] = core().teleport(player, place));
        return made[0];
    }

    // ---- WP-CH (the Clubhouse) ----------------------------------------------------------------------

    /**
     * Hand the player's ACTIVE session to {@code to} ({@code ref} its course, or {@code ""}), in place:
     * nothing is restored, saved or cleared, and from now on its end, void and kit go to {@code to}.
     * A racer seated from the Clubhouse, or back in it after a race, stays in one session.
     *
     * @return whether it was handed over
     */
    public boolean passTo(Player player, Game to, String ref) {
        if (player == null || to == null || !live()) {
            return false;
        }
        boolean[] done = {false};
        port().safely("handing a session over", () -> done[0] = core().passTo(player, to.id(), ref, hooks(to, null)));
        return done[0];
    }

    /**
     * Empty a session player's inventory for another game's kit (a session handed over), keeping
     * everything that isn't a kit item: it is banked in the saved-state row's carry, as at the
     * session's end, and comes home with them (the Clubhouse review, #3). Never {@code clear()} a
     * session inventory: an auction win or a Mini can arrive in it mid-session.
     *
     * @return whether it was done (false: no ACTIVE session; nothing changed)
     */
    public boolean bankExtras(Player player) {
        if (player == null || !live()) {
            return false;
        }
        boolean[] done = {false};
        port().safely("keeping a player's things", () -> done[0] = core().bankExtras(player));
        return done[0];
    }

    /**
     * Take the kit items (and only those) off a player: the inventory, the cursor, the crafting grid
     * and the ender chest. What else they hold stays, for the session's end to bank.
     */
    public void stripKit(Player player) {
        if (player != null) {
            port().safely("taking a kit off", () -> BukkitStateAdapter.stripKit(player));
        }
    }

    /**
     * Put a session player in {@code mode} (a Clubhouse watcher's SPECTATOR, and ADVENTURE again), as
     * the session's own change: the game-mode guard keeps them in it for this session only, and every
     * way out puts back the mode they came in with (it is in their saved state).
     *
     * @return whether it was done (false: no ACTIVE session)
     */
    public boolean gameMode(Player player, org.bukkit.GameMode mode) {
        if (player == null || mode == null || !live()) {
            return false;
        }
        boolean[] done = {false};
        port().safely("a game's game mode", () -> done[0] = port().gameMode(player, mode));
        return done[0];
    }

    // ---- end WP-CH --------------------------------------------------------------------------------

    /** Run {@code action}, a dismount the game makes itself (re-seating in a boat), past the guard. */
    public void ownDismount(Player player, Runnable action) {
        if (player == null) {
            action.run();
            return;
        }
        core().ownDismount(player, action);
    }

    // ---- admin ----------------------------------------------------------------------------------

    /**
     * {@code /hcm games saved <player> show|restore|return|discard confirm} (§7.6). Takes the
     * words after {@code saved} (a leading {@code games saved} or {@code saved} is skipped too).
     */
    public void adminSaved(CommandSender sender, String[] args) {
        new SavedStateAdmin(port()).handle(sender, args);
    }

    /** Tab completion for {@link #adminSaved}, with the same arguments. */
    public List<String> adminSavedTab(CommandSender sender, String[] args) {
        return SavedStateAdmin.tab(sender, args);
    }

    // ---- with or without the games service (recovery never switches off, §7.6) ------------------

    /**
     * {@code /hcm leave}: end the player's world game, or finish a return that didn't (the trip
     * home, or the things kept for them once they have made room), or say they're not in a game.
     * Through the plugin's one state machine, so it works with the games off or failed to start.
     */
    public static void leaveCommand(HomeCraftManagement plugin, Player player) {
        BukkitPort port = sharedPort(plugin, player);
        if (port != null) {
            port.safely("/hcm leave", () -> port.core().leave(player, EndReason.COMMAND));
        }
    }

    /**
     * {@code /hcm games saved <player> show|restore|return|discard confirm}, with or without the
     * games service. Takes the words after {@code saved}.
     */
    public static void savedCommand(HomeCraftManagement plugin, CommandSender sender, String[] args) {
        BukkitPort port = sharedPort(plugin, sender);
        if (port != null) {
            port.safely("/hcm games saved", () -> new SavedStateAdmin(port).handle(sender, args));
        }
    }

    private static BukkitPort sharedPort(HomeCraftManagement plugin, CommandSender sender) {
        try {
            return BukkitPort.of(plugin);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Games: saved-state recovery is not available", e);
            sender.sendMessage(Text.of("&cThat can't be done right now - see the console."));
            return null;
        }
    }

    // ---- internals ------------------------------------------------------------------------------

    /** The session's calls into its game, each inside the game's guard (a throw closes the game, not us). */
    private SessionCore.Hooks<Player> hooks(Game game, Consumer<Player> onReady) {
        return new SessionCore.Hooks<>() {
            @Override
            public void ready(Player player) {
                if (onReady != null) {
                    games.guard(game, () -> onReady.accept(player));
                }
                offIfFailed(player);
            }

            @Override
            public void ended(Player player, EndReason reason) {
                games.guard(game, () -> game.onSessionEnd(player, reason));
            }

            @Override
            public void voided(Player player) {
                games.guard(game, () -> game.onVoid(player));
                offIfFailed(player);
            }

            private void offIfFailed(Player player) {
                if (games.failed(game)) {
                    core().leave(player, EndReason.GAME_OFF);
                }
            }
        };
    }

    /** The kit guard calls a game's kit use through here, so a failure ends the session too. */
    void kitUse(Player player, Game game, String action, boolean leftClick) {
        games.guard(game, () -> game.onKitUse(player, action, leftClick));
        if (games.failed(game)) {
            core().leave(player, EndReason.GAME_OFF);
        }
    }
}
