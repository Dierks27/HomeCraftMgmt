package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.twentyone.TableRound;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneSettings;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Phase;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Seat;
import com.dierks.homecraft.games.cpu.CpuChoice;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuLevels;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.TableNpcs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC players at the Game Parlor's Twenty-One table, run over the framework's real gate, rounds and database:
 *
 * <ul>
 *   <li>an NPC joins a player who has sat alone for {@code join_after_seconds}, never before, never one who has NPCs
 *       off or sent one away;</li>
 *   <li>its hand is pretend: no round, no tokens, no row anywhere; every real hand is paid exactly as it would be;</li>
 *   <li>it is always ready, never waited on and can't be hurried; it moves within a second or two;</li>
 *   <li>it gives its seat to a real player; a show at the empty table is dropped the moment a real player sits;</li>
 *   <li>if anyone at the table wants easy, every NPC there plays easy.</li>
 * </ul>
 */
class NpcTableTest {

    /** The NPC side, as a test sets it. */
    static final class Npcs implements TableNpcs {
        final Set<UUID> off = new HashSet<>();
        final Map<UUID, CpuChoice.Pref> prefs = new HashMap<>();
        long joinAfterMs = 20_000;
        boolean audience;
        final SplittableRandom rng = new SplittableRandom(5);

        @Override
        public boolean wants(UUID real) {
            return !off.contains(real);
        }

        @Override
        public CpuLevel level(CpuPlayer npc, Collection<UUID> reals, String gameId) {
            List<CpuChoice.Pref> p = new ArrayList<>();
            for (UUID r : reals) {
                p.add(prefs.getOrDefault(r, CpuChoice.Pref.ANY));
            }
            return CpuLevels.forGame(npc.level(), p, null);
        }

        @Override
        public String label(CpuPlayer npc) {
            return CpuRoster.label(npc.name(), npc.level());
        }

        @Override
        public List<CpuPlayer> roster() {
            return CpuRoster.ALL;
        }

        @Override
        public long joinAfterMs() {
            return joinAfterMs;
        }

        @Override
        public boolean audience() {
            return audience;
        }

        @Override
        public RandomGenerator rng() {
            return rng;
        }
    }

