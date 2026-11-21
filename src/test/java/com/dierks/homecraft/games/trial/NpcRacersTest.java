package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC racers in party races (stage 2): the path they follow (a recorded real run, else the checkpoints) and how an
 * NPC's level and slips play out along it; the recorded runs; the party's NPC company (joining a lone racer after the
 * wait, giving way, leaving for someone with NPCs off); and that an NPC racer changes nothing real: the race waits
 * only for real racers, an NPC's finish never starts the finish window, and every place that rewards a place reads
 * the results as if no NPC had raced.
 */
class NpcRacersTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);
    private static final CpuPlayer OZZIE = CpuRoster.byKey("ozzie");
    private static final CpuPlayer PIP = CpuRoster.byKey("pip");
    private static final CpuPlayer WREN = CpuRoster.byKey("wren");

    // ---- the path --------------------------------------------------------------------------------------

    /** The shortest distance from (x, y, z) to the polyline through {@code pts}. */
    private static double offPath(List<GhostPath.Sample> pts, double x, double y, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < pts.size(); i++) {
            GhostPath.Sample a = pts.get(i);
            GhostPath.Sample b = pts.get(i + 1);
            double vx = b.x() - a.x();
            double vy = b.y() - a.y();
            double vz = b.z() - a.z();
            double len = vx * vx + vy * vy + vz * vz;
            double t = len == 0 ? 0 : ((x - a.x()) * vx + (y - a.y()) * vy + (z - a.z()) * vz) / len;
            t = Math.max(0, Math.min(1, t));
            double dx = a.x() + vx * t - x;
            double dy = a.y() + vy * t - y;
            double dz = a.z() + vz * t - z;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private static void staysOnPath(GhostPath.Run run, List<GhostPath.Sample> line) {
        int reached = 0;
        for (long ms = -500; ms <= run.finishMs() + 1_000; ms += 37) {
            GhostPath.Pose p = run.at(ms);
            assertTrue(offPath(line, p.x(), p.y(), p.z()) < 1e-6, "on the path at " + ms + " ms: " + p);
            assertTrue(p.reached() >= reached, "its count of targets never goes back (" + ms + " ms)");
            reached = p.reached();
        }
        assertTrue(run.at(run.finishMs()).done(), "over the line at its finish");
        assertFalse(run.at(run.finishMs() - 1).done(), "not a moment before");
    }

    @Test
    void theCheckpointPathRunsStartToFinishThroughEveryCheckpointAndNeverLeavesTheLine() {
        Course run = LapsTest.straight();
        GhostPath path = GhostPath.of(run, null, null, null, 10_000);
        assertNotNull(path);
        assertEquals(GhostPath.Source.CHECKPOINTS, path.source(), "nothing recorded, no splits: the checkpoints");
        assertEquals(5, path.samples().size(), "the start, three checkpoints and the finish");
        assertEquals(60 / GhostPath.PARKOUR_PACE * 1000, path.totalMs(), 2, "60 blocks at the runners' pace");
        GhostPath slow = GhostPath.of(run, null, null, null, 30_000);
        assertEquals(33_000, slow.totalMs(), 2, "never quicker than the course's shortest believable time");
        for (CpuLevel level : CpuLevel.values()) {
            for (int seed = 0; seed < 40; seed++) {
                staysOnPath(new GhostPath.Run(path, level, true, new SplittableRandom(seed)), path.samples());
            }
        }
    }

    @Test
    void aGridStartGoesStraightToTheStartFirst() {
        Course loop = LapsTest.loop(6, 2);
        Course.Spot grid = new Course.Spot(28, 65, -12, 0, 0);
        GhostPath path = GhostPath.of(Laps.raced(loop, grid, 0).course(), grid, null, null, 0);
        assertEquals(grid.x(), path.samples().get(0).x(), 1e-9, "it starts on its grid spot");
        assertEquals(loop.targets().get(0).z(), path.samples().get(1).z(), 1e-9,
                "then on to the first checkpoint (a raced course starts on the grid)");
        assertEquals(loop.targets().size(), path.targetMs().length, "every target of the raced course");
    }

    @Test
    void aRecordedRunIsReplayedWhereTheRiderWentAndHardIsCloseToItsTime() {
        Course run = LapsTest.straight();
        List<GhostPath.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 200; i++) { // a wiggly real run, 20 s, 0.1 s a point
            double z = 60.0 * i / 200;
            samples.add(new GhostPath.Sample(i * 100L, Math.sin(i / 7.0) * 2, 65 + (i % 10 == 0 ? 1 : 0), z, 0, 0));
        }
        long[] at = {5_000, 10_000, 15_000, 20_000};
        RunTrails.Trail trail = new RunTrails.Trail("run", run.layoutHash(), 20_000, samples, at);
        GhostPath path = GhostPath.of(run, null, trail, null, 10_000);
        assertEquals(GhostPath.Source.RECORDED, path.source(), "a recorded run comes first");
        assertEquals(20_000, path.totalMs());
        double hard = 0;
        double easy = 0;
        for (int seed = 0; seed < 60; seed++) {
            GhostPath.Run h = new GhostPath.Run(path, CpuLevel.HARD, true, new SplittableRandom(seed));
            GhostPath.Run e = new GhostPath.Run(path, CpuLevel.EASY, true, new SplittableRandom(seed));
            staysOnPath(h, samples);
            staysOnPath(e, samples);
            hard += h.finishMs();
            easy += e.finishMs();
        }
        hard /= 60;
        easy /= 60;
        assertTrue(hard >= 20_000 * 1.03 * 0.96 && hard < 20_000 * 1.15, "hard: close to the best run (" + hard + ")");
        assertTrue(easy > 20_000 * 1.45, "easy: well behind it (" + easy + ")");
        assertTrue(easy > hard * 1.3, "easy is slower than hard");

        RunTrails.Trail other = new RunTrails.Trail("run", run.layoutHash(), 20_000, samples, new long[]{5_000});
        assertEquals(GhostPath.Source.CHECKPOINTS, GhostPath.of(run, null, other, null, 0).source(),
                "a recording that didn't go through these targets is never used");
        long[] splits = {4_000, 8_000, 12_000, 16_000};
        GhostPath paced = GhostPath.of(run, null, null, splits, 0);
        assertEquals(GhostPath.Source.SPLITS, paced.source(), "a real run's checkpoint times, next best");
        assertEquals(16_000, paced.totalMs(), "at that run's pace");
    }

    @Test
    void easyNpcsSlipNowAndThenSometimesBackToTheirCheckpointAndHardOnesRarely() {
        GhostPath path = GhostPath.of(LapsTest.loop(6, 2), null, null, null, 0);
        int easySlips = 0;
        int hardSlips = 0;
        int backs = 0;
        for (int seed = 0; seed < 200; seed++) {
            for (GhostPath.Run.Leg l : new GhostPath.Run(path, CpuLevel.EASY, true, new SplittableRandom(seed)).legs()) {
                if (l.slip()) {
                    easySlips++;
                    if (l.refTo() > l.refFrom()) {
                        backs++; // the leg again, from the checkpoint
                    }
                }
            }
            for (GhostPath.Run.Leg l : new GhostPath.Run(path, CpuLevel.HARD, true, new SplittableRandom(seed)).legs()) {
                hardSlips += l.slip() ? 1 : 0;
            }
        }
        assertTrue(easySlips > hardSlips * 4, "easy slips far more often: " + easySlips + " vs " + hardSlips);
        assertTrue(backs > 0, "and sometimes goes back to its checkpoint");
        int flyerBacks = 0;
        for (int seed = 0; seed < 200; seed++) {
            for (GhostPath.Run.Leg l : new GhostPath.Run(path, CpuLevel.EASY, false, new SplittableRandom(seed)).legs()) {
                flyerBacks += l.slip() && l.refTo() > l.refFrom() ? 1 : 0;
            }
        }
        assertEquals(0, flyerBacks, "where a racer can't fall, a slip is only a stop");
    }

    // ---- the recorded runs --------------------------------------------------------------------------------

    @Test
    void onlyTheFastestCleanRunOnTheLayoutAsItIsNowIsKept() {
        RunTrails t = new RunTrails();
        assertTrue(record(t, SAM, 1, 30_000, false), "a clean counted run is kept");
        assertNotNull(t.best("run", 7));
        assertEquals(30_000, t.best("run", 7).ms());
        assertEquals(2, t.best("run", 7).targetMs().length);
        assertFalse(record(t, AVA, 2, 35_000, false), "a slower run isn't kept");
        assertEquals(30_000, t.best("run", 7).ms());
        assertFalse(record(t, AVA, 3, 25_000, true), "a run with a jump (a reset, a teleport) is never kept");
        assertEquals(30_000, t.best("run", 7).ms());
        assertTrue(record(t, AVA, 4, 25_000, false), "a faster clean one replaces it");
        t.sample(SAM, "run", 7, 4, 0, 0, 65, 0, 0, 0);
        t.broken(SAM);
        t.sample(SAM, "run", 7, 4, 100, 0, 65, 1, 0, 0);
        assertFalse(t.finished(SAM, "run", 7, 1_000, new long[]{1_000}, 0, 65, 2), "one put back isn't either");
        assertNull(t.best("run", 8), "the course was edited (another layout): nothing for it");
        t.drop(AVA);
        assertEquals(1, t.size());
    }

    /** A run of 31 points along +z; with {@code jump}, one point 20 blocks off. Returns false when not kept. */
    private static boolean record(RunTrails t, UUID who, long go, long ms, boolean jump) {
        for (int i = 0; i <= 60; i++) {
            double z = i;
            if (jump && i == 30) {
                z += 20;
            }
            t.sample(who, "run", 7, go, ms * i / 60, 0, 65, z, 0, 0);
        }
        return t.finished(who, "run", 7, ms, new long[]{ms / 2, ms}, 0, 65, 60);
    }

    // ---- the party ---------------------------------------------------------------------------------------

    @Test
    void anNpcJoinsALoneRacerAfterTheWaitAndGivesWayToFriends() {
        PartyLobby lobby = new PartyLobby(1, PartyLobby.Kind.RACE, "run", SAM, 3);
        NpcCrowd.Alone clock = new NpcCrowd.Alone();
        List<CpuPlayer> queue = new ArrayList<>(List.of(OZZIE, PIP, WREN));
        java.util.function.Supplier<CpuPlayer> pick = () -> queue.isEmpty() ? null : queue.remove(0);
        assertEquals(PartyLobby.Why.TOO_FEW, lobby.canStart(SAM), "alone: no start yet");
        assertNull(PartyNpcs.company(lobby, id -> true, clock, 0, 20_000, pick).joined(), "the wait starts");
        assertNull(PartyNpcs.company(lobby, id -> true, clock, 19_000, 20_000, pick).joined(), "not yet");
        assertEquals(OZZIE.id(), PartyNpcs.company(lobby, id -> true, clock, 20_000, 20_000, pick).joined(),
                "after join_after_seconds alone, an NPC joins");
        assertEquals(1, lobby.size(), "never a member");
        assertNull(lobby.canStart(SAM), "and Sam may race it");
        assertNull(PartyNpcs.company(lobby, id -> true, clock, 21_000, 20_000, pick).joined(), "the next waits");
        assertEquals(PIP.id(), PartyNpcs.company(lobby, id -> true, clock, 20_000 + NpcCrowd.NEXT_AFTER_MS, 20_000,
                pick).joined(), "then one more");
        assertNull(PartyNpcs.company(lobby, id -> true, clock, 99_000, 20_000, pick).joined(),
                "never more than the places no real player has (3 places: Sam and two NPCs)");
        assertEquals(List.of(OZZIE.id(), PIP.id()), lobby.npcs());

        assertNull(lobby.join(AVA), "Ava joins");
        assertEquals(List.of(OZZIE.id()), lobby.npcs(), "the last NPC in gave her its place");
        assertFalse(lobby.full(), "NPCs never make a party full for a friend");
        assertNull(PartyNpcs.company(lobby, id -> true, clock, 200_000, 20_000, pick).joined(),
                "with a friend in, no more join");
        PartyNpcs.Change left = PartyNpcs.company(lobby, id -> !id.equals(AVA), clock, 201_000, 20_000, pick);
        assertEquals(List.of(OZZIE.id()), left.left(), "Ava has NPCs off: every NPC leaves");
        assertTrue(lobby.npcs().isEmpty());
        assertEquals(PartyLobby.State.OPEN, lobby.state());
    }

    @Test
    void nobodyJoinsALobbyWhoseRacerHasNpcsOffOrOneThatIsRacing() {
        PartyLobby lobby = new PartyLobby(1, PartyLobby.Kind.RACE, "run", SAM, 8);
        NpcCrowd.Alone clock = new NpcCrowd.Alone();
        for (long t = 0; t < 120_000; t += 1_000) {
            assertNull(PartyNpcs.company(lobby, id -> false, clock, t, 20_000, () -> OZZIE).joined());
        }
        assertTrue(lobby.npcs().isEmpty(), "NPCs off: never any");
        assertTrue(lobby.addNpc(PIP.id()));
        lobby.join(AVA);
        assertNull(lobby.start(SAM));
        assertEquals(PartyNpcs.Change.NONE, PartyNpcs.company(lobby, id -> false, clock, 0, 20_000, () -> OZZIE),
                "a race under way is never changed");
        assertFalse(lobby.addNpc(OZZIE.id()), "and no NPC joins it");
    }

    @Test
    void npcGridSpotsAreBehindTheRealRacersAndNeverMoveThem() {
        Course run = LapsTest.straight();
        RaceGrid.Grid shared = RaceGrid.forCourse(run, (x, y, z) -> RaceGrid.Cell.AIR, 1);
        assertEquals(List.of(run.start(), run.start()), PartyNpcs.npcSpots(run, (x, y, z) -> RaceGrid.Cell.AIR,
                shared, 1, 2), "runners all start on the start");
        Course lane = RaceGridTest.straightBoat(0.5);
        RaceGrid.Surface open = (x, y, z) -> y < 65 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;
        RaceGrid.Grid two = RaceGrid.forCourse(lane, open, 2);
        List<Course.Spot> spots = PartyNpcs.npcSpots(lane, open, two, 2, 2);
        RaceGrid.Grid four = RaceGrid.forCourse(lane, open, 4);
        assertEquals(two.spots(), four.spots().subList(0, 2), "(the real racers' spots are the same either way)");
        assertEquals(four.spots().subList(2, 2 + spots.size()), spots, "the NPCs get the spots behind them");
        RaceGrid.Grid other = new RaceGrid.Grid(List.of(new Course.Spot(9, 65, 9, 0, 0)), RaceGrid.Mode.DOUBLE,
                List.of());
        assertEquals(List.of(), PartyNpcs.npcSpots(lane, open, other, 1, 2),
                "a grid for everyone that would move a real racer: the NPCs give way");
    }

    // ---- the race ----------------------------------------------------------------------------------------

    private final AtomicLong tick = new AtomicLong(1_000);

    @Test
    void aLoneRacerRacesTheNpcsAndTheRaceWaitsOnlyForThem() {
        Course loop = LapsTest.loop(6, 2);
        List<String> said = new ArrayList<>();
        PartyRace r = new PartyRace(7, loop, List.of(new PartyRace.Racer(SAM, "Sam")), 0, tick::get, said::add);
        assertTrue(r.addNpc(OZZIE.id(), CpuRoster.tagged("Ozzie")));
        assertFalse(r.addNpc(OZZIE.id(), "again"), "once");
        r.seated(SAM, loop.start());
        r.seated(OZZIE.id(), loop.start());
        assertEquals(List.of(SAM), r.racers(), "race mode seats only Sam");
        assertEquals(List.of(OZZIE.id()), r.npcs());
        assertTrue(r.seatingDone(), "Sam and an NPC: the race is on");
        assertFalse(r.addNpc(PIP.id(), "late"), "nobody joins after the seating");
        tick.set(r.goTick());
        r.tick(tick.get());
        assertEquals(PartyRace.State.RACING, r.state());
        assertEquals(1, r.in(), "the race waits for Sam only");

        r.finished(OZZIE.id(), 40_000, true, null);
        assertTrue(said.stream().anyMatch(l -> l.contains("Ozzie ⚙ NPC came 1st!")), said.toString());
        tick.addAndGet(PartyRace.FINISH_WINDOW_SECONDS * 20L + 200);
        assertEquals(PartyRace.Step.NONE, r.tick(tick.get()), "an NPC's finish never starts the window on Sam");
        assertTrue(r.bar(SAM).startsWith("&e2nd &7of 2"), "Sam sees where he is among everyone racing");
        r.finished(SAM, 52_000, true, null);
        assertEquals(PartyRace.Step.END, r.tick(tick.get()), "Sam is in: the race ends");

        List<PartyRace.Line> lines = r.results();
        assertEquals(2, lines.size(), "the race's own results show the NPC");
        assertEquals(OZZIE.id(), lines.get(0).id());
        assertEquals(1, lines.get(0).rank());
        assertTrue(PartyRace.chatLine(lines.get(0)).contains("Ozzie ⚙ NPC"), "with the gear and the word");
        List<PartyRace.Line> real = PartyRace.realOnly(lines);
        assertEquals(1, real.size(), "anything that rewards a place sees Sam alone");
        assertEquals(1, real.get(0).rank(), "first among real racers, exactly as without the NPC");
        assertEquals(52_000, real.get(0).ms(), "with his own time");
        assertNull(com.dierks.homecraft.games.clubhouse.FamilyWeekRules.partyWinner(real, r.racers().size()),
                "a race against NPCs alone is no family-week win");
        assertEquals(List.of(), ClubRaces.photoTop(real), "and no podium photo for one real racer, as without NPCs");
        assertEquals(List.of(OZZIE.id(), SAM), ClubRaces.photoTop(lines), "(the NPC would have taken the top step)");
    }

    @Test
    void aRealPlayersPlacesAreTheSameWithAndWithoutNpcs() {
        Course loop = LapsTest.loop(6, 2);
        PartyRace with = new PartyRace(7, loop, List.of(new PartyRace.Racer(SAM, "Sam"),
                new PartyRace.Racer(AVA, "Ava")), 0, tick::get, l -> { });
        PartyRace without = new PartyRace(8, loop, List.of(new PartyRace.Racer(SAM, "Sam"),
                new PartyRace.Racer(AVA, "Ava")), 0, tick::get, l -> { });
        with.addNpc(OZZIE.id(), CpuRoster.tagged("Ozzie"));
        with.addNpc(PIP.id(), CpuRoster.tagged("Pip"));
        for (PartyRace r : List.of(with, without)) {
            r.seated(SAM, loop.start());
            r.seated(AVA, loop.start());
        }
        with.seated(OZZIE.id(), loop.start());
        with.seated(PIP.id(), loop.start());
        for (PartyRace r : List.of(with, without)) {
            assertTrue(r.seatingDone());
            tick.set(r.goTick());
            r.tick(tick.get());
        }
        with.finished(OZZIE.id(), 30_000, true, null);
        for (PartyRace r : List.of(with, without)) {
            r.finished(AVA, 45_000, true, null);
            r.finished(SAM, 50_000, true, null);
        }
        assertEquals(PartyRace.Step.END, with.tick(tick.get()), "Pip still going doesn't hold anyone up");
        assertEquals(without.results(), PartyRace.realOnly(with.results()), "the real lines, place for place");
        assertEquals(com.dierks.homecraft.games.clubhouse.FamilyWeekRules.partyWinner(without.results(), 2),
                com.dierks.homecraft.games.clubhouse.FamilyWeekRules.partyWinner(PartyRace.realOnly(with.results()),
                        with.racers().size()), "the family week's win goes to the same racer");
        assertEquals(PartyRace.Result.STILL_RACING, with.results().get(3).result(), "Pip was still racing");
    }
}
