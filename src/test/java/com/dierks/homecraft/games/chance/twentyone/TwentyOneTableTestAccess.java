package com.dierks.homecraft.games.chance.twentyone;

/** Test access to the table's package-private helpers. */
public final class TwentyOneTableTestAccess {
    private TwentyOneTableTestAccess() {
    }

    public static String awayLine(boolean money, int in, int back) {
        return TwentyOneTable.awayLine(money, in, back);
    }
}
