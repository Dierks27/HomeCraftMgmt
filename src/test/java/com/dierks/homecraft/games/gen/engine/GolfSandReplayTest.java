package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.AdventureKit;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.GolfValidator;
import com.dierks.homecraft.games.golf.CourseCodec;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import com.dierks.homecraft.games.golf.LiveBlocks;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build's live proof reads sand as the course will be played (Course Variety §3.4, decision
 * 2): a golf layout of version 3 or later replays its witness line with smooth sandstone as sand,
 * so an Adventure hole whose par line stops in a bunker is proven and opens; the same blocks said to
 * be an older version are replayed with sandstone as stone (as every hand-built course reads it),
 * the line runs through, and the layout is refused.
 */
class GolfSandReplayTest {

    private static final String TINY = Slots.TINY_GOLF.id();

    /** A 5-wide lane with a flush strip of sand across it: its hole-in-one stops in the cup only on sand. */
    private static final String[] FLUSH = {
            "#######",
            "#00000#",
            "#00t00#",
            "#00000#",
            "#sssss#",
            "#sssss#",
            "#00000#",
            "#00000#",
            "#00000#",
            "#00c00#",
            "#00000#",
            "#######"};

    private Host host;
    private GenService gen;

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        if (host != null) {
            host.connection.close();
        }
    }

    /** One hand-drawn Adventure hole, in the slot's half, at golf planner version {@code algo}. */
    private record SandPlanner(int algo) implements Planner {

        @Override
        public String id() {
            return Slots.GOLF;
        }

        @Override
        public Plan plan(PlanInput in) {
            return sandPlan(in.slot(), in.half(), in.seed(), algo);
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return plan(in);
        }
    }

    static AdventureKit.Drawn hole(Box half) {
        int[] p = GolfPlanner.plot(half, 0);
        return AdventureKit.draw(p[0], p[1], half.minY() + GolfPlanner.TURF_ABOVE_FLOOR, FLUSH);
    }

    static Plan sandPlan(Slots.Def slot, Box half, long seed, int algo) {
        AdventureKit.Drawn d = hole(half);
        List<Putt> line = d.line();
        return AdventureKit.plan(slot, half, seed, algo, List.of(d), List.of(line), List.of());
    }

    private void boot(int algo) {
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new GenKit.FakePlanner(id));
        }
        planners.put(Slots.GOLF, new SandPlanner(algo));
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
    }

    /** Run the engine until the slot is live or {@code seconds} pass; its live tag, or null. */
    private GenTag drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
            GenTag tag = gen.liveTag(TINY);
            if (tag != null && gen.live(TINY, tag)) {
                return tag;
            }
        }
        return null;
    }

    @Test
    void theLineStopsInTheBunkerOnlyWhenSandIsSand() {
        FakeWorldCheck check = new FakeWorldCheck();
        Box half = Slots.TINY_GOLF.half('A');
        AdventureKit.Drawn d = hole(half);
        List<Putt> line = d.line();
        GolfCourse.Hole h = d.hole(GolfPlanner.par(line.size()));
        GolfShot.Replay sand = GolfShot.replay(check.blocks(d, true), h, line);
        GolfShot.Replay stone = GolfShot.replay(check.blocks(d, false), h, line);
        assertTrue(sand.holed() && sand.strokes() == line.size(), "with sand the par line holes: " + line);
        assertFalse(stone.holed() && stone.strokes() == line.size(), "read as stone it runs through: " + stone);
        assertEquals(List.of(), GolfValidator.problems(sandPlan(Slots.TINY_GOLF, half, 1, 3)),
                "and the layout is a sound Adventure hole by the full check");
    }

    /** The fake world's own ball reading of a drawn hole's blocks. */
    private static final class FakeWorldCheck {
        com.dierks.homecraft.games.golf.BallPhysics.Blocks blocks(AdventureKit.Drawn d, boolean sand) {
            GenKit.FakeWorld w = new GenKit.FakeWorld(GenKit.WORLD);
            for (Map.Entry<int[], String> e : d.placed()) {
                w.set(e.getKey()[0], e.getKey()[1], e.getKey()[2], w.canonical(e.getValue()));
            }
            return w.ballBlocks(sand);
        }
    }

    @Test
    void anAdventureLayoutIsProvenWithSandAndOpens() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, TINY);
        boot(3);
        GenTag tag = drive(30 * 60);
        assertNotNull(tag, "the version-3 layout replays its line with sand on the built blocks, and opens");
        assertTrue(tag.algo() == 3, "(a version-3 tag)");
        assertTrue(host.logged(Level.SEVERE, "didn't replay on the real blocks") == 0, "no failed replay");
    }

    /**
     * Keeping an Adventure course keeps its sand (the review of Course Variety). The keep's live
     * replay reads the built plot as the course was planned — its smooth sandstone as sand — so a par
     * line that stops on the sand is proven there and the keep goes through; and the kept course, a
     * normal course from then on, says it plays Adventure Golf's rules, so its rounds keep reading
     * its sand as sand (its par, its sloppy-player proof and its "Sand is slow!" sign were all worked
     * out that way). Before, the replay read the sand as stone: the line ran through, the keep
     * failed after building, and the plot was cleared again; the courses whose keep went through
     * played their bunkers as stone.
     */
    @Test
    void keepingAnAdventureCourseWithSandKeepsItsSand() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, TINY);
        boot(3);
        assertNotNull(drive(30 * 60), "day 1's sand hole is up");
        GenTag first = gen.liveTag(TINY);
        host.now = GenKit.at(2026, 9, 30, 4, 0) + 40_000;
        GenTag second = null;
        for (int s = 0; s < 30 * 60 && (second == null || second.editionKey().equals(first.editionKey())); s++) {
            drive(1);
            second = gen.liveTag(TINY);
        }
        assertTrue(second != null && !second.editionKey().equals(first.editionKey()), "day 2's is up: TINY-1 is"
                + " in the archive");
        List<String> said = new ArrayList<>();
        gen.keep(TINY, GenArgs.which("TINY-1"), "sand_links", null, false, true, said::add);
        for (int s = 0; s < 20 * 60 && host.dao.course("sand_links") == null; s++) {
            host.runPlans();
            drive(1);
        }
        GamesDao.CourseRow row = host.dao.course("sand_links");
        assertNotNull(row, "the keep's live replay reads the sand as sand, and the course is kept: " + said);
        GolfCourse kept = CourseCodec.fromRow(row);
        assertFalse(kept.generated(), "a normal course now");
        assertTrue(kept.adventure(), "that says it plays Adventure Golf's rules");
        assertTrue(LiveBlocks.sandPlays(kept), "so its rounds read its sand as sand");
        AdventureKit.Drawn d = hole(Slots.TINY_GOLF.half('A'));
        List<Putt> line = d.line();
        GolfShot.Replay replay = GolfShot.replay(host.world().ballBlocks(LiveBlocks.sandPlays(kept)),
                kept.holes().get(0), line);
        assertTrue(replay.holed() && replay.strokes() == line.size(), "and on its blocks, read as its rounds read"
                + " them, the par line still holes in " + line.size() + ": " + replay);
        assertTrue(host.logged(Level.WARNING, "the live replay failed") == 0, "no failed keep on the way");
    }

    @Test
    void onlyACourseKeptFromAnAdventureLayoutSaysItPlaysAdventureGolfsRules() {
        Box half = Slots.TINY_GOLF.half('A');
        GolfCourse three = CourseCodec.fromRow(KeptCourses.row("sand_links", "Sand Links", GenKit.WORLD,
                sandPlan(Slots.TINY_GOLF, half, 1, 3), 1000));
        assertTrue(three.adventure() && LiveBlocks.sandPlays(three), "kept from a version-3 layout: Adventure rules");
        assertNull(three.gen(), "(and still a normal course, with no gen block)");
        assertEquals(List.of(), KeptCourses.problems(CourseCodec.toRow(three, 1000, 1000), List.of(GenKit.WORLD)),
                "that opens like any other");
        GolfCourse two = CourseCodec.fromRow(KeptCourses.row("old_links", "Old Links", GenKit.WORLD,
                sandPlan(Slots.TINY_GOLF, half, 1, 2), 1000));
        assertFalse(two.adventure() || LiveBlocks.sandPlays(two), "kept from an older layout: the old rules");
    }

    @Test
    void theSameBlocksAsAnOlderLayoutAreReplayedWithoutSandAndRefused() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, TINY);
        boot(2);
        assertNull(drive(10 * 60), "read as stone (an older version's reading) the line runs through: never opened");
        assertTrue(host.logged(Level.SEVERE, "didn't replay on the real blocks") > 0,
                "the live replay failed, and the log says so");
    }
}
