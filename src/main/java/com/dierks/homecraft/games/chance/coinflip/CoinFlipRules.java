package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.games.Refusal;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Coin Flip's rules as plain logic (spec §5.7, R1.8): who may be invited, whether a flip may go
 * ahead at the moment the invited player confirms, the flip itself and its short show. No Bukkit:
 * the game gathers the facts (who is online where, what the play gate said, today's counts) and
 * these decide, so every rule is tested without a server.
 *
 * <p>Nothing is trusted from the moment of the invite. An invite only says "would you like to";
 * when the invited player confirms, every check runs again for BOTH players — the full play gate
 * (a pause, a limit or an empty wallet since the invite calls it off), both online, the same
 * world, close enough, the invite not used and not run out, and both daily limits and the pair's
 * — and only then are both players' tokens taken, in one transaction.
 */
public final class CoinFlipRules {

    /** The same two players can't be asked again within this long (either way round). */
    public static final long PAIR_COOLDOWN_MS = 30_000;

    /** The engine version stored with every flip: bump it if {@link #inviterWins} ever changes. */
    public static final int VERSION = 1;

    private CoinFlipRules() {
    }

    /**
     * One player at the moment of a check.
     *
     * @param id         who
     * @param online     whether they are on the server
     * @param world      the world they stand in ({@code null} when offline)
     * @param x          where
     * @param y          where
     * @param z          where
     * @param gate       what the play gate said for this stake ({@code null} = go ahead)
     * @param playsToday their Coin Flip rows today (one per flip they were in, whoever asked)
     */
    public record Side(UUID id, boolean online, String world, double x, double y, double z, Refusal gate,
                       int playsToday) {

        /** Someone who isn't on the server. */
        public static Side offline(UUID id) {
            return new Side(id, false, null, 0, 0, 0, null, 0);
        }
    }

    /** Who wins, from the flip's seed alone: {@code true} = the player who asked. */
    public static boolean inviterWins(long seed) {
        return new SplittableRandom(seed).nextBoolean();
    }

    /** Both in the same world and, unless {@code maxDistance} is 0, within that many blocks. */
    public static boolean inRange(int maxDistance, Side a, Side b) {
        if (a.world() == null || !a.world().equals(b.world())) {
            return false;
        }
        if (maxDistance <= 0) {
            return true;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= (double) maxDistance * maxDistance;
    }

    /**
     * Whether {@code inviter} may ask {@code candidate} to flip for {@code stake}: why not (for
     * the log and the tests — the inviter is never told), or {@code null} if they may.
     *
     * @param takesInvites   the candidate has Coin Flip invites turned on
     * @param waiting        the candidate already has an invite waiting, or is waiting on one of theirs
     * @param cooling        the two had an invite in the last {@link #PAIR_COOLDOWN_MS}
     * @param pairPlaysToday the pair's flips today, either way round
     */
    public static String invitable(CoinFlipSettings s, int stake, Side inviter, Side candidate, boolean takesInvites,
                                   boolean waiting, boolean cooling, int pairPlaysToday) {
        if (candidate.id().equals(inviter.id())) {
            return "that is the inviter";
        }
        if (!candidate.online()) {
            return "not online";
        }
        if (s.odds(stake) == null) {
            return "stake " + stake + " is off";
        }
        if (!inRange(s.maxDistance(), inviter, candidate)) {
            return "not close enough";
        }
        if (!takesInvites) {
            return "Coin Flip invites are off";
        }
        if (waiting) {
            return "an invite is already waiting";
        }
        if (cooling) {
            return "asked less than 30 seconds ago";
        }
        if (candidate.gate() != null) {
            return "the play gate said: " + candidate.gate().message();
        }
        if (candidate.playsToday() >= s.dailyLimit()) {
            return "no flips left today";
        }
        if (pairPlaysToday >= s.pairDailyLimit()) {
            return "the pair has no flips left today";
        }
        return null;
    }

    /**
     * Whether the flip may go ahead when the invited player confirms: why not (for the log; both
     * players only read "The flip was called off."), or {@code null} to flip. {@code a} asked,
     * {@code b} was asked; each side's {@code gate} is the FULL play gate run just now.
     *
     * @param unused  the invite has not been confirmed (or called off) already
     * @param expired the invite has run out
     */
    public static String confirmable(CoinFlipSettings s, int stake, boolean unused, boolean expired, Side a, Side b,
                                     int pairPlaysToday) {
        if (!unused) {
            return "the invite was already used";
        }
        if (expired) {
            return "the invite ran out";
        }
        if (s.odds(stake) == null) {
            return "stake " + stake + " is off now";
        }
        if (!a.online() || !b.online()) {
            return "a player left";
        }
        if (!inRange(s.maxDistance(), a, b)) {
            return "the players are no longer close enough";
        }
        if (a.gate() != null) {
            return "the inviter's play gate said: " + a.gate().message();
        }
        if (b.gate() != null) {
            return "the invited player's play gate said: " + b.gate().message();
        }
        if (a.playsToday() >= s.dailyLimit() || b.playsToday() >= s.dailyLimit()) {
            return "a player has no flips left today";
        }
        if (pairPlaysToday >= s.pairDailyLimit()) {
            return "the pair has no flips left today";
        }
        return null;
    }

    /** One key per pair, the same whoever asked. */
    public static String pairKey(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? x + ":" + y : y + ":" + x;
    }

    /** What both round rows store: the version, the shared pair id, the stake, the winner's share, who won. */
    public static String data(String pair, int stake, int pays, boolean inviterWins) {
        return "v=" + VERSION + ";pair=" + pair + ";stake=" + stake + ";pays=" + pays + ";winner="
                + (inviterWins ? "inviter" : "invited");
    }

    /**
     * The coin's face after each frame of the show ({@code true} = the inviter's side): it turns
     * over every frame and ends on the winner's side. Always {@code frames} long, whoever won.
     */
    public static boolean[] faces(boolean inviterWins, int frames) {
        int n = Math.max(1, frames);
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean last = (n - 1 - i) % 2 == 0;
            out[i] = last == inviterWins;
        }
        return out;
    }

    /**
     * The tick (after the show starts) at which each frame is shown: turns that slow down, a fixed
     * schedule whoever wins, ending at {@code ticks}.
     */
    public static long[] schedule(int frames, int ticks) {
        int n = Math.max(1, frames);
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double x = (double) i / n;
            w[i] = 1 + 2 * x * x;
            sum += w[i];
        }
        long[] gap = new long[n];
        long used = 0;
        for (int i = 0; i < n; i++) {
            gap[i] = Math.max(1, (long) Math.floor(ticks * w[i] / sum));
            used += gap[i];
        }
        for (int i = n - 1; used < ticks; i = i == 0 ? n - 1 : i - 1) {
            gap[i]++;
            used++;
        }
        long[] at = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += gap[i];
            at[i] = t;
        }
        return at;
    }
}
