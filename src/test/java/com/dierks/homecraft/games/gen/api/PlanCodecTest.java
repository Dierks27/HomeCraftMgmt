package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive's plan codec (GEN-SPEC-KEEP §1): a plan round-trips exactly (every block, sign,
 * keep-clear box and the whole course, trial or golf), the version-1 payload (written by name since
 * version 2 became the default) is pinned by golden bytes and a fixed stored blob decodes to the same
 * plan, and junk of every kind, in either version, is reported as unreadable, never thrown and never
 * half-read. Version 2 itself: {@link PlanCodecV2Test}.
 */
class PlanCodecTest {

    /** A small parkour plan with a bit of everything: blocks, a sign, a keep-clear box, a fall height. */
    static Plan trialPlan() {
        Box half = LegacyBoxes.half(Slots.DAILY_PARKOUR_HARD, 'A');
        List<String> palette = List.of(Palette.PATH_EASY, Palette.CHECKPOINT, Palette.FINISH);
        List<BlockOp> ops = List.of(new BlockOp(4611, 170, 4099, (short) 0), new BlockOp(4620, 171, 4108, (short) 1),
                new BlockOp(4630, 170, 4118, (short) 2));
        List<SignText> signs = List.of(new SignText(4631, 171, 4119, Palette.sign(4), GenCopy.finish()));
        Course c = new Course("fresh_parkour_hard", TrialKind.PARKOUR, "Hard Parkour", Tier.HARD, "",
                new Course.Spot(4611.5, 171.0, 4099.5, 45.0f, 0.0f),
                List.of(new Course.Mark(4620.5, 172.0, 4108.5, 2.2)), new Course.Mark(4630.5, 171.0, 4118.5, 3.0),
                168.0, 12, false, false, 1);
        return Plan.of("fresh_parkour_hard", 1, 0x3f2a91c07d1e55b0L, half, palette, ops, signs,
                List.of(Box.of(4610, 171, 4098, 4613, 174, 4101)), new PlannedTrial(c, 38_000L),
                List.of("3 jumps"), 1234);
    }

    /** A two-hole golf plan with attempts, witness lines, expert and kid strokes. */
    static Plan golfPlan() {
        Box half = LegacyBoxes.half(Slots.TINY_GOLF, 'B');
        List<GolfCourse.Hole> holes = List.of(
                new GolfCourse.Hole(new GolfCourse.Tee(5220.5, 164.0, 4100.5, 180.0f), new GolfCourse.Spot(5220, 162,
                        4112), 2, new GolfCourse.Spot(5217, 161, 4097), new GolfCourse.Spot(5224, 168, 4115)),
                new GolfCourse.Hole(new GolfCourse.Tee(5242.5, 164.0, 4100.5, 0.5f), new GolfCourse.Spot(5242, 162,
                        4118), 3, new GolfCourse.Spot(5239, 161, 4097), new GolfCourse.Spot(5246, 168, 4121)));
        GolfCourse g = new GolfCourse("fresh_tiny_golf", "Tiny Golf", "", false, 1, holes);
        PlannedGolf pg = new PlannedGolf(g, List.of(0, 3), List.of(List.of(new Putt(12.5f, 4)),
                List.of(new Putt(270.0f, 4), new Putt(265.25f, 2))), List.of(1, 2), List.of(2, 4));
        List<String> palette = List.of("minecraft:lime_concrete", "minecraft:black_concrete");
        List<BlockOp> ops = List.of(new BlockOp(5220, 163, 4100, (short) 0), new BlockOp(5220, 162, 4112, (short) 1));
        return Plan.of("fresh_tiny_golf", 2, -77L, half, palette, ops, List.of(), List.of(), pg, List.of(), 99_000);
    }

    @Test
    void aPlanRoundTripsExactlyTrialAndGolf() {
        for (Plan p : List.of(trialPlan(), golfPlan())) {
            PlanCodec.Read read = PlanCodec.decode(PlanCodec.encode(p));
            assertTrue(read.ok(), "a plan the codec wrote reads back: " + read.problem());
            assertEquals(p, read.plan(), "every block, sign, box and the whole course come back exactly");
            assertEquals(p.hash(), read.plan().hash(), "and so its hash names the same layout");
            assertArrayEquals(PlanCodec.payload(p), PlanCodec.payload(read.plan()), "written again, byte for byte");
        }
    }

    @Test
    void theVersionOnePayloadIsPinnedByGoldenBytes() throws Exception {
        byte[] payload = PlanCodec.payload(trialPlan(), PlanCodec.V1);
        assertEquals("HCMP", new String(Arrays.copyOf(payload, 4), java.nio.charset.StandardCharsets.US_ASCII),
                "it starts with its magic");
        assertEquals(PlanCodec.V1, payload[4], "then its version");
        assertEquals(GOLDEN_TRIAL_SHA, sha(payload), "the trial payload's bytes are pinned: a change to the format"
                + " must bump PlanCodec.VERSION and keep reading version 1");
        assertEquals(GOLDEN_GOLF_SHA, sha(PlanCodec.payload(golfPlan(), PlanCodec.V1)), "and the golf payload's");
        assertEquals(trialPlan(), PlanCodec.decode(PlanCodec.encode(trialPlan(), PlanCodec.V1)).plan(),
                "and a version-1 row written by name reads back exactly");
    }

