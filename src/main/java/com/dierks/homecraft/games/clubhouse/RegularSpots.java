package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the Clubhouse's NPC regulars may be (pure): only places the generated room's plan already has, each checked
 * against the plan's own blocks, so a regular is never put in a wall, over the void or on a table.
 *
 * <ul>
 *   <li><b>bar</b>: the 19th Hole's stools (they sit and drink);</li>
 *   <li><b>hall</b>: the Great Hall's chairs and the armchairs by the fire and the books (they sit and look about);</li>
 *   <li><b>arcade</b>: in front of each cabinet in the Arcade Room; <b>trophy</b>: in front of each pedestal in the
 *       Trophy Room (Lilah's path, so regulars stand there too);</li>
 *   <li><b>slots</b>: in front of each of the Game Parlor's slot machines; <b>track</b>: beside Mob Races' table;
 *       <b>table</b>: the Twenty-One table's four chairs (only for NPCs sitting at that table). These are the Parlor's:
 *       drawn only for players who may go in.</li>
 * </ul>
 *
 * <p>A seat is the seat block itself (a chair or a stool) with air over it; a place to stand has air or a carpet at
 * the feet, air at the head and something solid to stand on.
 */
final class RegularSpots {

    /** What a regular does there. */
    enum Kind {
        BAR,
        HALL,
        ARCADE,
        TROPHY,
        SLOTS,
        TRACK,
        TABLE
    }

    /**
     * One place.
     *
     * @param x,y,z  where it stands (its feet) or sits (the seat's top)
     * @param yaw    the way it faces
     * @param sit    on a seat
     * @param parlor in the Game Parlor (drawn only for players who may go in)
     */
    record Spot(Kind kind, double x, double y, double z, float yaw, boolean sit, boolean parlor) {
    }

    /** A carpet's height: standing on one, the feet are this much over its block. */
    static final double CARPET = 1.0 / 16;

    private RegularSpots() {
    }

    /** Every block of {@code plan} by position ({@code null}: air). */
    static Map<Long, String> blocks(Plan plan) {
        Map<Long, String> out = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            out.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
        }
        return out;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The places in the generated room {@code site} with these rooms built, in a fixed order. */
    static List<Spot> of(ClubhouseSite site, ClubhousePlanner.Rooms rooms) {
        Box box = site.box();
        Map<Long, String> b = blocks(site.plan());
        Box parlor = rooms.parlor() ? ClubhousePlanner.parlorInside(box) : null;
        List<Spot> out = new ArrayList<>();
        for (ClubhousePlanner.Seat s : ClubhousePlanner.seats(box)) {
            int i = (int) Math.floor(s.x()) - box.minX();
            Kind k = "RED".equals(s.color()) && i == 5 ? Kind.BAR
                    : "GREEN".equals(s.color()) || "BROWN".equals(s.color()) ? Kind.HALL : null;
            if (k != null) {
                seat(out, b, k, s, false);
            }
        }
        if (rooms.arcade()) {
            for (ClubhousePlanner.Place3 c : ClubhousePlanner.cabinets(box)) {
                before(out, b, Kind.ARCADE, c, box.minY() + 1, false);
            }
            // plan 11: beside the kid race's table, on its rug, either side, watching the race
            int x0 = box.minX() + ClubhousePlanner.ARCADE_X0 + ClubhousePlanner.KID_TRACK_X0;
            int z0 = box.minZ() + ClubhousePlanner.KID_TRACK_Z0;
            for (int k = z0 + 1; k < z0 + RaceTrack.LENGTH; k += 3) {
                stand(out, b, Kind.ARCADE, x0 - 1, box.minY() + 1, k, -90f, false);
                stand(out, b, Kind.ARCADE, x0 + RaceTrack.DEPTH, box.minY() + 1, k + 1, 90f, false);
            }
        }
        if (rooms.trophy()) {
            for (ClubhousePlanner.Pedestal p : ClubhousePlanner.pedestals(box)) {
                before(out, b, Kind.TROPHY, p.trophy(), box.minY() + 1, false);
            }
        }
        if (parlor != null) {
            int floor = box.minY() + ClubhousePlanner.UP_FLOOR + 1;
            List<ClubhousePlanner.Place3> machines = new ArrayList<>(ClubhousePlanner.parlorRow(box));
            machines.addAll(ClubhousePlanner.parlorMachines(box).subList(0, ClubhousePlanner.PARLOR_WALL.length));
            for (ClubhousePlanner.Place3 m : machines) {
                before(out, b, Kind.SLOTS, m, floor, true);
            }
            int x0 = box.minX() + ClubhousePlanner.ARCADE_X0 + ClubhousePlanner.TRACK_X0;
            int north = box.minZ() + ClubhousePlanner.TRACK_Z0 - 1;
            int south = box.minZ() + ClubhousePlanner.TRACK_Z0 + RaceTrack.DEPTH;
            for (int x = x0; x < x0 + RaceTrack.LENGTH; x += 2) {
                stand(out, b, Kind.TRACK, x, floor, north, 0f, true);
                stand(out, b, Kind.TRACK, x + 1, floor, south, 180f, true);
            }
            List<ClubhousePlanner.Seat> chairs = ClubhousePlanner.parlorSeats(box);
            for (int n = 0; n < 4 && n < chairs.size(); n++) { // the first table is Twenty-One's
                seat(out, b, Kind.TABLE, chairs.get(n), true);
            }
        }
        return out;
    }

    /** The place in front of {@code thing} (one block the way it faces), facing it, at floor height {@code floor}. */
    private static void before(List<Spot> out, Map<Long, String> b, Kind k, ClubhousePlanner.Place3 thing, int floor,
                               boolean parlor) {
        double rad = Math.toRadians(thing.yaw());
        int x = (int) Math.floor(thing.x() - Math.sin(rad));
        int z = (int) Math.floor(thing.z() + Math.cos(rad));
        stand(out, b, k, x, floor, z, thing.yaw() + 180f, parlor);
    }

    private static void stand(List<Spot> out, Map<Long, String> b, Kind k, int x, int y, int z, float yaw,
                              boolean parlor) {
        double feet = standOn(b, x, y, z);
        if (feet >= 0) {
            out.add(new Spot(k, x + 0.5, y + feet, z + 0.5, wrap(yaw), false, parlor));
        }
    }

    private static void seat(List<Spot> out, Map<Long, String> b, Kind k, ClubhousePlanner.Seat s, boolean parlor) {
        int x = (int) Math.floor(s.x());
        int y = (int) Math.floor(s.y());
        int z = (int) Math.floor(s.z());
        if (seatBlock(b.get(key(x, y, z))) && b.get(key(x, y + 1, z)) == null) {
            out.add(new Spot(k, s.x(), s.y(), s.z(), s.yaw(), true, parlor));
        }
    }

    /**
     * Whether someone can stand in the cell (x, y, z): how high over the cell's bottom their feet are (0, or a
     * carpet's height), or -1 when they can't (something in the way, at the feet or the head, or nothing under them).
     */
    static double standOn(Map<Long, String> b, int x, int y, int z) {
        String feet = b.get(key(x, y, z));
        String head = b.get(key(x, y + 1, z));
        String below = b.get(key(x, y - 1, z));
        if (head != null) {
            return -1;
        }
        if (feet != null) {
            return ClubhousePlanner.id(feet).endsWith("_carpet") && below != null
                    && !ClubhousePlanner.noFooting(below) ? CARPET : -1;
        }
        return below != null && !ClubhousePlanner.noFooting(below) ? 0 : -1;
    }

    /** Whether a block is a seat: a chair (stairs) or a stool (a bottom slab). */
    static boolean seatBlock(String block) {
        if (block == null) {
            return false;
        }
        String id = ClubhousePlanner.id(block);
        return id.endsWith("_stairs") || (id.endsWith("_slab") && block.contains("type=bottom"));
    }

    static float wrap(float yaw) {
        float y = yaw % 360f;
        if (y > 180f) {
            y -= 360f;
        } else if (y <= -180f) {
            y += 360f;
        }
        return y;
    }
}
