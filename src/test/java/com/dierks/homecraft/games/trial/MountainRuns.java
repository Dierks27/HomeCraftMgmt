package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-made Ice Boat "Mountain Run" (COURSE-VARIETY-SPEC §2), the race copy's fixture while the
 * algo-3 boat planner is still being built: a Medium sprint whose every number a test can work out by
 * hand, stored as the planner will store it (§2.6, §2.8).
 *
 * <p>Half A of {@code fresh_boat} at (4480, 160, 4352), so H0 = 160 and the centre C = (4544, 4416).
 * The launch pit and its start are on the top level (ice at H0 + 8, surface 169); the track spirals
 * clockwise and inward from the north side; every mark sits on the ice surface with radius w/2 + 0.5 =
 * 4 (the finish w/2 + 1.5 = 5). Five drops, 7 blocks down in all, and exactly one checkpoint between
 * every two lips:
 *
 * <pre>
 * start           (4510.5, 169, 4361.5)  the pit, facing east
 * cp 1, cp 2      169                    north side, the first lip after cp 2
 *   Hop (1)
 * cp 3, cp 4      168                    east side
 *   Big Drop (2)
 * cp 5            166                    south side
 *   Hop (1)
 * cp 6, cp 7      165                    south, then west side
 *   Hop (1)
 * cp 8, cp 9, cp 10  164                 west, north and east (inner rings); cp 10 is before the Final Drop
 *   Final Drop (2)
 * finish          (4560.5, 162, 4444.5)  the lowest deck, 25 blocks south of the stand's platform
 * </pre>
 *
 * The start is about 97 blocks from the finish, so it is a sprint, never a loop.
 */
public final class MountainRuns {

    /** The Ice Boat slot's half A, and its floor (H0). */
    public static final int H0 = 160;
    /** The pit's surface: H0 + 9. */
    public static final double TOP = H0 + 9;
    /** The last checkpoint (0-based) before the Final Drop. */
    public static final int FINAL_DROP_AT = 9;
    /** How many drops the Medium run has. */
    public static final int DROPS = 5;

    public static final Course.Spot START = new Course.Spot(4510.5, TOP, 4361.5, -90f, 0f);
    public static final List<Course.Mark> CHECKPOINTS = List.of(
            mark(4540.5, 169, 4361.5), mark(4575.5, 169, 4361.5),
            mark(4599.5, 168, 4395.5), mark(4599.5, 168, 4430.5),
            mark(4580.5, 166, 4462.5),
            mark(4530.5, 165, 4462.5), mark(4502.5, 165, 4430.5),
            mark(4502.5, 164, 4385.5), mark(4540.5, 164, 4379.5), mark(4576.5, 164, 4400.5));
    public static final Course.Mark FINISH = new Course.Mark(4560.5, 162, 4444.5, 5);

    private MountainRuns() {
    }

    private static Course.Mark mark(double x, double y, double z) {
        return new Course.Mark(x, y, z, 4);
    }

    /** The Mountain Run's tag: {@code fresh_boat}, boat planner algo 3, a weekly set. */
    public static GenTag tag() {
        return tag(3, 7);
    }

    /** A {@code fresh_boat} tag of boat planner {@code algo}, in a set of {@code cadence} days. */
    public static GenTag tag(int algo, int cadence) {
        return new GenTag("fresh_boat", "boat", algo, 20_725, 0, 42L, 'A', "3c0ffee00001", 39_300, 36_000, 44_000,
                List.of(), List.of(), 1_790_000_000_000L, cadence);
    }

    /** The Medium Mountain Run, as a weekly Fresh Ice Boat layout. */
    public static Course medium() {
        return medium(tag());
    }

    /** The Medium Mountain Run's marks with {@code tag} ({@code null}: as a hand-built course). */
    public static Course medium(GenTag tag) {
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games", START, CHECKPOINTS, FINISH,
                (double) (H0 + 1 - 3), 5, true, false, 1, tag);
    }

    /** A Mountain Run with one drop only: a Hop between cp 1 and cp 2, then flat to the finish. */
    public static Course oneDrop() {
        List<Course.Mark> cps = List.of(mark(4540.5, 169, 4361.5), mark(4575.5, 168, 4361.5),
                mark(4599.5, 168, 4395.5));
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.EASY, "games", START, cps,
                new Course.Mark(4599.5, 168, 4440.5, 6), 165.0, 5, true, false, 1, tag());
    }

    /** A Mountain Run whose only drop is between the start and its first checkpoint: no checkpoint before it. */
    public static Course dropBeforeTheFirstCheckpoint() {
        List<Course.Mark> cps = List.of(mark(4575.5, 168, 4361.5), mark(4599.5, 168, 4395.5));
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.EASY, "games", START, cps,
                new Course.Mark(4599.5, 168, 4440.5, 6), 165.0, 5, true, false, 1, tag());
    }

    /**
     * An Easy or Hard run as the planner lays one (§2.4: four drops; review CV gate): its last drop,
     * between cp 7 and cp 8, is up the mountain, where the landing strips let it in, and three more
     * checkpoints and about 120 blocks of flat track come before the finish. Its sign is its own HOP!,
     * never FINAL DROP!, so no racer hears "Final drop!" at cp 7 (radius w/2 + 0.5: 5 on easy, 3 on hard).
     *
     * <pre>
     * cp 1, cp 2           169   north side
     *   Hop
     * cp 3, cp 4           168   east side
     *   Hop
     * cp 5                 167   south side
     *   Hop
     * cp 6, cp 7           166   south, then west side
     *   Hop (the last)
     * cp 8, cp 9, cp 10    165   west, north and east (inner rings)
     * finish               165   the lowest deck, under the stand
     * </pre>
     */
    public static Course farFinalDrop(Tier tier) {
        double r = tier == Tier.EASY ? 5 : 3;
        List<Course.Mark> cps = List.of(
                new Course.Mark(4540.5, 169, 4361.5, r), new Course.Mark(4575.5, 169, 4361.5, r),
                new Course.Mark(4599.5, 168, 4395.5, r), new Course.Mark(4599.5, 168, 4430.5, r),
                new Course.Mark(4580.5, 167, 4462.5, r),
                new Course.Mark(4530.5, 166, 4462.5, r), new Course.Mark(4502.5, 166, 4430.5, r),
                new Course.Mark(4502.5, 165, 4385.5, r), new Course.Mark(4540.5, 165, 4379.5, r),
                new Course.Mark(4576.5, 165, 4400.5, r));
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", tier, "games", START, cps,
                new Course.Mark(4560.5, 165, 4444.5, r + 1), (double) (H0 + 5 - 3), 5, true, false, 1, tag());
    }

    /** The Medium run's marks laid flat at the pit's height: what a flat layout would count (nothing). */
    public static Course flat(GenTag tag) {
        List<Course.Mark> cps = new ArrayList<>();
        for (Course.Mark m : CHECKPOINTS) {
            cps.add(new Course.Mark(m.x(), TOP, m.z(), m.radius()));
        }
        return medium(tag).withCheckpoints(cps).withFinish(new Course.Mark(FINISH.x(), TOP, FINISH.z(), 5));
    }
}
