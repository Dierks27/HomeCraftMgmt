package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Where the generated courses may stand (GEN-SPEC §2.2, §2.4), as pure checks.
 *
 * <p>Every rule here protects something that isn't Fresh Courses': the hand-built courses, the
 * world's spawn, the edge of the world, and the other slots. The generator only ever writes inside
 * its own halves ({@link HalfWriter}), so the halves themselves must be where nothing else is:
 * <ul>
 *   <li><b>At config load</b> ({@link #validate}): each origin on the 16-block grid (rounded down
 *       with a WARN), inside the world's ±29,000,000, its heights within -56..312, a valid tier or
 *       mix, and every half of every slot at least {@value #APART} blocks from every other. A slot
 *       that breaks one is switched off on its own; the others keep working.</li>
 *   <li><b>At start and before every build</b> ({@link #worldProblems}, {@link #handBuiltProblem}):
 *       the world is listed in {@code games.worlds}, the region fits the world's height with 8 to
 *       spare and sits inside its border, the spawn and the safe spot are at least
 *       {@value #CLEARANCE} blocks outside every half, and no hand-built course comes within
 *       {@value #CLEARANCE} blocks of one. A hand-built row using a slot's id is never taken over
 *       ({@link #takenByHand}).</li>
 * </ul>
 * No Bukkit server is needed: the world is described by {@link WorldFacts}, read by the caller.
 */
public final class Regions {

    /** The farthest a region may reach from 0 along x or z. */
    public static final int MAX_XZ = 29_000_000;
    /** The lowest and highest block a region may use (-64..319 with 8 to spare). */
    public static final int MIN_Y = -56;
    public static final int MAX_Y = 312;
    /** Every half of every slot is at least this many blocks from every other. */
    public static final int APART = Slots.HALF_GAP;
    /** Hand-built courses, the spawn and the safe spot stay at least this far outside every half. */
    public static final int CLEARANCE = 16;
    /** Headroom to the world's floor and ceiling, checked at start. */
    public static final int HEADROOM = 8;
    /** A rollover this soon after a restart counts as "at the restart". */
    public static final int RESTART_SLACK_MINUTES = 10;

    private Regions() {
    }

    // ---- geometry -------------------------------------------------------------------------------

    /** Half {@code which} of {@code def}'s region at {@code origin}. */
    public static Box half(Slots.Def def, int[] origin, char which) {
        return def.half(origin[0], origin[1], origin[2], which);
    }

    /** Both halves, A then B. */
    public static List<Box> halves(Slots.Def def, int[] origin) {
        return List.of(half(def, origin, 'A'), half(def, origin, 'B'));
    }

    /** "half A x 4096..4159, y 160..207, z 4096..4159; half B ..." for admins and WARNs. */
    public static String describe(Slots.Def def, int[] origin) {
        return "half A " + half(def, origin, 'A').describe() + "; half B " + half(def, origin, 'B').describe();
    }

    // ---- at config load -----------------------------------------------------------------------

    /**
     * The slots with every problem fixed or switched off (GEN-SPEC §2.4), one WARN each through
     * {@code warn}; {@code path} is {@code games.fresh.slots}. Never throws.
     */
    public static List<SlotConfig> validate(List<SlotConfig> slots, Consumer<String> warn, String path) {
        List<SlotConfig> out = new ArrayList<>();
        for (SlotConfig c : slots) {
            Slots.Def def = c.def();
            if (def == null) {
                continue;
            }
            String key = path + "." + c.id();
            int[] o = c.origin();
            int[] aligned = {Math.floorDiv(o[0], 16) * 16, o[1], Math.floorDiv(o[2], 16) * 16};
            if (aligned[0] != o[0] || aligned[2] != o[2]) {
                warn.accept(key + ".origin [" + o[0] + ", " + o[1] + ", " + o[2] + "] is not on the 16-block grid"
                        + " - using [" + aligned[0] + ", " + aligned[1] + ", " + aligned[2] + "]");
                c = c.withOrigin(aligned);
            }
            if (c.enabled()) {
                String problem = problem(def, c);
                if (problem != null) {
                    warn.accept(key + " " + problem + " - that course is off");
                    c = c.withEnabled(false);
                }
            }
            out.add(c);
        }
        // Every half of every slot apart from every other: a later slot that meets an earlier one
        // is switched off (the earlier one was there first).
        for (int i = 0; i < out.size(); i++) {
            SlotConfig b = out.get(i);
            if (!b.enabled()) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                SlotConfig a = out.get(j);
                if (!a.enabled()) {
                    continue;
                }
                int gap = gap(a, b);
                if (gap < APART) {
                    warn.accept(path + "." + b.id() + " is " + (gap < 0 ? "on top of" : "only " + gap
                            + " blocks from") + " " + a.id() + " (they must be " + APART
                            + " apart) - that course is off");
                    out.set(i, b.withEnabled(false));
                    break;
                }
            }
        }
        return out;
    }

    /** Why a slot's own settings can't be used, or {@code null}. */
    static String problem(Slots.Def def, SlotConfig c) {
        String tier = def.tierProblem(c.tierOrMix());
        if (tier != null) {
            return (def.golf() ? "mix" : "tier") + " '" + c.tierOrMix() + "': " + tier;
        }
        int[] o = c.origin();
        Box region = def.region(o[0], o[1], o[2]);
        if (Math.abs((long) region.minX()) > MAX_XZ || Math.abs((long) region.maxX()) > MAX_XZ
                || Math.abs((long) region.minZ()) > MAX_XZ || Math.abs((long) region.maxZ()) > MAX_XZ) {
            return "reaches past +-" + MAX_XZ + " (" + region.describe() + ")";
        }
        if (region.minY() < MIN_Y || region.maxY() > MAX_Y) {
            return "needs y " + region.minY() + ".." + region.maxY() + ", outside " + MIN_Y + ".." + MAX_Y;
        }
        return null;
    }

    /**
     * Why slot {@code c} is too close to one of {@code others} (checked before a build, since an
     * admin's {@code on} can wake a slot config switched off), or {@code null}.
     */
    public static String apartProblem(SlotConfig c, List<SlotConfig> others) {
        for (SlotConfig o : others) {
            if (o.id().equals(c.id()) || o.def() == null) {
                continue;
            }
            int gap = gap(c, o);
            if (gap < APART) {
                return "it is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from") + " " + o.id()
                        + " (they must be " + APART + " apart)";
            }
        }
        return null;
    }

    /** The smallest gap between any half of {@code a} and any half of {@code b}. */
    static int gap(SlotConfig a, SlotConfig b) {
        int min = Integer.MAX_VALUE;
        for (Box x : halves(a.def(), a.origin())) {
            for (Box y : halves(b.def(), b.origin())) {
                min = Math.min(min, x.gap(y));
            }
        }
        return min;
    }

    /**
     * The one INFO line about the rollover (§2.4), or {@code null}: when restarts are scheduled and
     * the rollover isn't within {@value #RESTART_SLACK_MINUTES} minutes after one, the courses are
     * rebuilt while the server runs (nobody is interrupted, but the owner should know).
     */
    public static String rolloverNote(LocalTime rollover, List<LocalTime> restarts) {
        if (rollover == null || restarts == null || restarts.isEmpty()) {
            return null;
        }
        for (LocalTime r : restarts) {
            long after = Duration.between(r, rollover).toMinutes();
            if (after < 0) {
                after += 24 * 60;
            }
            if (after <= RESTART_SLACK_MINUTES) {
                return null;
            }
        }
        return "courses will be rebuilt while the server is running at " + rollover
                + " (players aren't interrupted)";
    }

    // ---- at start and before every build -------------------------------------------------------

    /**
     * What the world is, as far as a region cares.
     *
     * @param name      its name
     * @param listed    whether it is in {@code games.worlds}
     * @param minHeight its lowest block
     * @param maxHeight one above its highest block
     * @param border    the world border's blocks along x and z ({@code y} ignored), or {@code null}
     * @param spawn     the spawn block {x, y, z}
     * @param safeSpot  {@code games.fresh.safe_spot}, or {@code null}
     */
    public record WorldFacts(String name, boolean listed, int minHeight, int maxHeight, Box border, int[] spawn,
                             double[] safeSpot) {
    }

    /** Why {@code def}'s region at {@code origin} can't be built in {@code w}, in admin words; empty = fine. */
    public static List<String> worldProblems(Slots.Def def, int[] origin, WorldFacts w) {
        List<String> out = new ArrayList<>();
        if (!w.listed()) {
            out.add("the world " + w.name() + " isn't in games.worlds");
        }
        Box region = def.region(origin[0], origin[1], origin[2]);
        if (w.minHeight() + HEADROOM > region.minY() || region.maxY() + 1 > w.maxHeight() - HEADROOM) {
            out.add("it needs y " + region.minY() + ".." + region.maxY() + ", but " + w.name() + " has room for "
                    + (w.minHeight() + HEADROOM) + ".." + (w.maxHeight() - HEADROOM - 1));
        }
        Box b = w.border();
        if (b != null && (region.minX() < b.minX() || region.maxX() > b.maxX() || region.minZ() < b.minZ()
                || region.maxZ() > b.maxZ())) {
            out.add("it reaches past the world border (" + region.describe() + ")");
        }
        if (w.spawn() != null) {
            String near = near(def, origin, w.spawn()[0], w.spawn()[1], w.spawn()[2]);
            if (near != null) {
                out.add("the world's spawn is " + near);
            }
        }
        if (w.safeSpot() != null) {
            double[] s = w.safeSpot();
            String near = near(def, origin, (int) Math.floor(s[0]), (int) Math.floor(s[1]), (int) Math.floor(s[2]));
            if (near != null) {
                out.add("games.fresh.safe_spot is " + near);
            }
        }
        return out;
    }

    /** "inside half A" / "only 3 blocks from half B", or {@code null} when at least {@value #CLEARANCE} away. */
    private static String near(Slots.Def def, int[] origin, int x, int y, int z) {
        Box point = new Box(x, y, z, x, y, z);
        for (char which : new char[]{'A', 'B'}) {
            int gap = point.gap(half(def, origin, which));
            if (gap < CLEARANCE) {
                return gap < 0 ? "inside half " + which : "only " + gap + " blocks from half " + which
                        + " (it must be " + CLEARANCE + " away)";
            }
        }
        return null;
    }

    /**
     * One hand-built course's footprint.
     *
     * @param world    its world
     * @param box      its blocks: each point ± its radius, or a golf hole's bounds
     * @param courseId the course
     */
    public record Area(String world, Box box, String courseId) {
    }

    /**
     * The footprints of every hand-built course among {@code rows}: each time trial's start,
     * checkpoints and finish (each ± its radius), each golf hole's bounds. A row with a
     * {@code gen:} block is Fresh Courses' own and left out; one that can't be read is left out too
     * (its owner is told by its own game).
     */
    public static List<Area> handBuilt(List<GamesDao.CourseRow> rows) {
        List<Area> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (GamesDao.CourseRow row : rows) {
            if (row == null || hasGen(row.data())) {
                continue;
            }
            try {
                if (Slots.GAME_GOLF.equals(row.game())) {
                    GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                    for (GolfCourse.Hole h : g.holes()) {
                        if (h.bounded()) {
                            out.add(new Area(g.world(), Box.of(h.corner1().x(), h.corner1().y(), h.corner1().z(),
                                    h.corner2().x(), h.corner2().y(), h.corner2().z()), row.id()));
                        } else if (h.tee() != null) {
                            out.add(new Area(g.world(), sphere(h.tee().x(), h.tee().y(), h.tee().z(), 0), row.id()));
                        }
                    }
                } else if (Slots.GAME_TRIALS.equals(row.game())) {
                    Course c = CourseCodec.decode(row.id(), row.data()).course();
                    if (c == null) {
                        continue;
                    }
                    if (c.start() != null) {
                        out.add(new Area(c.world(), sphere(c.start().x(), c.start().y(), c.start().z(), 0), row.id()));
                    }
                    for (Course.Mark m : c.targets()) {
                        out.add(new Area(c.world(), sphere(m.x(), m.y(), m.z(), m.radius()), row.id()));
                    }
                }
            } catch (RuntimeException e) {
                // unreadable: its own game reports it
            }
        }
        return out;
    }

    /**
     * Why {@code def}'s region at {@code origin} in {@code world} is too close to a hand-built
     * course, or {@code null} when every one is at least {@value #CLEARANCE} blocks away.
     */
    public static String handBuiltProblem(Slots.Def def, int[] origin, String world, List<Area> areas) {
        for (Area a : areas) {
            if (a.world() == null || !a.world().equalsIgnoreCase(world)) {
                continue;
            }
            for (char which : new char[]{'A', 'B'}) {
                int gap = a.box().gap(half(def, origin, which));
                if (gap < CLEARANCE) {
                    return "the hand-built course " + a.courseId() + " is " + (gap < 0 ? "inside" : "only " + gap
                            + " blocks from") + " half " + which + " (" + a.box().describe() + "; it must be "
                            + CLEARANCE + " away)";
                }
            }
        }
        return null;
    }

    /**
     * Why the course row already using a slot's id can't be taken over, or {@code null} when there
     * is none or it is Fresh Courses' own (its game, with a {@code gen:} block).
     */
    public static String takenByHand(Slots.Def def, GamesDao.CourseRow row) {
        if (row == null) {
            return null;
        }
        if (!def.game().equals(row.game()) || !hasGen(row.data())) {
            return "course " + def.id() + " exists and wasn't made by Fresh Courses";
        }
        return null;
    }

    /** Whether a course row's YAML has a {@code gen:} block at all (readable or not). */
    public static boolean hasGen(String data) {
        if (data == null || data.isBlank() || !data.contains("gen")) {
            return false;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException | RuntimeException e) {
            return false;
        }
        return y.isSet("gen");
    }

    // ---- claims ---------------------------------------------------------------------------------

    /** What {@code gen.<slot>.claim} holds: "world,x,y,z,sx,sy,sz" (the origin and one half's size). */
    public static String claim(Slots.Def def, String world, int[] origin) {
        return world.toLowerCase(Locale.ROOT) + "," + origin[0] + "," + origin[1] + "," + origin[2] + ","
                + def.sizeX() + "," + def.sizeY() + "," + def.sizeZ();
    }

    /** The origin a claim was made at, or {@code null} when it can't be read. */
    public static int[] claimOrigin(String claim) {
        if (claim == null) {
            return null;
        }
        String[] p = claim.split(",");
        if (p.length != 7) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()),
                    Integer.parseInt(p[3].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Box sphere(double x, double y, double z, double r) {
        double rr = Math.max(0, r);
        return Box.of((int) Math.floor(x - rr), (int) Math.floor(y - rr), (int) Math.floor(z - rr),
                (int) Math.floor(x + rr), (int) Math.floor(y + rr), (int) Math.floor(z + rr));
    }
}
