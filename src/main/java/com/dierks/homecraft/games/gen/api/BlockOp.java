package com.dierks.homecraft.games.gen.api;

/**
 * One block of a plan (GEN-SPEC §4.0): a position and an index into the plan's
 * {@link Plan#palette()}. A short index rather than the block's text keeps a 40,000-op plan small
 * and lets the builder parse each palette entry once. Air is never an op: wherever a plan has no
 * op, the half is air (§0.2 R3).
 */
public record BlockOp(int x, int y, int z, short state) {

    public BlockOp {
        if (state < 0) {
            throw new IllegalArgumentException("a palette index is never negative: " + state);
        }
    }
}
