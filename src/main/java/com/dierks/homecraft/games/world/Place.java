package com.dierks.homecraft.games.world;

/**
 * A spot in a named world, with no Bukkit types: what the session state machine reasons about
 * (the start, the return point, a teleport's target). The Bukkit side converts to and from
 * {@code Location}.
 *
 * @param world the world's name
 */
record Place(String world, double x, double y, double z, float yaw, float pitch) {

    /** The same spot, facing nowhere in particular. */
    static Place of(String world, double x, double y, double z) {
        return new Place(world, x, y, z, 0f, 0f);
    }

    /** Whether both are in the same (named) world. */
    boolean sameWorld(Place other) {
        return other != null && world != null && world.equals(other.world);
    }

    /** Straight-line distance, or infinity between two worlds. */
    double distance(Place other) {
        if (!sameWorld(other)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Same world and every axis within {@code epsilon}: how a teleport is recognised as ours. */
    boolean near(Place other, double epsilon) {
        return sameWorld(other)
                && Math.abs(x - other.x) <= epsilon
                && Math.abs(y - other.y) <= epsilon
                && Math.abs(z - other.z) <= epsilon;
    }
}
