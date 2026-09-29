package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine with the four real planners (WP2, WP3) over the fake world: the packages wired
 * together end to end (GEN-SPEC §3.3, §3.5). Every slot (the ice boat switched on too) plans with
 * its generator's real work budget, passes the checks and its generator's validator, converges,
 * verifies (golf replays its witness lines on the blocks), flips and opens; a restart re-derives
 * every live layout from its tag and finds nothing to heal, even after the admin changed the golf
 * mix; and a layout that today's {@code trials.fall_depth} no longer allows is never re-opened.
 */
class RealPlannersTest {

    private static final List<String> SHIPPED = List.of("daily_parkour_easy", "daily_parkour_medium",
            "daily_parkour_hard", "sky_rings", "daily_golf", "tiny_golf", "ice_boat");

    private Host host;
    private GenService gen;

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    static Map<String, Planner> planners() {
        Map<String, Planner> out = new LinkedHashMap<>();
        for (Planner p : List.<Planner>of(new ParkourPlanner(), new RingsPlanner(), new GolfPlanner(),
                new BoatPlanner())) {
            out.put(p.id(), p);
        }
        return out;
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners());
        gen.start();
        gen.worldsReady();
    }

    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    private String problems() {
        StringBuilder b = new StringBuilder();
        for (var r : host.logs) {
            if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                b.append("\n  ").append(r.getLevel()).append(' ').append(r.getMessage());
            }
        }
        return b.toString();
    }

    @Test
    void everyShippedSlotBuildsWithItsRealPlannerOpensAndIsReDerivedAtTheNextBoot() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SHIPPED.toArray(new String[0]));
        boot();
        drive(60 + 15 * 60);
        for (String id : SHIPPED) {
            GenTag tag = gen.liveTag(id);
            assertNotNull(tag, id + " was built with its real planner:" + problems());
            assertTrue(gen.live(id, tag), id + " is open once it is verified");
            GamesDao.CourseRow row = host.dao.course(id);
            if (Slots.of(id).golf()) {
                GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                assertEquals(g.holes().size(), tag.witness().size(), id + " keeps a witness line per hole");
                assertTrue(g.problems(null).isEmpty(), id + "'s golf course is well formed: " + g.problems(null));
            } else {
                Course c = CourseCodec.decode(row.id(), row.data()).course();
                assertNotNull(c, id + "'s row decodes as a course");
                assertEquals(tag, c.gen(), id + "'s row carries the tag the gate vouches for");
                assertTrue(tag.goldMs() > 0 && tag.silverMs() > tag.goldMs(), id + " has its star times");
            }
        }
        assertFalse(gen.status(null).isEmpty(), "status has a line per slot");

        // A restart the same day: every live half is re-derived from its tag and verified untouched.
        long writes = host.world().writes;
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        for (String id : SHIPPED) {
            assertFalse(gen.live(id, gen.liveTag(id)), id + " stays shut at boot until it is checked");
        }
        drive(5 * 60);
        for (String id : SHIPPED) {
            assertTrue(gen.live(id, gen.liveTag(id)), id + " was re-derived and verified after the restart:"
                    + problems() + "\n" + gen.status(id));
        }
        assertEquals(writes, host.world().writes, "a clean restart heals nothing");
    }

    @Test
    void aGolfLayoutIsReDerivedAfterTheAdminChangedItsMix() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "daily_golf");
        boot();
        drive(60 + 3 * 60);
        GenTag live = gen.liveTag("daily_golf");
        assertNotNull(live, "Daily Golf was built:" + problems());
        List<String> said = new java.util.ArrayList<>();
        gen.tier("daily_golf", "EEEEEEEEE", said::add); // "from the next build"
        long writes = host.world().writes;
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        drive(3 * 60);
        assertEquals(live, gen.liveTag("daily_golf"), "the same layout is live after the restart");
        assertTrue(gen.live("daily_golf", live), "and it opens: it was re-derived with the mix it was made with,"
                + " not the new one:" + problems());
        assertEquals(writes, host.world().writes, "with nothing to heal");
    }

    @Test
    void aParkourLayoutThatTodaysFallDepthNoLongerAllowsIsReplacedNotReOpened() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "daily_parkour_hard");
        boot();
        drive(60 + 60);
        GenTag first = gen.liveTag("daily_parkour_hard");
        assertNotNull(first, "Hard Parkour was built at fall_depth 6:" + problems());
        host.fallDepth = 1; // the admin lowers trials.fall_depth, then restarts
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000;
        boot();
        drive(3 * 60);
        GenTag second = gen.liveTag("daily_parkour_hard");
        assertTrue(host.logged(Level.SEVERE, "can't be vouched for") >= 1,
                "the layout made for fall_depth 6 fails the check at 1:" + problems());
        assertEquals(first.reroll() + 1, second.reroll(), "its replacement is today's next reroll, on a fresh board");
        assertTrue(gen.live("daily_parkour_hard", second), "and it opens, planned for fall_depth 1");
    }
}
