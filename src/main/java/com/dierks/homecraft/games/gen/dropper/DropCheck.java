package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One level read back from the blocks, and flown (EVENTS-DROPPER-SPEC §B.1.6): what the validator
 * checks, and what the planner runs on each level it draws so a level that fails is redrawn before
 * the plan is ever handed over.
 *
 * <p><b>Everything comes from the voxels and the marks.</b> The ledge is the lime blocks under its
 * mark; the shaft's inside is where the glass is, walking out from the ledge; the way the shaft
 * faces is away from the one wall the ledge stands against; the pool is its mark's box; the layers
 * are the rows between the ledge and the water that hold anything solid. A witness program says only
 * which keys are held; where it goes, what it passes and where it lands are worked out here.
 *
 * <p><b>Where a pilot aims.</b> At each layer, the largest open square round the witness's crossing
 * point (the nearest one when there are several): the hole a child sees the way going through.
 */
public final class DropCheck {

    /**
     * A level as its blocks say it is.
     *
     * @param level     the level's index (0-based)
     * @param tier      its tier
     * @param x1        the shaft's inside, min and max blocks
     * @param z1        ...
     * @param x2        ...
     * @param z2        ...
     * @param ledgeTop  where the feet stand on the ledge
     * @param ledge     the ledge's blocks {x1, z1, x2, z2}
     * @param forward   the way the shaft is from the ledge
     * @param edgeX     the middle of the ledge's front edge
     * @param edgeZ     ...
     * @param pool      the pool's mark
     * @param surfaceY  the water's surface
     * @param layerRows the rows holding obstacles, top first
     */
    public record View(int level, DropRules.Level tier, int x1, int z1, int x2, int z2, int ledgeTop, int[] ledge,
                       DropProgram.Dir forward, double edgeX, double edgeZ, Course.Mark pool, int surfaceY,
                       List<Integer> layerRows) {

        /** The forward direction as a unit vector. */
        public double fx() {
            return forward.ux();
        }

        public double fz() {
            return forward.uz();
        }

        /** Where the witness starts: the middle exit, walking off in the middle of a step. */
        public DropSim.Body witnessStart() {
            return DropPilot.start(edgeX, edgeZ, fx(), fz(), -fz(), fx(), 0, DropPilot.WITNESS_TIMING, ledgeTop,
                    false);
        }

        /** How far block (x, z) is from the ledge's back wall, in blocks along forward (0-10). */
        public int forwardCell(int x, int z) {
            return switch (forward) {
                case S -> z - z1;
                case N -> z2 - z;
                case E -> x - x1;
                case W -> x2 - x;
                default -> 0;
            };
        }
    }

    /** A view, or why the blocks don't make one. */
    public record Read(View view, String problem) {
    }

    /**
     * How a witness went: the fall, and per layer (top first) where its feet crossed the plate's top
     * {x, z, tick}.
     */
    public record Flight(DropRun.Result result, List<double[]> crossings) {
    }

    /** A pilot that didn't make it. */
    public record Miss(String label, DropRun.Result result) {
    }

    private DropCheck() {
    }

    // ---- reading a level from the blocks --------------------------------------------------------------

