package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Take a rider (back seat)" on the real framework (CLUBHOUSE-SPEC §12): offered only for a boat and
 * only while the Clubhouse is on; who may drive and who may ride (never someone racing, in a party,
 * riding already, themself, or during the restart hold); the invite switch; and both values of
 * {@code games.trials.rider_runs_count}: true, a run with a rider counts as normal; false, it is just
 * for fun (VOID, with the reason in the finish line) and Race Night takes no riders.
 */
class RideAlongTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final Course LOOP = LapsTest.loop(6, 2);

    private GamesBench bench;
    private GamesService games;
    private TimeTrials trials;
    private Player dad;
    private Player kid;

    private void open(boolean clubhouseOn, boolean riderRunsCount) throws Exception {
        TimeTrialsSettings d = TimeTrialsSettings.defaults();
        TimeTrialsSettings t = new TimeTrialsSettings(d.enabled(), d.firstClear(), d.weeklyBestBonus(),
                d.courseOfWeekBonus(), d.dailyCap(), d.fallDepth(), d.minSeconds(), d.warmupSeconds(), d.partyMax(),
                riderRunsCount);
        ClubhouseSettings c = ClubhouseSettings.defaults();
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, Clubhouse.SPEC), "trials", t, "clubhouse",
                new ClubhouseSettings(clubhouseOn, c.origin(), c.maxMinutes(), c.partyAfter(), c.raceNightAfter(),
                        c.golfAfter()));
        games = bench.games();
        trials = (TimeTrials) games.game("trials");
        bench.dao().saveCourse(new GamesDao.CourseRow(LOOP.id(), "trials", LOOP.kind().id(), LOOP.name(),
                LOOP.world(), LOOP.enabled(), CourseCodec.encode(LOOP), LOOP.rev(), 0, 0), false);
        assertNotNull(trials.course(LOOP.id()), "the boat loop");
        dad = bench.player("Dad");
        kid = bench.player("Kid");
        // no server here, so the room is never built: the Clubhouse's door is open only when faked
        trials.raceMode().door(clubhouseOn ? new ClubhouseRacesTest.FakeDoor() : null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dad != null) {
            WorldEntities.passenger(dad.getUniqueId(), null);
        }
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void offeredForABoatWhileTheClubhouseIsOn() throws Exception {
        open(true, true);
        assertTrue(RideAlong.offered(games, TrialKind.BOAT), "a boat course's screen shows Take a rider");
        for (TrialKind k : TrialKind.values()) {
            if (k != TrialKind.BOAT) {
                assertFalse(RideAlong.offered(games, k), "only a boat has a back seat: " + k);
            }
        }
        assertTrue(RideAlong.nightOffered(games, trials.course(LOOP.id())), "and the Race Night screen");
        assertNull(RideAlong.driverRefusal(games, trials, dad, LOOP.id(), RideAlong.Purpose.SOLO), "Dad may drive");
        assertTrue(RideAlong.BUTTON.contains("Take a rider") && RideAlong.BUTTON.contains("(back seat)"),
                "the key fact in the item's NAME, for Bedrock");
    }

    @Test
    void withTheClubhouseSwitchedOnButItsRoomNotBuiltThereIsNoTakeARider() throws Exception {
        open(true, true);
        trials.raceMode().door(null); // on, but its room isn't built and checked: no door
        assertFalse(RideAlong.offered(games, TrialKind.BOAT), "no Take a rider on the course screen (#9)");
        assertFalse(RideAlong.nightOffered(games, trials.course(LOOP.id())), "nor on the Race Night screen");
        assertEquals("Riders aren't on right now.",
                RideAlong.driverRefusal(games, trials, dad, LOOP.id(), RideAlong.Purpose.SOLO), "and refused by name");
    }

    @Test
    void withTheClubhouseOffThereIsNoTakeARider() throws Exception {
        open(false, true);
        assertFalse(RideAlong.offered(games, TrialKind.BOAT), "no Take a rider anywhere");
        assertFalse(RideAlong.nightOffered(games, trials.course(LOOP.id())), "not on the Race Night screen either");
        assertEquals("Riders aren't on right now.",
                RideAlong.driverRefusal(games, trials, dad, LOOP.id(), RideAlong.Purpose.SOLO), "and refused by name");
    }

    @Test
    void whoMayRide() throws Exception {
        open(true, true);
        assertNull(RideAlong.riderRefusal(games, trials, dad, kid, RideAlong.Purpose.PARTY), "Kid may ride");
        assertNotNull(RideAlong.riderRefusal(games, trials, dad, dad, RideAlong.Purpose.SOLO), "never yourself");
        games.parties().create(PartyLobby.Kind.RACE, LOOP.id(), kid.getUniqueId(), 8);
        assertTrue(RideAlong.riderRefusal(games, trials, dad, kid, RideAlong.Purpose.PARTY).contains("in a party"),
                "someone in a party race is a racer, never a rider");
        games.parties().leave(kid.getUniqueId());
        trials.riders().paired(dad, kid, LOOP.id(), null);
        assertEquals("You have a rider already.",
                RideAlong.driverRefusal(games, trials, dad, LOOP.id(), RideAlong.Purpose.SOLO), "one back seat");
        assertTrue(RideAlong.riderRefusal(games, trials, bench.player("Mum"), kid, RideAlong.Purpose.SOLO)
                .contains("on a ride already"), "one ride at a time");
        assertEquals("You're riding with someone yourself.",
                RideAlong.driverRefusal(games, trials, kid, LOOP.id(), RideAlong.Purpose.SOLO), "a rider can't drive");
        trials.riders().sessionEnded(kid.getUniqueId());
        bench.restarts(List.of(LocalTime.of(12, 3)), 5); // a restart at 12:03, held from 11:58
        assertNotNull(RideAlong.driverRefusal(games, trials, dad, LOOP.id(), RideAlong.Purpose.SOLO),
                "no new rides in the restart hold");
        assertNotNull(RideAlong.riderRefusal(games, trials, dad, kid, RideAlong.Purpose.SOLO), "for either of them");
    }

    @Test
    void riderInvitesFollowPartyInvitesUntilTheirOwnChoiceIsStored() throws Exception {
        open(true, true);
        UUID k = kid.getUniqueId();
        assertTrue(games.invites().accepts(k, Riders.INVITE_KEY), "on for a new player");
        games.invites().setAccepts(k, "connect_four", false); // /hcm play invites off before riders existed
        games.invites().setAccepts(k, "tic_tac_toe", false);
        assertFalse(games.invites().accepts(k, Riders.INVITE_KEY), "an earlier 'off' keeps ride invites away too");
        games.invites().setAccepts(k, Riders.INVITE_KEY, true);
        assertTrue(games.invites().accepts(k, Riders.INVITE_KEY), "their own choice rules once stored");
    }

    @Test
    void riderRunsCountTrueARunWithARiderCountsAsNormal() throws Exception {
        open(true, true);
        assertTrue(trials.settings().riderRunsCount(), "the shipped value");
        trials.riders().paired(dad, kid, LOOP.id(), null);
        trials.riders().ofDriver(dad.getUniqueId()).seated = true; // Kid in the back seat
        FairPlay.Verdict ok = new FairPlay.Verdict(FairPlay.Kind.COUNTED, null);
        assertEquals(ok, trials.withRider(dad, ok), "a passenger doesn't change a boat's speed: it counts");
        assertNull(RideAlong.driverRefusal(games, trials, bench.player("Mum"), LOOP.id(), RideAlong.Purpose.NIGHT),
                "Race Night takes riders");
    }

    @Test
    void riderRunsCountFalseARunWithARiderIsJustForFunAndRaceNightTakesNone() throws Exception {
        open(true, false);
        FairPlay.Verdict ok = new FairPlay.Verdict(FairPlay.Kind.COUNTED, null);
        assertEquals(ok, trials.withRider(dad, ok), "no rider: counts");
        trials.riders().paired(dad, kid, LOOP.id(), null);
        assertEquals(ok, trials.withRider(dad, ok), "a rider who never got in: counts");
        trials.riders().ofDriver(dad.getUniqueId()).seated = true;
        FairPlay.Verdict fun = trials.withRider(dad, ok);
        assertEquals(FairPlay.Kind.VOID, fun.kind(), "just for fun: no board, record, rewards, Cup or points");
        assertEquals(Riders.FUN_ONLY, fun.reason(), "and the finish line says why");
        FairPlay.Verdict test = new FairPlay.Verdict(FairPlay.Kind.TEST, null);
        assertEquals(test, trials.withRider(dad, test), "a test run stays a test run");
        assertTrue(RideAlong.FUN_WARNING.contains("just for fun"), "the driver is told before the invite goes");
        Player mum = bench.player("Mum");
        assertEquals(RideAlong.NO_NIGHT, RideAlong.driverRefusal(games, trials, mum, LOOP.id(),
                RideAlong.Purpose.NIGHT), "Race Night refuses riders");
        assertFalse(RideAlong.nightOffered(games, trials.course(LOOP.id())), "and doesn't offer the button");
        assertNull(RideAlong.driverRefusal(games, trials, mum, LOOP.id(), RideAlong.Purpose.PARTY),
                "a party race still takes a rider, just for fun");
    }
}
