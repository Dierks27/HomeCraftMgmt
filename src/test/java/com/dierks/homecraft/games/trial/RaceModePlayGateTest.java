package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.PlayGate;
import com.dierks.homecraft.games.PlayerAs;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Race mode's play gate (the round-2 audit's G1 #3, the rest of the final gate's #2): a racer seated from
 * home, whoever asks (a Race Night's grid call, a late joiner, a racer back after a disconnect, a party
 * race's start), is refused when they no longer have {@code hcm.games.play}, before anything of theirs
 * is saved or moved, as a rider is refused at the boat. Only the permission: a joined Race Night racer
 * who walked into the nether is still taken to the track from there, by design (EVENTS-DROPPER-SPEC
 * §A.4.2, the join line "Keep playing - we'll take you to the track").
 *
 * <p>The real framework with no server. The course here is not ready, so a racer the gate lets through
 * stops at the course check, before anything would need a world.
 */
class RaceModePlayGateTest {

    /** A race that is on, with nothing else. */
    private static final class Night implements RaceLink {
        @Override
        public boolean alive() {
            return true;
        }

        @Override
        public long goTick() {
            return 100;
        }

        @Override
        public void progress(UUID racer, int reachedTargets, double toNext, long nanos) {
        }

        @Override
        public void finished(UUID racer, long raceMs, boolean counted, String voidReason) {
        }

        @Override
        public void left(UUID racer, EndReason why) {
        }
    }

    private static final String NOT_READY = "That course isn't ready right now.";

    private GamesBench bench;
    private TimeTrials trials;
    private final Course track = Course.create("ice_loop", TrialKind.BOAT, Tier.EASY);

    @BeforeEach
    void setUp() {
        bench = new GamesBench(GamesBench.at(2026, 10, 2, 18, 59), List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials",
                TimeTrialsSettings.defaults(), "race_night", RaceNightSettings.defaults());
        trials = (TimeTrials) bench.games().game("trials");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    @Test
    void aRacerWhoLostThePlayPermissionIsRefusedWithTheGatesOwnLine() {
        Player kim = PlayerAs.limited(bench.player("Kim"), Set.of(PlayGate.PERMISSION_PLAY), null);
        assertEquals(Refusal.NO_GAMES, trials.race(kim, track, track, null, null, new Night()),
                "Kim joined Race Night, then a parent took the Games away: never pulled onto the grid, and told why");
    }

    @Test
    void theGateIsOnlyThePermissionNotTheWorld() {
        Player ava = bench.player("Ava");
        assertEquals(NOT_READY, trials.race(ava, track, track, null, null, new Night()).message(),
                "Ava may play: the gate lets her on to the next check");
        Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        assertEquals(NOT_READY, trials.race(nia, track, track, null, null, new Night()).message(),
                "Nia, joined and now in the nether, is taken to the track from there as designed: the world step is"
                        + " never asked at the grid");
    }
}
