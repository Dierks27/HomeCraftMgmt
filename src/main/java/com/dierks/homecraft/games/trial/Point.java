package com.dierks.homecraft.games.trial;

/**
 * A position in a course's world, with no world attached and no Bukkit type: the unit the
 * checkpoint geometry, the fall rule and the speed checks work in, so all of them can be tested
 * without a server.
 */
public record Point(double x, double y, double z) {

    /** The straight-line distance to {@code other}. */
    public double distance(Point other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The distance to {@code other} across the ground (a boat floats a little above where it was set). */
    public double flatDistance(Point other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
