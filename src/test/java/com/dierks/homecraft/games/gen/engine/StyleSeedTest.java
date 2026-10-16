package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Decision;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Kind;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Pin;
import com.dierks.homecraft.games.gen.engine.GenScheduler.SlotView;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which seed a Mountain Run v2 week uses (MOUNTAIN-V2-SPEC §5.1, red-team F05 replacing its Race Night row),
 * pure: {@link StyleSeed} and the scheduler's use of it.
 *
 * <p>Pinned here: seed<sub>0</sub> is today's seed, label unchanged; {@code style: road} and {@code slalom}
 * are met every week (over 2,000 editions) by the first seed<sub>k</sub> of that style; {@code random} with
 * Race Night off keeps every week's own seed (about half and half), and with Race Night on is the Winding Road
 * every week; only Ice Boat in a Mountain Run v2 half is ever steered (every other slot, and Ice Boat still in
 * its 0.36 box, keeps its seed exactly); the scheduler builds from the steered seed, and a pin is used as
 * given; a random candidate of a style is one.
 */
class StyleSeedTest {

    private static final long SECRET = 0x5eed_5eedL;
    private static final String BOAT = Slots.ICE_BOAT.id();
    private static final Box V2_HALF = Box.sized(6080, 96, 2880, 480, 176, 640);
    private static final long MON_28_SEP = 20724;

    @Test
    void seedZeroIsTodaysSeedWithItsLabelUnchanged() {
        assertEquals("gen:fresh_boat@7", StyleSeed.label(BOAT, 7, 0, 0), "k = 0: today's label");
        assertEquals("gen:fresh_boat@7#2", StyleSeed.label(BOAT, 7, 2, 0), "with a reroll");
        assertEquals("gen:fresh_boat@7~3", StyleSeed.label(BOAT, 7, 0, 3), "k >= 1 appends ~k");
        assertEquals("gen:fresh_boat@7#2~15", StyleSeed.label(BOAT, 7, 2, 15), "after the reroll");
        for (long day = MON_28_SEP; day < MON_28_SEP + 70; day += 7) {
            assertEquals(GenSeed.seed(SECRET, 7, day, BOAT, 0), StyleSeed.seed(SECRET, 7, day, BOAT, 0, 0),
                    "seed_0 is GenSeed.seed exactly");
            assertEquals(GenSeed.seed(SECRET, 7, day, BOAT, 0), StyleSeed.seed(SECRET, 7, day, BOAT, 0, (BoatStyle) null),
                    "no style wanted: the edition's own seed");
            assertEquals(CabinetGame.seed(SECRET, Edition.index(7, day), "gen:fresh_boat@7~1"),
                    StyleSeed.seed(SECRET, 7, day, BOAT, 0, 1), "seed_1: the same HMAC over the label with ~1");
        }
    }

    @Test
    void aForcedStyleIsMetEveryWeekByItsFirstSeedOfThatStyle() {
        for (BoatStyle want : BoatStyle.values()) {
            int moved = 0;
            for (int n = 0; n < 2_000; n++) {
                long day = MON_28_SEP + 7L * n;
                long s = StyleSeed.seed(SECRET, 7, day, BOAT, n % 3, want);
                assertEquals(want, BoatStyle.of(s), want + " week " + n + ": always met");
                int k = 0;
                while (BoatStyle.of(StyleSeed.seed(SECRET, 7, day, BOAT, n % 3, k)) != want) {
                    k++;
                }
                assertEquals(StyleSeed.seed(SECRET, 7, day, BOAT, n % 3, k), s, "the first seed_k of that style");
                moved += k > 0 ? 1 : 0;
            }
            assertTrue(moved > 900 && moved < 1_100, want + ": about half the weeks needed a seed_k past 0: " + moved);
        }
    }

    @Test
    void theRuleRoadOrSlalomAsConfiguredAndTheWindingRoadWhileRaceNightIsOn() {
        assertEquals(BoatStyle.ROAD, StyleSeed.want(Slots.ICE_BOAT, V2_HALF, BoatStyle.ROAD, false), "road");
        assertEquals(BoatStyle.ROAD, StyleSeed.want(Slots.ICE_BOAT, V2_HALF, BoatStyle.ROAD, true), "road, night on");
        assertEquals(BoatStyle.SLALOM, StyleSeed.want(Slots.ICE_BOAT, V2_HALF, BoatStyle.SLALOM, false), "slalom");
        assertEquals(BoatStyle.SLALOM, StyleSeed.want(Slots.ICE_BOAT, V2_HALF, BoatStyle.SLALOM, true),
                "slalom even with Race Night on (the owner asked; Race Night then skips it, saying why)");
        assertNull(StyleSeed.want(Slots.ICE_BOAT, V2_HALF, null, false), "random, no Race Night: the week's own");
        assertEquals(BoatStyle.ROAD, StyleSeed.want(Slots.ICE_BOAT, V2_HALF, null, true),
                "F05: random with Race Night on is the Winding Road every week");
        Box old = LegacyBoxes.v036(Slots.ICE_BOAT, 'A');
        assertNull(StyleSeed.want(Slots.ICE_BOAT, old, BoatStyle.SLALOM, true),
                "Ice Boat in its 0.36 box (the spiral, no styles): its own seed, exactly as before");
        for (Slots.Def d : Slots.ALL) {
            if (!d.generator().equals(Slots.BOAT)) {
                assertNull(StyleSeed.want(d, V2_HALF, BoatStyle.ROAD, true), d.id() + " is never steered");
            }
        }
        assertNull(StyleSeed.want(null, V2_HALF, BoatStyle.ROAD, true), "no slot: nothing");
    }

