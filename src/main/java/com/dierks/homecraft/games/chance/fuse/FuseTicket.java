package com.dierks.homecraft.games.chance.fuse;

/**
 * 0.57.0: one player's place in a Creeper Fuse, as its round's data (pure, no Bukkit).
 *
 * <p>Written when the stake goes in ({@code v=1;in=10;cap=250;top=2000;auto=200}: the stake, the most a cash-out
 * pays, the stake's own top and the auto cash-out, 0 for none); the fuse's draw is added when it is lit, before the
 * multiple starts to climb ({@code ;fuse=<seed>;boom=187}, so every round says where its fuse was going to go off);
 * and the end when it is settled ({@code ;out=150} cashed out at 1.50x, {@code ;out=0} still in when it went off).
 *
 * <p><b>The exit rule</b> ({@link #settleOnExit}): a round still open was never cashed out, because a cash-out and
 * the fuse going off each settle the round in the same transaction that pays it. So whatever stopped it (a crash, a
 * restart, the ten-minute sweep), the stake comes back whole, once: what was locked was paid when it was locked, and
 * nothing is ever paid twice ({@link com.dierks.homecraft.games.ChanceRounds#close} only pays the call that closes it).
 */
public record FuseTicket(int stake, int cap, int top, int auto, long fuseSeed, int boom, int out) {

    /** The data format version written into every round. */
    public static final int VERSION = 1;
    /** Not drawn yet / not ended yet. */
    public static final int NONE = -1;

    /** A new place: the stake in, nothing drawn. */
    public static FuseTicket entry(int stake, int cap, int top, int auto) {
        return new FuseTicket(stake, cap, top, auto, 0, NONE, NONE);
    }

    /** With another auto cash-out (0: none). */
    public FuseTicket withAuto(int m) {
        return new FuseTicket(stake, cap, top, m, fuseSeed, boom, out);
    }

    /** With the fuse's draw written in. */
    public FuseTicket lit(long seed, int boomAt) {
        return new FuseTicket(stake, cap, top, auto, seed, boomAt, out);
    }

    /** Ended: cashed out at {@code m}, or 0 (it went off with them still in). */
    public FuseTicket ended(int m) {
        return new FuseTicket(stake, cap, top, auto, fuseSeed, boom, m);
    }

    /** What it pays as it ended: the cash-out's pay, or nothing. */
    public int payout() {
        return out > 0 ? FuseMath.payout(stake, out, cap) : 0;
    }

    /** The data as stored. */
    public String data() {
        StringBuilder b = new StringBuilder("v=").append(VERSION).append(";in=").append(stake).append(";cap=")
                .append(cap).append(";top=").append(top).append(";auto=").append(auto);
        if (boom != NONE) {
            b.append(";fuse=").append(fuseSeed).append(";boom=").append(boom);
        }
        if (out != NONE) {
            b.append(";out=").append(out);
        }
        return b.toString();
    }

    /** Read a round's data; throws on anything that isn't this format (the framework then gives the stake back). */
    public static FuseTicket read(String data) {
        if (data == null) {
            throw new IllegalArgumentException("no data");
        }
        int version = -1;
        int stake = -1;
        int cap = -1;
        int top = -1;
        int auto = 0;
        long seed = 0;
        int boom = NONE;
        int out = NONE;
        for (String part : data.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String v = part.substring(eq + 1);
            switch (part.substring(0, eq)) {
                case "v" -> version = Integer.parseInt(v);
                case "in" -> stake = Integer.parseInt(v);
                case "cap" -> cap = Integer.parseInt(v);
                case "top" -> top = Integer.parseInt(v);
                case "auto" -> auto = Integer.parseInt(v);
                case "fuse" -> seed = Long.parseLong(v);
                case "boom" -> boom = Integer.parseInt(v);
                case "out" -> out = Integer.parseInt(v);
                default -> {
                    // nothing else is written
                }
            }
        }
        if (version != VERSION || stake <= 0 || cap < stake || top < FuseMath.ONE || auto < 0) {
            throw new IllegalArgumentException("not a Creeper Fuse place of version " + VERSION + ": " + data);
        }
        return new FuseTicket(stake, cap, top, auto, seed, boom, out);
    }

    /**
     * The exit rule ({@link com.dierks.homecraft.games.ExitSettler}): an open round was never cashed out, so its stake
     * comes back. (A round whose data says it ended pays that end; none is left open that way.)
     */
    public static int settleOnExit(long seed, int stake, String data) {
        FuseTicket t = read(data);
        return t.out() != NONE ? t.payout() : t.stake();
    }
}
