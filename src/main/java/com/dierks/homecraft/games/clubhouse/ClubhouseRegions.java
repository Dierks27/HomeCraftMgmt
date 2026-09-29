package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whether the Clubhouse box may be used where it is (CLUBHOUSE-SPEC §1): the checks Fresh Courses
 * makes for its own areas, applied to the Clubhouse's one box, before a single block of it is
 * written. The same rules as the Falling Floors arena's box ({@code ArenaRegions}), through the same
 * API ({@link Regions.Extra}, {@link Regions#extraProblems}): at least {@value Regions#APART} blocks
 * from every Fresh Courses half there could ever be (every slot, switched on or not, and the
 * Classics), from the kept courses' area and from the arena's box; {@value Regions#CLEARANCE} from
 * every hand-built course; inside the world's heights (with {@value Regions#HEADROOM} to spare) and
 * border; and {@value Regions#CLEARANCE} from the world's spawn and the safe spot.
 *
 * <p><b>For Fresh Courses' own checks</b> (the other way round): {@link #extras} is the Clubhouse's
 * box as an extra, exactly as the arena exposes its own, so Fresh Courses, the keep area and
 * {@code /hcm games check} keep their distance from it by adding it to their list of extras.
 *
 * <p>Pure: everything comes in as values, so the rules are tested without a server.
 */
public final class ClubhouseRegions {

    /** What an admin reads the box as. */
    public static final String NAME = "clubhouse";

    private ClubhouseRegions() {
    }

    /**
     * The Clubhouse's box as an extra for Fresh Courses' checks (as configured, on or off, since its
     * blocks may stand). None when the settings can't be read.
     */
    public static List<Regions.Extra> extras(ClubhouseSettings s) {
        try {
            return s == null ? List.of() : List.of(new Regions.Extra(NAME, s.box()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Why the box can't be used, in admin words; empty when it can.
     *
     * @param settings  Fresh Courses' settings (its slots, Classics slots and keep area), or {@code null}
     * @param others    the other extra boxes it keeps apart from (the Falling Floors arena's)
     * @param handBuilt the hand-built courses' footprints, or {@code null} when they can't be read now
     * @param world     the world's facts; {@code null} skips them
     */
    public static List<String> problems(Box box, DailySettings settings, List<Regions.Extra> others,
                                        List<Regions.Area> handBuilt, Regions.WorldFacts world) {
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
        List<Regions.Extra> all = new ArrayList<>();
        for (Regions.Extra o : others == null ? List.<Regions.Extra>of() : others) {
            if (o != null && !o.name().equals(NAME)) {
                all.add(new Regions.Extra(o.name(), o.box()));
            }
        }
        all.add(extra);
        Map<String, String> near = Regions.extraProblems(all, slots, keep);
        if (near.containsKey(NAME)) {
            out.add(near.get(NAME));
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
        return gap < 0 ? "inside " + NAME : "only " + gap + " blocks from " + NAME + " (it must be "
                + Regions.CLEARANCE + " away)";
    }
}
