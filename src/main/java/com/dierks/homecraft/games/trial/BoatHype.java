package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.BoatStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Ice Boat "Mountain Run" says about itself (COURSE-VARIETY-SPEC §5.2, §8): how many drops
 * it has, for the tile NAME ("5 drops · ") and Race Night's hype line ("This week: 5 drops down the
 * mountain!"), and which checkpoint comes just before a Final Drop in front of the stand. Pure: no
 * Bukkit, never throws.
 *
 * <p><b>Why the drops are read off the marks.</b> A course stores no drop count, and the race copy
 * must not wait for the planner or re-derive a plan on the main thread. The Mountain Run puts exactly
 * one checkpoint between every two lips (§2.6), and every mark sits on the ice surface, so each leg
 * (start to the first checkpoint, checkpoint to checkpoint, the last checkpoint to the finish) holds
 * at most one drop, and a leg holds a drop exactly when its next mark is lower. Counting those legs is
 * the drop count, exactly; a 2-block Big Drop is one drop, like a Hop.
 *
 * <p><b>"Final drop!" only where the gold finish comes next.</b> The layout's own FINAL DROP! sign
 * ("Then the gold finish line!") stands only when its Final Drop is in front of the stand, at most 70
 * blocks before the finish (the Course Variety decisions); on easy and hard, and on most medium runs,
 * the last drop is farther up the mountain and has its own HOP! or BIG DROP! sign. The planner lays
 * the checkpoints so that the Final Drop's leg runs straight on to the finish exactly then, and has a
 * checkpoint after it otherwise, so the title is read off the marks the same way: it shows at the
 * checkpoint before the last drop only when the next target after that drop is the finish.
 *
 * <p><b>Only the Mountain Run.</b> A Fresh Ice Boat layout from boat planner algo {@value #FIRST_ALGO}
 * on; the flat algo-2 loops, kept courses (their tag is gone) and hand-built tracks say nothing new,
 * so they read exactly as they always did.
 *
 * <p><b>Mountain Run v2</b> (algo {@value #V2_ALGO} on, MOUNTAIN-V2-SPEC §12): its style is read off its
 * seed ({@link #style}, {@code BoatStyle.of(c.gen().seed())}), so the copy always says what the run really
 * is: "Winding Road · 27 drops · " on the tile and "This week: the Winding Road - 27 drops, 48 blocks down
 * the mountain!" (or "the Slalom - 14 drops through the gates!") in Race Night's hype, both from
 * {@link GenCopy}. Its 60-120 checkpoints go <b>quiet</b> ({@link #loud}): the big title shows at every
 * {@value #LOUD_EVERY}th checkpoint, at the one nearest half way ({@link #HALFWAY}) and before the Final
 * Drop; every other one is an action-bar line ({@link #quietBar}) and a soft ping. Its windows and leg
 * floor read its model time T_m ({@link #modelMs}, red-team F04), never the star reference.
 */
public final class BoatHype {

    /** The first boat planner algo that makes the downhill Mountain Run (§2). */
    public static final int FIRST_ALGO = 3;
    /** A mark at least this much lower than the one before it has a drop in its leg (drops are whole blocks). */
    public static final double STEP = 0.5;
    /** The title as a racer passes the checkpoint before the Final Drop (§5.2). */
    public static final String FINAL_DROP = "&aFinal drop!";
    /** The first boat planner algo that makes Mountain Run v2 (MOUNTAIN-V2-SPEC: BoatPlanner ALGO 4). */
    public static final int V2_ALGO = 4;
    /** On a Mountain Run v2, every this many checkpoints shows the big title (the rest are quiet). */
    public static final int LOUD_EVERY = 10;
    /** The title at the checkpoint nearest half way down a Mountain Run v2 (its HALFWAY! sign's). */
    public static final String HALFWAY = "&aHalfway!";

    private BoatHype() {
    }

    /** Whether {@code c} is a Mountain Run: a Fresh Ice Boat layout of algo {@value #FIRST_ALGO} or later. */
    public static boolean mountain(Course c) {
        if (c == null || c.kind() != TrialKind.BOAT) {
            return false;
        }
        GenTag t = c.gen();
        return t != null && Slots.BOAT.equals(t.generator()) && t.algo() >= FIRST_ALGO;
    }

    /**
     * Whether {@code c} is a Mountain Run v2: a Fresh Ice Boat layout of boat planner algo {@value #V2_ALGO} or
     * later (MOUNTAIN-V2-SPEC). Everything v2 changes at runtime asks this, so algo 2-3 layouts, kept courses
     * and hand-built tracks behave exactly as before.
     */
    public static boolean mountainV2(Course c) {
        return mountain(c) && c.gen().algo() >= V2_ALGO;
    }

    /** A Mountain Run v2's style, read off its seed ({@link BoatStyle#of}); {@code null} for any other course. */
    public static BoatStyle style(Course c) {
        return mountainV2(c) ? BoatStyle.of(c.gen().seed()) : null;
    }

    /** Whether {@code c} is a Mountain Run v2 Slalom (red and blue gate fences: not a Race Night track). */
    public static boolean slalom(Course c) {
        return style(c) == BoatStyle.SLALOM;
    }

    /**
     * A Slalom as its set's cadence says it (audit M02, M08): "the Slalom this week" on a weekly set, "the Slalom
     * today" on a daily one, and "a Slalom" on any other cadence or a Classic, whose words ("on this course")
     * would read badly after it.
     */
    public static String slalomNow(Course c) {
        int words = GenCopy.words(c == null ? null : c.gen());
        return words == 1 || words == 7 ? "the Slalom " + GenCopy.when(words) : "a Slalom";
    }

    /**
     * A Mountain Run v2's model time T_m in milliseconds ({@code BoatPlanner.modelMs} of its tag, red-team F04),
     * what its Race Night and party-race windows scale with; 0 for any other course, whose windows stay as
     * configured. Never the star reference ({@code refMs}, 4/5 of T_m).
     */
    public static long modelMs(Course c) {
        return mountainV2(c) ? BoatPlanner.modelMs(c.gen()) : 0;
    }

    /** How many blocks a run goes down from its start to its finish (0 with either missing). */
    public static int descent(Course c) {
        if (c == null || c.start() == null || c.finish() == null) {
            return 0;
        }
        return (int) Math.max(0, Math.round(c.start().y() - c.finish().y()));
    }

    /**
     * How many legs of {@code c} end lower than they start (the start, then every checkpoint, then the
     * finish): a Mountain Run's drops. 0 with no start.
     */
    public static int drops(Course c) {
        if (c == null || c.start() == null) {
            return 0;
        }
        int n = 0;
        double y = c.start().y();
        for (Course.Mark m : c.targets()) {
            if (m.y() <= y - STEP) {
                n++;
            }
            y = m.y();
        }
        return n;
    }

    /**
     * The index (0-based) of the checkpoint just before a Mountain Run's last drop, where the racer
     * hears {@link #FINAL_DROP}, when the finish comes right after that drop; -1 when {@code c} isn't a
     * Mountain Run, has no drop, its last drop comes before the first checkpoint, or a checkpoint stands
     * between its last drop and the finish (a Final Drop up the mountain, with no FINAL DROP! sign).
     */
    public static int finalDrop(Course c) {
        if (!mountain(c) || c.start() == null) {
            return -1;
        }
        List<Course.Mark> targets = c.targets();
        int last = -1;
        double y = c.start().y();
        for (int i = 0; i < targets.size(); i++) {
            double next = targets.get(i).y();
            if (next <= y - STEP) {
                last = i;
            }
            y = next;
        }
        if (last != targets.size() - 1) {
            return -1; // the finish doesn't come next: the drop's sign is its own HOP! or BIG DROP!
        }
        int at = last - 1; // the mark before the leg with the last drop in it
        return at >= 0 && at < c.checkpoints().size() ? at : -1;
    }

    /**
     * The checkpoint (0-based) nearest half way along a Mountain Run v2, where its HALFWAY! sign stands
     * ({@link #halfway(List, Point)} of its checkpoints and finish, the planner's own rule); -1 for any other
     * course or one with no checkpoint. Its start is never read, so every racer on a grid, however far back,
     * hears "Halfway!" at the same checkpoint, the sign's.
     */
    public static int halfway(Course c) {
        if (!mountainV2(c) || c.finish() == null || c.checkpoints().isEmpty()) {
            return -1;
        }
        List<Point> cps = new ArrayList<>(c.checkpoints().size());
        for (Course.Mark m : c.checkpoints()) {
            cps.add(m.center());
        }
        return halfway(cps, c.finish().center());
    }

    /**
     * The one rule for half way down a Mountain Run v2, shared by the planner's HALFWAY! sign and the runtime's
     * "Halfway!" title (audit M00): the checkpoint (0-based) whose distance from checkpoint 0, mark to mark,
     * is nearest half the distance from checkpoint 0 to {@code finish} (the first of two as near); -1 with no
     * checkpoint. It starts at checkpoint 0, not at the start, because a race moves the start back to each
     * racer's grid spot.
     */
    public static int halfway(List<Point> cps, Point finish) {
        if (cps == null || cps.isEmpty() || finish == null) {
            return -1;
        }
        double[] at = new double[cps.size()];
        for (int i = 1; i < cps.size(); i++) {
            at[i] = at[i - 1] + cps.get(i - 1).distance(cps.get(i));
        }
        double mid = (at[at.length - 1] + cps.get(cps.size() - 1).distance(finish)) / 2;
        int best = 0;
        for (int i = 1; i < at.length; i++) {
            if (Math.abs(at[i] - mid) < Math.abs(at[best] - mid)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Whether reaching checkpoint {@code index} (0-based) of {@code c} shows the big title. Always, except on a
     * Mountain Run v2, whose 60-120 checkpoints go quiet (§12): only every {@value #LOUD_EVERY}th one, the one
     * nearest half way ({@link #halfway}) and the one before the Final Drop ({@link #finalDrop}) are loud.
     */
    public static boolean loud(Course c, int index) {
        if (!mountainV2(c)) {
            return true;
        }
        return (index + 1) % LOUD_EVERY == 0 || index == halfway(c) || index == finalDrop(c);
    }

    /**
     * The big title for reaching checkpoint {@code index} (0-based) of {@code c}: "Final drop!" before a
     * Final Drop the finish comes right after ({@link #finalDrop}), "Halfway!" at a Mountain Run v2's
     * {@link #halfway} checkpoint, or nothing.
     */
    public static String checkpointTitle(Course c, int index) {
        if (index >= 0 && index == finalDrop(c)) {
            return FINAL_DROP;
        }
        return index >= 0 && index == halfway(c) ? HALFWAY : "";
    }

    /**
     * A quiet checkpoint's action-bar line (§12): "&amp;aCheckpoint 37/74 &amp;7· 1:12.4", with {@code index}
     * 0-based of {@code of} checkpoints and {@code time} the clock as {@link TrialText#time} writes it.
     */
    public static String quietBar(int index, int of, String time) {
        return "&aCheckpoint " + (index + 1) + "/" + of + " &7· " + time;
    }

    /**
     * A Mountain Run's tile fact before its stars, the way a Dropper's "3 levels · " is: "5 drops · ", or on
     * a Mountain Run v2 its style first, "Winding Road · 27 drops · " ({@link GenCopy#boatV2Tile});
     * {@code ""} for any other course.
     */
    public static String fact(Course c) {
        int n = mountain(c) ? drops(c) : 0;
        BoatStyle style = style(c);
        if (style != null) {
            return n > 0 ? GenCopy.boatV2Tile(style == BoatStyle.SLALOM, n) : style.title() + " · ";
        }
        return n > 0 ? TrialText.drops(n) + " · " : "";
    }

    /**
     * Race Night's hype line for its heads-up and join-open chat (§5.2): "&amp;bThis week: 5 drops down
     * the mountain!" ("Today: ..." on a daily set, "On this course: ..." on any other cadence, in the
     * cadence's own words); on a Mountain Run v2 "&amp;bThis week: the Winding Road - 27 drops, 48 blocks
     * down the mountain!" (or "the Slalom - 14 drops through the gates!", {@link GenCopy#boatV2Hype}), its
     * style read off its seed. {@code null} when {@code on} is false ({@code games.race_night.hype}), the
     * track isn't a Mountain Run, or it has no drops.
     */
    public static String line(Course c, boolean on) {
        if (!on || !mountain(c)) {
            return null;
        }
        int n = drops(c);
        if (n <= 0) {
            return null;
        }
        String when = GenCopy.when(GenCopy.words(c.gen()));
        BoatStyle style = style(c);
        if (style != null) {
            return GenCopy.boatV2Hype(when, style == BoatStyle.SLALOM, n, descent(c));
        }
        return "&b" + when.substring(0, 1).toUpperCase(Locale.ROOT) + when.substring(1) + ": " + TrialText.drops(n)
                + " down the mountain!";
    }
}
