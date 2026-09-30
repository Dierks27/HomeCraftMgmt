package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;

/** {@link CourseAdminTest}'s fake engine, for the golf editor's tests in another package. */
public final class CourseAdminTestAccess {

    private CourseAdminTestAccess() {
    }

    /** The engine as an editor sees it: {@code halves} in world "games" are kept. */
    public static GeneratedCourses keeping(Box... halves) {
        return CourseAdminTest.keeping(halves);
    }
}
