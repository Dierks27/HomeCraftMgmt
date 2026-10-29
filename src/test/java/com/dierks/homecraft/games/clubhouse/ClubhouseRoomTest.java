package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GenMetaDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse room (CLUBHOUSE-SPEC §1, §6): an empty unclaimed box is scanned, claimed, built and
 * verified; anyone's blocks in it close the Clubhouse without touching them until
 * {@code rebuild confirm}; a verify that fails closes it; a box too close to anything closes it; the
 * owner-built room is open with its arrival spot set and nothing built; {@code off} closes it for good
 * until {@code generated}; the admin's {@code tp} is refused until it is built and checked.
 */
class ClubhouseRoomTest {

    private static final ClubhouseSettings SETTINGS = ClubhouseSettings.defaults();
    private static final Box BOX = SETTINGS.box();

    /** The server as the room sees it: a fake world, a meta map, and who was moved out of the way. */
    static class Host implements RoomHost {
        final FakeWorldPort world = new FakeWorldPort("games");
        final Map<String, String> meta = new HashMap<>();
        final List<String> problems = new ArrayList<>();
        final List<Person> people = new ArrayList<>();
        final List<UUID> moved = new ArrayList<>();
        boolean loaded = true;
        long now;

        @Override
        public long now() {
            return now;
        }

        @Override
        public long nanoTime() {
            return 0L; // the build's time budget is never used up in a test
        }

        @Override
        public Logger logger() {
            return Logger.getAnonymousLogger();
        }

        @Override
        public String worldName() {
            return "games";
        }

        @Override
        public WorldPort world(String name) {
            return loaded && "games".equals(name) ? world : null;
        }

        /**
         * fx2-C #8: the live host keeps these in {@code hcm_meta} through {@link GenMetaDao}, which
         * refuses any key outside {@code gen.}; the fake refuses the same keys, so no test can pass on a
         * key the live server can't store.
         */
        @Override
        public String meta(String key) throws Exception {
            requireStorable(key);
            return meta.get(key);
        }

        @Override
        public void meta(String key, String value) throws Exception {
            requireStorable(key);
            if (value == null) {
                meta.remove(key);
            } else {
                meta.put(key, value);
            }
        }

        @Override
        public List<String> regionProblems(Box box, String world) {
            return problems;
        }

        @Override
        public List<Person> people() {
            return people;
        }

        @Override
        public boolean anyoneOnline() {
            return true;
        }

        @Override
        public double mspt() {
            return 20;
        }

        @Override
        public void toSafety(UUID player, String world) {
            moved.add(player);
        }

        private static void requireStorable(String key) {
            if (!GenMetaDao.allowed(key)) {
                throw new IllegalArgumentException("the live store refuses " + key);
            }
        }

        /** The worlds in games.worlds. */
        final java.util.Set<String> gamesWorlds = new java.util.HashSet<>(java.util.Set.of("games"));

        @Override
        public boolean gamesWorld(String name) {
            return name != null && gamesWorlds.contains(name);
        }
    }

    private final Host host = new Host();

    private ClubhouseRoom room() {
        ClubhouseRoom r = new ClubhouseRoom(host, SETTINGS);
        r.start();
        return r;
    }

    /** Tick until the room is {@code phase}, a second's check every 20 ticks. */
    private static void until(ClubhouseRoom r, ClubhouseRoom.Phase phase, int max) {
        for (int i = 0; i < max && r.phase() != phase; i++) {
            r.tick();
            if (i % 20 == 0) {
                r.second();
            }
        }
        assertEquals(phase, r.phase(), "reached: " + r.statusLines());
    }