    /** Level {@code level} as the blocks round its marks say it is. */
    public static Read read(DropWorld w, int level, DropRules.Level tier, Course.Mark ledgeMark,
                            Course.Mark poolMark) {
        String name = "level " + (level + 1);
        if (ledgeMark == null || poolMark == null) {
            return new Read(null, name + " has no ledge or no pool mark");
        }
        double top = ledgeMark.y();
        if (Math.abs(top - Math.rint(top)) > 1e-9) {
            return new Read(null, name + "'s ledge mark isn't on a block's top");
        }
        int ledgeTop = (int) Math.rint(top);
        int row = ledgeTop - 1;
        int cx = (int) Math.floor(ledgeMark.x());
        int cz = (int) Math.floor(ledgeMark.z());
        if (w.get(cx, row, cz) != DropWorld.LEDGE) {
            return new Read(null, name + "'s ledge mark isn't over its ledge");
        }
        // the ledge: the lime blocks joined to the one under the mark
        int[] lb = {cx, cz, cx, cz};
        List<int[]> todo = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        todo.add(new int[]{cx, cz});
        seen.add(key(cx, cz));
        while (!todo.isEmpty()) {
            int[] c = todo.remove(todo.size() - 1);
            lb[0] = Math.min(lb[0], c[0]);
            lb[1] = Math.min(lb[1], c[1]);
            lb[2] = Math.max(lb[2], c[0]);
            lb[3] = Math.max(lb[3], c[1]);
            if (seen.size() > 64) {
                return new Read(null, name + "'s ledge is far bigger than a ledge");
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (w.get(nx, row, nz) == DropWorld.LEDGE && seen.add(key(nx, nz))) {
                    todo.add(new int[]{nx, nz});
                }
            }
        }
        // the inside: walk out from the ledge's middle, over it, to the glass each way
        int x1 = cx;
        while (x1 - 1 >= w.half().minX() && w.get(x1 - 1, ledgeTop, cz) != DropWorld.WALL) {
            x1--;
        }
        int x2 = cx;
        while (x2 + 1 <= w.half().maxX() && w.get(x2 + 1, ledgeTop, cz) != DropWorld.WALL) {
            x2++;
        }
        int z1 = cz;
        while (z1 - 1 >= w.half().minZ() && w.get(cx, ledgeTop, z1 - 1) != DropWorld.WALL) {
            z1--;
        }
        int z2 = cz;
        while (z2 + 1 <= w.half().maxZ() && w.get(cx, ledgeTop, z2 + 1) != DropWorld.WALL) {
            z2++;
        }
        if (x2 - x1 + 1 != DropperGeometry.INSIDE || z2 - z1 + 1 != DropperGeometry.INSIDE) {
            return new Read(null, name + "'s shaft isn't " + DropperGeometry.INSIDE + " x " + DropperGeometry.INSIDE
                    + " inside glass at its ledge (" + (x2 - x1 + 1) + " x " + (z2 - z1 + 1) + ")");
        }
        int size = DropperGeometry.LEDGE;
        if (lb[2] - lb[0] + 1 != size || lb[3] - lb[1] + 1 != size) {
            return new Read(null, name + "'s ledge isn't " + size + " x " + size);
        }
        // the one wall it stands against says which way the shaft is
        List<DropProgram.Dir> against = new ArrayList<>();
        if (lb[1] == z1) {
            against.add(DropProgram.Dir.S);
        }
        if (lb[3] == z2) {
            against.add(DropProgram.Dir.N);
        }
        if (lb[0] == x1) {
            against.add(DropProgram.Dir.E);
        }
        if (lb[2] == x2) {
            against.add(DropProgram.Dir.W);
        }
        if (against.size() != 1) {
            return new Read(null, name + "'s ledge must stand against exactly one wall, not " + against.size());
        }
        DropProgram.Dir fwd = against.get(0);
        double mx = (lb[0] + lb[2] + 1) / 2.0;
        double mz = (lb[1] + lb[3] + 1) / 2.0;
        double ex = switch (fwd) {
            case E -> lb[2] + 1;
            case W -> lb[0];
            default -> mx;
        };
        double ez = switch (fwd) {
            case S -> lb[3] + 1;
            case N -> lb[1];
            default -> mz;
        };
        double s = DropMarks.surface(poolMark);
        if (Math.abs(s - Math.rint(s)) > 1e-9) {
            return new Read(null, name + "'s pool mark puts the water off a block");
        }
        int surfaceY = (int) Math.rint(s);
        List<Integer> rows = new ArrayList<>();
        for (int y = row; y >= surfaceY; y--) {
            boolean any = false;
            for (int x = x1; x <= x2 && !any; x++) {
                for (int z = z1; z <= z2 && !any; z++) {
                    any = w.get(x, y, z) == DropWorld.SOLID;
                }
            }
            if (any) {
                rows.add(y);
            }
        }
        return new Read(new View(level, tier, x1, z1, x2, z2, ledgeTop, lb, fwd, ex, ez, poolMark, surfaceY,
                List.copyOf(rows)), null);
    }

    // ---- flying ---------------------------------------------------------------------------------------

    /** Fly a witness program from the middle exit with clearance {@code r}, and note its layer crossings. */
    public static Flight witness(DropWorld w, View v, DropProgram p, double r) {
        DropRun.Result res = DropRun.fly(w, v.witnessStart(), p.controller(), r, DropSim.MAX_TICKS, Double.NaN,
                true);
        return new Flight(res, crossings(res.path(), v.layerRows()));
    }

