package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaRound;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * NPC players in The Floor Is Lava (stage 2), pure and tested: where each NPC is in the playroom, read from the
 * round's public state and the room's own plan, and never written to either.
 *
 * <p><b>How an NPC plays.</b> It walks the room stand to stand by the room's own rule ({@link Room#walks}: up a
 * step, down any drop), never through the furniture. While the floor is safe it potters about the floor. When the
 * warning starts it heads for the nearest stand that will be dry at the coming lava's height (a hard NPC almost at
 * once and quickly, an easy one later and slower, and now and then for one that isn't high enough). When the lava
 * comes, an NPC that isn't on a dry stand touched it: in Challenge it is out; in Group and Little it is put back on
 * the nearest dry stand, as a player is, and it costs nobody anything.
 *
 * <p>It never collects a star, never touches the round's count, and is never one of the round's players.
 */
public final class LavaNpcSim {

    /** What an NPC reads of the room (the live {@link LavaSite}, a fake in the tests). */
    public interface Room {

        /** Every stand of the play area. */
        List<LavaSite.Stand> stands();

        /** Whether a player on {@code a} can step onto its neighbour {@code b}. */
        boolean walks(LavaSite.Stand a, LavaSite.Stand b);

        /** Whether {@code s} is dry with the lava at {@code level}. */
        boolean dry(LavaSite.Stand s, int level);

        /** The floor's stands' height (where the lava comes first). */
        double floorY();

        /** The live room. */
        static Room of(LavaSite site) {
            List<LavaSite.Stand> reach = site.reach();
            double floor = site.box().minY() + 1;
            return new Room() {
                @Override
                public List<LavaSite.Stand> stands() {
                    return reach;
                }

                @Override
                public boolean walks(LavaSite.Stand a, LavaSite.Stand b) {
                    return site.walks(a, b);
                }

                @Override
                public boolean dry(LavaSite.Stand s, int level) {
                    return site.dry(s, level);
                }

                @Override
                public double floorY() {
                    return floor;
                }
            };
        }
    }

    /** Ticks a step from one stand to the next takes, by level (a player walks a block in about 5). */
    static final int STEP_HARD = 5;
    static final int STEP_MEDIUM = 7;
    static final int STEP_EASY = 10;
    /** How long after the warning an NPC starts for dry ground, by level (ticks; easy also waits a bit more). */
    static final int REACT_HARD = 8;
    static final int REACT_MEDIUM = 20;
    static final int REACT_EASY = 40;
    /** The chance an easy NPC heads for a stand that won't be high enough. */
    static final double EASY_MISJUDGE = 0.25;

    /** Something that happened to an NPC this tick. */
    public record Touched(UUID npc, boolean out, int lasted) {
    }

    /** One NPC in the room. */
    public static final class Walker {
        final UUID id;
        final CpuLevel level;
        LavaSite.Stand at;
        LavaSite.Stand from;
        final ArrayDeque<LavaSite.Stand> path = new ArrayDeque<>();
        int stepLeft;
        int stepTicks;
        double px;
        double py;
        double pz;
        float yaw;
        long next;
        boolean headed;
        boolean out;
        int lasted;
        int touches;

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

        /** The stand it is on (or stepping onto). */
        public LavaSite.Stand at() {
            return at;
        }

        public double x() {
            return px;
        }

        public double y() {
            return py;
        }

        public double z() {
            return pz;
        }

        public float yaw() {
            return yaw;
        }

        public boolean moving() {
            return stepLeft > 0;
        }

        /** Out of a Challenge. */
        public boolean out() {
            return out;
        }

        /** The rounds it lasted in a Challenge (set when it went out). */
        public int lasted() {
            return lasted;
        }

        /** How many times it touched the lava and was put back. */
        public int touches() {
            return touches;
        }
    }

    private final Room room;
    private final RandomGenerator rng;
    private final Map<LavaSite.Stand, List<LavaSite.Stand>> next = new HashMap<>();
    private final List<LavaSite.Stand> floor = new ArrayList<>();
    private final List<Walker> walkers = new ArrayList<>();
    private LavaRound.Phase phase;
    private long phaseAt;