    @Test
    void anEmptyUnclaimedBoxIsScannedClaimedBuiltAndVerified() {
        UUID stray = UUID.randomUUID();
        host.people.add(new Person(stray, "Stray", "games", BOX.minX() + 5, BOX.minY() + 2, BOX.minZ() + 5, null, null));
        ClubhouseRoom r = room();
        assertEquals(ClubhouseRoom.Phase.SCANNING, r.phase(), "an unclaimed box is only counted first");
        assertEquals(List.of(stray), host.moved, "anyone in the box who isn't a visitor is moved out of its way");
        assertFalse(r.open(), "nobody comes in before a verify");
        assertNull(r.spawn(0), "no arrival spot yet");
        until(r, ClubhouseRoom.Phase.READY, 3_000);
        assertTrue(r.open() && r.verified(), "built and checked: open");
        assertEquals(r.claimText(), host.meta.get(ClubhouseRoom.CLAIM_KEY), "the empty box was claimed");
        assertEquals(r.site().plan().ops().size(), host.world.count(BOX), "every block of the plan is there");
        assertNotNull(r.spawn(0), "arrival spots are handed out");
        assertTrue(r.guarded(), "the generated room's box is guarded");
        long writes = host.world.writes;
        ClubhouseRoom again = room();
        until(again, ClubhouseRoom.Phase.READY, 3_000);
        assertEquals(writes, host.world.writes, "a room already right is checked again without writing a block");
    }

    @Test
    void anyonesBlocksInAnUnclaimedBoxCloseItTouchingNothingUntilRebuildConfirm() {
        host.world.put(BOX.minX() + 3, BOX.minY() + 1, BOX.minZ() + 3, "minecraft:oak_planks");
        ClubhouseRoom r = room();
        until(r, ClubhouseRoom.Phase.CLOSED, 3_000);
        assertTrue(r.closedWhy().contains("1 block") && r.closedWhy().contains("rebuild confirm"), r.closedWhy());
        assertEquals(0, host.world.writes, "nothing of anyone's was touched");
        assertNull(host.meta.get(ClubhouseRoom.CLAIM_KEY), "and nothing claimed");
        assertTrue(r.rebuild(false).get(0).contains("rebuild confirm"), "without confirm it only says what it does");
        assertEquals(ClubhouseRoom.Phase.CLOSED, r.phase(), "and does nothing");
        assertTrue(r.rebuild(true).get(0).contains("being built"), r.statusLines().toString());
        until(r, ClubhouseRoom.Phase.READY, 3_000);
        assertEquals("minecraft:air", host.world.at(BOX.minX() + 3, BOX.minY() + 1, BOX.minZ() + 3),
                "confirmed: the block is cleared by the build");
    }

    @Test
    void aVerifyThatFailsClosesTheClubhouse() {
        host.meta.put(ClubhouseRoom.CLAIM_KEY, ClubhouseRoom.claimText("games", BOX));
        host.world.sticky.add(FakeWorldPort.pos(BOX.minX(), BOX.minY(), BOX.minZ())); // it never changes
        ClubhouseRoom r = room();
        assertEquals(ClubhouseRoom.Phase.BUILDING, r.phase(), "claimed: straight to the build");
        until(r, ClubhouseRoom.Phase.CLOSED, 5_000);
        assertTrue(r.closedWhy().contains("check failed"), r.closedWhy());
        assertFalse(r.open(), "closed: every flow goes back to today's");
        assertFalse(r.verified(), "and it isn't verified");
    }

    @Test
    void aBoxTooCloseToAnythingClosesIt() {
        host.problems.add("clubhouse is only 4 blocks from fresh_parkour's half A (they must be 32 apart)");
        ClubhouseRoom r = room();
        assertEquals(ClubhouseRoom.Phase.CLOSED, r.phase(), "before a block is written");
        assertTrue(r.closedWhy().contains("games.clubhouse.origin"), r.closedWhy());
        assertEquals(0, host.world.writes, "nothing written");
    }

    @Test
    void itWaitsForItsWorld() {
        host.loaded = false;
        ClubhouseRoom r = room();
        assertEquals(ClubhouseRoom.Phase.WAITING, r.phase(), "no world yet");
        host.loaded = true;
        r.second();
        assertEquals(ClubhouseRoom.Phase.SCANNING, r.phase(), "it starts once the world is there");
    }

