package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import com.dierks.homecraft.games.lava.rules.LavaRound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * The running playroom, tick by tick on a fake world: the claim and the build that gate the door (0.57.0: and the
 * room growing on a shipped server), a round of each mode from the first tap to the end, what a touch of the lava
 * does to a player in each, the furniture moving under the players, the surprises, leaving, and above all that the
 * lava is gone and the furniture back on every way out: the round's end, the last player leaving, a stop at any
 * phase, and a crash healed by the next start.
 */
class LavaServiceTest {

    private static final String LAVA = FakeWorldPort.canonicalOf(LavaWriter.LAVA);
    private static final String GLOW = FakeWorldPort.canonicalOf(LavaWriter.GLOW);
    private static final String SLIME = FakeWorldPort.canonicalOf(LavaPlanner.SLIME);

    /** A room on a fake world, with quick phases (safe_seconds 2, lava_seconds 2), 3 Group rounds; the room moves. */
    static final class Rig {
        final FakeWorldPort world = new FakeWorldPort(FakeLavaHost.WORLD);
        final FakeLavaHost host = new FakeLavaHost(world);
        LavaService service;
        long ticks;

        Rig() {
            this(4, false);
        }

        Rig(int stars, boolean surprises) {
            LavaSettings d = LavaSettings.defaults();
            host.settings = new LavaSettings(true, d.origin(), d.maxPlayers(), stars, 2, 2, 3, true, surprises, 3);
            service = new LavaService(host);
            host.onSessionEnd = id -> service.left(id);
            service.start();
        }

