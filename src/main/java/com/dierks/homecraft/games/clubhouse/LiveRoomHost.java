package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.BukkitWorldPort;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenMetaDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The running server as {@link ClubhouseRoom} sees it: the clock, the Games world, the database and
 * who is where. Main thread only; nothing here throws on purpose (a failure is logged).
 */
final class LiveRoomHost implements RoomHost {

    /** Where someone moved out of the box's way reads why. */
    static final String MOVED = "&7The Clubhouse is being built, so we moved you somewhere safe.";

    private final Clubhouse game;
    private final Map<String, BukkitWorldPort> ports = new HashMap<>();
    private final Map<String, World> portWorlds = new HashMap<>();

    LiveRoomHost(Clubhouse game) {
        this.game = game;
    }

    private GamesService games() {
        return game.games();
    }

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

    /** Fresh Courses' settings (its world, slots, keep area and safe spot), or {@code null}. */
    private DailySettings fresh() {
        try {
            return games().settings(DailyCourses.SPEC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The Games world: {@code games.fresh.world}, or the first of {@code games.worlds}. */
    @Override
    public String worldName() {
        return gamesWorld(games());
    }

    /** The Games world, as the arena finds its own. */
    static String gamesWorld(GamesService g) {
        try {
            DailySettings d = g.settings(DailyCourses.SPEC);
            if (d != null && !d.world().isBlank()) {
                return d.world();
            }
        } catch (RuntimeException ignored) {
            // fall through to games.worlds
        }
        for (String w : g.config().common().worlds()) {
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

    @Override
    public String meta(String key) throws Exception {
        return new GenMetaDao(game.plugin().database()).get(key);
    }

    @Override
    public void meta(String key, String value) throws Exception {
        new GenMetaDao(game.plugin().database()).set(key, value);
    }

    @Override
    public boolean gamesWorld(String name) {
        if (name == null) {
            return false;
        }
        for (String w : games().config().common().worlds()) {
            if (w != null && w.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
        return ClubhouseRegions.problems(box, d, arena(games()), handBuilt(), facts);
    }

    /** The Falling Floors arena's box, as configured (on or off: its blocks may stand). */
    static List<Regions.Extra> arena(GamesService g) {
        try {
            return List.of(new Regions.Extra(ArenaRegions.NAME, g.settings(FallingFloors.SPEC).box()));
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
            logger().log(Level.WARNING, "Clubhouse: could not read the hand-built courses to check its box", e);
            return null;
        }
    }

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

    /** To {@code games.fresh.safe_spot}, or the world's spawn, as Fresh Courses moves people. */
    @Override
    public void toSafety(UUID player, String world) {
        Player p = Bukkit.getPlayer(player);
        World w = Bukkit.getWorld(world);
        if (p == null || w == null || games().sessions().session(p) != null) {
            return; // a player in a game is its session's to move
        }
        DailySettings d = fresh();
        double[] spot = d == null ? null : d.safeSpot();
        Location at = p.getLocation();
        Location to = spot != null ? new Location(w, spot[0], spot[1], spot[2], at.getYaw(), at.getPitch())
                : w.getSpawnLocation();
        if (p.teleport(to)) {
            p.sendMessage(Text.of(MOVED));
        }
    }
}