    /**
     * Where a path's feet cross each row's top (top first), {x, z, tick}, interpolated along the
     * sub-step; a row it never crosses is left out.
     */
    public static List<double[]> crossings(List<double[]> path, List<Integer> rows) {
        List<double[]> out = new ArrayList<>();
        int i = 1;
        for (int row : rows) {
            double top = row + 1;
            while (i < path.size() && path.get(i)[1] > top) {
                i++;
            }
            if (i >= path.size()) {
                break;
            }
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double t = a[1] == b[1] ? 1 : (a[1] - top) / (a[1] - b[1]);
            t = Math.max(0, Math.min(1, t));
            out.add(new double[]{a[0] + t * (b[0] - a[0]), a[2] + t * (b[2] - a[2]), b[3]});
        }
        return out;
    }

    /**
     * The side of the largest square of open blocks at {@code row}, inside the shaft, whose footprint
     * holds point (px, pz); 0 when the point's own block is solid.
     */
    public static int openSquare(DropWorld w, View v, int row, double px, double pz) {
        int[] best = square(w, v, row, px, pz);
        return best == null ? 0 : best[2];
    }

    /** The centre (x, z) a pilot aims at through row {@code row}, round the crossing (px, pz). */
    public static double[] aim(DropWorld w, View v, int row, double px, double pz) {
        int[] sq = square(w, v, row, px, pz);
        if (sq == null) {
            return new double[]{px, pz};
        }
        return new double[]{sq[0] + sq[2] / 2.0, sq[1] + sq[2] / 2.0};
    }

    /** {x, z, side} of the largest open square holding (px, pz), the nearest when several; or null. */
    private static int[] square(DropWorld w, View v, int row, double px, double pz) {
        for (int k = DropperGeometry.INSIDE; k >= 1; k--) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            int xLo = Math.max(v.x1(), (int) Math.floor(px) - k + 1);
            int xHi = Math.min(v.x2() - k + 1, (int) Math.floor(px));
            int zLo = Math.max(v.z1(), (int) Math.floor(pz) - k + 1);
            int zHi = Math.min(v.z2() - k + 1, (int) Math.floor(pz));
            for (int wx = xLo; wx <= xHi; wx++) {
                for (int wz = zLo; wz <= zHi; wz++) {
                    if (!open(w, row, wx, wz, k)) {
                        continue;
                    }
                    double dx = wx + k / 2.0 - px;
                    double dz = wz + k / 2.0 - pz;
                    double d = dx * dx + dz * dz;
                    if (d < bestD - 1e-12) {
                        bestD = d;
                        best = new int[]{wx, wz, k};
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean open(DropWorld w, int row, int wx, int wz, int k) {
        for (int x = wx; x < wx + k; x++) {
            for (int z = wz; z < wz + k; z++) {
                if (w.solid(x, row, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** What the pilots aim at: through each layer round the witness's crossing, then the pool's centre. */
    public static List<DropPilot.Target> targets(DropWorld w, View v, List<double[]> crossings) {
        List<DropPilot.Target> out = new ArrayList<>();
        for (int i = 0; i < v.layerRows().size() && i < crossings.size(); i++) {
            int row = v.layerRows().get(i);
            double[] c = crossings.get(i);
            double[] a = aim(w, v, row, c[0], c[1]);
            out.add(new DropPilot.Target(a[0], a[1], row));
        }
        out.add(new DropPilot.Target(v.pool().x(), v.pool().z(), Double.NaN));
        return out;
    }

    /**
     * Fly the tier's pilots at {@code targets} with half the clearance; every one that doesn't
     * splash, the hardest first. {@code firstOnly} stops at the first miss (the planner's retry);
     * {@code work} gets the ticks flown added.
     */
    public static List<Miss> pilots(DropWorld w, View v, List<DropPilot.Target> targets, boolean firstOnly,
                                    long[] work) {
        List<DropPilot.Variant> all = DropPilot.variants(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), v.ledgeTop(), targets,
                v.tier());
        List<Miss> out = new ArrayList<>();
        double inflate = v.tier().tube() / 2;
        for (int i = all.size() - 1; i >= 0; i--) {
            DropPilot.Variant p = all.get(i);
            DropRun.Result r = DropRun.fly(w, p.start(), p.pilot().fresh(), inflate, DropSim.MAX_TICKS, Double.NaN,
                    false);
            if (work != null) {
                work[0] += r.ticks();
            }
            if (!r.splashed()) {
                out.add(new Miss(p.label(), r));
                if (firstOnly) {
                    return out;
                }
            }
        }
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
