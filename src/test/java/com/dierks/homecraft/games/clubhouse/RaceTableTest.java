package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob Races' table in the Game Parlor (plan 10; the owner: "make the track as small as you can", "it would be fun to be
 * able to walk around it"): where it stands and what is clear round it, the track drawn on it and the lanes the racers
 * run in, the same track on a table the owner built, and what a saved or owner-built room gets.
 */
class RaceTableTest {

    private static final ClubhouseSettings SETTINGS = ClubhouseSettings.defaults();
    private static final Box BOX = SETTINGS.box();
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    /** The Parlor's floor (its carpet lies on it), where feet stand, and the table's top. */
    private static final int FLOOR = BOX.minY() + ClubhousePlanner.UP_FLOOR;
    private static final int FEET = FLOOR + 1;
    private static final int X0 = BOX.minX() + ClubhousePlanner.ARCADE_X0 + ClubhousePlanner.TRACK_X0;
    private static final int X1 = X0 + RaceTrack.LENGTH - 1;
    private static final int Z0 = BOX.minZ() + ClubhousePlanner.TRACK_Z0;
    private static final int Z1 = Z0 + RaceTrack.DEPTH - 1;

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

    private static boolean onTable(int x, int z) {
        return x >= X0 && x <= X1 && z >= Z0 && z <= Z1;
    }

    /** Whether (x, z) is on the way round the table: the ring of cells one block out from it. */
    private static boolean onTheWayRound(int x, int z) {
        return x >= X0 - 1 && x <= X1 + 1 && z >= Z0 - 1 && z <= Z1 + 1 && !onTable(x, z);
    }

    private static boolean carpetOrAir(String block) {
        return block == null || ClubhousePlanner.id(block).endsWith("_carpet");
    }

    // ---- the plan --------------------------------------------------------------------------------------------

