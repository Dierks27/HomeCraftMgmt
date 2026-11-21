package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import com.dierks.homecraft.games.lava.rules.LavaRound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The running playroom, tick by tick on a fake world: the claim and the build that gate the door, a round of each
 * mode from the first tap to the end, what a touch of the lava does to a player in each, leaving, and above all that
 * the lava is gone on every way out: the round's end, the last player leaving, a stop at any phase, and a crash healed
 * by the next start.
 */
class LavaServiceTest {

    private static final String LAVA = FakeWorldPort.canonicalOf(LavaWriter.LAVA);
    private static final String GLOW = FakeWorldPort.canonicalOf(LavaWriter.GLOW);

    /** A room on a fake world, with quick phases: 2 s safe, 5 s warning, 2 s of lava, 2 s to drain. */
    private static final class Rig {
        final FakeWorldPort world = new FakeWorldPort(FakeLavaHost.WORLD);
        final FakeLavaHost host = new FakeLavaHost(world);
        LavaService service;
        long ticks;

        Rig() {
            this(4);
        }

        Rig(int stars) {
            LavaSettings d = LavaSettings.defaults();
            host.settings = new LavaSettings(true, d.origin(), d.maxPlayers(), stars, 2, 2, 3);
            service = new LavaService(host);
            host.onSessionEnd = id -> service.left(id);
            service.start();
        }

        Rig built() {
            for (int i = 0; i < 4000 && !service.open(); i++) {
                tick();
            }
            assertTrue(service.open(), "the room is built and checked: " + service.statusLines());
            return this;
        }

        void tick() {
            if (ticks % 20 == 0) {
                service.check();
            }
            ticks++;
            host.now += 50;
            service.tick();
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tick();
            }
        }

        /** Tick until the round is in {@code phase} (it must come within a few minutes). */
        void until(LavaRound.Phase phase) {
            for (int i = 0; i < 6000; i++) {
                LavaRound r = service.round();
                if (r != null && r.phase() == phase) {
                    return;
                }
                tick();
            }
            throw new AssertionError("never reached " + phase + ": " + service.statusLines());
        }

        /** What the game does for a player coming in: the refusal check, a session at the entry spot, then the join. */
        String join(FakeLavaHost.P p, LavaMode mode, boolean kid) {
            String why = service.joinRefusal(p.id, mode, kid);
            if (why != null) {
                return why;
            }
            LavaSite.Spot at = service.entrySpot();
            p.session = true;
            p.world = FakeLavaHost.WORLD;
            p.at(at.x(), at.y(), at.z());
            String late = service.joined(p.id, mode, kid);
            if (late != null) {
                p.session = false;
            }
            return late;
        }

        LavaSite site() {
            return service.site();
        }

