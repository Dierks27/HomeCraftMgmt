package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import org.junit.jupiter.api.Test;

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
    static final class Host implements RoomHost {
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

        @Override
        public String meta(String key) {
            return meta.get(key);
        }

        @Override
        public void meta(String key, String value) {
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
        assertEquals(List.of("generated"), admin.tab(null, new String[]{"g"}), "a start");
        List<String> status = ClubhouseAdmin.status(room(), 2, "off");
        assertTrue(status.get(1).contains("generated room") && status.get(status.size() - 1).contains("2 in it"),
                status.toString());
        assertTrue(ClubhouseAdmin.status(null, 0, "games.clubhouse.enabled is false").get(1).contains("Not running"),
                "not running");
    }
}
