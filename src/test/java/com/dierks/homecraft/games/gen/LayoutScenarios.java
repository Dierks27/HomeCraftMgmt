package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.arena.ArenaService;
import com.dierks.homecraft.games.clubhouse.ClubhouseRoom;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.ClassicWant;
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import com.dierks.homecraft.games.gen.engine.KeptPlot;
import com.dierks.homecraft.games.gen.engine.Regions;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import com.dierks.homecraft.storage.GenMetaDao;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The legacy guard's scenarios on a real (in-memory) database: a 0.35 install that built each kind of
 * thing, alone, and one that built nothing, each upgraded, with one oracle for all of them
 * ({@link #failures}). A built install must come out with every place exactly where and as 0.35 built
 * it, and every stored claim still naming the region config gives it (so the engine's "origin
 * changed" path, a reroll and orphaned blocks, can't fire); one that built nothing must come out with
 * the new layout. {@code LayoutGuardScenarioTest} runs them with the real rules;
 * {@code LayoutGuardMutationTest} with each rule broken, and needs a failure every time.
 */
public final class LayoutScenarios {

    /** The world 0.35 built in. */
    public static final String WORLD = "games";

    private LayoutScenarios() {
    }

    // ---- a 0.35 install's database ----------------------------------------------------------------

    /** A fresh in-memory database at the current schema, with what a 0.35 install could have built. */
    public static final class Install implements AutoCloseable {
        public final Connection connection;
        public final Database db;
        public final GenMetaDao meta;
        private long now = 1_790_000_000_000L;

        public Install() {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                db = Database.open(connection, Logger.getAnonymousLogger());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            meta = new GenMetaDao(db);
        }

        public void set(String key, String value) {
            try {
                meta.set(key, value);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * 0.35's claim of {@code def}'s region: {@code world,x,y,z,sx,sy,sz} at its 0.35 origin and 0.35 size
         * (gap 32): what 0.35 wrote, whatever size the slot has now (v4 grew golf's and the boat's).
         */
        public static String claim035(Slots.Def def) {
            int[] o = LegacyBoxes.origin(def);
            int[] s = LegacyBoxes.size(def);
            return WORLD + "," + o[0] + "," + o[1] + "," + o[2] + "," + s[0] + "," + s[1] + "," + s[2];
        }

        /** {@code def}'s region claimed at its 0.35 spot (a set being built, or one that stands). */
        public Install claim(Slots.Def def) {
            set(GenAdminKeys.claim(def.id()), claim035(def));
            return this;
        }

        /** A Fresh set that went up: its claim, its course row and its archive row, as 0.35's flip wrote them. */
        public Install edition(Slots.Def def) {
            claim(def);
            row(def);
            return archived(def);
        }

        /** A course row for {@code def} (and nothing else). */
        public Install row(Slots.Def def) {
            try {
                new GamesDao(db).saveCourse(new GamesDao.CourseRow(def.id(), def.game(), def.kind(), def.name(), WORLD,
                        true, "gen: {}\n", 1, now, now));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        /** An archived set of {@code def} (and nothing else: its course cleared since). */
        public Install archived(Slots.Def def) {
            try {
                new GenArchiveDao(db).archive(new GenArchiveDao.Row(def.id(), "20000:0", null, 0, 20_000, 7L, "1",
                        def.kind(), def.tierOrMix(), def.name(), now, null, new byte[]{1, 2, 3}, 1000, 2000, now, null),
                        now);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        /** A Classic holding a recall: the recall and its claim. */
        public Install recall(Slots.Def classic) {
            set(GenAdminKeys.recall(classic.id()), new ClassicWant("fresh_parkour", "20000:0", now, now + 7 * 86_400_000L,
                    false).text());
            return claim(classic);
        }

        /** A course kept in plot {@code n} of 0.35's keep area. */
        public Install kept(int n) {
            Box plot = LegacyBoxes.keep().plot(n);
            set(GenAdminKeys.plot(n), new KeptPlot(n, "kept_" + n, WORLD, plot, "fresh_parkour", "20000:0").text());
            return this;
        }

        /** The Clubhouse claimed at its 0.35 box. */
        public Install clubhouse() {
            set(ClubhouseRoom.CLAIM_KEY, ClubhouseRoom.claimText(WORLD, LegacyBoxes.clubhouse()));
            return this;
        }

        /** The arena claimed at its 0.35 box. */
        public Install arena() {
            set(ArenaService.CLAIM_KEY, ArenaService.claimText(WORLD, LegacyBoxes.fallingFloors()));
            return this;
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // an in-memory database: nothing to keep
            }
        }
    }

    // ---- config.yml on "disk", and faults ---------------------------------------------------------

    /** config.yml as text, as the plugin's file: every load reads it, every save writes it. */
    public static final class Disk implements LayoutGuard.ConfigFile {
        public String text;
        /** The next save fails (a full disk, or the server stopping in the middle). */
        public boolean failSave;
        public int saves;

        public Disk(FileConfiguration c) {
            this.text = c.saveToString();
        }

        @Override
        public FileConfiguration load() {
            YamlConfiguration c = new YamlConfiguration();
            try {
                c.loadFromString(text);
            } catch (Exception e) {
                return null;
            }
            return c;
        }

        @Override
        public boolean save(FileConfiguration c) {
            if (failSave) {
                failSave = false;
                throw new IllegalStateException("the server stopped while config.yml was being written");
            }
            text = c.saveToString();
            saves++;
            return true;
        }
    }

    /** The database side with faults: a stamp write or a read that stops the run. */
    public static final class Faulty implements LayoutGuard.Store {
        private final LayoutGuard.Store real;
        public boolean failStamp;
        public boolean failFacts;
        public int factsRead;
        public int stamps;

        public Faulty(LayoutGuard.Store real) {
            this.real = real;
        }

        @Override
        public String stamp() throws SQLException {
            return real.stamp();
        }

        @Override
        public void stamp(String text) throws SQLException {
            if (failStamp) {
                failStamp = false;
                throw new SQLException("the server stopped before the stamp was written");
            }
            stamps++;
            real.stamp(text);
        }

        @Override
        public LayoutGuard.Facts facts() throws SQLException {
            if (failFacts) {
                throw new SQLException("facts were read, but the stamped decision should have been used");
            }
            factsRead++;
            return real.facts();
        }
    }

    // ---- the oracle -------------------------------------------------------------------------------

    /**
     * Why {@code c} (a file after the guard) would let the engine treat something {@code install} built
     * as moved, or {@code null} when nothing would: every stored slot and Classic claim is still exactly
     * the region config now gives that slot, and the Clubhouse's and the arena's claims their boxes; the
     * keep area is 0.35's, so new keeps go on in the same grid; and every place is where 0.35 built it.
     *
     * <p>v4: the places whose size v4 changed ({@link LayoutGuard#RESIZED}: golf's two and the boat) can't
     * keep 0.35's shape, so they must be at their shipped spot, and a 0.35 claim of theirs must read as
     * resized: the engine records it as an old area and empties it (RETIRE), so nothing strands.
     */
    public static String legacyProblem(FileConfiguration c, Install install) {
        try {
            return legacyProblem(c, install.meta.like(GenMetaDao.PREFIX));
        } catch (SQLException e) {
            return "the database can't be read: " + e;
        }
    }

    /** {@link #legacyProblem(FileConfiguration, Install)} against the {@code gen.} keys {@code meta}. */
    public static String legacyProblem(FileConfiguration c, Map<String, String> meta) {
        Map<String, List<Box>> places = LayoutFixtures.places(LayoutFixtures.reread(c));
        List<Slots.Def> all = new ArrayList<>(Slots.ALL);
        all.addAll(Slots.CLASSICS);
        for (Slots.Def d : all) {
            String claim = meta.get(GenAdminKeys.claim(d.id()));
            if (claim == null) {
                continue;
            }
            List<Box> halves = places.get(d.id());
            if (LayoutGuard.RESIZED.contains(d.id())) {
                if (!Regions.resized(d, claim)) {
                    return d.id() + " was claimed as " + claim + ", which doesn't read as an older size: its old area"
                            + " wouldn't be emptied";
                }
                continue;
            }
            String now = Regions.claim(d, Regions.claimWorld(claim), new int[]{halves.get(0).minX(),
                    halves.get(0).minY(), halves.get(0).minZ()}, halves.get(1).minX() - halves.get(0).maxX() - 1);
            if (!now.equals(claim)) {
                return d.id() + " was claimed as " + claim + " but config now gives " + now + ": it would be moved";
            }
        }
        String club = meta.get(ClubhouseRoom.CLAIM_KEY);
        if (club != null && !club.equals(ClubhouseRoom.claimText(WORLD, places.get("clubhouse").get(0)))) {
            return "the Clubhouse was claimed as " + club + " but config now gives " + places.get("clubhouse").get(0);
        }
        String arena = meta.get(ArenaService.CLAIM_KEY);
        if (arena != null && !arena.equals(ArenaService.claimText(WORLD, places.get("falling_floors").get(0)))) {
            return "the arena was claimed as " + arena + " but config now gives " + places.get("falling_floors").get(0);
        }
        Map<String, List<Box>> legacy = LayoutFixtures.legacyPlaces();
        Map<String, List<Box>> shipped = LayoutFixtures.shippedPlaces();
        for (Map.Entry<String, List<Box>> e : legacy.entrySet()) {
            if (LayoutGuard.RESIZED.contains(e.getKey())) {
                if (!shipped.get(e.getKey()).equals(places.get(e.getKey()))) {
                    return e.getKey() + " is at " + places.get(e.getKey()) + ", not its shipped spot: v4 grew it, so no"
                            + " server keeps its 0.35 one";
                }
                continue;
            }
            if (!e.getValue().equals(places.get(e.getKey()))) {
                return e.getKey() + " is at " + places.get(e.getKey()) + ", not where 0.35 built it (" + e.getValue()
                        + ")";
            }
        }
        return null;
    }

    /** Why {@code c} isn't the new layout, or {@code null}. */
    public static String newProblem(FileConfiguration c) {
        Map<String, List<Box>> places = LayoutFixtures.places(LayoutFixtures.reread(c));
        Map<String, List<Box>> shipped = LayoutFixtures.shippedPlaces();
        return shipped.equals(places) ? null : "not the new layout: " + places;
    }

    // ---- the scenarios --------------------------------------------------------------------------

    /**
     * One 0.35 install.
     *
     * @param built whether it built something (so it must keep 0.35's layout)
     */
    public record Scenario(String name, Consumer<Install> fill, boolean built) {
    }

    /** Every kind of built thing on its own, the realistic mixes, and installs that built nothing. */
    public static final List<Scenario> ALL = List.of(
            new Scenario("never switched on", i -> { }, false),
            new Scenario("only settings and switches", i -> {
                i.set(GenAdminKeys.schedule(), "7|monday|1790000000000");
                i.set(GenAdminKeys.enabled("fresh_golf"), "false");
                i.set("gen.clubhouse.off", "true");
            }, false),
            new Scenario("a Fresh set up", i -> i.edition(Slots.DAILY_PARKOUR_EASY), true),
            new Scenario("a claim alone (the first set being built)", i -> i.claim(Slots.DAILY_GOLF), true),
            new Scenario("a Dropper's old halves alone", i -> i.set(GenAdminKeys.wet("fresh_dropper"),
                    Install.claim035(Slots.FRESH_DROPPER)), true),
            new Scenario("a course's old area alone (v4's record)", i -> i.set(GenAdminKeys.old("fresh_dropper"),
                    Install.claim035(Slots.FRESH_DROPPER)), true),
            new Scenario("a Classic holding a recall", i -> i.recall(Slots.CLASSIC_PARKOUR), true),
            new Scenario("a recall alone (not built yet)", i -> i.set(GenAdminKeys.recall("fresh_classic_golf"),
                    new ClassicWant("fresh_golf", "20000:0", 1, 2, false).text()), true),
            new Scenario("a kept course", i -> i.kept(1), true),
            new Scenario("a course being kept", i -> i.set(GenAdminKeys.KEEP_PENDING, "keep|2|cool_jumps"), true),
            new Scenario("the Clubhouse claimed", Install::clubhouse, true),
            new Scenario("the arena claimed", Install::arena, true),
            new Scenario("an archived set, its course cleared since", i -> i.archived(Slots.DAILY_GOLF), true),
            new Scenario("a course row alone", i -> i.row(Slots.TINY_GOLF), true),
            new Scenario("everything", i -> {
                for (Slots.Def d : Slots.ALL) {
                    i.edition(d);
                }
                i.recall(Slots.CLASSIC_DROPPER).kept(1).kept(2).clubhouse().arena();
            }, true));

    /** The 0.35.0 file as revision 19 leaves it before the database opens: marked. */
    public static YamlConfiguration marked035() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set("config_revision", 19);
        LayoutGuard.markPending(c);
        return c;
    }

    /**
     * Run every scenario with {@code rules}: the names of the ones that came out wrong (empty: all
     * right), each with why.
     */
    static List<String> failures(List<LayoutGuard.Evidence> rules) {
        List<String> out = new ArrayList<>();
        for (Scenario s : ALL) {
            try (Install install = new Install()) {
                s.fill().accept(install);
                Disk disk = new Disk(marked035());
                LayoutGuard.Outcome o = LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, 1L, rules);
                FileConfiguration after = disk.load();
                String why = s.built() ? legacyProblem(after, install) : newProblem(after);
                LayoutGuard.Decision want = s.built() ? LayoutGuard.Decision.LEGACY : LayoutGuard.Decision.NEW;
                if (why == null && o.decision() != want) {
                    why = "decided " + o.decision();
                }
                if (why == null && LayoutGuard.pending(after)) {
                    why = "the mark is still there";
                }
                if (why != null) {
                    out.add(s.name() + ": " + why);
                }
            } catch (RuntimeException e) {
                out.add(s.name() + ": " + e);
            }
        }
        return out;
    }
}
