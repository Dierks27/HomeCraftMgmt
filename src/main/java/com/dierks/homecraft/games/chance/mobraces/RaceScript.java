package com.dierks.homecraft.games.chance.mobraces;

import java.util.SplittableRandom;

/**
 * One Mob Race, written out before it is run (pure): who wins, the order the six finish in, and where each of them
 * is at every tick. The same seed and length always give the same race.
 *
 * <p><b>The winner is one draw.</b> {@link #winner(long)} is {@code nextInt(6)} of the seed and nothing else, so each
 * racer wins exactly one race in six. That is all that is ever paid on. Everything else here is the show: it is made
 * from the same seed, after the winner, and can never change who won.
 *
 * <p><b>The show.</b> A racer's place is a fraction of the whole race, 0 at the line and 1 at the finish. The winner
 * reaches 1 at tick {@link #ticks()}; the others follow one at a time, the second a clear gap behind
 * ({@link #WIN_GAP_MIN} ticks or more). Each runs at its own changing speed, never backwards and never standing
 * still, so the lead changes hands on the way round. A race is only kept if it is worth watching: the lead changes at
 * least {@link #LEAD_CHANGES} times and the winner is behind someone a third of the way in ({@link #TRIES} tries from
 * the seed; the liveliest is kept if none passes, which a test shows does not happen in practice).
 */
public final class RaceScript {

    /** The gap between the winner and the second home, in ticks. */
    static final int WIN_GAP_MIN = 10;
    static final int WIN_GAP_MAX = 18;
    /** The gap between each of the others, in ticks. */
    static final int GAP_MIN = 4;
    static final int GAP_MAX = 12;
    /** A race is kept when its lead changes this often. */
    static final int LEAD_CHANGES = 3;
    /** How many tries a seed gets at a race worth watching. */
    static final int TRIES = 60;
    /**
     * A racer's speed is drawn afresh about every this many ticks (three seconds), for a race of any length between
     * {@link #STRETCHES_MIN} and {@link #STRETCHES_MAX} times, and may be this far from even.
     */
    private static final int STRETCH_TICKS = 60;
    private static final int STRETCHES_MIN = 6;
    private static final int STRETCHES_MAX = 14;
    private static final double SWING = 0.3;
    /** Lead changes are counted from here on (ticks): all six are level at the line. */
    static final int COUNT_FROM = 10;
    /** How long a racer takes to slow down past the line (ticks), and how far each place runs on (blocks). */
    static final int SLOW_TICKS = 30;
    static final double RUN_ON = 0.3;

    private final long seed;
    private final int ticks;
    /** The racers in the order they finish: {@code order[0]} wins. */
    private final int[] order;
    /** Each racer's place in that order, 0 for the winner. */
    private final int[] place;
    /** The tick each racer reaches the finish. */
    private final int[] finish;
    /** Each racer's place along the race at every tick, 0 to 1. */
    private final double[][] at;
    private final int leadChanges;

    private RaceScript(long seed, int ticks, int[] order, int[] finish, double[][] at, int leadChanges) {
        this.seed = seed;
        this.ticks = ticks;
        this.order = order;
        this.finish = finish;
        this.at = at;
        this.leadChanges = leadChanges;
        this.place = new int[Racer.COUNT];
        for (int p = 0; p < order.length; p++) {
            place[order[p]] = p;
        }
    }

    /** Who wins the race drawn from {@code seed}: every racer exactly as likely as any other. */
    public static int winner(long seed) {
        return new SplittableRandom(seed).nextInt(Racer.COUNT);
    }

    /**
     * The race for {@code seed}, with the winner home at tick {@code ticks}.
     *
     * @throws IllegalArgumentException for a race shorter than five seconds
     */
    public static RaceScript of(long seed, int ticks) {
        if (ticks < 100) {
            throw new IllegalArgumentException("a race is at least 100 ticks: " + ticks);
        }
        SplittableRandom r = new SplittableRandom(seed);
        int winner = r.nextInt(Racer.COUNT); // the same first draw as winner(seed)
        // the order behind the winner: a shuffle of the other five
        int[] order = new int[Racer.COUNT];
        order[0] = winner;
        int n = 1;
        for (int i = 0; i < Racer.COUNT; i++) {
            if (i != winner) {
                order[n++] = i;
            }
        }
        for (int i = Racer.COUNT - 1; i > 1; i--) {
            int j = 1 + r.nextInt(i);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int[] finish = new int[Racer.COUNT];
        finish[order[0]] = ticks;
        int last = ticks;
        for (int p = 1; p < Racer.COUNT; p++) {
            last += p == 1 ? WIN_GAP_MIN + r.nextInt(WIN_GAP_MAX - WIN_GAP_MIN + 1)
                    : GAP_MIN + r.nextInt(GAP_MAX - GAP_MIN + 1);
            finish[order[p]] = last;
        }
        double[][] best = null;
        int bestChanges = -1;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            double[][] at = run(r.split(), finish, last);
            int changes = leadChanges(at, ticks);
            boolean behind = leader(at, ticks / 3) != winner;
            if (changes >= LEAD_CHANGES && behind) {
                best = at;
                bestChanges = changes;
                break;
            }
            int score = behind ? changes : changes - LEAD_CHANGES;
            if (best == null || score > bestChanges) {
                best = at;
                bestChanges = score;
            }
        }
        return new RaceScript(seed, ticks, order, finish, best, leadChanges(best, ticks));
    }

