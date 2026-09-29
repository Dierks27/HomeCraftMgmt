package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.List;

/**
 * The generated Clubhouse (CLUBHOUSE-SPEC §1): the plan the box is built and verified to, and the
 * places in it the flows use: where visitors arrive, the podium, and the results board.
 *
 * <p>Pure and immutable; {@link ClubhousePlanner} makes it from the box alone (the room never
 * changes, so there is no seed and no week).
 *
 * @param plan    every block of the box (air everywhere else)
 * @param spawns  where visitors arrive, spread over the room so 16 never stack, each facing the podium
 * @param podium  the three pedestals' standing spots: 1st (middle, highest), 2nd, 3rd
 * @param board   where the results board (a text display) floats, in front of its dark panel
 * @param inside  the room's air: the walls, floor and roof are outside it
 */
public record ClubhouseSite(Plan plan, List<Spot> spawns, List<Spot> podium, Spot board, Box inside) {

    /**
     * A place to put a player (or the board): the feet and which way they face.
     *
     * @param yaw Minecraft yaw (0 = south, 180 = north)
     */
    public record Spot(double x, double y, double z, float yaw) {
    }

    public ClubhouseSite {
        if (plan == null || inside == null || board == null) {
            throw new IllegalArgumentException("a Clubhouse needs its plan, its room and its board");
        }
        spawns = List.copyOf(spawns == null ? List.of() : spawns);
        podium = List.copyOf(podium == null ? List.of() : podium);
    }

    /** The Clubhouse's box (the plan's half). */
    public Box box() {
        return plan.half();
    }

    /** Arrival spot {@code n}, round and round. */
    public Spot spawn(int n) {
        return spawns.get(Math.floorMod(n, spawns.size()));
    }

    /** Whether feet at (x, y, z) are in the room (not in a wall, the floor or the roof). */
    public boolean in(double x, double y, double z) {
        return inside.contains(x, y, z);
    }
}
