package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dropper courses for the Time Trials tests (EVENTS-DROPPER-SPEC §B.1.7): one small hand-made
 * three-level course whose every number a test can work out by hand, and real ones from the planner
 * (cached; a plan takes a few milliseconds).
 *
 * <pre>
 * level 1: ledge (10.5, 100, 10.5), the start     pool Easy 11 x 11 at (12.5, 12.5), water at y 68
 * level 2: ledge (22.5, 100, 10.5)                 pool Medium 7 x 7 at (24.5, 12.5), water at y 60
 * level 3: ledge (34.5, 100, 10.5)                 pool Hard 5 x 5 at (36.5, 12.5), water at y 52 (the finish)
 * fall height 44 (the Hard pool's floor row is 48)
 * </pre>
 */
final class DropperCourses {

    static final Course.Mark POOL_1 = DropMarks.pool(12.5, 68, 12.5, 11);
    static final Course.Mark LEDGE_2 = DropMarks.ledge(22.5, 100, 10.5);
    static final Course.Mark POOL_2 = DropMarks.pool(24.5, 60, 12.5, 7);
    static final Course.Mark LEDGE_3 = DropMarks.ledge(34.5, 100, 10.5);
    static final Course.Mark POOL_3 = DropMarks.pool(36.5, 52, 12.5, 5);
    static final Course.Spot START = new Course.Spot(10.5, 100, 10.5, 0f, 30f);

    private static final Map<String, Course> PLANNED = new ConcurrentHashMap<>();

    private DropperCourses() {
    }

    /** The hand-made three-level dropper (EMH), hand-built (no tag). */
    static Course hand() {
        return hand(null);
    }

    /** The hand-made dropper with Fresh Courses' {@code tag} (or none). */
    static Course hand(GenTag tag) {
        return new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.MEDIUM, "games", START,
                List.of(POOL_1, LEDGE_2, POOL_2, LEDGE_3), POOL_3, 44.0, 4, true, false, 1, tag);
    }

    /** The same marks as a parkour course: what the other kinds see (nothing of the Dropper's rules). */
    static Course asParkour() {
        return new Course("steps", TrialKind.PARKOUR, "Steps", Tier.MEDIUM, "games", START,
                List.of(POOL_1, LEDGE_2, POOL_2, LEDGE_3), POOL_3, 44.0, 4, true, false, 1);
    }

    /** A real dropper the planner made for {@code mix} (Easy Dropper's slot for EEE, the Dropper's otherwise). */
    static Course planned(String mix, int n) {
        return PLANNED.computeIfAbsent(mix + "/" + n, k -> ((PlannedTrial) plan(mix, n).course()).course());
    }

    /** The planner's whole plan for {@code mix} and seed {@code n}, half A. */
    static Plan plan(String mix, int n) {
        Slots.Def slot = "EEE".equals(mix) ? Slots.EASY_DROPPER : Slots.FRESH_DROPPER;
        long day = 20_000 + n;
        try {
            return new DropperPlanner().plan(new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', day, 0,
                    GenSeed.seed(0x5EC12E7L, day, slot.id(), 0), mix, 6, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError("the dropper " + mix + "/" + n + " should plan: " + e.getMessage(), e);
        }
    }

    /** A point. */
    static Point at(double x, double y, double z) {
        return new Point(x, y, z);
    }
}