    private Host host;
    private GamesService games;
    private TwentyOneTable table;
    private final Npcs npcs = new Npcs();

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "twenty_one", TwentyOneSettings.defaults());
        games = GamesKit.service(host, List.of(TwentyOne.SPEC));
        table = ((TwentyOne) games.game("twenty_one")).table();
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        games.parlorGuest(f.id);
        return f;
    }

    private Seat sit(Fake f) {
        return table.sit(f.player, () -> { });
    }

    private void seconds(int n) {
        for (int i = 0; i < n; i++) {
            host.move(1_000);
            table.cpuTick(npcs);
        }
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private Seat npcSeat() {
        for (Seat s : table.seats()) {
            if (s.npc() != null) {
                return s;
            }
        }
        return null;
    }

    /** A shoe whose first deal to {@code seats} seats leaves every seat a move. */
    private static long plainShoe(int seats) {
        List<TwentyOneHand.Terms> terms = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            terms.add(TwentyOneSettings.defaults().odds(5).terms());
        }
        for (long seed = 1; seed < 10_000; seed++) {
            TableRound r = new TableRound(new TwentyOneHand.Shoe(seed, 0), terms);
            boolean plain = !r.over();
            for (int i = 0; i < seats; i++) {
                plain &= r.hand(i).playing();
            }
            if (plain) {
                return seed;
            }
        }
        throw new AssertionError("no plain deal");
    }

    // ---- joining ------------------------------------------------------------------------------------------

    @Test
    void anNpcJoinsAPlayerWhoSatAloneLongEnoughAndNotBefore() {
        Fake alex = player("Alex");
        sit(alex);
        seconds(19);
        assertNull(npcSeat(), "not before join_after_seconds");
        seconds(2);
        Seat npc = npcSeat();
        assertNotNull(npc, "after it, an NPC sits down");
        assertTrue(npc.ready(), "ready at once: nobody waits on it");
        assertTrue(npc.name().contains(CpuRoster.MARKER), "its name has the gear: " + npc.name());
        assertFalse(table.waitingOn(npc), "the table never waits on an NPC");
        assertEquals(2, table.seats().size());
        seconds(60);
        assertEquals(1, table.npcCount(), "one joins; no more pile in while it sits there");
    }

    @Test
    void nobodyJoinsAPlayerWithNpcsOffOrWhoSentOneAway() {
        Fake alex = player("Alex");
        npcs.off.add(alex.id);
        sit(alex);
        seconds(60);
        assertNull(npcSeat(), "NPCs off: none ever joins");

        Fake sam = player("Sam");
        table.leave(alex.id);
        sit(sam);
        seconds(21);
        Seat npc = npcSeat();
        assertNotNull(npc);
        assertTrue(table.sendAway(sam.player, npc), "Sam asks it to get up");
        assertNull(npcSeat());
        seconds(120);
        assertNull(npcSeat(), "no NPC joins Sam again until Sam gets up");
    }

    @Test
    void npcsLeaveATableWhereSomeoneHasThemOff() {
        Fake alex = player("Alex");
        sit(alex);
        seconds(21);
        assertNotNull(npcSeat());
        Fake sam = player("Sam");
        npcs.off.add(sam.id);
        sit(sam);
        seconds(1);
        assertNull(npcSeat(), "Sam has NPCs off: the NPC gets up between rounds");
    }

    // ---- playing ------------------------------------------------------------------------------------------

    @Test
    void anNpcsHandIsPretendAndEveryRealHandIsPaidAsItWouldBe() throws Exception {
        Fake alex = player("Alex");
        Seat mine = sit(alex);
        seconds(21);
        Seat npc = npcSeat();
        assertNotNull(npc);
        table.seedNextShoe(plainShoe(2));
        table.ready(alex.player, true);
        assertEquals(Phase.PLAYING, table.phase(), "Alex was the only one to wait for: the cards come at once");
        assertEquals(195, host.balanceOf(alex.id), "Alex's stake went in");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds"), "one round: Alex's. The NPC has none");
        // Alex stands; the NPC plays its own hand by itself, a second or two later
        table.stand(alex.player);
        for (int i = 0; i < 20 && table.phase() == Phase.PLAYING; i++) {
            seconds(1);
        }
        assertEquals(Phase.OPEN, table.phase(), "the round is over");
        TableRound.Hand hand = table.round().hand(mine.hand());
        assertEquals(195 + hand.payout(), host.balanceOf(alex.id), "Alex's hand paid once, as it would alone");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE player = '" + npc.id() + "'"),
                "nothing about the NPC is written");
        assertEquals(0, host.balanceOf(npc.id()), "an NPC has no tokens and never gets any");
        assertTrue(npc.ready(), "ready for the next one at once");
    }

    @Test
    void anNpcMovesWithinTwoSecondsAndCantBeHurried() {
        Fake alex = player("Alex");
        sit(alex);
        seconds(21);
        Seat npc = npcSeat();
        table.seedNextShoe(plainShoe(2));
        table.ready(alex.player, true);
        table.stand(alex.player);
        assertEquals(npc, table.turn(), "the NPC's turn");
        Seat me = table.seat(alex.id);
        assertFalse(table.canHurry(me), "nobody can Hurry Up an NPC");
        assertEquals(-1, table.hurrySeconds());
        int waited = 0;
        while (table.turn() == npc && npc.hand() >= 0 && table.round().hand(npc.hand()).cards().size() == 2
                && waited < 5) {
            seconds(1);
            waited++;
        }
        assertTrue(waited <= 3, "it moved within a second or two (plus the second it noticed): " + waited);
    }

    @Test
    void anNpcGivesItsSeatToARealPlayer() {
        Fake alex = player("Alex");
        sit(alex);
        for (CpuPlayer c : CpuRoster.ALL.subList(0, 3)) {
            assertNotNull(table.sitNpc(c, npcs));
        }
        assertEquals(4, table.seats().size(), "the table is full: Alex and three NPCs");
        Fake sam = player("Sam");
        assertNotNull(sit(sam), "Sam sits down: an NPC gets up for Sam");
        assertEquals(4, table.seats().size());
        assertEquals(2, table.npcCount());
        assertEquals(List.of(alex.id, sam.id), table.realIds());
    }

    @Test
    void aShowAtTheEmptyTableIsDroppedTheMomentARealPlayerSits() {
        npcs.audience = true;
        for (int i = 0; i < 600 && table.phase() != Phase.PLAYING; i++) {
            seconds(1);
        }
        assertTrue(table.npcCount() > 0, "NPCs sat down at the empty table while someone was in the Parlor");
        assertEquals(Phase.PLAYING, table.phase(), "and played a round of their own");
        assertFalse(table.hasReal());
        Fake alex = player("Alex");
        assertNotNull(sit(alex));
        assertEquals(Phase.OPEN, table.phase(), "Alex never waits on a show: its round is over at once");
        table.ready(alex.player, true);
        assertEquals(Phase.PLAYING, table.phase(), "Alex's cards come at once (the NPCs are always ready)");
        assertTrue(table.seat(alex.id).hand() >= 0);
    }

    @Test
    void aTableOfNpcsAloneNeverOpensARound() throws Exception {
        npcs.audience = true;
        for (int i = 0; i < 900; i++) {
            seconds(1);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "a show touches no round");
        npcs.audience = false;
        for (int i = 0; i < 30 && table.phase() == Phase.PLAYING; i++) {
            seconds(1); // a show round being played is played out
        }
        seconds(1);
        assertEquals(0, table.npcCount(), "nobody in the Parlor: the NPCs get up between rounds");
    }

    @Test
    void ifAnyoneAtTheTableWantsEasyEveryNpcThereDealsEasy() {
        Fake lilah = player("Lilah");
        npcs.prefs.put(lilah.id, CpuChoice.Pref.EASY);
        sit(lilah);
        CpuPlayer hard = CpuRoster.byKey("ozzie");
        Seat npc = table.sitNpc(hard, npcs);
        assertEquals(CpuLevel.HARD, hard.level());
        assertEquals(CpuLevel.EASY, npc.plays(), "Ozzie plays easy at Lilah's table");
        Fake jeff = player("Jeff");
        sit(jeff);
        table.seedNextShoe(plainShoe(3));
        table.ready(lilah.player, true);
        table.ready(jeff.player, true);
        assertEquals(CpuLevel.EASY, npc.plays(), "with Jeff (any) there too, still easy");
        assertEquals(Phase.PLAYING, table.phase());
    }
}
