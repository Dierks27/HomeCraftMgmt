package com.dierks.homecraft.games.gen.parkour;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The planner for Daily Parkour, easy, medium and hard (GEN-SPEC §4.1): a path of floating pads
 * from a green start pad to a gold finish pad, every jump one {@link JumpRules} allows for the
 * tier, so the course is solvable by construction.
 *
 * <p><b>How a layout grows.</b> The start pad sits in a corner of the half, heading inward. Each
 * jump ({@code fork("jump:" + i + ":" + n)}, a fresh stream for every try) draws a height step,
 * a gap, a side offset and the landing pad's size from the tier's tables, and is kept only if
 * the pad fits the half with a 3-block margin, keeps its own headroom and every jump's flight
 * clear, and is out of jumping reach of every pad that isn't its neighbour ({@link
 * JumpRules#minSkipGap}). Up to 20 tries a jump, backing up to 3 jumps, 5,000 tries in all; then
 * a fresh start ({@code fork("restart:" + n)}), up to 20 of them. Where a pad may turn the path,
 * the turn is drawn with the room ahead in mind: straight on while the path can reach its next
 * chance to turn, otherwise the gentlest turn inward, always winding the same way round the half,
 * so the path never heads back across itself; and the pads either side of a turn lean away from
 * each other so the turn keeps its no-shortcut distance.
 *
 * <p><b>Falls.</b> Easy resets 3 below its lowest pad. Medium and hard use the server's
 * {@code trials.fall_depth} under the checkpoints either side of the leg, so every pad of a leg
 * stays at least 2 above that floor: no legal position is ever reset. The depth a layout is
 * shaped for is {@code min(fall_depth, 6)}, so every depth of 6 or more gives the same layout,
 * and {@link #rederive} finds a layout shaped for a shallower one too.
 *
 * <p>Every plan is checked by the independent {@link ParkourValidator} before it is returned; a
 * plan that fails it counts as a failed start (it never happens: 10,000 seeds per tier are
 * tested). Pure: no Bukkit, no clock, no {@code java.util.Random}.
 */
public final class ParkourPlanner implements Planner {

    /** Its version; bump it whenever what it makes for a seed changes (golden hashes pin three seeds). */
    public static final int ALGO = 2;

    /** Candidates tried for one jump before backing up. */
    public static final int TRIES_PER_JUMP = 20;
    /** How many jumps it may back up from the furthest one it reached. */
    public static final int BACKTRACK = 3;
    /** Candidates tried in one start before starting afresh. */
    public static final int NODES_PER_START = 5_000;
    /** Fresh starts before it gives up. */
    public static final int RESTARTS = 20;
    /** The most work one plan can take: every start's tries. A budget of at least this never runs out. */
    public static final long WORK_BUDGET = (long) NODES_PER_START * RESTARTS;

    /** Pads stay this far inside the half's sides. */
    public static final int MARGIN = 3;
    /** The band of pad tops starts this far above the half's floor. */
    public static final int BAND_FLOOR = 8;
    /** Air kept over every pad top: the 1.25 jump peak plus a 1.8 body needs 3.05. */
    public static final int HEADROOM = 3;
    /** Easy falls back this far below its lowest pad. */
    public static final int EASY_FALL = 3;
    /**
     * Checkpoint and finish radii. A checkpoint's mark covers every spot on its 3 x 3 pad where
     * feet can stand, and a player's feet can be {@link #STAND} past a pad's edge: its far corner
     * is {@code hypot(1.8, 1.8) = 2.55} from the middle, so a child who lands on the corner of a
     * turning checkpoint and hops on is counted. (The block corner, 2.12, isn't enough.) No
     * checkpoint's mark reaches the pad before or after it ({@link Search#reachesNeighbour}).
     */
    public static final double CHECKPOINT_RADIUS = 2.6;
    public static final double FINISH_RADIUS = 3.0;
    /** How far past a pad's edge feet can still stand on it: half a player's width. */
    public static final double STAND = 0.3;
    /** The deepest {@code fall_depth} a layout is shaped for; deeper settings give the same layout. */
    public static final int FALL_DESIGN = 6;
    /** Reference time: seconds per jump, and running speed (blocks a second) walking and sprinting. */
    public static final double SECONDS_PER_JUMP = 0.4;
    public static final double WALK_SPEED = 4.3;
    public static final double SPRINT_SPEED = 5.6;

    /** The eight headings, 45° apart: east, south-east, south, ... as (x, z) steps. */
    static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    @Override
    public String id() {
        return Slots.PARKOUR;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        return build(in).plan();
    }

    /**
     * The layout the tag names, made again: the same seed through the same planner. The tier and
     * {@code fall_depth} in {@code in} are tried first; a layout made under another tier, or for a
     * shallower fall depth, is found by trying those too. It must hash the same as the tag, and
     * pass {@link ParkourValidator} under the live {@code fall_depth} of {@code in}: a layout the
     * current setting would send players back from is refused, so the engine builds a new one.
     */
    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        if (tag == null) {
            return plan(in);
        }
        if (tag.algo() != ALGO) {
            throw new GenFailed("this layout was made by parkour planner v" + tag.algo() + ", this is v" + ALGO);
        }
        List<String> tiers = new ArrayList<>();
        tiers.add(in.slot().normalise(in.tierOrMix()));
        for (JumpRules.Level l : JumpRules.Level.values()) {
            if (!tiers.contains(l.id())) {
                tiers.add(l.id());
            }
        }
        List<Integer> depths = new ArrayList<>();
        depths.add(in.fallDepth());
        for (int d = FALL_DESIGN; d >= 1; d--) {
            if (!depths.contains(d)) {
                depths.add(d);
            }
        }
        boolean anyHash = tag.planHash() == null || tag.planHash().isBlank();
        for (String tier : tiers) {
            for (int depth : depths) {
                PlanInput again = new PlanInput(in.slot(), in.half(), in.halfId(), tag.day(), tag.reroll(), tag.seed(),
                        tier, depth, in.workBudget(), in.cancelled());
                Plan p;
                try {
                    p = plan(again);
                } catch (GenFailed e) {
                    if (anyHash) {
                        throw e;
                    }
                    continue;
                }
                if (anyHash || tag.planHash().equals(p.hash())) {
                    // Found; but it must still be fair under the live fall_depth (a lower setting
                    // can put a pad under a leg's fall floor): if not, a new layout is made instead.
                    List<String> live = ParkourValidator.problems(p, tier, in.fallDepth());
                    if (!live.isEmpty()) {
                        throw new GenFailed("layout " + p.hash() + " was made for fall_depth " + depth + " and doesn't"
                                + " fit fall_depth " + in.fallDepth() + ": " + live.get(0));
                    }
                    return p;
                }
            }
        }
        throw new GenFailed("seed " + GenSeed.shortHex(tag.seed()) + " no longer makes layout " + tag.planHash());
    }

    // ---- the search ---------------------------------------------------------------------------

    /** A plan and what it took, for tests and status. */
    record Build(Plan plan, int restarts, int validatorRejections, long work) {
    }

    /** What a pad is for. */
    enum Kind { START, PATH, CHECKPOINT, FINISH }

    /**
     * One pad: its footprint (inclusive), its top (feet level; its blocks are one below), what it
     * is, the heading of the jump onto it (for the start: the first jump's) and the turn the jump
     * after it makes, in 45° steps.
     */
    record Pad(int x1, int z1, int x2, int z2, int top, Kind kind, int headingIn, int turnOut) {

        int sx() {
            return x2 - x1 + 1;
        }

        int sz() {
            return z2 - z1 + 1;
        }

        double cx() {
            return (x1 + x2 + 1) / 2.0;
        }

        double cz() {
            return (z1 + z2 + 1) / 2.0;
        }

        /** Its blocks. */
        Box blocks() {
            return new Box(x1, top - 1, z1, x2, top - 1, z2);
        }

        /** The air over it: one block wider, from its top up {@link #HEADROOM}. */
        Box headroom() {
            return new Box(x1 - 1, top, z1 - 1, x2 + 1, top + HEADROOM, z2 + 1);
        }

        /** The heading of the jump off it. */
        int headingOut() {
            return Math.floorMod(headingIn + turnOut, 8);
        }
    }

    Build build(PlanInput in) throws GenFailed {
        JumpRules.Level level = JumpRules.Level.of(in.slot().normalise(in.tierOrMix()));
        if (level == null) {
            throw new GenFailed("'" + in.tierOrMix() + "' isn't a parkour tier (easy, medium or hard)");
        }
        Box half = in.half();
        int bandLo = half.minY() + BAND_FLOOR;
        int bandHi = bandLo + level.band();
        if (bandHi + HEADROOM > half.maxY() || half.sizeX() < 2 * MARGIN + 24 || half.sizeZ() < 2 * MARGIN + 24) {
            throw new GenFailed("the area " + half.describe() + " is too small for " + level.id() + " parkour");
        }
        int liveDepth = clampDepth(in.fallDepth());
        int fallDesign = Math.min(FALL_DESIGN, liveDepth);
        GenRandom root = new GenRandom(in.seed());
        long work = 0;
        int rejected = 0;
        for (int restart = 0; restart < RESTARTS; restart++) {
            in.checkCancelled();
            Search s = new Search(level, half, bandLo, bandHi, fallDesign, root.fork("restart:" + restart));
            List<Pad> pads = s.run();
            work += s.nodes;
            if (in.workBudget() > 0 && work > in.workBudget()) {
                throw new GenFailed("the parkour plan went over its work budget (" + in.workBudget() + " tries)");
            }
            if (pads == null) {
                continue;
            }
            Plan plan = toPlan(in, level, pads, fallDesign, work, restart);
            if (ParkourValidator.problems(plan, level.id(), liveDepth).isEmpty()) {
                return new Build(plan, restart, rejected, work);
            }
            rejected++;
        }
        throw new GenFailed("no " + level.id() + " parkour layout found for seed " + GenSeed.shortHex(in.seed())
                + " in " + RESTARTS + " starts");
    }

    /** {@code trials.fall_depth} as the settings allow it (1 to 64). */
    static int clampDepth(int depth) {
        return Math.max(1, Math.min(64, depth));
    }

    /** One start: a depth-first search over the jumps, with bounded backtracking. */
    private static final class Search {
        final JumpRules.Level level;
        final Box half;
        final int bandLo;
        final int bandHi;
        final int fallDesign;
        final GenRandom rng;
        final List<Pad> pads = new ArrayList<>();
        final int[] tried;
        final int[] draws;
        final double[] rooms = new double[8];
        /** The way the path winds round the half: +1 or -1 eighths per turn, toward its inside. */
        int winding = 1;
        int version;
        int roomVersion = -1;
        double roomX = Double.NaN;
        double roomZ = Double.NaN;
        long nodes;

        Search(JumpRules.Level level, Box half, int bandLo, int bandHi, int fallDesign, GenRandom rng) {
            this.level = level;
            this.half = half;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.fallDesign = fallDesign;
            this.rng = rng;
            this.tried = new int[level.jumps() + 1];
            this.draws = new int[level.jumps() + 1];
        }

        /** The pads, start to finish, or {@code null} when this start ran out of tries. */
        List<Pad> run() {
            pads.add(start(rng.fork("start")));
            int deepest = 0;
            while (pads.size() <= level.jumps()) {
                int i = pads.size(); // the jump being placed lands on pad i
                Pad placed = null;
                while (placed == null && tried[i] < TRIES_PER_JUMP) {
                    if (nodes >= NODES_PER_START) {
                        return null;
                    }
                    tried[i]++;
                    nodes++;
                    placed = candidate(i, rng.fork("jump:" + i + ":" + draws[i]++));
                }
                if (placed != null) {
                    pads.add(placed);
                    version++;
                    deepest = Math.max(deepest, i);
                    continue;
                }
                // out of tries here: back up one jump, while that stays within reach of the deepest
                tried[i] = 0;
                if (i - 1 < 1 || i - 1 < deepest - BACKTRACK) {
                    return null;
                }
                pads.remove(pads.size() - 1);
                version++;
            }
            return pads;
        }

        /** The start pad: 5 x 5, in a corner, {@link #MARGIN} in, heading inward along one side. */
        Pad start(GenRandom r) {
            int corner = r.nextInt(4);
            boolean west = (corner & 1) == 0;
            boolean north = (corner & 2) == 0;
            int x1 = west ? half.minX() + MARGIN : half.maxX() - MARGIN - 4;
            int z1 = north ? half.minZ() + MARGIN : half.maxZ() - MARGIN - 4;
            int heading = r.nextBoolean() ? (west ? 0 : 4) : (north ? 2 : 6);
            int[] turned = DIRS[Math.floorMod(heading + 2, 8)];
            winding = turned[0] * (west ? 1 : -1) + turned[1] * (north ? 1 : -1) > 0 ? 1 : -1;
            int top = bandLo + level.band() / 2;
            return new Pad(x1, z1, x1 + 4, z1 + 4, top, Kind.START, heading, 0);
        }

        /** One try at the jump onto pad {@code i}: the pad, or {@code null} when it doesn't fit. */
        Pad candidate(int i, GenRandom r) {
            Pad from = pads.get(i - 1);
            int heading = from.headingOut();
            boolean diagonal = (heading & 1) == 1;
            Kind kind = i == level.jumps() ? Kind.FINISH
                    : i % level.checkpointEvery() == 0 ? Kind.CHECKPOINT : Kind.PATH;
            int[] size = size(kind, r);

            // the height step, by the tier's weights, inside the band and above the leg's fall floor
            int dy = drawDy(from, kind, r);
            if (dy == Integer.MIN_VALUE) {
                return null;
            }
            // the gap: a legal shape for this step; next to a turn, a long one spreads the path
            List<int[]> shapes = JumpRules.shapes(level, dy, diagonal);
            if (shapes.isEmpty()) {
                return null;
            }
            boolean nearTurn = from.turnOut() != 0 || (i >= 2 && pads.get(i - 2).turnOut() != 0);
            int[] shape = nearTurn && r.chance(0.7) ? longest(shapes) : r.pick(shapes);

            // the turn off this pad, decided from where it lands (placed centred), so it can lean toward it
            int turnOut = 0;
            if (kind != Kind.FINISH) {
                Pad probe = place(from, heading, shape, size, dy, kind, 0, null);
                if (!fits(probe)) {
                    return null;
                }
                turnOut = drawTurn(probe, heading, kind, size, r);
            }
            Pad to = place(from, heading, shape, size, dy, kind, turnOut, r);
            if (!fits(to)) {
                return null;
            }
            if (!clear(i, from, to) || reachesNeighbour(from, to)) {
                return null;
            }
            return to;
        }

        /**
         * Whether a checkpoint's mark on one of the two pads of this jump reaches the other: a mark
         * must be reached on its own pad, never while standing on the pad before it, and never
         * from the one after (a flat 1-block gap in or out of a checkpoint is too close).
         */
        boolean reachesNeighbour(Pad from, Pad to) {
            return (to.kind() == Kind.CHECKPOINT && markReaches(to, CHECKPOINT_RADIUS, from))
                    || (from.kind() == Kind.CHECKPOINT && markReaches(from, CHECKPOINT_RADIUS, to));
        }

        int[] size(Kind kind, GenRandom r) {
            return switch (kind) {
                case START, FINISH -> new int[]{5, 5};
                case CHECKPOINT -> new int[]{3, 3};
                case PATH -> switch (level) {
                    case EASY -> new int[]{3, 3};
                    case MEDIUM -> r.chance(0.6) ? new int[]{2, 2} : new int[]{1, 1};
                    case HARD -> r.chance(0.8) ? new int[]{1, 1} : r.nextBoolean() ? new int[]{1, 2} : new int[]{2, 1};
                };
            };
        }

        int drawDy(Pad from, Kind kind, GenRandom r) {
            double[] w = level.dyWeights();
            int legBase = legBase();
            double sum = 0;
            for (int k = 0; k < JumpRules.DYS.length; k++) {
                int top = from.top() + JumpRules.DYS[k];
                boolean ok = top >= bandLo && top <= bandHi && level.row(JumpRules.DYS[k]) != null;
                if (!level.fixedFall() && kind == Kind.PATH && top < legBase) {
                    ok = false; // a pad between checkpoints never sinks within 2 of the leg's fall floor
                }
                if (!ok) {
                    w[k] = 0;
                }
                sum += w[k];
            }
            return sum <= 0 ? Integer.MIN_VALUE : JumpRules.DYS[r.weighted(w)];
        }

        /** The lowest top a pad between this leg's checkpoints may have (medium and hard). */
        int legBase() {
            for (int k = pads.size() - 1; k >= 0; k--) {
                Pad p = pads.get(k);
                if (p.kind() == Kind.START || p.kind() == Kind.CHECKPOINT) {
                    return p.top() - fallDesign + 2;
                }
            }
            return Integer.MIN_VALUE;
        }

        /**
         * The turn a pad of this kind and size will make. The path winds one way round the half
         * ({@link #winding}, toward its inside from the start corner), so it never heads back
         * across itself: straight on while there is room to reach the next chance to turn, else
         * the gentlest turn inward that has room; the other way only when nothing inward does.
         */
        int drawTurn(Pad from, int heading, Kind kind, int[] size, GenRandom r) {
            boolean checkpoint = kind == Kind.CHECKPOINT;
            double need = switch (level) {
                case EASY -> 17;
                case MEDIUM -> 12;
                case HARD -> 9;
            };
            boolean[] ok = new boolean[5];
            double[] room = new double[5];
            int w = winding;
            int[] order = {w, 2 * w, 0, -w, -2 * w};
            int bestTurn = 0;
            double best = -1;
            for (int t : order) {
                ok[t + 2] = JumpRules.turnAllowed(level, t, checkpoint, size[0], size[1]);
                if (ok[t + 2]) {
                    room[t + 2] = room(from, Math.floorMod(heading + t, 8));
                    if (room[t + 2] > best) {
                        best = room[t + 2];
                        bestTurn = t;
                    }
                }
            }
            boolean diagonal = (heading & 1) == 1;
            // a diagonal stretch is half a corner: finish it, more often than not
            if (diagonal && ok[w + 2] && room[w + 2] >= need && r.chance(0.6)) {
                return w;
            }
            // straight on while there is room, now and then turning inward early for a change of shape
            double early = switch (level) {
                case EASY -> 0.3;
                case MEDIUM -> 0.03;
                case HARD -> 0.0;
            };
            if (room[2] >= need && (!r.chance(early) || !(ok[w + 2] || ok[2 * w + 2]))) {
                return 0;
            }
            for (int t : order) {
                if (ok[t + 2] && room[t + 2] >= need) {
                    return t;
                }
            }
            return bestTurn;
        }

        /**
         * Roughly how far the path could run along heading {@code h} from pad {@code from} (where
         * the next jump lands): until it leaves the half's inner area or comes within skipping
         * reach of an older pad, and 2. Remembered while the path and the spot stay the same.
         */
        double room(Pad from, int h) {
            if (roomVersion != version || roomX != from.cx() || roomZ != from.cz()) {
                java.util.Arrays.fill(rooms, -1);
                roomVersion = version;
                roomX = from.cx();
                roomZ = from.cz();
            }
            if (rooms[h] >= 0) {
                return rooms[h];
            }
            double dx = DIRS[h][0];
            double dz = DIRS[h][1];
            double len = Math.sqrt(dx * dx + dz * dz);
            dx /= len;
            dz /= len;
            double ox = from.cx();
            double oz = from.cz();
            // how far until the path leaves the half's inner area...
            double lo = MARGIN;
            double out = Math.min(ROOM_MAX, Math.min(exit(ox, dx, half.minX() + lo, half.maxX() + 1 - lo),
                    exit(oz, dz, half.minZ() + lo, half.maxZ() + 1 - lo)));
            // ...or comes within skipping reach (and 2) of an older pad
            int older = pads.size() - 2;
            for (int k = 0; k < older && out > 0; k++) {
                Pad p = pads.get(k);
                double keep = 2 + JumpRules.minSkipGap(level, from.top() - p.top(), false, 0);
                out = Math.min(out, enterRounded(ox, oz, dx, dz, p.x1(), p.x2() + 1, p.z1(), p.z2() + 1, keep));
            }
            out = Math.max(0, out);
            rooms[h] = out;
            return out;
        }

        /** The landing pad for a jump of {@code shape} along {@code heading}. */
        Pad place(Pad from, int heading, int[] shape, int[] size, int dy, Kind kind, int turnOut, GenRandom r) {
            // r == null: a probe, centred sideways
            int dx = DIRS[heading][0];
            int dz = DIRS[heading][1];
            int sx = size[0];
            int sz = size[1];
            int x1;
            int z1;
            if (dx != 0 && dz != 0) {
                int g = shape[0];
                x1 = dx > 0 ? from.x2() + 1 + g : from.x1() - 1 - g - (sx - 1);
                z1 = dz > 0 ? from.z2() + 1 + g : from.z1() - 1 - g - (sz - 1);
            } else if (dx != 0) {
                x1 = dx > 0 ? from.x2() + 1 + shape[0] : from.x1() - 1 - shape[0] - (sx - 1);
                z1 = lateral(from.z1(), from.z2(), sz, shape[1], lean(heading, turnOut, from, true), r);
            } else {
                z1 = dz > 0 ? from.z2() + 1 + shape[0] : from.z1() - 1 - shape[0] - (sz - 1);
                x1 = lateral(from.x1(), from.x2(), sx, shape[1], lean(heading, turnOut, from, false), r);
            }
            return new Pad(x1, z1, x1 + sx - 1, z1 + sz - 1, from.top() + dy, kind, heading, turnOut);
        }

        /**
         * Which way (-1, 0, +1 along the side axis) the landing pad leans: toward the side it will
         * turn to, or, straight after a turn, on along the way the path came, so the pads either
         * side of a turn end up far apart.
         */
        int lean(int heading, int turnOut, Pad from, boolean sideIsZ) {
            if (turnOut != 0) {
                int[] next = DIRS[Math.floorMod(heading + turnOut, 8)];
                return Integer.signum(sideIsZ ? next[1] : next[0]);
            }
            if (from.turnOut() != 0) {
                int[] before = DIRS[from.headingIn()];
                return Integer.signum(sideIsZ ? before[1] : before[0]);
            }
            return 0;
        }

        /** The landing pad's first row along the side axis, for a side gap of {@code side} (0 or 1). */
        int lateral(int p1, int p2, int w, int side, int lean, GenRandom r) {
            int aligned = p1 + Math.floorDiv((p2 - p1 + 1) - w, 2);
            if (r == null) {
                return aligned;
            }
            if (side == 1) {
                boolean plus = lean != 0 ? lean > 0 : r.nextBoolean();
                return plus ? p2 + 2 : p1 - 1 - w;
            }
            int lo;
            int hi;
            if (level == JumpRules.Level.EASY) {
                // a child walking down the middle of the pad lands on the next one
                lo = Math.max(aligned - 1, p1 - w + JumpRules.EASY_OVERLAP);
                hi = Math.min(aligned + 1, p2 - JumpRules.EASY_OVERLAP + 1);
            } else {
                lo = p1 - w + 1;
                hi = p2;
            }
            if (lo > hi) {
                return aligned;
            }
            if (lean != 0 && r.chance(0.75)) {
                return lean > 0 ? hi : lo;
            }
            if (r.chance(0.5)) {
                return Math.max(lo, Math.min(hi, aligned));
            }
            return r.nextInt(lo, hi);
        }

        /** Inside the half with the margin, in the band, with its headroom under the half's roof. */
        boolean fits(Pad p) {
            return p.x1() >= half.minX() + MARGIN && p.x2() <= half.maxX() - MARGIN && p.z1() >= half.minZ() + MARGIN
                    && p.z2() <= half.maxZ() - MARGIN && p.top() - 1 >= half.minY()
                    && p.top() + HEADROOM <= half.maxY() && p.top() >= bandLo && p.top() <= bandHi;
        }

        /**
         * Pad {@code i} ({@code to}, after {@code from}) as a jump the tier allows, with no older
         * pad in its headroom or in the new jump's flight, not itself in any older headroom or
         * flight, and out of jumping reach of every older pad.
         */
        boolean clear(int i, Pad from, Pad to) {
            int gx = axisGap(from.x1(), from.x2(), to.x1(), to.x2());
            int gz = axisGap(from.z1(), from.z2(), to.z1(), to.z2());
            int overlap = gx > gz ? overlap(from.z1(), from.z2(), to.z1(), to.z2())
                    : overlap(from.x1(), from.x2(), to.x1(), to.x2());
            if (!JumpRules.allowed(level, to.top() - from.top(), gx, gz, overlap)) {
                return false;
            }
            Box blocks = to.blocks();
            Box head = to.headroom();
            Box flight = flight(from, to);
            for (int k = 0; k < i - 1; k++) {
                Pad p = pads.get(k);
                Box pb = p.blocks();
                if (pb.intersects(head) || pb.intersects(flight) || blocks.intersects(p.headroom())
                        || blocks.intersects(flight(p, pads.get(k + 1)))) {
                    return false;
                }
                boolean aroundTurn = k == i - 2 && from.turnOut() != 0;
                double dIn = aroundTurn ? gapBetween(p, from) : 0;
                if (gapBetween(p, to) < JumpRules.minSkipGap(level, to.top() - p.top(), aroundTurn, dIn) - 1e-9) {
                    return false;
                }
            }
            return true;
        }

        static int[] longest(List<int[]> shapes) {
            int[] best = shapes.get(0);
            for (int[] s : shapes) {
                if (JumpRules.gap(s[0], s[1]) > JumpRules.gap(best[0], best[1])) {
                    best = s;
                }
            }
            return best;
        }
    }

    /** How far {@link Search#room} looks. */
    static final double ROOM_MAX = 48;

    /** Along a ray from {@code o} with step {@code d}: how far until it leaves [lo, hi] (0 if outside already). */
    static double exit(double o, double d, double lo, double hi) {
        if (o < lo || o > hi) {
            return 0;
        }
        if (d > 1e-12) {
            return (hi - o) / d;
        }
        if (d < -1e-12) {
            return (lo - o) / d;
        }
        return Double.MAX_VALUE;
    }

    /**
     * Along a ray from (ox, oz) with step (dx, dz): how far until it enters the rectangle
     * [x1, x2] x [z1, z2]; 0 when it starts inside, {@code MAX_VALUE} when it never does.
     */
    static double enter(double ox, double oz, double dx, double dz, double x1, double x2, double z1, double z2) {
        double tIn = -Double.MAX_VALUE;
        double tOut = Double.MAX_VALUE;
        double[] o = {ox, oz};
        double[] d = {dx, dz};
        double[] lo = {x1, z1};
        double[] hi = {x2, z2};
        for (int a = 0; a < 2; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) {
                    return Double.MAX_VALUE;
                }
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (hi[a] - o[a]) / d[a];
            tIn = Math.max(tIn, Math.min(t1, t2));
            tOut = Math.min(tOut, Math.max(t1, t2));
        }
        if (tIn > tOut || tOut < 0) {
            return Double.MAX_VALUE;
        }
        return Math.max(0, tIn);
    }

    /**
     * Along a ray from (ox, oz) with unit step (dx, dz): how far until it comes within {@code r}
     * of the rectangle [x1, x2] x [z1, z2] (the rectangle grown by r with round corners); 0 when
     * it starts that close, {@code MAX_VALUE} when it never does.
     */
    static double enterRounded(double ox, double oz, double dx, double dz, double x1, double x2, double z1,
                               double z2, double r) {
        double t = Math.min(enter(ox, oz, dx, dz, x1 - r, x2 + r, z1, z2), enter(ox, oz, dx, dz, x1, x2, z1 - r, z2 + r));
        double[][] corners = {{x1, z1}, {x1, z2}, {x2, z1}, {x2, z2}};
        for (double[] c : corners) {
            double fx = ox - c[0];
            double fz = oz - c[1];
            double b = fx * dx + fz * dz;
            double q = fx * fx + fz * fz - r * r;
            if (q <= 0) {
                return 0;
            }
            double disc = b * b - q;
            if (disc >= 0) {
                double hit = -b - Math.sqrt(disc);
                if (hit >= 0) {
                    t = Math.min(t, hit);
                }
            }
        }
        return t;
    }

    // ---- geometry the validator shares ----------------------------------------------------------

    /** Whole blocks of air between two ranges (0 when they overlap or touch). */
    static int axisGap(int a1, int a2, int b1, int b2) {
        if (b1 > a2) {
            return b1 - a2 - 1;
        }
        if (a1 > b2) {
            return a1 - b2 - 1;
        }
        return 0;
    }

    /** How many rows two ranges share. */
    static int overlap(int a1, int a2, int b1, int b2) {
        return Math.max(0, Math.min(a2, b2) - Math.max(a1, b1) + 1);
    }

    /**
     * Whether a mark of {@code radius} over the middle of {@code on} (at its top) reaches any block
     * of {@code other}: the validator's measure.
     */
    static boolean markReaches(Pad on, double radius, Pad other) {
        double ex = Math.max(0, Math.max(other.x1() - on.cx(), on.cx() - (other.x2() + 1)));
        double ez = Math.max(0, Math.max(other.z1() - on.cz(), on.cz() - (other.z2() + 1)));
        double ey = other.top() - on.top();
        return ex * ex + ey * ey + ez * ez <= radius * radius + 1e-9;
    }

    static double gapBetween(Pad a, Pad b) {
        return JumpRules.gap(axisGap(a.x1(), a.x2(), b.x1(), b.x2()), axisGap(a.z1(), a.z2(), b.z1(), b.z2()));
    }

    /** The air a jump flies through: both footprints and the gap, a block wider, up to 3 over the higher top. */
    static Box flight(Pad a, Pad b) {
        return new Box(Math.min(a.x1(), b.x1()) - 1, Math.min(a.top(), b.top()), Math.min(a.z1(), b.z1()) - 1,
                Math.max(a.x2(), b.x2()) + 1, Math.max(a.top(), b.top()) + HEADROOM, Math.max(a.z2(), b.z2()) + 1);
    }

    // ---- from pads to a plan ------------------------------------------------------------------

    private Plan toPlan(PlanInput in, JumpRules.Level level, List<Pad> pads, int fallDesign, long work, int restarts) {
        Box half = in.half();
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        String path = switch (level) {
            case EASY -> Palette.PATH_EASY;
            case MEDIUM -> Palette.PATH_MEDIUM;
            case HARD -> Palette.PATH_HARD;
        };
        for (int k = 0; k < pads.size(); k++) {
            Pad p = pads.get(k);
            String block = switch (p.kind()) {
                case START -> Palette.START;
                case CHECKPOINT -> Palette.CHECKPOINT;
                case FINISH -> Palette.FINISH;
                case PATH -> path;
            };
            // an arrow in the middle of the start pad, and of every easy turn pad, pointing the way on
            String arrow = null;
            if (p.kind() == Kind.START) {
                arrow = arrowToward(p.headingIn());
            } else if (level == JumpRules.Level.EASY && p.turnOut() != 0) {
                arrow = arrowToward(p.headingOut());
            }
            int mx = p.x1() + p.sx() / 2;
            int mz = p.z1() + p.sz() / 2;
            for (int x = p.x1(); x <= p.x2(); x++) {
                for (int z = p.z1(); z <= p.z2(); z++) {
                    String b = arrow != null && x == mx && z == mz ? arrow : block;
                    ops.add(new BlockOp(x, p.top() - 1, z, index(palette, b)));
                }
            }
        }

        Pad start = pads.get(0);
        Pad finish = pads.get(pads.size() - 1);
        Pad beforeFinish = pads.get(pads.size() - 2);
        List<SignText> signs = new ArrayList<>();
        // the start sign: on the start pad's front-left corner, facing the start spot
        int[] fwd = DIRS[start.headingIn()];
        int[] left = {fwd[1], -fwd[0]};
        int sx = (fwd[0] != 0 ? fwd[0] : left[0]) > 0 ? start.x2() : start.x1();
        int sz = (fwd[1] != 0 ? fwd[1] : left[1]) > 0 ? start.z2() : start.z1();
        signs.add(new SignText(sx, start.top(), sz,
                Palette.sign(rotationToward(sx + 0.5, sz + 0.5, start.cx(), start.cz())),
                GenCopy.parkourStart(level.id())));
        // the finish sign: on the finish pad's block furthest from the pad before it, facing back along the path
        int fx = finish.x1();
        int fz = finish.z1();
        double far = -1;
        for (int x = finish.x1(); x <= finish.x2(); x++) {
            for (int z = finish.z1(); z <= finish.z2(); z++) {
                double d = Math.hypot(x + 0.5 - beforeFinish.cx(), z + 0.5 - beforeFinish.cz());
                if (d > far + 1e-9) {
                    far = d;
                    fx = x;
                    fz = z;
                }
            }
        }
        signs.add(new SignText(fx, finish.top(), fz,
                Palette.sign(rotationToward(fx + 0.5, fz + 0.5, beforeFinish.cx(), beforeFinish.cz())),
                GenCopy.finish()));

        // the course: start on the start pad's middle, facing the first jump; a mark on every checkpoint and the finish
        Pad first = pads.get(1);
        Course.Spot spot = new Course.Spot(start.cx(), start.top(), start.cz(),
                yawToward(start.cx(), start.cz(), first.cx(), first.cz()), 0f);
        List<Course.Mark> checkpoints = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        double length = 0;
        for (int k = 0; k < pads.size(); k++) {
            Pad p = pads.get(k);
            lowest = Math.min(lowest, p.top());
            highest = Math.max(highest, p.top());
            if (k > 0) {
                Pad q = pads.get(k - 1);
                length += Math.hypot(p.cx() - q.cx(), p.cz() - q.cz());
            }
            if (p.kind() == Kind.CHECKPOINT) {
                checkpoints.add(new Course.Mark(p.cx(), p.top(), p.cz(), CHECKPOINT_RADIUS));
            }
        }
        Course.Mark end = new Course.Mark(finish.cx(), finish.top(), finish.cz(), FINISH_RADIUS);
        Double fallY = level.fixedFall() ? Double.valueOf(lowest - EASY_FALL) : null;
        double speed = level.mode() == JumpSim.Mode.WALK ? WALK_SPEED : SPRINT_SPEED;
        long refMs = Math.round(1000 * (level.jumps() * SECONDS_PER_JUMP + length / speed));
        int minSeconds = (int) Math.max(5, Math.floor(0.4 * refMs / 1000.0));
        Slots.Def slot = in.slot();
        Course course = new Course(slot.id(), TrialKind.PARKOUR, slot.name(), Tier.of(level.id()), "", spot,
                checkpoints, end, fallY, minSeconds, true, false, 1);

        List<Box> keepClear = new ArrayList<>();
        for (int k = 0; k < pads.size(); k++) {
            clip(keepClear, pads.get(k).headroom(), half);
            if (k > 0) {
                clip(keepClear, flight(pads.get(k - 1), pads.get(k)), half);
            }
        }

        List<String> summary = new ArrayList<>();
        summary.add(slot.name() + " (" + level.id() + "): " + level.jumps() + " jumps, " + checkpoints.size()
                + " checkpoints, reference " + String.format(Locale.ROOT, "%.1f", refMs / 1000.0) + "s, shortest "
                + minSeconds + "s");
        StringBuilder jumps = new StringBuilder("jumps (height/gap):");
        for (int k = 1; k < pads.size(); k++) {
            Pad a = pads.get(k - 1);
            Pad b = pads.get(k);
            jumps.append(' ').append(JumpRules.signed(b.top() - a.top())).append('/')
                    .append(Math.round(gapBetween(a, b) * 10) / 10.0);
            if (b.kind() == Kind.CHECKPOINT) {
                jumps.append(" |");
            }
        }
        summary.add(jumps.toString());
        summary.add("pads y " + lowest + ".." + highest + (level.fixedFall() ? ", falls back below y " + (lowest
                - EASY_FALL) : ", falls back " + clampDepth(in.fallDepth()) + " under the checkpoints (shaped for "
                + fallDesign + ")"));
        summary.add("seed " + GenSeed.shortHex(in.seed()) + ", " + (restarts + 1) + " start(s), " + work + " tries");
        return Plan.of(slot.id(), ALGO, in.seed(), half, palette, ops, signs, keepClear,
                new PlannedTrial(course, refMs), summary, work);
    }

    private static void clip(List<Box> out, Box b, Box half) {
        if (b.intersects(half)) {
            out.add(new Box(Math.max(b.minX(), half.minX()), Math.max(b.minY(), half.minY()),
                    Math.max(b.minZ(), half.minZ()), Math.min(b.maxX(), half.maxX()), Math.min(b.maxY(), half.maxY()),
                    Math.min(b.maxZ(), half.maxZ())));
        }
    }

    /** The palette index of {@code block}, adding it on first use (so the palette is in first-use order). */
    static short index(List<String> palette, String block) {
        int i = palette.indexOf(block);
        if (i < 0) {
            palette.add(block);
            i = palette.size() - 1;
        }
        return (short) i;
    }

    /**
     * The magenta glazed terracotta whose arrow points along {@code heading} (a side, not a
     * diagonal). Glazed terracotta is placed facing the player who places it, and the magenta
     * arrow points away from them, so an arrow pointing east is the block facing west.
     */
    static String arrowToward(int heading) {
        return Palette.arrow(switch (Math.floorMod(heading, 8)) {
            case 0 -> "west";
            case 2 -> "north";
            case 4 -> "east";
            case 6 -> "south";
            default -> throw new IllegalArgumentException("an arrow points along a side, not heading " + heading);
        });
    }

    /** Minecraft's yaw (0 = south, 90 = west) from (x, z) toward (tx, tz). */
    static float yawToward(double x, double z, double tx, double tz) {
        double yaw = StrictMath.toDegrees(StrictMath.atan2(-(tx - x), tz - z));
        return (float) (yaw < 0 ? yaw + 360 : yaw);
    }

    /** A standing sign's rotation (sixteenths; 0 = its text faces south) so its text faces (tx, tz). */
    static int rotationToward(double x, double z, double tx, double tz) {
        return Math.floorMod((int) Math.round(yawToward(x, z, tx, tz) / 22.5), 16);
    }
}