        Rig built() {
            for (int i = 0; i < 6000 && !service.open(); i++) {
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

        /** As {@link #until}, with {@code good} playing well all the while ({@link #play}). */
        void until(LavaRound.Phase phase, List<FakeLavaHost.P> good) {
            for (int i = 0; i < 6000; i++) {
                LavaRound r = service.round();
                if (r != null && r.phase() == phase) {
                    return;
                }
                play(good);
                tick();
            }
            throw new AssertionError("never reached " + phase + ": " + service.statusLines());
        }

        /**
         * Good play, for one tick: in the warning, up to ground that stays dry; otherwise each takes a star of their
         * own when there is one out (standing on its block), and stays where they are.
         */
        void play(List<FakeLavaHost.P> good) {
            LavaRound r = service.round();
            if (r == null || r.over()) {
                return;
            }
            List<double[]> out = new ArrayList<>(host.stars.values());
            int n = 0;
            for (FakeLavaHost.P p : good) {
                if (!r.playing(p.id)) {
                    continue;
                }
                if (r.phase() == LavaRound.Phase.WARNING) {
                    LavaSite.Stand on = site().standUnder(p.x, p.y, p.z);
                    if (on == null || !site().dry(on, r.nextLevel() + 1)) {
                        LavaSite.Spot dry = site().nearestSafe(Math.min(LavaPlanner.SAFE_TOP, r.nextLevel() + 1), p.x,
                                p.y, p.z);
                        if (dry != null) {
                            p.at(dry.x(), dry.y(), dry.z());
                        }
                    }
                } else if (n < out.size()) {
                    double[] s = out.get(n++);
                    p.at(s[0], s[1] - LavaService.STAR_UP, s[2]);
                }
            }
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

        /** The room as it stands now (its furniture where the round has it). */
        LavaSite site() {
            return service.site();
        }

        /** How many blocks of the box are not the room as built. */
        int differences() {
            return differences(service.built());
        }

        /** How many blocks of the box are not {@code s}'s. */
        int differences(LavaSite s) {
            Box box = service.box();
            int n = 0;
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        String want = s.block(x, y, z);
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

        /** Stand a player on open floor of the room as it is now, with nothing near it: the middlest such place. */
        LavaSite.Stand toFloor(FakeLavaHost.P p) {
            LavaSite.Stand best = null;
            double least = Double.MAX_VALUE;
            Box b = service.box();
            for (LavaSite.Stand s : site().starStands()) {
                boolean open = true;
                for (int dx = -3; dx <= 3 && open; dx++) {
                    for (int dz = -3; dz <= 3 && open; dz++) {
                        for (LavaSite.Stand o : site().column(s.x() + dx, s.z() + dz)) {
                            open &= o.y() <= b.minY() + 1 + 1e-9;
                        }
                    }
                }
                double d = Math.hypot(s.x() - b.minX() - 24, s.z() - b.minZ() - 24);
                if (open && d < least) {
                    least = d;
                    best = s;
                }
            }
            assertNotNull(best, "open floor");
            p.at(best.x() + 0.5, best.y(), best.z() + 0.5);
            return best;
        }

        /** Walk a player into every star that is out, one a tick. */
        int collectAll(FakeLavaHost.P p) {
            int n = 0;
            while (!host.stars.isEmpty() && service.round() != null && !service.round().over()) {
                Map.Entry<Integer, double[]> e = host.stars.entrySet().iterator().next();
                double[] s = e.getValue();
                p.at(s[0], s[1] - LavaService.STAR_UP, s[2]);
                tick();
                if (host.stars.containsKey(e.getKey()) && service.round() != null && !service.round().over()) {
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
        assertEquals("games,7312,160,8544,48,16,48", rig.host.claim, "0.57.0: 48 across");
        assertEquals(0, rig.differences(), "the box is exactly the plan");
        assertEquals(rig.service.built().plan().ops().size(), rig.world.blocks.size());
        assertEquals(0, rig.count(LAVA), "and the plan has no lava in it");
        assertEquals(0, rig.world.tickets.size(), "the build let its chunks go");
        assertNull(rig.service.joinRefusal(lilah.id, LavaMode.LITTLE, true), "now she may come in");
        assertNull(rig.service.closedWhy());
        assertTrue(rig.service.verified());
    }

    /**
     * 0.57.0: a shipped server's room was 32 across. Its claim is recognised: only the new ground (the chunks the old
     * room never had) is counted, and empty, the bigger box is claimed and the new room built over the old.
     */
    @Test
    void aShippedRoomGrowsOntoItsEmptyNewGround() {
        Rig rig = new Rig();
        Box box = rig.service.box();
        Box old = LavaSettings.defaults().oldBox();
        assertEquals(32, old.sizeX());
        rig.host.claim = LavaService.claimText(FakeLavaHost.WORLD, old);
        for (int x = old.minX(); x <= old.maxX(); x += 3) { // the old room's blocks, all through its box
            for (int z = old.minZ(); z <= old.maxZ(); z += 3) {
                rig.world.put(x, old.minY() + (x + z) % old.sizeY(), z, "minecraft:white_terracotta");
            }
        }
        assertTrue(LavaService.claimedOrGrowing(rig.host.claim, FakeLavaHost.WORLD, box), "the check reads it as ours");
        assertEquals("&eThe playroom's old box is claimed: its new ground is claimed at its next build when it is"
                + " empty (" + box.describe() + ").", rig.service.claim(false).get(0));
        rig.built();
        assertEquals(LavaService.claimText(FakeLavaHost.WORLD, box), rig.host.claim, "the bigger box is claimed");
        assertEquals(0, rig.differences(), "the new room stands where the old one was, and on the new ground");
        assertNull(rig.service.closedWhy());

        Rig crowded = new Rig();
        crowded.host.claim = LavaService.claimText(FakeLavaHost.WORLD, old);
        crowded.world.put(box.maxX() - 2, box.minY() + 4, box.maxZ() - 2, "minecraft:diamond_block");
        crowded.ticks(300);
        assertFalse(crowded.service.open());
        assertTrue(crowded.service.closedWhy().contains("where it grows to"), crowded.service.closedWhy());
        assertEquals(1, crowded.world.blocks.size(), "nothing written");
        assertNull(LavaService.grownFrom(LavaService.claimText("other", old), FakeLavaHost.WORLD, box),
                "another world's claim is not this room's");
    }

    @Test
    void aBoxWithSomeoneElsesBlocksClosesTheGameUntilAnAdminClaimsIt() {
        Rig rig = new Rig();
        Box box = rig.service.box();
        rig.world.put(box.minX() + 5, box.minY() + 5, box.minZ() + 5, "minecraft:diamond_block");
        rig.ticks(300);
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
        assertNotEquals(rig.service.built().layout(), rig.site().layout(), "0.57.0: the furniture is somewhere new");
        assertEquals(0, rig.differences(rig.site()), "and the room is that layout, block for block");
        assertFalse(rig.site().collides(lilah.x, lilah.y, lilah.z), "she is on the sofa, wherever it stands");
        assertEquals(9, rig.world.tickets.size(), "the room's chunks are held while a round is on");
        rig.tick();
        LavaRound round = rig.service.round();
        assertEquals(LavaMode.LITTLE, round.mode());
        assertEquals(LavaRound.Phase.SAFE, round.phase(), "no waiting: it has started");
        assertEquals(LavaHost.Kit.PLAY, lilah.kit, "only the way out in her hands");
        assertEquals(4, rig.host.stars.size(), "the stars are out");
        assertEquals(LavaText.starRow(0, 4), lilah.bar, "her bar is a row of stars, none gold yet");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.START));
        int up = 0;
        for (double[] s : rig.host.stars.values()) {
            double feet = s[1] - LavaService.STAR_UP - rig.service.box().minY();
            assertTrue(feet >= 1 && feet <= LavaService.LITTLE_LOW, "on the floor or low furniture: " + feet);
            up += feet > 1 ? 1 : 0;
        }
        assertEquals(1, up, "0.57.0: one of the four up on the low furniture");

        assertEquals(2, collectTwo(rig, lilah), "she walks into two of them");
        assertEquals(LavaText.starRow(2, 4), lilah.bar);
        assertEquals(2, rig.host.stars.size());
        assertTrue(lilah.sounds.contains(LavaHost.Cue.STAR), "a chime for a star");
        assertTrue(rig.host.fx.contains(LavaHost.Fx.SPARKLE), "and a sparkle");

        // the warning: a rumble, rising notes, the floor glowing, embers
        LavaSite.Stand floor = rig.toFloor(lilah);
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
        assertEquals(moves + 1, lilah.teleportedTo.size(), "one touch, one move");
        LavaSite.Spot back = lilah.teleportedTo.get(lilah.teleportedTo.size() - 1);
        assertTrue(back.y() >= rig.service.box().minY() + 2, "onto dry ground: " + back);
        assertFalse(rig.site().touches(1, lilah.x, lilah.y, lilah.z), "she is not in the lava any more");
        assertTrue(Math.hypot(back.x() - floor.x() - 0.5, back.z() - floor.z() - 0.5) < 12,
                "the nearest dry ground, not across the room: " + back + " from " + floor);
        assertEquals(rig.site().facing(back.x(), back.z()), back.yaw(), "facing into the room");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.SIZZLE), "a sizzle");
        assertTrue(rig.host.fx.contains(LavaHost.Fx.SMOKE), "and a puff of smoke");
        assertEquals(0, lilah.sessionsEnded, "she is still playing: Little can't be lost");
        assertEquals(LavaText.starRow(2, 4), lilah.bar, "and her stars stay collected");
        assertTrue(lilah.calmed > 100, "fire, fall and breath are cleared from her every tick");
        // 0.57.0: during the lava two of the stars she hasn't got wait up on the low furniture
        assertEquals(2, rig.host.stars.size(), "stars on the furniture during the lava");
        for (double[] s : rig.host.stars.values()) {
            LavaSite.Stand st = rig.site().standUnder(s[0], s[1] - LavaService.STAR_UP, s[2]);
            assertNotNull(st);
            assertTrue(rig.site().dry(st, 1), "on dry furniture: " + st);
            assertTrue(rig.site().pieceOf(st) >= 0, "a piece of furniture");
        }
        double[] up1 = rig.host.stars.values().iterator().next();
        lilah.at(up1[0], up1[1] - LavaService.STAR_UP, up1[2]);
        rig.tick();
        assertEquals(LavaText.starRow(3, 4), lilah.bar, "she can climb up and take one");
        assertEquals(1, rig.host.stars.size());

        // the lava drains; the stars she hasn't got come back
        rig.until(LavaRound.Phase.SAFE);
        assertEquals(0, rig.count(LAVA), "drained");
        assertEquals(0, rig.count(GLOW), "the floor is its own again");
        assertEquals(0, rig.differences(rig.site()), "the room is exactly its layout between the lava's visits");
        assertTrue(rig.host.stars.size() <= 2, "the ones left to find");
        assertTrue(lilah.sounds.contains(LavaHost.Cue.CLEAR));
        assertEquals(2, rig.service.round().round());

        // the last star: fireworks, a fanfare, and after a moment the big Play again
        rig.collectAll(lilah);
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
        assertEquals(0, rig.differences(), "the furniture is back where it was built, at once");
        assertFalse(rig.service.built().collides(lilah.x, lilah.y, lilah.z), "and nobody is inside it");
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
            p.at(s[0], s[1] - LavaService.STAR_UP, s[2]);
            rig.tick();
            n++;
        }
        return n;
    }

    /** The owner: "the mid lvl was fun the first time, but then you just know how it works". */
    @Test
    void everyGameTheFurnitureIsSomewhereNew() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        Set<List<LavaLayout.Placed>> seen = new HashSet<>();
        for (int game = 0; game < 4; game++) {
            assertNull(rig.join(a, LavaMode.LITTLE, false));
            seen.add(rig.site().layout());
            assertEquals(0, rig.differences(rig.site()), "the room is the game's layout");
            assertFalse(rig.site().collides(a.x, a.y, a.z), "on the sofa, never inside a piece");
            assertEquals(rig.site().spawn(game == 0 ? 0 : 0).y(), a.y, 1e-9);
            rig.service.left(a.id);
            a.session = false;
            assertEquals(0, rig.differences(), "back as built the moment it is over");
            rig.ticks(200);
        }
        assertEquals(4, seen.size(), "four games, four rooms");
    }