        /** How many blocks of the box are not what the plan says. */
        int differences() {
            Box box = service.box();
            int n = 0;
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        String want = site().block(x, y, z);
                        String plan = want == null ? WorldPort.AIR : FakeWorldPort.canonicalOf(want);
                        if (!plan.equals(world.at(x, y, z))) {
                            n++;
                        }
                    }
                }
            }
            return n;
        }

        int count(String state) {
            int n = 0;
            for (String s : world.blocks.values()) {
                n += s.equals(state) ? 1 : 0;
            }
            return n;
        }

        /** Stand a player on the open floor between the footstool and the table. */
        void toFloor(FakeLavaHost.P p) {
            Box b = service.box();
            p.at(b.minX() + 16.5, b.minY() + 1, b.minZ() + 12.5);
        }

        /** Walk a player into every star that is out, one a tick. */
        int collectAll(FakeLavaHost.P p) {
            int n = 0;
            while (!host.stars.isEmpty() && service.round() != null && !service.round().over()) {
                double[] s = host.stars.values().iterator().next();
                int before = host.stars.size();
                p.at(s[0], service.box().minY() + 1, s[2]);
                tick();
                if (host.stars.size() >= before) {
                    break; // not collectable now (the lava came)
                }
                n++;
            }
            return n;
        }
    }

    // ---- the door: the claim and the build -------------------------------------------------------------

    @Test
    void theRoomIsClaimedBuiltAndCheckedBeforeAnyoneComesIn() {
        Rig rig = new Rig();
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        assertFalse(rig.service.open());
        assertEquals(LavaText.FIXING, rig.service.joinRefusal(lilah.id, LavaMode.LITTLE, true),
                "nobody is put into a room that isn't there yet");
        assertEquals(LavaText.FIXING, rig.service.joined(lilah.id, LavaMode.LITTLE, true));
        assertNull(rig.host.claim, "nothing is claimed before the box is seen to be empty");
        rig.built();
        assertEquals(LavaService.claimText(FakeLavaHost.WORLD, rig.service.box()), rig.host.claim, "the empty box"
                + " is claimed, then built");
        assertEquals("games,7312,160,8544,32,16,32", rig.host.claim);
        assertEquals(0, rig.differences(), "the box is exactly the plan");
        assertEquals(rig.site().plan().ops().size(), rig.world.blocks.size());
        assertEquals(0, rig.count(LAVA), "and the plan has no lava in it");
        assertEquals(0, rig.world.tickets.size(), "the build let its chunks go");
        assertNull(rig.service.joinRefusal(lilah.id, LavaMode.LITTLE, true), "now she may come in");
        assertNull(rig.service.closedWhy());
        assertTrue(rig.service.verified());
    }

    @Test
    void aBoxWithSomeoneElsesBlocksClosesTheGameUntilAnAdminClaimsIt() {
        Rig rig = new Rig();
        Box box = rig.service.box();
        rig.world.put(box.minX() + 5, box.minY() + 5, box.minZ() + 5, "minecraft:diamond_block");
        rig.ticks(200);
        assertFalse(rig.service.open());
        assertNotNull(rig.service.closedWhy(), "something is in the box: closed, touching nothing");
        assertTrue(rig.service.closedWhy().contains("1 block that"), rig.service.closedWhy());
        assertTrue(rig.service.closedWhy().contains("/hcm games lava claim confirm"), rig.service.closedWhy());
        assertEquals(1, rig.world.blocks.size(), "not a block was written");
        assertNull(rig.host.claim);
        FakeLavaHost.P p = rig.host.player("Sam");
        assertEquals(LavaText.CLOSED, rig.service.joinRefusal(p.id, LavaMode.GROUP, false));

        assertEquals("&eThe playroom's box isn't claimed yet (" + box.describe() + "): it is claimed at its first"
                + " build when it is empty.", rig.service.claim(false).get(0));
        List<String> said = rig.service.claim(true);
        assertEquals("&aClaimed.", said.get(0));
        assertTrue(said.get(1).contains("opens again"), said.toString());
        rig.built();
        assertEquals(0, rig.differences(), "the admin's claim clears what was there");
        assertTrue(rig.service.claim(false).get(0).startsWith("&aThe playroom's box is claimed"));
    }

    @Test
    void aBoxThatCrowdsAnotherPlaceIsNeverBuilt() {
        Rig rig = new Rig();
        rig.host.regionProblems = List.of("floor_is_lava is only 8 blocks from clubhouse (they must be 32 apart)");
        rig.ticks(100);
        assertFalse(rig.service.open());
        assertTrue(rig.service.closedWhy().contains("its box can't be used"), rig.service.closedWhy());
        assertTrue(rig.service.closedWhy().contains("games.floor_is_lava.origin"), rig.service.closedWhy());
        assertEquals(0, rig.world.writes, "not a block is written");
        rig.host.regionProblems = List.of();
        assertTrue(rig.service.requestRebuild().contains("opens again"));
        rig.built();
    }

    @Test
    void aClaimThatCantBeReadWaitsAndTriesAgain() {
        Rig rig = new Rig();
        rig.host.claimFails = true;
        rig.ticks(100);
        assertFalse(rig.service.open());
        assertNull(rig.service.closedWhy(), "the database is busy: it waits, it doesn't close");
        assertTrue(String.join("\n", rig.service.statusLines()).contains("its claim can't be read"));
        rig.host.claimFails = false;
        rig.built();
    }

    // ---- Little -------------------------------------------------------------------------------------

    @Test
    void littleFromOneTapToTheFireworks() {
        Rig rig = new Rig().built();
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        assertNull(rig.join(lilah, LavaMode.LITTLE, true), "one tap: she is in");
        assertEquals(rig.site().spawn(0).y(), lilah.y, "on the sofa: a safe spot");
        assertEquals(4, rig.world.tickets.size(), "the room's chunks are held while a round is on");
        rig.tick();
        LavaRound round = rig.service.round();
        assertEquals(LavaMode.LITTLE, round.mode());
        assertEquals(LavaRound.Phase.SAFE, round.phase(), "no waiting, no choosing: it has started");
        assertEquals(LavaHost.Kit.PLAY, lilah.kit, "only the way out in her hands");
        assertEquals(4, rig.host.stars.size(), "the stars are out");
        assertEquals(LavaText.starRow(0, 4), lilah.bar, "her bar is a row of stars, none gold yet");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.START));
        for (double[] s : rig.host.stars.values()) {
            assertEquals(rig.service.box().minY() + 1 + LavaService.STAR_UP, s[1], 1e-9, "floating over the floor");
        }

        assertEquals(2, collectTwo(rig, lilah), "she walks into two of them");
        assertEquals(LavaText.starRow(2, 4), lilah.bar);
        assertEquals(2, rig.host.stars.size());
        assertTrue(lilah.sounds.contains(LavaHost.Cue.STAR), "a chime for a star");
        assertTrue(rig.host.fx.contains(LavaHost.Fx.SPARKLE), "and a sparkle");

        // the warning: a rumble, rising notes, the floor glowing, embers
        rig.toFloor(lilah);
        rig.until(LavaRound.Phase.WARNING);
        rig.tick();
        assertTrue(lilah.sounds.contains(LavaHost.Cue.RUMBLE));
        assertTrue(lilah.titles.contains(LavaText.CLIMB_BIG), "arrows up: climb!");
        assertTrue(rig.count(GLOW) > 200, "the floor glows: " + rig.count(GLOW));
        assertEquals(0, rig.count(LAVA), "no lava yet");
        rig.ticks(60);
        assertTrue(lilah.sounds.stream().filter(c -> c == LavaHost.Cue.RISE).count() >= 5, "a rising sound");
        assertTrue(rig.host.fx.contains(LavaHost.Fx.EMBERS));
        assertEquals(rig.site().glowCells().size(), rig.count(GLOW), "the whole floor glows by now");

        // she never climbs: the lava comes, and she is put back on the nearest safe spot, unhurt
        int moves = lilah.teleportedTo.size();
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertEquals(rig.site().layer(1).size(), rig.count(LAVA), "real lava, one layer, wall to wall");
        assertEquals(0, rig.host.stars.size(), "the stars are put away while the floor is lava");
        assertEquals(moves + 1, lilah.teleportedTo.size(), "one touch, one move");
        LavaSite.Spot back = lilah.teleportedTo.get(lilah.teleportedTo.size() - 1);
        assertTrue(back.y() >= rig.service.box().minY() + 2, "onto dry ground: " + back);
        assertFalse(rig.site().touches(1, lilah.x, lilah.y, lilah.z), "she is not in the lava any more");
        assertTrue(Math.hypot(back.x() - (rig.service.box().minX() + 16.5), back.z() - (rig.service.box().minZ() + 12.5))
                < 6, "the nearest dry ground, not across the room: " + back);
        assertEquals(rig.site().facing(back.x(), back.z()), back.yaw(), "facing into the room");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.SIZZLE), "a sizzle");
        assertTrue(rig.host.fx.contains(LavaHost.Fx.SMOKE), "and a puff of smoke");
        assertEquals(0, lilah.sessionsEnded, "she is still playing: Little can't be lost");
        assertEquals(LavaText.starRow(2, 4), lilah.bar, "and her stars stay collected");
        assertTrue(lilah.calmed > 100, "fire, fall and breath are cleared from her every tick");

        // the lava drains; the stars she hasn't got come back
        rig.until(LavaRound.Phase.SAFE);
        assertEquals(0, rig.count(LAVA), "drained");
        assertEquals(0, rig.count(GLOW), "the floor is its own again");
        assertEquals(0, rig.differences(), "the room is exactly its plan between the lava's visits");
        assertEquals(2, rig.host.stars.size(), "two left to find");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.CLEAR));
        assertEquals(2, rig.service.round().round());

        // the last star: fireworks, a fanfare, and after a moment the big Play again
        assertEquals(2, rig.collectAll(lilah));
        assertTrue(rig.service.round().over(), "every star: the round is over");
        assertEquals(1, rig.host.scored.size());
        LavaResult result = rig.host.scored.get(0);
        assertEquals(LavaMode.LITTLE, result.mode());
        assertEquals(4, result.stars(), "the full count");
        assertEquals(List.of(lilah.id), result.players());
        assertEquals(4L, rig.host.bests.get(lilah.id + ":lava:little"), "her best is on Little's board");
        assertEquals(LavaHost.Kit.OVER, lilah.kit, "Play again and the door");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.WIN));
        assertTrue(lilah.titles.contains(LavaText.WIN_BIG));
        assertNull(lilah.bar);
        assertEquals(0, lilah.endScreens, "the party first");
        rig.ticks(LavaService.PARTY_TICKS + 1);
        assertTrue(rig.host.fx.stream().filter(f -> f == LavaHost.Fx.FIREWORKS).count() >= 5, "fireworks");
        assertEquals(1, lilah.endScreens, "then her end screen opens: one big Play again, and a door");
        assertEquals(0, lilah.sessionsEnded, "she stays in the room until she chooses");
        assertEquals(0, rig.differences());
    }

    private static int collectTwo(Rig rig, FakeLavaHost.P p) {
        int n = 0;
        for (int i = 0; i < 2; i++) {
            double[] s = rig.host.stars.values().iterator().next();
            p.at(s[0], rig.service.box().minY() + 1, s[2]);
            rig.tick();
            n++;
        }
        return n;
    }

    @Test
    void aChildWhoNeverMovesIsNeverLeftInTheLava() {
        Rig rig = new Rig().built();
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        rig.join(lilah, LavaMode.LITTLE, true);
        rig.tick();
        rig.toFloor(lilah);
        int wetTicks = 0;
        for (int round = 0; round < 3; round++) {
            rig.until(LavaRound.Phase.LAVA);
            while (rig.service.round().phase() == LavaRound.Phase.LAVA) {
                rig.tick();
                if (rig.site().touches(rig.service.writer().level(), lilah.x, lilah.y, lilah.z)) {
                    wetTicks++;
                }
            }
            rig.until(LavaRound.Phase.SAFE);
            rig.toFloor(lilah); // she wanders back onto the floor and stands there again
        }
        assertEquals(0, wetTicks, "she is never in the lava at the end of a tick");
        assertEquals(3, lilah.teleportedTo.size(), "put back once each time the lava came");
        assertFalse(rig.service.round().over(), "three visits of the lava and no stars: still not over, nothing lost");
        assertEquals(0, lilah.sessionsEnded);

        // the server's own word that she is in lava (a ledge the geometry didn't expect) puts her back too
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(LavaService.TOUCH_TICKS + LavaWriter.LAYER_TICKS + 1);
        LavaSite.Spot seat = rig.site().spawn(0);
        lilah.at(seat.x(), seat.y(), seat.z());
        lilah.inLava = true;
        int before = lilah.teleportedTo.size();
        rig.tick();
        assertEquals(before + 1, lilah.teleportedTo.size());
    }

    @Test
    void aSecondChildTappingJoinsTheSameRound() {
        Rig rig = new Rig().built();
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        FakeLavaHost.P max = rig.host.player("Max");
        rig.join(lilah, LavaMode.LITTLE, true);
        rig.ticks(30);
        LavaRound round = rig.service.round();
        assertNull(rig.join(max, LavaMode.LITTLE, true));
        assertTrue(round == rig.service.round(), "the same round");
        assertEquals(List.of(lilah.id, max.id), round.members());
        assertEquals(LavaHost.Kit.PLAY, max.kit);
        assertNotEquals(lilah.teleportedTo, max.x == lilah.x && max.z == lilah.z, "another spot on the sofa");
        assertEquals(LavaText.ALREADY_IN, rig.service.joinRefusal(max.id, LavaMode.LITTLE, true));
        rig.collectAll(max);
        rig.collectAll(lilah);
        assertTrue(round.over());
        assertEquals(List.of(lilah.id, max.id), rig.host.scored.get(0).players(), "they finished it together");
        assertEquals(4L, rig.host.bests.get(max.id + ":lava:little"));
        assertEquals(4L, rig.host.bests.get(lilah.id + ":lava:little"));
    }

    private static void assertNotEquals(Object a, boolean same, String why) {
        assertFalse(same, why);
    }

    // ---- leaving, stopping, crashing: the lava is always gone ---------------------------------------------

    @Test
    void leavingMidRoundFreesThePlayerAndEndsAnEmptyRound() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        rig.join(a, LavaMode.LITTLE, false);
        rig.join(b, LavaMode.LITTLE, false);
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(10);
        assertTrue(rig.count(LAVA) > 0);
        LavaRound round = rig.service.round();

        rig.service.left(a.id);
        assertTrue(round == rig.service.round(), "one left: the round goes on for the other");
        assertFalse(round.isMember(a.id), "and the one who left is free of it");
        assertNull(a.bar, "their bar is gone");
        assertTrue(rig.count(LAVA) > 0);

        rig.service.left(b.id);
        assertNull(rig.service.round(), "nobody left: the round is over and gone");
        assertEquals(0, rig.count(LAVA), "and the lava with it, at once");
        assertEquals(0, rig.count(GLOW));
        assertEquals(0, rig.differences(), "the room is its plan again");
        assertEquals(0, rig.host.stars.size());
        assertNull(b.bar);
        assertEquals(0, rig.host.scored.size(), "a round nobody finished records nothing");
        assertEquals(0, rig.world.tickets.size(), "the chunks are let go");
        rig.ticks(200);
        assertTrue(rig.service.open(), "the empty room was checked against its plan again, and is open");
        assertEquals(0, rig.world.tickets.size());
        assertNull(rig.join(a, LavaMode.GROUP, false), "the room is free for anything now");
        rig.service.left(a.id);
        rig.service.left(a.id); // twice is nothing
        assertNull(rig.service.round());
    }

    @Test
    void aStopAtAnyPhaseInAnyModeLeavesNoLavaAndTheRoomAsPlanned() {
        for (LavaMode mode : LavaMode.values()) {
            for (LavaRound.Phase phase : new LavaRound.Phase[]{LavaRound.Phase.GATHER, LavaRound.Phase.SAFE,
                    LavaRound.Phase.WARNING, LavaRound.Phase.LAVA, LavaRound.Phase.DRAIN}) {
                for (int extra : new int[]{0, 1, 3, 25, 55}) {
                    if ((phase == LavaRound.Phase.GATHER && mode == LavaMode.LITTLE)
                            || (phase == LavaRound.Phase.DRAIN && mode == LavaMode.CHALLENGE)) {
                        continue; // Little doesn't gather; Challenge never drains
                    }
                    Rig rig = new Rig().built();
                    FakeLavaHost.P p = rig.host.player("Alex");
                    rig.join(p, mode, false);
                    if (phase != LavaRound.Phase.GATHER) {
                        rig.service.startNow(p.id);
                    }
                    if (mode == LavaMode.CHALLENGE && phase != LavaRound.Phase.GATHER) {
                        // a second round of Challenge: lava is already standing in the room during its safe phase
                        rig.until(LavaRound.Phase.LAVA);
                        LavaSite.Spot high = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, p.x, p.y, p.z);
                        p.at(high.x(), high.y(), high.z());
                        rig.until(LavaRound.Phase.SAFE);
                    }
                    rig.until(phase);
                    rig.ticks(extra);
                    String at = mode + " in " + rig.service.round().phase() + " +" + extra;
                    rig.service.stop();
                    assertEquals(0, rig.count(LAVA), "no lava after a stop: " + at);
                    assertEquals(0, rig.count(GLOW), "no glowing floor after a stop: " + at);
                    assertEquals(0, rig.differences(), "the room is exactly its plan after a stop: " + at);
                    assertEquals(0, rig.host.stars.size(), "no stars left out: " + at);
                    assertNull(p.bar, "no bar left up: " + at);
                    assertEquals(0, rig.world.tickets.size(), "no chunk held: " + at);
                }
            }
        }
    }

    @Test
    void aCrashMidLavaIsHealedByTheNextStartBeforeAnyoneComesIn() {
        Rig rig = new Rig().built();
        FakeLavaHost.P p = rig.host.player("Alex");
        rig.join(p, LavaMode.CHALLENGE, false);
        rig.service.startNow(p.id);
        LavaSite.Spot high = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, p.x, p.y, p.z);
        p.at(high.x(), high.y(), high.z());
        for (int i = 0; i < 4000 && rig.service.writer().level() < 3; i++) {
            rig.tick();
        }
        int left = rig.count(LAVA);
        assertTrue(left > 1500, "three layers of lava standing in the room: " + left);
        // the server dies here: nothing is stopped, nothing is put away. The next start:
        p.session = false;
        p.at(0.5, 64, 0.5);
        rig.service = new LavaService(rig.host);
        rig.service.start();
        FakeLavaHost.P next = rig.host.player("Blake");
        assertEquals(LavaText.FIXING, rig.service.joinRefusal(next.id, LavaMode.LITTLE, true),
                "nobody comes in before the room has been checked in this run");
        rig.built();
        assertEquals(0, rig.count(LAVA), "the build's check took every block of it away");
        assertEquals(0, rig.differences());
        assertNull(rig.join(next, LavaMode.LITTLE, true));
    }

    @Test
    void theEmptyRoomIsCheckedAgainAndHealsWhateverChanged() {
        Rig rig = new Rig().built();
        FakeLavaHost.P p = rig.host.player("Alex");
        rig.join(p, LavaMode.LITTLE, false);
        rig.ticks(40);
        Box box = rig.service.box();
        rig.world.put(box.minX() + 20, box.minY() + 9, box.minZ() + 20, LavaWriter.LAVA); // something went wrong
        rig.world.put(box.minX() + 3, box.minY(), box.minZ() + 20, "minecraft:air");
        assertEquals(2, rig.differences());
        rig.service.left(p.id);
        p.session = false;
        p.at(0.5, 64, 0.5);
        rig.ticks(300);
        assertEquals(0, rig.differences(), "the check that follows an emptied room puts it right");
        assertTrue(rig.service.open());
    }

    // ---- Group --------------------------------------------------------------------------------------

    @Test
    void groupOneSharedCountATouchCostsAStarAndTheLavaRisesEveryRound() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        assertNull(rig.join(a, LavaMode.GROUP, false));
        rig.ticks(5);
        assertEquals(LavaRound.Phase.GATHER, rig.service.round().phase(), "it waits a moment for others");
        assertEquals(LavaHost.Kit.GATHER, a.kit, "with a Start now in hand");
        assertTrue(a.bar.contains("Group starts in"), a.bar);
        assertEquals(0, rig.host.stars.size());
        assertNull(rig.join(b, LavaMode.GROUP, false), "another joins during the wait");
        rig.service.startNow(b.id);
        LavaRound round = rig.service.round();
        assertEquals(LavaRound.Phase.SAFE, round.phase(), "anyone may start it");
        assertEquals(LavaHost.Kit.PLAY, a.kit);
        assertEquals(4, rig.host.stars.size());

        assertEquals(4, rig.collectAll(a));
        assertEquals(4, round.stars());
        assertEquals(LavaText.groupBar(4, 1, 3), b.bar, "one count on everyone's bar, and the rounds as dots");

        // round 1: one deep. Blake stays on the floor: put back, and the group loses a star
        rig.toFloor(b);
        LavaSite.Spot seat = rig.site().spawn(0);
        a.at(seat.x(), seat.y(), seat.z());
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertEquals(1, rig.service.writer().level());
        assertEquals(3, round.stars(), "a touch costs the group one star");
        assertTrue(b.heard().contains("[bar] " + LavaText.LOST_STAR));
        assertEquals(1, b.teleportedTo.size(), "and puts the player on the nearest safe spot");
        assertEquals(0, a.teleportedTo.size(), "the one on the sofa is dry");
        assertEquals(0, b.sessionsEnded, "nobody is out in Group");

        // rounds 2 and 3: the lava a block higher each time; from the seat (feet 3) Alex is wet at level 3
        rig.until(LavaRound.Phase.DRAIN);
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(2 * LavaWriter.LAYER_TICKS + 2);
        assertEquals(2, rig.service.writer().level(), "round 2: two deep");
        assertEquals(rig.site().layer(1).size() + rig.site().layer(2).size(), rig.count(LAVA));
        assertEquals(0, a.teleportedTo.size(), "the seat is still dry");
        rig.until(LavaRound.Phase.DRAIN);
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(3 * LavaWriter.LAYER_TICKS + 2);
        assertEquals(3, rig.service.writer().level(), "round 3: three deep");
        assertEquals(1, a.teleportedTo.size(), "now the seat is under: the low furniture goes first");
        assertTrue(a.y >= rig.service.box().minY() + 4, "and Alex is put on ground that is dry at this level");

        // after the last round's drain: the total, on Group's board, for both
        for (int i = 0; i < 2000 && !round.over(); i++) {
            rig.tick();
        }
        assertTrue(round.over());
        LavaResult result = rig.host.scored.get(0);
        assertEquals(LavaMode.GROUP, result.mode());
        assertEquals(round.stars(), result.stars());
        assertEquals(3, result.rounds());
        assertEquals((long) result.stars(), rig.host.bests.get(a.id + ":lava:group"));
        assertEquals((long) result.stars(), rig.host.bests.get(b.id + ":lava:group"));
        assertEquals(0, rig.count(LAVA));
        assertEquals(0, rig.differences());
        assertTrue(a.heard().contains("Together you collected"), a.heard());
    }

    // ---- Challenge ----------------------------------------------------------------------------------

    @Test
    void challengeTheLavaNeverDrainsATouchPutsYouOutAndTheLastOneStandingWins() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        rig.join(a, LavaMode.CHALLENGE, false);
        rig.join(b, LavaMode.CHALLENGE, false);
        rig.service.startNow(a.id);
        LavaRound round = rig.service.round();
        assertEquals(0, rig.host.stars.size(), "no stars: only the climb");
        assertTrue(a.bar.startsWith("&cRound 1"), a.bar);
        FakeLavaHost.P late = rig.host.player("Casey");
        assertEquals(LavaText.CHALLENGE_GOING, rig.join(late, LavaMode.CHALLENGE, false), "nobody joins once it started");

        // Alex climbs to the top shelf; Blake stays on the sofa's seat (feet 3)
        LavaSite.Spot top = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, a.x, a.y, a.z);
        a.at(top.x(), top.y(), top.z());
        rig.until(LavaRound.Phase.LAVA);
        rig.until(LavaRound.Phase.SAFE);
        assertEquals(2, round.round());
        assertEquals(1, rig.service.writer().level(), "the lava stays: it never drains");
        assertEquals(rig.site().layer(1).size(), rig.count(LAVA));
        assertEquals(List.of(a.id, b.id), round.alive(), "the seat is dry at one deep");
        rig.until(LavaRound.Phase.LAVA);
        rig.until(LavaRound.Phase.SAFE);
        assertEquals(2, rig.service.writer().level());
        assertEquals(List.of(a.id, b.id), round.alive(), "and at two");

        // round 3: three deep. The seat goes under and Blake is out: to the gallery, to watch. The first layer comes
        // the tick the lava does, so the round can be over in that same tick
        for (int i = 0; i < 2000 && !round.over() && round.phase() != LavaRound.Phase.LAVA; i++) {
            rig.tick();
        }
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertTrue(round.over(), "one left standing: it is over");
        assertEquals(2, b.teleportedTo.size(), "up to the gallery, then down again for the party");
        LavaSite.Spot watched = b.teleportedTo.get(0);
        assertTrue(rig.site().inGallery(watched.x(), watched.y(), watched.z()), "out: to the gallery");
        assertFalse(rig.site().inGallery(b.x, b.y, b.z), "and down for the end");
        assertTrue(b.heard().contains("The lava got you!"), b.heard());
        assertTrue(b.sounds.contains(LavaHost.Cue.OUT));
        assertEquals(0, b.sessionsEnded, "out, but still in the room");
        LavaResult result = rig.host.scored.get(0);
        assertEquals(List.of(a.id), result.winners());
        assertEquals(2, result.standing(b.id).place());
        assertEquals(2, result.standing(b.id).survived(), "Blake lasted two rounds");
        assertEquals(2L, rig.host.bests.get(b.id + ":lava:challenge"));
        assertEquals(2L, rig.host.bests.get(a.id + ":lava:challenge"), "the winner's rounds too");
        assertTrue(a.heard().contains("You're the last one standing!"), a.heard());
        assertTrue(b.heard().contains("Last one standing: &eAlex"), b.heard());
        assertEquals(0, rig.count(LAVA), "the lava is gone the moment it ends");
        assertEquals(0, rig.differences());
        assertEquals(LavaHost.Kit.OVER, b.kit);
    }

    @Test
    void theOneWhoIsOutWatchesFromTheGalleryAndComesDownAtTheEnd() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        FakeLavaHost.P c = rig.host.player("Casey");
        for (FakeLavaHost.P p : List.of(a, b, c)) {
            rig.join(p, LavaMode.CHALLENGE, false);
        }
        rig.service.startNow(a.id);
        LavaRound round = rig.service.round();
        for (FakeLavaHost.P p : List.of(a, b)) {
            LavaSite.Spot top = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, p.x, p.y, p.z);
            p.at(top.x(), top.y(), top.z());
        }
        rig.toFloor(c);
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertFalse(round.over(), "two still standing");
        assertEquals(List.of(a.id, b.id), round.alive());
        assertTrue(rig.site().inGallery(c.x, c.y, c.z), "out: up to the gallery, to watch");
        assertEquals(LavaHost.Kit.WATCH, c.kit);
        assertTrue(c.heard().contains(LavaText.WATCHING));
        assertTrue(a.bar.endsWith("2 standing"), a.bar);
        // the lava rises to the flood: the two on the top shelf go under together
        for (int i = 0; i < 20_000 && !round.over(); i++) {
            rig.tick();
            assertFalse(rig.site().touches(rig.service.writer().level(), c.x, c.y, c.z), "the gallery stays dry");
        }
        assertTrue(round.over());
        LavaResult result = rig.host.scored.get(0);
        assertEquals(List.of(a.id, b.id), result.winners(), "the last ones standing, together");
        assertEquals(LavaPlanner.SAFE_TOP, result.standing(a.id).survived(), "they lasted every round there is dry"
                + " ground for");
        assertEquals(3, result.standing(c.id).place());
        assertFalse(rig.site().inGallery(c.x, c.y, c.z), "the watcher is brought down for the party");
        assertFalse(rig.site().inGallery(a.x, a.y, a.z));
        assertEquals(0, rig.count(LAVA));
        assertEquals(0, rig.differences());
    }

    @Test
    void aloneInChallengeTheScoreIsTheRoundsLasted() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.CHALLENGE, false);
        assertEquals(LavaRound.Phase.GATHER, rig.service.round().phase());
        rig.ticks(LavaRound.GATHER_TICKS + 2);
        LavaRound round = rig.service.round();
        assertEquals(LavaRound.Phase.SAFE, round.phase(), "nobody else came: it starts by itself, alone");
        for (int i = 0; i < 20_000 && !round.over(); i++) {
            rig.tick(); // standing on the sofa's seat all the while
        }
        LavaResult result = rig.host.scored.get(0);
        assertTrue(result.solo());
        assertEquals(2, result.standing(a.id).survived(), "the seat is dry for two rounds");
        assertEquals(2L, rig.host.bests.get(a.id + ":lava:challenge"));
        assertTrue(a.heard().contains("You lasted &e2 rounds"), a.heard());
    }

    // ---- one room, one round ------------------------------------------------------------------------

    @Test
    void oneRoomOneRoundAndWhoMayJoinIt() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        rig.join(a, LavaMode.GROUP, false);
        assertEquals(LavaMode.GROUP, rig.service.busyWith());
        assertEquals(LavaText.busy(LavaMode.GROUP), rig.join(b, LavaMode.CHALLENGE, false),
                "the room is in use for Group: Challenge waits");
        assertEquals(LavaText.busy(LavaMode.GROUP), rig.join(b, LavaMode.LITTLE, false));
        assertNull(rig.join(lilah, LavaMode.LITTLE, true), "but a child's tap on the cabinet joins the Group round:"
                + " nobody can lose there either");
        assertEquals(LavaMode.GROUP, rig.service.round().mode());
        assertEquals(List.of(a.id, lilah.id), rig.service.round().members());
        assertEquals(2, rig.service.playing());
        assertFalse(b.session, "the one refused never got a session");

        Rig other = new Rig().built();
        FakeLavaHost.P c = other.host.player("Casey");
        FakeLavaHost.P kid = other.host.player("Lilah");
        other.join(c, LavaMode.CHALLENGE, false);
        assertEquals(LavaText.busy(LavaMode.CHALLENGE), other.join(kid, LavaMode.LITTLE, true),
                "a Challenge is not for her: the tap is told the room is in use");
        other.service.startNow(c.id);
        assertEquals(LavaText.busy(LavaMode.CHALLENGE), other.join(kid, LavaMode.LITTLE, true));
    }

    @Test
    void theRoomIsFullAtMaxPlayers() {
        Rig rig = new Rig().built();
        List<FakeLavaHost.P> in = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            FakeLavaHost.P p = rig.host.player("P" + n);
            assertNull(rig.join(p, LavaMode.LITTLE, false));
            in.add(p);
        }
        java.util.Set<String> spots = new java.util.HashSet<>();
        for (FakeLavaHost.P p : in) {
            assertTrue(spots.add(p.x + "," + p.z), "each arrives on a spot of their own");
        }
        FakeLavaHost.P seventh = rig.host.player("P7");
        assertEquals(LavaText.full(6), rig.join(seventh, LavaMode.LITTLE, false));
        rig.service.left(in.get(0).id);
        assertNull(rig.join(seventh, LavaMode.LITTLE, false));
    }

    // ---- after the end ------------------------------------------------------------------------------

    @Test
    void playAgainStartsANewRoundForEveryoneStillThereButNeverFromAStrayTap() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        rig.join(a, LavaMode.LITTLE, false);
        rig.join(b, LavaMode.LITTLE, false);
        rig.tick();
        assertFalse(rig.service.again(a.id), "Play again does nothing while a round is on");
        rig.collectAll(a);
        LavaRound first = rig.service.round();
        assertTrue(first.over());
        assertFalse(rig.service.again(a.id), "a thumb still pressing as the round ends doesn't start the next one");
        assertTrue(first == rig.service.round());
        rig.ticks(LavaService.PARTY_TICKS);
        assertTrue(rig.service.again(a.id), "after the party, Play again");
        LavaRound second = rig.service.round();
        assertFalse(first == second);
        assertEquals(LavaMode.LITTLE, second.mode());
        assertEquals(List.of(a.id, b.id), second.members(), "everyone still in the room plays again");
        assertEquals(LavaRound.Phase.SAFE, second.phase(), "at once");
        assertEquals(4, rig.host.stars.size(), "new stars");
        assertEquals(LavaHost.Kit.PLAY, b.kit);
        assertEquals(rig.site().spawn(0).y(), a.y, "back on the sofa");
        assertEquals(LavaText.starRow(0, 4), a.bar);
    }

    @Test
    void whoeverLingersLongAfterTheEndGoesBackAndTheRoomIsFree() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.LITTLE, false);
        rig.tick();
        rig.collectAll(a);
        assertTrue(rig.service.round().over());
        rig.ticks(LavaService.LINGER_TICKS - 5);
        assertEquals(0, a.sessionsEnded, "plenty of time to choose");
        rig.ticks(10);
        assertEquals(1, a.sessionsEnded, "then the room lets them go");
        assertNull(rig.service.round());
        assertNull(rig.service.busyWith());
    }

    @Test
    void aNewRoundOfAnotherModeSendsTheLingerersBackFirst() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        rig.join(a, LavaMode.LITTLE, false);
        rig.tick();
        rig.collectAll(a);
        assertTrue(rig.service.round().over());
        assertNull(rig.service.busyWith(), "a finished round doesn't hold the room");
        assertNull(rig.join(b, LavaMode.CHALLENGE, false), "someone starts a Challenge");
        assertEquals(1, a.sessionsEnded, "the one still standing about from Little is sent back");
        assertEquals(List.of(b.id), rig.service.round().members());
        assertEquals(LavaMode.CHALLENGE, rig.service.round().mode());

        Rig same = new Rig().built();
        FakeLavaHost.P c = same.host.player("Casey");
        FakeLavaHost.P d = same.host.player("Drew");
        same.join(c, LavaMode.LITTLE, false);
        same.tick();
        same.collectAll(c);
        assertNull(same.join(d, LavaMode.LITTLE, true), "the same mode: whoever is still there plays on");
        assertEquals(0, c.sessionsEnded);
        assertEquals(List.of(c.id, d.id), same.service.round().members());
    }

    @Test
    void nobodyIsEverOutsideTheRoomAndTheVoidPutsThemBackOnTheSofa() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.LITTLE, false);
        rig.tick();
        Box box = rig.service.box();
        a.at(box.minX() - 5.5, box.minY() + 3, box.minZ() + 4.5); // through a wall, somehow
        rig.ticks(21);
        assertTrue(rig.site().inside().contains(a.x, a.y, a.z), "brought back inside within a second");
        a.at(box.minX() + 4.5, box.minY() - 40, box.minZ() + 4.5);
        rig.service.voided(a.id);
        assertEquals(rig.site().spawn(0).y(), a.y, "the void: back onto the sofa");
    }

    @Test
    void theStatusSaysWhatTheRoomIsDoing() {
        Rig rig = new Rig();
        assertTrue(String.join("\n", rig.service.statusLines()).contains("box x 7312..7343, y 160..175, z 8544..8575"
                + " in games"));
        rig.built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.GROUP, false);
        rig.service.startNow(a.id);
        String status = String.join("\n", rig.service.statusLines());
        assertTrue(status.contains("plan " + rig.site().plan().hash()), status);
        assertTrue(status.contains("Group, safe: 1/6 here, round 1"), status);
        assertTrue(status.contains("builds: 1 done"), status);
        assertEquals("&eA round is going: the playroom is checked when it is empty.", rig.service.requestRebuild());
        assertEquals(List.of("&6The Floor Is Lava", "&7Not running: games.enabled is false"),
                LavaAdmin.lines(null, "status", new String[0], "games.enabled is false"));
        assertEquals(LavaAdmin.HELP, LavaAdmin.lines(rig.service, "what", new String[0], ""));
        assertNull(LavaAdmin.tpRefusal(rig.service, true, ""));
        assertEquals(LavaAdmin.TP_NOT_BUILT, LavaAdmin.tpRefusal(new Rig().service, true, ""));
        Map<Integer, LavaSite.Spot> out = rig.service.stars();
        assertEquals(4, out.size());
    }
}
