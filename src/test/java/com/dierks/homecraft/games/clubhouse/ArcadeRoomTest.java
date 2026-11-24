package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.pack.ClubModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 11, the Arcade Room filled out (the owner: "we need to take the mob race and plop it into the arcade!", "A
 * second track in the Arcade Room", "There were two Simon Says in the arcade though... Do we have all the games that
 * the kids can play in the arcade as it looks very empty"): the kid race's table, the same as the Parlor's and turned to
 * run north-south, with a clear, lit way all round it; every cabinet place and its standing place still free and lit;
 * the archway's way in clear; one cabinet for every kid game and The Floor Is Lava's, never one twice; the room's play
 * things; and where an edited, saved or owner-built room has its table.
 */
class ArcadeRoomTest {

    private static final ClubhouseSettings SETTINGS = ClubhouseSettings.defaults();
    private static final Box BOX = SETTINGS.box();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final Map<Long, String> B = blocks(SITE.plan());
    /** The Arcade Room's floor, where feet stand, and the table's top. */
    private static final int FLOOR = BOX.minY();
    private static final int FEET = FLOOR + 1;
    private static final int X0 = BOX.minX() + ClubhousePlanner.ARCADE_X0 + ClubhousePlanner.KID_TRACK_X0;
    private static final int X1 = X0 + RaceTrack.DEPTH - 1;
    private static final int Z0 = BOX.minZ() + ClubhousePlanner.KID_TRACK_Z0;
    private static final int Z1 = Z0 + RaceTrack.LENGTH - 1;
    private static final int LIT = 4;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static Map<Long, String> blocks(Plan plan) {
        Map<Long, String> out = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            out.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
        }
        return out;
    }

    private static String at(int x, int y, int z) {
        return B.get(key(x, y, z));
    }

    /** How far (x, z) is from the table: 0 on it, 1 on the way round it, and so on. */
    private static int out(int x, int z) {
        return Math.max(Math.max(X0 - x, x - X1), Math.max(Math.max(Z0 - z, z - Z1), 0));
    }

    private static boolean carpetOrAir(String block) {
        return block == null || ClubhousePlanner.id(block).endsWith("_carpet");
    }

    /** The plan's block light (as {@code ClubhousePlannerTest} floods it). */
    private static Map<Long, Integer> light() {
        Map<Long, Integer> light = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (BlockOp op : SITE.plan().ops()) {
            int l = ClubhousePlanner.lightOf(SITE.plan().blockOf(op));
            if (l > 0) {
                light.put(key(op.x(), op.y(), op.z()), l);
                q.add(new int[]{op.x(), op.y(), op.z(), l});
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (light.getOrDefault(key(c[0], c[1], c[2]), 0) > c[3] || c[3] <= 1) {
                continue;
            }
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                String n = B.get(key(x, y, z));
                if (!BOX.contains(x, y, z) || (n != null && !ClubhousePlanner.letsLight(n))) {
                    continue;
                }
                if (light.getOrDefault(key(x, y, z), 0) < c[3] - 1) {
                    light.put(key(x, y, z), c[3] - 1);
                    q.add(new int[]{x, y, z, c[3] - 1});
                }
            }
        }
        return light;
    }

    // ---- the table ----------------------------------------------------------------------------------------

    @Test
    void theTableIsTheParlorsTurnedNorthSouthWithItsLightAndItsBarrier() {
        Box room = ClubhousePlanner.arcadeInside(BOX);
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                String where = (x - BOX.minX()) + "," + (z - BOX.minZ());
                assertNotNull(at(x, FLOOR, z), "the floor under the table at " + where);
                assertEquals(ClubhousePlanner.TRACK_TABLE, at(x, FEET, z), "the table, one block high, at " + where);
                assertEquals(ClubhousePlanner.TRACK_LIGHT, at(x, FEET + 1, z), "the invisible light the racers run in");
                assertEquals(ClubhousePlanner.BARRIER, at(x, FEET + 2, z), "the barrier over it: nobody on the track");
                for (int y = FEET; y <= FEET + 2; y++) {
                    assertTrue(room.contains(x, y, z), "in the Arcade Room's air: " + where);
                }
            }
        }
        assertEquals(8, RaceTrack.LENGTH, "the same size as the Parlor's");
        assertEquals(3, RaceTrack.DEPTH);
        // the frame the show draws on: the table's north-east corner, facing south (its length), west across it
        RaceTable.Frame f = ClubhousePlanner.arcadeTable(BOX);
        assertEquals(RaceTable.Frame.standingOn(X1 + 0.5, FEET + 1, Z0 + 0.5, 0f), f,
                "the same track an owner standing on its north-east corner facing south would get");
        assertEquals(FEET, f.blockY());
        Set<Long> cells = new HashSet<>();
        for (int i = 0; i < RaceTrack.LENGTH; i++) {
            for (int k = 0; k < RaceTrack.DEPTH; k++) {
                int[] c = f.cell(i, k);
                assertEquals(0, out(c[0], c[1]), "cell " + i + "," + k + " is the table's");
                cells.add(key(c[0], 0, c[1]));
            }
        }
        assertEquals(24, cells.size(), "every block of the table, once");
        double[] foot = f.footprint();
        for (RaceTable.Piece p : RaceTable.pieces()) {
            double[] box = p.box(f);
            assertTrue(box[0] >= foot[0] - 1e-9 && box[0] + box[3] <= foot[2] + 1e-9, "on the table: " + p);
            assertTrue(box[2] >= foot[1] - 1e-9 && box[2] + box[5] <= foot[3] + 1e-9, "on the table: " + p);
            assertTrue(box[1] + box[4] <= FEET + 2 + 1e-9, "under the barrier: " + p);
        }
        for (int lane = 0; lane < Racer.COUNT; lane++) {
            for (double d = 0; d < RaceTrack.LAP; d += 0.25) {
                RaceTrack.Point p = RaceTrack.at(lane, d);
                double x = f.x(p.u(), p.w());
                double z = f.z(p.u(), p.w());
                assertTrue(x > foot[0] && x < foot[2] && z > foot[1] && z < foot[3], "a racer stays over the table");
            }
        }
        assertNotEquals(ClubhousePlanner.raceTable(BOX), f, "a second track: not the Parlor's");
        assertEquals(14, ClubhousePlanner.ALGO, "a block change: every server builds the room once more (14: the Parlor's"
                + " Hold'em table)");
        assertEquals("kidrace", RaceShow.KID_TAP, "its tap opens the kid race, never a machine of chance");
        assertFalse(RaceShow.KID_TAP.startsWith("machine:"));
    }

    @Test
    void thereIsAClearLitWayAllRoundTheTable() {
        Box room = ClubhousePlanner.arcadeInside(BOX);
        Map<Long, Integer> light = light();
        int cells = 0;
        for (int x = X0 - 1; x <= X1 + 1; x++) {
            for (int z = Z0 - 1; z <= Z1 + 1; z++) {
                if (out(x, z) != 1) {
                    continue;
                }
                cells++;
                String where = (x - BOX.minX()) + "," + (z - BOX.minZ());
                assertTrue(room.contains(x, FEET, z) && room.contains(x, FEET + 1, z), "in the room: " + where);
                String floor = at(x, FLOOR, z);
                assertTrue(floor != null && !ClubhousePlanner.noFooting(floor), "a floor to walk on at " + where);
                assertTrue(carpetOrAir(at(x, FEET, z)), "feet free (on the rug) at " + where);
                assertNull(at(x, FEET + 1, z), "head free at " + where);
                assertNull(at(x, FEET + 2, z), "room to jump at " + where);
                assertTrue(light.getOrDefault(key(x, FEET, z), 0) >= LIT, "lit at " + where);
            }
        }
        assertEquals(2 * (RaceTrack.LENGTH + 2) + 2 * RaceTrack.DEPTH, cells, "the whole ring round the table");
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                assertEquals(15, light.get(key(x, FEET + 1, z)), "the track itself in full light");
            }
        }
        // the bright rug lies on the way round, in two rings
        assertEquals(ClubhousePlanner.KID_RUG[0], at(X0 - 1, FEET, Z0 + 3));
        assertEquals(ClubhousePlanner.KID_RUG[1], at(X0 - 2, FEET, Z0 + 3));
        assertEquals(ClubhousePlanner.KID_RUG[0], at(X1 + 1, FEET, Z1 + 1), "corners too");
    }

    @Test
    void everyCabinetAndItsStandingPlaceAndTheWayInStayFreeAndLit() {
        Map<Long, Integer> light = light();
        Box room = ClubhousePlanner.arcadeInside(BOX);
        List<ClubhousePlanner.Place3> cabs = ClubhousePlanner.cabinets(BOX);
        assertEquals(13, cabs.size(), "plan 13: thirteen places, the eight kid games, The Floor Is Lava and four spares");
        assertEquals(13, ClubhousePlanner.CABINETS);
        List<int[]> before = new ArrayList<>();
        for (ClubhousePlanner.Place3 c : cabs) {
            int cx = (int) Math.floor(c.x());
            int cz = (int) Math.floor(c.z());
            double rad = Math.toRadians(c.yaw());
            int fx = (int) Math.floor(c.x() - Math.sin(rad));
            int fz = (int) Math.floor(c.z() + Math.cos(rad));
            before.add(new int[]{fx, fz});
            assertTrue(out(cx, cz) > 1 && out(fx, fz) > 1, "clear of the table and its way round: " + c);
            assertEquals(ClubhousePlanner.MAT, at(cx, FLOOR, cz), "on its mat");
            assertNull(at(cx, FEET, cz), "air for the cabinet");
            assertNull(at(cx, FEET + 1, cz), "two high");
            assertTrue(room.contains(fx, FEET, fz), "its standing place is in the room: " + c);
            assertNull(at(fx, FEET, fz), "its standing place is free: " + c);
            assertNull(at(fx, FEET + 1, fz));
            assertTrue(at(fx, FLOOR, fz) != null && !ClubhousePlanner.noFooting(at(fx, FLOOR, fz)));
            assertTrue(light.getOrDefault(key(fx, FEET, fz), 0) >= LIT, "and lit: " + c);
            assertTrue(light.getOrDefault(key(cx, FEET, cz), 0) >= LIT, "the cabinet's place lit too: " + c);
        }
        // the first nine places are where they were (the filling order is stable), the spares after them
        int[][] first = {{14, 36}, {1, 36}, {14, 39}, {1, 39}, {14, 42}, {1, 42}, {1, 33}, {14, 45}, {1, 45},
                {14, 48}, {1, 48}, {14, 51}, {1, 51}};
        for (int n = 0; n < first.length; n++) {
            assertEquals(BOX.minX() + first[n][0] + 0.5, cabs.get(n).x(), 1e-9);
            assertEquals(BOX.minZ() + first[n][1] + 0.5, cabs.get(n).z(), 1e-9);
        }
        // the archway's way in: its three columns from the archway down the room, nothing in the way
        for (int i = ClubhousePlanner.ARCH_ARCADE_X0; i < ClubhousePlanner.ARCH_ARCADE_X0 + ClubhousePlanner.ARCH_W; i++) {
            for (int k = ClubhousePlanner.SIDE_Z0; k < ClubhousePlanner.SIDE_Z1 - 2; k++) {
                int x = BOX.minX() + i;
                int z = BOX.minZ() + k;
                assertTrue(carpetOrAir(at(x, FEET, z)) && at(x, FEET + 1, z) == null, "the way in at " + i + "," + k);
                assertTrue(out(x, z) > 1, "clear of the table");
            }
        }
        // and a body walks from the archway to every standing place and all round the table
        WalkGrid grid = WalkGrid.of(SITE.plan());
        WalkGrid.Cell door = grid.at(BOX.minX() + ClubhousePlanner.ARCH_ARCADE_X0 + 1.5, FEET,
                BOX.minZ() + ClubhousePlanner.SIDE_Z0 + 0.5);
        assertNotNull(door, "the archway's end is on the grid");
        for (int[] f : before) {
            assertNotNull(grid.path(door, grid.at(f[0] + 0.5, FEET, f[1] + 0.5), Set.of()),
                    "a walk to the cabinet at " + (f[0] - BOX.minX()) + "," + (f[1] - BOX.minZ()));
        }
        for (int[] corner : new int[][]{{X0 - 1, Z0 - 1}, {X1 + 1, Z0 - 1}, {X0 - 1, Z1 + 1}, {X1 + 1, Z1 + 1}}) {
            WalkGrid.Cell c = grid.at(corner[0] + 0.5, FEET + RegularSpots.CARPET, corner[1] + 0.5);
            assertNotNull(c, "the way round is on the grid");
            assertNotNull(grid.path(door, c, Set.of()), "and reached from the archway");
        }
    }

    // ---- the cabinets ---------------------------------------------------------------------------------------

    @Test
    void everyKidGameHasExactlyOneCabinetAndNoneTwice() {
        List<String> shipped = SETTINGS.arcadeGames();
        List<String> room = KidSet.fill(shipped, KidSet.CABINET_GAMES, SideRooms.LAVA_GAME, ClubhousePlanner.CABINETS);
        assertEquals(ClubhousePlanner.OLD_CABINETS, room.size(), "every game has a cabinet, the nine places as before;"
                + " plan 13's four spares wait for more games: " + room);
        assertEquals(room, KidSet.fill(shipped, KidSet.CABINET_GAMES, SideRooms.LAVA_GAME,
                ClubhousePlanner.OLD_CABINETS), "the same games in the same places as a room at its old size");
        assertEquals(new HashSet<>(room).size(), room.size(), "no game twice: " + room);
        assertTrue(room.containsAll(KidSet.CABINET_GAMES), "every one of the eight kid games: " + room);
        assertEquals(8, KidSet.CABINET_GAMES.size());
        assertEquals(List.of("creeper_sweeper", "ore_merge", "snake", "mini_match", "simon_says", "whack_a_zombie",
                "connect_four", "tic_tac_toe").stream().sorted().toList(), KidSet.CABINET_GAMES.stream().sorted().toList());
        assertEquals(shipped, room.subList(0, shipped.size()), "the listed games keep their places");
        assertEquals(SideRooms.LAVA_GAME, room.get(shipped.size()), "The Floor Is Lava's cabinet where it was");
        assertEquals(SideRooms.lavaIndex(shipped.size(), ClubhousePlanner.CABINETS), shipped.size());
        assertEquals(1, room.stream().filter("simon_says"::equals).count(), "one Simon Says");
        // the owner lists all eight: lava gets the last place
        List<String> all = KidSet.fill(KidSet.CABINET_GAMES, KidSet.CABINET_GAMES, SideRooms.LAVA_GAME, 9);
        assertEquals(SideRooms.LAVA_GAME, all.get(8));
        // more places than games: never a repeat, the rest stay empty
        assertEquals(List.of("snake", SideRooms.LAVA_GAME, "simon_says"), KidSet.fill(List.of("snake", "snake"),
                List.of("snake", "simon_says"), SideRooms.LAVA_GAME, 9));
        assertEquals(List.of("snake"), KidSet.fill(List.of("snake"), List.of(), null, 9));
        assertEquals(List.of("snake", "simon_says"), KidSet.fill(List.of("snake", "simon_says", "mini_match"),
                List.of(), null, 2), "never more than the places");
        // each has its own model (block-atlas textures only) with a picture for players without the pack
        for (String g : KidSet.CABINET_GAMES) {
            ClubModels.Model m = ClubModels.cabinetFor(g);
            assertNotEquals("cabinet", m.name(), g + " has a cabinet of its own");
            assertEquals(ClubModels.Kind.CABINET, m.kind());
            assertNotNull(m.fallback(), g + "'s stand-in");
            assertTrue(m.screen().startsWith("minecraft:block/"), g + ": " + m.screen());
        }
        assertNotEquals("cabinet", ClubModels.cabinetFor(SideRooms.LAVA_GAME).name());
    }

    // ---- the play things ------------------------------------------------------------------------------------

    @Test
    void theRoomHasItsPlayThingsAndNothingToClimbInto() {
        Map<Long, Integer> light = light();
        int ax = BOX.minX() + ClubhousePlanner.ARCADE_X0;
        List<ClubhousePlanner.Prop> decor = ClubhousePlanner.decor(BOX, ClubhousePlanner.Rooms.BOTH);
        assertTrue(decor.stream().filter(p -> p.key().startsWith("arcade.ball.")).count() >= 100,
                "plan 13: a ball pit full of balls, now that there is room (GrownWingTest has the rest)");
        assertTrue(decor.stream().filter(p -> p.key().startsWith("arcade.plush.")).count() >= 16, "plushies on show");
        // the plush case: glass to its lit top, so nothing stands on it
        for (int[] c : ClubhousePlanner.PLUSH_CASE) {
            int x = ax + c[0];
            int z = BOX.minZ() + c[1];
            assertEquals(ClubhousePlanner.CABINET, at(x, FEET, z));
            assertEquals(ClubhousePlanner.ROOF_GLASS, at(x, FEET + 1, z));
            assertEquals(ClubhousePlanner.ROOF_GLASS, at(x, FEET + 2, z));
            assertEquals(15, ClubhousePlanner.lightOf(at(x, FEET + 3, z)), "a lit cap");
        }
        // the bean bags: a stair a child sits in, lit over it, air over that (nothing to climb into)
        for (int[] bag : ClubhousePlanner.BEAN_BAGS) {
            int x = ax + bag[0];
            int z = BOX.minZ() + bag[1];
            assertTrue(at(x, FEET, z).contains("_stairs"), "a bean bag");
            assertNull(at(x, FEET + 1, z));
            assertNull(at(x, FEET + 2, z));
            assertTrue(light.getOrDefault(key(x, FEET + 1, z), 0) >= LIT, "its top is lit");
        }
        // the coloured lamps over the rug's corners, on chains
        int lamps = 0;
        for (int x = X0 - 1; x <= X1 + 1; x += RaceTrack.DEPTH + 1) {
            for (int z = Z0 - 1; z <= Z1 + 1; z += RaceTrack.LENGTH + 1) {
                assertEquals(ClubhousePlanner.CHAIN, at(x, FLOOR + ClubhousePlanner.SIDE_TOP, z));
                assertTrue(ClubhousePlanner.lightOf(at(x, FLOOR + ClubhousePlanner.SIDE_TOP - 1, z)) == 15, "a lamp");
                lamps++;
            }
        }
        assertEquals(4, lamps);
        // and no new spawn spot anywhere in the room: every place a mob could stand is lit
        Box room = ClubhousePlanner.arcadeInside(BOX);
        for (int x = room.minX(); x <= room.maxX(); x++) {
            for (int z = room.minZ(); z <= room.maxZ(); z++) {
                for (int y = room.minY(); y <= room.maxY(); y++) {
                    String below = at(x, y - 1, z);
                    String here = at(x, y, z);
                    if (below == null || ClubhousePlanner.noFooting(below)) {
                        continue;
                    }
                    if (here != null && !carpetOrAir(here) && !ClubhousePlanner.id(here).equals("minecraft:light")) {
                        continue;
                    }
                    assertTrue(light.getOrDefault(key(x, y, z), 0) >= LIT, "lit at " + (x - BOX.minX()) + ","
                            + (y - BOX.minY()) + "," + (z - BOX.minZ()));
                }
            }
        }
    }

    // ---- the room: generated, edited, saved, owner-built ------------------------------------------------------

    private final ClubhouseRoomTest.Host host = new ClubhouseRoomTest.Host();

    private ClubhouseRoom room() {
        ClubhouseRoom r = new ClubhouseRoom(host, SETTINGS);
        r.start();
        return r;
    }

    private static void ready(ClubhouseRoom r) {
        for (int i = 0; i < 6_000 && r.phase() != ClubhouseRoom.Phase.READY; i++) {
            r.tick();
            if (i % 20 == 0) {
                r.second();
            }
        }
        assertEquals(ClubhouseRoom.Phase.READY, r.phase(), "reached: " + r.statusLines());
    }

    @Test
    void theGeneratedRoomHasItsTableAndAnOwnerBuiltOneOnlyWhereTheOwnerPlacesIt() {
        ClubhouseRoom r = room();
        assertNull(r.arcadeTrack(), "none before the room is built");
        ready(r);
        ClubhouseRoom.RaceSpot spot = r.arcadeTrack();
        assertNotNull(spot);
        assertEquals(ClubhousePlanner.arcadeTable(BOX), spot.frame());
        assertTrue(spot.built() && !spot.own(), "the generated room's own table, built and checked");
        assertEquals(ClubhousePlanner.TRACK_TABLE, host.world.at(X0, FEET, Z0), "written");
        assertEquals(ClubhousePlanner.BARRIER, host.world.at(X1, FEET + 2, Z1));
        assertTrue(r.inArcadeRoom("games", X0 - 1.5, FEET, Z0 + 2.5), "the way round is in the Arcade Room");
        assertEquals(ClubhousePlanner.CABINETS, r.generatedCabinetPlaces().size());
        assertEquals(4, r.cabinetPlaces(ClubhouseSettings.KID_GAMES).size(), "one place a game, never round again");

        // editing: the owner's place, looked for
        ClubhouseRoom.Place corner = new ClubhouseRoom.Place("games", BOX.minX() + 3.4, FEET, BOX.minZ() + 40.6, 0f);
        assertTrue(r.arcade("mob_races", corner).startsWith("&aThe Arcade Room's race track is drawn on the table"));
        assertTrue(r.edit().startsWith("&a"));
        ClubhouseRoom.RaceSpot own = r.arcadeTrack();
        assertTrue(own.own() && !own.built());
        assertEquals(RaceTable.Frame.standingOn(corner.x(), corner.y(), corner.z(), corner.yaw()), own.frame());
        assertTrue(r.arcade("mob_races", null).contains("cleared"));
        assertFalse(r.arcadeTrack().own() || r.arcadeTrack().built(), "HCM's place, looked for in an edited room");
        assertTrue(r.arcade("wheel", corner).startsWith("&c"), "only mob_races");

        // owner-built: none until placed
        ClubhouseRoom hand = room();
        hand.here(new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f));
        assertNull(hand.arcadeTrack(), "an owner-built room gets no table until one is placed");
        ClubhouseRoom.Place theirs = new ClubhouseRoom.Place("games", 110.2, 71, -30.8, 90f);
        hand.arcade("mob_races", theirs);
        assertEquals(RaceTable.Frame.standingOn(110.2, 71, -30.8, 90f), hand.arcadeTrack().frame());
        assertTrue(hand.inArcadeRoom("games", 112, 71, -30), "near the owner's table is in their Arcade Room");
        ClubhouseRoom again = room();
        assertEquals(hand.arcadeTrack(), again.arcadeTrack(), "kept across a restart");
        again.arcade("mob_races", null);
        assertNull(again.arcadeTrack());
    }
}
