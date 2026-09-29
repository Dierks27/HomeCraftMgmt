package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a Falling Floors arena before a single block of it is set (EVENTS-DROPPER-SPEC §B.3.2),
 * without trusting the planner: everything is read back from the plan's own blocks.
 *
 * <p>Why a second opinion: the planner is where a mistake would be made, so it can't be the one to
 * say there is none. Every rule here protects a child or the world: nothing outside the box; no
 * block a wall top could make into a safe spot; floors big enough to play on and in no more than
 * two pieces, none of them a lone block; spawns on the floor with room above and apart from each
 * other; a gallery nobody can fall out of or jump in from; only allowed blocks (glass, never water);
 * and not so many blocks that a reset would take long.
 *
 * <p>Pure. Each problem starts with its rule's name ({@code "area: ..."}), so a test can build one
 * bad plan per rule and see that exactly that rule fires.
 */
public final class ArenaValidator {

    /** The rules, in the order they are checked. */
    public static final List<String> RULES = List.of("box", "ops", "palette", "floors", "walls", "footprint", "area",
            "pieces", "spawns", "gallery", "out");
    /** The fewest spawns a top floor may have (the shipped rounds hold 12). */
    public static final int MIN_SPAWNS = 12;
    /** Air a spawn needs above it: a player is 1.8 tall. */
    public static final int SPAWN_HEADROOM = 3;

    private ArenaValidator() {
    }

    /** Every problem with {@code site}; empty when it may be built and played. Never throws. */
    public static List<String> problems(ArenaSite site) {
        List<String> out = new ArrayList<>();
        if (site == null || site.plan() == null || site.layout() == null) {
            out.add("box: there is no plan");
            return out;
        }
        try {
            check(site, out);
        } catch (RuntimeException e) {
            out.add("box: the plan can't be read (" + e + ")");
        }
        return out;
    }

