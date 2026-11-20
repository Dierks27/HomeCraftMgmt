package com.dierks.homecraft.games.chance.mobraces;

/**
 * The shape of the Mob Races track (pure): a ring one block wide round a table {@value #LENGTH} blocks long and
 * {@value #DEPTH} deep, with an infield {@code LENGTH - 2} by 1 in its middle.
 *
 * <p>Everything is in the table's own measure: {@code u} along its length (0 to {@value #LENGTH}), {@code w} across
 * it (0 to {@value #DEPTH}), from one corner of the table's top. Where the table stands, and which way it lies, is
 * the Clubhouse's to say; this only knows the track.
 *
 * <p><b>The lanes.</b> Six lanes lie across the ring, lane 0 nearest the infield. The racers run with the infield on
 * their right: along the first long side in the direction of {@code u}, round the far end, back along the other long
 * side and round the near end. Every lane is the infield's edge moved out by that lane's distance, so a corner is a
 * quarter turn about the infield's corner. The same distance along a lap puts every lane abreast: level on a
 * straight, at the same angle in a turn. So who is in front on the track is who is in front in the race.
 */
public final class RaceTrack {

    /** The table's size in blocks. */
    public static final int LENGTH = 8;
    public static final int DEPTH = 3;
    /** The infield: {@code u} from 1 to {@code LENGTH - 1}, {@code w} from 1 to 2. */
    public static final double IN_U0 = 1;
    public static final double IN_U1 = LENGTH - 1;
    public static final double IN_W0 = 1;
    public static final double IN_W1 = 2;
    /** The lanes lie this far from the ring's edges, so a racer's whole body stays over the table. */
    public static final double LANE_EDGE = 0.1;
    /** A turn counts as this much of a lap's length: the quarter turn of a lane in the ring's middle. */
    static final double TURN = Math.PI / 4;
    /** The straights' lengths: the two long sides, the two ends. */
    static final double LONG = IN_U1 - IN_U0;
    static final double SHORT = IN_W1 - IN_W0;
    /** One lap, in blocks along the ring's middle. */
    public static final double LAP = 2 * LONG + 2 * SHORT + 4 * TURN;
    /** Where the start and finish line crosses the first long side ({@code u}). */
    public static final double LINE_U = LENGTH / 2.0;

    private RaceTrack() {
    }

    /**
     * A place on the track.
     *
     * @param u       along the table
     * @param w       across the table
     * @param heading the way a racer there runs, in degrees: 0 along {@code u}, 90 along {@code w}
     */
    public record Point(double u, double w, double heading) {
    }

    /** How far lane {@code lane}'s middle is from the infield's edge. */
    public static double laneOut(int lane) {
        if (lane < 0 || lane >= Racer.COUNT) {
            throw new IllegalArgumentException("a lane is 0 to " + (Racer.COUNT - 1) + ": " + lane);
        }
        return LANE_EDGE + (1 - 2 * LANE_EDGE) * (lane + 0.5) / Racer.COUNT;
    }

    /**
     * Where a racer in {@code lane} is after {@code distance} blocks from the start line (any number of laps; the
     * ring's middle measures it).
     */
    public static Point at(int lane, double distance) {
        double out = laneOut(lane);
        // measured from the first long side's start (u = IN_U0), where the line is LINE_U - IN_U0 along
        double s = ((distance + (LINE_U - IN_U0)) % LAP + LAP) % LAP;
        double cu;
        double cw;
        double heading;
        if (s < LONG) { // the first long side, along u
            cu = IN_U0 + s;
            cw = IN_W0;
            heading = 0;
        } else if ((s -= LONG) < TURN) {
            cu = IN_U1;
            cw = IN_W0;
            heading = 90 * s / TURN;
        } else if ((s -= TURN) < SHORT) { // the far end, along w
            cu = IN_U1;
            cw = IN_W0 + s;
            heading = 90;
        } else if ((s -= SHORT) < TURN) {
            cu = IN_U1;
            cw = IN_W1;
            heading = 90 + 90 * s / TURN;
        } else if ((s -= TURN) < LONG) { // the other long side, back
            cu = IN_U1 - s;
            cw = IN_W1;
            heading = 180;
        } else if ((s -= LONG) < TURN) {
            cu = IN_U0;
            cw = IN_W1;
            heading = 180 + 90 * s / TURN;
        } else if ((s -= TURN) < SHORT) { // the near end
            cu = IN_U0;
            cw = IN_W1 - s;
            heading = 270;
        } else {
            s -= SHORT;
            cu = IN_U0;
            cw = IN_W0;
            heading = 270 + 90 * Math.min(1, s / TURN);
        }
        // the lane is to the left of the way they run (the infield is on their right)
        double rad = Math.toRadians(heading);
        return new Point(cu + out * Math.sin(rad), cw - out * Math.cos(rad), heading % 360);
    }

    /** Whether ({@code u}, {@code w}) is on the ring (the table's top, less the infield). */
    public static boolean onRing(double u, double w) {
        boolean table = u >= 0 && u <= LENGTH && w >= 0 && w <= DEPTH;
        boolean infield = u > IN_U0 && u < IN_U1 && w > IN_W0 && w < IN_W1;
        return table && !infield;
    }
}
