package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;

import java.util.List;

/**
 * Where the Clubhouse's boards hang (0.38.3; the owner: "the board I have now doesn't work great as it rotates as
 * you walk so it only works if you are directly in front of it. Is there a way to lock it in place?").
 *
 * <p><b>Locked in place.</b> Every board is a text display with a FIXED billboard: flat on its wall like a sign,
 * facing into the room, pulled {@value #OFF_WALL} of a block off the wall's face so it never sinks into it, and
 * drawn at {@value #SCALE}x so it reads from anywhere in the room. In the generated room the results board hangs on
 * the north wall facing south (in front of its black panel), this week's best on the east wall facing west, now
 * playing on the south wall facing north and my stats on the west wall facing east, all with their bottom edge
 * {@value #BOTTOM} blocks above the floor's block: over the windows (3-6) and under the trim (14). An owner-built
 * room's board hangs where the admin stood, facing the way they faced, squared to north, east, south or west.
 *
 * <p><b>The facing.</b> A display's yaw is the way its text faces (0 south, 90 west, 180 north, -90 east): a FIXED
 * display is drawn turned by {@code -yaw} about the vertical, and its text is read from the +z side at yaw 0, as a
 * CENTER billboard's is from a camera looking north. Should a board show its back on the live server, {@link #FRONT}
 * turns every one of them round in one place.
 *
 * <p>On Bedrock, Geyser draws a text display as a floating name tag: it keeps none of this (no fixed rotation, no
 * scale) and turns to face the viewer, with the same words.
 *
 * <p>Pure: the box and the stored spots in, the anchors out.
 */
public final class WallBoards {

    /** The results board (and the admin's word for it: {@code /hcm games clubhouse board}). */
    public static final String RESULTS = "results";
    /** This week's best: the east wall. */
    public static final String FAME = "fame";
    /** Now playing: the south wall. */
    public static final String PLAYING = "playing";
    /** My stats (only the viewer sees theirs): the west wall. */
    public static final String STATS = "stats";
    /** The walls' boards, in the admin's order ({@code board fame|playing|stats}). */
    public static final List<String> WALLS = List.of(FAME, PLAYING, STATS);

    /** Added to every board's yaw: 0, or 180 if the live server shows the boards' backs (one place to turn them). */
    static final float FRONT = 0f;
    /** How far in front of the wall's face a board hangs: never in the blocks, never flickering with them. */
    static final double OFF_WALL = 0.06;
    /** The boards' bottom edge above the floor's block: just over the windows (3-6), on the black panel (7-11). */
    static final double BOTTOM = 7.25;
    /** The generated room's size (10 blocks wide at {@value #LINE_WIDTH} px), and an owner-built room's. */
    static final float SCALE = 2.0f;
    static final float HAND_SCALE = 1.5f;
    /** Where a line wraps (px): about 32 plain characters. */
    static final int LINE_WIDTH = 200;
    /** Every board shows this many lines (a title and 8 rows), padded, so its frame never jumps as it changes. */
    static final int LINES = ClubBoard.MAX_ROWS + 1;
    /** A text line's height on a display (px at scale 1, before the block's 1/40): 9 for the font and 1 between. */
    static final int LINE_PX = 10;
    /** One pixel of a display's text, in blocks, at scale 1. */
    static final double PX = 0.025;

    private WallBoards() {
    }

    /**
     * A board on a wall.
     *
     * @param x         the anchor (the text's bottom middle)
     * @param y         its bottom edge
     * @param z         the anchor
     * @param yaw       the way the text faces (Minecraft yaw: 0 south, 90 west, 180 north, -90 east)
     * @param scale     how big it is drawn (Java only: Geyser ignores it)
     * @param lineWidth where its lines wrap (px)
     */
    public record Wall(double x, double y, double z, float yaw, float scale, int lineWidth) {

        /** Its widest text box (blocks), the background's pixel round it included. */
        public double width() {
            return (lineWidth + 2) * PX * scale;
        }

        /** Its text box's height with {@link #LINES} lines (blocks). */
        public double height() {
            return LINES * LINE_PX * PX * scale;
        }
    }

    /**
     * The generated room's board {@code which} in {@code box}: the results on the north wall facing south, this
     * week's best on the east wall facing west, now playing on the south wall facing north, my stats on the west wall
     * facing east; {@code null} for any other word.
     */
    public static Wall generated(String which, Box box) {
        if (which == null || box == null) {
            return null;
        }
        // the walls' inner faces: north z = minZ + 1, east x = maxX, south z = maxZ, west x = minX + 1 (Box.sized);
        // the room's air runs from minX + 1 to maxX, so its middle is minX + 16 on a 32-block box
        double midX = (box.minX() + 1 + box.maxX()) / 2.0;
        double midZ = (box.minZ() + 1 + box.maxZ()) / 2.0;
        double y = box.minY() + BOTTOM;
        return switch (which) {
            case RESULTS -> new Wall(midX, y, box.minZ() + 1 + OFF_WALL, turn(0f), SCALE, LINE_WIDTH);
            case FAME -> new Wall(box.maxX() - OFF_WALL, y, midZ, turn(90f), SCALE, LINE_WIDTH);
            case PLAYING -> new Wall(midX, y, box.maxZ() - OFF_WALL, turn(180f), SCALE, LINE_WIDTH);
            case STATS -> new Wall(box.minX() + 1 + OFF_WALL, y, midZ, turn(-90f), SCALE, LINE_WIDTH);
            default -> null;
        };
    }

    /**
     * An owner-built room's board: where the admin stood (their back to the wall), facing the way they faced,
     * squared to north, east, south or west.
     */
    public static Wall hand(ClubhouseRoom.Place at) {
        return at == null ? null : new Wall(at.x(), at.y(), at.z(), turn(snap(at.yaw())), HAND_SCALE, LINE_WIDTH);
    }

    /** {@code yaw} to the nearest of 0, 90, 180 and -90 (a board hangs square to its wall). */
    static float snap(float yaw) {
        double y = ((yaw % 360.0) + 360.0) % 360.0;
        int quarter = (int) Math.round(y / 90.0) % 4;
        return switch (quarter) {
            case 1 -> 90f;
            case 2 -> 180f;
            case 3 -> -90f;
            default -> 0f;
        };
    }

    /** {@code yaw} turned by {@link #FRONT}, in -180 to 180. */
    static float turn(float yaw) {
        double y = ((yaw + FRONT) % 360.0 + 360.0) % 360.0;
        return (float) (y > 180.0 ? y - 360.0 : y);
    }

    /** Whether two yaws are the same way, within a degree (a display reads its yaw back as -180 to 180). */
    static boolean sameWay(float a, float b) {
        double d = Math.abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0);
        return d <= 1.0;
    }

    /**
     * {@code text} padded with one-space lines to {@link #LINES}: its title stays at the top of a still frame (a
     * display grows up from its bottom edge). Never cut: a longer text stays as it is.
     */
    static String pad(String text) {
        String t = text == null ? "" : text;
        int lines = t.split("\n", -1).length;
        StringBuilder b = new StringBuilder(t);
        for (int i = lines; i < LINES; i++) {
            b.append("\n ");
        }
        return b.toString();
    }
}