    @Test
    void aChildWhoNeverMovesIsNeverLeftInTheLava() {
        Rig rig = new Rig().built();
        FakeLavaHost.P lilah = rig.host.player("Lilah");
        rig.join(lilah, LavaMode.LITTLE, true);
        rig.tick();
        rig.toFloor(lilah);
        int base = lilah.teleportedTo.size();
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
        assertEquals(base + 3, lilah.teleportedTo.size(), "put back once each time the lava came");
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
        List<LavaLayout.Placed> layout = rig.site().layout();
        assertNull(rig.join(max, LavaMode.LITTLE, true));
        assertTrue(round == rig.service.round(), "the same round");
        assertEquals(layout, rig.site().layout(), "the same room: a second child doesn't move the furniture");
        assertEquals(List.of(lilah.id, max.id), round.members());
        assertEquals(LavaHost.Kit.PLAY, max.kit);
        assertFalse(max.x == lilah.x && max.z == lilah.z, "another spot on the sofa");
        assertEquals(LavaText.ALREADY_IN, rig.service.joinRefusal(max.id, LavaMode.LITTLE, true));
        for (int i = 0; i < 2000 && !round.over(); i++) {
            rig.play(List.of(max, lilah));
            rig.tick();
        }
        assertTrue(round.over());
        assertEquals(List.of(lilah.id, max.id), rig.host.scored.get(0).players(), "they finished it together");
        assertEquals(4L, rig.host.bests.get(max.id + ":lava:little"));
        assertEquals(4L, rig.host.bests.get(lilah.id + ":lava:little"));
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
        assertEquals(0, rig.differences(), "the room is its plan again, the furniture where it was built");
        assertEquals(0, rig.host.stars.size());
        assertNull(b.bar);
        assertEquals(0, rig.host.scored.size(), "a round nobody finished records nothing");
        assertEquals(0, rig.world.tickets.size(), "the chunks are let go");
        rig.ticks(300);
        assertTrue(rig.service.open(), "the empty room was checked against its plan again, and is open");
        assertEquals(0, rig.world.tickets.size());
        assertNull(rig.join(a, LavaMode.GROUP, false), "the room is free for anything now");
        rig.service.left(a.id);
        rig.service.left(a.id); // twice is nothing
        assertNull(rig.service.round());
    }

