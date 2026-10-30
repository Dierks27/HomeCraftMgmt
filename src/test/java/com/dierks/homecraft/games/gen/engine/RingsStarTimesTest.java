package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sky Rings' own star factors ({@code games.fresh.stars.rings}) in the tag a layout goes live with
 * ({@link GenService#tagFor}): its expert time is the autopilot's flown lap, so the shared easy factors would
 * give every clean run three stars. Set, they apply to Sky Rings on every tier and to Classic Sky Rings; missing,
 * Sky Rings takes its tier's like every other course; nothing else is ever touched by them.
 */
class RingsStarTimesTest {

    private static final DailySettings.Stars TIERS = new DailySettings.Stars(
            Map.of("easy", 2.0, "medium", 1.5, "hard", 1.25), Map.of("easy", 3.0, "medium", 2.2, "hard", 1.8));
    private static final DailySettings.Stars RINGS = new DailySettings.Stars(TIERS.gold(), TIERS.silver(),
            new DailySettings.Stars.Rings(1.4, 2.0));

    private static GenTag tag(Slots.Def def, String tier, DailySettings.Stars factors) {
        Plan plan = GenKit.plan(def, def.half('A'), 7L, 1); // its expert time is 30 s
        return GenService.tagFor(def, def.generator(), plan, 0L, 0, 7L, 'A', tier, factors, 0L, 7);
    }

    @Test
    void skyRingsTakesItsOwnFactorsOnEveryTier() {
        for (String tier : Slots.TIERS) {
            GenTag t = tag(Slots.SKY_RINGS, tier, RINGS);
            assertEquals(Stars.threshold(30_000, 1.4), t.goldMs(), tier + ": gold is 1.4 times the lap");
            assertEquals(Stars.threshold(30_000, 2.0), t.silverMs(), tier + ": silver 2.0 times");
        }
        GenTag classic = tag(Slots.CLASSIC_RINGS, "easy", RINGS);
        assertEquals(Stars.threshold(30_000, 1.4), classic.goldMs(), "and so does Classic Sky Rings");
    }

    @Test
    void withoutThemSkyRingsTakesItsTiersAndNoOtherCourseEverTakesThem() {
        GenTag rings = tag(Slots.SKY_RINGS, "easy", TIERS);
        assertEquals(Stars.threshold(30_000, 2.0), rings.goldMs(), "missing: easy's gold");
        assertEquals(Stars.threshold(30_000, 3.0), rings.silverMs(), "and easy's silver");
        GenTag parkour = tag(Slots.DAILY_PARKOUR_EASY, "easy", RINGS);
        assertEquals(Stars.threshold(30_000, 2.0), parkour.goldMs(), "Easy Parkour keeps easy's factors");
        assertEquals(Stars.threshold(30_000, 3.0), parkour.silverMs(), "both of them");
    }
}
