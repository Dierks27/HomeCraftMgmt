package com.dierks.homecraft.games;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;

/**
 * What the course engines ask about generated courses (GEN-SPEC §0.2 R5, §3.4), through
 * {@link GamesService#generated()}.
 *
 * <p>A generated course is playable only while the Daily Courses engine vouches for its blocks:
 * the gate is shut at every boot until the live half is verified, and shut whenever the
 * {@code daily} game is off. So the engines don't trust a row with a {@code gen:} block on its own;
 * they ask {@link #live}. A run in progress keeps counting while the layout it started on still
 * stands ({@link #standing}), even after the next layout went live.
 *
 * <p>Until the engine is installed — and whenever {@code daily} is off — this is {@link #NONE}: no
 * generated course is live, none is standing, and no area is protected, so hand-built courses
 * behave exactly as before and a leftover generated row simply stays closed.
 */
public interface GeneratedCourses {

    /**
     * Whether course {@code courseId} may be played now. A course with no tag (hand-built) is not
     * this gate's business: always true. A generated one only while its live layout is verified.
     */
    boolean live(String courseId, GenTag tag);

    /**
     * Whether the layout {@code tag} names still stands: the live layout, or the previous one until
     * its half starts being cleared. A run on a standing layout counts. False for no tag.
     */
    boolean standing(GenTag tag);

    /** What a player reads when generated course {@code courseId} can't be played now. */
    String closedLine(String courseId);

    /** When the next set of courses is due (epoch ms), or -1 when nothing is scheduled. */
    long nextChangeAt();

    /** Whether block (x, y, z) of {@code world} is inside a Daily Courses half (anyone's edits are refused there). */
    boolean inArea(String world, int x, int y, int z);

    /** No engine, or {@code daily} is off: nothing generated is live or standing; no area is kept. */
    GeneratedCourses NONE = new GeneratedCourses() {
        @Override
        public boolean live(String courseId, GenTag tag) {
            return tag == null;
        }

        @Override
        public boolean standing(GenTag tag) {
            return false;
        }

        @Override
        public String closedLine(String courseId) {
            Slots.Def slot = Slots.of(courseId);
            return GenCopy.closed(slot == null ? "That course" : slot.name());
        }

        @Override
        public long nextChangeAt() {
            return -1;
        }

        @Override
        public boolean inArea(String world, int x, int y, int z) {
            return false;
        }
    };
}