    private static void check(ArenaSite site, List<String> out) {
        Plan plan = site.plan();
        Box box = plan.half();
        if (box == null || box.sizeX() != FallingFloorsSettings.SIZE_X || box.sizeY() != FallingFloorsSettings.SIZE_Y
                || box.sizeZ() != FallingFloorsSettings.SIZE_Z) {
            out.add("box: the box must be " + FallingFloorsSettings.SIZE_X + " x " + FallingFloorsSettings.SIZE_Y + " x "
                    + FallingFloorsSettings.SIZE_Z + ", not " + (box == null ? "missing" : box.describe()));
            return;
        }
        for (BlockOp op : plan.ops()) {
            if (!box.contains(op.x(), op.y(), op.z())) {
                out.add("box: a block at " + at(op) + " is outside the box (" + box.describe() + ")");
                break;
            }
        }
        if (plan.ops().size() > ArenaPlanner.MAX_OPS) {
            out.add("ops: " + plan.ops().size() + " blocks, more than " + ArenaPlanner.MAX_OPS);
        }
        List<String> badBlocks = Palette.problems(plan.palette());
        for (String p : plan.palette()) {
            if (Palette.poolWater(p) || Palette.id(p).contains("water") || Palette.id(p).contains("lava")) {
                badBlocks.add(p);
            }
        }
        if (!badBlocks.isEmpty()) {
            out.add("palette: blocks a plan may not use: " + badBlocks);
        }
        for (BlockOp op : plan.ops()) {
            if (op.state() >= plan.palette().size()) {
                out.add("palette: a block at " + at(op) + " names palette entry " + op.state() + " of "
                        + plan.palette().size());
                return;
            }
        }

        List<Integer> floorYs = new ArrayList<>();
        for (int off : ArenaPlanner.FLOOR_OFFSETS) {
            floorYs.add(box.minY() + off);
        }
        int galleryY = box.minY() + ArenaPlanner.GALLERY_OFFSET;
        Map<Integer, List<Cell>> floors = new HashMap<>();
        for (int y : floorYs) {
            floors.put(y, new ArrayList<>());
        }
        Set<Long> occupied = new HashSet<>();
        Set<Long> walk = new HashSet<>();
        Set<Long> rails = new HashSet<>();
        String wall = null;
        String wrongColour = null;
        for (BlockOp op : plan.ops()) {
            occupied.add(key(op.x(), op.y(), op.z()));
            String block = Palette.id(plan.blockOf(op));
            List<Cell> floor = floors.get(op.y());
            if (floor != null) {
                int f = floorYs.indexOf(op.y());
                if (!block.equals(Palette.stainedGlass(ArenaPlanner.FLOOR_COLOURS.get(f))) && wrongColour == null) {
                    wrongColour = "floors: the block at " + at(op) + " is " + block + ", but floor " + (f + 1)
                            + " is " + ArenaPlanner.FLOOR_COLOURS.get(f).replace('_', ' ') + " stained glass";
                }
                floor.add(new Cell(op.x(), op.z()));
                continue;
            }
            int depth = ArenaPlanner.ringDepth(box, op.x(), op.z());
            if (op.y() == galleryY && depth < ArenaPlanner.GALLERY_WIDTH && block.equals(ArenaPlanner.WALK)) {
                walk.add(key(op.x(), 0, op.z()));
                continue;
            }
            if (op.y() > galleryY && op.y() <= galleryY + ArenaPlanner.RAIL_HEIGHT
                    && (depth == 0 || depth == ArenaPlanner.GALLERY_WIDTH - 1) && block.equals(ArenaPlanner.RAIL)) {
                rails.add(key(op.x(), op.y(), op.z()));
                continue;
            }
            if (wall == null) {
                wall = "walls: " + block + " at " + at(op) + " is neither a floor nor the gallery (a wall top would be"
                        + " a safe spot)";
            }
        }
        if (wrongColour != null) {
            out.add(wrongColour);
        }
        FloorLayout layout = site.layout();
        List<Integer> layoutYs = new ArrayList<>();
        for (FloorLayout.Layer l : layout.layers()) {
            layoutYs.add(l.y());
        }
        if (!layoutYs.equals(floorYs)) {
            out.add("floors: the floors are at y " + layoutYs + ", not " + floorYs + " (8 apart from the top)");
        } else {
            for (int i = 0; i < floorYs.size(); i++) {
                if (!new TreeSet<>(floors.get(floorYs.get(i))).equals(new TreeSet<>(layout.layer(i).cells()))) {
                    out.add("floors: floor " + (i + 1) + "'s cells are not the plan's own blocks");
                    break;
                }
            }
        }
        if (wall != null) {
            out.add(wall);
        }

        int fx0 = box.minX() + ArenaPlanner.FOOTPRINT_OFFSET;
        int fz0 = box.minZ() + ArenaPlanner.FOOTPRINT_OFFSET;
        Box footprint = new Box(fx0, box.minY(), fz0, fx0 + ArenaPlanner.FOOTPRINT - 1, box.maxY(),
                fz0 + ArenaPlanner.FOOTPRINT - 1);
        outer:
        for (int y : floorYs) {
            for (Cell c : floors.get(y)) {
                if (!footprint.contains(c.x(), y, c.z())) {
                    out.add("footprint: the floor block at " + c.x() + "," + y + "," + c.z() + " is outside the "
                            + ArenaPlanner.FOOTPRINT + " x " + ArenaPlanner.FOOTPRINT + " footprint");
                    break outer;
                }
            }
        }
        for (int i = 0; i < floorYs.size(); i++) {
            int n = floors.get(floorYs.get(i)).size();
            if (n < ArenaPlanner.MIN_CELLS || n > ArenaPlanner.MAX_CELLS) {
                out.add("area: floor " + (i + 1) + " has " + n + " blocks; it needs " + ArenaPlanner.MIN_CELLS + "-"
                        + ArenaPlanner.MAX_CELLS + " (" + ArenaPlanner.TARGET_CELLS + ", give or take 10%)");
            }
        }
        for (int i = 0; i < floorYs.size(); i++) {
            List<Integer> pieces = pieces(floors.get(floorYs.get(i)));
            if (pieces.size() > ArenaPlanner.MAX_PIECES) {
                out.add("pieces: floor " + (i + 1) + " is in " + pieces.size() + " pieces; at most "
                        + ArenaPlanner.MAX_PIECES + " (a ring and its island)");
            } else if (!pieces.isEmpty() && pieces.get(pieces.size() - 1) < ArenaPlanner.MIN_PIECE) {
                out.add("pieces: floor " + (i + 1) + " has a piece of " + pieces.get(pieces.size() - 1)
                        + " blocks; none may be under " + ArenaPlanner.MIN_PIECE);
            }
        }

        spawns(site, floorYs, floors, occupied, out);
        gallery(site, box, galleryY, walk, rails, floors, out);

        int outY = layout.outY();
        int bottom = floorYs.get(floorYs.size() - 1);
        if (outY != box.minY() + ArenaPlanner.OUT_OFFSET || outY >= bottom || outY <= box.minY()) {
            out.add("out: out must be y " + (box.minY() + ArenaPlanner.OUT_OFFSET) + ", below the bottom floor (y "
                    + bottom + ") and inside the box, not y " + outY);
        }
    }

