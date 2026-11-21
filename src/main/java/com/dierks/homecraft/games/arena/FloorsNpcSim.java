package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.CellState;
import com.dierks.homecraft.games.arena.rules.FloorRules;
import com.dierks.homecraft.games.cpu.CpuLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * NPC players on Falling Floors (stage 2), pure and tested: where each NPC stands, steps and falls, by the floors' own
 * rules, read from the round's floors and never written to them.
 *
 * <p><b>The rules an NPC plays by.</b> The same as a player's: a cell holds while it is whole or red, and falls away
 * {@code fade_ticks} after it was first stepped on. A real player's steps mark the real floors; an NPC's own steps
 * can't (no NPC ever writes a block), so each NPC keeps the cells it has stepped on itself ({@link Walker#used}) and
 * treats them as the floors would: red, then gone. So an NPC sees a cell as gone when the real floors say it is gone
 * or when its own steps used it up ({@link #seen}), and it only ever stands where the real floors hold it.
 *
 * <p><b>How it plays.</b> On a cell it steps to a neighbour that is still whole (a hard NPC picks the one with the
 * most whole cells round it; an easy one picks more at random and is slower), and keeps moving. Now and then an easy
 * NPC waits too long ({@link Walker#lingering}) and its cell falls under it. With nothing whole under it, it falls to
 * the next floor that holds it there, or out under the last floor.
 *
 * <p>Nothing here is a player: an NPC is never in the round's players, its places or its results.
 */
public final class FloorsNpcSim {

    /** What an NPC reads of the floors (the round's {@link FloorRules}, a fake in the tests). */
    public interface Floors {

        int layers();

        /** Where players stand on floor {@code layer}. */
        int topY(int layer);

        /** Under this, a player is out. */
        int outY();

        /** Cell (x, z) of floor {@code layer}: AIR for anything that isn't a cell. */
        CellState state(int layer, int x, int z);

        /** The round's floors, read only. */
        static Floors of(FloorRules f) {
            return new Floors() {
                @Override
                public int layers() {
                    return f.layout().layerCount();
                }

                @Override
                public int topY(int layer) {
                    return f.layout().topY(layer);
                }

                @Override
                public int outY() {
                    return f.layout().outY();
                }

                @Override
                public CellState state(int layer, int x, int z) {
                    return f.state(layer, x, z);
                }
            };
        }
    }

    /** Ticks a step from one cell to the next takes, by level (a player walks a block in about 5). */
    static final int STEP_HARD = 4;
    static final int STEP_MEDIUM = 5;
    static final int STEP_EASY = 7;
    /** The chance, at each step, that an NPC waits too long where it is. */
    static final double LINGER_EASY = 0.08;
    static final double LINGER_MEDIUM = 0.015;
    /** Gravity and the most a fall drops a tick (blocks), a player's. */
    static final double GRAVITY = 0.08;
    static final double MAX_FALL = 3.9;

    /** One NPC on the floors. */
    public static final class Walker {
        final UUID id;
        final CpuLevel level;
        int layer;
        int x;
        int z;
        int fromX;
        int fromZ;
        int stepLeft;
        int stepTicks;
        double px;
        double py;
        double pz;
        double vy;
        float yaw;
        boolean falling;
        int fallTo;
        boolean lingering;
        boolean out;
        long outAt = -1;
        /** The cells it has stepped on itself: (layer, x, z) to the tick each is gone for it. */
        final Map<Long, Long> used = new HashMap<>();

        Walker(UUID id, CpuLevel level) {
            this.id = id;
            this.level = level;
        }

        public UUID id() {
            return id;
        }

        public CpuLevel level() {
            return level;
        }

        /** Where its body is now. */
        public double x() {
            return px;
        }

        public double y() {
            return py;
        }

        public double z() {
            return pz;
        }

        /** The way it faces: the way it last stepped. */
        public float yaw() {
            return yaw;
        }

        /** The floor and cell it stands on (or is stepping to). */
        public int layer() {
            return layer;
        }

        public int cellX() {
            return x;
        }

        public int cellZ() {
            return z;
        }

        /** Mid-step between two cells. */
        public boolean moving() {
            return stepLeft > 0;
        }

        public boolean falling() {
            return falling;
        }

        public boolean lingering() {
            return lingering;
        }

        public boolean out() {
            return out;
        }

        /** The play tick it went out, or -1. */
        public long outAt() {
            return outAt;
        }
    }

    private final int fade;
    private final RandomGenerator rng;
    private final List<Walker> walkers = new ArrayList<>();

    /**
     * @param fadeTicks the round's {@code fade_ticks}: how long a stepped-on cell holds
     */
    public FloorsNpcSim(int fadeTicks, RandomGenerator rng) {
        this.fade = Math.max(2, fadeTicks);
        this.rng = rng;
    }

    /** An NPC starts the round on cell (x, z) of floor {@code layer} (a spawn) at play tick {@code now}. */
    public Walker add(UUID id, CpuLevel level, int layer, int x, int z, Floors f, long now) {
        Walker w = new Walker(id, level);
        w.layer = layer;
        w.x = x;
        w.z = z;
        w.px = x + 0.5;
        w.pz = z + 0.5;
        w.py = f.topY(layer);
        w.stepTicks = Math.max(1, Math.min(stepTicks(level), fade - 2));
        walkers.add(w);
        return w;
    }

    static int stepTicks(CpuLevel level) {
        return switch (level) {
            case HARD -> STEP_HARD;
            case MEDIUM -> STEP_MEDIUM;
            case EASY -> STEP_EASY;
        };
    }

    public List<Walker> walkers() {
        return List.copyOf(walkers);
    }

    private static long key(int layer, int x, int z) {
        return ((long) layer << 48) ^ (((long) x & 0xFFFFFF) << 24) ^ ((long) z & 0xFFFFFF);
    }

    /** How NPC {@code w} sees cell (x, z) of floor {@code layer} at tick {@code now}: the real floors and its own steps. */
    static CellState seen(Walker w, Floors f, int layer, int x, int z, long now) {
        CellState real = f.state(layer, x, z);
        if (real == CellState.AIR) {
            return CellState.AIR;
        }
        Long gone = w.used.get(key(layer, x, z));
        if (gone == null) {
            return real;
        }
        return now >= gone ? CellState.AIR : CellState.RED;
    }

    private void mark(Walker w, long now) {
        w.used.putIfAbsent(key(w.layer, w.x, w.z), now + fade);
    }

    /**
     * Play tick {@code now}, read after the floors have moved on this tick: every NPC still in steps, waits or falls.
     *
     * @return the NPCs who went out this tick
     */
    public List<Walker> step(long now, Floors f) {
        List<Walker> outNow = new ArrayList<>();
        for (Walker w : walkers) {
            if (w.out) {
                continue;
            }
            if (w.falling) {
                fall(w, f, now);
                if (w.out) {
                    outNow.add(w);
                }
                continue;
            }
            if (w.stepLeft > 0) {
                w.stepLeft--;
                double t = 1.0 - (double) w.stepLeft / w.stepTicks;
                w.px = w.fromX + 0.5 + (w.x - w.fromX) * t;
                w.pz = w.fromZ + 0.5 + (w.z - w.fromZ) * t;
                if (w.stepLeft > 0) {
                    continue;
                }
            }
            mark(w, now);
            if (!seen(w, f, w.layer, w.x, w.z, now).holds()) {
                startFall(w, f, now);
                if (w.out) {
                    outNow.add(w);
                }
                continue;
            }
            if (w.lingering) {
                continue; // it waits... until its cell is gone under it
            }
            if (rng.nextDouble() < linger(w.level)) {
                w.lingering = true;
                continue;
            }
            int[] next = choose(w, f, now);
            if (next != null) {
                w.fromX = w.x;
                w.fromZ = w.z;
                w.x = next[0];
                w.z = next[1];
                w.stepLeft = w.stepTicks;
                w.yaw = (float) Math.toDegrees(Math.atan2(-(w.x - w.fromX), w.z - w.fromZ));
            }
        }
        return outNow;
    }

    static double linger(CpuLevel level) {
        return switch (level) {
            case EASY -> LINGER_EASY;
            case MEDIUM -> LINGER_MEDIUM;
            case HARD -> 0;
        };
    }

    /** The whole neighbour to step to, or {@code null} when there is none (it stays, and falls). */
    private int[] choose(Walker w, Floors f, long now) {
        int[] best = null;
        double bestScore = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = w.x + dx;
                int nz = w.z + dz;
                if (seen(w, f, w.layer, nx, nz, now) != CellState.SOLID) {
                    continue;
                }
                double room = 0;
                for (int ex = -1; ex <= 1; ex++) {
                    for (int ez = -1; ez <= 1; ez++) {
                        if ((ex != 0 || ez != 0) && seen(w, f, w.layer, nx + ex, nz + ez, now) == CellState.SOLID) {
                            room++;
                        }
                    }
                }
                double score = switch (w.level) {
                    case HARD -> room + rng.nextDouble() * 0.5;
                    case MEDIUM -> room + rng.nextDouble() * 3;
                    case EASY -> rng.nextDouble() * 8 + room * 0.3;
                };
                if (score > bestScore) {
                    bestScore = score;
                    best = new int[]{nx, nz};
                }
            }
        }
        return best;
    }

    private void startFall(Walker w, Floors f, long now) {
        w.falling = true;
        w.lingering = false;
        w.vy = 0;
        w.fallTo = -1;
        for (int l = w.layer + 1; l < f.layers(); l++) {
            if (seen(w, f, l, w.x, w.z, now).holds()) {
                w.fallTo = l;
                break;
            }
        }
        fall(w, f, now);
    }

    private void fall(Walker w, Floors f, long now) {
        w.vy = Math.max(-MAX_FALL, (w.vy - GRAVITY) * 0.98);
        w.py += w.vy;
        double floor = w.fallTo >= 0 ? f.topY(w.fallTo) : f.outY() - 1;
        if (w.py > floor) {
            return;
        }
        if (w.fallTo < 0) {
            w.py = floor;
            w.out = true;
            w.outAt = now;
            return;
        }
        if (!seen(w, f, w.fallTo, w.x, w.z, now).holds()) {
            startFallFrom(w, f, now); // it went while it fell: on down
            return;
        }
        w.py = floor;
        w.layer = w.fallTo;
        w.falling = false;
        w.vy = 0;
        mark(w, now);
    }

    private void startFallFrom(Walker w, Floors f, long now) {
        int from = w.fallTo;
        w.fallTo = -1;
        for (int l = from + 1; l < f.layers(); l++) {
            if (seen(w, f, l, w.x, w.z, now).holds()) {
                w.fallTo = l;
                break;
            }
        }
    }
}
