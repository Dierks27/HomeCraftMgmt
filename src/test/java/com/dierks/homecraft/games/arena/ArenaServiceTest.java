package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.arena.FakeArenaHost.P;
import com.dierks.homecraft.games.arena.rules.ArenaRound;
import com.dierks.homecraft.games.arena.rules.ArenaText;
import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.arena.rules.RoundSettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.engine.BuildJob;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Falling Floors arena running tick by tick on a fake world (EVENTS-DROPPER-SPEC §B.3.3, §B.4):
 * the gate is shut until the boot verify passes; the boot heals whatever a crash left behind; the
 * reset after every round converges the box back to the week's plan with no difference left; no
 * round starts in the restart hold; anyone on the floors is moved out of the reset's way first; a
 * box that can't be put back three times closes the game, naming where; a box with someone else's
 * blocks in it (or too close to another area) is never written; round writes are floor cells only;
 * and collisions come back on every way off the floors.
 */
class ArenaServiceTest {

    private final FakeWorldPort world = new FakeWorldPort(FakeArenaHost.WORLD);
    private final FakeArenaHost host = new FakeArenaHost(world);
    private ArenaService arena;
    private int ticked;

    private ArenaService start() {
        arena = new ArenaService(host);
        host.onSessionEnd = id -> arena.left(id);
        arena.start();
        return arena;
    }

