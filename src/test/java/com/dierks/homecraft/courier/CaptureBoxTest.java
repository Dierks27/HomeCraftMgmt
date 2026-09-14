package com.dierks.homecraft.courier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture box must contain the building, whichever way it is turned.
 *
 * <p>This is the invariant the Courier's whole "the field goes back exactly as it was" promise
 * rests on. A block placed outside the snapshotted region is never restored — it stays in the
 * world for good, silently, with nothing left that knows it is there.
 *
 * <p>It has already been wrong once. The box was sized {@code 2 * span + 2 * padding} and
 * centred on the <b>waypoint</b>, but the structure is placed at the waypoint offset by half its
 * own size — so a template of 11 or wider overflowed the box with the shipped padding of 4.
 * Every shipped vanilla house sits within a block or two of that boundary, and
 * {@code courier.buildings} invites admins to drop in commissioned builds of any size.
 *
 * <p>Registry-free: this is arithmetic, so it needs no server and no Bukkit call.
 */
class CaptureBoxTest {

    /** Padding values an admin might plausibly configure. */
    private static final int[] PADDINGS = {0, 1, 2, 3, 4, 8, 16};

    @Test
    void theBoxContainsEveryRotationOfEveryPlausibleTemplate() {
        for (int sizeX = 1; sizeX <= 48; sizeX++) {
            for (int sizeZ = 1; sizeZ <= 48; sizeZ++) {
                for (int padding : PADDINGS) {
                    assertContains(sizeX, sizeZ, padding);
                }
            }
        }
    }

    /**
     * A rotation pivots about the placement origin and can send the structure into any of the
     * four quadrants around it, reaching up to {@code max(sizeX, sizeZ)} away. The box has to
     * cover all four.
     */
    private void assertContains(int sizeX, int sizeZ, int padding) {
        int span = Math.max(sizeX, sizeZ);
        int side = DeliverySite.captureSide(sizeX, sizeZ, padding);

        // Work in coordinates relative to the placement origin, so the arithmetic is the same
        // wherever in the world the delivery lands.
        int at = 0;
        int origin = DeliverySite.captureOrigin(at, side);
        int boxMin = origin;
        int boxMax = origin + side - 1;

        int reachMin = at - span;
        int reachMax = at + span;

        assertTrue(boxMin <= reachMin && reachMax <= boxMax, () -> String.format(
                "a %dx%d structure with padding %d reaches [%d, %d] but the box only covers "
                        + "[%d, %d] — anything outside is captured by nothing and restored by "
                        + "nothing", sizeX, sizeZ, padding, reachMin, reachMax, boxMin, boxMax));
    }

    /**
     * The box is centred on where the structure is PUT, not on the waypoint.
     *
     * <p>Pinned separately because it is the specific mistake that was made: the two points
     * differ by half the structure, and the old code used the wrong one.
     */
    @Test
    void centringOnTheWaypointWouldNotHaveBeenEnough() {
        int sizeX = 11;
        int sizeZ = 11;
        int padding = 4;
        int span = Math.max(sizeX, sizeZ);
        int side = DeliverySite.captureSide(sizeX, sizeZ, padding);

        int waypoint = 0;
        int placementOrigin = waypoint - sizeX / 2; // exactly what build() does

        int wrongMin = waypoint - side / 2;
        int wrongMax = wrongMin + side - 1;
        boolean wrongBoxWouldHaveContainedIt =
                wrongMin <= placementOrigin - span && placementOrigin + span <= wrongMax;
        assertTrue(!wrongBoxWouldHaveContainedIt,
                "this test is asserting nothing — the waypoint-centred box now fits, so the "
                        + "regression it guards can no longer happen and the test should go");

        int rightMin = DeliverySite.captureOrigin(placementOrigin, side);
        int rightMax = rightMin + side - 1;
        assertTrue(rightMin <= placementOrigin - span && placementOrigin + span <= rightMax,
                "the origin-centred box must contain the structure");
    }
}