    @Test
    void theOwnerBuiltRoomIsOpenWithItsArrivalSpotAndNothingBuilt() {
        ClubhouseRoom r = room();
        ClubhouseRoom.Place spawn = new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f);
        assertTrue(r.here(spawn).contains("Clubhouse now"), "here");
        assertEquals(ClubhouseRoom.Phase.READY, r.phase(), "open at once");
        assertTrue(r.hand() && !r.guarded(), "owner-built: nothing built or guarded");
        assertEquals(new ClubhouseSite.Spot(100.5, 70, -20.5, 90f), r.spawn(3), "everyone arrives where the owner stood");
        assertNull(r.podium(1), "no podium until it is set");
        assertTrue(r.podium(2, new ClubhouseRoom.Place("games", 101, 71, -21, 0f)).contains("Podium place 2"), "podium 2");
        assertTrue(r.podium(4, spawn).contains("1, 2 and 3"), "only three places");
        assertTrue(r.board(new ClubhouseRoom.Place("games", 102, 73, -25, 0f)).contains("board"), "board");
        assertEquals(new ClubhouseSite.Spot(101, 71, -21, 0f), r.podium(2), "set");
        assertFalse(r.handComplete(), "podium 1 and 3 still to set");
        assertTrue(r.contains("games", 110, 70, -10), "within 24 of the arrival spot is in the room");
        assertFalse(r.contains("games", 130, 70, -20), "further isn't");
        long writes = host.world.writes;
        ClubhouseRoom reread = room();
        assertTrue(reread.hand() && reread.open(), "the owner-built room is kept across a restart");
        assertEquals(new ClubhouseSite.Spot(101, 71, -21, 0f), reread.podium(2), "with its spots");
        assertEquals(writes, host.world.writes, "nothing built");
        assertTrue(reread.generated().contains("generated"), "back to the generated room");
        assertFalse(reread.hand(), "generated");
        assertEquals(ClubhouseRoom.Phase.SCANNING, reread.phase(), "built and checked first");
    }

    @Test
    void theOwnerBuiltRoomMustBeInAGamesWorld() {
        ClubhouseRoom r = room();
        String no = r.here(new ClubhouseRoom.Place("hub", 0.5, 70, 0.5, 0f));
        assertTrue(no.contains("Games world") && no.contains("hub"), "refused, with the world named: " + no);
        assertFalse(r.hand(), "still the generated room");
        assertTrue(r.here(new ClubhouseRoom.Place("games", 0.5, 70, 0.5, 0f)).contains("Clubhouse now"),
                "a room in a Games world is fine");
    }

    @Test
    void offClosesItUntilGeneratedAndStaysOffAcrossARestart() {
        ClubhouseRoom r = room();
        until(r, ClubhouseRoom.Phase.READY, 3_000);
        assertTrue(r.switchOff().contains("off"), "off");
        assertEquals(ClubhouseRoom.Phase.CLOSED, r.phase(), "closed");
        assertTrue(r.off(), "switched off");
        ClubhouseRoom later = room();
        assertEquals(ClubhouseRoom.Phase.CLOSED, later.phase(), "still off after a restart");
        later.generated();
        until(later, ClubhouseRoom.Phase.READY, 3_000);
        assertTrue(later.open(), "open again");
    }

    @Test
    void tpIsRefusedUntilTheRoomIsBuiltAndChecked() {
        ClubhouseRoom r = room();
        assertEquals(ClubhouseAdmin.TP_NOT_BUILT, ClubhouseAdmin.tpRefusal(r, true, "off"),
                "being built: nothing to stand on yet");
        until(r, ClubhouseRoom.Phase.READY, 3_000);
        assertNull(ClubhouseAdmin.tpRefusal(r, true, "off"), "built and checked: an admin may go and look");
        assertTrue(ClubhouseAdmin.tpRefusal(null, true, "games.clubhouse.enabled is false").contains("isn't running"),
                "not running");
        r.switchOff();
        assertEquals(ClubhouseAdmin.TP_NOT_BUILT, ClubhouseAdmin.tpRefusal(r, true, "off"), "off: refused");
    }

    @Test
    void theAdminsTabCompletionAndStatus() {
        ClubhouseAdmin admin = new ClubhouseAdmin(null);
        assertEquals(List.of("status", "tp", "here", "podium", "board", "generated", "rebuild", "off"),
                admin.tab(null, new String[]{""}), "every subcommand");
        assertEquals(List.of("confirm"), admin.tab(null, new String[]{"rebuild", "c"}), "rebuild confirm");
        assertEquals(List.of("1", "2", "3"), admin.tab(null, new String[]{"podium", ""}), "the three places");
        assertEquals(List.of("fame", "playing", "stats"), admin.tab(null, new String[]{"board", ""}),
                "0.38.3: the walls' boards");
        assertEquals(List.of("playing"), admin.tab(null, new String[]{"board", "p"}), "a start");
        assertEquals(List.of("generated"), admin.tab(null, new String[]{"g"}), "a start");
        List<String> status = ClubhouseAdmin.status(room(), 2, "off");
        assertTrue(status.get(1).contains("generated room") && status.get(status.size() - 1).contains("2 in it"),
                status.toString());
        assertTrue(ClubhouseAdmin.status(null, 0, "games.clubhouse.enabled is false").get(1).contains("Not running"),
                "not running");
    }

    /**
     * 0.38.3: an owner-built room's wall boards (this week's best, now playing, my stats) are saved under
     * {@code gen.} and read back after a restart, each facing the way the admin faced (squared); they are optional
     * (the check still asks only for the arrival spot, the podium and the results board); an unknown word is
     * refused; the generated room has all four on its walls.
     */
    @Test
    void theWallBoardsOfAnOwnerBuiltRoomAreSavedAndReadBack() {
        ClubhouseRoom generated = room();
        until(generated, ClubhouseRoom.Phase.READY, 3_000);
        for (String which : List.of(WallBoards.RESULTS, WallBoards.FAME, WallBoards.PLAYING, WallBoards.STATS)) {
            assertEquals(WallBoards.generated(which, BOX), generated.wall(which), "the generated room's " + which);
        }
        assertNull(generated.wall("podium"), "only the boards");

        ClubhouseRoom r = room();
        r.here(new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f));
        assertNull(r.wall(WallBoards.FAME), "not set: no board on that wall");
        assertNull(r.wall(WallBoards.RESULTS), "nor the results board");
        for (int n = 1; n <= 3; n++) {
            r.podium(n, new ClubhouseRoom.Place("games", 100 + n, 71, -21, 0f));
        }
        assertTrue(r.board("results", new ClubhouseRoom.Place("games", 102, 73, -25, 170f))
                .startsWith("&aThe results board hangs"), "board results is the results board");
        assertTrue(r.handComplete(), "complete without the walls' boards: they are optional");
        String fame = r.board("fame", new ClubhouseRoom.Place("games", 110, 72, -20, -95f));
        assertTrue(fame.startsWith("&aThe this week's best board hangs") && fame.contains("facing the way you face"),
                fame);
        assertTrue(r.board("playing", new ClubhouseRoom.Place("games", 100, 72, -10, 3f)).contains("now playing"),
                "now playing");
        assertTrue(r.board("STATS", new ClubhouseRoom.Place("games", 90, 72, -20, 88f)).contains("my stats"),
                "my stats (any case)");
        String no = r.board("podium", new ClubhouseRoom.Place("games", 90, 72, -20, 88f));
        assertTrue(no.startsWith("&c") && no.contains("fame, playing and stats"), "an unknown word: " + no);
        assertEquals("games;110.00;72.00;-20.00;-95.00", host.meta.get(ClubhouseRoom.BOARD_KEY + ".fame"),
                "saved as it was set");
        assertTrue(GenMetaDao.allowed(ClubhouseRoom.WALL_KEY + WallBoards.STATS), "under gen.: the live store keeps it");
        assertTrue(r.statusLines().stream().anyMatch(l -> l.startsWith("fame board games;110")), r.statusLines().toString());

        ClubhouseRoom later = room();
        assertEquals(new WallBoards.Wall(110, 72, -20, -90f, WallBoards.HAND_SCALE, WallBoards.WALL_WIDTH),
                later.wall(WallBoards.FAME), "read back after a restart, squared to east");
        assertEquals(0f, later.wall(WallBoards.PLAYING).yaw(), 0.001f, "south");
        assertEquals(90f, later.wall(WallBoards.STATS).yaw(), 0.001f, "west");
        assertEquals(180f, later.wall(WallBoards.RESULTS).yaw(), 0.001f, "the results board: north");
        assertEquals(WallBoards.LINE_WIDTH, later.wall(WallBoards.RESULTS).lineWidth(), "as the generated room's");
        assertTrue(later.handSpots().stream().noneMatch(l -> l.startsWith("fame")), "the check's spots are as before");

        host.meta.put(ClubhouseRoom.BOARD_KEY + ".stats", "hub;1;2;3;0");
        assertNull(room().wall(WallBoards.STATS), "a board in another world than the room's isn't drawn");
    }

    /**
     * fx2-C #8: every setting the room keeps goes through the live store ({@link GenMetaDao} on a real
     * SQLite, as {@code LiveRoomHost} does), which takes only keys under {@code gen.}: the start reads
     * them, and off, here, the podium, the board, generated and rebuild confirm are all saved and read
     * back after a restart.
     */
    @Test
    void everySettingIsSavedAndReadBackThroughTheLiveStore() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            GenMetaDao store = new GenMetaDao(Database.open(conn, Logger.getAnonymousLogger()));
            Host live = new Host() {
                @Override
                public String meta(String key) throws Exception {
                    return store.get(key);
                }

                @Override
                public void meta(String key, String value) throws Exception {
                    store.set(key, value);
                }
            };
            for (String key : List.of(ClubhouseRoom.CLAIM_KEY, ClubhouseRoom.MODE_KEY, ClubhouseRoom.OFF_KEY,
                    ClubhouseRoom.SPAWN_KEY, ClubhouseRoom.BOARD_KEY, ClubhouseRoom.PODIUM_KEY + "1")) {
                assertTrue(GenMetaDao.allowed(key), key + " must be a key the live store keeps (gen.)");
            }
            ClubhouseRoom r = new ClubhouseRoom(live, SETTINGS);
            r.start();
            assertEquals(ClubhouseRoom.Phase.SCANNING, r.phase(), "its settings were read at the start: "
                    + r.statusLines());
            assertTrue(r.switchOff().startsWith("&aThe Clubhouse is off"), "off is saved");
            ClubhouseRoom later = new ClubhouseRoom(live, SETTINGS);
            later.start();
            assertTrue(later.off() && later.phase() == ClubhouseRoom.Phase.CLOSED, "and still off after a restart");

            ClubhouseRoom.Place spawn = new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f);
            assertTrue(later.here(spawn).contains("Clubhouse now"), "here is saved");
            for (int n = 1; n <= 3; n++) {
                assertTrue(later.podium(n, new ClubhouseRoom.Place("games", 100 + n, 71, -21, 0f))
                        .contains("Podium place " + n), "podium " + n + " is saved");
            }
            assertTrue(later.board(new ClubhouseRoom.Place("games", 102, 73, -25, 0f)).startsWith("&aThe results"),
                    "the board is saved");
            ClubhouseRoom owners = new ClubhouseRoom(live, SETTINGS);
            owners.start();
            assertTrue(owners.hand() && owners.open() && owners.handComplete(),
                    "the owner-built room and all its spots come back after a restart: " + owners.handSpots());

            assertTrue(owners.generated().startsWith("&aBack to the generated"), "generated is saved");
            assertTrue(owners.switchOff().startsWith("&aThe Clubhouse is off"), "off again");
            assertTrue(owners.rebuild(true).get(0).contains("being built"), "rebuild confirm clears off and claims");
            ClubhouseRoom rebuilt = new ClubhouseRoom(live, SETTINGS);
            rebuilt.start();
            assertFalse(rebuilt.off() || rebuilt.hand(), "rebuild confirm's switches are kept after a restart");
            assertNull(store.get(ClubhouseRoom.OFF_KEY), "the off switch is gone from the store");
        }
    }
}
