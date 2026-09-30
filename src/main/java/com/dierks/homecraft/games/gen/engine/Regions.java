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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>Named extra boxes</b> ({@link Extra}, EVENTS-DROPPER-SPEC §B.3.2): an area that isn't a Fresh
 * Courses slot but shares their part of the world (the Falling Floors arena) is checked by the same
 * rules the other way round: at least {@value #APART} blocks from every half and the keep area, at
 * least {@value #CLEARANCE} from every hand-built course ({@link #extraProblems},
 * {@link #extraHandBuiltProblem}). The slots were there first, so an extra box that is too close is
 * the one that stays shut.
 *
 * <p><b>Apart is not out of sight.</b> {@value #APART} blocks is the build rule: two areas closer
 * than that are never both built. It used to be the gap between a slot's own two halves too; that gap
 * is now each slot's own ({@code half_gap}, {@link SlotConfig#halfGap}), and being out of each other's
 * sight is {@link com.dierks.homecraft.games.gen.api.Sight}'s question, which {@code /hcm games check}
 * WARNs about and never refuses.
 *
 * <p>No Bukkit server is needed: the world is described by {@link WorldFacts}, read by the caller.
 */
public final class Regions {

    /** The farthest a region may reach from 0 along x or z. */
    public static final int MAX_XZ = 29_000_000;
    /** The lowest and highest block a region may use (-64..319 with 8 to spare). */
    public static final int MIN_Y = -56;
    public static final int MAX_Y = 312;
    /**
     * Every half of every slot is at least this many blocks from every other, and from the arena, the
     * Clubhouse and the keep area: the rule to be built at all (0.35's gap between halves, kept as the
     * rule when the gap became each slot's own). Sight is {@code Sight}'s, a WARN.
     */
    public static final int APART = 32;
    /** Hand-built courses, the spawn and the safe spot stay at least this far outside every half. */
    public static final int CLEARANCE = 16;
    /** Headroom to the world's floor and ceiling, checked at start. */
    public static final int HEADROOM = 8;
    /** A rollover this soon after a restart counts as "at the restart". */
    public static final int RESTART_SLACK_MINUTES = 10;

    private Regions() {
    }

    // ---- geometry -------------------------------------------------------------------------------

    /** Half {@code which} of {@code def}'s region at {@code origin}, its halves {@code gap} blocks apart. */
    public static Box half(Slots.Def def, int[] origin, int gap, char which) {
        return def.half(origin[0], origin[1], origin[2], which, gap);
    }

    /** Both halves, A then B. */
    public static List<Box> halves(Slots.Def def, int[] origin, int gap) {
        return List.of(half(def, origin, gap, 'A'), half(def, origin, gap, 'B'));
    }

    /** A slot's two halves as its config places them. */
    public static List<Box> halves(SlotConfig c) {
        return halves(c.def(), c.origin(), c.halfGap());
    }

    /** "half A x 4096..4159, y 160..207, z 4096..4159; half B ..." for admins and WARNs. */
    public static String describe(Slots.Def def, int[] origin, int gap) {
        return "half A " + half(def, origin, gap, 'A').describe() + "; half B "
                + half(def, origin, gap, 'B').describe();
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

    /** Why a slot's own settings can't be used, or {@code null}. Each half is checked, not the air between them. */
    static String problem(Slots.Def def, SlotConfig c) {
        String tier = def.tierProblem(c.tierOrMix());
        if (tier != null) {
            return (def.mixed() ? "mix" : "tier") + " '" + c.tierOrMix() + "': " + tier;
        }
        for (char which : new char[]{'A', 'B'}) {
            Box half = half(def, c.origin(), c.halfGap(), which);
            if (Math.abs((long) half.minX()) > MAX_XZ || Math.abs((long) half.maxX()) > MAX_XZ
                    || Math.abs((long) half.minZ()) > MAX_XZ || Math.abs((long) half.maxZ()) > MAX_XZ) {
                return "reaches past +-" + MAX_XZ + " (half " + which + " " + half.describe() + ")";
            }
            if (half.minY() < MIN_Y || half.maxY() > MAX_Y) {
                return "needs y " + half.minY() + ".." + half.maxY() + ", outside " + MIN_Y + ".." + MAX_Y;
            }
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

    /** The smallest gap between any half of {@code a} and any half of {@code b}, each at its own gap. */
    static int gap(SlotConfig a, SlotConfig b) {
        int min = Integer.MAX_VALUE;
        for (Box x : halves(a)) {
            for (Box y : halves(b)) {
                min = Math.min(min, x.gap(y));
            }
        }
        return min;
    }

    // ---- named extra boxes (EVENTS-DROPPER-SPEC §B.3.2) ---------------------------------------------

    /**
     * An area that isn't a Fresh Courses slot but must keep clear of them, and they of it: the
     * Falling Floors arena box.
     *
     * @param name what an admin reads ({@code falling_floors})
     * @param box  its blocks
     */
    public record Extra(String name, Box box) {

        public Extra {
            if (name == null || name.isBlank() || box == null) {
                throw new IllegalArgumentException("an extra box needs a name and a box");
            }
        }
    }

    /**
     * Why {@code extra} can't be used, or {@code null}: it must stay inside +-{@value #MAX_XZ} and y
     * {@value #MIN_Y}..{@value #MAX_Y}, and be at least {@value #APART} blocks from every half of every
     * switched-on slot in {@code slots} (Classics slots included, when the caller passes them) and from
     * the keep area ({@code keep}, or {@code null} for none).
     */
    public static String extraProblem(Extra extra, List<SlotConfig> slots, KeepArea keep) {
        Box b = extra.box();
        if (Math.abs((long) b.minX()) > MAX_XZ || Math.abs((long) b.maxX()) > MAX_XZ
                || Math.abs((long) b.minZ()) > MAX_XZ || Math.abs((long) b.maxZ()) > MAX_XZ) {
            return extra.name() + " reaches past +-" + MAX_XZ + " (" + b.describe() + ")";
        }
        if (b.minY() < MIN_Y || b.maxY() > MAX_Y) {
            return extra.name() + " needs y " + b.minY() + ".." + b.maxY() + ", outside " + MIN_Y + ".." + MAX_Y;
        }
        for (SlotConfig c : slots == null ? List.<SlotConfig>of() : slots) {
            if (c == null || !c.enabled() || c.def() == null) {
                continue;
            }
            for (char which : new char[]{'A', 'B'}) {
                int gap = b.gap(half(c.def(), c.origin(), c.halfGap(), which));
                if (gap < APART) {
                    return extra.name() + " is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from") + " "
                            + c.id() + "'s half " + which + " (they must be " + APART + " apart)";
                }
            }
        }
        if (keep != null && keep.maxPlots() > 0) {
            int gap = b.gap(keep.area());
            if (gap < APART) {
                return extra.name() + " is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from")
                        + " the kept courses' area (" + keep.describe() + "; they must be " + APART + " apart)";
            }
        }
        return null;
    }

    /**
     * Why {@code def}'s region at {@code origin} (its halves {@code gap} apart) crowds one of
     * {@code extras} (Fresh Courses' side of {@link #extraProblem}: the same rule, asked for one slot),
     * or {@code null} when it keeps {@value #APART} blocks from every one. Whether the slot is switched
     * on doesn't matter.
     */
    public static String extrasProblem(Slots.Def def, int[] origin, int gap, List<Extra> extras) {
        if (def == null || origin == null) {
            return null;
        }
        List<SlotConfig> one = List.of(SlotConfig.shipped(def).withOrigin(origin).withHalfGap(gap).withEnabled(true));
        for (Extra e : extras == null ? List.<Extra>of() : extras) {
            String p = e == null ? null : extraProblem(e, one, null);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * Why the keep area crowds one of {@code extras} ({@link #extraProblem} for the keep area), or
     * {@code null}: keeping is refused while it does.
     */
    public static String keepExtrasProblem(KeepArea keep, List<Extra> extras) {
        if (keep == null) {
            return null;
        }
        for (Extra e : extras == null ? List.<Extra>of() : extras) {
            String p = e == null ? null : extraProblem(e, List.of(), keep);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * {@link #extraProblem} for several boxes, which must also be {@value #APART} apart from each
     * other: each box's problem by its name (in order), only for the boxes that have one.
     */
    public static Map<String, String> extraProblems(List<Extra> extras, List<SlotConfig> slots,
                                                               KeepArea keep) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Extra> seen = new ArrayList<>();
        for (Extra e : extras == null ? List.<Extra>of() : extras) {
            if (e == null) {
                continue;
            }
            String problem = extraProblem(e, slots, keep);
            for (int i = 0; problem == null && i < seen.size(); i++) {
                int gap = e.box().gap(seen.get(i).box());
                if (gap < APART) {
                    problem = e.name() + " is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from") + " "
                            + seen.get(i).name() + " (they must be " + APART + " apart)";
                }
            }
            if (problem != null) {
                out.put(e.name(), problem);
            } else {
                seen.add(e);
            }
        }
        return out;
    }

    /**
     * Why {@code extra} is within {@value #APART} blocks of one of {@code others} (another extra box:
     * the arena's, the Clubhouse's), in {@link #extraProblems}' words, or {@code null}. Each pair is
     * asked on its own, so a neighbour with a problem of its own (crowding a slot, say) still counts,
     * since its blocks may stand; a neighbour with {@code extra}'s own name is skipped.
     */
    public static String extraApartProblem(Extra extra, List<Extra> others) {
        for (Extra o : others == null ? List.<Extra>of() : others) {
            if (o == null || extra == null || o.name().equals(extra.name())) {
                continue;
            }
            int gap = extra.box().gap(o.box());
            if (gap < APART) {
                return extra.name() + " is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from") + " "
                        + o.name() + " (they must be " + APART + " apart)";
            }
        }
        return null;
    }

    /**
     * Why {@code extra} in {@code world} is too close to a hand-built course, or {@code null} when
     * every one is at least {@value #CLEARANCE} blocks away ({@link #handBuilt} lists them).
     */
    public static String extraHandBuiltProblem(Extra extra, String world, List<Area> areas) {
        for (Area a : areas == null ? List.<Area>of() : areas) {
            if (a.world() == null || !a.world().equalsIgnoreCase(world)) {
                continue;
            }
            int gap = a.box().gap(extra.box());
            if (gap < CLEARANCE) {
                return "the hand-built course " + a.courseId() + " is " + (gap < 0 ? "inside" : "only " + gap
                        + " blocks from") + " " + extra.name() + " (" + a.box().describe() + "; it must be "
                        + CLEARANCE + " away)";
            }
        }
        return null;
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

    /**
     * Why {@code def}'s region at {@code origin} (its halves {@code gap} apart) can't be built in
     * {@code w}, in admin words; empty = fine. Each half is checked on its own (the world's height and
     * border, the spawn and the safe spot), never the air between them.
     */
    public static List<String> worldProblems(Slots.Def def, int[] origin, int gap, WorldFacts w) {
        List<String> out = new ArrayList<>();
        if (!w.listed()) {
            out.add("the world " + w.name() + " isn't in games.worlds");
        }
        Box a = half(def, origin, gap, 'A');
        Box b = half(def, origin, gap, 'B');
        if (w.minHeight() + HEADROOM > a.minY() || a.maxY() + 1 > w.maxHeight() - HEADROOM) {
            out.add("it needs y " + a.minY() + ".." + a.maxY() + ", but " + w.name() + " has room for "
                    + (w.minHeight() + HEADROOM) + ".." + (w.maxHeight() - HEADROOM - 1));
        }
        for (Box h : List.of(a, b)) {
            if (outside(h, w.border())) {
                out.add("it reaches past the world border (half " + (h == a ? 'A' : 'B') + " " + h.describe() + ")");
                break;
            }
        }
        if (w.spawn() != null) {
            String near = near(List.of(a, b), w.spawn()[0], w.spawn()[1], w.spawn()[2]);
            if (near != null) {
                out.add("the world's spawn is " + near);
            }
        }
        if (w.safeSpot() != null) {
            double[] s = w.safeSpot();
            String near = near(List.of(a, b), (int) Math.floor(s[0]), (int) Math.floor(s[1]), (int) Math.floor(s[2]));
            if (near != null) {
                out.add("games.fresh.safe_spot is " + near);
            }
        }
        return out;
    }

    /**
     * Why kept-course plot {@code n} (standing in {@code plot}) can't be used in {@code w}, in admin
     * words; empty = fine: inside the world's height (with {@value #HEADROOM} to spare) and border,
     * and the spawn and the safe spot at least {@value #CLEARANCE} blocks outside it. The keep area
     * had none of these checks in 0.35; with its plots spread apart, the last ones reach far out.
     */
    public static List<String> plotWorldProblems(int n, Box plot, WorldFacts w) {
        List<String> out = new ArrayList<>();
        if (w.minHeight() + HEADROOM > plot.minY() || plot.maxY() + 1 > w.maxHeight() - HEADROOM) {
            out.add("plot " + n + " needs y " + plot.minY() + ".." + plot.maxY() + ", but " + w.name()
                    + " has room for " + (w.minHeight() + HEADROOM) + ".." + (w.maxHeight() - HEADROOM - 1));
        }
        if (outside(plot, w.border())) {
            out.add("plot " + n + " reaches past the world border (" + plot.describe() + ")");
        }
        if (w.spawn() != null) {
            String near = near(plot, w.spawn()[0], w.spawn()[1], w.spawn()[2]);
            if (near != null) {
                out.add("the world's spawn is " + near + " plot " + n);
            }
        }
        if (w.safeSpot() != null) {
            double[] s = w.safeSpot();
            String near = near(plot, (int) Math.floor(s[0]), (int) Math.floor(s[1]), (int) Math.floor(s[2]));
            if (near != null) {
                out.add("games.fresh.safe_spot is " + near + " plot " + n);
            }
        }
        return out;
    }

    /**
     * The plots of {@code keep} that can't be used in {@code w} ({@link #plotWorldProblems}), by
     * number, each with its first problem; empty when every plot fits.
     */
    public static Map<Integer, String> keepWorldProblems(KeepArea keep, WorldFacts w) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (keep == null || w == null) {
            return out;
        }
        for (int n = 1; n <= keep.maxPlots(); n++) {
            List<String> p = plotWorldProblems(n, keep.plot(n), w);
            if (!p.isEmpty()) {
                out.put(n, p.get(0));
            }
        }
        return out;
    }

    /** Whether any of {@code box} lies outside {@code border} (x and z; {@code null}: no border known). */
    private static boolean outside(Box box, Box border) {
        return border != null && (box.minX() < border.minX() || box.maxX() > border.maxX()
                || box.minZ() < border.minZ() || box.maxZ() > border.maxZ());
    }

    /** "inside half A" / "only 3 blocks from half B", or {@code null} when at least {@value #CLEARANCE} away. */
    private static String near(List<Box> halves, int x, int y, int z) {
        Box point = new Box(x, y, z, x, y, z);
        for (int i = 0; i < halves.size(); i++) {
            char which = (char) ('A' + i);
            int gap = point.gap(halves.get(i));
            if (gap < CLEARANCE) {
                return gap < 0 ? "inside half " + which : "only " + gap + " blocks from half " + which
                        + " (it must be " + CLEARANCE + " away)";
            }
        }
        return null;
    }

    /** "inside" / "only 3 blocks from", or {@code null} when at least {@value #CLEARANCE} away from {@code box}. */
    private static String near(Box box, int x, int y, int z) {
        int gap = new Box(x, y, z, x, y, z).gap(box);
        if (gap >= CLEARANCE) {
            return null;
        }
        return gap < 0 ? "inside" : "only " + gap + " blocks from";
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
     * Why {@code def}'s region at {@code origin} (its halves {@code halfGap} apart) in {@code world} is
     * too close to a hand-built course, or {@code null} when every one is at least {@value #CLEARANCE}
     * blocks away.
     */
    public static String handBuiltProblem(Slots.Def def, int[] origin, int halfGap, String world, List<Area> areas) {
        for (Area a : areas) {
            if (a.world() == null || !a.world().equalsIgnoreCase(world)) {
                continue;
            }
            for (char which : new char[]{'A', 'B'}) {
                int gap = a.box().gap(half(def, origin, halfGap, which));
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

    /**
     * What {@code gen.<slot>.claim} holds: "world,x,y,z,sx,sy,sz" (the origin and one half's size) when
     * the halves stand {@link Slots#LEGACY_HALF_GAP} apart, so a claim made before gaps were recorded
     * still matches; "world,x,y,z,sx,sy,sz,gap" for any other gap. Where half B stands is part of the
     * claim, so a changed gap reads as a moved region, exactly like a changed origin.
     */
    public static String claim(Slots.Def def, String world, int[] origin, int gap) {
        return world.toLowerCase(Locale.ROOT) + "," + origin[0] + "," + origin[1] + "," + origin[2] + ","
                + def.sizeX() + "," + def.sizeY() + "," + def.sizeZ()
                + (gap == Slots.LEGACY_HALF_GAP ? "" : "," + gap);
    }

    /**
     * The gap between the halves a claim was made with: {@link Slots#LEGACY_HALF_GAP} for the 7 fields
     * every claim had before gaps were recorded (never the default gap, which may have changed since),
     * the 8th field otherwise; {@code null} when the claim can't be read.
     */
    public static Integer claimGap(String claim) {
        if (claimOrigin(claim) == null) {
            return null;
        }
        String[] p = claim.split(",");
        return p.length == 7 ? Slots.LEGACY_HALF_GAP : Integer.parseInt(p[7].trim()); // read by claimOrigin
    }

    /** The world a claim was made in, or {@code null} when it can't be read. */
    public static String claimWorld(String claim) {
        if (claim == null || claimOrigin(claim) == null) {
            return null;
        }
        String w = claim.substring(0, claim.indexOf(',')).trim();
        return w.isEmpty() ? null : w;
    }

    /**
     * The claims a stored wet list names ({@link GenAdminKeys#wet}): ';' between them, each a whole
     * claim; blanks, unreadable ones and repeats dropped, in order.
     */
    public static List<String> wetClaims(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) {
            return out;
        }
        for (String c : stored.split(";")) {
            String t = c.trim();
            if (claimOrigin(t) != null && claimWorld(t) != null && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** A wet list as stored, or {@code null} (the key unset) when it is empty. */
    public static String wetText(List<String> claims) {
        return claims == null || claims.isEmpty() ? null : String.join(";", claims);
    }

    /** The origin a claim was made at, or {@code null} when it can't be read (7 fields, or 8 with the gap). */
    public static int[] claimOrigin(String claim) {
        if (claim == null) {
            return null;
        }
        String[] p = claim.split(",");
        if (p.length != 7 && p.length != 8) {
            return null;
        }
        try {
            if (p.length == 8 && Integer.parseInt(p[7].trim()) < 0) {
                return null;
            }
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
