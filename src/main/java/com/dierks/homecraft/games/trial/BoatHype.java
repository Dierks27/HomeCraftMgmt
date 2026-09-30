package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.List;
import java.util.Locale;

/**
 * What the Ice Boat "Mountain Run" says about itself (COURSE-VARIETY-SPEC §5.2, §8): how many drops
 * it has, for the tile NAME ("5 drops · ") and Race Night's hype line ("This week: 5 drops down the
 * mountain!"), and which checkpoint comes just before the Final Drop. Pure: no Bukkit, never throws.
 *
 * <p><b>Why the drops are read off the marks.</b> A course stores no drop count, and the race copy
 * must not wait for the planner or re-derive a plan on the main thread. The Mountain Run puts exactly
 * one checkpoint between every two lips (§2.6), and every mark sits on the ice surface, so each leg
 * (start to the first checkpoint, checkpoint to checkpoint, the last checkpoint to the finish) holds
 * at most one drop, and a leg holds a drop exactly when its next mark is lower. Counting those legs is
 * the drop count, exactly; a 2-block Big Drop is one drop, like a Hop.
 *
 * <p><b>Only the Mountain Run.</b> A Fresh Ice Boat layout from boat planner algo {@value #FIRST_ALGO}
 * on; the flat algo-2 loops, kept courses (their tag is gone) and hand-built tracks say nothing new,
 * so they read exactly as they always did.
 */
public final class BoatHype {

    /** The first boat planner algo that makes the downhill Mountain Run (§2). */
    public static final int FIRST_ALGO = 3;
    /** A mark at least this much lower than the one before it has a drop in its leg (drops are whole blocks). */
    public static final double STEP = 0.5;
    /** The title as a racer passes the checkpoint before the Final Drop (§5.2). */
    public static final String FINAL_DROP = "&aFinal drop!";

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
     * hears {@link #FINAL_DROP}; -1 when {@code c} isn't a Mountain Run, has no drop, or its last drop
     * comes before the first checkpoint.
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
        int at = last - 1; // the mark before the leg with the last drop in it
        return at >= 0 && at < c.checkpoints().size() ? at : -1;
    }

    /** The big title for reaching checkpoint {@code index} (0-based) of {@code c}: "Final drop!" or nothing. */
    public static String checkpointTitle(Course c, int index) {
        return index >= 0 && index == finalDrop(c) ? FINAL_DROP : "";
    }

    /**
     * A Mountain Run's tile fact before its stars, the way a Dropper's "3 levels · " is: "5 drops · ";
     * {@code ""} for any other course.
     */
    public static String fact(Course c) {
        int n = mountain(c) ? drops(c) : 0;
        return n > 0 ? TrialText.drops(n) + " · " : "";
    }

    /**
     * Race Night's hype line for its heads-up and join-open chat (§5.2): "&amp;bThis week: 5 drops down
     * the mountain!" ("Today: ..." on a daily set, "On this course: ..." on any other cadence, in the
     * cadence's own words). {@code null} when {@code on} is false ({@code games.race_night.hype}), the
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
        return "&b" + when.substring(0, 1).toUpperCase(Locale.ROOT) + when.substring(1) + ": " + TrialText.drops(n)
                + " down the mountain!";
    }
}
