package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 6: auction wins delivered mid-session, through Clubhouse -> party race (parkour) -> Clubhouse ->
 * home, are never deleted and never doubled (the real world-session state machine under the Clubhouse's
 * door and race mode, the real no-push team; only the server's I/O is mirrored).
 *
 * <p>Ben holds 33 of his own things (3 storage slots free). A Mini reaches him while he waits in the
 * Clubhouse; it is banked into his row's carry at the hand-over to the race, before the race's kit. A book
 * reaches him mid-race and stays in his inventory through the trip back to the Clubhouse (a kit strip,
 * never a clear); a second Mini reaches him there. At home he gets all three once. Two variants: the
 * database refuses the bank at the hand-over (the Mini goes straight back into his inventory), and a crash
 * right after the bank (the Mini comes home once at the next join). A carry that doesn't fit waits in the
 * row for {@code /hcm leave}.
 */
class CrossFeatureJourneyDeliveriesTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    /** A hand-built parkour course in the Games world: runners join the no-push team. */
    private static final Course LAVA = new Course("lava_leap", TrialKind.PARKOUR, "Lava Leap", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5),
            null, null, true, false, 1);

    private JourneyBench j;
    private Course lava;
    private Player ava;
    private Player ben;
    private PartyLobby lobby;
    private PartyRace party;

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, Clubhouse.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(), "clubhouse", ClubhouseSettings.defaults());
        lava = j.course(LAVA);
        ava = j.player("Ava", "SURVIVAL", "blue");
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    /** Ben with {@code own} of his own things: "diamond x3" and junk to fill the rest. */
    private void ben(int own) {
        List<String> items = new ArrayList<>(List.of("diamond x3"));
        for (int i = 1; i < own; i++) {
            items.add("junk:" + i);
        }
        ben = j.player("Ben", "SURVIVAL", "red", items);
    }

    private boolean onTeam(Player p) {
        return NoPush.TEAM.equals(j.board.teamOf(p.getName()));
    }

    /** Ava's party on the lava course; both go to the Clubhouse. */
    private void clubhouse() {
        lobby = j.games.parties().create(PartyLobby.Kind.RACE, lava.id(), id(ava), 8);
        assertNull(j.games.parties().join(lobby.id(), id(ben)), "Ben joins Ava's party");
        assertNull(j.club.enter(id(ava), ClubVisits.Kind.PARTY, false), "Ava goes to the Clubhouse");
        assertNull(j.club.enter(id(ben), ClubVisits.Kind.PARTY, false), "Ben goes to the Clubhouse");
        assertTrue(onTeam(ben), "in the Clubhouse, Ben is on the no-push team");
        assertTrue(j.pushes.isOn(id(ben)), "held (by the Clubhouse)");
    }

    /** A copy of PartyRaces.start, from the Clubhouse: both seated (their sessions handed over). */
    private void start() {
        assertNull(j.trials.party().startProblem(ava), "Ava may start");
        List<PartyRace.Racer> racers = new ArrayList<>();
        for (UUID m : lobby.members()) {
            assertTrue(j.door.seatable(m), "waiting in the Clubhouse");
            racers.add(new PartyRace.Racer(m, j.players.get(m).getName()));
        }
        assertNull(lobby.start(id(ava)), "the lobby starts");
        party = new PartyRace(lobby.id(), lava, racers, 0, () -> j.race.tick, line -> {
        });
        party.clubhouse(j.door.partyAfter());
        j.trials.party().running(party);
        for (PartyRace.Racer r : racers) {
            assertNull(j.race.seat(r.id(), lava, Laps.raced(lava, lava.start(), 0).course(), lava.start(), null, party),
                    r.name() + " is seated");
            party.seated(r.id(), lava.start());
        }
        assertTrue(party.seatingDone(), "on the grid");
    }

    private boolean racing(Player p) {
        TrialRun r = j.trials.run(id(p));
        return r != null && r.race != null && r.phase == TrialRun.Phase.RUNNING && r.progress != null;
    }

    private void tick() {
        j.bench.move(50);
        j.race.tick();
        if (party.tick(j.race.tick) == PartyRace.Step.END) {
            j.door.result(ClubRaces.partySheet(lava.name(), party.results(), 1), null); // a copy of PartyRaces.finish
            lobby.finish();
        }
    }

    /** Go, the race's 45 seconds, then Ben and Ava cross (Ben mid-race gets a book). */
    private void race(Runnable midRace) {
        for (int i = 0; i < 20 * 20 && !(racing(ava) && racing(ben)); i++) {
            tick();
        }
        assertTrue(racing(ben), "the race is on");
        assertTrue(onTeam(ben), "racing parkour, Ben is on the no-push team (race mode's)");
        for (int i = 0; i < 20 * 20; i++) {
            tick();
        }
        midRace.run();
        for (int i = 0; i < 25 * 20; i++) {
            tick();
        }
        j.race.cross(id(ben), 45_000);
        tick();
        tick();
        j.race.cross(id(ava), 46_000);
        for (int i = 0; i < 40 && party.state() != PartyRace.State.DONE; i++) {
            tick();
        }
        assertEquals(PartyRace.State.DONE, party.state(), "the race is over");
    }

    private long items(String item) {
        return j.rail.count(id(ben), item);
    }

    @Test
    void deliveriesInTheClubhouseAndMidRaceAllComeHomeOnceAndNothingIsDeleted() throws Exception {
        ben(33);
        assertEquals(3, j.rail.free(id(ben)), "3 storage slots free");
        clubhouse();

        // 2. An auction win while he waits
        assertTrue(j.rail.deliver(id(ben), "Mini #42"), "the Mini reaches Ben in the Clubhouse");

        // 3. The start: the hand-over banks the Mini before the race's kit
        start();
        assertEquals(List.of("Mini #42"), j.rail.carry(id(ben)), "the Mini is in the row's carry");
        assertTrue(j.rail.items(id(ben)).stream().allMatch(i -> i.startsWith("kit:trials:")),
                "the inventory holds only the race kit: " + j.rail.items(id(ben)));

        // 4-5. A book mid-race; the finish takes him back to the Clubhouse; a second Mini there
        race(() -> assertTrue(j.rail.deliver(id(ben), "Enchanted Book"), "a book reaches Ben mid-race"));
        assertEquals(ClubVisits.Kind.PARTY, j.club.visits().get(id(ben)).kind(), "Ben is back in the Clubhouse");
        assertEquals(1, items("Enchanted Book"), "the book is still in his inventory: a kit strip, never a clear");
        assertEquals(List.of("Mini #42"), j.rail.carry(id(ben)), "the carry is unchanged");
        assertFalse(j.rail.items(id(ben)).stream().anyMatch(i -> i.startsWith("kit:trials:")),
                "no race kit survives the hand-over: " + j.rail.items(id(ben)));
        assertTrue(onTeam(ben), "back in the Clubhouse: on the no-push team (restored first, then taken in)");
        assertTrue(j.pushes.isOn(id(ben)), "and held by the Clubhouse");
        assertTrue(j.rail.deliver(id(ben), "Mini #43"), "a second Mini reaches him in the Clubhouse");

        // 6. Leave game: home into a full inventory; then he drops junk and types /hcm leave
        j.rail.leave(id(ben), EndReason.QUIT_ITEM);
        j.rail.arriveAll();
        assertEquals(j.homes.get(id(ben)), j.rail.place(id(ben)), "home");
        for (String item : List.of("diamond x3", "Mini #42", "Enchanted Book", "Mini #43")) {
            assertEquals(1, items(item), item + " comes home exactly once");
        }
        assertEquals(0, j.rail.free(id(ben)), "his inventory is full now");
        assertTrue(j.rail.dropped(id(ben)).isEmpty(), "nothing was dropped at his feet");
        assertNull(j.rail.row(id(ben)), "everything was handed over: the row is finished");
        assertTrue(j.rail.drop(id(ben), "junk:1"), "Ben drops junk to make room");
        j.rail.leave(id(ben), EndReason.COMMAND);
        assertTrue(j.rail.messages(id(ben)).contains("&7You're not in a game."), "/hcm leave: nothing left to do");
        for (String item : List.of("diamond x3", "Mini #42", "Enchanted Book", "Mini #43")) {
            assertEquals(1, items(item), item + " still once");
        }
        assertFalse(j.rail.holdsKit(id(ben)), "no kit came home");
        assertEquals("red", j.board.teamOf("Ben"), "back on his own team");
        assertTrue(j.board.entries(NoPush.TEAM).stream().noneMatch("Ben"::equals), "no leftover member");

        // both races counted once
        assertEquals(45_000L, j.best(id(ben), Scores.course(lava.id())), "Ben's race is on the board");
        assertEquals(46_000L, j.best(id(ava), Scores.course(lava.id())), "and Ava's");
        assertEquals(1, j.told.courses(id(ben)), "Ben's finish counted once");
        assertEquals(1, j.told.courses(id(ava)), "Ava's too");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
        assertEquals(0, j.rail.severe(), "the world sessions logged nothing severe: " + j.rail.logged());
    }

    @Test
    void whenTheDatabaseRefusesTheBankTheMiniGoesStraightBackAndComesHomeOnce() throws Exception {
        ben(33);
        clubhouse();
        assertTrue(j.rail.deliver(id(ben), "Mini #42"), "the Mini reaches Ben in the Clubhouse");
        try (Statement st = j.bench.connection().createStatement()) {
            st.execute("CREATE TRIGGER no_bank BEFORE UPDATE ON game_saved_state BEGIN SELECT RAISE(ABORT, 'disk full');"
                    + " END");
        }
        start();
        try (Statement st = j.bench.connection().createStatement()) {
            st.execute("DROP TRIGGER no_bank");
        }
        assertTrue(j.rail.carry(id(ben)).isEmpty(), "nothing could be banked");
        assertEquals(1, items("Mini #42"), "the Mini went straight back into his inventory: never lost");
        race(() -> {
        });
        j.rail.leave(id(ben), EndReason.QUIT_ITEM);
        j.rail.arriveAll();
        assertEquals(1, items("Mini #42"), "and it is home exactly once");
        assertEquals(1, items("diamond x3"), "with his own things once");
        assertTrue(j.rail.dropped(id(ben)).isEmpty(), "nothing dropped");
        assertNull(j.rail.row(id(ben)), "the row is finished");
    }

    @Test
    void aCrashRightAfterTheBankBringsTheMiniHomeOnceAtTheNextJoin() throws Exception {
        ben(33);
        clubhouse();
        assertTrue(j.rail.deliver(id(ben), "Mini #42"), "the Mini reaches Ben in the Clubhouse");
        start();
        assertEquals(List.of("Mini #42"), j.rail.carry(id(ben)), "banked");
        j.rail.crash();
        j.rail.joined(id(ben));
        j.rail.arriveAll();
        assertEquals(j.homes.get(id(ben)), j.rail.place(id(ben)), "home after the crash");
        assertEquals(1, items("Mini #42"), "the Mini comes home once");
        assertEquals(1, items("diamond x3"), "his own things once");
        assertEquals(1, j.rail.applies(id(ben)), "his state was put back once");
        assertFalse(j.rail.holdsKit(id(ben)), "no kit");
        assertNull(j.rail.row(id(ben)), "the row is finished");
    }

    @Test
    void aCarryThatDoesNotFitWaitsInTheRowUntilHeMakesRoomAndTypesHcmLeave() throws Exception {
        ben(34);
        assertEquals(2, j.rail.free(id(ben)), "2 storage slots free");
        clubhouse();
        assertTrue(j.rail.deliver(id(ben), "Mini #42"), "a Mini in the Clubhouse");
        start();
        race(() -> assertTrue(j.rail.deliver(id(ben), "Enchanted Book"), "a book mid-race"));
        assertTrue(j.rail.deliver(id(ben), "Mini #43"), "a second Mini in the Clubhouse");
        j.rail.leave(id(ben), EndReason.QUIT_ITEM);
        j.rail.arriveAll();
        assertEquals(0, j.rail.free(id(ben)), "the inventory is full");
        assertNotNull(j.rail.row(id(ben)), "what didn't fit stays in the row");
        assertEquals(1, j.rail.carry(id(ben)).size(), "one thing waits: " + j.rail.carry(id(ben)));
        assertTrue(j.rail.messages(id(ben)).stream().anyMatch(m -> m.contains("didn't fit")), j.rail.messages(id(ben))
                .toString());
        assertTrue(j.rail.drop(id(ben), "junk:1"), "he makes room");
        j.rail.leave(id(ben), EndReason.COMMAND);
        j.rail.arriveAll();
        for (String item : List.of("diamond x3", "Mini #42", "Enchanted Book", "Mini #43")) {
            assertEquals(1, items(item), item + " is his exactly once");
        }
        assertNull(j.rail.row(id(ben)), "only now, with everything handed over, the row is finished");
        assertTrue(j.rail.dropped(id(ben)).isEmpty(), "nothing was ever dropped");
    }
}
