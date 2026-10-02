package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.games.gen.boat.HandMountain.wx;
import static com.dierks.homecraft.games.gen.boat.HandMountain.wz;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's proof (MOUNTAIN-V2-SPEC §9, §15 package B): hand-made mountains of both styles
 * and every tier pass, and every mutation §15 lists is caught by exactly the line meant to catch it
 * (where one fault breaks two rules by its nature, both lines and nothing else), plus v2's own
 * rules: the real downhill, the drop caps per style, the stand at the bottom with its clear
 * cylinder and sight line, the Final Drop in front of it, the 128 checkpoints, the exact half, the
 * gate fields. The mountains come from {@link HandMountain}; a mutation builds a variant of one or
 * edits single blocks, signs or marks of a built plan.
 */
class MountainValidatorTest {

    private static HandMountain road;
    private static Plan base;

    @BeforeAll
    static void build() {
        road = HandMountain.road("medium");
        base = road.plan();
    }

    private static List<String> check(Plan p, String tier) {
        return MountainValidator.problems(p, tier);
    }

    /** Every part is said, and nothing else is: each line holds one of the parts. */
    private static void only(Plan p, String tier, String why, String... parts) {
        List<String> problems = check(p, tier);
        for (String part : parts) {
            assertTrue(problems.stream().anyMatch(l -> l.contains(part)),
                    why + ": expected '" + part + "' in " + problems);
        }
        for (String line : problems) {
            boolean known = false;
            for (String part : parts) {
                known |= line.contains(part);
            }
            assertTrue(known, why + ": and nothing else, but also '" + line + "' in " + problems);
        }
        assertEquals(parts.length, problems.size(), why + ": one line a fault: " + problems);
    }

    private static void passes(Plan p, String tier, String why) {
        assertEquals(List.of(), check(p, tier), why);
    }

    /** The checkpoint index (0-based) of the {@code n}th mark of {@code kind}. */
    private static int mark(HandMountain m, String kind, int n) {
        int seen = 0;
        for (int i = 0; i < m.kinds.size(); i++) {
            if (m.kinds.get(i).equals(kind) && seen++ == n) {
                return i;
            }
        }
        throw new IllegalStateException("no " + kind + " number " + n);
    }

    private static Plan withoutCheckpoints(Plan p, int... indices) {
        Course c = HandRun.course(p);
        List<Course.Mark> cps = new ArrayList<>(c.checkpoints());
        java.util.Arrays.sort(indices);
        for (int i = indices.length - 1; i >= 0; i--) {
            cps.remove(indices[i]);
        }
        Course f = c.withCheckpoints(cps);
        return HandRun.withCourse(p, f.withMinSeconds(MountainValidator.minSeconds(f)), HandRun.refMs(p));
    }

