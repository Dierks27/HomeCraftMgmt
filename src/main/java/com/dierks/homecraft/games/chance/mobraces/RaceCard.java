package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.games.RtpLimits;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * One race's card (pure): each racer's form today and its exact chance of winning, fixed from the moment the race is
 * open for entries until it is run (the owner: "gave us real odds and real stats on the mobs. So you could actually
 * win more on longshots and also have a bit of info on the mob").
 *
 * <p><b>The stats make the chances.</b> Every racer has three fixed ratings, 1 to 5 ({@link Racer#speed()},
 * {@link Racer#stamina()}, {@link Racer#start()}), and today's form, drawn when the card is made, {@value #FORM_MIN}
 * to +{@value #FORM_MAX}. Its strength is
 *
 * <pre>    strength = Speed + Stamina + Start + {@value #FORM_WEIGHT} x Form</pre>
 *
 * and its chance is the share {@code e^(strength / }{@value #TEMPERATURE}{@code )} has of all six, each then held
 * between {@value #LOW} and {@value #HIGH} parts in {@value #WHOLE} (4% to 45%: there is always a real favourite and
 * real longshots, and nothing is near-certain) with the others scaled so they still add up. The chances are kept as
 * whole parts in {@value #WHOLE} that add up to exactly {@value #WHOLE}, and those parts are what is drawn and paid
 * on: the winner is the race seed's first draw, {@code nextInt(}{@value #WHOLE}{@code )}, landing in its racer's
 * parts ({@link #winner(long, int[])}). Nothing else decides it.
 *
 * <p><b>What a pick pays</b> ({@link #pay(int, int, double, int)}): for each racer and each stake, the whole amount
 * whose return ({@code chance x pays / stake}) is closest to the game's target, of those inside the 85-95 band locked
 * in code and no more than the cap. A pair with no such amount isn't offered. So a favourite at 40% pays about 2.25
 * times the stake and a longshot at 5% about 18 times, and every pick gives back about the same overall.
 */
public final class RaceCard {

    /** Chances are whole parts of this. */
    public static final int WHOLE = 10_000;
    /** No racer's chance is under this many parts (4%) or over this many (45%). */
    public static final int LOW = 400;
    public static final int HIGH = 4_500;
    /** Today's form: from this to {@link #FORM_MAX}. */
    public static final int FORM_MIN = -2;
    public static final int FORM_MAX = 2;
    /** How much one step of form counts against the ratings. */
    public static final int FORM_WEIGHT = 2;
    /** How far apart strengths spread the chances: higher is closer together. */
    public static final double TEMPERATURE = 4.5;
    /** The favourite's mark on the board and the screen: a small gold sparkle. */
    public static final String FAVOURITE = "&6✦";
    /** The longest shot's mark: a small aqua arrow flying far (both from the Dingbats, which every font here has). */
    public static final String LONGEST = "&b➹";

    private final long seed;
    private final int[] form;
    private final int[] parts;

    private RaceCard(long seed, int[] form, int[] parts) {
        this.seed = seed;
        this.form = form;
        this.parts = parts;
    }

    /** The card drawn from {@code seed}: the same seed, the same card. */
    public static RaceCard of(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int[] form = new int[Racer.COUNT];
        for (int i = 0; i < Racer.COUNT; i++) {
            form[i] = FORM_MIN + r.nextInt(FORM_MAX - FORM_MIN + 1);
        }
        return new RaceCard(seed, form, chances(form));
    }

    /** The card for this form (tests, and a card read back). */
    public static RaceCard withForm(int[] form) {
        if (form == null || form.length != Racer.COUNT) {
            throw new IllegalArgumentException("a form for each racer");
        }
        for (int f : form) {
            if (f < FORM_MIN || f > FORM_MAX) {
                throw new IllegalArgumentException("form is " + FORM_MIN + " to " + FORM_MAX + ": " + f);
            }
        }
        return new RaceCard(0L, form.clone(), chances(form));
    }

    /** {@code racer}'s strength with {@code form}: Speed + Stamina + Start + 2 x Form. */
    public static int strength(Racer racer, int form) {
        return racer.speed() + racer.stamina() + racer.start() + FORM_WEIGHT * form;
    }

    /**
     * The chances for this form, as whole parts of {@value #WHOLE} that add up to it exactly, each from {@value #LOW}
     * to {@value #HIGH}: the shares of {@code e^(strength / T)}, held inside those limits with the rest scaled to fit,
     * then rounded by the largest remainders.
     */
    static int[] chances(int[] form) {
        double[] q = new double[Racer.COUNT];
        double least = Double.MAX_VALUE;
        for (Racer r : Racer.values()) {
            q[r.lane()] = StrictMath.exp(strength(r, form[r.lane()]) / TEMPERATURE);
            least = Math.min(least, q[r.lane()]);
        }
        // the scale whose held shares add up to WHOLE: the sum rises with the scale, so halve the gap until it fits
        double lo = 0;
        double hi = WHOLE / least;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            double sum = 0;
            for (double v : q) {
                sum += held(mid * v);
            }
            if (sum < WHOLE) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double[] x = new double[Racer.COUNT];
        int[] out = new int[Racer.COUNT];
        int given = 0;
        for (int i = 0; i < Racer.COUNT; i++) {
            x[i] = held(hi * q[i]);
            out[i] = (int) Math.floor(x[i]);
            given += out[i];
        }
        // what rounding down left over goes one part each to the largest remainders (the lower lane first)
        while (given < WHOLE) {
            int best = -1;
            for (int i = 0; i < Racer.COUNT; i++) {
                if (out[i] < HIGH && (best < 0 || x[i] - out[i] > x[best] - out[best])) {
                    best = i;
                }
            }
            out[best]++;
            x[best] = out[best]; // its remainder is used
            given++;
        }
        while (given > WHOLE) { // never in practice: the held shares add up to WHOLE, give or take the last bit
            int most = 0;
            for (int i = 1; i < Racer.COUNT; i++) {
                if (out[i] > out[most]) {
                    most = i;
                }
            }
            out[most]--;
            given--;
        }
        return out;
    }

    private static double held(double v) {
        return Math.max(LOW, Math.min(HIGH, v));
    }

    /** The seed it was drawn from (0 for one made from a form). */
    public long seed() {
        return seed;
    }

    /** {@code racer}'s form today, {@value #FORM_MIN} to {@value #FORM_MAX}. */
    public int form(Racer racer) {
        return form[racer.lane()];
    }

    /** {@code racer}'s strength today. */
    public int strength(Racer racer) {
        return strength(racer, form(racer));
    }

    /** {@code racer}'s chance of winning, in parts of {@value #WHOLE}. */
    public int parts(Racer racer) {
        return parts[racer.lane()];
    }

    /** Every racer's chance in parts of {@value #WHOLE}, by lane (a copy). */
    public int[] parts() {
        return parts.clone();
    }

    /** {@code racer}'s chance of winning, 0 to 1. */
    public double chance(Racer racer) {
        return parts(racer) / (double) WHOLE;
    }

    /** The racer most likely to win (the lower lane when two are level). */
    public Racer favourite() {
        int best = 0;
        for (int i = 1; i < Racer.COUNT; i++) {
            if (parts[i] > parts[best]) {
                best = i;
            }
        }
        return Racer.of(best);
    }

    /** The racer least likely to win (the lower lane when two are level). */
    public Racer longest() {
        int least = 0;
        for (int i = 1; i < Racer.COUNT; i++) {
            if (parts[i] < parts[least]) {
                least = i;
            }
        }
        return Racer.of(least);
    }

    /** What a pick on {@code racer} pays with {@code stake} on it, or {@code null} when that pair isn't offered. */
    public Pay pay(Racer racer, int stake, double rtp, int cap) {
        return pay(parts(racer), stake, rtp, cap);
    }

    /**
     * What a pick whose chance is {@code parts} in {@value #WHOLE} pays with {@code stake} on it, for a target of
     * {@code rtp} percent, when no play may give back more than {@code cap}: of the whole amounts over the stake and
     * no more than the cap whose return ({@code parts x pays / (WHOLE x stake)}) is inside the 85-95 band, the one
     * closest to the target (the lower of two as close). {@code null} when there is none.
     */
    public static Pay pay(int parts, int stake, double rtp, int cap) {
        if (parts <= 0 || parts > WHOLE || stake <= 0 || cap <= stake) {
            return null;
        }
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
        long want = target.num() * WHOLE * stake; // the target, over (target.den x WHOLE x stake)
        long per = target.den() * parts;          // one whole amount more adds this much on the same footing
        long near = want / per;                    // the whole amount just at or under the target
        Pay best = null;
        long bestOff = Long.MAX_VALUE;
        for (long pays : new long[]{near, near + 1, cap}) {
            if (pays <= stake || pays > cap) {
                continue;
            }
            Pay p = new Pay(stake, (int) pays, parts);
            if (!RtpLimits.inBand(p.ratio())) {
                continue;
            }
            long off = Math.abs(pays * per - want);
            if (off < bestOff || (off == bestOff && pays < best.pays())) {
                best = p;
                bestOff = off;
            }
        }
        return best;
    }

    /**
     * What a pick pays at one stake.
     *
     * @param stake what goes on the racer
     * @param pays  what comes back if it wins (always more than the stake)
     * @param parts its chance, in parts of {@value #WHOLE}
     */
    public record Pay(int stake, int pays, int parts) {

        /** The exact return: {@code parts x pays / (WHOLE x stake)}. */
        public RtpLimits.Ratio ratio() {
            return new RtpLimits.Ratio((long) parts * pays, (long) WHOLE * stake);
        }

        /** The exact return as a fraction. */
        public double rtp() {
            return ratio().value();
        }
    }

    /**
     * Who wins the race drawn from {@code raceSeed} when the chances are {@code parts}: the seed's first draw,
     * {@code nextInt(WHOLE)}, falls in one racer's parts, counted from the inside lane out.
     */
    public static int winner(long raceSeed, int[] parts) {
        return winner(new SplittableRandom(raceSeed), parts);
    }

    /** The same draw, from {@code r} (the race's own first draw). */
    static int winner(SplittableRandom r, int[] parts) {
        int at = r.nextInt(WHOLE);
        for (int i = 0; i < parts.length; i++) {
            at -= parts[i];
            if (at < 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("chances that don't add up to " + WHOLE + ": " + Arrays.toString(parts));
    }

    /** Whether {@code parts} are chances a race can be drawn with: one for each racer, each over 0, adding up to WHOLE. */
    public static boolean valid(int[] parts) {
        if (parts == null || parts.length != Racer.COUNT) {
            return false;
        }
        int sum = 0;
        for (int p : parts) {
            if (p <= 0 || p > WHOLE) {
                return false;
            }
            sum += p;
        }
        return sum == WHOLE;
    }

    /** "1 race in 2.5": how often {@code racer} wins, as a player reads it (a tenth under 10, whole from there). */
    public String oneIn(Racer racer) {
        return oneIn(parts(racer));
    }

    static String oneIn(int parts) {
        double n = WHOLE / (double) parts;
        if (n < 9.95) {
            String s = String.format(java.util.Locale.ROOT, "%.1f", n);
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        }
        return Long.toString(Math.round(n));
    }

    /** "40.25%": the exact chance, for the grown-ups. */
    public String percent(Racer racer) {
        int p = parts(racer);
        return p / 100 + "." + String.format(java.util.Locale.ROOT, "%02d", p % 100) + "%";
    }
}
