package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.FreshBench;
import com.dierks.homecraft.games.gen.golf.AdventureKit;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey: an Adventure Golf course with a pond, through the real Fresh Courses engine and a round
 * (Course Variety §9; the review of Course Variety). The engine builds Golf of the Week's layout of
 * version 3 — its pond drained first and filled last, its par line replayed on the built blocks —
 * and opens it; Mini Golf offers it; a round tees off on the built blocks, read as its rounds read
 * them ({@link LiveBlocks#forCourse}: Adventure Golf's rules); a putt into the pond is a splash, +1,
 * the ball back on its spot; "Reset ball" then has nothing to do; the par line from there holes out;
 * and on a second round a putt that stops dry, then "Reset ball", costs one stroke and puts the
 * ball back. {@code GolfRounds}' server part (the ball's entity, the chat) is mirrored, as
 * {@link GolfBench} does.
 */
class CrossFeatureJourneyFreshGolfPondTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final String SLOT = Slots.DAILY_GOLF.id();

    /**
     * A 7-wide lane with a 3 x 4 pond beside it, open to the lane and walled round its far side (a
     * POND_SIDE on Medium), the tee at the lane's near end, the cup at its far end.
     */
    private static final String[] POND = {
            "#########",
            "#0000000#",
            "#000t000#",
            "#0000000#",
            "#0000000####",
            "#0000000~~~#",
            "#0000000~~~#",
            "#0000000~~~#",
            "#0000000~~~#",
            "#0000000####",
            "#0000000#",
            "#0000000#",
            "#0000000#",
            "#000c000#",
            "#0000000#",
            "#########"};

    /** The pond hole, drawn in the first plot of {@code half}. */
    private static AdventureKit.Drawn hole(Box half) {
        int[] p = GolfPlanner.plot(half, 0);
        return AdventureKit.draw(p[0], p[1], half.minY() + GolfPlanner.TURF_ABOVE_FLOOR, POND);
    }

    /** Golf's planner for the journey: the pond hole, as a layout of golf planner version 3. */
    private record PondPlanner() implements Planner {

        @Override
        public String id() {
            return Slots.GOLF;
        }

        @Override
        public int algo() {
            return 3;
        }

        @Override
        public Plan plan(PlanInput in) {
            AdventureKit.Drawn d = hole(in.half());
            return AdventureKit.plan(in.slot(), in.half(), in.seed(), 3, List.of(d), List.of(d.line()), List.of());
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return plan(in);
        }
    }

    private GamesBench bench;
    private FreshBench fresh;
    private MiniGolf golf;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(MiniGolf.SPEC), "golf", MiniGolfSettings.defaults());
        golf = (MiniGolf) bench.games().game(MiniGolf.SPEC.id());
        fresh = new FreshBench(bench, List::of, (p, c, again) -> {
        }, new PondPlanner(), SLOT);
        assertTrue(fresh.driveUntil(() -> golf.playableCourse(SLOT) != null, 30 * 60),
                "the week's Adventure course goes up and Mini Golf offers it: " + fresh.logged());
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    /** {@code GolfRounds.teeOff}, mirrored: the ball on the hole's tee, settled, its spot marked. */
    private static LiveRound teeOff(GolfCourse c, BallPhysics.Blocks blocks) {
        LiveRound r = new LiveRound(UUID.randomUUID(), c, new GolfRun(c.pars(), MiniGolfSettings.defaults().maxOverPar()),
                null);
        r.tee(blocks);
        BallPhysics.settle(r.ball, blocks);
        r.markSpot();
        return r;
    }

    /** The ball rolled out, a tick at a time ({@code GolfRounds.roll}). */
    private static LiveRound.Result rollOut(LiveRound r, BallPhysics.Blocks blocks) {
        LiveRound.Result res = LiveRound.Result.ROLLING;
        for (int i = 0; i < 2_000 && res == LiveRound.Result.ROLLING; i++) {
            res = r.roll(blocks);
        }
        return res;
    }

    /** {@code GolfRounds.reset}, mirrored: nothing when the ball is on its spot, else back there for a stroke. */
    private static boolean reset(LiveRound r) {
        if (r.atSpot()) {
            return false; // "Your ball is already at your last spot."
        }
        r.back();
        return true;
    }

    @Test
    void aPuttIntoThePondIsASplashAndResetPutsTheBallBackOnlyWhenItMoved() {
        GolfCourse c = golf.playableCourse(SLOT);
        assertTrue(c.generated() && c.gen().algo() == 3, "a Fresh course, planned at version 3: " + c.gen());
        assertTrue(LiveBlocks.sandPlays(c), "so its rounds play Adventure Golf's rules");
        Box half = Slots.DAILY_GOLF.half(c.gen().half());
        AdventureKit.Drawn d = hole(half);
        GolfCourse.Hole h = c.hole(1);
        int turf = half.minY() + GolfPlanner.TURF_ABOVE_FLOOR;
        assertTrue(fresh.world().ballBlocks(true).surface(d.x(9), turf - 1, d.z(6)).wet(),
                "the engine built the pond, and filled it");
        // the built blocks, read as GolfRounds reads them for this course (LiveBlocks.forCourse)
        BallPhysics.Blocks blocks = fresh.world().ballBlocks(LiveBlocks.sandPlays(c));
        assertTrue(GolfShot.adventure(blocks), "(Adventure Golf's rules)");

        // 1. from the tee, a putt straight at the pond: a splash, +1, the ball back on the tee
        LiveRound r = teeOff(c, blocks);
        double teeX = r.ball.x();
        double teeZ = r.ball.z();
        double yaw = Math.toDegrees(Math.atan2(-(d.x(9) + 0.5 - teeX), d.z(6) + 0.5 - teeZ));
        r.putt((float) yaw, 3);
        assertEquals(LiveRound.Result.BACK, rollOut(r, blocks), "into the pond");
        assertEquals(BallPhysics.Outcome.WATER, r.outcome, "a splash (\"Splash! Back to your last spot, +1 stroke.\")");
        assertEquals(2, r.run.strokes(), "the putt and the penalty");
        assertEquals(teeX, r.ball.x(), 0.0, "back on the tee, where it was putted from");
        assertEquals(teeZ, r.ball.z(), 0.0, "...");
        assertEquals(h.tee().y(), r.ball.y(), 1e-9, "on the tee, not in the water");
        assertFalse(r.ball.moving(), "still, ready to putt");

        // 2. "Reset ball" now: it is already on its spot, so nothing happens and nothing is charged
        assertFalse(reset(r), "Reset ball: \"Your ball is already at your last spot.\"");
        assertEquals(2, r.run.strokes(), "no stroke for it");

        // 3. the par line from the tee holes out
        List<Putt> line = d.line();
        LiveRound.Result last = LiveRound.Result.STILL;
        for (Putt p : line) {
            r.putt(p.yaw(), p.power());
            last = rollOut(r, blocks);
            if (last == LiveRound.Result.IN_CUP) {
                break;
            }
        }
        assertEquals(LiveRound.Result.IN_CUP, last, "the par line holes out on the built blocks: " + line);
        assertNotNull(r.last, "the hole is done");
        assertEquals(2 + line.size(), r.last.strokes(), "the splash cost one stroke more, and no more");

        // 4. a second round: a short putt that stops dry, then "Reset ball": +1, back on the tee
        LiveRound again = teeOff(c, blocks);
        again.putt(0f, 1);
        assertEquals(LiveRound.Result.STILL, rollOut(again, blocks), "a tap along the lane stops on the turf");
        assertFalse(again.atSpot(), "it moved");
        assertTrue(reset(again), "Reset ball");
        assertEquals(2, again.run.strokes(), "the putt and the reset");
        assertEquals(teeX, again.ball.x(), 0.0, "back on the tee");
        assertTrue(again.atSpot(), "on its spot again");
    }
}
