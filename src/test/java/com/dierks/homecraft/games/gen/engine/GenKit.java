package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What the engine tests share: a map-backed world, a small deterministic planner, and a host with
 * a clock the test moves, over a real in-memory SQLite. Nothing here is the server; everything the
 * engine does with it is its own code.
 */
final class GenKit {

    /** Where the players live: a zone with DST. */
    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    static final String WORLD = "games";
    static final String CONCRETE = "minecraft:white_concrete";

    private GenKit() {
    }

    static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- the world ----------------------------------------------------------------------------

    /** A world of block texts; chunks "load" (with a ticket) when asked. */
    static class FakeWorld implements WorldPort {
        final String name;
        final Map<Long, String> blocks = new HashMap<>();
        final Map<Long, List<String>> signs = new HashMap<>();
        final Set<Long> tickets = new HashSet<>();
        final List<Runnable> pendingLoads = new ArrayList<>();
        final Set<Long> failLoads = new HashSet<>();
        final List<String> rules = new ArrayList<>();
        boolean asyncLoads;
        boolean distrusted;
        long writes;
        long released;
        /** Throw on the write after this many more (a crash mid-apply); -1 = never. */
        long killAfter = -1;
        int[] spawn = {0, 64, 0};
        Box border;

        FakeWorld(String name) {
            this.name = name;
        }

        static long chunk(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }

        String at(int x, int y, int z) {
            return blocks.get(pos(x, y, z));
        }

        void put(int x, int y, int z, String state) {
            blocks.put(pos(x, y, z), canonicalOf(state));
        }

