package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf v4's independent check (GOLF-V4-SPEC §6.1, §7): a sound plan passes, and each mutation is
 * caught — a par off by one, a tee sign saying the wrong par, a witness over par, a witness with a
 * penalty, a witness that skims a pond, a kid over the bound, a wet kid, a hole overrunning its plot,
 * scenery inside the physics grid. Older plans still go to their own frozen rules.
 */
class GolfValidatorV4Test {

    private static Plan sound;
    private static Plan tiny;

    @BeforeAll
    static void plan() throws GenFailed {
        sound = new GolfPlanner().plan(GolfPlannerV4Test.input(Slots.DAILY_GOLF, 11));
        tiny = new GolfPlanner().plan(GolfPlannerV4Test.input(Slots.TINY_GOLF, 11));
    }

    private static PlannedGolf golf(Plan p) {
        return (PlannedGolf) p.course();
    }

    private static Plan with(Plan p, List<GolfCourse.Hole> holes, List<SignText> signs, List<List<Putt>> witness,
                             List<Integer> expert) {
        PlannedGolf g = golf(p);
        GolfCourse c = g.course();
        PlannedGolf changed = new PlannedGolf(new GolfCourse(c.id(), c.name(), c.world(), c.enabled(), c.rev(), holes),
                g.attempts(), witness, expert, g.kid());
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), signs, p.keepClear(), changed,
                p.summary(), p.work());
    }

    /** {@code p} with hole {@code i}'s par {@code par}, its sign saying it. */
    private static Plan withPar(Plan p, int i, int par) {
        List<GolfCourse.Hole> holes = new ArrayList<>(golf(p).course().holes());
        GolfCourse.Hole h = holes.get(i);
        holes.set(i, new GolfCourse.Hole(h.tee(), h.cup(), par, h.corner1(), h.corner2()));
        List<SignText> signs = new ArrayList<>(p.signs());
        SignText s = signs.get(i);
        GenCopy.TeeFeature f = GenCopy.golfTeeFeature(s.lines(), i + 1, h.par());
        signs.set(i, new SignText(s.x(), s.y(), s.z(), s.blockData(), GenCopy.golfTee(i + 1, par, f)));
        return with(p, holes, signs, golf(p).witness(), golf(p).expert());
    }

    /** {@code p} with hole {@code i}'s witness {@code line} (E its putts). */
    private static Plan withLine(Plan p, int i, List<Putt> line) {
        List<List<Putt>> witness = new ArrayList<>(golf(p).witness());
        witness.set(i, line);
        List<Integer> expert = new ArrayList<>(golf(p).expert());
        expert.set(i, line.size());
        return with(p, golf(p).course().holes(), p.signs(), witness, expert);
    }

    private static void says(List<String> problems, String words, String why) {
        assertTrue(problems.stream().anyMatch(s -> s.contains(words)), why + ": " + problems);
    }

    private static OrdinaryPar.Measure measure(Plan p, int i) throws GenFailed {
        PlanBlocks grid = PlanBlocks.of(p.half(), p.palette(), p.ops());
        GolfCourse.Hole h = golf(p).course().holes().get(i);
        return OrdinaryPar.measure(grid, h, LaneMap.of(grid, h, 4), OrdinaryPar.model(p.slot()), Work.unlimited());
    }

    @Test
    void aSoundPlanPassesQuickAndFull() {
        assertEquals(List.of(), GolfValidator.quickProblems(sound), "the planner's plan passes the quick check");
        assertEquals(List.of(), GolfValidator.problems(sound), "and the full one");
        assertEquals(List.of(), GolfValidator.problems(tiny), "Tiny Golf's too");
        assertTrue(GolfValidator.v4(sound), "a version-4 plan plays Golf v4's rules");
    }

    @Test
    void aParOffByOneIsCaughtFromTheBlocks() {
        for (int d : new int[]{-1, 1}) {
            int par = golf(sound).course().holes().get(4).par() + d;
            says(GolfValidator.quickProblems(withPar(sound, 4, par)), "hole 5's par is " + par + ", but its blocks measure",
                    "a par " + (d > 0 ? "over" : "under") + " what the ordinary player measures");
        }
    }

    @Test
    void aTeeSignSayingTheWrongParIsCaught() {
        List<SignText> signs = new ArrayList<>(sound.signs());
        SignText s = signs.get(2);
        int par = golf(sound).course().holes().get(2).par();
        signs.set(2, new SignText(s.x(), s.y(), s.z(), s.blockData(), GenCopy.golfTee(3, par + 1)));
        says(GolfValidator.quickProblems(with(sound, golf(sound).course().holes(), signs, golf(sound).witness(),
                golf(sound).expert())), "hole 3 has no tee sign saying its par", "the sign says par " + (par + 1));
    }

    @Test
    void aWitnessOverParOrWithAPenaltyIsCaught() throws GenFailed {
        boolean over = false;
        boolean penalty = false;
        for (int i = 0; i < 9 && !(over && penalty); i++) {
            int par = golf(sound).course().holes().get(i).par();
            for (OrdinaryPar.Line l : measure(sound, i).holed()) {
                if (!over && l.clean() && l.strokes() == l.putts().size() && l.putts().size() > par) {
                    says(GolfValidator.quickProblems(withLine(sound, i, l.putts())), "hole " + (i + 1) + "'s line takes "
                            + l.putts().size() + " putts, over its par", "a holed line longer than par is no witness");
                    over = true;
                }
                if (!penalty && l.strokes() > l.putts().size()) {
                    says(GolfValidator.quickProblems(withLine(sound, i, l.putts())), "takes a penalty",
                            "a line that splashed on the way is no witness");
                    penalty = true;
                }
            }
        }
        assertTrue(over, "some rollout took more than par");
        assertTrue(penalty, "and some splashed on its way in");
    }

    @Test
    void aWitnessThatSkimsAPondIsCaught() throws GenFailed {
        AdventureKit.Drawn d = GolfValidatorV3Test.brave();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        LaneMap lane = LaneMap.of(g, h, 4);
        ExpertSearch.Result plain = ExpertSearch.search(g, h, lane, ExpertSearch.MAX_DEPTH, Work.unlimited());
        assertTrue(plain.found() && SafeExpert.unsafePutt(g, h, plain.witness()) > 0,
                "the fewest putts skim past the pond: 3 degrees off, it splashes");
        String p = GolfValidatorV4.lineProblem(g, h, lane, plain.witness(), 1);
        assertNotNull(p, "the v4 witness test refuses it");
        assertTrue(p.contains("splashes"), "because it has no room to miss: " + p);
        List<Putt> safe = SafeExpert.search(g, h, lane, ExpertSearch.MAX_DEPTH, Work.unlimited()).witness();
        assertEquals(null, GolfValidatorV4.lineProblem(g, h, lane, safe, 1), "the safe line passes");
    }

    @Test
    void aKidOverTheBoundIsCaughtByTheFullCheck() {
        PlannedGolf g = golf(sound);
        int i = 0;
        while (g.kid().get(i) < 5) {
            i++; // a long hole: the kid takes 5 or more
        }
        int k = g.kid().get(i);
        Plan p = withPar(sound, i, k - 3);
        says(GolfValidator.problems(p), "hole " + (i + 1) + ": a sloppy player can need",
                "with par " + (k - 3) + " the kid's " + k + " strokes are over par + 2");
        assertTrue(GolfValidator.quickProblems(p).stream().noneMatch(s -> s.contains("sloppy")),
                "the quick check doesn't run the kid's tree");
    }

    @Test
    void aWetKidOrOneOffTheLaneIsCaught() {
        // an ice causeway one wide between two ponds: on ice even a Tap runs nine blocks, so six degrees
        // off, the kid's ball is in the water whatever it plays
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "#########",
                "#0000000#",
                "#000t000#",
                "#0000000#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#~~~i~~~#",
                "#0000000#",
                "#0000000#",
                "#000c000#",
                "#0000000#",
                "#########");
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(3);
        List<String> p = GolfValidatorV4.kidProblems(g, h, LaneMap.of(g, h, 4), 9, 1);
        says(p, "can splash", "a kid who can putt into a pond is wet");
    }

    @Test
    void aHoleOverrunningItsPlotIsCaught() {
        // the same blocks judged as a Tiny Golf plan stand on 20 x 40 plots: hole 1 overruns its plot
        Plan relabelled = Plan.of(Slots.TINY_GOLF.id(), sound.algo(), sound.seed(), sound.half(), sound.palette(),
                sound.ops(), sound.signs(), sound.keepClear(), sound.course(), sound.summary(), sound.work());
        says(GolfValidator.quickProblems(relabelled), "overrun its plot", "a 40 x 64 hole on a 20 x 40 plot");
    }

    @Test
    void sceneryInsideThePhysicsGridIsCaught() {
        GolfCourse.Hole h = golf(sound).course().holes().get(0);
        Box b = Box.of(h.corner1().x(), h.corner1().y(), h.corner1().z(), h.corner2().x(), h.corner2().y(),
                h.corner2().z());
        int x = b.maxX() + 1;
        int z = (b.minZ() + b.maxZ()) / 2;
        List<String> palette = new ArrayList<>(sound.palette());
        int moss = palette.indexOf(Palette.MOSS);
        if (moss < 0) {
            palette.add(Palette.MOSS);
            moss = palette.size() - 1;
        }
        List<BlockOp> ops = new ArrayList<>(sound.ops());
        int turf = b.minY() + LaneMap.BASE_ABOVE_FLOOR;
        boolean taken = ops.stream().anyMatch(o -> o.x() == x && o.z() == z && o.y() == turf - 1);
        assertTrue(!taken, "the column beside the bounds is empty in the sound plan");
        ops.add(new BlockOp(x, turf - 1, z, (short) moss));
        Plan p = Plan.of(sound.slot(), sound.algo(), sound.seed(), sound.half(), palette, ops, sound.signs(),
                sound.keepClear(), sound.course(), sound.summary(), sound.work());
        says(GolfValidator.quickProblems(p), "scenery block stands within 1 column of a hole's bounds",
                "scenery one column outside a hole's bounds is in the ball's grid");
    }

    @Test
    void olderPlansStillGoToTheirOwnFrozenRules() {
        Plan asThree = Plan.of(sound.slot(), 3, sound.seed(), sound.half(), sound.palette(), sound.ops(), sound.signs(),
                sound.keepClear(), sound.course(), sound.summary(), sound.work());
        assertTrue(!GolfValidator.v4(asThree), "a version-3 plan isn't judged by Golf v4's play rules");
        List<String> problems = GolfValidator.quickProblems(asThree);
        assertTrue(problems.stream().anyMatch(s -> s.contains("for an expert line of") || s.contains("'s line has")),
                "Adventure Golf's frozen rules judge it: par must be E + 1 there, and an expert line at most "
                        + ExpertSearch.MAX_DEPTH + " putts: " + problems);
    }
}
