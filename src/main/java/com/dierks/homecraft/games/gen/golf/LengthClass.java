package com.dierks.homecraft.games.gen.golf;

/**
 * A Golf v4 hole's length class (GOLF-V4-SPEC §3.3): how long it is, and so the par the ordinary
 * player must measure on it ({@link OrdinaryPar}).
 *
 * <p>The clubs stay as they are (Tap 1.9, Putt 3.7, Chip 5.9, Swing 8.9, Drive 12.9 blocks on flat
 * turf), so the measured par bands (§3.2) put par 2 at 8-12 blocks, par 3 at 16-25, par 4 at 27-38
 * and par 5 at 40-52; each class's routings sit inside its band, clear of the rounding edges. A
 * course deals its classes from its tiers ({@link DealV4}): Golf of the Week's EEEMMMMHH gets
 * S2 M3 L3 X1 or S2 M3 L2 X2. Tiny Golf only ever has S and M.
 */
enum LengthClass {

    /** Par 2: a straight of 8-12. */
    S(2, 8, 12),
    /** Par 3: a straight of 16-24, or a dogleg of 10-14 up and 6-10 across. */
    M(3, 16, 25),
    /** Par 4: a dogleg, a layup, an S-bend or a long straight with a piece. */
    L(4, 27, 38),
    /** Par 5: two or three legs. */
    X(5, 40, 52);

    /** The par the class's holes must measure. */
    final int par;
    /** The class's path band (blocks from the tee to the cup along the lane), as designed. */
    final int shortest;
    final int longest;

    LengthClass(int par, int shortest, int longest) {
        this.par = par;
        this.shortest = shortest;
        this.longest = longest;
    }

    /** The class for a par (2-5); anything longer is X, anything shorter S. */
    static LengthClass ofPar(int par) {
        for (LengthClass c : values()) {
            if (c.par == par) {
                return c;
            }
        }
        return par < S.par ? S : X;
    }

    /** The class's letter in a summary: S, M, L or X. */
    char letter() {
        return name().charAt(0);
    }
}