    @Test
    void randomWithNoRaceNightIsAboutHalfAndHalfAndRaceNightMakesEveryWeekARoad() {
        int roads = 0;
        int n = 4_000;
        for (int i = 0; i < n; i++) {
            long day = MON_28_SEP + 7L * i;
            BoatStyle off = BoatStyle.of(StyleSeed.seed(SECRET, 7, day, BOAT, 0,
                    StyleSeed.want(Slots.ICE_BOAT, V2_HALF, null, false)));
            roads += off == BoatStyle.ROAD ? 1 : 0;
            assertEquals(BoatStyle.ROAD, BoatStyle.of(StyleSeed.seed(SECRET, 7, day, BOAT, 0,
                    StyleSeed.want(Slots.ICE_BOAT, V2_HALF, null, true))), "week " + i + ": Race Night on, a road");
        }
        assertTrue(Math.abs(roads - n / 2) < n * 0.04, "random with no night: about half roads: " + roads + "/" + n);
    }

    @Test
    void theSchedulerBuildsFromTheSteeredSeedAndAPinAsGiven() {
        RestartHold hold = new RestartHold(List.of(LocalTime.of(4, 0)), GenKit.ZONE, 5);
        Edition weekly = new Edition(GenKit.ZONE, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, null);
        DailySettings s = GenKit.weekly(BOAT);
        long now = GenKit.at(2026, 9, 28, 9, 0);
        long own = GenSeed.seed(SECRET, 7, MON_28_SEP, BOAT, 0);
        BoatStyle other = BoatStyle.of(own) == BoatStyle.ROAD ? BoatStyle.SLALOM : BoatStyle.ROAD;
        Decision plain = GenScheduler.decide(view(null, null), now, 0, s, hold, weekly);
        assertEquals(Kind.BUILD, plain.kind(), "fixture: a build is due");
        assertEquals(own, plain.seed(), "no style: the edition's own seed, as before");
        Decision steered = GenScheduler.decide(view(null, other), now, 0, s, hold, weekly);
        assertEquals(other, BoatStyle.of(steered.seed()), "a view that names a style builds that style");
        assertEquals(StyleSeed.seed(SECRET, 7, MON_28_SEP, BOAT, 0, other), steered.seed(), "from StyleSeed");
        assertNotEquals(own, steered.seed(), "fixture: not the week's own seed");
        Decision same = GenScheduler.decide(view(null, BoatStyle.of(own)), now, 0, s, hold, weekly);
        assertEquals(own, same.seed(), "a week whose own seed has the style keeps it");
        long pinned = 0x5eedL;
        Decision pin = GenScheduler.decide(view(new Pin(pinned, 4, 0), other), now, 0, s, hold, weekly);
        assertEquals(pinned, pin.seed(), "an admin's pinned seed is used as given, whatever its style");
    }

    private static SlotView view(Pin pin, BoatStyle style) {
        return new SlotView(BOAT, true, false, null, true, "medium", "medium", 0, pin, 4, 0, 0, 0, false, SECRET, 0,
                style);
    }

    @Test
    void aRandomCandidateOfAStyleIsOne() {
        AtomicLong next = new AtomicLong(1);
        for (int i = 0; i < 200; i++) {
            assertEquals(BoatStyle.SLALOM, BoatStyle.of(StyleSeed.random(next::getAndIncrement, BoatStyle.SLALOM)),
                    "a candidate of the Slalom");
            assertEquals(BoatStyle.ROAD, BoatStyle.of(StyleSeed.random(next::getAndIncrement, BoatStyle.ROAD)),
                    "a candidate of the Winding Road");
        }
        List<Long> drawn = new ArrayList<>();
        long first = StyleSeed.random(() -> {
            long s = 1000 + drawn.size();
            drawn.add(s);
            return s;
        }, null);
        assertEquals(1000, first, "no style: the first draw");
        assertEquals(1, drawn.size(), "and only one");
        long road = 0;
        while (BoatStyle.of(road) != BoatStyle.ROAD) {
            road++;
        }
        long only = road;
        assertEquals(only, StyleSeed.random(() -> only, BoatStyle.SLALOM),
                "64 draws with none of the style: the first draw (never a loop forever)");
        assertEquals("the Winding Road", StyleSeed.words(BoatStyle.ROAD), "the admin's words");
        assertEquals("either style", StyleSeed.words(null), "for random");
    }
}
