package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.kidrace.KidRaces;
import com.dierks.homecraft.games.kidrace.KidRacesSettings;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade Room's free Mob Races (the owner: "Free, prize only if she wins", "A second track in the Arcade Room"), run
 * for real over the framework's gate, rewards and database:
 *
 * <ul>
 *   <li>it is free and nothing like a game of chance: nothing is put in, no round is opened, the Parlor's limits and
 *       the chance permission never come into it, and it is never on the Luck tab;</li>
 *   <li>when the winner is home, only whoever picked it is paid, the prize, once for that race; nobody else is told
 *       they lost or loses anything;</li>
 *   <li>the prizes stop at the game's daily cap;</li>
 *   <li>NPC players may join a race a real player is in, are paid nothing, and a race of NPCs alone never runs.</li>
 * </ul>
 */
class KidRacesTest {

    /** The card every race here is run on: every racer in its usual form. */
    static final RaceCard CARD = RaceCard.withForm(new int[Racer.COUNT]);

    private Host host;
    private GamesService games;
    private KidRaces races;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 22, 17, 0));
        open(KidRacesSettings.defaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** The games with these settings (and the Parlor's Mob Races beside it); the Arcade Room's track is there. */
    private void open(KidRacesSettings settings) {
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 60), "kid_races", settings, "mob_races",
                MobRacesSettings.defaults());
        games = GamesKit.service(host, List.of(MobRaces.SPEC, KidRaces.SPEC));
        races = (KidRaces) games.game("kid_races");
        races.useCard(CARD);
        races.trackSeen();
    }

    /** A player in the Arcade Room with 50 tokens. */
    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 50);
        races.guest(f.id);
        return f;
    }

    private static long seedWhere(Racer racer) {
        for (long seed = 1; seed < 10_000; seed++) {
            if (RaceCard.winner(seed, CARD.parts()) == racer.lane()) {
                return seed;
            }
        }
        throw new AssertionError("no seed for " + racer);
    }

    private void pass(long ms) {
        host.move(ms);
        races.trackSeen();
        races.tick();
    }

    /** The race now running to its finish and the result standing until the track is free again. */
    private void runToTheEnd() {
        assertEquals(Phase.RUNNING, races.phase());
        pass(races.settings().raceSeconds() * 1000L);
        assertEquals(Phase.RESULT, races.phase(), "the winner is home");
        pass(KidRaces.RESULT_MS);
        assertEquals(Phase.IDLE, races.phase(), "the track is free again");
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    // ---- free, and never a game of chance ------------------------------------------------------------------

    @Test
    void aRaceIsFreeAndNeverTouchesTheGamesOfChance() throws Exception {
        Fake lilah = player("Lilah");
        lilah.denied.add(PlayGate.PERMISSION_CHANCE); // a child without the chance permission
        assertNull(games.canOpen(lilah.player, races), "her gate lets her in: it is never behind hcm.games.chance");
        assertTrue(games.open(lilah.player, "kid_races", null), "a tap on the table opens it");
        assertFalse(races.kind().chance());
        assertEquals(GameKind.TRIAL, races.kind(), "a free skill game's kind");
        assertFalse(races.featurable());

        assertTrue(races.enter(lilah.player, Racer.FOX), "one tap on a racer enters");
        assertEquals(Phase.GATHERING, races.phase(), "the first entry starts the wait");
        assertEquals(races.settings().gatherSeconds(), races.secondsLeft());
        assertEquals(50, host.balanceOf(lilah.id), "nothing was put in");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round of chance, ever");
        assertNull(games.rounds().openRound(lilah.id, "kid_races"));
        assertEquals(0, games.breaks().tokensInToday(lilah.id), "nothing counts toward the chance limits");
        assertFalse(races.enter(lilah.player, Racer.COW), "one racer each");

        races.seedNextRace(seedWhere(Racer.CHICKEN));
        races.startNow(lilah.player);
        assertEquals(Phase.RUNNING, races.phase(), "the big green picture: everyone in it asked, so it starts now");
        runToTheEnd();
        assertEquals(50, host.balanceOf(lilah.id), "her racer lost: nothing was taken");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rewards"), "and nothing paid");
        String heard = lilah.heard().toLowerCase(Locale.ROOT);
        assertTrue(heard.contains("won the race! hooray for chicken"), "a cheer for the winner: " + heard);
        for (String sad : List.of("lost", "lose", "sorry", "no win", "better luck", "try again")) {
            assertFalse(heard.contains(sad), "never a sad word: " + sad);
        }

        // its own ledger source, never one of the games of chance's, and never on the Luck tab
        assertEquals(TokenService.Source.GAMES_KID_RACES, races.source());
        assertFalse(GamesDao.CHANCE_SOURCES.contains(races.source().name()));
        assertEquals(Game.Tab.LITTLE, KidRaces.TAB, "0.59.0: on Little ones with Picture Bingo, never Luck");
        assertTrue(races.inRoom(lilah.id), "its tile shows in the Arcade Room");
        races.guests(List.of());
        assertTrue(races.tiles(lilah.player).isEmpty(), "and only there");
        assertEquals(0, host.severe());
    }

    @Test
    void onlyInTheArcadeRoomAndOnlyWithItsTrack() {
        Fake sam = new Fake("Sam");
        host.online.put(sam.id, sam.player);
        assertFalse(races.enter(sam.player, Racer.PIG), "not in the Arcade Room");
        assertTrue(sam.heard().contains("Arcade Room"), sam.heard());
        races.guest(sam.id);
        host.move(KidRaces.RESULT_MS * 2); // the Clubhouse hasn't said its track is there for a while
        assertFalse(races.enter(sam.player, Racer.PIG), "no track, no race");
        races.trackSeen();
        assertTrue(races.enter(sam.player, Racer.PIG));
    }

    // ---- the prize ------------------------------------------------------------------------------------------

    @Test
    void onlyThePickerOfTheWinnerIsPaidAndOnlyOnce() throws Exception {
        Fake lilah = player("Lilah");
        Fake sam = player("Sam");
        Fake alex = player("Alex");
        assertTrue(races.enter(lilah.player, Racer.CHICKEN));
        assertTrue(races.enter(sam.player, Racer.PIG));
        assertTrue(races.enter(alex.player, Racer.CHICKEN), "two may pick the same racer");
        races.seedNextRace(seedWhere(Racer.CHICKEN));
        pass(races.settings().gatherSeconds() * 1000L);
        assertEquals(Phase.RUNNING, races.phase(), "the wait is over");
        assertEquals(50, host.balanceOf(lilah.id), "nothing is paid before the winner is home");
        pass(races.settings().raceSeconds() * 1000L);
        assertEquals(Phase.RESULT, races.phase());
        assertEquals(Racer.CHICKEN, races.winner());
        int prize = KidRacesSettings.defaults().prize();
        assertEquals(2, prize, "ships 2 tokens");
        assertEquals(50 + prize, host.balanceOf(lilah.id), "her racer won: the prize");
        assertEquals(50 + prize, host.balanceOf(alex.id), "and the other one who picked it");
        assertEquals(50, host.balanceOf(sam.id), "the Pig's picker: nothing, and nothing taken");
        assertTrue(lilah.heard().contains("won, and it was yours! Hooray!"), lilah.heard());
        assertTrue(lilah.heard().contains("+2 tokens"), "the skill games' own +N line: " + lilah.heard());
        assertFalse(sam.heard().toLowerCase(Locale.ROOT).contains("lost"), sam.heard());
        assertEquals(2, count("SELECT COUNT(*) FROM game_rewards WHERE kind = 'RACE_WIN' AND tokens = 2"),
                "one prize each for the two who picked it");

        for (int n = 0; n < 7; n++) {
            pass(1_000); // the result standing, and the track free again: nothing more is paid
            races.tick();
        }
        assertEquals(Phase.IDLE, races.phase());
        assertEquals(50 + prize, host.balanceOf(lilah.id), "paid once");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rewards"));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(Racer.CHICKEN, races.lastWinner());
        assertEquals(0, host.severe());
    }

    @Test
    void thePrizesStopAtTheDailyCap() throws Exception {
        assertEquals(10, KidRacesSettings.defaults().dailyCap(), "ships 10 a day");
        Fake lilah = player("Lilah");
        for (int race = 1; race <= 7; race++) {
            assertTrue(races.enter(lilah.player, Racer.SHEEP), "race " + race);
            races.seedNextRace(seedWhere(Racer.SHEEP));
            races.startNow(lilah.player);
            runToTheEnd();
            int expected = 50 + Math.min(10, 2 * race);
            assertEquals(expected, host.balanceOf(lilah.id), "after race " + race + ": 2 a win, 10 a day at most");
        }
        assertTrue(lilah.heard().contains("You've won all the game tokens you can today"), "told once: "
                + lilah.heard());
        assertEquals(5, count("SELECT COUNT(*) FROM game_rewards WHERE kind = 'RACE_WIN'"), "five wins paid");
        // a new day, a new cap
        host.move(24L * 3600_000L);
        races.trackSeen();
        assertTrue(races.enter(lilah.player, Racer.SHEEP));
        races.seedNextRace(seedWhere(Racer.SHEEP));
        races.startNow(lilah.player);
        runToTheEnd();
        assertEquals(62, host.balanceOf(lilah.id), "the next day pays again");
    }

    @Test
    void aPrizeOfNothingPaysNothing() throws Exception {
        open(new KidRacesSettings(true, 0, 10, 15, 20));
        Fake lilah = player("Lilah");
        assertTrue(races.enter(lilah.player, Racer.COW));
        races.seedNextRace(seedWhere(Racer.COW));
        races.startNow(lilah.player);
        runToTheEnd();
        assertEquals(50, host.balanceOf(lilah.id));
        assertTrue(lilah.heard().contains("Hooray!"), "still a fanfare");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rewards"));
    }

    // ---- NPC players -----------------------------------------------------------------------------------------

    @Test
    void npcsJoinARealPlayersRaceAreNeverPaidAndNeverRaceAlone() throws Exception {
        UUID npc = UUID.randomUUID();
        UUID npc2 = UUID.randomUUID();
        UUID npc3 = UUID.randomUUID();
        assertFalse(races.enterNpc(npc, "Pip", Racer.FOX), "no race gathering: an NPC can't start one");
        assertEquals(Phase.IDLE, races.phase());

        Fake lilah = player("Lilah");
        assertTrue(races.enter(lilah.player, Racer.RABBIT));
        assertTrue(races.enterNpc(npc, "Pip", Racer.FOX), "a friendly NPC picks a racer too");
        assertTrue(races.enterNpc(npc2, "Bo", Racer.FOX));
        assertFalse(races.enterNpc(npc3, "Max", Racer.COW), "at most " + KidRaces.MAX_NPCS);
        assertFalse(races.enterNpc(npc, "Pip", Racer.COW), "once each");
        assertEquals(3, races.entries().size());

        // she walks out of the Arcade Room before the start: the race is off, NPCs and all
        races.guests(List.of());
        pass(1_000);
        assertEquals(Phase.IDLE, races.phase(), "a race of NPCs alone never runs");
        assertTrue(races.entries().isEmpty());

        // back in: an NPC's pick wins, and nobody is paid
        races.guest(lilah.id);
        assertTrue(races.enter(lilah.player, Racer.RABBIT));
        assertTrue(races.enterNpc(npc, "Pip", Racer.FOX));
        races.seedNextRace(seedWhere(Racer.FOX));
        races.startNow(lilah.player);
        assertEquals(Phase.RUNNING, races.phase(), "an NPC never holds the start up");
        runToTheEnd();
        assertEquals(50, host.balanceOf(lilah.id));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rewards"), "an NPC is paid nothing");

        // she leaves the server while it gathers: off again
        assertTrue(races.enter(lilah.player, Racer.RABBIT));
        assertTrue(races.enterNpc(npc, "Pip", Racer.FOX));
        races.onQuit(lilah.player);
        assertEquals(Phase.IDLE, races.phase());
        assertTrue(races.entries().isEmpty());
    }

    // ---- what a screen shows ---------------------------------------------------------------------------------

    @Test
    void eachRacersStarsComeFromTheSameCard() {
        RaceCard card = RaceCard.withForm(new int[]{2, -2, 0, 1, -1, 0});
        int five = 0;
        int one = 0;
        for (Racer r : Racer.values()) {
            int s = KidRaces.stars(card, r);
            assertTrue(s >= 1 && s <= 5, r + ": " + s);
            five += s == 5 ? 1 : 0;
            one += s == 1 ? 1 : 0;
        }
        assertEquals(5, KidRaces.stars(card, card.favourite()), "the strongest today has five stars");
        assertEquals(1, KidRaces.stars(card, card.longest()), "the weakest one");
        assertTrue(five >= 1 && one >= 1);
        assertEquals(Racer.CHICKEN.label() + " &e★★★&8☆☆", KidRaces.boardLine(Racer.CHICKEN, 3),
                "the Arcade Room's board shows stars, never odds or pay");
        assertNotNull(races.card());
        for (String line : races.rules()) {
            String l = line.toLowerCase(Locale.ROOT);
            for (String banned : List.of("bet", "wager", "gambl", "casino", "lucky", "jackpot", "almost")) {
                assertFalse(l.contains(banned), line);
            }
        }
    }
}