    /** {@code p} moved by (dx, dy, dz): every block, sign, box, mark and the half. */
    static Plan shifted(Plan p, int dx, int dy, int dz) {
        List<BlockOp> ops = new ArrayList<>(p.ops().size());
        for (BlockOp op : p.ops()) {
            ops.add(new BlockOp(op.x() + dx, op.y() + dy, op.z() + dz, op.state()));
        }
        List<SignText> signs = new ArrayList<>();
        for (SignText s : p.signs()) {
            signs.add(new SignText(s.x() + dx, s.y() + dy, s.z() + dz, s.blockData(), s.lines()));
        }
        List<Box> boxes = new ArrayList<>();
        for (Box b : p.keepClear()) {
            boxes.add(b.translate(dx, dy, dz));
        }
        Course c = HandRun.course(p);
        Course.Spot s = c.start();
        List<Course.Mark> cps = new ArrayList<>();
        for (Course.Mark m : c.checkpoints()) {
            cps.add(new Course.Mark(m.x() + dx, m.y() + dy, m.z() + dz, m.radius()));
        }
        Course.Mark f = c.finish();
        Course moved = c.withStart(new Course.Spot(s.x() + dx, s.y() + dy, s.z() + dz, s.yaw(), s.pitch()))
                .withCheckpoints(cps).withFinish(new Course.Mark(f.x() + dx, f.y() + dy, f.z() + dz, f.radius()))
                .withFallY(c.fallY() + dy);
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half().translate(dx, dy, dz), p.palette(), ops, signs, boxes,
                new PlannedTrial(moved, HandRun.refMs(p)), p.summary(), p.work());
    }

    // ---- the hand-made mountains ----------------------------------------------------------------------

    @Test
    void aHandMadeMountainOfEveryTierAndBothStylesPasses() {
        for (String tier : List.of("easy", "medium", "hard")) {
            for (boolean slalom : new boolean[]{false, true}) {
                HandMountain m = slalom ? HandMountain.slalom(tier) : HandMountain.road(tier);
                Plan p = m.plan();
                String name = tier + (slalom ? " slalom" : " road");
                assertEquals(slalom, MountainValidator.slalom(p.seed()), name + ": its seed names its style");
                passes(p, tier, name + ": the hand-made mountain is proven");
                assertEquals(MountainValidator.FIRST_ALGO, p.algo(), name + ": an algo-4 plan");
                assertEquals(List.of(), BoatValidator.problems(p, tier), name + ": and through the dispatcher");
                Course c = HandRun.course(p);
                int descent = (int) (c.start().y() - c.finish().y());
                MountainValidator.Rules rules = MountainValidator.Rules.of(tier, slalom);
                assertTrue(descent >= rules.descentMin() && descent <= rules.descentCap(),
                        name + ": falls " + descent + ", a real downhill within the tier's window");
            }
        }
    }

    @Test
    void theMediumRoadHasThePiecesTheRulesAreAbout() {
        Course c = HandRun.course(base);
        assertTrue(c.checkpoints().size() > 64 && c.checkpoints().size() <= MountainValidator.MAX_CHECKPOINTS,
                "more checkpoints than v3's 64 and within v2's 128: " + c.checkpoints().size());
        assertEquals(21, road.lips.size(), "21 drops, the Final Drop last");
        assertEquals(14, road.lips.stream().filter(l -> l[2] == 2).count(), "14 of them 2 blocks: two thirds");
        boolean snow = false;
        boolean stone = false;
        boolean spruce = false;
        boolean overTrack = false;
        for (BlockOp op : base.ops()) {
            String b = Palette.id(base.blockOf(op));
            snow |= b.equals(Palette.SNOW);
            stone |= b.equals(Palette.STONE);
            spruce |= b.equals("minecraft:spruce_leaves");
            int x = op.x() - HandMountain.HALF.minX();
            int z = op.z() - HandMountain.HALF.minZ();
            overTrack |= b.equals(Palette.STONE) && road.level(x, z) != HandMountain.NONE
                    && op.y() == road.level(x, z) + 5;
        }
        assertTrue(snow && stone && spruce, "the mountain's snow, stone and spruce");
        assertTrue(overTrack, "a tunnel: stone 5 over the ice");
        assertTrue(base.ops().size() > 30_000 && base.ops().size() <= MountainValidator.MAX_OPS,
                "a real-sized plan under the cap: " + base.ops().size());
    }

    @Test
    void roundedCornersAsAPlannerDrawsThemPassToo() {
        for (String tier : List.of("easy", "medium", "hard")) {
            HandMountain m = HandMountain.road(tier);
            m.radius = 20;
            passes(m.plan(), tier, tier + " road with quarter rings of radius 20");
        }
    }

    @Test
    void aRealSizedMountainWithItsSkinIsProvenInSeconds() {
        HandMountain m = HandMountain.road("medium");
        m.skin = true;
        Plan p = m.plan();
        assertTrue(p.ops().size() > 250_000 && p.ops().size() <= MountainValidator.MAX_OPS,
                "a moss skin over every column away from the track: " + p.ops().size() + " blocks");
        check(p, "medium"); // warm up
        long t0 = System.nanoTime();
        List<String> problems = check(p, "medium");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(List.of(), problems, "the whole mountain is proven");
        assertTrue(ms < 15_000, "column-sparse: seconds, not minutes, for " + p.ops().size() + " blocks: " + ms + " ms");
    }

    @Test
    void theVerdictIsTheSameAtAnyChunkAlignedSpot() {
        // §10.2: the proof works on half-local integers, so a plan moved anywhere is judged alike
        for (int[] to : new int[][]{{-6080, 0, -2880}, {20000 - 6080, 0, -20000 - 2880}, {-6080 - 4096, 8, 4096}}) {
            Plan moved = shifted(base, to[0], to[1], to[2]);
            passes(moved, "medium", "moved to " + moved.half().describe());
        }
        HandMountain s = HandMountain.slalom("medium");
        passes(shifted(s.plan(), 20000 - 6080, 0, -20000 - 2880), "medium", "a slalom far out of the range too");
    }

    // ---- the mutations of §15 -------------------------------------------------------------------------

    @Test
    void aMissingWallBlockIsRefused() {
        int[] lip = road.lips.get(0);
        int wall = lip[4] + road.h + 1;
        only(HandRun.without(base, wx(lip[3] - 40), lip[5] + 1, wz(wall)), "medium", "W1: a hole in the wall",
                "isn't solid at y " + (lip[5] + 1));
    }

    @Test
    void aFlightZoneWallOneTooLowIsRefused() {
        int[] lip = road.lips.get(0);
        assertEquals(2, lip[2], "fixture: the first drop is a 2");
        int x = wx(lip[3] + 20);
        int z = wz(lip[4] + road.h + 1);
        int topY = lip[5] + 2;
        assertTrue(base.ops().stream().anyMatch(op -> op.x() == x && op.y() == topY && op.z() == z),
                "the landing's wall reaches 2 over the lip's surface");
        only(HandRun.without(base, x, topY, z), "medium", "W2: a wall only 2 over the landing",
                "too low beside the landing");
    }

    @Test
    void aThreeBlockStepIsRefusedAndItsFlightIsTooLongForOneLeg() {
        HandMountain m = HandMountain.road("medium");
        m.drops.set(3, 3);
        m.drops.set(4, 1);
        only(m.plan(), "medium", "V3, V8: a drop of 3 would break a boat; its 62-block zone can't fit a 60 leg",
                "the track drops 3 blocks", "apart; at most 60.0");
    }

    @Test
    void aCheckpointInAFlightZoneIsRefused() {
        int i = mark(road, "after", 2);
        Course.Mark m = HandRun.course(base).checkpoints().get(i);
        int[] lip = road.lips.get(2);
        HandMountain.Leg leg = road.legs.get(lip[0]);
        Course.Mark into = new Course.Mark(m.x() - 4 * leg.dx(), m.y(), m.z() - 4 * leg.dz(), m.radius());
        only(HandRun.withCheckpoint(base, i, into), "medium", "a boat may still be flying there", "is in a flight zone");
    }

    @Test
    void aLegOverSixtyIsRefused() {
        only(withoutCheckpoints(base, mark(road, "after", 1)), "medium", "a reset never sends a boat far back",
                "apart; at most 60.0");
    }

    @Test
    void aResetFacingFarOffItsLaneIsRefused() {
        int i = mark(road, "corner-after", 0);
        Course.Mark m = HandRun.course(base).checkpoints().get(i);
        Course.Mark further = new Course.Mark(m.x(), m.y(), m.z() + 20, m.radius());
        List<String> problems = check(HandRun.withCheckpoint(base, i, further), "medium");
        assertEquals(1, problems.size(), "one line: " + problems);
        assertTrue(problems.get(0).startsWith("checkpoint " + i + "'s reset faces 7")
                        && problems.get(0).endsWith("degrees off the lane (toward the next target); at most 60.0"),
                "the one before the corner now faces its next target over 70 degrees off its lane: " + problems);
    }

    @Test
    void twoDropsInOneLegAreRefused() {
        assertEquals(road.lips.get(1)[0], road.lips.get(2)[0], "fixture: the second and third drops share a traverse");
        only(withoutCheckpoints(base, mark(road, "after", 1), mark(road, "before", 2)), "medium",
                "one checkpoint between every two drops", "2 drops between", "apart; at most 60.0");
    }

    @Test
    void aStrayBlockIsRefused() {
        only(HandRun.with(base, wx(200), 130, wz(100), Palette.GLASS), "medium", "glass away from any wall",
                "a stray minecraft:glass at");
        only(HandRun.with(base, wx(200), 130, wz(100), RaceStand.FLOOR), "medium", "white concrete is the stand's alone",
                "a stray minecraft:white_concrete at");
        passes(HandRun.with(base, wx(200), 130, wz(100), Palette.STONE), "medium", "stone away from the track is a crag");
        passes(HandRun.with(base, wx(200), 130, wz(100), Palette.SNOW), "medium", "and snow is snow");
        only(HandRun.with(base, wx(200), 130, wz(100), Palette.GATE_LEFT), "medium", "a gate colour away from any wall",
                "a stray minecraft:red_concrete at");
    }

    @Test
    void aLeafAtTheWrongDistanceIsRefused() {
        BlockOp leaf = null;
        for (BlockOp op : base.ops()) {
            if (base.blockOf(op).startsWith("minecraft:spruce_leaves")) {
                leaf = op;
                break;
            }
        }
        assertNotNull(leaf, "fixture: a spruce leaf");
        int d = Integer.parseInt(Palette.states(base.blockOf(leaf)).get("distance"));
        Map<int[], String> wrong = new HashMap<>();
        wrong.put(new int[]{leaf.x(), leaf.y(), leaf.z()}, Palette.leaves("spruce", d < 7 ? d + 1 : d - 1));
        only(HandRun.edit(base, op -> false, wrong), "medium", "a leaf the game would change churns the verify pass",
                "1 leaf says the wrong distance");
    }

    @Test
    void theStandObstructedIsRefused() {
        int floorY = RaceStand.floorY(HandMountain.BOTTOM + 1);
        int cx = MountainValidator.standX(base.half());
        int cz = MountainValidator.standZ(base.half());
        only(HandRun.with(base, cx, floorY + 3, cz - 10, Palette.STONE), "medium",
                "nothing but the stand at or over its floor within 16", "within 16.0 of the viewing stand");
        only(HandRun.with(base, cx + 12, floorY, cz + 9, Palette.log("oak")), "medium", "a trunk by the stand",
                "within 16.0 of the viewing stand");
        passes(HandRun.with(base, cx + 12, floorY - 1, cz + 9, Palette.STONE), "medium",
                "under the stand's floor the mountain may rise round it");
        passes(HandRun.with(base, cx + 13, floorY + 5, cz + 11, Palette.STONE), "medium",
                "and more than 16 from its middle, anything at any height");
        only(HandRun.without(base, cx - 3, floorY, cz - 3), "medium", "the platform whole",
                "the stand's platform has a hole");
        only(HandRun.without(base, cx + 3, floorY + 2, cz), "medium", "the rail unbroken", "the stand's rail has a gap");
        List<SignText> noSign = new ArrayList<>(base.signs());
        noSign.removeIf(s -> s.y() == floorY + 1);
        only(HandRun.withSigns(base, noSign), "medium", "its sign", "the stand has no sign");
    }

    @Test
    void theSightLineFromTheStandToTheFinishBlockedIsRefused() {
        // the eye at the platform's middle, floor + 2.6; the rider at the finish, its centre + 1.5: at z
        // 582.5 (18 from the eye, 10 from the finish) the line is at y 112.5 + 5.1 x 10/28 = 114.3
        int cx = MountainValidator.standX(base.half());
        int z = wz(582);
        only(HandRun.with(base, cx, 114, z, Palette.STONE), "medium", "a crag between the stand and the finish",
                "the view from the stand to the finish is blocked at " + cx + " 114 " + z + " (minecraft:stone)");
        passes(HandRun.with(base, cx, 112, z, Palette.STONE), "medium", "a block under the line doesn't block it");
        passes(HandRun.with(base, cx + 2, 114, z, Palette.STONE), "medium", "nor one beside it");
    }

    @Test
    void aGridShortOfTwelveIsRefused() {
        HandMountain shorter = HandMountain.road("medium");
        shorter.pitBack = 30;
        only(shorter.plan(), "medium", "one row fewer: Race Night's 12 don't fit", "the starting grid seats 10");
    }

    @Test
    void aGateOpeningNarrowerThanPIsRefused() {
        HandMountain m = HandMountain.slalom("medium");
        passes(m.plan(), "medium", "fixture: the slalom's 6-wide openings pass");
        m.opening = 4;
        only(m.plan(), "medium", "V5: a 4-wide opening on medium's P of 5", "a gap of 4.0 between walls",
                "no path 5 wide runs from the start to the finish");
    }

    @Test
    void tooManyBlocksAreRefused() {
        List<BlockOp> ops = new ArrayList<>(base.ops());
        List<String> palette = new ArrayList<>(base.palette());
        palette.add(Palette.MOSS);
        short moss = (short) (palette.size() - 1);
        Box half = base.half();
        for (int x = half.minX() + 2; ops.size() <= MountainValidator.MAX_OPS; x++) {
            for (int z = half.minZ() + 2; z < half.maxZ() - 1 && ops.size() <= MountainValidator.MAX_OPS; z++) {
                ops.add(new BlockOp(x, half.minY(), z, moss));
            }
        }
        Plan big = new Plan(base.slot(), base.algo(), base.seed(), half, palette, ops, base.signs(), base.keepClear(),
                base.course(), base.summary(), base.work(), "big");
        only(big, "medium", "V13: the plan cap", (MountainValidator.MAX_OPS + 1) + " blocks is more than 400000");
    }

    @Test
    void anUnalignedOrWrongSizedHalfIsRefused() {
        only(shifted(base, 8, 0, 0), "medium", "V13: a half off the chunk grid", "isn't on chunk corners");
        only(shifted(base, 0, 0, -1), "medium", "in z too", "isn't on chunk corners");
        Plan small = Plan.of(base.slot(), base.algo(), base.seed(), Box.sized(6080, 96, 2880, 480, 176, 624),
                base.palette(), base.ops(), base.signs(), base.keepClear(), base.course(), base.summary(), base.work());
        only(small, "medium", "the half is exactly 480 x 176 x 640", "isn't a Mountain Run v2 half");
        assertFalse(check(V3FixturesHalf.plan(), "medium").isEmpty(), "an algo-3 spiral's half is no v2 half");
    }

    /** The frozen medium spiral, for the size check. */
    private static final class V3FixturesHalf {
        static Plan plan() {
            return com.dierks.homecraft.games.gen.V3Fixtures.boat("medium").plan();
        }
    }

    // ---- v2's own rules -------------------------------------------------------------------------------

    @Test
    void aMountainThatHardlyFallsIsNoDownhill() {
        HandMountain flat = HandMountain.road("medium");
        flat.drops.clear();
        flat.drops.addAll(HandMountain.drops(20, 10, 1));
        only(flat.plan(), "medium", "V8: 31 down, medium falls at least 32",
                "the track falls only 31 blocks from the start to the bottom; a medium road falls at least 32");
        HandMountain steep = HandMountain.road("easy");
        steep.drops.clear();
        steep.drops.addAll(HandMountain.drops(20, 4, 1));
        steep.bands = 6;
        passes(steep.plan(), "easy", "fixture: 25 down on easy is in its 16-28");
        steep.drops.clear();
        steep.drops.addAll(HandMountain.drops(26, 3, 1));
        only(steep.plan(), "easy", "V8: easy falls at most 28",
                "the track falls 30 blocks from the start to the bottom; an easy road falls at most 28");
    }

    @Test
    void theTwoBlockDropsAreCappedPerStyleAndTier() {
        HandMountain more = HandMountain.road("medium");
        more.drops.set(14, 2);
        only(more.plan(), "medium", "two thirds of 21 is 14",
                "15 drops of 2 blocks; a medium road with 21 drops has at most 14");
        HandMountain easy = HandMountain.road("easy");
        easy.drops.set(4, 2);
        only(easy.plan(), "easy", "D3: easy has at most 4 big drops", "5 drops of 2 blocks; an easy road with 14 drops"
                + " has at most 4");
        HandMountain slalom = HandMountain.slalom("easy");
        slalom.drops.set(2, 2);
        slalom.drops.set(4, 2);
        slalom.drops.set(6, 2);
        List<String> p = check(slalom.plan(), "easy");
        assertTrue(p.stream().anyMatch(l -> l.equals("3 drops of 2 blocks; an easy slalom with 8 drops has at most 2")),
                "an easy slalom has at most 2 big drops: " + p);
    }

    @Test
    void blueIceOnEasyIsRefused() {
        HandMountain easy = HandMountain.road("easy");
        Plan p = easy.plan();
        int[] l = easy.lips.get(0);
        Map<int[], String> blue = new HashMap<>();
        blue.put(new int[]{wx(l[3] - 10), l[5], wz(l[4])}, Palette.TRACK_FAST);
        only(HandRun.edit(p, op -> false, blue), "easy", "easy races on packed ice", "blue ice on an easy track");
        passes(HandMountain.road("hard").plan(), "hard", "hard's blue straight is fine");
    }

    @Test
    void theFinalDropIsInTheFinishsOwnLegInFrontOfTheStand() {
        HandMountain early = HandMountain.road("medium");
        early.finalLip = 150;
        early.finalAfter = true;
        only(early.plan(), "medium", "a checkpoint between the Final Drop and the finish",
                "no Final Drop between checkpoint");
        HandMountain far = HandMountain.road("medium");
        far.finalLip = 165;
        only(far.plan(), "medium", "75 before the finish: it can't be in a leg of 60 either",
                "before the finish; at most 70.0, in front of the stand", "apart; at most 60.0");
    }

    @Test
    void upTo128CheckpointsAndNoMore() {
        HandMountain dense = HandMountain.road("medium");
        dense.fill = 12;
        Plan p = dense.plan();
        int n = HandRun.course(p).checkpoints().size();
        assertTrue(n > MountainValidator.MAX_CHECKPOINTS, "fixture: " + n + " checkpoints");
        only(p, "medium", "V6: the course cap", n + " checkpoints is more than 128");
        HandMountain fewer = HandMountain.road("medium");
        fewer.fill = 15;
        Plan q = fewer.plan();
        int m = HandRun.course(q).checkpoints().size();
        assertTrue(m > 100 && m <= 128, "fixture: " + m + " checkpoints, between v3's cap and v2's");
        passes(q, "medium", "v2 allows up to 128 checkpoints");
        assertEquals(128, MountainValidator.MAX_CHECKPOINTS, "§3.5: the v4 cap");
    }

    @Test
    void nothingStandsOnTheHalfsOutermostRing() {
        only(HandRun.with(base, wx(0), 130, wz(300), Palette.STONE), "medium", "W4: nothing within 1 of the half's edge",
                "is on the half's edge");
        only(HandRun.with(base, wx(1), 130, wz(300), Palette.STONE), "medium", "scenery keeps 2 inside",
                "of the area's edge");
        only(HandRun.with(base, wx(479), 130, wz(639), Palette.STONE), "medium", "the far corner too",
                "is on the half's edge");
    }

    @Test
    void theStandTwelveFromTheIceAndElevenIsRefused() {
        // a bay south of the finish band, west of the stand: its corner block 9 west and 8 north of the
        // platform's edge is 12.0 away, a row nearer 11.4
        HandMountain ok = HandMountain.road("medium");
        ok.extra.add(new int[]{222, 576, 228, 589, HandMountain.BOTTOM});
        passes(ok.plan(), "medium", "a bay 12 from the stand's platform is out of reach");
        HandMountain near = HandMountain.road("medium");
        near.extra.add(new int[]{222, 576, 228, 590, HandMountain.BOTTOM});
        only(near.plan(), "medium", "11.4 is within a boat's reach", "the stand is 11.4 blocks from the ice; at least 12.0");
    }

    @Test
    void aTunnelRoofIsFiveOverTheIceAndRockOverTheTrackIsOk() {
        int ice = HandMountain.BOTTOM + 1;
        only(HandRun.with(base, wx(80), ice + 4, wz(HandMountain.FINISH_Z), Palette.STONE), "medium",
                "a roof at 4 bumps heads", "something in the headroom");
        passes(HandRun.with(base, wx(80), ice + 8, wz(HandMountain.FINISH_Z), Palette.SNOW), "medium",
                "more rock or snow over the tunnel");
        only(HandRun.with(base, wx(150), ice + 6, wz(HandMountain.FINISH_Z), Palette.GLASS), "medium",
                "only roofs, rock, trees and moss over the track", "a block over the track");
    }

    @Test
    void aSlalomsGateFieldsMayHavePocketsElsewhereNot() {
        // a slot 2 wide and 3 deep in the corridor's wall (narrow, so its gap is said either way):
        // behind a fence, in a gate field, a slalom's pocket; on a plain traverse, a side pocket
        HandMountain near = HandMountain.slalom("medium");
        near.plan();
        int[] f = near.fences.get(2);
        int zc = near.legs.get(0).z0();
        int side = f[3] < 0 ? 1 : -1; // the fence's closed side
        int w = zc + side * (near.h + 1);
        near.extra.add(new int[]{f[0] + 3, Math.min(w, w + side * 2), f[0] + 4, Math.max(w, w + side * 2), f[4]});
        List<String> inField = check(near.plan(), "medium");
        assertTrue(inField.stream().anyMatch(l -> l.contains("a gap of 2.0")), "the slot's own gap: " + inField);
        assertFalse(inField.stream().anyMatch(l -> l.contains("a side pocket")),
                "§5.4: a pocket in a gate field is a slalom's: " + inField);
        HandMountain far = HandMountain.slalom("medium");
        far.plan();
        HandMountain.Leg t = far.legs.get(2);
        int x = t.x(200);
        int wz = t.z0() + far.h + 1;
        far.extra.add(new int[]{x, wz, x + 1, wz + 2, far.level(x, t.z0())});
        List<String> plain = check(far.plan(), "medium");
        assertTrue(plain.stream().anyMatch(l -> l.contains("a side pocket")),
                "away from the gates the pocket rule holds: " + plain);
    }

    @Test
    void theStyleComesFromTheSeed() {
        HandMountain s = HandMountain.slalom("medium");
        Plan p = s.plan();
        long roadSeed = HandMountain.seedFor(false);
        Plan asRoad = Plan.of(p.slot(), p.algo(), roadSeed, p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                p.course(), p.summary(), p.work());
        List<String> problems = check(asRoad, "medium");
        assertTrue(problems.contains("the track falls only 16 blocks from the start to the bottom; a medium road falls"
                + " at least 32: a real downhill"), "a road seed's plan is judged as a road: " + problems);
        int slalom = 0;
        for (long seed = 0; seed < 100_000; seed++) {
            slalom += MountainValidator.slalom(seed) ? 1 : 0;
        }
        assertTrue(Math.abs(slalom - 50_000) < 1_000, "about half the seeds are slaloms: " + slalom);
        assertEquals(MountainValidator.slalom(0xc454d3d6504ca5bdL), MountainValidator.slalom(0xc454d3d6504ca5bdL),
                "a pure function of the seed");
    }

    // ---- the contract ------------------------------------------------------------------------------------

    @Test
    void theRulesTableIsTheSpecs() {
        // §3.4, §5.3, §5.4: {P, B, 2s (count, or share num/den), descent min, cap, blue, pocket width}
        Object[][] want = {
                {"easy", false, 5, 5, 4, 0, 0, 16, 28, false, 5},
                {"medium", false, 4, 4, 0, 2, 3, 32, 52, true, 4},
                {"hard", false, 4, 3, 0, 4, 5, 44, 64, true, 4},
                {"easy", true, 7, 5, 2, 0, 0, 8, 16, false, 5},
                {"medium", true, 5, 4, 0, 2, 3, 16, 34, true, 5},
                {"hard", true, 4, 3, 0, 4, 5, 20, 40, true, 4}};
        for (Object[] w : want) {
            MountainValidator.Rules r = MountainValidator.Rules.of((String) w[0], (Boolean) w[1]);
            assertEquals(new MountainValidator.Rules((String) w[0], (Boolean) w[1], (Integer) w[2], (Integer) w[3],
                    (Integer) w[4], (Integer) w[5], (Integer) w[6], (Integer) w[7], (Integer) w[8], (Boolean) w[9],
                    (Integer) w[10]), r, w[0] + (r.slalom() ? " slalom" : " road"));
            assertEquals(2, r.maxDrop(), "drops of at most 2 on every tier (no Gate 0)");
        }
        assertEquals(4, MountainValidator.Rules.of("easy", false).bigDrops(22), "D3: easy road, 4 big drops whatever");
        assertEquals(14, MountainValidator.Rules.of("medium", false).bigDrops(21), "medium: two thirds");
        assertEquals(20, MountainValidator.Rules.of("hard", true).bigDrops(25), "hard: four fifths");
        assertEquals("an easy slalom", MountainValidator.Rules.of(" EASY ", true).label(), "any case");
        assertNull(MountainValidator.Rules.of("EEE", false), "a golf mix is no tier");
    }

    @Test
    void theStandSpotIsTheRuntimesContract() {
        Point spot = MountainValidator.standSpot(HandMountain.HALF, 111);
        assertEquals(new Point(6080 + 240.5, 116, 2880 + 600.5), spot,
                "§4.1: the half's middle in x, 40 in from its south edge, finish + 5 (RaceStand.spotV4)");
        assertEquals(40, MountainValidator.STAND_BACK, "RaceStand.STAND_BACK");
        assertEquals(RaceStand.floorY(111), 115, "its platform block one under where players stand");
    }

    @Test
    void aPlanItCantJudgeIsRefusedNeverThrown() {
        assertFalse(check(null, "medium").isEmpty(), "no plan is never a pass");
        assertEquals(List.of("'EEE' isn't an Ice Boat tier"), check(base, "EEE"), "a golf mix");
        Course c = HandRun.course(base);
        Course nowhere = c.withFinish(new Course.Mark(1e9, 1e9, -1e9, 5));
        assertFalse(check(HandRun.withCourse(base, nowhere, 120_000), "medium").isEmpty(),
                "a finish far outside the half is refused, never thrown");
        assertFalse(MountainValidator.problems(base, (MountainValidator.Rules) null).isEmpty(), "no rules, no pass");
    }
}