        /** Blocks inside a box. */
        long count(Box b) {
            long n = 0;
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (blocks.containsKey(pos(x, y, z))) {
                            n++;
                        }
                    }
                }
            }
            return n;
        }

        /** A copy of what a box holds, for "identical world" checks. */
        Map<Long, String> copy(Box b) {
            Map<Long, String> out = new HashMap<>();
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        String s = blocks.get(pos(x, y, z));
                        if (s != null) {
                            out.put(pos(x, y, z), s + (signs.containsKey(pos(x, y, z)) ? signs.get(pos(x, y, z)) : ""));
                        }
                    }
                }
            }
            return out;
        }

        static String canonicalOf(String blockData) {
            String s = blockData.trim().toLowerCase(Locale.ROOT);
            if (s.contains("bogus")) {
                throw new IllegalArgumentException("not a block: " + blockData);
            }
            return s.indexOf(':') < 0 ? "minecraft:" + s : s;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int minHeight() {
            return -64;
        }

        @Override
        public int maxHeight() {
            return 320;
        }

        @Override
        public Box border() {
            return border;
        }

        @Override
        public int[] spawn() {
            return spawn.clone();
        }

        @Override
        public void load(int cx, int cz, Consumer<Boolean> done) {
            long k = chunk(cx, cz);
            Runnable r = () -> {
                boolean ok = !failLoads.contains(k);
                if (ok) {
                    tickets.add(k);
                }
                done.accept(ok);
            };
            if (asyncLoads) {
                pendingLoads.add(r);
            } else {
                r.run();
            }
        }

        void finishLoads() {
            List<Runnable> now = new ArrayList<>(pendingLoads);
            pendingLoads.clear();
            now.forEach(Runnable::run);
        }

        @Override
        public void release(int cx, int cz) {
            tickets.remove(chunk(cx, cz));
            released++;
        }

        @Override
        public ChunkView snapshot(int cx, int cz) {
            if (!tickets.contains(chunk(cx, cz))) {
                return null;
            }
            Map<Long, String> copy = new HashMap<>();
            Set<Integer> sections = new HashSet<>();
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                long p = e.getKey();
                int x = (int) (p >> 38);
                int z = (int) ((p << 26) >> 38);
                int y = (int) ((p << 52) >> 52);
                if (x >> 4 == cx && z >> 4 == cz) {
                    copy.put(p, e.getValue());
                    sections.add(y >> 4);
                }
            }
            return new ChunkView() {
                @Override
                public boolean sectionEmpty(int y) {
                    return !distrusted && !sections.contains(y >> 4);
                }

                @Override
                public boolean air(int x, int y, int z) {
                    return !copy.containsKey(pos(x, y, z));
                }

                @Override
                public String block(int x, int y, int z) {
                    String s = copy.get(pos(x, y, z));
                    return s == null ? AIR : s;
                }
            };
        }

        @Override
        public String canonical(String blockData) {
            return canonicalOf(blockData);
        }

        @Override
        public void set(int x, int y, int z, String state) {
            if (killAfter == 0) {
                killAfter = -1;
                throw new IllegalStateException("the server died mid-write");
            }
            if (killAfter > 0) {
                killAfter--;
            }
            writes++;
            long p = pos(x, y, z);
            if (AIR.equals(state)) {
                blocks.remove(p);
            } else {
                blocks.put(p, state);
            }
            signs.remove(p);
        }

        @Override
        public void sign(int x, int y, int z, List<String> lines) {
            List<String> four = new ArrayList<>(lines);
            while (four.size() < 4) {
                four.add("");
            }
            signs.put(pos(x, y, z), four);
        }

        @Override
        public List<String> signLines(int x, int y, int z) {
            String b = blocks.get(pos(x, y, z));
            if (b == null || !b.contains("sign")) {
                return null;
            }
            return signs.getOrDefault(pos(x, y, z), List.of("", "", "", ""));
        }

        @Override
        public BallPhysics.Blocks ballBlocks() {
            return new BallPhysics.Blocks() {
                @Override
                public double top(int x, int y, int z, double px, double pz) {
                    String b = blocks.get(pos(x, y, z));
                    if (b == null) {
                        return NONE;
                    }
                    return b.contains("type=bottom") ? 0.5 : 1.0;
                }

                @Override
                public BallPhysics.Surface surface(int x, int y, int z) {
                    String b = blocks.getOrDefault(pos(x, y, z), "");
                    if (b.contains("slime")) {
                        return BallPhysics.Surface.SLIME;
                    }
                    if (b.contains("ice")) {
                        return BallPhysics.Surface.ICE;
                    }
                    return b.contains("soul_soil") ? BallPhysics.Surface.SLOW : BallPhysics.Surface.NORMAL;
                }
            };
        }

        @Override
        public void worldRules(Consumer<String> changed) {
            rules.add("applied");
            changed.accept("game rule spawn_mobs true -> false");
        }

        @Override
        public void distrustEmptySections() {
            distrusted = true;
        }
    }

    // ---- plans ----------------------------------------------------------------------------------

    /**
     * A small parkour-like plan inside {@code half}: a start pad, a checkpoint pad and a gold finish
     * pad with a sign, placed by the seed so two seeds give two layouts.
     */
    static Plan plan(Slots.Def def, Box half, long seed, int algo) {
        List<String> palette = List.of(Palette.PATH_EASY, Palette.CHECKPOINT, Palette.FINISH);
        List<BlockOp> ops = new ArrayList<>();
        int y = half.minY() + 10;
        int shift = (int) Math.floorMod(seed, 9);
        int[][] pads = {{half.minX() + 3, half.minZ() + 3, 0}, {half.minX() + 12 + shift, half.minZ() + 12, 1},
                {half.minX() + 24 + shift, half.minZ() + 24, 2}};
        for (int[] p : pads) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    ops.add(new BlockOp(p[0] + dx, y, p[1] + dz, (short) p[2]));
                }
            }
        }
        List<SignText> signs = List.of(new SignText(pads[2][0] + 1, y + 1, pads[2][1] + 1, Palette.sign(0),
                GenCopy.finish()));
        Course.Spot start = new Course.Spot(pads[0][0] + 1.5, y + 1, pads[0][1] + 1.5, 0f, 0f);
        Course.Mark cp = new Course.Mark(pads[1][0] + 1.5, y + 1, pads[1][1] + 1.5, 2.2);
        Course.Mark finish = new Course.Mark(pads[2][0] + 1.5, y + 1, pads[2][1] + 1.5, 3.0);
        Course c = new Course(def.id(), TrialKind.PARKOUR, def.name(), Tier.EASY, "", start, List.of(cp), finish,
                (double) y - 2, null, false, false, 1);
        return Plan.of(def.id(), algo, seed, half, palette, ops, signs, List.of(), new PlannedTrial(c, 30_000),
                List.of("3 pads"), 42);
    }

    /** A planner of {@link #plan}s that counts its calls and can be told to fail. */
    static final class FakePlanner implements Planner {
        final String id;
        int algo = 1;
        int plans;
        int rederives;
        /** Thrown from the next plan (then cleared), or {@code null}. */
        Throwable fail;
        boolean failAlways;
        /** Thrown from every rederive while set (a live layout that can't be vouched for). */
        GenFailed rederiveFail;

        FakePlanner(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int algo() {
            return algo;
        }

        @Override
        public Plan plan(PlanInput in) throws GenFailed {
            plans++;
            in.checkCancelled();
            if (fail != null) {
                Throwable t = fail;
                if (!failAlways) {
                    fail = null;
                }
                if (t instanceof GenFailed g) {
                    throw g;
                }
                if (t instanceof RuntimeException r) {
                    throw r;
                }
                throw (Error) t;
            }
            return GenKit.plan(in.slot(), in.half(), in.seed(), algo);
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
            rederives++;
            if (rederiveFail != null) {
                throw rederiveFail;
            }
            return GenKit.plan(in.slot(), in.half(), in.seed(), algo);
        }
    }

    // ---- settings -----------------------------------------------------------------------------------

    /**
     * Fresh Courses on, in {@link #WORLD}, with only {@code on} slots switched on, changing every
     * day (so a test's "next day" is a new edition); {@link #weekly} for the shipped cadence.
     */
    static DailySettings settings(String... on) {
        DailySettings d = DailySettings.defaults();
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(c.withEnabled(List.of(on).contains(c.id())));
        }
        return d.withEnabled(true).withWorld(WORLD).withSlots(slots).withCadence(Edition.DAILY);
    }

    /** As {@link #settings}, at the shipped weekly cadence. */
    static DailySettings weekly(String... on) {
        return settings(on).withCadence(Edition.WEEKLY);
    }

    // ---- the host -----------------------------------------------------------------------------------

    /** A store that can be told to fail its secret or its flips. */
    static final class FlakyStore implements GenStore {
        final GenStore real;
        boolean secretFails;
        boolean flipFails;
        boolean keepFails;
        int flips;

        FlakyStore(GenStore real) {
            this.real = real;
        }

        @Override
        public long secret() throws SQLException {
            if (secretFails) {
                throw new SQLException("the database is locked");
            }
            return real.secret();
        }

        @Override
        public GamesDao.CourseRow course(String id) throws SQLException {
            return real.course(id);
        }

        @Override
        public List<GamesDao.CourseRow> courses(String game) throws SQLException {
            return real.courses(game);
        }

        @Override
        public int flip(GamesDao.CourseRow row, Map<String, String> meta) throws SQLException {
            if (flipFails) {
                throw new SQLException("disk I/O error");
            }
            flips++;
            return real.flip(row, meta);
        }

        @Override
        public String meta(String key) throws SQLException {
            return real.meta(key);
        }

        @Override
        public void meta(String key, String value) throws SQLException {
            real.meta(key, value);
        }

        @Override
        public Map<String, String> metaLike(String prefix) throws SQLException {
            return real.metaLike(prefix);
        }

        @Override
        public int pruneBoards(long oldestDay, long oldestWeek) throws SQLException {
            return real.pruneBoards(oldestDay, oldestWeek);
        }

        @Override
        public List<String> editionBoards() throws SQLException {
            return real.editionBoards();
        }

        @Override
        public int dropEditionBoards(List<String> boards) throws SQLException {
            return real.dropEditionBoards(boards);
        }

        @Override
        public boolean hasScores(String game, String board) throws SQLException {
            return real.hasScores(game, board);
        }

        @Override
        public Flipped flip(GamesDao.CourseRow row, Map<String, String> meta, GenArchiveDao.Row archive, long now)
                throws SQLException {
            if (flipFails) {
                throw new SQLException("disk I/O error");
            }
            flips++;
            return real.flip(row, meta, archive, now);
        }

        @Override
        public GenArchiveDao.Row edition(String slot, String edition) throws SQLException {
            return real.edition(slot, edition);
        }

        @Override
        public GenArchiveDao.Row editionByCode(String code) throws SQLException {
            return real.editionByCode(code);
        }

        @Override
        public List<GenArchiveDao.Row> editions(String slot, int offset, int limit) throws SQLException {
            return real.editions(slot, offset, limit);
        }

        @Override
        public int editionCount(String slot) throws SQLException {
            return real.editionCount(slot);
        }

        @Override
        public GenArchiveDao.Row editionLive(String slot, long after, long at) throws SQLException {
            return real.editionLive(slot, after, at);
        }

        @Override
        public List<GenArchiveDao.Row> editionsBySeed(String slot, String hexPrefix) throws SQLException {
            return real.editionsBySeed(slot, hexPrefix);
        }

        @Override
        public Set<String> archivedBoards() throws SQLException {
            return real.archivedBoards();
        }

        @Override
        public int pruneArchive(long endedBefore, Set<String> spared) throws SQLException {
            return real.pruneArchive(endedBefore, spared);
        }

        @Override
        public GenArchiveDao.BoardStats boardStats(String game, String board) throws SQLException {
            return real.boardStats(game, board);
        }

        @Override
        public List<GamesDao.ScoreRow> top(String game, String board, boolean lowerIsBetter, int limit)
                throws SQLException {
            return real.top(game, board, lowerIsBetter, limit);
        }

        @Override
        public void closeCourse(String id, Map<String, String> meta) throws SQLException {
            real.closeCourse(id, meta);
        }

        @Override
        public int keep(GamesDao.CourseRow row, String fromBoard, String toBoard, String slot, String edition,
                        Map<String, String> meta) throws SQLException {
            if (keepFails) {
                throw new SQLException("disk I/O error");
            }
            return real.keep(row, fromBoard, toBoard, slot, edition, meta);
        }

        @Override
        public void dropCourse(String id, Map<String, String> meta) throws SQLException {
            real.dropCourse(id, meta);
        }
    }

    /** The server, as the engine sees it. */
    static final class Host implements GenHost {
        final Connection connection;
        final Database db;
        final GamesDao dao;
        final FlakyStore store;
        final Map<String, FakeWorld> worlds = new LinkedHashMap<>();
        final List<Person> people = new ArrayList<>();
        final Map<String, Integer> changed = new HashMap<>();
        final List<String> told = new ArrayList<>();
        final List<String> bars = new ArrayList<>();
        final List<UUID> ended = new ArrayList<>();
        final List<UUID> moved = new ArrayList<>();
        final List<LogRecord> logs = new ArrayList<>();
        final List<Runnable> plannerQueue = new ArrayList<>();
        /** What {@code /hcm play} already opens (games, aliases, courses), for the kept-course id rules. */
        final Set<String> playIds = new HashSet<>();
        final Map<UUID, String> names = new HashMap<>();
        final Logger logger = Logger.getAnonymousLogger();
        DailySettings settings;
        List<LocalTime> restarts = List.of();
        long now;
        long nanos;
        boolean holdPlans;
        double mspt = 10;
        /** The extra boxes Fresh Courses keeps apart from (the Falling Floors arena), none by default. */
        List<Regions.Extra> extras = List.of();
        /** {@code trials.fall_depth}. */
        int fallDepth = 6;

        Host(long now, String... on) {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                db = Database.open(connection, Logger.getAnonymousLogger());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            dao = new GamesDao(db);
            store = new FlakyStore(GenStore.of(db));
            worlds.put(WORLD, new FakeWorld(WORLD));
            settings = GenKit.settings(on);
            this.now = now;
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logs.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        FakeWorld world() {
            return worlds.get(WORLD);
        }

        long logged(Level level, String part) {
            return logs.stream().filter(r -> r.getLevel() == level && r.getMessage().contains(part)).count();
        }

        void runPlans() {
            List<Runnable> now = new ArrayList<>(plannerQueue);
            plannerQueue.clear();
            now.forEach(Runnable::run);
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public long nanoTime() {
            return nanos += 1_000;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public DailySettings settings() {
            return settings;
        }

        @Override
        public RestartHold restartHold() {
            return new RestartHold(restarts, ZONE, 5);
        }

        @Override
        public ZoneId zone() {
            return ZONE;
        }

        @Override
        public DayOfWeek weekStart() {
            return DayOfWeek.MONDAY;
        }

        @Override
        public List<String> gamesWorlds() {
            return List.of(WORLD);
        }

        @Override
        public int fallDepth() {
            return fallDepth;
        }

        @Override
        public WorldPort world(String name) {
            return worlds.get(name);
        }

        @Override
        public GenStore store() {
            return store;
        }

        @Override
        public Executor planner() {
            return r -> {
                if (holdPlans) {
                    plannerQueue.add(r);
                } else {
                    r.run();
                }
            };
        }

        @Override
        public void coursesChanged(String gameId) {
            changed.merge(gameId, 1, Integer::sum);
        }

        @Override
        public List<Person> people() {
            return new ArrayList<>(people);
        }

        @Override
        public boolean anyoneOnline() {
            return !people.isEmpty();
        }

        @Override
        public double mspt() {
            return mspt;
        }

        @Override
        public void tell(UUID player, String line) {
            told.add(line);
        }

        @Override
        public void actionBar(UUID player, String line) {
            bars.add(line);
        }

        @Override
        public void endRun(UUID player) {
            ended.add(player);
            people.removeIf(p -> p.id().equals(player));
        }

        @Override
        public List<Regions.Extra> extras() {
            return extras;
        }

        @Override
        public boolean playIdTaken(String id) {
            return playIds.contains(id);
        }

        @Override
        public String playerName(UUID player) {
            return names.getOrDefault(player, "someone");
        }

        @Override
        public void move(UUID player, String world, double x, double y, double z) {
            moved.add(player);
            for (int i = 0; i < people.size(); i++) {
                Person p = people.get(i);
                if (p.id().equals(player)) {
                    people.set(i, new Person(p.id(), p.name(), world, x, y, z, p.sessionGame(), p.sessionRef()));
                }
            }
        }
    }
}
