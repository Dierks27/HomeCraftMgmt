package com.dierks.homecraft.games.trial;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An admin's test runs of a Fresh Courses preview ({@code /hcm games gen test}, WP-ADM): who is
 * on one, and how to start it again.
 *
 * <p>Why it exists: a preview is a course with no row, built only for the test run, so the result
 * screen's "Play again" can't look it up by id the way it does a test of the live course (that
 * would put the admin on the live course instead). It asks Fresh Courses for the preview again
 * instead, through the command that started it, which checks it all over again (the preview may
 * be gone, promoted, or being rebuilt). A test run of any other course forgets it.
 *
 * <p>A test run records nothing whatever course it is on ({@link FairPlay#judge}: never a board, a
 * reward, a Cup time, a quest or an achievement), so nothing else here needs to know it's a preview.
 */
final class PreviewTests {

    private final Map<UUID, Runnable> again = new HashMap<>();

    /** {@code player} started a test run on a preview; "Play again" runs {@code again}. */
    void started(UUID player, Runnable again) {
        if (player != null && again != null) {
            this.again.put(player, again);
        }
    }

    /** {@code player} started a test run of another course (or left): "Play again" is that course's. */
    void forget(UUID player) {
        again.remove(player);
    }

    /** How to start {@code player}'s preview test again, or {@code null} when their last test wasn't one. */
    Runnable again(UUID player) {
        return again.get(player);
    }
}
