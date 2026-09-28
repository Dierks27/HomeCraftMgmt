package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.world.Session;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.RecordComponent;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The ordered checks before any play (spec §4.1, R1.10, R3.9). One gate for every game, so no
 * game can forget a limit, and the order is the order a player is told things in:
 * <ol start="0">
 *   <li>in a world session, only that session's game ("Finish your game first");</li>
 *   <li>{@code games.enabled}, the game's own switch, not failed;</li>
 *   <li>{@code hcm.games.play}, and {@code hcm.games.chance} for a game of chance;</li>
 *   <li>an economy world, a {@code games.worlds} world or a {@code games.play_worlds} world;</li>
 *   <li>games of chance only: Take a break's pause;</li>
 *   <li>games of chance only: the click cooldown (silent);</li>
 *   <li>games of chance only: the game's {@code daily_limit};</li>
 *   <li>games of chance only: the day's token limit (own, parent/admin, server) with this stake;</li>
 *   <li>the balance covers the stake.</li>
 * </ol>
 * Opening a screen runs steps 0-4 ({@link #open}); putting tokens in runs all of them
 * ({@link #check}); putting MORE in mid-round (a Double, a Coin Flip confirm) re-runs 2, 4, 7 and
 * 8 for the extra amount at the moment of the debit ({@link #extra}).
 *
 * <p>Checking never starts the cooldown — a screen may ask the gate whether to light a button as
 * often as it likes. The cooldown starts when tokens actually go in: {@link ChanceRounds} calls
 * {@link #clicked} after every play it settles or opens.
 *
 * <p>If Take a break or today's plays can't be read, a game of chance is refused ("Games of
 * chance are closed right now") rather than let through: it fails closed.
 */
public final class PlayGate {

    /** The click cooldown never goes below this, whatever {@code games.click_cooldown_ms} says. */
    public static final long COOLDOWN_FLOOR_MS = 250;
    /** Every game. */
    public static final String PERMISSION_PLAY = "hcm.games.play";
    /** Games of chance (and the Scratch Ticket, Crates and token Card Packs). */
    public static final String PERMISSION_CHANCE = Breaks.PERMISSION_CHANCE;

    private final GamesService games;
    /** Player → when tokens last went into a game of chance (the cooldown). */
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public PlayGate(GamesService games) {
        this.games = games;
    }

    /** Steps 0-4: may the player open this game's screen? {@code null} = yes. */
    public Refusal open(Player player, Game game) {
        Refusal r = basics(player, game);
        if (r == null && chance(game)) {
            Breaks.Today today = today(player);
            r = today == null ? Refusal.CHANCE_CLOSED : pause(today);
        }
        return r;
    }

    /** Every step, for putting {@code stake} tokens in. {@code null} = go ahead. */
    public Refusal check(Player player, Game game, int stake) {
        Refusal r = basics(player, game);
        if (r != null) {
            return r;
        }
        if (chance(game)) {
            Breaks.Today today = today(player);
            if (today == null) {
                return Refusal.CHANCE_CLOSED;
            }
            r = pause(today);
            if (r == null) {
                r = cooldown(player);
            }
            if (r == null) {
                r = dailyLimit(player, game);
            }
            if (r == null && today.over(stake)) {
                r = Refusal.personalLimit(today.limit());
            }
            if (r != null) {
                return r;
            }
        }
        return balance(player, stake);
    }

    /** Steps 2, 4, 7 and 8 for {@code extra} more tokens mid-round. {@code null} = go ahead. */
    public Refusal extra(Player player, Game game, int extra) {
        Refusal r = permission(player, game);
        if (r != null) {
            return r;
        }
        if (chance(game)) {
            Breaks.Today today = today(player);
            if (today == null) {
                return Refusal.CHANCE_CLOSED;
            }
            r = pause(today);
            if (r == null && today.over(extra)) {
                r = Refusal.personalLimit(today.limit());
            }
            if (r != null) {
                return r;
            }
        }
        return balance(player, extra);
    }

    /**
     * Start the click cooldown: tokens just went into a game of chance. {@link ChanceRounds} does
     * this for every play it settles or opens; a game that moves tokens another way (Coin Flip's
     * two-player flip) calls it itself.
     */
    public void clicked(Player player) {
        if (player != null) {
            lastClick.put(player.getUniqueId(), games.host().clock().nowMillis());
        }
    }

    /** Forget the player's cooldown (they left). */
    void forget(UUID player) {
        lastClick.remove(player);
    }

    /**
     * Whether games may be played in this world: an economy world, a {@code games.worlds} world
     * (the world games' own) or a {@code games.play_worlds} world (a hub, say).
     */
    public boolean worldAllowed(World world) {
        if (world == null) {
            return false;
        }
        if (games.host().economyWorld(world)) {
            return true;
        }
        GamesConfig.Common common = games.config().common();
        return named(common.worlds(), world.getName()) || named(common.playWorlds(), world.getName());
    }

    /**
     * A game's {@code daily_limit} from its live settings (every game of chance's settings record
     * has one), or -1 when it has none.
     */
    public int dailyLimit(Game game) {
        GameSpec<?> spec = games.spec(game.id());
        if (spec == null) {
            return -1;
        }
        Object v = component(games.config().settings(spec), "dailyLimit");
        return v instanceof Integer n ? n : -1;
    }

    // ---- the steps ----------------------------------------------------------------------------

    /** Steps 0-3, every game. */
    private Refusal basics(Player player, Game game) {
        Refusal r = session(player, game);
        if (r == null) {
            r = open(game);
        }
        if (r == null) {
            r = permission(player, game);
        }
        if (r == null) {
            r = world(player);
        }
        return r;
    }

    /** Step 0: a player in a world session may only use that session's game. */
    private Refusal session(Player player, Game game) {
        Session s = games.sessions().session(player);
        return s != null && !s.gameId().equals(game.id()) ? Refusal.IN_SESSION : null;
    }

    /** Step 1: the games, the game's own switch, and not failed. */
    private Refusal open(Game game) {
        if (games.failed(game)) {
            return Refusal.BROKEN;
        }
        return games.enabled(game) ? null : Refusal.CLOSED;
    }

    /** Step 2: the permissions (never more detail: a parent may have switched it off). */
    private static Refusal permission(Player player, Game game) {
        if (!player.hasPermission(PERMISSION_PLAY)) {
            return Refusal.NO_GAMES;
        }
        return chance(game) && !player.hasPermission(PERMISSION_CHANCE) ? Refusal.NO_CHANCE : null;
    }

    /** Step 3: the world. */
    private Refusal world(Player player) {
        return worldAllowed(player.getWorld()) ? null : Refusal.WORLD;
    }

    /** Step 4: Take a break's pause. */
    private Refusal pause(Breaks.Today today) {
        return today.paused(games.host().clock().nowMillis())
                ? Refusal.paused(Breaks.untilText(games.host().clock(), today.pausedUntil())) : null;
    }

    /** Step 5: the click cooldown, silent. */
    private Refusal cooldown(Player player) {
        Long last = lastClick.get(player.getUniqueId());
        if (last == null) {
            return null;
        }
        long gap = Math.max(COOLDOWN_FLOOR_MS, games.config().common().clickCooldownMs());
        return games.host().clock().nowMillis() - last < gap ? Refusal.SILENT : null;
    }

    /** Step 6: the game's own daily limit, counted from today's rounds. */
    private Refusal dailyLimit(Player player, Game game) {
        int limit = dailyLimit(game);
        if (limit < 0) {
            return null;
        }
        try {
            int plays = games.dao().playsToday(player.getUniqueId(), game.id(), games.host().clock().dayKey());
            return plays >= limit ? Refusal.dailyLimit(game.name()) : null;
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not count today's " + game.id() + " plays", e);
            return Refusal.CHANCE_CLOSED;
        }
    }

    /** Step 8: the balance. */
    private Refusal balance(Player player, int stake) {
        if (stake <= 0) {
            return null;
        }
        int have = games.host().balance(player.getUniqueId());
        return have < stake ? Refusal.needMore(stake - have) : null;
    }

    private Breaks.Today today(Player player) {
        Breaks breaks = games.breaks();
        return breaks == null ? null : breaks.today(player.getUniqueId());
    }

    private static boolean chance(Game game) {
        return game.kind().chance();
    }

    private static boolean named(List<String> worlds, String name) {
        for (String w : worlds) {
            if (w != null && w.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A record component's value by name ({@code dailyLimit}, {@code stakes}), or {@code null}.
     * Each game's settings record is its own type; the framework reads the few components every
     * game of chance shares by name rather than making every owner implement an interface.
     */
    static Object component(Object record, String name) {
        if (record == null || !record.getClass().isRecord()) {
            return null;
        }
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (c.getName().equals(name)) {
                try {
                    return c.getAccessor().invoke(record);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