    /** Each racer's place at every tick up to {@code last}: its own speeds, added up, scaled to finish on its tick. */
    private static double[][] run(SplittableRandom r, int[] finish, int last) {
        double[][] at = new double[Racer.COUNT][last + 1];
        int stretches = Math.max(STRETCHES_MIN, Math.min(STRETCHES_MAX, last / STRETCH_TICKS));
        for (int racer = 0; racer < Racer.COUNT; racer++) {
            double[] pace = new double[stretches + 1];
            for (int k = 0; k < pace.length; k++) {
                pace[k] = 1 - SWING + 2 * SWING * r.nextDouble();
            }
            int end = finish[racer];
            double[] run = at[racer];
            double sum = 0;
            for (int t = 1; t <= end; t++) {
                double x = (t - 0.5) / end * stretches;
                int k = Math.min(stretches - 1, (int) x);
                double blend = (1 - Math.cos((x - k) * Math.PI)) / 2;
                sum += pace[k] + (pace[k + 1] - pace[k]) * blend;
                run[t] = sum;
            }
            for (int t = 1; t <= last; t++) {
                run[t] = t >= end ? 1 : run[t] / sum;
            }
        }
        return at;
    }

    /** Who is in front at {@code tick} (the lower lane when two are level). */
    private static int leader(double[][] at, int tick) {
        int lead = 0;
        for (int racer = 1; racer < Racer.COUNT; racer++) {
            if (at[racer][tick] > at[lead][tick]) {
                lead = racer;
            }
        }
        return lead;
    }

    /** How often the racer in front changes between {@link #COUNT_FROM} and the winner's finish. */
    private static int leadChanges(double[][] at, int ticks) {
        int changes = 0;
        int lead = leader(at, COUNT_FROM);
        for (int t = COUNT_FROM + 1; t <= ticks; t++) {
            int now = leader(at, t);
            if (now != lead) {
                changes++;
                lead = now;
            }
        }
        return changes;
    }

    /** The seed it was written from. */
    public long seed() {
        return seed;
    }

    /** The tick the winner reaches the finish: the race is decided for the room to see. */
    public int ticks() {
        return ticks;
    }

    /** The tick the last racer reaches the finish. */
    public int lastTick() {
        return finish[order[Racer.COUNT - 1]];
    }

    /** The winner (a lane, 0 to 5). */
    public int winner() {
        return order[0];
    }

    /** The racer that finishes in {@code place} (0 wins). */
    public int finisher(int place) {
        return order[place];
    }

    /** Where {@code racer} finishes: 0 for the winner, 5 for the last. */
    public int placeOf(int racer) {
        return place[racer];
    }

    /** The tick {@code racer} reaches the finish. */
    public int finishTick(int racer) {
        return finish[racer];
    }

    /** How far {@code racer} is along the race at {@code tick}: 0 at the line, 1 at the finish, never less than before. */
    public double at(int racer, int tick) {
        if (tick <= 0) {
            return 0;
        }
        double[] run = at[racer];
        return tick >= run.length ? 1 : run[tick];
    }

    /**
     * How far {@code racer} has run on past the finish at {@code tick}, in blocks: nothing until it finishes, then it
     * slows to a stop, the winner furthest on and the last the least, so they come to rest in the order they finished.
     */
    public double beyond(int racer, int tick) {
        int since = tick - finish[racer];
        if (since <= 0) {
            return 0;
        }
        double f = Math.min(1.0, since / (double) SLOW_TICKS);
        return RUN_ON * (Racer.COUNT - place[racer]) * (1 - (1 - f) * (1 - f));
    }

    /** Who is in front at {@code tick} (a lane). */
    public int leaderAt(int tick) {
        return leader(at, Math.max(0, Math.min(tick, at[0].length - 1)));
    }

    /** How often the lead changed hands on the way (for the tests). */
    public int leadChanges() {
        return leadChanges;
    }
}