    @Test
    void aFixedStoredBlobDecodesToTheSamePlan() {
        PlanCodec.Read read = PlanCodec.decode(Base64.getDecoder().decode(GOLDEN_TRIAL_GZIP));
        assertTrue(read.ok(), "a blob stored by version 1 still reads: " + read.problem());
        assertEquals(trialPlan(), read.plan(), "to exactly the plan it was written from");
    }

    @Test
    void junkIsReportedAsUnreadableAndNeverThrows() throws Exception {
        assertUnreadable(null, "nothing stored");
        for (int version : new int[]{PlanCodec.V1, PlanCodec.VERSION}) {
            junkOf(version);
        }
    }

    private static void junkOf(int version) throws Exception {
        byte[] good = PlanCodec.encode(trialPlan(), version);
        byte[] payload = PlanCodec.payload(trialPlan(), version);
        assertUnreadable(new byte[0], "an empty blob");
        assertUnreadable("not gzip at all".getBytes(), "text that isn't gzip");
        assertUnreadable(Arrays.copyOf(good, good.length / 2), "a blob cut in half");
        assertUnreadable(gzip("HCMP".getBytes()), "the magic and nothing after it");
        byte[] wrongMagic = payload.clone();
        wrongMagic[0] = 'X';
        assertUnreadable(gzip(wrongMagic), "the wrong magic");
        byte[] newer = payload.clone();
        newer[4] = PlanCodec.VERSION + 1;
        PlanCodec.Read v3 = PlanCodec.decode(gzip(newer));
        assertFalse(v3.ok(), "a newer format is never guessed at");
        assertTrue(v3.problem().contains("format " + (PlanCodec.VERSION + 1)), "and says which: " + v3.problem());
        byte[] older = payload.clone();
        older[4] = 0;
        assertUnreadable(gzip(older), "a format before the first");
        assertUnreadable(gzip(Arrays.copyOf(payload, payload.length - 3)), "a payload cut short");
        byte[] trailing = Arrays.copyOf(payload, payload.length + 1);
        assertUnreadable(gzip(trailing), "a byte after the end");
        byte[] tampered = payload.clone();
        tampered[tampered.length - 1] ^= 0x01; // the reference time: a different course under the old hash
        PlanCodec.Read t = PlanCodec.decode(gzip(tampered));
        assertFalse(t.ok(), "a payload whose blocks don't match its stored hash is not built");
        byte[] huge = payload.clone();
        // the palette count, right after magic(4) version(1) slot(2+18) algo(4) seed(8) half(24)
        int at = 4 + 1 + 2 + "fresh_parkour_hard".length() + 4 + 8 + 24;
        huge[at] = 0x7f;
        assertUnreadable(gzip(huge), "a list claiming billions of entries");
        for (int i = 0; i < 200; i++) {
            byte[] noise = new byte[i * 7 + 1];
            new java.util.Random(i).nextBytes(noise);
            PlanCodec.Read r = PlanCodec.decode(noise);
            assertNull(r.plan(), "random bytes never make a plan (" + i + ")");
            PlanCodec.Read p = PlanCodec.decodePayload(noise);
            assertNull(p.plan(), "nor as a payload (" + i + ")");
        }
    }

    private static void assertUnreadable(byte[] blob, String what) {
        PlanCodec.Read r = PlanCodec.decode(blob);
        assertFalse(r.ok(), what + " is unreadable");
        assertNull(r.plan(), what + " gives no plan");
        assertNotNull(r.problem(), what + " says why");
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The SHA-256 of the version-1 payloads of {@link #trialPlan} and {@link #golfPlan}. */
    static final String GOLDEN_TRIAL_SHA = "9b87462a95ca0b244d1953e5e94d68133c7d3ac3f16f6a6aebe31b349e4dce50";
    static final String GOLDEN_GOLF_SHA = "74efc0b2c0ad88079a3511254649aecb898aa934b24d2da7ff9f49c96662dfad";
    /** {@link #trialPlan} as version 1 stored it (base64 of the gzip blob). */
    static final String GOLDEN_TRIAL_GZIP = "H4sIAAAAAAAA//Nw9g1gZBBKK0otzogvSCzKzi8tis9ILEphYGBgtNeaeKBWLnQDA4MQkMuwgIFBAEgJ2QOJ80A2iG"
            + "ZmkMjNzEtNLkpMK7Eqz8gsSY1Pzs9LLkotSWWQRcjkZKZnlMQn5ZQiSYsgpNPzc1KAsvnJ2WAjGYSAmGEV0AoQzSDE"
            + "AyRWA3k8DIxAnhhUToyBCeRIoIg4VF6cQQ5hZn5idnxxZnpedFF+SWJJZn6erUksIwO7m6efZ7CHIlQnE1QnkBZiBb"
            + "LXAdmsYDl2Y4Ws0tyCYgYwYLnEwJOaZGFpmmhglGaekoY9yNihPAYeDyBXIQDKY4GEJ6PDJuYGkGEOqQlgQx02QPhO"
            + "JgwwAFTDA1XTAFUD5TPOBIFZQAViaIZA+RwwE1KhDCDmgbOAYEoBAP6dSgbtAQAA";
}