    public LavaNpcSim(Room room, RandomGenerator rng) {
        this.room = room;
        this.rng = rng;
        Map<Long, List<LavaSite.Stand>> byColumn = new HashMap<>();
        for (LavaSite.Stand s : room.stands()) {
            byColumn.computeIfAbsent(col(s.x(), s.z()), k -> new ArrayList<>()).add(s);
            if (Math.abs(s.y() - room.floorY()) < 1e-6) {
                floor.add(s);
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (LavaSite.Stand a : room.stands()) {
            List<LavaSite.Stand> out = new ArrayList<>();
            for (int[] d : dirs) {
                for (LavaSite.Stand b : byColumn.getOrDefault(col(a.x() + d[0], a.z() + d[1]), List.of())) {
                    if (room.walks(a, b)) {
                        out.add(b);
                    }
                }
            }
            next.put(a, out);
        }
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** An NPC comes into the room on stand {@code at} (one of the room's) at tick {@code now}. */
    public Walker add(UUID id, CpuLevel level, LavaSite.Stand at, long now) {
        Walker w = new Walker(id, level);
        w.stepTicks = switch (level) {
            case HARD -> STEP_HARD;
            case MEDIUM -> STEP_MEDIUM;
            case EASY -> STEP_EASY;
        };
        place(w, at);
        w.next = now + 20 + rng.nextInt(40);
        walkers.add(w);
        return w;
    }

    /** A random stand of the floor (where an NPC comes in), or {@code null} for a room with none. */
    public LavaSite.Stand floorStand() {
        return floor.isEmpty() ? null : floor.get(rng.nextInt(floor.size()));
    }

    public List<Walker> walkers() {
        return List.copyOf(walkers);
    }

    /** Every NPC leaves (the round ended). */
    public void clear() {
        walkers.clear();
    }

    private static void place(Walker w, LavaSite.Stand s) {
        w.at = s;
        w.from = s;
        w.stepLeft = 0;
        w.path.clear();
        w.px = s.x() + 0.5;
        w.py = s.y();
        w.pz = s.z() + 0.5;
    }

    /**
     * Tick {@code now} of a round in {@code mode}, at {@code phase} with the lava at {@code level} and coming next to
     * {@code nextLevel}, its round number {@code round}.
     *
     * @return the NPCs who touched the lava this tick
     */
    public List<Touched> step(long now, LavaRound.Phase phase, int level, int nextLevel, LavaMode mode, int round) {
        List<Touched> touched = new ArrayList<>();
        boolean changed = phase != this.phase;
        if (changed) {
            this.phase = phase;
            phaseAt = now;
        }
        for (Walker w : walkers) {
            if (w.out) {
                continue;
            }
            if (changed && phase == LavaRound.Phase.LAVA) {
                if (w.stepLeft > 0) {
                    place(w, w.at); // it lands where it was stepping
                }
                w.path.clear();
                w.headed = false;
                if (!room.dry(w.at, level)) {
                    boolean out = mode == LavaMode.CHALLENGE;
                    if (out) {
                        w.out = true;
                        w.lasted = Math.max(0, round - 1);
                    } else {
                        w.touches++;
                        LavaSite.Stand dry = nearestDry(w.at, level);
                        if (dry != null) {
                            place(w, dry); // put back on dry ground, as a player is
                        }
                    }
                    touched.add(new Touched(w.id, out, w.lasted));
                    continue;
                }
            }
            if (w.stepLeft > 0) {
                walk(w);
                continue;
            }
            if (!w.path.isEmpty()) {
                stepTo(w, w.path.poll());
                continue;
            }
            switch (phase) {
                case WARNING -> {
                    if (!w.headed && now >= phaseAt + react(w)) {
                        w.headed = true;
                        int aim = w.level == CpuLevel.EASY && rng.nextDouble() < EASY_MISJUDGE
                                ? Math.max(0, nextLevel - 1) : nextLevel;
                        List<LavaSite.Stand> way = wayToDry(w.at, aim);
                        w.path.addAll(way);
                    }
                }
                case LAVA -> {
                    // it stays where it is dry
                }
                default -> {
                    w.headed = false;
                    if (now >= w.next) {
                        w.next = now + 30 + rng.nextInt(70);
                        LavaSite.Stand s = potter(w, level);
                        if (s != null) {
                            stepTo(w, s);
                        }
                    }
                }
            }
        }
        return touched;
    }

    private int react(Walker w) {
        return switch (w.level) {
            case HARD -> REACT_HARD;
            case MEDIUM -> REACT_MEDIUM + (int) (w.id.getLeastSignificantBits() & 7);
            case EASY -> REACT_EASY + (int) (Math.abs(w.id.getLeastSignificantBits()) % 30);
        };
    }

    /** A neighbour to wander to while the floor is safe (dry at the lava now), or {@code null}. */
    private LavaSite.Stand potter(Walker w, int level) {
        List<LavaSite.Stand> ok = new ArrayList<>();
        for (LavaSite.Stand s : next.getOrDefault(w.at, List.of())) {
            if (level <= 0 || room.dry(s, level)) {
                ok.add(s);
            }
        }
        return ok.isEmpty() ? null : ok.get(rng.nextInt(ok.size()));
    }

    private void stepTo(Walker w, LavaSite.Stand s) {
        w.from = w.at;
        w.at = s;
        w.stepLeft = w.stepTicks;
        w.yaw = (float) Math.toDegrees(Math.atan2(-(s.x() - w.from.x()), s.z() - w.from.z()));
    }

    private static void walk(Walker w) {
        w.stepLeft--;
        double t = 1.0 - (double) w.stepLeft / w.stepTicks;
        double up = w.at.y() > w.from.y() ? Math.sin(Math.PI * t) * 0.4 : 0; // a little hop up a step
        w.px = w.from.x() + 0.5 + (w.at.x() - w.from.x()) * t;
        w.pz = w.from.z() + 0.5 + (w.at.z() - w.from.z()) * t;
        w.py = w.from.y() + (w.at.y() - w.from.y()) * t + up;
    }

    /** The walk (each stand after {@code from}) to the nearest stand dry at {@code level}; empty when there is none. */
    List<LavaSite.Stand> wayToDry(LavaSite.Stand from, int level) {
        if (room.dry(from, level)) {
            return List.of();
        }
        Map<LavaSite.Stand, LavaSite.Stand> came = new HashMap<>();
        ArrayDeque<LavaSite.Stand> queue = new ArrayDeque<>();
        queue.add(from);
        came.put(from, from);
        while (!queue.isEmpty()) {
            LavaSite.Stand a = queue.poll();
            if (room.dry(a, level)) {
                List<LavaSite.Stand> way = new ArrayList<>();
                for (LavaSite.Stand s = a; !s.equals(from); s = came.get(s)) {
                    way.add(s);
                }
                Collections.reverse(way);
                return way;
            }
            for (LavaSite.Stand b : next.getOrDefault(a, List.of())) {
                if (!came.containsKey(b)) {
                    came.put(b, a);
                    queue.add(b);
                }
            }
        }
        return List.of();
    }

    /** The nearest stand dry at {@code level} to {@code s} (as the room puts a player back), or {@code null}. */
    private LavaSite.Stand nearestDry(LavaSite.Stand s, int level) {
        LavaSite.Stand best = null;
        double least = Double.MAX_VALUE;
        for (LavaSite.Stand o : room.stands()) {
            if (!room.dry(o, level)) {
                continue;
            }
            double dx = o.x() - s.x();
            double dz = o.z() - s.z();
            double dy = (o.y() - s.y()) * 0.5;
            double d = dx * dx + dz * dz + dy * dy;
            if (d < least) {
                least = d;
                best = o;
            }
        }
        return best;
    }
}
