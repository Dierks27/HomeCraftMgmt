package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.boat.MountainValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Mountain Run v2's viewing stand at the bottom (MOUNTAIN-V2-SPEC §4.1, §7.4, §12), with no server.
 *
 * <p>Pinned here: {@code RaceStand.spotV4} is exactly the spot the proof checks
 * ({@code MountainValidator.standSpot}: the half's middle x, {@value RaceStand#STAND_BACK} in from its south
 * edge, 5 above the finish) for any half and finish height, so the stand races use is the stand that was
 * proven; {@code of} gives it to an algo-4 layout and the old spot (the half's centre, 5 above the start) to
 * algo 2 and 3 exactly as before; nothing to a hand-built or a non-boat course; and a racer parked on a v2
 * stand faces north, up the mountain, while any other keeps the way they faced.
 */
class RaceStandV4Test {

    @Test
    void spotV4IsTheProofsOwnStandSpotForAnyHalfAndFinish() {
        Box[] halves = {MountainRunsV2.HALF, Box.sized(7136, 96, 2880, 480, 176, 640),
                Box.sized(-4096, 0, 8192, 480, 176, 640), Box.sized(16, -48, -640, 64, 32, 96)};
        double[] finishes = {110, 110.5, 96.0001, -20, 250};
        for (Box half : halves) {
            for (double y : finishes) {
                assertEquals(MountainValidator.standSpot(half, y), RaceStand.spotV4(half, y),
                        "RaceStand.spotV4 must equal MountainValidator.standSpot: " + half.describe() + " at " + y);
            }
        }
        assertEquals(MountainValidator.STAND_BACK, RaceStand.STAND_BACK, "one STAND_BACK, 40 (§4.1)");
        assertEquals(40, RaceStand.STAND_BACK, "§4.1: 40 in from the half's south edge");
    }

    @Test
    void theV2StandIsAtTheBottomInFrontOfTheFinish() {
        Box half = MountainRunsV2.HALF;
        Point p = RaceStand.spotV4(half, 110);
        assertEquals(new Point(6080 + 240 + 0.5, 115, 2880 + 600 + 0.5), p,
                "local (240, 600), 5 above the finish: players stand on the platform's top");
        assertEquals(3480, RaceStand.centreZV4(half), "its centre row");
        assertEquals(RaceStand.floorY(110), (int) Math.floor(p.y()) - 1, "the floor is the block under their feet");
    }

    @Test
    void ofGivesAnAlgo4LayoutTheStandByItsFinish() {
        Course v2 = MountainRunsV2.road();
        Box half = MountainRunsV2.HALF;
        assertEquals(RaceStand.spotV4(half, v2.finish().y()), RaceStand.of(v2, half),
                "RaceTrack.freshStand and PartyRaces read the v2 stand through RaceStand.of");
        Course later = MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000, 5, 7));
        assertEquals(RaceStand.spotV4(half, later.finish().y()), RaceStand.of(later, half), "and a later algo too");
        Course noFinish = v2.withFinish(null);
        assertNull(RaceStand.of(noFinish, half), "a v2 course with no finish yet has no stand");
    }

    @Test
    void algo2And3KeepTheStandOverTheStartExactlyAsBefore() {
        Box half = Box.sized(6080, 160, 5888, 128, 16, 128);
        Course v3 = MountainRuns.medium();
        assertEquals(RaceStand.spot(half, v3.start().y()), RaceStand.of(v3, half), "algo 3: the centre, 5 over the start");
        assertEquals(new Point(6080 + 64 + 0.5, v3.start().y() + 5, 5888 + 64 + 0.5), RaceStand.of(v3, half),
                "the same numbers as 0.36");
        Course v2loop = MountainRuns.medium(MountainRuns.tag(2, 7));
        assertEquals(RaceStand.spot(half, v2loop.start().y()), RaceStand.of(v2loop, half), "algo 2 as well");
        assertNull(RaceStand.of(MountainRuns.medium(MountainRuns.tag(1, 7)), half), "algo 1 never had one");
        assertNull(RaceStand.of(MountainRunsV2.of(null), MountainRunsV2.HALF), "a hand-built track has none");
        assertNull(RaceStand.of(MountainRunsV2.road(), null), "and nothing without its half");
    }

    @Test
    void aRacerParkedOnAV2StandFacesNorthUpTheMountain() {
        assertEquals(180f, RaceStand.facing(MountainRunsV2.road(), 12f), "north: yaw 180, up the face (§7.4)");
        assertEquals(180f, RaceStand.facing(MountainRunsV2.slalom(), -40f), "on either style");
        assertEquals(12f, RaceStand.facing(MountainRuns.medium(), 12f), "the spiral's racers keep their way");
        assertEquals(-40f, RaceStand.facing(MountainRunsV2.of(null), -40f), "so does a hand-built track's");
        assertEquals(7f, RaceStand.facing(null, 7f), "and nothing's");
    }
}
