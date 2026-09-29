package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.trial.Course;

/**
 * A planned time trial (parkour, Sky Rings, the ice boat): the course Time Trials runs unchanged,
 * and its reference (expert) time, from which the star times are set.
 *
 * @param course the course (start, checkpoints, finish, fall height, shortest time); its id is the
 *               slot and its {@code gen} is filled in at the flip
 * @param refMs  the reference time, in milliseconds
 */
public record PlannedTrial(Course course, long refMs) implements PlannedCourse {

    public PlannedTrial {
        if (course == null) {
            throw new IllegalArgumentException("a planned trial needs its course");
        }
    }
}
