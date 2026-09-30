package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.PlayerAs;
import com.dierks.homecraft.games.gen.NewCoursesNudge;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night never nudges a player who can't play the Games (the final gate's #1): a child whose parent
 * took {@code hcm.games.play} away hears none of its chat lines (so never a [Join] that answers "You don't
 * have permission.") and never sees the join window's bar, as the rest of the module keeps its nudges
 * from them. Both ask the one {@link LivePorts#who}, on the real framework, so they can't drift apart.
 */
class RaceNightNewsGateTest {

    private GamesBench bench;
    private GamesService games;
    private RaceNight night;

    @BeforeEach
    void setUp() {
        bench = new GamesBench(GamesBench.at(2026, 10, 2, 18, 50), List.of(TimeTrials.SPEC, RaceNight.SPEC),
                "trials", TimeTrialsSettings.defaults(), "race_night", RaceNightSettings.defaults());
        games = bench.games();
        night = (RaceNight) games.game("race_night");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    @Test
    void aPlayerWhoCantPlayTheGamesHearsNoRaceNightLine() {
        Player ava = bench.player("Ava");
        Player kim = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.play"), null);
        for (Announcer.Line line : Announcer.Line.values()) {
            assertTrue(Announcer.hears(line, LivePorts.who(games, ava, true, false), 0), "Ava hears " + line);
            assertFalse(Announcer.hears(line, LivePorts.who(games, kim, true, false), 0),
                    "Kim, whose parent took the Games away, never hears " + line + " or gets its [Join] (#1)");
        }
    }

    @Test
    void theJoinBarIsOnlyForPlayersWhoCouldJoin() {
        Player ava = bench.player("Ava");
        Player kim = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.play"), null);
        Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        assertTrue(night.joinBar(ava, false), "Ava, who could join, sees the join window's countdown");
        assertFalse(night.joinBar(kim, false), "Kim never sees it: /hcm play race would refuse her (#1)");
        assertFalse(night.joinBar(nia, false), "nor someone where games aren't played: joining from there is refused");
        assertTrue(night.joinBar(nia, true), "but a racer who joined still sees the time to the start, wherever");
    }

    @Test
    void theBarsTheNightDrawsAskTheSameGate() {
        Player ava = bench.player("Ava");
        Player kim = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.play"), null);
        Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
        assertEquals(RaceNight.Bar.JOIN, night.barFor(ava, null, true), "the join window is open: Ava sees its bar");
        assertEquals(RaceNight.Bar.NONE, night.barFor(kim, null, true),
                "what the night draws for Kim is nothing, although her news is on (#1: the bar asks the play gate)");
        assertEquals(RaceNight.Bar.NONE, night.barFor(nia, null, true), "nor for Nia, where games aren't played");
        NightRunner.Racer joined = new NightRunner.Racer(nia.getUniqueId(), "Nia", 0L);
        assertEquals(RaceNight.Bar.JOIN, night.barFor(nia, joined, true), "until she has joined: then the countdown");
        joined.seated = true;
        assertEquals(RaceNight.Bar.OWN, night.barFor(nia, joined, true), "seated, the night draws her own bar");
        assertEquals(RaceNight.Bar.NONE, night.barFor(ava, null, false), "no join window: no join bar");
        assertTrue(night.watch(ava), "Ava watches");
        assertEquals(RaceNight.Bar.WATCH, night.barFor(ava, null, true), "a watcher sees the watchers' bar instead");
    }

    @Test
    void newsOffStillHidesTheBar() throws Exception {
        Player ava = bench.player("Ava");
        bench.dao().setPref(ava.getUniqueId(), NewCoursesNudge.PREF_NEWS, "off");
        assertFalse(night.joinBar(ava, false), "/hcm play news off hides the join bar, as before");
    }
}
