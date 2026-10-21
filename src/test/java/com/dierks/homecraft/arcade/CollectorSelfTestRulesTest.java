package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.CollectorSelfTest.CapFacts;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup self-test's rules (HALLOWEEN-SPEC §3.1 #11, E14, red-team m8a). The cap itself needs a
 * server to build, so the test at enable reads its facts off the real item; here are the judgements:
 * one warning per missing part, a trail particle that needs no data, and a UTC clock under any name.
 */
class CollectorSelfTestRulesTest {

    private static final Prize CAP = row("cap_pumpkin_2026");
    private static final Prize TRAIL = row("trail_leaves_2026");

    private static Prize row(String id) {
        for (Prize p : Collectors.ROWS) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        throw new AssertionError(id + " is in the catalogue");
    }

    private static CapFacts good() {
        return new CapFacts(0xF9801D, true, true, 0, "cap_pumpkin_2026", PrizeType.HAT.name());
    }

    @Test
    void aGoodCapHasNoProblems() {
        assertEquals(List.of(), CollectorSelfTest.capProblems(CAP, good()));
        assertEquals(List.of(), CollectorSelfTest.capProblems(CAP,
                new CapFacts(0xFFF9801D, true, true, 0, "cap_pumpkin_2026", "HAT")), "an alpha byte is not a colour");
    }

    @Test
    void eachMissingPartIsOneWarning() {
        List<CapFacts> broken = List.of(
                new CapFacts(null, true, true, 0, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xA06540, true, true, 0, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xF9801D, false, true, 0, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xF9801D, true, false, 0, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xF9801D, true, true, 1, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xF9801D, true, true, -1, "cap_pumpkin_2026", "HAT"),
                new CapFacts(0xF9801D, true, true, 0, null, "HAT"),
                new CapFacts(0xF9801D, true, true, 0, "cap_pumpkin_2026", null));
        String[] says = {"DYED_COLOR", "DYED_COLOR", "TRIM", "UNBREAKABLE", "gives armour", "ATTRIBUTE_MODIFIERS",
                "hcm:token_prize", "prize_kind"};
        for (int i = 0; i < broken.size(); i++) {
            List<String> problems = CollectorSelfTest.capProblems(CAP, broken.get(i));
            assertEquals(1, problems.size(), "case " + i + ": " + problems);
            assertTrue(problems.get(0).contains(says[i]), problems.get(0));
            assertTrue(problems.get(0).startsWith("cap_pumpkin_2026 "), "it names the cap: " + problems.get(0));
        }
        assertEquals(6, CollectorSelfTest.capProblems(CAP, new CapFacts(null, false, false, 2, null, null)).size(),
                "everything missing is six warnings");
    }

    @Test
    void theTrailsParticleNeedsNoData() {
        assertNull(CollectorSelfTest.particleProblem(TRAIL, true, Void.class));
        assertTrue(CollectorSelfTest.particleProblem(TRAIL, false, null).contains("is not a particle"));
        assertTrue(CollectorSelfTest.particleProblem(TRAIL, true, Color.class).contains("needs data (Color)"));
        // final audit F7: the one line at enable says the trail is held back, and why
        assertTrue(CollectorSelfTest.particleProblem(TRAIL, false, null).contains("is not sold or given (it can't "
                + "show on this server yet)"), "worded as /hcm give prize's answer, with no version");
        assertTrue(CollectorSelfTest.particleProblem(TRAIL, true, Color.class).contains("not sold or given"));
    }

    /** The Santa Cap is checked like the Pumpkin Cap, against its own red dye and quartz rib band. */
    @Test
    void theSantaCapIsCheckedForItsOwnLook() {
        Prize santa = row("cap_santa_2026");
        assertEquals(List.of(), CollectorSelfTest.capProblems(santa,
                new CapFacts(0xB02E26, true, true, 0, "cap_santa_2026", "HAT")));
        List<String> orange = CollectorSelfTest.capProblems(santa,
                new CapFacts(0xF9801D, true, true, 0, "cap_santa_2026", "HAT"));
        assertEquals(1, orange.size(), "the Pumpkin Cap's orange is not the Santa Cap's red: " + orange);
        assertTrue(orange.get(0).startsWith("cap_santa_2026 is not dyed #B02E26"), orange.get(0));
        List<String> noBand = CollectorSelfTest.capProblems(santa,
                new CapFacts(0xB02E26, false, true, 0, "cap_santa_2026", "HAT"));
        assertEquals(List.of("cap_santa_2026 has no trim (TRIM rib/quartz) - it shows without its trim."), noBand);
        assertEquals(1, CollectorSelfTest.capProblems(santa,
                new CapFacts(0xB02E26, true, true, 0, "cap_pumpkin_2026", "HAT")).size(), "its own tag");
    }

    @Test
    void twinklesParticleNeedsNoData() {
        Prize twinkle = row("trail_twinkle_2026");
        assertNull(CollectorSelfTest.particleProblem(twinkle, true, Void.class));
        assertTrue(CollectorSelfTest.particleProblem(twinkle, false, null)
                .startsWith("trail_twinkle_2026's particle ELECTRIC_SPARK is not a particle on this server"));
    }

    /** The self-test covers every collector: two caps (dye, trim) and two trails (particle). */
    @Test
    void theSelfTestCoversEveryCollector() {
        assertEquals(List.of("cap_pumpkin_2026", "cap_santa_2026"), Collectors.ROWS.stream()
                .filter(p -> p.type() == PrizeType.HAT && p.collector().look() != null).map(Prize::id).toList());
        assertEquals(List.of("trail_leaves_2026", "trail_twinkle_2026"), Collectors.ROWS.stream()
                .filter(p -> p.type() == PrizeType.TRAIL).map(Prize::id).toList());
    }

    @Test
    void aUtcClockIsCaughtUnderAnyName() {
        assertTrue(CollectorSelfTest.utc(ZoneOffset.UTC), "the fallback for a blank or unknown zone");
        assertTrue(CollectorSelfTest.utc(ZoneId.of("UTC")));
        assertTrue(CollectorSelfTest.utc(ZoneId.of("Etc/UTC")));
        assertTrue(CollectorSelfTest.utc(ZoneId.of("GMT")));
        assertTrue(CollectorSelfTest.utc(null));
        assertFalse(CollectorSelfTest.utc(ZoneId.of("America/Chicago")), "the shipped zone");
        assertFalse(CollectorSelfTest.utc(ZoneId.of("Europe/London")), "on UTC only in winter: a real choice");
        assertFalse(CollectorSelfTest.utc(ZoneOffset.ofHours(-6)));
    }
}
