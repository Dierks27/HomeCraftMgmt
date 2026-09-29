package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesScreens;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invites;
import com.dierks.homecraft.games.PlayerAs;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 *
 * <p>The final gate's group B: the ride invite's answer really runs, so accepting it ([Accept], {@code /hcm
 * play accept} or the Games screen's tile) seats the rider, and a no, an expiry or a quit tells the driver
 * (#0); and a player the play gate would refuse (no {@code hcm.games.play}, or not in a world games are
 * played in) is never asked to ride, and never pulled into a ride's session (#2).
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

    /** The player picker, as "Take a rider" opens it: who it would list, and the pick. */
    private static final class Picker implements GamesScreens {
        Predicate<Player> eligible;
        Consumer<Player> chosen;

        @Override
        public void games(Player player, Runnable back) {
        }

        @Override
        public void scores(Player player, Game game, String board, boolean lowerIsBetter, Runnable back) {
        }

        @Override
        public void takeABreak(Player player, Runnable back) {
        }

        @Override
        public void pickPlayer(Player player, Game game, Predicate<Player> eligible, Consumer<Player> chosen,
                               Runnable back) {
            this.eligible = eligible;
            this.chosen = chosen;
        }
    }

    /** Dad taps Take a rider on the loop's screen: the picker it opens. */
    private Picker takeARider() {
        Picker picker = new Picker();
        games.screens(picker);
        RideAlong.take(games, dad, LOOP.id(), RideAlong.Purpose.SOLO, null);
        assertNotNull(picker.chosen, "Take a rider opens the player picker");
        return picker;
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

    // ---- the final gate's group B -------------------------------------------------------------------

    @Test
    void acceptingARideInviteSeatsTheRiderBehindTheDriver() throws Exception {
        open(true, true);
        Picker picker = takeARider();
        assertTrue(picker.eligible.test(kid), "Kid may ride, so the picker lists them");
        picker.chosen.accept(kid);
        assertTrue(bench.heard(dad.getUniqueId()).contains("Ride invite sent to Kid"), bench.heard(dad.getUniqueId()));
        assertNotNull(games.invites().pending(kid.getUniqueId()), "the ride invite waits for Kid");
        assertTrue(games.invites().accept(kid), "Kid says yes: [Accept], /hcm play accept or the Games screen's tile");
        Riders.Ride ride = trials.riders().ofDriver(dad.getUniqueId());
        assertNotNull(ride, "the invite's answer runs: accepting pairs Kid with Dad (#0: it never ran, so nobody"
                + " could ever ride)");
        assertEquals(kid.getUniqueId(), ride.rider, "Kid is Dad's rider");
        assertTrue(bench.heard(dad.getUniqueId()).contains("Kid rides with you!"), "Dad is told: "
                + bench.heard(dad.getUniqueId()));
        assertTrue(bench.heard(kid.getUniqueId()).contains("You're riding with Dad!"), "and so is Kid: "
                + bench.heard(kid.getUniqueId()));
        assertEquals(0, bench.severe(), "nothing failed on the way");
    }

    @Test
    void aRideInviteTurnedDownRunOutOrCalledOffByAQuitTellsTheDriver() throws Exception {
        open(true, true);
        takeARider().chosen.accept(kid);
        assertTrue(games.invites().deny(kid), "Kid says no");
        assertTrue(bench.heard(dad.getUniqueId()).contains("Kid didn't hop in."), "Dad hears no (#0): "
                + bench.heard(dad.getUniqueId()));
        assertNull(trials.riders().ofDriver(dad.getUniqueId()), "and nobody rides");

        Player mum = bench.player("Mum");
        takeARider().chosen.accept(mum);
        bench.move(Riders.INVITE_SECONDS * 1000L);
        bench.runTasks();
        assertNull(games.invites().pending(mum.getUniqueId()), "Mum's invite ran out");
        assertTrue(bench.heard(dad.getUniqueId()).contains("Mum didn't hop in."), "Dad hears that too: "
                + bench.heard(dad.getUniqueId()));

        bench.move(Invites.PAIR_COOLDOWN_MS);
        Player ava = bench.player("Ava");
        takeARider().chosen.accept(ava);
        games.invites().cancel(ava.getUniqueId()); // Ava quits
        assertTrue(bench.heard(dad.getUniqueId()).contains("Ava didn't hop in."), "and a quit: "
                + bench.heard(dad.getUniqueId()));
        assertEquals(0, bench.severe(), "nothing failed on the way");
    }

    @Test
    void theRideInviteIsNamedForWhatItIsSoTheGamesScreenTileSaysSo() throws Exception {
        open(true, true);
        takeARider().chosen.accept(kid);
        assertEquals("Ride along", games.invites().pending(kid.getUniqueId()).name(),
                "the Games screen's tile NAME reads 'Ride along invite from Dad' (Bedrock reads only names)");
    }

    @Test
    void aPlayerWhoCantPlayGamesIsNeverAskedToRide() throws Exception {
        open(true, true);
        Player noGames = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.play"), null);
        Player nether = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        assertNotNull(RideAlong.riderRefusal(games, trials, dad, noGames, RideAlong.Purpose.SOLO),
                "a parent took the Games away: /hcm play rider Kim is refused (#2)");
        assertNotNull(RideAlong.riderRefusal(games, trials, dad, nether, RideAlong.Purpose.SOLO),
                "not in a world games are played in: refused, as /hcm play clubhouse is (#2)");
        Picker picker = takeARider();
        assertFalse(picker.eligible.test(noGames), "the picker leaves Kim out");
        assertFalse(picker.eligible.test(nether), "and Nia");
        assertTrue(picker.eligible.test(kid), "Kid may still ride");
    }

    @Test
    void theAnswerChecksThePlayGateAgainSoARiderWhoLostItStaysHome() throws Exception {
        open(true, true);
        Player kim = bench.player("Kim");
        Picker picker = takeARider();
        assertTrue(picker.eligible.test(kim), "Kim may ride when asked");
        picker.chosen.accept(PlayerAs.limited(kim, Set.of("hcm.games.play"), null)); // the parent steps in meanwhile
        assertTrue(games.invites().accept(kim), "Kim accepts");
        assertNull(trials.riders().ofDriver(dad.getUniqueId()), "but the answer's own check refuses: nobody rides");
        assertEquals(0, bench.severe(), "a refusal, not a failure");
    }

    @Test
    void aRidersSessionIsEnteredOnlyThroughThePlayGate() throws Exception {
        open(true, true);
        Player nether = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        org.bukkit.Location boat = new org.bukkit.Location(PlayerAs.world("games"), 0, 64, 0);
        assertFalse(RideAlong.live(trials).enter(nether, LOOP.id(), boat, p -> {
        }), "a paired rider who went to another world since is not pulled into the ride's session from there");
        assertTrue(bench.heard(nether.getUniqueId()).contains("Games can't be played in this world."),
                "they are told why, as by /hcm play: " + bench.heard(nether.getUniqueId()));
    }
}
