package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the Falling Floors box may be used where it is (EVENTS-DROPPER-SPEC §B.3.2, S3): the
 * checks Fresh Courses makes for its own areas, applied to the arena's one box, before a single
 * block of it is written.
 *
 * <p>Why every Fresh Courses slot counts, switched on or not: an admin can switch a slot on at any
 * time ({@code /hcm games gen}), and a slot once claimed keeps its blocks. So the box keeps
 * {@value Regions#APART} blocks from every half there could ever be (and the kept courses'
 * area), {@value Regions#CLEARANCE} from every hand-built course, stays inside the world's heights
 * (with {@value Regions#HEADROOM} to spare) and border, and keeps {@value Regions#CLEARANCE} from the
 * world's spawn and the safe spot, so moving someone out of the way never lands them in it.
 *
 * <p>Pure: everything comes in as values, so the rules are tested without a server.
 */
public final class ArenaRegions {

    /** What an admin reads the box as. */
    public static final String NAME = "falling_floors";

    private ArenaRegions() {
    }

    /**
     * Why the box can't be used, in admin words; empty when it can.
     *
     * @param settings  Fresh Courses' settings (its slots, Classics slots and keep area)
     * @param handBuilt the hand-built courses' footprints, or {@code null} when they can't be read now
     *                  (then that check is skipped: the first-use claim still refuses a box with
     *                  anyone's blocks in it)
     * @param world     the world's facts; {@code null} skips them
     */
    public static List<String> problems(Box box, DailySettings settings, List<Regions.Area> handBuilt,
                                        Regions.WorldFacts world) {
        List<String> out = new ArrayList<>();
        if (box == null) {
            out.add("there is no box");
            return out;
        }
        Regions.Extra extra = new Regions.Extra(NAME, box);
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        KeepArea keep = null;
        if (settings != null) {
            for (DailySettings.SlotConfig c : settings.slots()) {
                slots.add(c.withEnabled(true));
            }
            for (DailySettings.SlotConfig c : settings.archive().classics()) {
                slots.add(c.withEnabled(true));
            }
            keep = settings.archive().keepProblem() == null ? settings.archive().keep() : null;
        }
        String near = Regions.extraProblem(extra, slots, keep);
        if (near != null) {
            out.add(near);
        }
        if (handBuilt != null && world != null) {
            String built = Regions.extraHandBuiltProblem(extra, world.name(), handBuilt);
            if (built != null) {
                out.add(built);
            }
        }
        if (world != null) {
            out.addAll(worldProblems(box, world));
        }
        return out;
    }

    /** The world's side: listed, heights, border, spawn and safe spot. */
    static List<String> worldProblems(Box box, Regions.WorldFacts w) {
        List<String> out = new ArrayList<>();
        if (!w.listed()) {
            out.add("the world " + w.name() + " isn't in games.worlds");
        }
        if (w.minHeight() + Regions.HEADROOM > box.minY() || box.maxY() + 1 > w.maxHeight() - Regions.HEADROOM) {
            out.add(NAME + " needs y " + box.minY() + ".." + box.maxY() + ", but " + w.name() + " has room for "
                    + (w.minHeight() + Regions.HEADROOM) + ".." + (w.maxHeight() - Regions.HEADROOM - 1));
        }
        Box b = w.border();
        if (b != null && (box.minX() < b.minX() || box.maxX() > b.maxX() || box.minZ() < b.minZ()
                || box.maxZ() > b.maxZ())) {
            out.add(NAME + " reaches past the world border (" + box.describe() + ")");
        }
        if (w.spawn() != null) {
            String near = near(box, w.spawn()[0], w.spawn()[1], w.spawn()[2]);
            if (near != null) {
                out.add("the world's spawn is " + near);
            }
        }
        if (w.safeSpot() != null) {
            double[] s = w.safeSpot();
            String near = near(box, (int) Math.floor(s[0]), (int) Math.floor(s[1]), (int) Math.floor(s[2]));
            if (near != null) {
                out.add("games.fresh.safe_spot is " + near);
            }
        }
        return out;
    }

    private static String near(Box box, int x, int y, int z) {
        int gap = new Box(x, y, z, x, y, z).gap(box);
        if (gap >= Regions.CLEARANCE) {
            return null;
        }
        return gap < 0 ? "inside " + NAME : "only " + gap + " blocks from " + NAME + " (it must be " + Regions.CLEARANCE
                + " away)";
    }
}