    /**
     * Every stop leaves the room as built: no lava, no glow, no slime, the furniture where it was built, whatever the
     * mode and phase, whatever moved and whichever surprise was on.
     */
    @Test
    void aStopAtAnyPhaseInAnyModeLeavesNoLavaAndTheRoomAsBuilt() {
        for (LavaMode mode : LavaMode.values()) {
            for (LavaRound.Phase phase : new LavaRound.Phase[]{LavaRound.Phase.GATHER, LavaRound.Phase.SAFE,
                    LavaRound.Phase.WARNING, LavaRound.Phase.LAVA, LavaRound.Phase.DRAIN}) {
                for (int extra : new int[]{0, 3, 25, 55}) {
                    if ((phase == LavaRound.Phase.GATHER && mode == LavaMode.LITTLE)
                            || (phase == LavaRound.Phase.DRAIN && mode == LavaMode.CHALLENGE)) {
                        continue; // Little doesn't gather; Challenge never drains
                    }
                    Rig rig = new Rig(4, true).built();
                    FakeLavaHost.P p = rig.host.player("Alex");
                    rig.join(p, mode, false);
                    List<FakeLavaHost.P> good = mode == LavaMode.LITTLE ? List.of() : List.of(p); // Little: no end
                    if (phase != LavaRound.Phase.GATHER) {
                        rig.service.startNow(p.id);
                        // a second round: the furniture has moved (Group, Challenge), a surprise may be on, and in a
                        // Challenge lava is already standing in the room during its safe phase
                        rig.until(LavaRound.Phase.LAVA, good);
                        rig.until(mode == LavaMode.CHALLENGE ? LavaRound.Phase.SAFE : LavaRound.Phase.DRAIN, good);
                        if (mode != LavaMode.CHALLENGE) {
                            rig.until(LavaRound.Phase.SAFE, good);
                        }
                    }
                    rig.until(phase, good);
                    for (int i = 0; i < extra; i++) {
                        rig.play(good);
                        rig.tick();
                    }
                    String at = mode + " in " + rig.service.round().phase() + " +" + extra;
                    rig.service.stop();
                    assertEquals(0, rig.count(LAVA), "no lava after a stop: " + at);
                    assertEquals(0, rig.count(GLOW), "no glowing floor after a stop: " + at);
                    assertEquals(0, rig.count(SLIME), "no slime after a stop: " + at);
                    assertEquals(0, rig.differences(), "the room is exactly as built after a stop: " + at);
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
        for (int i = 0; i < 6000 && (rig.service.writer() == null || rig.service.writer().level() < 3); i++) {
            rig.play(List.of(p));
            rig.tick();
        }
        int left = rig.count(LAVA);
        assertTrue(left > 3000, "three layers of lava standing in the room: " + left);
        assertTrue(rig.differences() > left, "and the furniture somewhere else");
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
        assertEquals(0, rig.differences(), "and put the furniture back where it was built");
        assertNull(rig.join(next, LavaMode.LITTLE, true));
    }

    @Test
    void theEmptyRoomIsCheckedAgainAndHealsWhateverChanged() {
        Rig rig = new Rig().built();
        FakeLavaHost.P p = rig.host.player("Alex");
        rig.join(p, LavaMode.LITTLE, false);
        rig.ticks(40);
        Box box = rig.service.box();
        rig.service.left(p.id);
        p.session = false;
        p.at(0.5, 64, 0.5);
        rig.world.put(box.minX() + 20, box.minY() + 11, box.minZ() + 20, LavaWriter.LAVA); // something went wrong
        rig.world.put(box.minX() + 3, box.minY(), box.minZ() + 40, "minecraft:air");
        assertEquals(2, rig.differences());
        rig.ticks(400);
        assertEquals(0, rig.differences(), "the check that follows an emptied room puts it right");
        assertTrue(rig.service.open());
    }

    // ---- Group --------------------------------------------------------------------------------------

    @Test
    void groupOneSharedCountStarGoalsHeartsAndTheLavaRisesEveryRound() {
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
        assertEquals(6, round.roundGoal(), "two players: six of the round's eight stars");

        assertEquals(4, rig.collectAll(a));
        assertEquals(4, round.stars());
        assertEquals(LavaText.groupBar(4, 1, 3, 4, 6, 3, 3), b.bar, "one count, the round's goal, the hearts, the"
                + " rounds as dots");

        // round 1: one deep. Blake stays on the floor: put back, and the group loses a star
        rig.toFloor(b);
        rig.until(LavaRound.Phase.WARNING);
        LavaSite.Spot high = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, a.x, a.y, a.z);
        a.at(high.x(), high.y(), high.z());
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertEquals(1, rig.service.writer().level());
        assertEquals(3, round.stars(), "a touch costs the group one star");
        assertTrue(b.heard().contains("[bar] " + LavaText.LOST_STAR));
        assertEquals(0, b.sessionsEnded, "nobody is out in Group");
        // 0.57.0: stars on the furniture during the lava, where they can be got to without touching it
        assertEquals(2, rig.host.stars.size(), "two at a time");
        for (double[] s : rig.host.stars.values()) {
            LavaSite.Stand st = rig.site().standUnder(s[0], s[1] - LavaService.STAR_UP, s[2]);
            assertNotNull(st, "over a place to stand");
            assertTrue(rig.site().dry(st, 1), "dry: " + st);
        }
        assertEquals(4, rig.collectAll(a), "two waves of two");
        assertEquals(7, round.stars());
        rig.until(LavaRound.Phase.DRAIN);
        assertTrue(a.titles.contains(LavaText.GOAL_BIG), "the goal reached: a tick for everyone");
        assertTrue(a.sounds.contains(LavaHost.Cue.GOAL));

        // round 2: the furniture warns, moves, and Alex rides the piece under him; nobody collects: a heart goes
        rig.until(LavaRound.Phase.SAFE);
        assertTrue(round.shifting());
        assertTrue(a.titles.contains(LavaText.SHIFT_BIG), "arrows both ways: the furniture is moving");
        assertTrue(a.sounds.contains(LavaHost.Cue.SHIFT));
        assertEquals(0, rig.host.stars.size(), "no stars while it moves");
        LavaSite.Spot seat = rig.site().spawn(3);
        a.at(seat.x(), seat.y(), seat.z());
        List<LavaLayout.Placed> before = rig.site().layout();
        for (int i = 0; i < 200 && round.shifting(); i++) {
            rig.tick();
            assertTrue(rig.host.fx.contains(LavaHost.Fx.SHIFT), "the pieces sparkle before they move");
        }
        assertNotEquals(before, rig.site().layout(), "a new layout");
        assertEquals(0, rig.differences(rig.site()), "the room is the new layout, block for block");
        assertTrue(a.sounds.contains(LavaHost.Cue.MOVED));
        assertEquals(0, rig.site().pieceOf(rig.site().standUnder(a.x, a.y, a.z)), "Alex rode the sofa");
        assertEquals(LavaPlanner.SEAT_FEET + rig.service.box().minY(), a.y, 1e-9, "still on its seat");
        assertEquals(4, rig.host.stars.size(), "then the stars come out");
        rig.until(LavaRound.Phase.DRAIN);
        assertEquals(2, round.hearts(), "the round's goal missed: a heart goes");
        assertTrue(a.titles.contains(LavaText.MISSED_BIG));
        assertTrue(a.sounds.contains(LavaHost.Cue.MISSED));

        // round 3: three deep; from the seat (feet 3) Alex is wet at level 3: the low furniture goes first
        rig.until(LavaRound.Phase.WARNING);
        LavaSite.Spot seat3 = rig.site().spawn(3);
        a.at(seat3.x(), seat3.y(), seat3.z());
        int moves = a.teleportedTo.size();
        rig.until(LavaRound.Phase.LAVA);
        rig.ticks(3 * LavaWriter.LAYER_TICKS + 2);
        assertEquals(3, rig.service.writer().level(), "round 3: three deep");
        assertEquals(moves + 1, a.teleportedTo.size(), "the seat is under: put back");
        assertTrue(a.y >= rig.service.box().minY() + 4, "on ground that is dry at this level");

        // after the last round's drain: the total, on Group's new board, for both
        for (int i = 0; i < 2000 && !round.over(); i++) {
            rig.tick();
        }
        assertTrue(round.over());
        LavaResult result = rig.host.scored.get(0);
        assertEquals(LavaMode.GROUP, result.mode());
        assertEquals(round.stars(), result.stars());
        assertEquals(3, result.rounds());
        assertEquals(1, result.goals());
        assertEquals(1, result.hearts());
        assertEquals((long) result.stars(), rig.host.bests.get(a.id + ":lava:group2"));
        assertEquals((long) result.stars(), rig.host.bests.get(b.id + ":lava:group2"));
        assertEquals(0, rig.count(LAVA));
        assertEquals(0, rig.differences(), "the furniture back where it was built");
        assertTrue(a.heard().contains("Together you collected"), a.heard());
    }

    /** A piece that lands where someone stands lifts them onto it: nobody is ever left inside a block. */
    @Test
    void whenTheFurnitureMovesNobodyIsLeftInsideIt() {
        for (int game = 0; game < 4; game++) {
            Rig rig = new Rig().built();
            List<FakeLavaHost.P> ps = new ArrayList<>();
            for (int n = 0; n < 6; n++) {
                FakeLavaHost.P p = rig.host.player("P" + n);
                rig.join(p, LavaMode.GROUP, false);
                ps.add(p);
            }
            rig.host.now += game * 7919L;
            rig.service.startNow(ps.get(0).id);
            LavaRound round = rig.service.round();
            for (int r = 0; r < 2; r++) {
                rig.until(LavaRound.Phase.DRAIN);
                rig.until(LavaRound.Phase.SAFE);
                // spread everyone over the room: on the floor, on pieces, anywhere
                List<LavaSite.Stand> all = rig.site().reach();
                for (int n = 0; n < ps.size(); n++) {
                    LavaSite.Stand s = all.get((n * 997 + game * 131 + r * 17) % all.size());
                    ps.get(n).at(s.x() + 0.5, s.y(), s.z() + 0.5);
                }
                for (int i = 0; i < 200 && round.shifting(); i++) {
                    rig.tick();
                }
                for (FakeLavaHost.P p : ps) {
                    assertFalse(rig.site().collides(p.x, p.y, p.z), "nobody inside a piece: " + p.name + " at "
                            + p.x + "," + p.y + "," + p.z);
                    assertNotNull(rig.site().standUnder(p.x, p.y, p.z), "standing on something: " + p.name);
                }
            }
            rig.service.stop();
            assertEquals(0, rig.differences());
        }
    }

    // ---- the surprises ------------------------------------------------------------------------------

    /** Each surprise does what it warns of, and every one is gone by the round after. */
    @Test
    void theSurprisesDoWhatTheyWarnOf() {
        Set<LavaRound.Surprise> seen = new HashSet<>();
        for (int game = 0; game < 30 && seen.size() < 5; game++) {
            Rig rig = new Rig(4, true).built();
            rig.host.now += game * 104_729L;
            FakeLavaHost.P a = rig.host.player("Alex");
            rig.join(a, LavaMode.GROUP, false);
            rig.service.startNow(a.id);
            LavaRound round = rig.service.round();
            for (int i = 0; i < 20_000 && !round.over(); i++) {
                LavaRound.Surprise s = round.surprise();
                if (s != null && round.phase() == LavaRound.Phase.WARNING && round.left() == 1) {
                    rig.play(List.of(a));
                    List<LavaLayout.Placed> was = rig.site().layout();
                    int deep = round.nextLevel();
                    rig.tick(); // the lava comes
                    switch (s) {
                        case SINK -> {
                            long gone = rig.site().layout().stream().filter(java.util.Objects::isNull).count();
                            if (gone == 1) {
                                seen.add(s);
                                assertEquals(0, rig.differences(rig.site()) - rig.count(GLOW) - rig.count(LAVA),
                                        "a piece sank: the room is the layout without it");
                            }
                            assertTrue(a.titles.contains(LavaText.CLIMB_BIG));
                        }
                        case PATCHES -> {
                            rig.ticks(deep * LavaWriter.LAYER_TICKS + 1);
                            assertTrue(rig.count(LAVA) > 0, "lava on one side");
                            int full = 0;
                            for (int l = 1; l <= deep; l++) {
                                full += rig.site().layer(l).size();
                            }
                            assertTrue(rig.count(LAVA) < full * 3 / 4, "on one side only, first");
                            for (int k = 0; k < 200 && round.phase() == LavaRound.Phase.LAVA
                                    && rig.count(LAVA) < full; k++) {
                                rig.tick();
                            }
                            assertEquals(full, rig.count(LAVA), "then everywhere, while the lava is still up");
                            seen.add(s);
                        }
                        case FAST_RISE -> {
                            int full = 0;
                            for (int l = 1; l <= deep; l++) {
                                full += rig.site().layer(l).size();
                            }
                            assertEquals(full, rig.count(LAVA), "all at once");
                            seen.add(s);
                        }
                        case STAR_SHOWER -> {
                            assertTrue(rig.host.fx.contains(LavaHost.Fx.SHOWER));
                            assertEquals(3, rig.host.stars.size(), "three at a time");
                            seen.add(s);
                        }
                        case BOUNCY -> {
                            assertEquals(9, rig.count(SLIME), "a cushion of slime");
                            assertNotEquals(was, rig.service.built().layout());
                            seen.add(s);
                        }
                    }
                }
                if (round.phase() == LavaRound.Phase.SAFE && round.surprise() == null) {
                    assertEquals(0, rig.count(SLIME), "the bouncy cushion is a cushion again the round after");
                    for (LavaLayout.Placed p : rig.site().layout()) {
                        assertNotNull(p, "and the sunk piece is back");
                    }
                }
                rig.play(List.of(a));
                rig.tick();
            }
            rig.service.stop();
            assertEquals(0, rig.differences());
        }
        assertEquals(Set.of(LavaRound.Surprise.values()), seen, "every surprise seen and checked");
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
        assertEquals(4, rig.host.stars.size(), "0.57.0: a star each and two more: keep collecting");
        assertTrue(a.bar.startsWith("&cRound 1"), a.bar);
        assertTrue(a.bar.contains("★ 0/1"), a.bar);
        FakeLavaHost.P late = rig.host.player("Casey");
        assertEquals(LavaText.CHALLENGE_GOING, rig.join(late, LavaMode.CHALLENGE, false), "nobody joins once it started");

        // both play well for two rounds
        rig.until(LavaRound.Phase.LAVA, List.of(a, b));
        rig.until(LavaRound.Phase.SAFE, List.of(a, b));
        assertEquals(2, round.round());
        assertEquals(1, rig.service.writer().level(), "the lava stays: it never drains");
        assertEquals(List.of(a.id, b.id), round.alive());
        rig.until(LavaRound.Phase.LAVA, List.of(a, b));
        assertEquals(rig.site().layer(1).size() + rig.site().layer(2).size() > 0, true);
        rig.until(LavaRound.Phase.SAFE, List.of(a, b));
        assertEquals(2, rig.service.writer().level());
        assertEquals(List.of(a.id, b.id), round.alive(), "and at two");

        // round 3: Blake stops: he stays on the floor-level of a low step and the lava gets him
        for (int i = 0; i < 2000 && !round.over() && round.phase() != LavaRound.Phase.LAVA; i++) {
            rig.play(List.of(a));
            if (round.phase() == LavaRound.Phase.WARNING) {
                LavaSite.Stand low = null;
                for (LavaSite.Stand s : rig.site().safe(2)) {
                    if (!rig.site().dry(s, 3) && s.y() % 1 == 0) { // a whole block's top, feet 3
                        low = s;
                        break;
                    }
                }
                b.at(low.x() + 0.5, low.y(), low.z() + 0.5);
            }
            rig.tick();
        }
        rig.ticks(3 * LavaWriter.LAYER_TICKS + 2);
        assertTrue(round.over(), "one left standing: it is over");
        assertTrue(b.teleportedTo.size() >= 2, "up to the gallery, then down again for the party");
        assertTrue(b.heard().contains("The lava got you!"), b.heard());
        assertTrue(b.sounds.contains(LavaHost.Cue.OUT));
        assertEquals(0, b.sessionsEnded, "out, but still in the room");
        assertFalse(rig.service.built().inGallery(b.x, b.y, b.z), "down for the end");
        LavaResult result = rig.host.scored.get(0);
        assertEquals(List.of(a.id), result.winners());
        assertEquals(2, result.standing(b.id).place());
        assertEquals(2, result.standing(b.id).survived(), "Blake lasted two rounds");
        assertEquals(2L, rig.host.bests.get(b.id + ":lava:challenge2"));
        assertEquals(2L, rig.host.bests.get(a.id + ":lava:challenge2"), "the winner's rounds too");
        assertTrue(a.heard().contains("You're the last one standing!"), a.heard());
        assertTrue(b.heard().contains("Last one standing: &eAlex"), b.heard());
        assertEquals(0, rig.count(LAVA), "the lava is gone the moment it ends");
        assertEquals(0, rig.differences());
        assertEquals(LavaHost.Kit.OVER, b.kit);
    }

    @Test
    void theOneWhoIsOutWatchesFromTheGalleryAndTheBestLastToTheFlood() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        FakeLavaHost.P b = rig.host.player("Blake");
        FakeLavaHost.P c = rig.host.player("Casey");
        for (FakeLavaHost.P p : List.of(a, b, c)) {
            rig.join(p, LavaMode.CHALLENGE, false);
        }
        rig.service.startNow(a.id);
        LavaRound round = rig.service.round();
        rig.until(LavaRound.Phase.WARNING, List.of(a, b));
        rig.toFloor(c);
        rig.until(LavaRound.Phase.LAVA, List.of(a, b));
        rig.ticks(LavaWriter.LAYER_TICKS + 2);
        assertFalse(round.over(), "two still standing");
        assertEquals(List.of(a.id, b.id), round.alive());
        assertTrue(rig.site().inGallery(c.x, c.y, c.z), "out: up to the gallery, to watch");
        assertEquals(LavaHost.Kit.WATCH, c.kit);
        assertTrue(c.heard().contains(LavaText.WATCHING));
        assertTrue(a.bar.contains("2 standing"), a.bar);
        // the lava rises to the flood, the room moving every round: the two go under together at the last
        for (int i = 0; i < 40_000 && !round.over(); i++) {
            rig.play(List.of(a, b));
            rig.tick();
            assertFalse(rig.site().touches(rig.service.writer() == null ? 0 : rig.service.writer().level(), c.x, c.y,
                    c.z), "the gallery stays dry");
        }
        assertTrue(round.over());
        LavaResult result = rig.host.scored.get(0);
        assertEquals(List.of(a.id, b.id), result.winners(), "the last ones standing, together");
        assertEquals(LavaPlanner.SAFE_TOP, result.standing(a.id).survived(), "they lasted every round there is dry"
                + " ground for");
        assertEquals(3, result.standing(c.id).place());
        assertFalse(rig.service.built().inGallery(c.x, c.y, c.z), "the watcher is brought down for the party");
        assertEquals(0, rig.count(LAVA));
        assertEquals(0, rig.differences());
    }

    /** The owner: "it seems like all you have to do is get the stars and wait now". Not in Challenge. */
    @Test
    void aloneInChallengeYouMustKeepCollectingAndTheScoreIsTheRoundsLasted() {
        Rig rig = new Rig().built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.CHALLENGE, false);
        assertEquals(LavaRound.Phase.GATHER, rig.service.round().phase());
        rig.ticks(LavaRound.GATHER_TICKS + 2);
        LavaRound round = rig.service.round();
        assertEquals(LavaRound.Phase.SAFE, round.phase(), "nobody else came: it starts by itself, alone");
        for (int r = 1; r <= 2; r++) {
            rig.until(LavaRound.Phase.LAVA, List.of(a));
            rig.until(LavaRound.Phase.SAFE, List.of(a));
        }
        // round 3: Alex climbs high and waits, collecting nothing
        LavaSite.Spot top = rig.site().nearestSafe(LavaPlanner.SAFE_TOP, a.x, a.y, a.z);
        a.at(top.x(), top.y(), top.z());
        for (int i = 0; i < 4000 && !round.over(); i++) {
            rig.tick();
        }
        assertTrue(round.over(), "a round without a star: out");
        LavaResult result = rig.host.scored.get(0);
        assertTrue(result.solo());
        assertEquals(2, result.standing(a.id).survived());
        assertEquals(2L, rig.host.bests.get(a.id + ":lava:challenge2"));
        assertTrue(a.heard().contains("No star this round"), a.heard());
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
        assertEquals(LavaText.busy(LavaMode.GROUP), rig.service.joinRefusal(lilah.id, LavaMode.CHALLENGE, true),
                "the cabinet's Challenge waits too");
        assertNull(rig.join(lilah, LavaMode.LITTLE, true), "but Little from the cabinet joins the Group round:"
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
        assertNull(other.service.joinRefusal(kid.id, LavaMode.CHALLENGE, true),
                "0.57.0: from the cabinet she (or her dad) may pick the Challenge gathering");
        other.service.startNow(c.id);
        assertEquals(LavaText.busy(LavaMode.CHALLENGE), other.join(kid, LavaMode.LITTLE, true));
        assertEquals(LavaText.CHALLENGE_GOING, other.service.joinRefusal(kid.id, LavaMode.CHALLENGE, true));
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
        Set<String> spots = new HashSet<>();
        for (FakeLavaHost.P p : in) {
            assertTrue(spots.add(p.x + "," + p.z), "each arrives on a spot of their own");
            assertFalse(rig.site().collides(p.x, p.y, p.z));
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
        List<LavaLayout.Placed> firstRoom = null;
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
        assertEquals(rig.site().spawn(0).y(), a.y, "back on the sofa, wherever it is now");
        assertFalse(rig.site().collides(a.x, a.y, a.z));
        assertFalse(rig.site().collides(b.x, b.y, b.z));
        assertEquals(LavaText.starRow(0, 4), a.bar);
        assertNull(firstRoom);
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
        assertFalse(same.site().collides(c.x, c.y, c.z), "on the new sofa, not inside the new room");
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
        assertTrue(String.join("\n", rig.service.statusLines()).contains("box x 7312..7359, y 160..175, z 8544..8591"
                + " in games"), String.join("\n", rig.service.statusLines()));
        rig.built();
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.GROUP, false);
        rig.service.startNow(a.id);
        String status = String.join("\n", rig.service.statusLines());
        assertTrue(status.contains("plan " + rig.service.built().plan().hash()), status);
        assertTrue(status.contains("Group, safe: 1/6 here, round 1"), status);
        assertTrue(status.contains("the furniture moved"), status);
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

    @Test
    void movingRoomOffKeepsTheFurnitureWhereItWasBuilt() {
        Rig rig = new Rig().built();
        LavaSettings s = rig.host.settings;
        rig.host.settings = new LavaSettings(true, s.origin(), 6, 4, 2, 2, 3, false, false, 3);
        FakeLavaHost.P a = rig.host.player("Alex");
        rig.join(a, LavaMode.GROUP, false);
        rig.service.startNow(a.id);
        for (int r = 0; r < 2; r++) {
            rig.until(LavaRound.Phase.DRAIN);
            rig.until(LavaRound.Phase.SAFE);
            assertFalse(rig.service.round().shifting());
            assertEquals(rig.service.built().layout(), rig.site().layout());
        }
    }
}
