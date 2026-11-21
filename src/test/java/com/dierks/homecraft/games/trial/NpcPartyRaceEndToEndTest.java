package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NpcTrace;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.FamilyWeekRules;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.CpuSettings;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A party race with an NPC racer, start to finish, on the real framework, database and race mode ({@link RaceBench}),
 * with the NPC players on (stage 2): Ava opens a party alone, Ozzie joins her after the wait, she starts alone and
 * races him; Ozzie (an NPC) crosses first on his ghost path; Ava's finish is her normal counted run.
 *
 * <p>Pinned: Ava's board time, rewards and Cup time are exactly a normal run's; the race's own results show Ozzie
 * first, marked; every place that rewards a place sees Ava alone; and, the NPC players' "no trace" scan
 * ({@code CpuPlayersTest}'s, {@link NpcTrace}) over every table: no row anywhere names an NPC.
 */
class NpcPartyRaceEndToEndTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final Course BUILT = LapsTest.loop(6, 2);
    private static final RaceGrid.Surface ICE = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;

    private GamesBench bench;
    private GamesService games;
    private TimeTrials trials;
    private RaceBench race;
    private Course loop;
    private Player ava;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC, CpuPlayers.SPEC),
                "trials", TimeTrialsSettings.defaults(), "cup", CupSettings.defaults(), "cpu", CpuSettings.defaults());
        games = bench.games();
        trials = (TimeTrials) games.game("trials");
        bench.dao().saveCourse(new GamesDao.CourseRow(BUILT.id(), "trials", BUILT.kind().id(), BUILT.name(),
                BUILT.world(), BUILT.enabled(), CourseCodec.encode(BUILT), BUILT.rev(), 0, 0), false);
        loop = trials.course(BUILT.id());
        race = new RaceBench(trials);
        ava = bench.player("Ava");
        race.add(ava);
        bench.give(ava.getUniqueId(), 20);
        ((WeeklyCup) games.game("cup")).desk().dao().choose(loop.id(), true);
        assertNull(((WeeklyCup) games.game("cup")).desk().enter(ava.getUniqueId(), loop), "Ava enters the Cup");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private UUID id(Player p) {
        return p.getUniqueId();
    }

    private List<String> rewardKinds(UUID player) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = bench.connection().prepareStatement(
                "SELECT kind FROM game_rewards WHERE player = ? ORDER BY id")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void aLoneRacerRacesAnNpcAndNothingRealEverNamesIt() throws Exception {
        CpuPlayers npcs = CpuPlayers.of(games);
        assertNotNull(npcs, "NPC players are on");
        CpuPlayer ozzie = CpuRoster.byKey("ozzie");

        // Ava's party, alone; Ozzie joins after the wait
        PartyLobby lobby = games.parties().create(PartyLobby.Kind.RACE, loop.id(), id(ava), 8);
        NpcCrowd.Alone clock = new NpcCrowd.Alone();
        long joinAfter = npcs.joinAfterMs();
        assertNull(PartyNpcs.company(lobby, npcs::wants, clock, 0, joinAfter, () -> ozzie).joined());
        assertEquals(ozzie.id(), PartyNpcs.company(lobby, npcs::wants, clock, joinAfter, joinAfter, () -> ozzie)
                .joined(), "Ozzie keeps her company");
        assertNull(trials.party().startProblem(ava), "she may start alone with an NPC in the party");

        // the start, as PartyRaces.start does it: Ava seated through race mode, Ozzie beside her as an NPC racer
        assertNull(lobby.start(id(ava)));
        List<String> said = new ArrayList<>();
        PartyRace party = new PartyRace(lobby.id(), loop, List.of(new PartyRace.Racer(id(ava), "Ava")), 0,
                () -> race.tick, said::add);
        RaceGrid.Grid grid = RaceGrid.forCourse(loop, ICE, 1);
        List<Course.Spot> npcSpots = PartyNpcs.npcSpots(loop, ICE, grid, 1, 1);
        assertEquals(1, npcSpots.size(), "a spot for Ozzie behind Ava");
        assertNull(race.seat(id(ava), loop, Laps.raced(loop, grid.spot(0), 0).course(), grid.spot(0), null, party));
        party.seated(id(ava), grid.spot(0));
        assertTrue(party.addNpc(ozzie.id(), CpuRoster.tagged(npcs.name(ozzie))));
        party.seated(ozzie.id(), npcSpots.get(0));
        CpuLevel level = npcs.level(ozzie, List.of(id(ava)), PartyNpcs.GAME);
        GhostPath path = GhostPath.of(Laps.raced(loop, npcSpots.get(0), 0).course(), npcSpots.get(0),
                trials.trail(loop), null, 40_000);
        GhostPath.Run ghost = new GhostPath.Run(path, level, true, new SplittableRandom(3));
        assertTrue(party.seatingDone(), "Ava and Ozzie: the race is on");
        trials.party().running(party);
        for (int i = 0; i < 20 * 10 && race.startOf(id(ava)) < 0; i++) {
            bench.move(50);
            race.tick();
            party.tick(race.tick);
        }
        assertTrue(race.startOf(id(ava)) >= 0, "Go");

        // Ozzie's ghost goes round (the NPC driver's calls), and crosses at its finish; then Ava
        long go = party.goTick();
        boolean ozzieIn = false;
        for (int i = 0; i < 20 * 120 && !ozzieIn; i++) {
            bench.move(50);
            race.tick();
            party.tick(race.tick);
            GhostPath.Pose p = ghost.at((race.tick - go) * 50);
            if (p.done()) {
                party.finished(ozzie.id(), ghost.finishMs(), true, null);
                ozzieIn = true;
            } else {
                party.progress(ozzie.id(), p.reached(), p.toNext(), System.nanoTime());
            }
        }
        assertTrue(ozzieIn, "Ozzie finished");
        long ozzieMs = ghost.finishMs();
        race.cross(id(ava), ozzieMs + 5_000);
        boolean ended = false;
        for (int i = 0; i < 40 && !ended; i++) {
            race.tick();
            ended = party.tick(race.tick) == PartyRace.Step.END;
        }
        assertTrue(ended, "Ava in: the race is over");

        List<PartyRace.Line> lines = party.results();
        assertEquals(List.of(ozzie.id(), id(ava)), lines.stream().map(PartyRace.Line::id).toList(),
                "the race's own results: Ozzie first");
        assertTrue(said.stream().anyMatch(l -> l.contains("⚙ NPC came 1st")), "and its own line said so: " + said);
        List<PartyRace.Line> real = PartyRace.realOnly(lines);
        assertEquals(1, real.size());
        assertEquals(1, real.get(0).rank(), "for anything that rewards a place, Ava came first, alone");
        assertNull(FamilyWeekRules.partyWinner(real, party.racers().size()), "and alone is no family-week win");

        // Ava's run counted exactly as a normal run would
        assertEquals(ozzieMs + 5_000, bench.dao().best(id(ava), TimeTrials.SPEC.id(), Scores.course(loop.id())),
                "her own time on the course's board");
        assertEquals(1, rewardKinds(id(ava)).stream().filter("FIRST_CLEAR"::equals).count(), "the first clear, once");
        assertFalse(rewardKinds(id(ava)).contains("EVENT_PRIZE"), "no prize: a party race pays none");

        // no trace: no row anywhere names an NPC
        for (CpuPlayer c : CpuRoster.ALL) {
            assertEquals(List.of(), NpcTrace.rowsNaming(bench.connection(), c.id()),
                    c.name() + ": no row anywhere names an NPC, after a race it ran in");
        }
        assertFalse(NpcTrace.rowsNaming(bench.connection(), id(ava)).isEmpty(), "(the scan finds Ava's rows)");
    }
}