    @Test
    void theTableIsAPlainBlockTableWithAirForTheRacersAndABarrierOverThat() {
        Map<Long, String> b = blocks(SITE.plan());
        Box parlor = ClubhousePlanner.parlorInside(BOX);
        assertEquals(8, RaceTrack.LENGTH, "8 long");
        assertEquals(3, RaceTrack.DEPTH, "3 deep");
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                String at = (x - BOX.minX()) + "," + (z - BOX.minZ());
                assertNotNull(b.get(key(x, FLOOR, z)), "the Parlor's floor is under the table at " + at);
                assertEquals(ClubhousePlanner.TRACK_TABLE, b.get(key(x, FEET, z)), "the table, one block high, at " + at);
                assertNull(b.get(key(x, FEET + 1, z)), "one block of air over it, where the racers run, at " + at);
                assertEquals(ClubhousePlanner.BARRIER, b.get(key(x, FEET + 2, z)), "an invisible barrier over that at "
                        + at + ": nobody stands on the track");
                for (int y = FEET; y <= FEET + 2; y++) {
                    assertTrue(parlor.contains(x, y, z), "all of it is in the Parlor's air: " + at + " y " + y);
                }
            }
        }
        // a player is 1.8 high: with the barrier there is one block of room over the table, so nobody fits on it,
        // and from the floor or from a card table's top (one block up) no jump reaches the barrier's top
        assertTrue(ClubhousePlanner.noFooting(ClubhousePlanner.BARRIER), "nothing spawns or stands on a barrier");
        assertTrue(ClubhousePlanner.letsLight(ClubhousePlanner.BARRIER), "and light passes it as it does air");
        assertFalse(ClubhousePlanner.noFooting(ClubhousePlanner.TRACK_TABLE), "the table itself is a solid block");
        // lanterns hang over its four corners
        for (int x : new int[]{X0, X1}) {
            for (int z : new int[]{Z0, Z1}) {
                assertEquals(ClubhousePlanner.HANGING, b.get(key(x, BOX.minY() + ClubhousePlanner.UP_TOP - 1, z)),
                        "a lantern over the table's corner " + (x - BOX.minX()) + "," + (z - BOX.minZ()));
                assertNotNull(b.get(key(x, BOX.minY() + ClubhousePlanner.UP_TOP, z)), "hung from a beam or a chain");
            }
        }
        // the table is centred on the room's middle line (the Parlor's air is 14 wide)
        assertEquals(parlor.minX() + parlor.maxX() + 1, X0 + X1 + 1, "as far from the west wall as from the east");
        // the frame the show draws on is this table: its corner, its top, east along it and south across
        RaceTable.Frame f = ClubhousePlanner.raceTable(BOX);
        assertEquals(new RaceTable.Frame(X0, FEET + 1, Z0, 1, 0, 0, 1), f);
        assertEquals(FEET, f.blockY(), "its blocks are one under its top");
        Set<Long> cells = new HashSet<>();
        for (int i = 0; i < RaceTrack.LENGTH; i++) {
            for (int k = 0; k < RaceTrack.DEPTH; k++) {
                int[] c = f.cell(i, k);
                assertTrue(onTable(c[0], c[1]) && cells.add(key(c[0], 0, c[1])), "cell " + i + "," + k);
            }
        }
        assertEquals(24, cells.size());
        assertEquals(10, ClubhousePlanner.ALGO, "plan 10: every server builds its Clubhouse once more for the table");
    }

    @Test
    void thereIsAClearWayAllRoundTheTable() {
        Map<Long, String> b = blocks(SITE.plan());
        Box parlor = ClubhousePlanner.parlorInside(BOX);
        int cells = 0;
        for (int x = X0 - 1; x <= X1 + 1; x++) {
            for (int z = Z0 - 1; z <= Z1 + 1; z++) {
                if (!onTheWayRound(x, z)) {
                    continue;
                }
                cells++;
                String at = (x - BOX.minX()) + "," + (z - BOX.minZ());
                assertTrue(parlor.contains(x, FEET, z) && parlor.contains(x, FEET + 1, z), "in the Parlor: " + at);
                String floor = b.get(key(x, FLOOR, z));
                assertTrue(floor != null && !ClubhousePlanner.noFooting(floor), "a floor to walk on at " + at);
                assertTrue(carpetOrAir(b.get(key(x, FEET, z))), "feet free (on the felt) at " + at);
                assertNull(b.get(key(x, FEET + 1, z)), "head free at " + at);
                assertNull(b.get(key(x, FEET + 2, z)), "and room to jump at " + at + ": the barrier is over the table"
                        + " only");
            }
        }
        assertEquals(2 * (RaceTrack.LENGTH + 2) + 2 * RaceTrack.DEPTH, cells, "the whole ring round the table");
    }

    @Test
    void theTableAndTheWayRoundItAreClearOfEverythingElseInTheParlor() {
        Map<Long, String> b = blocks(SITE.plan());
        // where visitors arrive, and the door out behind them
        ClubhouseSite.Spot arrival = ClubhousePlanner.parlorArrival(BOX);
        int ax = (int) Math.floor(arrival.x());
        int az = (int) Math.floor(arrival.z());
        assertFalse(onTable(ax, az) || onTheWayRound(ax, az), "the arrival spot is not on the table or the way round");
        assertEquals(Z0 - 2, az, "the arrival row, then one clear row, then the table");
        assertTrue(ax >= X0 && ax <= X1, "arriving, the table is straight ahead");
        assertEquals(0f, arrival.yaw(), "and they face it (south)");
        ClubhousePlanner.Place3 exit = ClubhousePlanner.parlorExit(BOX);
        assertTrue((int) Math.floor(exit.z()) < az, "the door out is behind the arrival spot, away from the table");
        assertFalse(onTheWayRound((int) Math.floor(exit.x()), (int) Math.floor(exit.z())));

        // every machine, its standing place in front of it, the two card tables and their chairs, the ticket booth
        List<ClubhousePlanner.Place3> machines = new ArrayList<>(ClubhousePlanner.parlorMachines(BOX));
        machines.addAll(ClubhousePlanner.parlorRow(BOX));
        machines.add(ClubhousePlanner.parlorTicket(BOX));
        assertEquals(6 + 5 + 1, machines.size());
        for (ClubhousePlanner.Place3 m : machines) {
            int mx = (int) Math.floor(m.x());
            int mz = (int) Math.floor(m.z());
            assertFalse(onTable(mx, mz) || onTheWayRound(mx, mz), "a machine is clear of the table and the way round"
                    + " it: " + m);
        }
        for (int[] w : ClubhousePlanner.PARLOR_WALL) { // the wall machines: a player stands on the cell in front
            int front = BOX.minX() + w[0] + (w[2] < 0 ? 1 : -1);
            int z = BOX.minZ() + w[1];
            assertFalse(onTable(front, z) || onTheWayRound(front, z), "a wall machine's standing place is its own: "
                    + w[0] + "," + w[1]);
            assertTrue(carpetOrAir(b.get(key(front, FEET, z))) && b.get(key(front, FEET + 1, z)) == null,
                    "and it is still free");
        }
        for (int[] row : ClubhousePlanner.PARLOR_ROW) { // the back row and the booth: a player stands north of each
            int z = BOX.minZ() + row[1] - 1;
            assertTrue(z > Z1 + 1, "the back row's standing places are south of the way round");
        }
        assertTrue(BOX.minZ() + ClubhousePlanner.PARLOR_TICKET[1] - 1 > Z1 + 1, "the booth's too");
        for (int[] t : ClubhousePlanner.PARLOR_TABLE) {
            int z = BOX.minZ() + t[1];
            assertEquals(Z1 + 3, z, "two clear rows between the track and the card tables");
            for (int dx = -1; dx <= 1; dx++) { // the table and the chair either side of it
                int x = BOX.minX() + t[0] + dx;
                assertNotNull(b.get(key(x, FEET, z)), "a card table or its chair stands where it did: " + x + "," + z);
                assertFalse(onTable(x, z) || onTheWayRound(x, z));
            }
            assertEquals(ClubhousePlanner.FELT_BLOCK, b.get(key(BOX.minX() + t[0], FEET, z)), "the card table");
            assertTrue(ClubhousePlanner.id(b.get(key(BOX.minX() + t[0] - 1, FEET, z))).endsWith("_stairs"), "a chair");
            assertTrue(ClubhousePlanner.id(b.get(key(BOX.minX() + t[0] + 1, FEET, z))).endsWith("_stairs"), "a chair");
        }
        // two clear columns between each end of the table and the machines' mats on the side walls
        for (int[] w : ClubhousePlanner.PARLOR_WALL) {
            int mx = BOX.minX() + w[0];
            assertTrue(Math.min(Math.abs(mx - X0), Math.abs(mx - X1)) == 3, "wall machine at " + w[0]);
        }
        // nothing but the Parlor's own floor cells was taken: the felt that lay where the table stands
        Map<Long, String> without = new HashMap<>(b);
        int lanterns = 0;
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                for (int y = FEET; y <= BOX.minY() + ClubhousePlanner.UP_TOP; y++) {
                    String was = without.remove(key(x, y, z));
                    lanterns += was != null && ClubhousePlanner.lightOf(was) > 0 ? 1 : 0;
                }
            }
        }
        assertEquals(4, lanterns);
        assertEquals(24 + 24 + 4 + 2 + RaceTrack.LENGTH, b.size() - without.size(), "the table, the barrier, four"
                + " lanterns, two chains and the beam over its north row (it was there): nothing else is in the"
                + " table's column");
    }

    // ---- the track drawn on it -------------------------------------------------------------------------------

    @Test
    void everyPieceOfTheModelLiesOnTheTableUnderTheBarrier() {
        List<RaceTable.Piece> pieces = RaceTable.pieces();
        assertTrue(pieces.size() > 100 && pieces.size() < 160, "a model, not a swarm: " + pieces.size());
        Map<String, Integer> made = new HashMap<>();
        boolean[][] surfaced = new boolean[RaceTrack.LENGTH][RaceTrack.DEPTH];
        for (RaceTable.Piece p : pieces) {
            assertTrue(p.u() >= 0 && p.u() + p.su() <= RaceTrack.LENGTH + 1e-9, "on the table along it: " + p);
            assertTrue(p.w() >= 0 && p.w() + p.sw() <= RaceTrack.DEPTH + 1e-9, "and across it: " + p);
            assertTrue(p.y() >= 0 && p.y() + p.sy() <= RaceTable.TALLEST + 1e-9, "on its top, under the barrier: " + p);
            assertTrue(p.su() > 0 && p.sy() > 0 && p.sw() > 0, "a box: " + p);
            String name = p.block().substring("minecraft:".length()).toUpperCase(java.util.Locale.ROOT);
            assertNotNull(org.bukkit.Material.getMaterial(name), "a block of this API: " + p.block());
            made.merge(p.block(), 1, Integer::sum);
            if (p.y() == 0 && p.sy() == RaceTable.SURFACE) {
                boolean ring = RaceTrack.onRing(p.u() + 0.5, p.w() + 0.5);
                assertEquals(ring ? RaceTable.CLAY : RaceTable.GRASS, p.block(), "clay on the ring, grass inside: " + p);
                surfaced[(int) p.u()][(int) p.w()] = true;
            } else {
                assertTrue(p.y() >= RaceTable.SURFACE - 1e-9, "everything else stands on the surface: " + p);
            }
        }
        for (int i = 0; i < RaceTrack.LENGTH; i++) {
            for (int k = 0; k < RaceTrack.DEPTH; k++) {
                assertTrue(surfaced[i][k], "the surface covers the table's block " + i + "," + k);
            }
        }
        assertEquals(18, made.get(RaceTable.CLAY), "the ring: 18 blocks of running surface");
        assertEquals(6, made.get(RaceTable.GRASS), "the infield: 6 of grass");
        assertTrue(made.get(RaceTable.WHITE) > 40, "white rails, inside and out");
        assertTrue(made.get(RaceTable.BLACK) >= 12, "a checkered line and gantry");
        assertTrue(made.containsKey(RaceTable.ROOF) && made.containsKey(RaceTable.STAND), "a grandstand with a roof");
        assertTrue(made.keySet().stream().filter(k -> k.contains("tulip") || k.contains("poppy") || k.contains("daisy")
                || k.contains("dandelion") || k.contains("cornflower") || k.contains("bluet") || k.contains("allium")
                || k.contains("orchid")).count() >= 6, "flowers on the grass: " + made.keySet());
        // in the world: each piece's box is inside the table's footprint, whichever way the table lies
        for (float yaw : new float[]{0f, 90f, 180f, -90f}) {
            RaceTable.Frame f = RaceTable.Frame.standingOn(100.5, 70.0, -40.5, yaw);
            double[] foot = f.footprint();
            for (RaceTable.Piece p : pieces) {
                double[] box = p.box(f);
                assertTrue(box[0] >= foot[0] - 1e-9 && box[0] + box[3] <= foot[2] + 1e-9, yaw + ": " + p);
                assertTrue(box[2] >= foot[1] - 1e-9 && box[2] + box[5] <= foot[3] + 1e-9, yaw + ": " + p);
                assertEquals(70.0 + p.y(), box[1], 1e-9);
                assertEquals(p.su() * p.sw(), box[3] * box[5], 1e-9, "turned, never stretched: " + p);
            }
        }
    }

    @Test
    void everyLaneStaysOnTheRingAndTheRacersRunAbreast() {
        assertEquals(2 * 6 + 2 * 1 + Math.PI, RaceTrack.LAP, 1e-9, "a lap: the long sides, the ends, four quarter turns");
        double widest = 0.3; // the widest racer drawn: a quarter of a 0.9-wide mob is under this
        for (int lane = 0; lane < Racer.COUNT; lane++) {
            double out = RaceTrack.laneOut(lane);
            assertTrue(out - widest / 2 >= 0 && out + widest / 2 <= 1.0, "lane " + lane + " keeps a racer's whole body"
                    + " over the ring: " + out);
            if (lane > 0) {
                assertTrue(out > RaceTrack.laneOut(lane - 1), "the lanes go outward");
            }
            RaceTrack.Point before = RaceTrack.at(lane, 0);
            assertEquals(RaceTrack.LINE_U, before.u(), 1e-9, "everyone starts on the line");
            assertEquals(RaceTrack.IN_W0 - out, before.w(), 1e-9, "in its own lane");
            assertEquals(0.0, before.heading(), 1e-9, "facing along the track");
            for (double d = 0.01; d <= 2 * RaceTrack.LAP; d += 0.01) {
                RaceTrack.Point p = RaceTrack.at(lane, d);
                assertTrue(RaceTrack.onRing(p.u(), p.w()), "lane " + lane + " at " + d + " is on the ring: " + p);
                assertTrue(p.u() >= 0.05 && p.u() <= RaceTrack.LENGTH - 0.05 && p.w() >= 0.05
                        && p.w() <= RaceTrack.DEPTH - 0.05, "and off the table's edge: " + p);
                double step = Math.hypot(p.u() - before.u(), p.w() - before.w());
                assertTrue(step <= 0.02 + 1e-9, "no jump along lane " + lane + " at " + d + ": " + step);
                double turn = Math.abs(p.heading() - before.heading());
                assertTrue(Math.min(turn, 360 - turn) <= 1.2, "it turns smoothly at " + d + ": " + turn);
                before = p;
            }
            RaceTrack.Point lap = RaceTrack.at(lane, RaceTrack.LAP);
            assertEquals(RaceTrack.LINE_U, lap.u(), 1e-9, "a lap ends where it began");
            assertEquals(RaceTrack.IN_W0 - out, lap.w(), 1e-9);
        }
        // the same distance puts every lane abreast: the same heading, on one line across the track
        for (double d = 0; d < RaceTrack.LAP; d += 0.037) {
            RaceTrack.Point in = RaceTrack.at(0, d);
            RaceTrack.Point outer = RaceTrack.at(Racer.COUNT - 1, d);
            assertEquals(in.heading(), outer.heading(), 1e-9, "abreast at " + d);
            double rad = Math.toRadians(in.heading());
            double along = (outer.u() - in.u()) * Math.cos(rad) + (outer.w() - in.w()) * Math.sin(rad);
            assertEquals(0.0, along, 1e-9, "neither is ahead of the other at " + d);
        }
        // the racers go round with the infield on their right: east along the north side in the generated room
        RaceTable.Frame f = ClubhousePlanner.raceTable(BOX);
        assertEquals(-90f, f.yaw(0), 1e-3, "along the line they face east");
        assertEquals(0f, f.yaw(90), 1e-3, "round the far end they face south");
        assertEquals(90f, f.yaw(180), 1e-3, "back along the south side, west");
        assertEquals(180f, f.yaw(270), 1e-3, "and round the near end, north");
        RaceTrack.Point start = RaceTrack.at(0, 0);
        assertEquals(X0 + RaceTrack.LINE_U, f.x(start.u(), start.w()), 1e-9, "the line is in the middle of the table");
        assertTrue(f.z(start.u(), start.w()) < Z0 + 1, "on its north side, facing where visitors arrive");
    }

    @Test
    void aTableTheOwnerStandsOnIsTheSameTrackTurnedTheirWay() {
        // standing on the generated table's north-west corner facing east is the generated table itself
        assertEquals(ClubhousePlanner.raceTable(BOX), RaceTable.Frame.standingOn(X0 + 0.3, FEET + 1, Z0 + 0.7, -90f));
        assertEquals(ClubhousePlanner.raceTable(BOX), RaceTable.Frame.standingOn(X0 + 0.5, FEET + 1.0, Z0 + 0.5, -80f),
                "the way they face is squared");
        int[][] ahead = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int[][] right = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}};
        float[] yaws = {0f, 90f, 180f, -90f};
        for (int n = 0; n < yaws.length; n++) {
            RaceTable.Frame f = RaceTable.Frame.standingOn(100.5, 70.0, -40.5, yaws[n]);
            assertEquals(70.0, f.top(), "the table's top is under their feet");
            assertEquals(69, f.blockY());
            Set<Long> cells = new HashSet<>();
            for (int i = 0; i < RaceTrack.LENGTH; i++) {
                for (int k = 0; k < RaceTrack.DEPTH; k++) {
                    int[] c = f.cell(i, k);
                    assertEquals(100 + i * ahead[n][0] + k * right[n][0], c[0], "yaw " + yaws[n] + " cell " + i + "," + k);
                    assertEquals(-41 + i * ahead[n][1] + k * right[n][1], c[1], "yaw " + yaws[n] + " cell " + i + "," + k);
                    cells.add(key(c[0], 0, c[1]));
                }
            }
            assertEquals(24, cells.size(), "8 the way they face, 3 to their right, from the block they stand on");
            assertEquals(yaws[n], f.yaw(0), 1e-3, "the racers set off the way the owner faced");
            double[] foot = f.footprint();
            assertEquals(RaceTrack.LENGTH * RaceTrack.DEPTH, (foot[2] - foot[0]) * (foot[3] - foot[1]), 1e-9);
            for (int lane = 0; lane < Racer.COUNT; lane++) {
                for (double d = 0; d < RaceTrack.LAP; d += 0.25) {
                    RaceTrack.Point p = RaceTrack.at(lane, d);
                    double x = f.x(p.u(), p.w());
                    double z = f.z(p.u(), p.w());
                    assertTrue(x > foot[0] && x < foot[2] && z > foot[1] && z < foot[3], "a racer stays over the table");
                }
            }
        }
    }

    // ---- what the board says ---------------------------------------------------------------------------------

    @Test
    void theLabelAndTheBoardSayWhatIsHappening() {
        assertEquals("&a&lMob Races", RaceShow.labelLine(MobRaces.Phase.IDLE, -1));
        assertEquals("&a&lMob Races &7· &e12 s", RaceShow.labelLine(MobRaces.Phase.GATHERING, 12),
                "while entries are taken, the seconds left");
        assertEquals("&a&lMob Races &7· &eracing", RaceShow.labelLine(MobRaces.Phase.RUNNING, -1));
        assertEquals("&a&lMob Races", RaceShow.labelLine(MobRaces.Phase.RESULT, -1));

        List<String[]> in = List.of(new String[]{"Alex", Racer.CHICKEN.label()}, new String[]{"Sam", Racer.FOX.label()});
        List<String> idle = RaceShow.boardLines(MobRaces.Phase.IDLE, -1, List.of(), null, null, List.of(), null);
        assertEquals(List.of("&fSix racers. Pick one.", "&7Tap the table to enter"), idle);
        assertTrue(RaceShow.boardLines(MobRaces.Phase.IDLE, -1, List.of(), null, null, List.of(), Racer.COW)
                .contains("&7Last winner: &a4 Cow"));
        List<String> gathering = RaceShow.boardLines(MobRaces.Phase.GATHERING, 7, in, null, null, List.of(), null);
        assertEquals(List.of("&eThe race starts in 7 s", "&fAlex &7on &c1 Chicken", "&fSam &7on &65 Fox",
                "&7Tap the table to enter too"), gathering, "the countdown and who is in on which racer");
        List<String> running = RaceShow.boardLines(MobRaces.Phase.RUNNING, -1, in, Racer.PIG, null, List.of(), null);
        assertEquals(List.of("&eThey're off!", "&7In front: &d2 Pig", "&fAlex &7on &c1 Chicken", "&fSam &7on &65 Fox"),
                running);
        assertEquals(List.of("&6The winner: &65 Fox", "&aSam picked it!"), RaceShow.boardLines(MobRaces.Phase.RESULT,
                -1, in, null, Racer.FOX, List.of("Sam"), Racer.FOX));
        assertEquals(List.of("&6The winner: &e6 Sheep", "&7Nobody picked it this time."),
                RaceShow.boardLines(MobRaces.Phase.RESULT, -1, in, null, Racer.SHEEP, List.of(), Racer.SHEEP));
        List<String[]> many = new ArrayList<>();
        for (int n = 0; n < RaceShow.BOARD_NAMES + 3; n++) {
            many.add(new String[]{"P" + n, Racer.of(n % Racer.COUNT).label()});
        }
        List<String> crowded = RaceShow.boardLines(MobRaces.Phase.GATHERING, 3, many, null, null, List.of(), null);
        assertTrue(crowded.contains("&7and 3 more"), "a full room still fits the board: " + crowded);
        assertEquals("machine:mob_races", RaceShow.TAP, "a tap on the table opens Mob Races as a machine's tap does");
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
    void theGeneratedRoomHasItsTrackBuiltAndTheRebuildWritesIt() {
        ClubhouseRoom r = room();
        assertNull(r.raceTrack(), "no track before the room is built and checked");
        ready(r);
        ClubhouseRoom.RaceSpot spot = r.raceTrack();
        assertNotNull(spot);
        assertEquals(ClubhousePlanner.raceTable(BOX), spot.frame());
        assertFalse(spot.own());
        assertTrue(spot.built(), "built and checked: the table is there");
        assertEquals(ClubhousePlanner.TRACK_TABLE, host.world.at(X0, FEET, Z0), "the table was written");
        assertEquals(ClubhousePlanner.BARRIER, host.world.at(X1, FEET + 2, Z1), "and its barrier");
        assertTrue(r.parlorMachines().stream().noneMatch(m -> m.game().equals("mob_races")), "it is not a machine");
        assertEquals(11, r.parlorMachines().size(), "the eleven machines are where they were");

        // a server updating from plan 9: its room stands without the table, and the boot's build writes just that
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                host.world.put(x, FEET, z, ClubhousePlanner.FELT);
                host.world.put(x, FEET + 2, z, "minecraft:air");
            }
        }
        for (int x : new int[]{X0, X1}) {
            host.world.put(x, BOX.minY() + ClubhousePlanner.UP_TOP, Z1, "minecraft:air");
            host.world.put(x, BOX.minY() + ClubhousePlanner.UP_TOP - 1, Z1, "minecraft:air");
        }
        long before = host.world.writes;
        ClubhouseRoom updated = room();
        ready(updated);
        assertEquals(24 + 24 + 4, host.world.writes - before, "the table, its barrier and the two lanterns on their"
                + " chains, and nothing else, are built");
        assertEquals(ClubhousePlanner.TRACK_TABLE, host.world.at(X0 + 3, FEET, Z0 + 1));
        assertTrue(updated.raceTrack().built());
    }

    @Test
    void anEditedOrSavedRoomHasATrackOnlyWhereItsBlocksOrItsOwnerPutOne() {
        ClubhouseRoom r = room();
        ready(r);
        // the owner's place is recorded any time, and used once they edit the room
        ClubhouseRoom.Place corner = new ClubhouseRoom.Place("games", BOX.minX() + 3.4, FEET + 1, BOX.minZ() + 43.6,
                180f);
        String said = r.upstairs("parlor", "mob_races", corner, true);
        assertTrue(said.startsWith("&aMob Races' track is drawn on the table you stand on"), said);
        assertTrue(said.contains("8 blocks the way you face, 3 to your right"), said);
        assertFalse(r.raceTrack().own(), "not editing: the generated room's own table");
        assertTrue(r.edit().startsWith("&a"));
        ClubhouseRoom.RaceSpot own = r.raceTrack();
        assertTrue(own.own() && !own.built(), "editing: the owner's place, and the table is theirs to have built");
        assertEquals(RaceTable.Frame.standingOn(corner.x(), corner.y(), corner.z(), corner.yaw()), own.frame());
        assertTrue(r.parlorMachines().stream().noneMatch(m -> m.game().equals("mob_races")), "still not a machine");
        assertEquals(11, r.parlorMachines().size());
        assertTrue(r.upstairs("parlor", "mob_races", null, true).contains("cleared"));
        ClubhouseRoom.RaceSpot plan = r.raceTrack();
        assertEquals(ClubhousePlanner.raceTable(BOX), plan.frame(), "cleared: HCM's place again");
        assertFalse(plan.own() || plan.built(), "but in a room being edited the table has to be looked for");

        // saved: the room keeps the blocks it has, so the table is only there if the save has it
        List<String> out = new ArrayList<>();
        r.save(true, out::addAll);
        ready(r);
        assertNotNull(r.savedId(), out.toString());
        ClubhouseRoom.RaceSpot saved = r.raceTrack();
        assertEquals(ClubhousePlanner.raceTable(BOX), saved.frame());
        assertFalse(saved.built(), "a saved room's blocks are the owner's: looked for, never taken for granted");
        // a place outside the Clubhouse's box is not used in a saved room
        r.upstairs("parlor", "mob_races", new ClubhouseRoom.Place("games", 5000, 70, 5000, 0f), true);
        assertFalse(r.raceTrack().own());
        // HCM's room again: its own table, known to be there
        assertTrue(r.generated(true).startsWith("&a"));
        ready(r);
        assertTrue(r.raceTrack().built() && !r.raceTrack().own());
    }

    @Test
    void anOwnerBuiltRoomHasATrackOnlyWhereTheOwnerPlacesOne() {
        ClubhouseRoom r = room();
        r.here(new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f));
        assertTrue(r.hand() && r.open());
        assertNull(r.raceTrack(), "none until it is placed");
        ClubhouseRoom.Place corner = new ClubhouseRoom.Place("games", 110.2, 71, -30.8, 90f);
        assertTrue(r.upstairs("parlor", "mob_races", corner, true).startsWith("&aMob Races' track"));
        ClubhouseRoom.RaceSpot spot = r.raceTrack();
        assertTrue(spot.own() && !spot.built());
        assertEquals(RaceTable.Frame.standingOn(110.2, 71, -30.8, 90f), spot.frame());
        assertEquals(70, spot.frame().blockY(), "the table is the block they stand on");
        assertTrue(r.parlorMachines().isEmpty(), "the track is not a machine");
        r.upstairs("parlor", "wheel", new ClubhouseRoom.Place("games", 105, 70, -25, 0f), true);
        assertEquals(1, r.parlorMachines().size());
        assertEquals("wheel", r.parlorMachines().get(0).game());
        ClubhouseRoom again = room(); // kept across a restart
        assertEquals(spot, again.raceTrack());
        assertTrue(again.upstairs("parlor", "mob_races", null, true).contains("cleared"));
        assertNull(again.raceTrack());
        assertTrue(r.upstairs("parlor", "pigs", corner, false).contains("mob_races"), "the command names it");
    }
}
