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
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * NPC players in The Floor Is Lava (stage 2), pure and tested: where each NPC is in the playroom, read from the
 * round's public state and the room's own plan, and never written to either.
 *
 * <p><b>How an NPC plays.</b> It walks the room stand to stand by the room's own rule ({@link Room#walks}: up a
 * step, down any drop), never through the furniture; 0.57.0: a medium or hard NPC also hops from piece to piece
 * ({@link Room#hops}), as the grown-ups do. While the floor is safe, and during the lava, it goes after a star of its
 * own (a pretend one: it heads for a place a few hops away, over dry ground only while the lava is up, and sparkles
 * there), so it moves about the way a player must now. When the warning starts it heads for the nearest stand that
 * will be dry at the coming lava's height (a hard NPC almost at once and quickly, an easy one later and slower, and
 * now and then for one that isn't high enough). Whenever the lava is up and an NPC isn't on a dry stand it touched
 * it: in Challenge it is out; in Group and Little it is put back on the nearest dry stand, as a player is, and it
 * costs nobody anything. 0.57.0, Challenge: an NPC that ends a round without its stars is out too (an easy one now
 * and then misses its star; a hard one hardly ever).
 *
 * <p><b>The room moves</b> (0.57.0): {@link #moveRoom} takes the next layout and puts every NPC where the room puts a
 * player ({@link LavaShift}: riding its piece, lifted onto one that landed on it).
 *
 * <p>It never collects a real star, never touches the round's count, and is never one of the round's players.
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

        /** 0.57.0: the stands a jump from {@code a} lands on (none: walks only). */
        default List<LavaSite.Stand> hops(LavaSite.Stand a) {
            return List.of();
        }

        /** The live room. */
        static Room of(LavaSite site) {
            List<LavaSite.Stand> reach = site.hopReach();
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

                @Override
                public List<LavaSite.Stand> hops(LavaSite.Stand a) {
                    return site.hopsFrom(a);
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
    /** 0.57.0, Challenge: the chance an NPC misses its round's stars, by level. */
    static final double MISS_EASY = 0.3;
    static final double MISS_MEDIUM = 0.12;
    static final double MISS_HARD = 0.04;
    /** 0.57.0: how far (moves) an NPC looks for its pretend star. */
    static final int STAR_MOVES = 14;

    /** Something that happened to an NPC this tick. {@code starless}: out of a Challenge for a round without stars. */
    public record Touched(UUID npc, boolean out, int lasted, boolean starless) {

        public Touched(UUID npc, boolean out, int lasted) {
            this(npc, out, lasted, false);
        }
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
        int stepNow;
        double px;
        double py;
        double pz;
        float yaw;
        long next;
        boolean headed;
        boolean out;
        int lasted;
        int touches;
        // 0.57.0
        boolean starTrip;
        int got;
        boolean misses;
        int sparkles;

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

        /** 0.57.0: its pretend stars this round. */
        public int got() {
            return got;
        }

        /** 0.57.0: its pretend stars in all. */
        public int sparkles() {
            return sparkles;
        }
    }

    private Room room;
    private final RandomGenerator rng;
    private final Map<LavaSite.Stand, List<LavaSite.Stand>> next = new HashMap<>();
    private final List<LavaSite.Stand> floor = new ArrayList<>();
    private final List<Walker> walkers = new ArrayList<>();
    private final List<double[]> sparkled = new ArrayList<>();
    private LavaRound.Phase phase;
    private long phaseAt;
    private int roundSeen;

    public LavaNpcSim(Room room, RandomGenerator rng) {
        this.rng = rng;
        use(room);
    }

    private void use(Room r) {
        this.room = r;
        next.clear();
        floor.clear();
        Map<Long, List<LavaSite.Stand>> byColumn = new HashMap<>();
        for (LavaSite.Stand s : r.stands()) {
            byColumn.computeIfAbsent(col(s.x(), s.z()), k -> new ArrayList<>()).add(s);
            if (Math.abs(s.y() - r.floorY()) < 1e-6) {
                floor.add(s);
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (LavaSite.Stand a : r.stands()) {
            List<LavaSite.Stand> out = new ArrayList<>();
            for (int[] d : dirs) {
                for (LavaSite.Stand b : byColumn.getOrDefault(col(a.x() + d[0], a.z() + d[1]), List.of())) {
                    if (r.walks(a, b)) {
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

    /** 0.57.0: where NPCs collected a pretend star since the last call ({x, y, z} each), for a sparkle. */
    public List<double[]> sparkled() {
        List<double[]> out = List.copyOf(sparkled);
        sparkled.clear();
        return out;
    }

    /**
     * 0.57.0: the furniture moved: the room is {@code to} now, and each NPC still in play goes to {@code where}
     * says ({@code null}: the stand under it in the new room, or the nearest).
     */
    public void moveRoom(Room to, Function<Walker, LavaSite.Stand> where) {
        use(to);
        for (Walker w : walkers) {
            if (w.out) {
                continue;
            }
            LavaSite.Stand s = where == null ? null : where.apply(w);
            if (s == null) {
                s = standNear(w.px, w.py, w.pz);
            }
            if (s != null) {
                place(w, s);
            }
            w.headed = false;
            w.starTrip = false;
        }
    }

    /** The room's stand nearest a position (its own column first). */
    private LavaSite.Stand standNear(double x, double y, double z) {
        LavaSite.Stand best = null;
        double least = Double.MAX_VALUE;
        for (LavaSite.Stand s : room.stands()) {
            double dx = s.x() + 0.5 - x;
            double dz = s.z() + 0.5 - z;
            double dy = s.y() - y;
            double d = dx * dx + dz * dz + dy * dy * 0.25 + (s.y() > y + 0.6 ? 4 : 0);
            if (d < least) {
                least = d;
                best = s;
            }
        }
        return best;
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

    /** As {@link #step(long, LavaRound.Phase, int, int, LavaMode, int, int)}, needing no stars. */
    public List<Touched> step(long now, LavaRound.Phase phase, int level, int nextLevel, LavaMode mode, int round) {
        return step(now, phase, level, nextLevel, mode, round, 0);
    }

    /**
     * Tick {@code now} of a round in {@code mode}, at {@code phase} with the lava at {@code level} and coming next to
     * {@code nextLevel}, its round number {@code round}; in Challenge each must collect {@code need} stars a round.
     *
     * @return the NPCs who touched the lava (or ran out of stars) this tick
     */
    public List<Touched> step(long now, LavaRound.Phase phase, int level, int nextLevel, LavaMode mode, int round,
                              int need) {
        List<Touched> touched = new ArrayList<>();
        boolean changed = phase != this.phase;
        LavaRound.Phase before = this.phase;
        if (changed) {
            this.phase = phase;
            phaseAt = now;
        }
        if (round != roundSeen) {
            // a new round: Challenge's NPCs without their stars are out, then everyone tries again
            if (mode == LavaMode.CHALLENGE && roundSeen > 0 && before == LavaRound.Phase.LAVA) {
                for (Walker w : walkers) {
                    if (!w.out && need > 0 && (w.misses || w.got < need)) {
                        w.out = true;
                        w.lasted = Math.max(0, roundSeen - 1);
                        touched.add(new Touched(w.id, true, w.lasted, true));
                    }
                }
            }
            roundSeen = round;
            for (Walker w : walkers) {
                w.got = 0;
                double miss = switch (w.level) {
                    case EASY -> MISS_EASY;
                    case MEDIUM -> MISS_MEDIUM;
                    case HARD -> MISS_HARD;
                };
                w.misses = mode == LavaMode.CHALLENGE && rng.nextDouble() < miss;
            }
        }
        for (Walker w : walkers) {
            if (w.out) {
                continue;
            }
            if (changed && phase == LavaRound.Phase.WARNING && w.starTrip) {
                w.path.clear(); // the rumble: forget the star, climb
                w.starTrip = false;
            }
            if (changed && phase == LavaRound.Phase.LAVA) {
                if (w.stepLeft > 0) {
                    place(w, w.at); // it lands where it was stepping
                }
                w.path.clear();
                w.headed = false;
                w.starTrip = false;
            }
            if (phase.inPlay() && level > 0 && w.stepLeft == 0 && !room.dry(w.at, level)) {
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
            if (w.stepLeft > 0) {
                walk(w);
                if (w.stepLeft == 0 && w.path.isEmpty() && w.starTrip) {
                    w.starTrip = false; // a pretend star: a sparkle where it got to
                    w.got++;
                    w.sparkles++;
                    sparkled.add(new double[]{w.px, w.py + 0.9, w.pz});
                    w.next = now + 20 + rng.nextInt(40);
                }
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
                        w.starTrip = false;
                        int aim = w.level == CpuLevel.EASY && rng.nextDouble() < EASY_MISJUDGE
                                ? Math.max(0, nextLevel - 1) : nextLevel;
                        w.path.addAll(wayToDry(w, w.at, aim, level));
                    }
                }
                case SAFE, LAVA -> {
                    w.headed = false;
                    if (now >= w.next) {
                        w.next = now + 30 + rng.nextInt(60);
                        List<LavaSite.Stand> way = starWay(w, level);
                        if (!way.isEmpty()) {
                            w.starTrip = true;
                            w.path.addAll(way);
                        } else {
                            LavaSite.Stand s = potter(w, level);
                            if (s != null) {
                                stepTo(w, s);
                            }
                        }
                    }
                }
                default -> {
                    w.headed = false;
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

    /** Where an NPC may go from {@code a}: walks, and for a medium or hard NPC, hops. */
    private List<LavaSite.Stand> moves(Walker w, LavaSite.Stand a) {
        List<LavaSite.Stand> walks = next.getOrDefault(a, List.of());
        if (w.level == CpuLevel.EASY) {
            return walks;
        }
        List<LavaSite.Stand> hops = room.hops(a);
        if (hops.isEmpty()) {
            return walks;
        }
        List<LavaSite.Stand> out = new ArrayList<>(walks);
        for (LavaSite.Stand h : hops) {
            if (!out.contains(h)) {
                out.add(h);
            }
        }
        return out;
    }

    /** A neighbour to wander to (dry at the lava now), or {@code null}. */
    private LavaSite.Stand potter(Walker w, int level) {
        List<LavaSite.Stand> ok = new ArrayList<>();
        for (LavaSite.Stand s : next.getOrDefault(w.at, List.of())) {
            if (level <= 0 || room.dry(s, level)) {
                ok.add(s);
            }
        }
        return ok.isEmpty() ? null : ok.get(rng.nextInt(ok.size()));
    }

    /**
     * 0.57.0: the way to a pretend star: a stand a few moves off (over dry stands only), picked at random among
     * those {@value #STAR_MOVES} moves away at most and three or more away; empty when there is none.
     */
    private List<LavaSite.Stand> starWay(Walker w, int level) {
        Map<LavaSite.Stand, LavaSite.Stand> came = new HashMap<>();
        Map<LavaSite.Stand, Integer> dist = new HashMap<>();
        ArrayDeque<LavaSite.Stand> queue = new ArrayDeque<>();
        queue.add(w.at);
        came.put(w.at, w.at);
        dist.put(w.at, 0);
        List<LavaSite.Stand> far = new ArrayList<>();
        while (!queue.isEmpty()) {
            LavaSite.Stand a = queue.poll();
            int d = dist.get(a);
            if (d >= 3) {
                far.add(a);
            }
            if (d >= STAR_MOVES) {
                continue;
            }
            for (LavaSite.Stand b : moves(w, a)) {
                if (!came.containsKey(b) && (level <= 0 || room.dry(b, level))) {
                    came.put(b, a);
                    dist.put(b, d + 1);
                    queue.add(b);
                }
            }
        }
        if (far.isEmpty()) {
            return List.of();
        }
        LavaSite.Stand goal = far.get(rng.nextInt(far.size()));
        List<LavaSite.Stand> way = new ArrayList<>();
        for (LavaSite.Stand s = goal; !s.equals(w.at); s = came.get(s)) {
            way.add(s);
        }
        Collections.reverse(way);
        return way;
    }

    private void stepTo(Walker w, LavaSite.Stand s) {
        w.from = w.at;
        w.at = s;
        int far = Math.max(Math.abs(s.x() - w.from.x()), Math.abs(s.z() - w.from.z()));
        w.stepNow = w.stepTicks * Math.max(1, far);
        w.stepLeft = w.stepNow;
        w.yaw = (float) Math.toDegrees(Math.atan2(-(s.x() - w.from.x()), s.z() - w.from.z()));
    }

    private static void walk(Walker w) {
        w.stepLeft--;
        int len = Math.max(1, w.stepNow);
        double t = 1.0 - (double) w.stepLeft / len;
        boolean hop = Math.abs(w.at.x() - w.from.x()) + Math.abs(w.at.z() - w.from.z()) > 1;
        double up = hop ? Math.sin(Math.PI * t) * 1.0 : w.at.y() > w.from.y() ? Math.sin(Math.PI * t) * 0.4 : 0;
        w.px = w.from.x() + 0.5 + (w.at.x() - w.from.x()) * t;
        w.pz = w.from.z() + 0.5 + (w.at.z() - w.from.z()) * t;
        w.py = w.from.y() + (w.at.y() - w.from.y()) * t + up;
    }

    /** The walk (each stand after {@code from}) to the nearest stand dry at {@code level}; empty when there is none. */
    List<LavaSite.Stand> wayToDry(LavaSite.Stand from, int level) {
        return wayToDry(null, from, level, 0);
    }

    /** As above, for {@code w} (its hops too), never through a stand under the lava now ({@code now}). */
    private List<LavaSite.Stand> wayToDry(Walker w, LavaSite.Stand from, int level, int now) {
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
            for (LavaSite.Stand b : w == null ? next.getOrDefault(a, List.of()) : moves(w, a)) {
                if (!came.containsKey(b) && (now <= 0 || room.dry(b, now))) {
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
