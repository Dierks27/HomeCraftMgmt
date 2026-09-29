package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dropper's proof (EVENTS-DROPPER-SPEC §B.1.6), from the plan alone: its blocks, its signs, its
 * course marks and the witness programs written in its summary. Nothing the planner kept to itself
 * is trusted; a plan passes only if all of these hold.
 *
 * <ol>
 *   <li><b>Witness replay.</b> Flown in 4 swept sub-steps a tick, each witness program (and a TWIN's
 *       second one) never lets the hitbox grown by r touch an obstacle or the floor round the pool,
 *       never lands, and enters the pool's water. Walls and the ledge are checked with the plain
 *       hitbox: a scrape isn't a landing.</li>
 *   <li><b>The 33 pilots</b> ({@link DropPilot}) reach the water from every sampled start, never
 *       touching an obstacle with the hitbox grown by r/2 and never standing on a block.</li>
 *   <li><b>Enclosure:</b> each shaft's walls are glass from the pool floor to the ledge top + 4, it has
 *       a floor and no roof, and a flood fill from the ledge (as high as a jump can reach) stays inside
 *       its own shaft.</li>
 *   <li><b>Sealed pools:</b> every water block has water or a solid block on its 4 sides and below;
 *       water is only inside pool boxes, and every block of a pool box's 3 rows is water (the game
 *       counts a move into the box as the splash); every pool box is at least 1 block inside the
 *       half.</li>
 *   <li><b>Openings and inputs:</b> each layer's opening round the witness is at least the tier's
 *       minimum, and the witness's input changes are within the tier's limit, early enough. The level
 *       is the tier's shape: its drop, pool size, number of layers and clear air over the water.</li>
 *   <li><b>The marks</b> follow {@link DropMarks#problems}, one level per letter of the mix, each
 *       ledge mark on its lime ledge and each pool mark over its water; {@code fallY} is under every
 *       pool's floor.</li>
 *   <li><b>Every op is inside the half,</b> none twice, at most {@value #MAX_OPS}.</li>
 *   <li><b>Palette lint:</b> full cubes only ({@link DropBlocks#allowed}), and water only as a still
 *       source.</li>
 *   <li><b>Signs:</b> one per level, inside the half, on the wall over its ledge facing into the
 *       shaft, reading "LEVEL i of n" in GenCopy's rules.</li>
 * </ol>
 */
public final class DropperValidator {

    /** The most blocks a Dropper plan may place. */
    public static final int MAX_OPS = 20_000;
    /** How a level's witness is written in the summary: {@code "level 2 witness: S7 -*"}. */
    public static final String WITNESS_PREFIX = "level %d witness: ";
    /** How a TWIN layer's second witness is written. */
    public static final String TWIN_PREFIX = "level %d twin: ";
    /** At most this many pilot misses are listed per level. */
    private static final int MISS_LINES = 3;

    private DropperValidator() {
    }

    /**
     * Everything wrong with {@code plan} as a Dropper of the mix its own pools name
     * ({@link DropMarks#mix}): for a plan that arrives without its slot, a recalled or kept one.
     */
    public static List<String> problems(Plan plan) {
        String mix = plan != null && plan.course() instanceof PlannedTrial t ? DropMarks.mix(t.course()) : null;
        if (mix == null) {
            List<String> out = new ArrayList<>();
            out.add(plan == null ? "there is no plan" : "its pools don't name a dropper mix");
            return out;
        }
        return problems(plan, mix);
    }

    /** Everything wrong with {@code plan} as a Dropper of {@code mix}; empty when it is proven. */
    public static List<String> problems(Plan plan, String mix) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            out.add("there is no plan");
            return out;
        }
        List<DropRules.Level> tiers = DropRules.levels(mix);
        if (tiers.isEmpty()) {
            out.add(DropRules.mixProblem(mix));
            return out;
        }
        Box half = plan.half();
        // 7: ops inside the half, none twice, not too many
        if (plan.ops().size() > MAX_OPS) {
            out.add("the plan places " + plan.ops().size() + " blocks; a dropper places at most " + MAX_OPS);
        }
        boolean[] seen = new boolean[(int) Math.min(Integer.MAX_VALUE, half.volume())];
        int outside = 0;
        int twice = 0;
        for (BlockOp op : plan.ops()) {
            if (!half.contains(op.x(), op.y(), op.z())) {
                outside++;
            } else {
                int at = (int) (((long) (op.y() - half.minY()) * half.sizeX() + (op.x() - half.minX())) * half.sizeZ()
                        + (op.z() - half.minZ()));
                if (seen[at]) {
                    twice++;
                }
                seen[at] = true;
            }
            if (op.state() >= plan.palette().size()) {
                out.add("a block at " + op.x() + "," + op.y() + "," + op.z() + " has no palette entry");
                return out;
            }
        }
        if (outside > 0) {
            out.add(outside + " block" + (outside == 1 ? " is" : "s are") + " outside the half");
        }
        if (twice > 0) {
            out.add(twice + " block" + (twice == 1 ? " is" : "s are") + " set twice");
        }
        // 8: palette lint
        for (String p : plan.palette()) {
            if (!DropBlocks.allowed(p)) {
                out.add("the palette has " + p + ", which a dropper may not place");
            }
        }
        // 6: the marks
        if (!(plan.course() instanceof PlannedTrial t)) {
            out.add("a dropper's plan has no trial course");
            return out;
        }
        Course c = t.course();
        List<String> marks = DropMarks.problems(c);
        out.addAll(marks);
        if (!marks.isEmpty()) {
            return out;
        }
        int n = DropMarks.levels(c);
        if (n != tiers.size()) {
            out.add("the course has " + n + " levels; the mix " + DropRules.normalise(mix) + " has " + tiers.size());
            return out;
        }
        if (c.start() != null && !half.contains(c.start().x(), c.start().y(), c.start().z())) {
            out.add("the start is outside the half");
        }
        for (Course.Mark m : c.targets()) {
            if (!half.contains(m.x(), m.y(), m.z())) {
                out.add("a mark at " + m.x() + "," + m.y() + "," + m.z() + " is outside the half");
            }
        }
        DropWorld w = DropWorld.of(plan);
        // 4: water only in pool boxes, every pool box inside the half by a block
        List<Course.Mark> pools = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Course.Mark pool = DropMarks.poolOf(c, i);
            pools.add(pool);
            double[] b = DropMarks.poolBox(pool);
            if (b[0] < half.minX() + 1 || b[3] > half.maxX() || b[2] < half.minZ() + 1 || b[5] > half.maxZ()
                    || b[1] < half.minY() + 1 || b[4] > half.maxY()) {
                out.add("level " + (i + 1) + "'s pool isn't a block inside the half");
            }
        }
        int strayWater = 0;
        int openWater = 0;
        for (BlockOp op : plan.ops()) {
            if (w.get(op.x(), op.y(), op.z()) != DropWorld.WATER) {
                continue;
            }
            boolean inBox = false;
            for (Course.Mark pool : pools) {
                double[] b = DropMarks.poolBox(pool);
                double s = DropMarks.surface(pool);
                if (op.x() >= b[0] && op.x() + 1 <= b[3] && op.z() >= b[2] && op.z() + 1 <= b[5] && op.y() >= b[1]
                        && op.y() + 1 <= s) {
                    inBox = true;
                    break;
                }
            }
            if (!inBox) {
                strayWater++;
            }
            int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
            for (int[] d : around) {
                byte k = w.get(op.x() + d[0], op.y() + d[1], op.z() + d[2]);
                if (k != DropWorld.WATER && !w.solid(op.x() + d[0], op.y() + d[1], op.z() + d[2])) {
                    openWater++;
                    break;
                }
            }
        }
        if (strayWater > 0) {
            out.add(strayWater + " water block" + (strayWater == 1 ? " is" : "s are") + " outside every pool box");
        }
        if (openWater > 0) {
            out.add(openWater + " water block" + (openWater == 1 ? " has" : "s have")
                    + " air beside or under it: pools must be sealed");
        }
        // 9: signs
        out.addAll(signProblems(plan, c, n));
        // 1, 2, 3, 5: each level
        List<DropProgram> witnesses = programs(plan, WITNESS_PREFIX, n);
        List<DropProgram> twins = programs(plan, TWIN_PREFIX, n);
        for (int i = 0; i < n; i++) {
            out.addAll(level(w, c, i, tiers.get(i), witnesses.get(i), twins.get(i)));
        }
        return out;
    }

    /** The programs the summary gives per level (null where it gives none). */
    public static List<DropProgram> programs(Plan plan, String prefix, int levels) {
        List<DropProgram> out = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            String head = prefix.formatted(i + 1);
            DropProgram p = null;
            for (String line : plan.summary()) {
                if (line.startsWith(head)) {
                    p = DropProgram.parse(line.substring(head.length()));
                    break;
                }
            }
            out.add(p);
        }
        return out;
    }

    /** Rules 1, 2, 3 and 5 for one level. */
    static List<String> level(DropWorld w, Course c, int i, DropRules.Level tier, DropProgram witness,
                              DropProgram twin) {
        List<String> out = new ArrayList<>();
        String name = "level " + (i + 1);
        DropCheck.Read read = DropCheck.read(w, i, tier, DropMarks.ledgeOf(c, i), DropMarks.poolOf(c, i));
        if (read.view() == null) {
            out.add(read.problem());
            return out;
        }
        DropCheck.View v = read.view();
        // the tier's shape
        int drop = v.ledgeTop() - v.surfaceY();
        if (drop != tier.drop()) {
            out.add(name + " drops " + drop + " to its water; a " + tier.name().toLowerCase() + " level drops "
                    + tier.drop());
        }
        int poolSize = (int) Math.rint(2 * DropMarks.halfWidth(v.pool()));
        if (poolSize != tier.pool()) {
            out.add(name + "'s pool is " + poolSize + " a side; a " + tier.name().toLowerCase() + " pool is "
                    + tier.pool());
        }
        if (v.layerRows().size() != tier.layers()) {
            out.add(name + " has " + v.layerRows().size() + " layers; a " + tier.name().toLowerCase() + " level has "
                    + tier.layers());
        }
        if (!v.layerRows().isEmpty()) {
            int lowest = v.layerRows().get(v.layerRows().size() - 1);
            if (lowest - v.surfaceY() < DropperGeometry.CLEAR_AIR) {
                out.add(name + " has an obstacle " + (lowest - v.surfaceY()) + " over its water; at least "
                        + DropperGeometry.CLEAR_AIR + " clear");
            }
        }
        // the pool box is the water: a splash is the first move into it, so none of it may be solid
        double[] box = DropMarks.poolBox(v.pool());
        int dry = 0;
        for (int y = v.surfaceY() - DropperGeometry.POOL_DEPTH; y < v.surfaceY(); y++) {
            for (int x = (int) Math.rint(box[0]); x < (int) Math.rint(box[3]); x++) {
                for (int z = (int) Math.rint(box[2]); z < (int) Math.rint(box[5]); z++) {
                    if (w.get(x, y, z) != DropWorld.WATER) {
                        dry++;
                    }
                }
            }
        }
        if (dry > 0) {
            out.add(name + "'s pool box has " + dry + " block" + (dry == 1 ? " that isn't" : "s that aren't")
                    + " water");
        }
        // 3: enclosure
        out.addAll(enclosure(w, v, name));
        // 1: the witness, and a TWIN's second one
        if (witness == null) {
            out.add(name + " has no witness in the plan's summary");
            return out;
        }
        DropCheck.Flight f = DropCheck.witness(w, v, witness, tier.tube());
        out.addAll(flight(f, name + "'s witness", v, tier, witness, w));
        if (twin != null) {
            out.addAll(flight(DropCheck.witness(w, v, twin, tier.tube()), name + "'s second witness", v, tier, twin,
                    w));
        }
        if (!f.result().splashed() || f.crossings().size() != v.layerRows().size()) {
            return out;
        }
        // 2: the pilots
        List<DropCheck.Miss> misses = DropCheck.pilots(w, v, DropCheck.targets(w, v, f.crossings()), false, null);
        for (int k = 0; k < misses.size() && k < MISS_LINES; k++) {
            DropCheck.Miss m = misses.get(k);
            out.add(name + ": a pilot (" + m.label() + ") " + describe(m.result()));
        }
        if (misses.size() > MISS_LINES) {
            out.add(name + ": " + (misses.size() - MISS_LINES) + " more pilots miss");
        }
        return out;
    }

    /** Rules 1 and 5 for one witness. */
    private static List<String> flight(DropCheck.Flight f, String who, DropCheck.View v, DropRules.Level tier,
                                       DropProgram p, DropWorld w) {
        List<String> out = new ArrayList<>();
        if (!f.result().splashed()) {
            out.add(who + " (" + p.encode() + ") " + describe(f.result()));
            return out;
        }
        if (f.crossings().size() != v.layerRows().size()) {
            out.add(who + " doesn't pass every layer");
            return out;
        }
        for (int j = 0; j < v.layerRows().size(); j++) {
            double[] x = f.crossings().get(j);
            int k = DropCheck.openSquare(w, v, v.layerRows().get(j), x[0], x[1]);
            if (k < tier.minOpening()) {
                out.add(who + " goes through layer " + (j + 1) + " by an opening of " + k + "; at least "
                        + tier.minOpening());
            }
        }
        int changes = p.changes(v.forward());
        if (changes > tier.maxChanges()) {
            out.add(who + " changes its input " + changes + " times; at most " + tier.maxChanges());
        }
        if (tier.changesBefore() > 0 && changes > 0
                && p.lastChange(v.forward()) >= f.crossings().get(tier.changesBefore() - 1)[2] - 1) {
            out.add(who + " changes its input after layer " + tier.changesBefore());
        }
        return out;
    }

    /** Rule 3: glass walls from the floor to the ledge top + 4, a floor, no roof, nothing leaks out. */
    private static List<String> enclosure(DropWorld w, DropCheck.View v, String name) {
        List<String> out = new ArrayList<>();
        int floor = v.surfaceY() - DropperGeometry.POOL_DEPTH - 1;
        int top = v.ledgeTop() + DropperGeometry.WALL_ABOVE - 1;
        int gaps = 0;
        for (int y = floor; y <= top; y++) {
            for (int x = v.x1() - 1; x <= v.x2() + 1; x++) {
                for (int z = v.z1() - 1; z <= v.z2() + 1; z++) {
                    boolean ring = x < v.x1() || x > v.x2() || z < v.z1() || z > v.z2();
                    if (ring && w.get(x, y, z) != DropWorld.WALL) {
                        gaps++;
                    }
                }
            }
        }
        if (gaps > 0) {
            out.add(name + "'s walls have " + gaps + " block" + (gaps == 1 ? "" : "s") + " missing between its floor"
                    + " and 4 above its ledge");
        }
        int holes = 0;
        for (int x = v.x1(); x <= v.x2(); x++) {
            for (int z = v.z1(); z <= v.z2(); z++) {
                if (!w.solid(x, floor, z)) {
                    holes++;
                }
            }
        }
        if (holes > 0) {
            out.add(name + "'s floor has " + holes + " hole" + (holes == 1 ? "" : "s"));
        }
        int roof = 0;
        for (int y = v.ledgeTop(); y <= w.half().maxY(); y++) {
            for (int x = v.x1() - 1; x <= v.x2() + 1; x++) {
                for (int z = v.z1() - 1; z <= v.z2() + 1; z++) {
                    boolean inside = x >= v.x1() && x <= v.x2() && z >= v.z1() && z <= v.z2();
                    if ((inside || y > top) && w.get(x, y, z) != DropWorld.AIR) {
                        roof++;
                    }
                }
            }
        }
        if (roof > 0) {
            out.add(name + " has " + roof + " block" + (roof == 1 ? "" : "s") + " over its ledge: a shaft has no roof");
        }
        // a flood fill from over the ledge, as high as a body can reach, must stay inside
        int reach = v.ledgeTop() + DropperGeometry.WALL_ABOVE - 1;
        int sx = (v.ledge()[0] + v.ledge()[2]) / 2;
        int sz = (v.ledge()[1] + v.ledge()[3]) / 2;
        // the flood may only ever reach the ring round the inside: anything it reaches there is a leak
        int ox = v.x1() - 1;
        int oz = v.z1() - 1;
        int oy = w.half().minY();
        int nx = DropperGeometry.INSIDE + 2;
        int nz = DropperGeometry.INSIDE + 2;
        int ny = reach - oy + 1;
        boolean[] seen = new boolean[nx * ny * nz];
        int[] queue = new int[nx * ny * nz];
        int head = 0;
        int tail = 0;
        if (v.ledgeTop() <= reach) {
            int first = ((v.ledgeTop() - oy) * nx + (sx - ox)) * nz + (sz - oz);
            queue[tail++] = first;
            seen[first] = true;
        }
        boolean leaks = false;
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (head < tail) {
            int at = queue[head++];
            int y = at / (nx * nz) + oy;
            int x = (at % (nx * nz)) / nz + ox;
            int z = at % nz + oz;
            if (x < v.x1() || x > v.x2() || z < v.z1() || z > v.z2()) {
                leaks = true;
                break;
            }
            for (int[] d : steps) {
                int cx = x + d[0];
                int cy = y + d[1];
                int cz = z + d[2];
                if (cy > reach || cy < oy || w.solid(cx, cy, cz)) {
                    continue;
                }
                int c = ((cy - oy) * nx + (cx - ox)) * nz + (cz - oz);
                if (!seen[c]) {
                    seen[c] = true;
                    queue[tail++] = c;
                }
            }
        }
        if (leaks) {
            out.add(name + " isn't closed: from its ledge a player could get out of its shaft");
        }
        return out;
    }

    /** Rule 9: one sign a level, over its ledge, facing in, "LEVEL i of n". */
    private static List<String> signProblems(Plan plan, Course c, int n) {
        List<String> out = new ArrayList<>();
        Box half = plan.half();
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z())) {
                out.add("a sign at " + s.x() + "," + s.y() + "," + s.z() + " is outside the half");
            }
            if (!Palette.id(s.blockData()).equals(Palette.WALL_SIGN)) {
                out.add("a sign at " + s.x() + "," + s.y() + "," + s.z() + " isn't on a wall: " + s.blockData());
            }
            out.addAll(GenCopy.signProblems(s.lines()));
        }
        if (plan.signs().size() != n) {
            out.add("a dropper has one sign a level: " + plan.signs().size() + " for " + n + " levels");
        }
        for (int i = 0; i < n; i++) {
            Course.Mark ledge = DropMarks.ledgeOf(c, i);
            List<String> want = DropperPlanner.signLines(i + 1, n);
            boolean found = false;
            for (SignText s : plan.signs()) {
                double dx = ledge.x() - (s.x() + 0.5);
                double dz = ledge.z() - (s.z() + 0.5);
                if (!s.lines().equals(want) || s.y() != (int) Math.rint(ledge.y()) + DropperGeometry.SIGN_ABOVE
                        || Math.abs(dx) + Math.abs(dz) > 1 + 1e-9) {
                    continue;
                }
                // on the wall behind the ledge's middle, facing the ledge
                String facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
                if (s.blockData().equals(Palette.wallSign(facing))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.add("level " + (i + 1) + " has no sign reading LEVEL " + (i + 1) + " of " + n
                        + " on the wall over its ledge");
            }
        }
        return out;
    }

    private static String describe(DropRun.Result r) {
        int[] h = r.hit();
        return switch (r.outcome()) {
            case TOUCHED -> "touches the block at " + h[0] + "," + h[1] + "," + h[2];
            case LANDED -> "lands" + (h == null ? "" : " on the block at " + h[0] + "," + h[1] + "," + h[2]);
            case FELL -> "falls out of the half";
            case TIMEOUT -> "never reaches the water";
            case STOPPED, SPLASH -> "stops";
        };
    }
}
