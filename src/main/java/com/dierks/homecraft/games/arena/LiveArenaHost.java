package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.arena.rules.ArenaScoring;
import com.dierks.homecraft.games.arena.rules.Feet;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseRegions;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenMetaDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The running server as {@link ArenaService} sees it: the clock, the world, the database, the
 * players, and everything said and shown to them. Main thread only; nothing here throws on
 * purpose: a failure is logged, and a player who went offline is simply skipped.
 *
 * <p>The few server lookups the teleport and the kit route on ({@link #online}, {@link #bukkitWorld},
 * {@link #inSession}, the two teleports and {@link #fill}) are package-private methods, so a test
 * can answer them without a server.
 */
class LiveArenaHost implements ArenaHost {

    /** Where someone moved out of the box's way reads why. */
    static final String MOVED = "&7The Falling Floors arena is being fixed, so we moved you somewhere safe.";

    private final FallingFloors game;
    private final Map<String, BukkitWorldPort> ports = new HashMap<>();
    private final Map<String, World> portWorlds = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    LiveArenaHost(FallingFloors game) {
        this.game = game;
    }

    private GamesService games() {
        return game.games();
    }

    // ---- time, settings, world ------------------------------------------------------------------

    @Override
    public long now() {
        return games().clock().nowMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public Logger logger() {
        return game.log();
    }

    @Override
    public FallingFloorsSettings settings() {
        return game.settings();
    }

    /** Fresh Courses' settings (its world, slots, keep area and safe spot), or {@code null}. */
    private DailySettings fresh() {
        try {
            return games().settings(DailyCourses.SPEC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String worldName() {
        DailySettings d = fresh();
        if (d != null && !d.world().isBlank()) {
            return d.world();
        }
        for (String w : games().config().common().worlds()) {
            if (w != null && !w.isBlank()) {
                return w;
            }
        }
        return "";
    }

    @Override
    public WorldPort world(String name) {
        World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        if (w == null) {
            return null;
        }
        String key = w.getName();
        if (portWorlds.get(key) != w) {
            portWorlds.put(key, w);
            ports.put(key, new BukkitWorldPort(game.plugin(), w));
        }
        return ports.get(key);
    }

    /** The week the arena's shape belongs to: Fresh Courses' week, turning at its rollover. */
    @Override
    public long weekKey(long now) {
        Edition ed = DailyLookup.edition(games());
        return ed.weekKey(ed.day(now));
    }

    @Override
    public Long secret() {
        try {
            return games().dao().secret();
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public String claim() throws Exception {
        return new GenMetaDao(game.plugin().database()).get(ArenaService.CLAIM_KEY);
    }

    @Override
    public void claim(String value) throws Exception {
        new GenMetaDao(game.plugin().database()).set(ArenaService.CLAIM_KEY, value);
    }

    @Override
    public List<String> regionProblems(Box box, String world) {
        DailySettings d = fresh();
        WorldPort port = world(world);
        Regions.WorldFacts facts = null;
        if (port != null) {
            boolean listed = false;
            for (String w : games().config().common().worlds()) {
                listed |= w != null && w.equalsIgnoreCase(port.name());
            }
            facts = new Regions.WorldFacts(port.name(), listed, port.minHeight(), port.maxHeight(), port.border(),
                    port.spawn(), d == null ? null : d.safeSpot());
        }
        return ArenaRegions.problems(box, d, clubhouse(), handBuilt(), facts);
    }

    /** WP-CH: the Clubhouse's room as configured (on or off: its blocks may stand); none when unreadable. */
    private List<Regions.Extra> clubhouse() {
        try {
            return ClubhouseRegions.extras(games().settings(Clubhouse.SPEC));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Every hand-built course's footprint, or {@code null} when the rows can't be read now. */
    private List<Regions.Area> handBuilt() {
        try {
            List<GamesDao.CourseRow> rows = new ArrayList<>(games().dao().courses(Slots.GAME_TRIALS));
            rows.addAll(games().dao().courses(Slots.GAME_GOLF));
            return Regions.handBuilt(rows);
        } catch (SQLException | RuntimeException e) {
            logger().log(Level.WARNING, "Falling Floors: could not read the hand-built courses to check its box", e);
            return null;
        }
    }

    // ---- people ---------------------------------------------------------------------------------

    @Override
    public List<Person> people() {
        List<Person> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            Session s = games().sessions().session(p);
            out.add(new Person(p.getUniqueId(), p.getName(), l.getWorld() == null ? "" : l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), s == null ? null : s.gameId(), s == null ? null : s.ref()));
        }
        return out;
    }

    @Override
    public boolean anyoneOnline() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    @Override
    public double mspt() {
        return Bukkit.getAverageTickTime();
    }

    @Override
    public boolean holding() {
        return games().restartHold().holding(now());
    }

    @Override
    public long nextRestart() {
        return games().restartHold().next(now());
    }

    /** The next restart's time ("4:00 PM"): a round may be held for it before the games' own hold. */
    @Override
    public String heldFor() {
        RestartHold h = games().restartHold();
        long next = h.next(now());
        return next < 0 ? null : h.clock(next);
    }

    @Override
    public Feet feet(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return null;
        }
        Location l = p.getLocation();
        return new Feet(l.getX(), l.getY(), l.getZ(), 0);
    }

    /**
     * A player in the arena's session goes through the session's own teleport (it also becomes
     * their safe point); anyone else (an admin watching) through a plain one.
     */
    @Override
    public boolean teleport(UUID player, String world, ArenaSite.Spot spot) {
        Player p = online(player);
        World w = world == null ? null : bukkitWorld(world);
        if (p == null || w == null || spot == null) {
            return false;
        }
        Location to = new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), 0);
        return inSession(p) ? sessionTeleport(p, to) : plainTeleport(p, to);
    }

    // ---- the server lookups (a test answers them) ----------------------------------------------

    /** The player, when online. */
    Player online(UUID player) {
        return player == null ? null : Bukkit.getPlayer(player);
    }

    /** The world, when loaded. */
    World bukkitWorld(String name) {
        return Bukkit.getWorld(name);
    }

    /** Whether the player is in a world session (the arena's: a player is in one at a time). */
    boolean inSession(Player p) {
        return games().sessions().session(p) != null;
    }

    boolean sessionTeleport(Player p, Location to) {
        return games().sessions().teleport(p, to);
    }

    boolean plainTeleport(Player p, Location to) {
        return p.teleport(to);
    }

    /** To {@code games.fresh.safe_spot}, or the world's spawn, as Fresh Courses moves people. */
    @Override
    public void toSafety(UUID player, String world) {
        Player p = Bukkit.getPlayer(player);
        World w = Bukkit.getWorld(world);
        if (p == null || w == null) {
            return;
        }
        DailySettings d = fresh();
        double[] spot = d == null ? null : d.safeSpot();
        Location at = p.getLocation();
        Location to = spot != null ? new Location(w, spot[0], spot[1], spot[2], at.getYaw(), at.getPitch())
                : w.getSpawnLocation();
        p.teleport(to);
        p.sendMessage(Text.of(MOVED));
    }

    @Override
    public void endSession(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && games().sessions().session(p) != null) {
            games().sessions().leave(p, EndReason.FINISH);
        }
    }

    @Override
    public void collidable(UUID player, boolean on) {
        Player p = online(player);
        if (p != null) {
            p.setCollidable(on);
        }
    }

    @Override
    public NoPush noPush() {
        return games().noPush();
    }

    // ---- the kit --------------------------------------------------------------------------------

    /**
     * The kit, key facts in the NAMES: in the lobby "Ready" (grey until pressed, then green) and
     * "Play solo" when it is offered; always "Leave game" in the last slot (two clicks, the kit
     * guard's). Nothing else is ever handed out.
     */
    @Override
    public void kit(UUID player, Kit kit) {
        Player p = online(player);
        if (p == null || kit == null || !inSession(p)) {
            return; // only the arena's own players: nobody else's inventory is ever touched
        }
        fill(p, kit);
    }

    /**
     * Take the old kit off and hand out {@code kit}: the kit items only, never the player's own things
     * (an auction win or a Mini can arrive mid-session; the session's end banks it).
     */
    void fill(Player p, Kit kit) {
        games().sessions().stripKit(p);
        PlayerInventory inv = p.getInventory();
        if (kit.kind() == KitKind.LOBBY) {
            KitItems.put(inv, 0, KitItems.item(game, FallingFloors.READY, kit.ready() ? Material.LIME_DYE : Material.GRAY_DYE,
                    kit.ready() ? FloorsText.KIT_READY_ON : FloorsText.KIT_READY,
                    FloorsText.readyLore(settings().minPlayers()).toArray(new String[0])));
            if (kit.solo()) {
                KitItems.put(inv, 4, KitItems.item(game, FallingFloors.SOLO, Material.CLOCK, FloorsText.KIT_SOLO,
                        "&7A round just for you.", "&7Keep moving to last longer!"));
            }
        }
        KitItems.put(inv, 8, KitItems.item(game, FallingFloors.LEAVE, Material.BARRIER, FloorsText.KIT_LEAVE,
                "&7Click twice to leave.", "&7Your things come back."));
        inv.setHeldItemSlot(kit.kind() == KitKind.LOBBY ? 0 : 1);
    }

    // ---- words, titles, sounds, bars ------------------------------------------------------------

    @Override
    public void tell(UUID player, String line) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && line != null) {
            p.sendMessage(Text.of(line));
        }
    }

    @Override
    public void actionBar(UUID player, String line) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && line != null) {
            p.sendActionBar(Text.of(line));
        }
    }

    @Override
    public void title(UUID player, String big, String small, int stayTicks) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small == null ? "" : small), Title.Times.times(Duration.ZERO,
                    Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(200))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    @Override
    public void sound(UUID player, Cue cue) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || cue == null) {
            return;
        }
        try {
            switch (cue) {
                case COUNT -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
                case GO -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 2.0f);
                case OUT -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 1.0f);
                case END -> p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }

    @Override
    public void bar(UUID player, String title, float progress) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        float at = Math.max(0f, Math.min(1f, progress));
        BossBar bar = bars.get(player);
        if (bar == null) {
            bar = BossBar.bossBar(Text.of(title), at, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(player, bar);
            p.showBossBar(bar);
        } else {
            bar.name(Text.of(title));
            bar.progress(at);
        }
    }

    @Override
    public void hideBar(UUID player) {
        BossBar bar = bars.remove(player);
        Player p = Bukkit.getPlayer(player);
        if (bar != null && p != null) {
            p.hideBossBar(bar);
        }
    }

    /** Every bar away (the game stopped). */
    void hideAllBars() {
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
        bars.clear();
    }

    @Override
    public String name(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            return p.getName();
        }
        try {
            OfflinePlayer o = Bukkit.getOfflinePlayer(player);
            return o.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- results --------------------------------------------------------------------------------

    /**
     * Boards, rewards and the E4 achievement for a played-out round ({@link FloorsRewards}): scores
     * for everyone who played it, tokens only to players still online (as every skill game pays).
     */
    @Override
    public void scored(RoundResult result, long week) {
        GamesService g = games();
        int bonus = g.featured().isFeatured(game.id()) ? g.config().common().featuredBonus() : 0;
        FallingFloorsSettings s = settings();
        FloorsRewards.apply(result, g.clock().dayKey(), week, s.rewards(), bonus, new FloorsRewards.Ledger() {
            @Override
            public Long best(UUID player, String board, long value) {
                ScoreResult r = g.scores().submit(player, game.id(), board, value, false);
                if (!r.personalBest()) {
                    return null;
                }
                return r.previous() == null ? -1L : r.previous();
            }

            @Override
            public void addPoints(UUID player, String board, long delta) {
                try {
                    g.dao().addPoints(player, game.id(), board, delta, now());
                } catch (SQLException | RuntimeException e) {
                    logger().log(Level.WARNING, "Falling Floors: could not add a win to " + board, e);
                }
            }

            @Override
            public int pay(UUID player, ArenaScoring.Claim c) {
                Player p = Bukkit.getPlayer(player);
                return p == null ? 0 : g.rewards().pay(p, game, TokenService.Source.GAMES_FLOORS, c.kind(), c.ref(),
                        c.tokens(), s.dailyCap(), c.detail());
            }

            @Override
            public int payWhole(UUID player, ArenaScoring.Claim c) {
                Player p = Bukkit.getPlayer(player);
                return p == null ? 0 : g.rewards().payWhole(p, game, TokenService.Source.GAMES_FLOORS, c.kind(),
                        c.ref(), c.tokens(), s.dailyCap(), c.detail(), FloorsText.MILESTONE_LIMIT);
            }

            @Override
            public void lastedMinute(UUID player) {
                Player p = Bukkit.getPlayer(player);
                if (p != null) {
                    g.tellProgress(l -> l.floorsLastedMinute(p));
                }
            }

            @Override
            public void tell(UUID player, String line) {
                LiveArenaHost.this.tell(player, line);
            }
        });
    }
}