    private static void spawns(ArenaSite site, List<Integer> floorYs, Map<Integer, List<Cell>> floors,
                               Set<Long> occupied, List<String> out) {
        List<Cell> spawns = site.layout().spawns();
        int top = floorYs.get(0);
        Set<Cell> topCells = new HashSet<>(floors.get(top));
        if (spawns.size() < MIN_SPAWNS) {
            out.add("spawns: only " + spawns.size() + " spawns; the top floor needs at least " + MIN_SPAWNS);
            return;
        }
        for (Cell s : spawns) {
            if (!topCells.contains(s)) {
                out.add("spawns: the spawn at " + s.x() + "," + s.z() + " is not on the top floor");
                return;
            }
            for (int h = 1; h <= SPAWN_HEADROOM; h++) {
                if (occupied.contains(key(s.x(), top + h, s.z()))) {
                    out.add("spawns: the spawn at " + s.x() + "," + s.z() + " has a block " + h + " above it");
                    return;
                }
            }
        }
        for (int i = 0; i < spawns.size(); i++) {
            for (int j = i + 1; j < spawns.size(); j++) {
                double dx = spawns.get(i).x() - spawns.get(j).x();
                double dz = spawns.get(i).z() - spawns.get(j).z();
                if (dx * dx + dz * dz < ArenaPlanner.SPAWN_APART * ArenaPlanner.SPAWN_APART - 1e-9) {
                    out.add("spawns: the spawns at " + spawns.get(i).x() + "," + spawns.get(i).z() + " and "
                            + spawns.get(j).x() + "," + spawns.get(j).z() + " are nearer than "
                            + (int) ArenaPlanner.SPAWN_APART);
                    return;
                }
            }
        }
    }

    private static void gallery(ArenaSite site, Box box, int galleryY, Set<Long> walk, Set<Long> rails,
                                Map<Integer, List<Cell>> floors, List<String> out) {
        if (site.galleryY() != galleryY) {
            out.add("gallery: the gallery is at y " + site.galleryY() + ", not " + galleryY);
            return;
        }
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                int depth = ArenaPlanner.ringDepth(box, x, z);
                if (depth >= ArenaPlanner.GALLERY_WIDTH) {
                    continue;
                }
                if (!walk.contains(key(x, 0, z))) {
                    out.add("gallery: the walk has a hole at " + x + "," + galleryY + "," + z);
                    return;
                }
                if (depth == 0 || depth == ArenaPlanner.GALLERY_WIDTH - 1) {
                    for (int h = 1; h <= ArenaPlanner.RAIL_HEIGHT; h++) {
                        if (!rails.contains(key(x, galleryY + h, z))) {
                            out.add("gallery: the " + (depth == 0 ? "outer" : "inner") + " rail has a gap at " + x + ","
                                    + (galleryY + h) + "," + z);
                            return;
                        }
                    }
                }
            }
        }
        int topY = site.layout().topY(0);
        if (galleryY + 1 - topY < 5) {
            out.add("gallery: the gallery is only " + (galleryY + 1 - topY) + " above the top floor");
            return;
        }
        for (List<Cell> cells : floors.values()) {
            for (Cell c : cells) {
                int depth = ArenaPlanner.ringDepth(box, c.x(), c.z());
                if (depth < ArenaPlanner.GALLERY_WIDTH + ArenaPlanner.GALLERY_GAP) {
                    out.add("gallery: the floor block at " + c.x() + "," + c.z() + " is less than "
                            + ArenaPlanner.GALLERY_GAP + " from the gallery");
                    return;
                }
            }
        }
        if (site.gallerySpots().isEmpty()) {
            out.add("gallery: there is nowhere to put players in the gallery");
            return;
        }
        for (ArenaSite.Spot s : site.gallerySpots()) {
            if (!site.inGallery(s.x(), s.y(), s.z()) || s.y() != galleryY + 1) {
                out.add("gallery: the spot " + s.x() + "," + s.y() + "," + s.z() + " is not on the walk");
                return;
            }
            int depth = ArenaPlanner.ringDepth(box, (int) Math.floor(s.x()), (int) Math.floor(s.z()));
            if (depth == 0 || depth >= ArenaPlanner.GALLERY_WIDTH - 1) {
                out.add("gallery: the spot " + s.x() + "," + s.z() + " is in a rail");
                return;
            }
        }
    }

    /** The sizes of a floor's 4-connected pieces, largest first. */
    static List<Integer> pieces(List<Cell> cells) {
        Set<Cell> left = new HashSet<>(cells);
        List<Integer> sizes = new ArrayList<>();
        for (Cell start : new TreeSet<>(cells)) {
            if (!left.remove(start)) {
                continue;
            }
            int n = 0;
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Cell c = queue.poll();
                n++;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Cell next = new Cell(c.x() + d[0], c.z() + d[1]);
                    if (left.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            sizes.add(n);
        }
        sizes.sort((a, b) -> Integer.compare(b, a));
        return sizes;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static String at(BlockOp op) {
        return op.x() + "," + op.y() + "," + op.z();
    }
}
