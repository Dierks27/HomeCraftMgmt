package com.dierks.homecraft.games.cabinet.whack;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Whack-a-Zombie's rules, with no Bukkit in sight (spec §10b): a timed round on a grid of holes,
 * zombies (+1) and now and then a villager (-1) popping up for a moment each.
 *
 * <p><b>The whole round is worked out before it starts.</b> {@link #schedule} draws every pop —
 * when, which hole, zombie or villager, how long — from the seed alone, so today's daily round is
 * the same pops for everyone and nothing the player does changes what comes next (a whacked
 * zombie just leaves its hole empty early). Bedrock players get each pop for half as long again;
 * the holes are booked for the longer window, so both platforms see the very same pops.
 *
 * <p>Time runs in steps of a tenth of a second ({@link #STEPS_PER_SECOND}); the screen calls
 * {@link #tick()} once per step. The score never goes below zero.
 */
public final class WhackEngine {

    /** Holes on the board (3 by 3). */
    public static final int HOLES = 9;
    /** Steps per second: one step is two server ticks. */
    public static final int STEPS_PER_SECOND = 10;
    /** The first pop comes a second in, so nobody starts behind. */
    static final int START = 10;
    /** Roughly one pop in seven is a villager (never the first). */
    static final int VILLAGER_PERCENT = 15;

    /** What pops up. */
    public enum Kind {
        ZOMBIE,
        VILLAGER
    }

    /** What a whack did: the points it was worth. */
    public enum Hit {
        /** An empty hole: nothing. */
        EMPTY(0),
        /** A zombie: +1. */
        ZOMBIE(1),
        /** A villager: -1. */
        VILLAGER(-1);

        public final int points;

        Hit(int points) {
            this.points = points;
        }
    }

    /**
     * One pop.
     *
     * @param start         the step it appears
     * @param hole          where (0-8, row by row)
     * @param kind          zombie or villager
     * @param window        how many steps it stays up on Java
     * @param bedrockWindow how many steps it stays up on Bedrock (longer)
     */
    public record Pop(int start, int hole, Kind kind, int window, int bedrockWindow) {

        /** The first step it is gone again. */
        public int end(boolean bedrock) {
            return start + (bedrock ? bedrockWindow : window);
        }
    }

    private final List<Pop> pops;
    private final boolean[] whacked;
    private final boolean bedrock;
    private final int steps;
    private int now;
    private int score;
    private int zombies;
    private int villagers;

    /**
     * @param seconds how long the round lasts
     * @param bedrock whether the player gets the longer Bedrock windows
     */
    public WhackEngine(long seed, int seconds, boolean bedrock) {
        this.steps = Math.max(1, seconds) * STEPS_PER_SECOND;
        this.bedrock = bedrock;
        this.pops = schedule(seed, steps, HOLES);
        this.whacked = new boolean[pops.size()];
    }

    /**
     * Every pop of a round of {@code steps} on {@code holes} holes, in order, from the seed alone.
     * Pops come a little faster and stay up a little less as the round goes on. A hole is only
     * picked when it's free for the whole Bedrock window plus a blank step, so no platform ever
     * sees two pops in one hole at once.
     */
    public static List<Pop> schedule(long seed, int steps, int holes) {
        SplittableRandom rng = new SplittableRandom(seed);
        int[] freeAt = new int[holes];
        List<Pop> out = new ArrayList<>();
        List<Integer> free = new ArrayList<>(holes);
        int t = START;
        while (t < steps) {
            int window = 10 + rng.nextInt(5) - (3 * t) / steps;
            int bedrockWindow = (window * 3 + 1) / 2;
            boolean villager = rng.nextInt(100) < VILLAGER_PERCENT && !out.isEmpty();
            int roll = rng.nextInt(1 << 20);
            free.clear();
            for (int h = 0; h < holes; h++) {
                if (freeAt[h] <= t) {
                    free.add(h);
                }
            }
            if (!free.isEmpty()) {
                int hole = free.get(roll % free.size());
                out.add(new Pop(t, hole, villager ? Kind.VILLAGER : Kind.ZOMBIE, window, bedrockWindow));
                freeAt[hole] = t + bedrockWindow + 1;
            }
            t += Math.max(2, 3 + rng.nextInt(5) - (2 * t) / steps);
        }
        return List.copyOf(out);
    }

    /** One step of time. */
    public void tick() {
        if (now < steps) {
            now++;
        }
    }

    /** What is up in {@code hole} right now, or {@code null} for nothing. */
    public Kind at(int hole) {
        int i = showing(hole);
        return i < 0 ? null : pops.get(i).kind();
    }

    /** Whack {@code hole}: a zombie is +1, a villager -1 (never below zero), an empty hole nothing. */
    public Hit whack(int hole) {
        int i = showing(hole);
        if (i < 0) {
            return Hit.EMPTY;
        }
        whacked[i] = true;
        if (pops.get(i).kind() == Kind.ZOMBIE) {
            zombies++;
            score++;
            return Hit.ZOMBIE;
        }
        villagers++;
        score = Math.max(0, score - 1);
        return Hit.VILLAGER;
    }

    private int showing(int hole) {
        if (over()) {
            return -1;
        }
        for (int i = 0; i < pops.size(); i++) {
            Pop p = pops.get(i);
            if (p.start() > now) {
                break;
            }
            if (p.hole() == hole && !whacked[i] && now < p.end(bedrock)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the round is over. */
    public boolean over() {
        return now >= steps;
    }

    /** Steps gone by. */
    public int now() {
        return now;
    }

    /** Whole seconds left, rounded up (what the clock tile shows). */
    public int secondsLeft() {
        return (steps - now + STEPS_PER_SECOND - 1) / STEPS_PER_SECOND;
    }

    /** Points: zombies minus villagers, never below zero. */
    public int score() {
        return score;
    }

    /** Zombies whacked. */
    public int zombies() {
        return zombies;
    }

    /** Villagers bonked. */
    public int villagers() {
        return villagers;
    }

    /** The round's pops (for tests and the rules). */
    public List<Pop> pops() {
        return pops;
    }
}
