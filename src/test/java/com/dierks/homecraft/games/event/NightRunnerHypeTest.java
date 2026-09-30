package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run's hype in a real night's chat (COURSE-VARIETY-SPEC §5.2): a {@link NightRunner} on a
 * fake clock and a real database, from before its heads-up to its join window.
 *
 * <p>Pinned here: on the Mountain Run the heads-up (T - 30) and the join-open line (T - 10) both end
 * with "This week: 5 drops down the mountain!"; with {@code hype} off, or on a flat loop, they are the
 * lines as they always were; and nothing else a night announces changes.
 */
class NightRunnerHypeTest {

    private static final long MIN = 60_000L;
    /** The start: Fri 2 Oct 2026 19:00 UTC. */
    private static final long T = 1_790_967_600_000L;
    private static final String ID = "rn-20261002-1900";
    private static final String HYPE = "&bThis week: 5 drops down the mountain!";

    private Connection conn;
    private EventDao dao;
    private Ports ports;

    /** The server as the night sees it: a clock, nobody online, and every announcement written down. */
    private static final class Ports implements NightPorts {
        long now = T - 31 * MIN;
        long tick = 1_000;
        final Map<String, String> announced = new LinkedHashMap<>();

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
            return false;
        }

        @Override
        public boolean free(UUID player) {
            return false;
        }

        @Override
        public String name(UUID player) {
            return "Ava";
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
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
        }

        @Override
        public void title(UUID player, String big, String small) {
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
        }

        @Override
        public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
            announced.put(line.name(), text);
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

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new EventDao(db, new GamesDao(db));
        ports = new Ports();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private static List<Course.Spot> grid(Course c) {
        List<Course.Spot> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(new Course.Spot(c.start().x() - 4 * (i / 2), c.start().y(), c.start().z() + (i % 2 == 0 ? -1.5 : 1.5),
                    c.start().yaw(), 0));
        }
        return out;
    }

    private static Course loop() {
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        List<Course.Mark> two = new ArrayList<>(lap);
        two.addAll(lap);
        return new Course("ice", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games", new Course.Spot(20, 64, -16, 0, 0), two,
                new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
    }

    /** A night on {@code c} with the hype {@code on}, run from T - 31 to just after its window opens. */
    private Map<String, String> night(Course c, boolean on) {
        NightRules rules = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                0, 60, 4, 20);
        EventPlan plan = new EventPlan(ID, c.id(), T - 10 * MIN, T, rules, false, "");
        Point stand = new Point(4544.5, c.start().y() + RaceStand.ABOVE, 4416.5); // the half's centre, 5 up
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
        NightRunner r = new NightRunner(plan, new NightRunner.Track(c, c.name(), grid(c), stand), dao, ports,
                new PayLoop(dao, nobody, () -> ports.now, Logger.getAnonymousLogger()), ZoneOffset.UTC, null, 30,
                EventMachine.State.scheduled());
        r.hype(on);
        assertEquals(on, r.hype(), "fixture: the switch as Race Night hands it over");
        while (ports.now < T - 10 * MIN + 1_000) {
            ports.now += 50;
            ports.tick++;
            r.tick();
        }
        assertEquals(EventMachine.Phase.OPEN, r.phase(), "fixture: the window is open at T - 10");
        return ports.announced;
    }

    @Test
    void theHeadsUpAndJoinOpenEndWithTheMountainRunsDrops() {
        Map<String, String> said = night(MountainRuns.medium(), true);
        assertTrue(said.get("HEADS_UP").startsWith("&6Race Night at 7:00 PM! &7Boat races on Ice Boat"),
                "the heads-up at T - 30: " + said);
        assertTrue(said.get("HEADS_UP").endsWith(" " + HYPE), "§5.2: ends with the hype: " + said.get("HEADS_UP"));
        assertTrue(said.get("JOIN_OPEN").startsWith("&bRace Night &7on Ice Boat starts at 7:00 PM"),
                "join-open at T - 10: " + said);
        assertTrue(said.get("JOIN_OPEN").endsWith(" " + HYPE), "and so does join-open: " + said.get("JOIN_OPEN"));
    }

    @Test
    void withTheHypeOffTheLinesAreAsTheyWere() {
        Map<String, String> said = night(MountainRuns.medium(), false);
        assertEquals(EventCopy.headsUp(T, T - 10 * MIN, "Ice Boat", ZoneOffset.UTC), said.get("HEADS_UP"),
                "hype: false: the heads-up as shipped");
        assertEquals(EventCopy.joinOpen(T, "Ice Boat", ZoneOffset.UTC), said.get("JOIN_OPEN"), "and join-open");
    }

    @Test
    void aFlatLoopSaysNothingNewWithTheHypeOn() {
        Map<String, String> said = night(loop(), true);
        assertEquals(EventCopy.headsUp(T, T - 10 * MIN, "Ice Loop", ZoneOffset.UTC), said.get("HEADS_UP"),
                "a loop: the heads-up as it always was");
        assertEquals(EventCopy.joinOpen(T, "Ice Loop", ZoneOffset.UTC), said.get("JOIN_OPEN"), "and join-open");
        assertFalse(said.values().stream().anyMatch(s -> s.contains("mountain")), "no hype anywhere: " + said);
    }
}