    /** {@code n} ticks: the clock moves 50 ms a tick, and the once-a-second check runs every 20th. */
    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            host.now += 50;
            arena.tick();
            if (++ticked % 20 == 0) {
                arena.check();
            }
        }
    }

    /** Tick until the arena is in {@code phase}; fail after {@code max} ticks. */
    private void until(ArenaRound.Phase phase, int max) {
        for (int i = 0; i < max; i++) {
            if (arena.round() != null && arena.round().phase() == phase) {
                return;
            }
            ticks(1);
        }
        assertEquals(phase, arena.round() == null ? null : arena.round().phase(), "reached within " + max + " ticks: "
                + arena.statusLines());
    }

    private ArenaService booted() {
        start();
        until(ArenaRound.Phase.LOBBY, 400);
        return arena;
    }

    /** A player's session puts them at the next gallery spot, then the arena takes them in. */
    private P join(String name) {
        P p = host.player(name);
        assertNull(arena.joinRefusal(p.id), name + " may come in");
        ArenaSite.Spot s = arena.entrySpot();
        p.at(s.x(), s.y(), s.z());
        p.session = true;
        assertNull(arena.joined(p.id), name + " is in");
        return p;
    }

    /** Everyone ready, then on to Go. */
    private void playTogether(P... players) {
        for (P p : players) {
            arena.ready(p.id);
        }
        until(ArenaRound.Phase.PLAYING, TO_GO);
    }

    /** The countdown, the teleports and the 3-2-1, with room to spare. */
    private static final int TO_GO = 400;

    /** How many blocks of the box differ from this week's plan (air everywhere the plan has nothing). */
    private int diffs() {
        Plan plan = arena.site().plan();
        Map<Long, String> want = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            want.put(FakeWorldPort.pos(op.x(), op.y(), op.z()), FakeWorldPort.canonicalOf(plan.blockOf(op)));
        }
        Box b = arena.box();
        int n = 0;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    String w = want.getOrDefault(FakeWorldPort.pos(x, y, z), WorldPort.AIR);
                    if (!w.equals(world.at(x, y, z))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private Cell topCell(int n) {
        return arena.site().layout().layer(0).cells().get(n);
    }

    private boolean inGallery(P p) {
        return arena.site().inGallery(p.x, p.y, p.z);
    }

    // ---- the gate and the boot ------------------------------------------------------------------

    @Test
    void theGateIsShutUntilTheBootVerifyPassesThenAnEmptyBoxIsClaimedAndBuilt() {
        start();
        assertEquals(ArenaRound.Phase.RESET, arena.round().phase(), "the rules start with the boot verify");
        P early = host.player("Early");
        assertEquals(FloorsText.FIXING, arena.joinRefusal(early.id), "nobody comes in before a verify has passed");
        assertFalse(arena.verified(), "not verified yet");
        ticks(3);
        assertEquals(ArenaRound.Phase.RESET, arena.round().phase(), "still building");
        assertEquals(FloorsText.FIXING, arena.joinRefusal(early.id), "the gate is still shut while it builds");
        until(ArenaRound.Phase.LOBBY, 400);
        assertTrue(arena.verified(), "the verify passed");
        assertEquals(arena.claimText(), host.claim, "the empty box was claimed at its first build");
        assertEquals(0, diffs(), "the box is exactly this week's plan");
        assertEquals(arena.site().plan().ops().size(), world.count(arena.box()), "and nothing else is in it");
        assertNull(arena.joinRefusal(early.id), "now players may come in");
    }

    @Test
    void theBootHealsWhateverACrashLeftBehind() {
        booted();
        Cell a = topCell(0);
        Cell b = topCell(40);
        world.put(a.x(), 200, a.z(), WorldPort.AIR); // a crash mid-round: a cell gone
        world.put(b.x(), 200, b.z(), FloorWriter.RED); // and one red
        Cell mid = arena.site().layout().layer(1).cells().get(3);
        world.put(mid.x(), 193, mid.z(), "minecraft:stone"); // something left standing on a floor
        Box box = arena.box();
        world.put(box.minX(), 207, box.minZ() + 20, WorldPort.AIR); // a hole in the outer rail
        assertNotEquals(0, diffs(), "(the box is damaged)");

        start(); // the restart: the claim is kept, everything else is new
        assertEquals(FloorsText.FIXING, arena.joinRefusal(host.player("Kid").id), "the gate is shut at boot");
        until(ArenaRound.Phase.LOBBY, 400);
        assertEquals(0, diffs(), "the boot verify healed every block");
        assertEquals("minecraft:yellow_stained_glass", world.at(a.x(), 200, a.z()), "the lost cell is back");
        assertEquals(WorldPort.AIR, world.at(mid.x(), 193, mid.z()), "the leftover is gone");
    }

    @Test
    void aBoxWithSomeoneElsesBlocksInItIsNeverWrittenUntilAnAdminClaimsIt() {
        Box box = FallingFloorsSettings.defaults().box();
        world.put(box.minX() + 20, box.minY() + 3, box.minZ() + 20, "minecraft:stone");
        world.put(box.minX() + 21, box.minY() + 3, box.minZ() + 20, "minecraft:oak_planks");
        start();
        until(ArenaRound.Phase.CLOSED, 400);
        assertEquals(0, world.writes, "nothing was written: those blocks are someone else's");
        assertNull(host.claim, "and the box isn't claimed");
        assertTrue(String.join("\n", arena.statusLines()).contains("claim confirm"), "status says how to claim it: "
                + arena.statusLines());
        assertEquals(ArenaText.closed(), arena.joinRefusal(host.player("Kid").id), "nobody comes in");

        List<String> said = arena.claim(true);
        assertTrue(said.get(0).contains("Claimed"), said.toString());
        assertEquals(arena.claimText(), host.claim, "the admin's claim is recorded");
        until(ArenaRound.Phase.LOBBY, 400);
        assertEquals(0, diffs(), "the claimed box is cleared and built");
        assertEquals(WorldPort.AIR, world.at(box.minX() + 20, box.minY() + 3, box.minZ() + 20), "the stone is gone");
    }

    @Test
    void aBoxTooCloseToAnotherAreaIsNeverUsed() {
        host.regionProblems.add("falling_floors is only 12 blocks from fresh_boat's half A (they must be 32 apart)");
        start();
        until(ArenaRound.Phase.CLOSED, 50);
        assertEquals(0, world.writes, "not one block");
        assertTrue(arena.round().closedReason().contains("fresh_boat") && arena.round().closedReason()
                .contains("games.falling_floors.origin"), "it says why and how to move it: " + arena.round().closedReason());
    }

    // ---- rounds -------------------------------------------------------------------------------

    @Test
    void aSoloRoundTurnsTheFloorRedThenAirAndTheResetPutsItAllBack() {
        booted();
        P alex = join("Alex");
        assertTrue(alex.kit.solo(), "alone, with solo on: Play solo is offered");
        arena.solo(alex.id);
        until(ArenaRound.Phase.HOLD, 10);
        assertTrue(arena.held(alex.id), "held in place for the 3-2-1");
        assertEquals(201.0, alex.y, "on the top floor");
        assertFalse(inGallery(alex), "on a spawn, not in the gallery");
        assertFalse(alex.collidable, "nobody can shove anybody");
        assertEquals(NoPush.TEAM, host.teamOf(alex), "and on the no-push team, where no player pushes another");
        until(ArenaRound.Phase.PLAYING, 100);
        assertFalse(arena.held(alex.id), "Go lets go");
        assertTrue(alex.titles.contains(ArenaText.go()), "Go is shown");
        int logAtGo = world.log.size();

        Cell under = new Cell((int) Math.floor(alex.x), (int) Math.floor(alex.z));
        ticks(1);
        assertEquals(FloorWriter.RED, world.at(under.x(), 200, under.z()), "standing still turns the cell red at once");
        ticks(host.settings.fadeTicks() + 1);
        assertEquals(WorldPort.AIR, world.at(under.x(), 200, under.z()), "and it is gone fade_ticks later");

        ticks(200);
        alex.at(alex.x, 150, alex.z); // fell through
        ticks(1);
        assertTrue(inGallery(alex), "out means back in the gallery");
        assertTrue(alex.heard().contains("You lasted"), "with the time: " + alex.heard());
        assertTrue(alex.collidable, "and collisions back");
        assertNull(host.teamOf(alex), "and off the no-push team");
        assertEquals(1, host.scored.size(), "the round was scored once");
        RoundResult r = host.scored.get(0);
        assertTrue(r.solo(), "a solo round");
        assertEquals(List.of(), r.winners(), "solo has no winner");
        assertEquals(host.week, host.scoredWeeks.get(0), "on this week's boards");

        for (String entry : world.log.subList(logAtGo, world.log.size())) {
            String[] xyz = entry.substring(0, entry.indexOf('=')).split(",");
            int x = Integer.parseInt(xyz[0]);
            int y = Integer.parseInt(xyz[1]);
            int z = Integer.parseInt(xyz[2]);
            String block = entry.substring(entry.indexOf('=') + 1);
            assertTrue(arena.site().layout().isFloorCell(x, y, z), "a round writes floor cells only: " + entry);
            assertTrue(block.equals(FloorWriter.RED) || block.equals(WorldPort.AIR), "red or air only: " + entry);
        }

        assertNotEquals(0, diffs(), "(the round took blocks away)");
        until(ArenaRound.Phase.LOBBY, 200);
        assertEquals(0, diffs(), "the reset converged the box back to the plan: no difference left");
        assertTrue(alex.kit.solo() && alex.kit.kind() == ArenaHost.KitKind.LOBBY, "the lobby kit again");
    }

    @Test
    void theLastOneStandingWinsAndEveryoneEndsInTheGalleryWithCollisionsBack() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        P c = join("Cat");
        playTogether(a, b, c);
        for (P p : List.of(a, b, c)) {
            assertEquals(201.0, p.y, p.name + " is on the top floor");
            assertEquals(List.of(false), p.collisions, p.name + ": collisions off for the round");
            assertEquals(ArenaHost.KitKind.ROUND, p.kit.kind(), p.name + " holds only Leave game");
        }
        ticks(5);
        a.at(a.x, 170, a.z);
        ticks(1);
        assertTrue(inGallery(a), "Ann is out and in the gallery");
        assertTrue(a.heard().contains("3rd of 3"), a.heard());
        ticks(10);
        arena.voided(b.id); // the void took Ben
        ticks(1);
        assertTrue(inGallery(b), "Ben too");
        assertTrue(b.heard().contains("2nd of 3"), b.heard());
        assertEquals(1, host.scored.size(), "one left standing: the round is over");
        assertEquals(List.of(c.id), host.scored.get(0).winners(), "Cat won");
        assertTrue(inGallery(c), "the winner watches the results from the gallery too");
        assertTrue(c.heard().contains("Last one standing - you won!"), c.heard());
        for (P p : List.of(a, b, c)) {
            assertTrue(p.heard().contains("Cat &ewon"), p.name + " saw the results: " + p.heard());
            assertEquals(List.of(false, true), p.collisions, p.name + ": collisions back, once");
        }
        until(ArenaRound.Phase.LOBBY, 200);
        assertEquals(0, diffs(), "whole floors for the next round");
    }

    @Test
    void noRoundStartsInTheRestartHoldButOneGoingFinishes() {
        booted();
        host.holding = true;
        host.heldFor = "4:00 PM";
        arena.check();
        P a = join("Ann");
        arena.solo(a.id);
        assertTrue(a.heard().contains("The server restarts at 4:00 PM"), "solo waits for the restart: " + a.heard());
        P b = join("Ben");
        arena.ready(a.id);
        arena.ready(b.id);
        ticks(1000);
        assertEquals(ArenaRound.Phase.LOBBY, arena.round().phase(), "no round starts in the hold");
        assertTrue(host.scored.isEmpty(), "(nothing was played)");

        host.holding = false;
        host.heldFor = null;
        until(ArenaRound.Phase.COUNTDOWN, 40);
        host.holding = true;
        host.heldFor = "4:00 PM";
        until(ArenaRound.Phase.LOBBY, 40);
        assertTrue(b.heard().contains("The server restarts at 4:00 PM"), "a countdown stops for the hold: " + b.heard());

        host.holding = false;
        until(ArenaRound.Phase.PLAYING, 1000);
        host.holding = true; // the hold comes while a round is going
        ticks(100);
        assertEquals(ArenaRound.Phase.PLAYING, arena.round().phase(), "the round going carries on");
        a.at(a.x, 150, a.z);
        ticks(2);
        assertEquals(1, host.scored.size(), "and is played out");
    }

    @Test
    void anyoneOnTheFloorsIsMovedOutOfTheWayBeforeAReset() {
        booted();
        P kid = join("Kid");
        Cell c = topCell(10);
        kid.at(c.centerX(), 201, c.centerZ()); // somehow on the floors between rounds
        P admin = host.player("Admin"); // not in the arena: walked in
        Cell m = arena.site().layout().layer(1).cells().get(10);
        admin.at(m.centerX(), 193, m.centerZ());
        P watcher = host.player("Watcher"); // in the gallery, not playing
        watcher.at(arena.site().gallery(3).x(), arena.site().gallery(3).y(), arena.site().gallery(3).z());
        int kidTeleports = kid.teleports;

        arena.requestReset();
        ticks(1);
        assertEquals(kidTeleports + 1, kid.teleports, "the arena player went back to the gallery");
        assertTrue(inGallery(kid), "and is there");
        assertEquals(1, admin.movedToSafety, "someone else in the box went to the safe spot");
        assertEquals(0, watcher.movedToSafety + watcher.teleports, "the gallery is left alone");
        until(ArenaRound.Phase.LOBBY, 200);
    }

    @Test
    void threeFailedVerifiesCloseTheGameNamingWhereAndAnAdminCanOpenItAgain() {
        booted();
        P kid = join("Kid");
        Cell c = topCell(7);
        world.put(c.x(), 200, c.z(), "minecraft:stone");
        world.sticky.add(FakeWorldPort.pos(c.x(), 200, c.z())); // it won't be put back
        arena.requestReset();
        until(ArenaRound.Phase.CLOSED, 2000);
        String status = String.join("\n", arena.statusLines());
        assertTrue(status.contains("3 failed"), "three failed resets: " + status);
        assertTrue(status.contains("first at " + c.x() + ",200," + c.z()), "the status names where: " + status);
        assertTrue(host.logs.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("closed")),
                "a WARN in the console: " + host.logs);
        assertEquals(1, kid.sessionsEnded, "the kid was sent home, things and all");
        assertTrue(kid.heard().contains(ArenaText.closed()), kid.heard());
        assertFalse(arena.round().isMember(kid.id), "and is out of the arena");
        assertEquals(ArenaText.closed(), arena.joinRefusal(kid.id), "nobody comes in");
        assertFalse(arena.verified(), "the gate is shut");

        world.sticky.clear();
        assertTrue(arena.requestReset().contains("opens again"), "an admin's reset opens it again");
        until(ArenaRound.Phase.LOBBY, 400);
        assertEquals(0, diffs(), "put back and verified");
    }

    @Test
    void aNewWeeksFloorsWaitForTheRoundGoingWhichScoresOnItsOwnWeek() {
        booted();
        String oldHash = arena.site().plan().hash();
        long oldWeek = host.week;
        P a = join("Ann");
        P b = join("Ben");
        playTogether(a, b);
        host.week += 7;
        ticks(20); // the once-a-second check sees the new week
        assertEquals(host.week, arena.site().week(), "next week's floors are made");
        assertNotEquals(oldHash, arena.site().plan().hash(), "a new shape");
        assertEquals(ArenaRound.Phase.PLAYING, arena.round().phase(), "the round going carries on");
        a.at(a.x, 150, a.z);
        ticks(1);
        assertEquals(List.of(oldWeek), host.scoredWeeks, "the round counts on the week its floors were made for");
        until(ArenaRound.Phase.LOBBY, 300);
        assertEquals(0, diffs(), "the reset built next week's floors");
    }

    // ---- collisions come back on every way off the floors ---------------------------------------

    @Test
    void leavingMidRoundPutsCollisionsBackAtOnce() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        P c = join("Cat");
        playTogether(a, b, c);
        arena.left(a.id); // Leave game, /hcm leave or a quit: the session ended
        assertTrue(a.collidable, "Ann can be pushed again the moment she leaves");
        ticks(1);
        assertFalse(a.heard().contains("You lasted"), "a leaver isn't given a place: " + a.heard());
        b.at(b.x, 150, b.z);
        ticks(1);
        assertTrue(b.collidable && c.collidable, "everyone's back at the end");
        assertEquals(List.of(c.id), host.scored.get(0).winners(), "Cat out-lasted Ben");
    }

    @Test
    void theGameClosingMidRoundPutsCollisionsBackAndSendsEveryoneHome() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        playTogether(a, b);
        arena.round().close("an admin closed it");
        ticks(1);
        for (P p : List.of(a, b)) {
            assertTrue(p.collidable, p.name + ": collisions back");
            assertNull(host.teamOf(p), p.name + ": off the no-push team");
            assertEquals(1, p.sessionsEnded, p.name + " went home");
        }
        assertTrue(host.scored.isEmpty(), "a called-off round scores nothing");
        assertTrue(arena.uncollided().isEmpty(), "nobody left without collisions");
    }

    @Test
    void stoppingMidRoundPutsCollisionsBack() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        playTogether(a, b);
        assertFalse(a.collidable || b.collidable, "(off for the round)");
        assertEquals(NoPush.TEAM, host.teamOf(a), "(on the no-push team)");
        arena.stop();
        assertTrue(a.collidable && b.collidable, "a reload or a stop puts them back");
        assertNull(host.teamOf(a), "Ann is off the no-push team");
        assertNull(host.teamOf(b), "and so is Ben");
        assertNull(arena.job(), "and no reset is left running");
    }

    @Test
    void aRoundCalledOffBeforeGoPutsCollisionsBackAndPlayersInTheGallery() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        arena.ready(a.id);
        arena.ready(b.id);
        until(ArenaRound.Phase.HOLD, 400);
        arena.left(b.id); // one of two leaves on the spawns: too few to play
        ticks(1);
        assertTrue(a.collidable && b.collidable, "collisions back for both");
        assertNull(host.teamOf(a), "Ann is off the no-push team");
        assertNull(host.teamOf(b), "and so is Ben");
        assertTrue(inGallery(a), "Ann is back in the gallery");
        assertTrue(a.heard().contains(ArenaText.calledOff()), a.heard());
        assertTrue(host.scored.isEmpty(), "nothing scored");
        until(ArenaRound.Phase.LOBBY, 200);
    }

    // ---- nobody can push anybody (F review #1) -------------------------------------------------

    /**
     * {@code setCollidable(false)} doesn't stop one player pushing another; only a scoreboard team's
     * collision rule does. So every round player goes on the games' no-push team at the round's
     * start, and every way off the floors takes them off it, back on the team they came from.
     */
    @Test
    void roundPlayersGoOnTheNoPushTeamAndEveryWayOffPutsThemBackOnTheirOwn() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        P c = join("Cat");
        P d = join("Dan");
        host.teamNames.add("blue");
        host.teams.put("Ben", "blue"); // another plugin's nametag colour
        playTogether(a, b, c, d);
        for (P p : List.of(a, b, c, d)) {
            assertEquals(NoPush.TEAM, host.teamOf(p), p.name + " is on the no-push team for the round");
            assertFalse(p.collidable, p.name + ": and no mob pushes them either");
        }
        a.at(a.x, 150, a.z);
        ticks(1);
        assertNull(host.teamOf(a), "out: Ann is off the team at once");
        arena.left(b.id); // Leave game, /hcm leave or a quit
        assertEquals("blue", host.teamOf(b), "leaving: Ben is back on his own team");
        c.at(c.x, 150, c.z);
        ticks(1);
        assertEquals(1, host.scored.size(), "(Dan is the last one standing)");
        for (P p : List.of(a, c, d)) {
            assertNull(host.teamOf(p), p.name + " is off the team after the round");
        }
        assertFalse(host.noPush.isOn(d.id), "nobody is left on it");
    }

    // ---- a spawn nobody reached (F review #3) ---------------------------------------------------

    @Test
    void aPlayerWhoseSpawnTeleportFailedWatchesTheRoundAndCantWinIt() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        P c = join("Cat");
        host.teleportFails.add(b.id); // the server refused it
        playTogether(a, b, c);
        assertEquals(List.of(a.id, c.id), arena.round().starters(), "Ben is out of the round before Go");
        assertTrue(arena.round().isMember(b.id), "but still in the arena");
        assertTrue(inGallery(b), "watching from the gallery, where he was");
        assertTrue(b.heard().contains(FloorsText.NO_SPAWN), "he's told why: " + b.heard());
        assertTrue(b.collidable, "his collisions are back");
        assertNull(host.teamOf(b), "and he's off the no-push team");
        assertEquals(ArenaHost.KitKind.WATCH, b.kit.kind(), "holding the watcher's kit");
        a.at(a.x, 150, a.z);
        ticks(1);
        RoundResult r = host.scored.get(0);
        assertEquals(List.of(c.id), r.winners(), "Cat won: the round was Ann's and Cat's");
        assertTrue(r.standings().stream().noneMatch(st -> st.player().equals(b.id)), "Ben had no part in it");
    }

    @Test
    void aRoundLeftWithTooFewOnTheSpawnsIsCalledOff() {
        booted();
        P a = join("Ann");
        P b = join("Ben");
        host.teleportFails.add(b.id);
        arena.ready(a.id);
        arena.ready(b.id);
        until(ArenaRound.Phase.TELEPORT, TO_GO);
        ticks(3);
        assertTrue(a.heard().contains(ArenaText.calledOff()), "one player can't be a round: " + a.heard());
        assertTrue(inGallery(a), "Ann is back in the gallery");
        assertTrue(a.collidable && host.teamOf(a) == null, "with her collisions back");
        assertTrue(host.scored.isEmpty(), "nothing scored");
        until(ArenaRound.Phase.LOBBY, 300);
    }

    // ---- nobody arrives in a gallery that failed its check (F review #4) -------------------------

    @Test
    void anArrivalAfterAVerifyFailedIsNotLetIn() {
        booted();
        P kid = host.player("Kid");
        assertNull(arena.joinRefusal(kid.id), "the gate is open: the kid's session starts");
        Cell c = topCell(7);
        world.put(c.x(), 200, c.z(), "minecraft:stone");
        world.sticky.add(FakeWorldPort.pos(c.x(), 200, c.z()));
        arena.requestReset();
        for (int i = 0; i < 2000 && arena.verified(); i++) {
            ticks(1);
        }
        assertFalse(arena.verified(), "(a verify failed while the kid was on the way)");
        assertEquals(FloorsText.FIXING, arena.joined(kid.id), "so they aren't let in: their session ends");
        assertFalse(arena.round().isMember(kid.id), "and they aren't in the arena");
        world.sticky.clear();
        until(ArenaRound.Phase.LOBBY, 2000);
        assertNull(arena.joined(kid.id), "once the floors pass again, they are");
    }

    // ---- the restart (F review #8) --------------------------------------------------------------

    @Test
    void noCountdownStartsWhoseRoundCouldRunIntoTheNextRestart() {
        booted();
        long tail = ArenaService.tailTicks(host.settings.round(), arena.site().layout());
        long roundMs = host.settings.roundSeconds() * 1000L;
        long extra = tail * RoundSettings.MS_PER_TICK - roundMs;
        assertTrue(extra > 30_000 && extra < 60_000, "a round's worst case is round_seconds and about 40 s: +" + extra);
        long fromCountdown = (RoundSettings.COUNTDOWN_TICKS + tail) * RoundSettings.MS_PER_TICK
                + ArenaService.RESTART_MARGIN_MS;
        host.heldFor = "4:00 PM";
        host.nextRestart = host.now + fromCountdown - 1_000; // a second short
        ticks(20);
        P a = join("Ann");
        P b = join("Ben");
        arena.ready(a.id);
        arena.ready(b.id);
        ticks(100);
        assertEquals(ArenaRound.Phase.LOBBY, arena.round().phase(), "no countdown: its round might end after the restart");

        host.nextRestart = host.now + fromCountdown + 3_000; // room for all of it, 3 s to spare
        until(ArenaRound.Phase.COUNTDOWN, 40);
        until(ArenaRound.Phase.PLAYING, TO_GO); // only what is left of a countdown counts: it isn't stopped as it runs
        host.nextRestart = host.now + 1_000; // the restart is close now, but a round going finishes
        ticks(100);
        assertEquals(ArenaRound.Phase.PLAYING, arena.round().phase(), "the round going carries on");
    }

    @Test
    void aSoloRoundOnlyStartsWhenItCanEndBeforeTheNextRestart() {
        booted();
        long solo = ArenaService.tailTicks(host.settings.round(), arena.site().layout()) * RoundSettings.MS_PER_TICK
                + ArenaService.RESTART_MARGIN_MS;
        host.heldFor = "4:00 PM";
        host.nextRestart = host.now + solo - 1_000;
        P a = join("Ann");
        arena.solo(a.id);
        assertTrue(a.heard().contains("The server restarts at 4:00 PM"), "held for the restart: " + a.heard());
        assertEquals(ArenaRound.Phase.LOBBY, arena.round().phase(), "no solo round");
        host.nextRestart = host.now + solo + 1_000;
        arena.solo(a.id);
        assertEquals(ArenaRound.Phase.TELEPORT, arena.round().phase(), "with time for all of it, it starts");
    }

    // ---- a reset a newer request replaced (F review #10) ----------------------------------------

    /** Tick until the reset job running now is over; how many ticks it was driven. */
    private int drivesLeft() {
        BuildJob j = arena.job();
        assertNotNull(j, "(a reset is running)");
        int n = 0;
        while (arena.job() == j) {
            ticks(1);
            assertTrue(++n < 2000, "(the reset ends)");
        }
        return n;
    }

    private String status() {
        return String.join("\n", arena.statusLines());
    }

    @Test
    void aResetANewerRequestReplacedNeverCountsAsOne() {
        booted();
        arena.requestReset();
        ticks(1); // the request is taken: its job starts
        int k = drivesLeft(); // a reset of whole floors takes k ticks
        assertTrue(status().contains("resets: 2 done"), status());

        arena.requestReset();
        ticks(1);
        BuildJob replaced = arena.job();
        ticks(k - 1); // one tick from done
        assertSame(replaced, arena.job(), "(still running)");
        arena.requestReset(); // an admin's reset (or a new week's floors) replaces it
        ticks(1);
        assertTrue(replaced.done(), "(the replaced reset finished on this very tick)");
        assertTrue(status().contains("resets: 2 done"), "its answer counts for nothing: " + status());
        assertEquals(ArenaRound.Phase.RESET, arena.round().phase(), "the newer request's reset runs");
        until(ArenaRound.Phase.LOBBY, 400);
        assertTrue(status().contains("resets: 3 done"), "and counts once it's done: " + status());
    }

    @Test
    void aFailedResetANewerRequestReplacedNeverCountsAsAFailure() {
        booted();
        Cell c = topCell(7);
        world.put(c.x(), 200, c.z(), "minecraft:stone");
        world.sticky.add(FakeWorldPort.pos(c.x(), 200, c.z())); // it won't be put back
        arena.requestReset();
        ticks(1);
        int k = drivesLeft(); // a failing reset takes k ticks; the failure asks for try 2 at once
        assertTrue(status().contains("1 failed"), status());
        BuildJob replaced = arena.job();
        assertNotNull(replaced, "(try 2 is running)");
        ticks(k - 1);
        assertSame(replaced, arena.job(), "(still running)");
        arena.requestReset();
        ticks(1);
        assertTrue(replaced.failed(), "(the replaced reset failed on this very tick)");
        assertTrue(status().contains("1 failed"), "its failure isn't counted: " + status());
        assertEquals(1, host.logs.stream().filter(l -> l.contains("the reset failed")).count(),
                "nor logged: " + host.logs);
    }

    @Test
    void anArenaKnowsTheSettingsItWasMadeFor() {
        start();
        FallingFloorsSettings d = host.settings;
        assertTrue(arena.builtWith(d, FakeArenaHost.WORLD), "the settings it was made with");
        assertTrue(arena.builtWith(new FallingFloorsSettings(true, d.origin(), d.fadeTicks(), d.minPlayers(),
                d.maxPlayers(), d.solo(), d.roundSeconds(), 800, 2, d.milestones(), d.milestoneRewards(), 5),
                FakeArenaHost.WORLD), "the rewards and the reset's speed are read live: still the same arena");
        assertFalse(arena.builtWith(new FallingFloorsSettings(true, List.of(6400, 176, 4352), d.fadeTicks(),
                d.minPlayers(), d.maxPlayers(), d.solo(), d.roundSeconds(), d.resetBlocksPerTick(), d.dailyReward(),
                d.milestones(), d.milestoneRewards(), d.dailyCap()), FakeArenaHost.WORLD), "a new origin: a new arena");
        assertFalse(arena.builtWith(new FallingFloorsSettings(true, d.origin(), 15, d.minPlayers(), d.maxPlayers(),
                d.solo(), d.roundSeconds(), d.resetBlocksPerTick(), d.dailyReward(), d.milestones(),
                d.milestoneRewards(), d.dailyCap()), FakeArenaHost.WORLD), "a new fade: a new arena");
        assertFalse(arena.builtWith(d, "other"), "another world: a new arena");
    }

    @Test
    void theStatusSaysWhatTheArenaIsDoing() {
        start();
        until(ArenaRound.Phase.LOBBY, 400);
        String status = String.join("\n", arena.statusLines());
        assertTrue(status.contains("lobby") && status.contains("resets: 1 done"), status);
        assertTrue(status.contains(arena.site().plan().hash()), "the plan's hash, for admins: " + status);
        assertEquals(Set.of(), arena.uncollided(), "nobody's collisions are off");
        assertNotNull(arena.writer(), "the floor writer is ready for the first round");
    }
}
