package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.clubhouse.ClubBench;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The cross-feature journeys' bench in {@code games.trial}: the framework ({@link GamesBench}) with Time
 * Trials, the Weekly Cup and the Clubhouse built from their specs, over ONE rail of world sessions
 * ({@link SessionBench}: the real state machine, so every player's things, game mode and saved-state row
 * go through every hand-over), ONE no-push team on a fake main scoreboard (shared by race mode, the
 * Clubhouse and ride along), the Clubhouse ({@link ClubBench}, its door {@link ClubBench.SessionDoor}),
 * race mode ({@link RaceBench} with the rail under it) and ride along (a real {@link Riders} over a port
 * backed by all of the above, installed as Time Trials' own).
 *
 * <p>Every player is a {@link GamesBench} player plus a rail body with the same id: at home in the
 * economy world, holding "diamond x3" and their own sword, an ender-chest item, xp, their own game mode,
 * and on another plugin's scoreboard team.
 */
final class JourneyBench implements AutoCloseable {

    static final long MIN = 60_000L;
    /** Laps' two-lap loop in the Games world (a boat course). */
    static final Course LOOP = LapsTest.loop(6, 2);
    /** Open ice at the loop's height. */
    static final RaceGrid.Surface ICE = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;

    /** A main scoreboard's teams: entry to team. */
    static final class Board implements NoPush.Board {
        final Map<String, String> teamOf = new HashMap<>();
        final Set<String> teams = new HashSet<>();

        @Override
        public String teamOf(String entry) {
            return teamOf.get(entry);
        }

        @Override
        public void ensureNoCollision(String team) {
            teams.add(team);
        }

        @Override
        public boolean exists(String team) {
            return teams.contains(team);
        }

        @Override
        public void add(String team, String entry) {
            if (teams.contains(team)) {
                teamOf.put(entry, team);
            }
        }

        @Override
        public void remove(String team, String entry) {
            if (team.equals(teamOf.get(entry))) {
                teamOf.remove(entry);
            }
        }

        @Override
        public Set<String> entries(String team) {
            Set<String> out = new HashSet<>();
            teamOf.forEach((e, t) -> {
                if (t.equals(team)) {
                    out.add(e);
                }
            });
            return out;
        }
    }

    final GamesBench bench;
    GamesService games;
    TimeTrials trials;
    WeeklyCup cup;
    final SessionBench rail;
    final Board board = new Board();
    NoPush pushes = new NoPush(() -> board);
    ClubBench club;
    ClubBench.SessionDoor door;
    RaceBench race;
    final RaceBench.Told told = new RaceBench.Told();
    Riders riders;
    RidePort ridePort;
    Course loop;
    /** Everyone's player, by id, and who is online (the visibility's viewers). */
    final Map<UUID, Player> players = new LinkedHashMap<>();
    final List<UUID> online = new ArrayList<>();
    final Map<UUID, String> teams = new HashMap<>();
    final Map<UUID, String> modes = new HashMap<>();
    final Map<UUID, SessionBench.Spot> homes = new HashMap<>();
    /** The server's boats: a driver's boat now, and who sits in each. */
    final Map<UUID, Entity> boats = new HashMap<>();
    final Map<Entity, List<UUID>> seats = new HashMap<>();
    /** Who can't be pushed (or collide with a racing boat) now. */
    final Set<UUID> notCollidable = new HashSet<>();
    private int homesMade;

    JourneyBench(long now, List<GameSpec<?>> specs, Object... idThenSettings) {
        bench = new GamesBench(now, specs, idThenSettings);
        board.teams.add("red");
        board.teams.add("blue");
        rail = new SessionBench(bench.games(), bench.dao());
        wire();
    }

    /** Wire the framework's games (again after a reboot): the one rail, team, Clubhouse, race bench and ride along. */
    void wire() {
        games = bench.games();
        rail.games(games);
        games.progress(told);
        trials = (TimeTrials) games.game(TimeTrials.SPEC.id());
        cup = games.game("cup") instanceof WeeklyCup c ? c : null;
        trials.raceMode().pushes(pushes);
        if (games.game(Clubhouse.SPEC.id()) != null) {
            club = new ClubBench(games, rail, pushes, players::get, () -> List.copyOf(online));
            door = club.door();
            trials.raceMode().door(door);
        } else {
            club = null;
            door = null;
            trials.raceMode().door(null);
        }
        race = new RaceBench(trials);
        race.door = door;
        race.rail = new RaceRail();
        race.boats = new BoatHooks();
        for (Player p : players.values()) {
            race.add(p);
        }
        ridePort = new RidePort();
        riders = new Riders(ridePort);
        trials.riders(riders); // SEAM S1: the ride along the tests drive is Time Trials' own
        if (loop != null) {
            loop = trials.course(loop.id());
        }
    }

    // ---- setup -------------------------------------------------------------------------------------

    /** Save {@code c} as a Time Trials course and read it back as Time Trials does. */
    Course course(Course c) throws SQLException {
        bench.dao().saveCourse(new GamesDao.CourseRow(c.id(), "trials", c.kind().id(), c.name(), c.world(),
                c.enabled(), CourseCodec.encode(c), c.rev(), 0, 0), false);
        trials.forget();
        return trials.course(c.id());
    }

    /** The loop saved and read back. */
    Course loop() throws SQLException {
        loop = course(LOOP);
        return loop;
    }

    /**
     * A player: on the framework bench (online, 20 tokens) and on the rail at their own home in
     * {@code mode}, holding "diamond x3", their own sword and an ender-chest item, with xp, on
     * {@code team}.
     */
    Player player(String name, String mode, String team) {
        return player(name, mode, team, List.of("diamond x3", "sword:" + name));
    }

    /** {@link #player(String, String, String)} holding {@code items} (slot 0 up) instead. */
    Player player(String name, String mode, String team, List<String> items) {
        Player p = bench.player(name);
        UUID id = p.getUniqueId();
        bench.give(id, 20);
        SessionBench.Spot home = new SessionBench.Spot("world", 100 + 4 * homesMade++, 64, 100);
        rail.body(p, home, mode, items.toArray(new String[0]));
        rail.ender(id).add("ender:" + name);
        rail.xp(id, 12, 0.25f, 300);
        if (team != null) {
            board.teamOf.put(name, team);
        }
        players.put(id, p);
        online.add(id);
        teams.put(id, team);
        modes.put(id, mode);
        homes.put(id, home);
        race.add(p);
        return p;
    }

    static UUID id(Player p) {
        return p.getUniqueId();
    }

    // ---- the server's side -------------------------------------------------------------------------

    /**
     * The worlds handed out, held: a {@link Location} keeps its world only weakly, so a proxy nobody else
     * holds can be collected mid-journey and {@code getWorld()} throws "World unloaded".
     */
    private static final Map<String, World> WORLDS = new ConcurrentHashMap<>();

    /** A world by name (the few methods race mode and ride along ask of it); one per name. */
    static World world(String name) {
        return WORLDS.computeIfAbsent(name, JourneyBench::newWorld);
    }

    private static World newWorld(String name) {
        return (World) Proxy.newProxyInstance(JourneyBench.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "equals" -> a[0] instanceof World w && name.equals(w.getName());
                    case "hashCode" -> name.hashCode();
                    case "toString" -> "World(" + name + ")";
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    default -> zero(m.getReturnType());
                });
    }

    static SessionBench.Spot spot(String world, Course.Spot s) {
        return new SessionBench.Spot(world, s.x(), s.y(), s.z());
    }

    static SessionBench.Spot spot(Location l) {
        return new SessionBench.Spot(l.getWorld() == null ? "games" : l.getWorld().getName(), l.getX(), l.getY(),
                l.getZ());
    }

    Location location(SessionBench.Spot s) {
        return s == null ? null : new Location(world(s.world()), s.x(), s.y(), s.z());
    }

    /**
     * The player as the server's own object would answer ride along: the bench's player, sitting in
     * the boat the bench seated them in ({@code getVehicle}) and standing where their body stands.
     */
    Player view(UUID id) {
        Player base = players.get(id);
        if (base == null) {
            return null;
        }
        return (Player) Proxy.newProxyInstance(JourneyBench.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getVehicle" -> boats.get(id);
                    case "getLocation" -> location(rail.place(id));
                    case "isOnline" -> online.contains(id);
                    case "equals" -> a[0] instanceof Player q && id.equals(q.getUniqueId());
                    case "hashCode" -> id.hashCode();
                    default -> {
                        try {
                            yield m.invoke(base, a);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    /** A game boat at {@code at}: its place and who sits in it. */
    Entity boat(SessionBench.Spot at) {
        UUID bid = UUID.randomUUID();
        Location l = location(at);
        Entity[] self = new Entity[1];
        self[0] = (Entity) Proxy.newProxyInstance(JourneyBench.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getLocation" -> l.clone();
                    case "getUniqueId" -> bid;
                    case "isValid" -> seats.containsKey(self[0]);
                    case "hashCode" -> bid.hashCode();
                    case "equals" -> proxy == a[0];
                    case "toString" -> "boat@" + at;
                    default -> zero(m.getReturnType());
                });
        return self[0];
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }

    /** Race mode's world sessions: the rail. */
    final class RaceRail implements RaceBench.Rail {
        @Override
        public boolean busy(UUID id) {
            return rail.session(id) != null || !rail.home(id);
        }

        @Override
        public boolean enter(UUID id, Course base, Course.Spot at) {
            String why = rail.enterNow(id, trials, base.id(), spot(base.world(), at), p -> {
            });
            Session s = rail.session(id);
            return why == null && s != null && s.phase() == Session.Phase.ACTIVE;
        }

        @Override
        public void leave(UUID id, EndReason why) {
            rail.leave(id, why);
        }

        @Override
        public void move(UUID id, Course base, Course.Spot at) {
            rail.teleport(id, spot(base.world(), at));
        }

        @Override
        public void kit(UUID id, Course base) {
            rail.stripKit(id);
            rail.putKit(id, 0, "kit:trials:checkpoint");
            rail.putKit(id, 8, "kit:trials:leave");
        }
    }

    /** The server's boats and ride along's calls where Time Trials spawns, parks and removes them. */
    final class BoatHooks implements RaceBench.Boats {
        @Override
        public void seated(UUID driver, Course base, Course.Spot at) {
            gone(driver);
            Entity b = boat(spot(base.world(), at));
            boats.put(driver, b);
            seats.put(b, new ArrayList<>(List.of(driver)));
            riders.seated(view(driver), b, base.id()); // TimeTrials.seat: the rider behind the driver
            rail.step(); // a rider's session entered at the boat arrives
            rail.arriveAll();
        }

        @Override
        public void parked(UUID driver, Point stand) {
            riders.follow(view(driver), location(new SessionBench.Spot("games", stand.x(), stand.y(), stand.z())));
        }

        @Override
        public void gone(UUID driver) {
            Entity b = boats.remove(driver);
            if (b != null) {
                seats.remove(b); // both out: the boat is gone
            }
        }
    }

    /** {@link Riders.Port} over the bench: the rail, the Clubhouse's door, the no-push team, the boats. */
    final class RidePort implements Riders.Port {
        final List<String> entered = new ArrayList<>();

        @Override
        public Player online(UUID id) {
            return id != null && online.contains(id) ? view(id) : null;
        }

        @Override
        public boolean enter(Player rider, String courseId, Location at, Consumer<Player> ready) {
            entered.add(rider.getName() + " " + courseId);
            return rail.enter(rider.getUniqueId(), trials, courseId, spot(at), ready) == null;
        }

        @Override
        public boolean riding(Player p) {
            Session s = rail.session(p.getUniqueId());
            return s != null && trials.id().equals(s.gameId()) && s.phase() == Session.Phase.ACTIVE
                    && trials.run(p.getUniqueId()) == null;
        }

        @Override
        public boolean teleport(Player p, Location at) {
            return rail.teleport(p.getUniqueId(), spot(at));
        }

        @Override
        public boolean board(Entity boat, Player rider) {
            List<UUID> in = seats.get(boat);
            if (in == null) {
                return false;
            }
            seats.values().forEach(l -> l.remove(rider.getUniqueId()));
            in.add(rider.getUniqueId());
            return true;
        }

        @Override
        public boolean aboard(Entity boat, Player rider) {
            return boat != null && seats.getOrDefault(boat, List.of()).contains(rider.getUniqueId());
        }

        @Override
        public void leave(Player p, EndReason why) {
            rail.leave(p.getUniqueId(), why);
        }

        @Override
        public boolean takeIn(Player rider, ClubVisits.Kind kind, String line) {
            return door != null && door.takeIn(rider, kind, line);
        }

        @Override
        public void kit(Player rider, String driverName) {
            UUID id = rider.getUniqueId();
            rail.stripKit(id);
            rail.putKit(id, 4, "kit:trials:ride");
            rail.putKit(id, 8, "kit:trials:leave");
        }

        @Override
        public void passenger(UUID driver, UUID rider) {
            WorldEntities.passenger(driver, rider);
        }

        @Override
        public void noPush(UUID id, String name, boolean on) {
            if (on) {
                pushes.on(id, name);
            } else {
                pushes.off(id, name);
            }
        }

        @Override
        public void collidable(UUID id, boolean on) {
            if (on) {
                notCollidable.remove(id);
            } else {
                notCollidable.add(id);
            }
        }

        @Override
        public Location at(UUID id) {
            return players.containsKey(id) ? location(rail.place(id)) : null;
        }

        @Override
        public boolean inClub(UUID id) {
            return club != null && club.visits().in(id);
        }

        @Override
        public boolean fromClub(Player rider, String courseId) {
            return door != null && door.handOut(rider, trials, courseId);
        }

        @Override
        public boolean driving(UUID id) {
            return trials.onRun(id);
        }

        @Override
        public void tell(Player p, String line) {
            if (p != null && line != null) {
                p.sendMessage(Text.of(line));
            }
        }

        @Override
        public long now() {
            return bench.now();
        }
    }

    // ---- Race Night ------------------------------------------------------------------------------------

    /** Race Night's rows, over the bench's database. */
    com.dierks.homecraft.storage.EventDao events() {
        return new com.dierks.homecraft.storage.EventDao(bench.db(), bench.dao());
    }

    /**
     * Race Night's ports over the bench: race mode's calls through {@link RaceBench}, the telling to the
     * bench's players, and LivePorts' Clubhouse hooks (mirrored: they read the Clubhouse's own door,
     * which a bench Clubhouse has none of).
     */
    final class NightPortsBench implements com.dierks.homecraft.games.event.NightPorts {
        final List<String> log = new ArrayList<>();
        final List<String> watchers = new ArrayList<>();

        @Override
        public long now() {
            return bench.now();
        }

        @Override
        public long tick() {
            return race.tick;
        }

        @Override
        public boolean online(UUID player) {
            return online.contains(player) && race.player(player) != null;
        }

        @Override
        public boolean free(UUID player) {
            return (door != null && door.seatable(player)) || race.free(player); // LivePorts.free
        }

        @Override
        public boolean restartHeld() {
            return games.restartHeld() != null;
        }

        @Override
        public String name(UUID player) {
            Player p = players.get(player);
            return p == null ? null : p.getName();
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            return race.seat(racer, base, raced, grid, stand, link);
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
            race.regrid(racer, raced, grid);
        }

        @Override
        public void park(UUID racer) {
            race.park(racer);
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
            trials.endRace(racer, why, line);
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            return trials.reserve(courseId, holder, line);
        }

        @Override
        public void release(String courseId, Object holder) {
            trials.release(courseId, holder);
        }

        @Override
        public void endSoloRuns(String courseId, java.util.Collection<UUID> racers, String line) {
        }

        @Override
        public void warnSoloRuns(String courseId, java.util.Collection<UUID> racers, String line) {
        }

        @Override
        public void tell(UUID player, String line, boolean queueIfOffline) {
            Player p = online(player) ? players.get(player) : null;
            if (p != null) {
                p.sendMessage(Text.of(line));
            } else if (queueIfOffline) {
                games.notice(player, line, true);
            }
        }

        @Override
        public void title(UUID player, String big, String small) {
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
            watchers.add(line);
        }

        @Override
        public void announce(com.dierks.homecraft.games.event.Announcer.Line line, String text,
                             java.util.Collection<UUID> racers) {
        }

        @Override
        public void changed() {
        }

        @Override
        public long points(UUID player, String board) {
            return 0L;
        }

        @Override
        public void progress(UUID player, boolean won) {
            Player p = players.get(player);
            if (p != null) {
                games.tellProgress(g -> g.raceNightFinished(p, won)); // LivePorts.progress
            }
        }

        @Override
        public void log(String line, boolean warn) {
            log.add(line);
        }

        @Override
        public boolean clubhouse(String trackWorld) {
            return door != null && door.nightAfter() && trackWorld.equalsIgnoreCase(door.world());
        }

        @Override
        public void toClubhouse(UUID racer, String line) {
            trials.endRaceToClubhouse(racer, EndReason.FINISH, line);
        }

        @Override
        public void clubhouseResults(com.dierks.homecraft.games.event.NightRunner night) {
            door.result(com.dierks.homecraft.games.event.ClubNight.sheet(night), null);
            door.podium(com.dierks.homecraft.games.event.ClubNight.podium(night.standings()));
        }
    }

    /** The bench's pay loop for Race Night's prizes: bench players online, the real rewards. */
    com.dierks.homecraft.games.event.PayLoop payLoop() {
        return com.dierks.homecraft.games.event.NightBench.payLoop(games, events(),
                id -> online.contains(id) ? players.get(id) : null, bench::now);
    }

    /**
     * A night on {@code track} starting at {@code startsAt}: three races, {@code warmup} seconds of
     * shared warm-up, points 10/8/6..., prizes 5/3/2 and a finisher's 1, the viewing stand at
     * {@code stand} (or none), a prize night this week.
     */
    com.dierks.homecraft.games.event.NightRunner night(String id, Course track, long startsAt, int warmup, Point stand,
                                                        com.dierks.homecraft.games.event.NightPorts ports) {
        com.dierks.homecraft.games.event.NightRules rules = new com.dierks.homecraft.games.event.NightRules(3, 0, 2, 8,
                List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false, warmup, 60, 4, 20);
        List<Course.Spot> grid = RaceGrid.forCourse(track, ICE, 8).spots();
        com.dierks.homecraft.games.event.EventPlan plan = new com.dierks.homecraft.games.event.EventPlan(id, track.id(),
                startsAt - 10 * MIN, startsAt, rules, false, "");
        com.dierks.homecraft.games.event.NightRunner n = new com.dierks.homecraft.games.event.NightRunner(plan,
                new com.dierks.homecraft.games.event.NightRunner.Track(track, track.name(), grid, stand), events(), ports,
                payLoop(), java.time.ZoneOffset.UTC, null, 30,
                com.dierks.homecraft.games.event.EventMachine.State.scheduled());
        n.prizeWeek(2920, 3);
        return n;
    }

    // ---- reading the database --------------------------------------------------------------------------

    /** Rows of {@code table} for the player ({@code column} names the player). */
    long rows(String table, String column, UUID player) throws SQLException {
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** The kinds of reward the player was paid, in order. */
    List<String> rewardKinds(UUID player) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT kind FROM game_rewards WHERE player = ? ORDER BY id")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    long rewards(UUID player, String kind) throws SQLException {
        return rewardKinds(player).stream().filter(kind::equals).count();
    }

    /** The player's Cup time on {@code course} (any week), or {@code null}. */
    Long cupTime(UUID player, String course) throws SQLException {
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT best_ms FROM cup_entries WHERE course = ? AND player = ?")) {
            ps.setString(1, course);
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /** The player's time on a Time Trials board, or {@code null}. */
    Long best(UUID player, String board) throws SQLException {
        return bench.dao().best(player, TimeTrials.SPEC.id(), board);
    }

    // ---- common checks -----------------------------------------------------------------------------------

    /** Home once, with their own things once, their own mode, and nothing left in a row. */
    String homeProblem(UUID id) {
        List<String> problems = new ArrayList<>();
        if (!homes.get(id).equals(rail.place(id))) {
            problems.add("not home: " + rail.place(id));
        }
        List<String> own = rail.own(id);
        List<String> items = rail.items(id);
        for (String item : own) {
            if (rail.count(id, item) != 1) {
                problems.add(item + " x" + rail.count(id, item));
            }
        }
        if (rail.holdsKit(id)) {
            problems.add("a kit item: " + items);
        }
        if (!rail.dropped(id).isEmpty()) {
            problems.add("dropped " + rail.dropped(id));
        }
        String ender = "ender:" + players.get(id).getName();
        if (!rail.ender(id).equals(List.of(ender))) {
            problems.add("ender chest " + rail.ender(id));
        }
        if (!modes.get(id).equals(rail.gameMode(id))) {
            problems.add("mode " + rail.gameMode(id));
        }
        if (rail.row(id) != null) {
            problems.add("row " + rail.row(id).phase());
        }
        if (rail.session(id) != null) {
            problems.add("still in a session");
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    @Override
    public void close() throws Exception {
        for (UUID a : players.keySet()) {
            WorldEntities.passenger(a, null); // the passenger map is the server's, shared across tests
        }
        bench.close();
    }
}
