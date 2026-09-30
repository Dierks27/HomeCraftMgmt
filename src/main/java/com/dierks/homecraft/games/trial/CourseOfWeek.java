package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The course of the week (spec §11): one open course, the same all week, that pays a small bonus
 * once a day to anyone who finishes it. An admin can pin one ({@code /hcm games course <id>
 * feature}); otherwise it is picked by a hash of the local week key over the open courses, so the
 * whole server agrees on it without storing anything, and it changes when the week does.
 */
final class CourseOfWeek {

    /** Keeps the week's pick from lining up with the featured game's pick for the same day key. */
    private static final long SALT = 0x5EED_C0DE_7121_A15EL;

    private CourseOfWeek() {
    }

    /**
     * This week's course among {@code open} (any order; sorted here, so the order they were read
     * in can't change the pick): the pinned one if it is open, else a stable hash of
     * {@code weekKey}. {@code null} when no course is open.
     */
    static String pick(Collection<String> open, long weekKey, String pinned) {
        if (open == null || open.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>(open);
        ids.sort(null);
        if (pinned != null && ids.contains(pinned)) {
            return pinned;
        }
        long z = weekKey ^ SALT;
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return ids.get((int) Math.floorMod(z, (long) ids.size()));
    }
}
