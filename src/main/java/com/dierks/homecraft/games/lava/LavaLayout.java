package com.dierks.homecraft.games.lava;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 0.57.0: where the playroom's furniture stands this time (the owner: "if the room could change maybe that would be
 * more fun? You don't even need to change the furniture per se, just move it around"). Pure and deterministic: the
 * same seed always gives the same layout, so a round, a test and a replay agree.
 *
 * <p><b>The pieces.</b> The same furniture every time ({@link #CATALOGUE}: the sofa, the coffee table, the toy chest,
 * two toy staircases, two floor cushions and four stools), each in the same slot of every layout, so a piece is the
 * same piece before and after a shift (who stood on it rides along: {@link LavaShift}). A layout gives each a place,
 * a turn (0 to 3 quarter turns) and a style (a stool's height, a toy staircase's colours). The bookcase with its
 * gallery is part of the room itself and never moves: it is the room's highest ground.
 *
 * <p><b>Every layout is playable</b>, and {@link LavaPlanner#layout} only hands out one that passes its check
 * (else the next try of the same seed; after them all, the room as built):
 * <ul>
 *   <li>every piece stands clear of the walls, the bookcase and every other piece, with at least a block of floor
 *       all round it, so a piece's own half-block steps always start from open floor and nothing is ever walled in;
 *   </li>
 *   <li>every piece with steps ({@link Kind#stepped}) is a walk of half steps from the floor (no jump needed: Lilah);
 *       every stool is a jump from the floor or from a piece next to it ({@link LavaSite#hopReach});</li>
 *   <li>most pieces stand near another (a gap of one to three blocks), so there are always hops from piece to piece
 *       while the floor is lava, and the highest ground (the bookcase's second shelf) is the same in every layout,
 *       so Challenge's last level is always the same.</li>
 * </ul>
 */
public final class LavaLayout {

    /** A kind of furniture: its footprint unturned ({@code w} along x, {@code d} along z; its front is +z). */
    public enum Kind {
        SOFA(14, 10, true),
        TABLE(8, 8, true),
        CHEST(10, 7, true),
        TOYS(5, 4, true),
        CUSHION(3, 3, true),
        /** A toy-block stool: no steps, a hop from the floor or a piece beside it. */
        STOOL(2, 2, false);

        public final int w;
        public final int d;
        /** It has its own half-block steps from the floor (a walk); otherwise it is reached by a jump. */
        public final boolean stepped;

        Kind(int w, int d, boolean stepped) {
            this.w = w;
            this.d = d;
            this.stepped = stepped;
        }
    }

    /** Every layout's pieces, in slot order: a piece keeps its slot through every shift. */
    public static final List<Kind> CATALOGUE = List.of(Kind.SOFA, Kind.TABLE, Kind.CHEST, Kind.TOYS, Kind.TOYS,
            Kind.CUSHION, Kind.CUSHION, Kind.STOOL, Kind.STOOL, Kind.STOOL, Kind.STOOL);

    /** The stools' heights (feet over the floor block), by style. */
    static final double[] STOOL_FEET = {2.0, 2.5, 3.0};

    /**
     * One piece where it stands.
     *
     * @param x      its footprint's west edge (an offset from the box's corner)
     * @param z      its footprint's north edge
     * @param turns  quarter turns clockwise (seen from above), 0 to 3
     * @param style  its look and, for a stool, its height ({@link #STOOL_FEET})
     * @param bouncy 0.57.0 surprise: a cushion or a stool made of slime for one round
     */
    public record Placed(Kind kind, int x, int z, int turns, int style, boolean bouncy) {

        public Placed {
            turns = Math.floorMod(turns, 4);
        }

        /** Its footprint along x, turned. */
        public int sizeX() {
            return turns % 2 == 0 ? kind.w : kind.d;
        }

        /** Its footprint along z, turned. */
        public int sizeZ() {
            return turns % 2 == 0 ? kind.d : kind.w;
        }

        /** The room offsets {x, z} of the piece's own cell (i, k). */
        public int[] at(int i, int k) {
            int w = kind.w;
            int d = kind.d;
            return switch (turns) {
                case 1 -> new int[]{x + d - 1 - k, z + i};
                case 2 -> new int[]{x + w - 1 - i, z + d - 1 - k};
                case 3 -> new int[]{x + k, z + w - 1 - i};
                default -> new int[]{x + i, z + k};
            };
        }

        /** The piece's own cell {i, k} at room offsets (x, z), or {@code null} when it isn't on the footprint. */
        public int[] local(int ox, int oz) {
            if (ox < x || oz < z || ox >= x + sizeX() || oz >= z + sizeZ()) {
                return null;
            }
            int a = ox - x;
            int b = oz - z;
            int w = kind.w;
            int d = kind.d;
            return switch (turns) {
                case 1 -> new int[]{b, d - 1 - a};
                case 2 -> new int[]{w - 1 - a, d - 1 - b};
                case 3 -> new int[]{w - 1 - b, a};
                default -> new int[]{a, b};
            };
        }

        /** The yaw of the piece's front (+z unturned is south, yaw 0). */
        public float frontYaw() {
            return switch (turns) {
                case 1 -> 90f; // the front faces west
                case 2 -> 180f;
                case 3 -> -90f;
                default -> 0f;
            };
        }

        /** The same piece somewhere else. */
        Placed movedTo(int nx, int nz, int nturns) {
            return new Placed(kind, nx, nz, nturns, style, bouncy);
        }

        /** The same piece, made of slime (or not). */
        public Placed bouncy(boolean on) {
            return new Placed(kind, x, z, turns, style, on);
        }

        /** Whether the other placement covers any cell within {@code margin} of this one's footprint. */
        boolean near(int ox, int oz, int sx, int sz, int margin) {
            return ox < x + sizeX() + margin && ox + sx > x - margin && oz < z + sizeZ() + margin
                    && oz + sz > z - margin;
        }
    }

    /** The open floor pieces may stand on: offsets {@value #EDGE}..{@code size - 1 - EDGE}. */
    static final int EDGE = 2;
    /** Pieces keep at least this much floor between them. */
    static final int APART = 1;
    /** A layout's tries before {@link LavaPlanner#layout} settles for the room as built. */
    static final int TRIES = 40;

    private LavaLayout() {
    }

    /**
     * A candidate layout for {@code seed}'s try {@code attempt} in a room {@code size} across, with the area
     * {@code reserved} (x0, z0, x1, z1, inclusive offsets) kept clear: the bookcase. {@code null} when the pieces
     * didn't all fit (the next try may).
     */
    static List<Placed> candidate(long seed, int attempt, int size, int[] reserved) {
        SplittableRandom rng = new SplittableRandom(mix(seed, attempt));
        List<Placed> out = new ArrayList<>();
        // the big pieces each in a part of the room of their own (four quarters and the middle, shuffled), so the
        // furniture is spread over the room; the cushions and stools go near them, a hop away
        List<Integer> parts = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        Collections.shuffle(parts, new java.util.Random(rng.nextLong()));
        int big = 0;
        for (Kind kind : CATALOGUE) {
            int part = kind.stepped && kind != Kind.CUSHION ? parts.get(big++ % parts.size()) : -1;
            int style = rng.nextInt(kind == Kind.STOOL ? STOOL_FEET.length : 6);
            Placed placed = null;
            for (int t = 0; t < 400 && placed == null; t++) {
                int turns = rng.nextInt(4);
                int sx = turns % 2 == 0 ? kind.w : kind.d;
                int sz = turns % 2 == 0 ? kind.d : kind.w;
                int x;
                int z;
                boolean near = !out.isEmpty() && part < 0;
                if (near) {
                    Placed base = out.get(rng.nextInt(out.size()));
                    int gap = APART + rng.nextInt(2); // a gap a jump clears
                    switch (rng.nextInt(4)) {
                        case 0 -> { // east of it
                            x = base.x() + base.sizeX() + gap;
                            z = base.z() - sz + 1 + rng.nextInt(base.sizeZ() + sz - 1);
                        }
                        case 1 -> { // west
                            x = base.x() - gap - sx;
                            z = base.z() - sz + 1 + rng.nextInt(base.sizeZ() + sz - 1);
                        }
                        case 2 -> { // south
                            z = base.z() + base.sizeZ() + gap;
                            x = base.x() - sx + 1 + rng.nextInt(base.sizeX() + sx - 1);
                        }
                        default -> { // north
                            z = base.z() - gap - sz;
                            x = base.x() - sx + 1 + rng.nextInt(base.sizeX() + sx - 1);
                        }
                    }
                } else {
                    int half = size / 2;
                    int x0 = part == 4 ? half - 8 : part % 2 == 0 ? EDGE : half;
                    int z0 = part == 4 ? half - 8 : part < 2 ? EDGE : half;
                    int x1 = part == 4 ? half + 8 : part % 2 == 0 ? half : size - EDGE;
                    int z1 = part == 4 ? half + 8 : part < 2 ? half : size - EDGE;
                    x = x0 + rng.nextInt(Math.max(1, x1 - x0 - sx + 1));
                    z = z0 + rng.nextInt(Math.max(1, z1 - z0 - sz + 1));
                }
                if (fits(out, x, z, sx, sz, size, reserved)) {
                    placed = new Placed(kind, x, z, turns, style, false);
                }
            }
            if (placed == null) {
                return null;
            }
            out.add(placed);
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether a footprint is on the open floor, clear of the bookcase and {@value #APART} from every piece. */
    static boolean fits(List<Placed> others, int x, int z, int sx, int sz, int size, int[] reserved) {
        if (x < EDGE || z < EDGE || x + sx > size - EDGE || z + sz > size - EDGE) {
            return false;
        }
        if (reserved != null && x <= reserved[2] && x + sx - 1 >= reserved[0] && z <= reserved[3]
                && z + sz - 1 >= reserved[1]) {
            return false;
        }
        for (Placed p : others) {
            if (p != null && p.near(x, z, sx, sz, APART)) {
                return false;
            }
        }
        return true;
    }

    /** Which pieces stand somewhere else (or are gone, or come back) between two layouts, by slot. */
    public static List<Integer> moved(List<Placed> from, List<Placed> to) {
        List<Integer> out = new ArrayList<>();
        int n = Math.max(from.size(), to.size());
        for (int i = 0; i < n; i++) {
            Placed a = i < from.size() ? from.get(i) : null;
            Placed b = i < to.size() ? to.get(i) : null;
            if (a == null ? b != null : !a.equals(b)) {
                out.add(i);
            }
        }
        return out;
    }

    /** The layout without the piece in {@code slot} (it sank into the lava). */
    public static List<Placed> without(List<Placed> layout, int slot) {
        List<Placed> out = new ArrayList<>(layout);
        if (slot >= 0 && slot < out.size()) {
            out.set(slot, null);
        }
        return Collections.unmodifiableList(out);
    }

    /** The layout with the piece in {@code slot} made bouncy. */
    public static List<Placed> bouncy(List<Placed> layout, int slot) {
        List<Placed> out = new ArrayList<>(layout);
        if (slot >= 0 && slot < out.size() && out.get(slot) != null) {
            out.set(slot, out.get(slot).bouncy(true));
        }
        return Collections.unmodifiableList(out);
    }

    /** A seed for a try (or a round) from a seed: SplitMix64's finaliser, so near seeds give unrelated layouts. */
    public static long mix(long seed, long n) {
        long z = seed + 0x9E3779B97F4A7C15L * (n + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
