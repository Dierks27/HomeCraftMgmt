package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Palette;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The magenta arrows in a Mountain Run's walls point the way on the faces a rider sees (MOUNTAIN-V21-SPEC D7).
 * The model is vanilla's (26.2 client assets): blockstates/magenta_glazed_terracotta.json turns the model y 0
 * (south), 90 (west), 180 (north), 270 (east); models/block/template_glazed_terracotta.json turns the texture on
 * the north face 90, south 270, west 0, east 180, up 0 (clockwise as the viewer sees it); the texture's arrow
 * points to the image's top. {@link #shown} is that model; {@link RasterV4#wallArrow} is the rule it pins, and
 * {@link TrackRaster#arrowToward} (the top face's, algo 3) stays as it was.
 */
class WallArrowTest {

    private static final Map<String, Integer> MODEL_Y = Map.of("south", 0, "west", 90, "north", 180, "east", 270);
    private static final Map<String, Integer> FACE_ROT = Map.of("N", 90, "S", 270, "W", 0, "E", 180);
    private static final Map<String, int[]> DIR = Map.of("N", new int[]{0, 0, -1}, "S", new int[]{0, 0, 1},
            "E", new int[]{1, 0, 0}, "W", new int[]{-1, 0, 0}, "U", new int[]{0, 1, 0}, "D", new int[]{0, -1, 0});

    /** Clockwise seen from above, {@code deg} (a multiple of 90): N to E to S to W. */
    private static int[] turn(int[] v, int deg) {
        int x = v[0];
        int z = v[2];
        for (int k = 0; k < Math.floorMod(deg, 360) / 90; k++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[]{x, v[1], z};
    }

    /** The name (N, S, E, W, U, D) of unit vector {@code v}. */
    static String name(int[] v) {
        for (Map.Entry<String, int[]> e : DIR.entrySet()) {
            if (Arrays.equals(e.getValue(), v)) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException(Arrays.toString(v));
    }

    /** The unit vector {x, y, z} of way {@code name} (N, S, E, W, U, D). */
    static int[] dir(String name) {
        return DIR.get(name);
    }

    /** The way the arrow points on world face {@code face} (N, S, E, W, U) of the block facing {@code facing}. */
    static String shown(String facing, String face) {
        int y = MODEL_Y.get(facing);
        if (face.equals("U")) {
            return name(turn(DIR.get("N"), y)); // up: the texture's top is north, turned with the block
        }
        String model = name(turn(DIR.get(face), -y)); // the model face that lands on this world face
        int[] n = DIR.get(model);
        int[] right = {n[2], 0, -n[0]}; // the viewer looks along -n; right = (-n) x up
        int[] up = {0, 1, 0};
        int[] arrow = switch (FACE_ROT.get(model)) {
            case 0 -> up;
            case 90 -> right;
            case 180 -> new int[]{0, -1, 0};
            default -> new int[]{-right[0], 0, -right[2]};
        };
        return name(turn(arrow, y));
    }

    /** The facing of an arrow block's text. */
    static String facing(String block) {
        return block.substring(block.indexOf("facing=") + 7, block.length() - 1);
    }

    @Test
    void theModelReadsAsVanillaShowsIt() {
        // the top face: the frozen planners' rule (an arrow pointing east is the block facing west)
        assertEquals("E", shown("west", "U"), "the top of a block facing west points east");
        assertEquals("N", shown("south", "U"), "the top of a block facing south points north");
        // a side face square to the facing shows the arrow flat, 90 clockwise of the facing
        assertEquals("E", shown("north", "N"), "facing north, its north face points east");
        assertEquals("E", shown("north", "S"), "and its south face too");
        assertEquals("U", shown("north", "E"), "its east face stands the arrow up");
        assertEquals("D", shown("north", "W"), "its west face down");
        // 0.37.0's bug: travelling east, facing west shows down on the left wall's face and up on the right's
        assertEquals("D", shown("west", "S"), "the old left wall's face points down");
        assertEquals("U", shown("west", "N"), "the old right wall's face points up");
    }

    @Test
    void theWallArrowsPointTheWayOnBothWalls() {
        // {way, dx, dz, the left wall's face onto the lane, the right wall's}
        Object[][] cases = {{"E", 1, 0, "S", "N"}, {"W", -1, 0, "N", "S"}, {"S", 0, 1, "W", "E"},
                {"N", 0, -1, "E", "W"}};
        for (Object[] c : cases) {
            String f = facing(RasterV4.wallArrow((int) c[1], (int) c[2]));
            assertEquals(c[0], shown(f, (String) c[3]), "left wall, way " + c[0]);
            assertEquals(c[0], shown(f, (String) c[4]), "right wall, way " + c[0]);
        }
        assertEquals(Palette.arrow("north"), RasterV4.wallArrow(1, 0), "east is the block facing north");
        assertEquals(Palette.arrow("south"), RasterV4.wallArrow(-1, 0), "west is facing south");
        assertEquals(Palette.arrow("east"), RasterV4.wallArrow(0, 1), "south is facing east");
        assertEquals(Palette.arrow("west"), RasterV4.wallArrow(0, -1), "north is facing west");
        assertEquals(Palette.arrow("north"), RasterV4.wallArrow(0.7, 0.7), "a tie goes to x, as arrowToward's");
    }

    @Test
    void theFrozenTopFaceRuleStays() {
        assertEquals(Palette.arrow("west"), TrackRaster.arrowToward(1, 0), "algo 3: east is facing west");
        assertEquals(Palette.arrow("east"), TrackRaster.arrowToward(-1, 0), "west is facing east");
        assertEquals(Palette.arrow("north"), TrackRaster.arrowToward(0, 1), "south is facing north");
        assertEquals(Palette.arrow("south"), TrackRaster.arrowToward(0, -1), "north is facing south");
    }
}
