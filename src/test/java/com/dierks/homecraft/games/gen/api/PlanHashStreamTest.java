package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Plan#hash} streamed into the digest (MOUNTAIN-V2-SPEC §3.6 "Memory", F11: no 12-14 MB string for a
 * Mountain Run) names every layout exactly as before: the same 12 hex digits as the text-building hash it
 * replaced ({@link #reference}, kept here verbatim) for plans of every kind, small and far past one flush,
 * with block texts in any script, characters outside the BMP and lone surrogates; and known layouts keep
 * their pinned names, one of them pinned by a row stored before the change.
 */
class PlanHashStreamTest {

    /** The hash as it was before it was streamed: the whole text in one string, then its UTF-8. */
    static String reference(List<String> palette, List<BlockOp> ops, List<SignText> signs, PlannedCourse course) {
        StringBuilder sb = new StringBuilder();
        List<BlockOp> sortedOps = new ArrayList<>(ops == null ? List.of() : ops);
        sortedOps.sort(Comparator.comparingInt(BlockOp::x).thenComparingInt(BlockOp::y).thenComparingInt(BlockOp::z)
                .thenComparing(op -> block(palette, op)));
        for (BlockOp op : sortedOps) {
            sb.append("op ").append(op.x()).append(' ').append(op.y()).append(' ').append(op.z()).append(' ')
                    .append(block(palette, op)).append('\n');
        }
        List<SignText> sortedSigns = new ArrayList<>(signs == null ? List.of() : signs);
        sortedSigns.sort(Comparator.comparingInt(SignText::x).thenComparingInt(SignText::y)
                .thenComparingInt(SignText::z).thenComparing(SignText::blockData)
                .thenComparing(s -> String.join("|", s.lines())));
        for (SignText s : sortedSigns) {
            sb.append("sign ").append(s.x()).append(' ').append(s.y()).append(' ').append(s.z()).append(' ')
                    .append(s.blockData()).append(' ').append(String.join("|", s.lines())).append('\n');
        }
        sb.append(Plan.canonical(course));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String block(List<String> palette, BlockOp op) {
        return palette != null && op.state() < palette.size() ? palette.get(op.state()) : "#" + op.state();
    }

    @Test
    void knownLayoutsKeepTheirPinnedNames() {
        Plan trial = PlanCodecTest.trialPlan();
        assertEquals("eb895a02f7df", trial.hash(), "the codec's trial plan");
        assertEquals("eb895a02f7df", PlanCodec.decode(Base64.getDecoder().decode(PlanCodecTest.GOLDEN_TRIAL_GZIP))
                .plan().hash(), "the very name a version-1 row stored before the hash was streamed");
        assertEquals("cc2b809ba076", PlanCodecTest.golfPlan().hash(), "the codec's golf plan");
        Plan mountain = SyntheticMountain.plan(41);
        assertEquals("0df21f531f06", mountain.hash(), "a Mountain Run v2-sized plan, far past one flush");
        assertEquals(reference(mountain.palette(), mountain.ops(), mountain.signs(), mountain.course()), mountain.hash(),
                "the same as the whole-text hash");
    }

    @Test
    void theStreamedHashIsTheWholeTextHashForEveryKindOfPlan() {
        SplittableRandom rnd = new SplittableRandom(4);
        String[] texts = {"FINISH!", "GO", "a|pipe", "CHECKPOINT 3", "LEG 7"}; // a sign's lines: plain ASCII
        String[] blocks = {"minecraft:oak_sign[rotation=4]", "minecraft:caf\u00e9_sign", "minecraft:\u65e5\u672c",
                "minecraft:\uD83C\uDFC1_flag", "ends high \uD83C", "\uDF89 starts low", "x\ny"}; // any text at all
        Plan trial = PlanCodecTest.trialPlan();
        Plan golf = PlanCodecTest.golfPlan();
        for (int i = 0; i < 200; i++) {
            int n = i % 20 == 0 ? rnd.nextInt(2_000, 6_000) : rnd.nextInt(0, 60); // some past many flushes
            List<String> palette = new ArrayList<>(List.of("minecraft:stone", "minecraft:oak_sign[rotation=4]",
                    "minecraft:\u00e9_block", "minecraft:\uD83C\uDFC1", "lone high \uD83C", "\uDF89 lone low"));
            List<BlockOp> ops = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                ops.add(new BlockOp(rnd.nextInt(-100, 100), rnd.nextInt(-64, 320), rnd.nextInt(-100, 100),
                        (short) rnd.nextInt(palette.size() + 2))); // and indexes past the palette ("#5")
            }
            List<SignText> signs = new ArrayList<>();
            for (int k = 0; k < rnd.nextInt(0, 30); k++) {
                List<String> lines = new ArrayList<>();
                for (int l = 0; l < 1 + rnd.nextInt(4); l++) {
                    lines.add(texts[rnd.nextInt(texts.length)]);
                }
                signs.add(new SignText(rnd.nextInt(-5, 5), rnd.nextInt(60, 70), rnd.nextInt(-5, 5),
                        blocks[rnd.nextInt(blocks.length)], lines));
            }
            PlannedCourse course = switch (i % 3) {
                case 0 -> trial.course();
                case 1 -> golf.course();
                default -> null;
            };
            assertEquals(reference(palette, ops, signs, course), Plan.hash(palette, ops, signs, course),
                    "plan " + i + " (" + n + " blocks, " + signs.size() + " signs) hashes as the whole text did");
        }
        assertEquals(reference(null, null, null, null), Plan.hash(null, null, null, null), "and nothing at all");
    }

    @Test
    void aGolfCourseWithManyHolesHashesAsTheWholeTextDid() {
        Plan g = PlanCodecTest.golfPlan();
        List<GolfCourse.Hole> holes = new ArrayList<>();
        for (int i = 0; i < 400; i++) { // a course text far past one flush on its own
            holes.add(new GolfCourse.Hole(new GolfCourse.Tee(i + 0.5, 64, i * 2 + 0.5, i * 1.5f),
                    new GolfCourse.Spot(i, 63, i * 3), 2 + i % 4, new GolfCourse.Spot(i - 3, 60, i), null));
        }
        PlannedGolf pg = new PlannedGolf(new GolfCourse("long", "Long", "", false, 1, holes), List.of(), List.of(),
                List.of(), List.of());
        assertEquals(reference(g.palette(), g.ops(), g.signs(), pg), Plan.hash(g.palette(), g.ops(), g.signs(), pg),
                "a 400-hole course's text hashes the same");
        Course c = ((PlannedTrial) PlanCodecTest.trialPlan().course()).course();
        assertEquals(reference(List.of(), List.of(), List.of(), new PlannedTrial(c, 1)),
                Plan.hash(List.of(), List.of(), List.of(), new PlannedTrial(c, 1)), "and a course with no blocks");
    }
}
