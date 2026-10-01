package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a racer reads at the track on a Mountain Run (CV final gate): a real {@link NightRunner} on a fake
 * clock, racers who are online and free, and a real database, from the join window to race 1's grid.
 *
 * <p>Pinned here: on the Mountain Run the grid title's subtitle has no laps ("Ice Boat · you start 1st":
 * a sprint has none, as the live bar and the Race Night tile say) and the warm-up is "Warm-up runs"; on the
 * same marks under the algo-2 planner, hand-built, or on a loop, both read exactly as before ("1 lap",
 * "2 laps", "Warm-up laps").
 */
class NightRunnerDownhillCopyTest {

    private static final long MIN = 60_000L;
    /** The start: Fri 2 Oct 2026 19:00 UTC. */
    private static final long T = 1_790_967_600_000L;
    private static final String ID = "rn-20261002-1900";
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    /** One database per night (each night is the same night, on another track). */
    private final List<Connection> conns = new ArrayList<>();
    private Ports ports;

    /** The server: a clock, two racers online and free, a Time Trials that seats them, and what they were shown. */
    private static final class Ports implements NightPorts {
        long now = T - 11 * MIN;
        long tick = 1_000;
        final Set<UUID> seated = new HashSet<>();
        final Map<UUID, List<String>> titles = new HashMap<>();
        final Map<UUID, List<String>> told = new HashMap<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public boolean online(UUID player) {
            return true;
        }

        @Override
        public boolean free(UUID player) {
            return !seated.contains(player);
        }

        @Override
        public String name(UUID player) {
            return player.equals(A) ? "Ava" : "Ben";
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            seated.add(racer);
            return null;
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
        }

        @Override
        public void park(UUID racer) {
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            return true;
        }

        @Override
        public void release(String courseId, Object holder) {
        }

        @Override
        public void endSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void warnSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void tell(UUID player, String line, boolean queueIfOffline) {
            told.computeIfAbsent(player, k -> new ArrayList<>()).add(line);
        }

        @Override
        public void title(UUID player, String big, String small) {
            titles.computeIfAbsent(player, k -> new ArrayList<>()).add(big + " | " + small);
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
        }

        @Override
        public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
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
        }

        @Override
        public void log(String line, boolean warn) {
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection c : conns) {
            c.close();
        }
    }

    private EventDao freshDao() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            conns.add(c);
            Database db = Database.open(c, Logger.getAnonymousLogger());
            return new EventDao(db, new GamesDao(db));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Four grid spots behind {@code c}'s start. */
    private static List<Course.Spot> grid(Course c) {
        List<Course.Spot> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(new Course.Spot(c.start().x() - 4 * (i / 2), c.start().y(), c.start().z() + (i % 2 == 0 ? -1.5 : 1.5),
                    c.start().yaw(), 0));
        }
        return out;
    }

    /** A hand-built two-lap loop. */
    private static Course loop() {
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        List<Course.Mark> two = new ArrayList<>(lap);
        two.addAll(lap);
        return new Course("ice", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games", new Course.Spot(20, 64, -16, 0, 0), two,
                new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
    }

    /**
     * A night on {@code c} (with a shared warm-up of {@code warmupSeconds}), Ava and Ben in, run until just
     * after race 1's Go.
     */
    private NightRunner night(Course c, int warmupSeconds) {
        ports = new Ports();
        EventDao dao = freshDao();
        NightRules rules = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                warmupSeconds, 60, 4, 20);
        EventPlan plan = new EventPlan(ID, c.id(), T - 10 * MIN, T, rules, false, "");
        PayLoop.Payer nobody = new PayLoop.Payer() {
            @Override
            public int pay(UUID player, String ref, int tokens, String detail) {
                return tokens;
            }

            @Override
            public boolean paid(UUID player, String ref) {
                return false;
            }
        };
        NightRunner r = new NightRunner(plan, new NightRunner.Track(c, c.name(), grid(c), new Point(0, 70, 0)), dao,
                ports, new PayLoop(dao, nobody, () -> ports.now, Logger.getAnonymousLogger()), ZoneOffset.UTC, null, 30,
                EventMachine.State.scheduled());
        while (ports.now < T - 10 * MIN + 1_000) {
            step(r);
        }
        assertEquals(EventMachine.Phase.OPEN, r.phase(), "fixture: the window is open at T - 10");
        assertNull(r.join(A, "Ava"), "fixture: Ava joins");
        assertNull(r.join(B, "Ben"), "fixture: Ben joins");
        while (ports.now < T + 250) {
            step(r);
        }
        return r;
    }

    private void step(NightRunner r) {
        ports.now += 50;
        ports.tick++;
        r.tick();
    }

    /** Ava's grid title for race 1 (the first title she was shown), as "big | small". */
    private String gridTitle() {
        List<String> shown = ports.titles.get(A);
        assertNotNull(shown, "Ava was shown titles");
        return shown.get(0);
    }

    private boolean heard(String words) {
        return ports.told.getOrDefault(A, List.of()).stream().anyMatch(l -> l.contains(words));
    }

    @Test
    void theMountainRunsGridTitleHasNoLapsAndItsWarmUpIsRuns() {
        NightRunner n = night(MountainRuns.medium(), 0);
        assertEquals(1, n.laps(), "fixture: a sprint is raced once");
        String title = gridTitle();
        assertTrue(title.startsWith("&6Race 1 of 3 | &7Ice Boat · you start "), "the grid title: " + title);
        assertFalse(title.contains("lap"), "no laps on a sprint (the tile says \"3 downhill races\"): " + title);

        night(MountainRuns.medium(), 60);
        assertTrue(heard("&bWarm-up runs &7- not counted. Tap &aReady &7when you're set."),
                "the warm-up at the top of the mountain is a run: " + ports.told.get(A));
        assertFalse(heard("Warm-up laps"), "not laps: " + ports.told.get(A));
    }

    @Test
    void theSameMarksUnderTheAlgo2PlannerOrHandBuiltAndALoopReadAsBefore() {
        for (Course c : List.of(MountainRuns.medium(MountainRuns.tag(2, 7)), MountainRuns.medium(null))) {
            String what = c.gen() == null ? "hand-built" : "the algo-2 planner's layout";
            night(c, 0);
            String title = gridTitle();
            assertTrue(title.startsWith("&6Race 1 of 3 | &7Ice Boat · 1 lap · you start "), what + ": as before: "
                    + title);
            night(c, 60);
            assertTrue(heard("&bWarm-up laps &7- not counted."), what + ": the warm-up as before: " + ports.told.get(A));
        }
        night(loop(), 0);
        String title = gridTitle();
        assertTrue(title.startsWith("&6Race 1 of 3 | &7Ice Loop · 2 laps · you start "), "a loop: as before: " + title);
        night(loop(), 60);
        assertTrue(heard("&bWarm-up laps &7- not counted."), "a loop's warm-up as before: " + ports.told.get(A));
    }
}
