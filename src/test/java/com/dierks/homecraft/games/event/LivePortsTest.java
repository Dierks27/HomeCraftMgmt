package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #6, the live hop: a Race Night settles after its last race, when a racer may be offline, and
 * {@link LivePorts#progress} is how the settle tells the achievements ("Race at Race Night", "Win a
 * Race Night"). It tells them by id, so a racer who logged off before the results still has the night
 * counted; it never looks the racer up online first (a lookup that finds nobody would drop them).
 *
 * <p>The real framework with no server: the racers here are online nowhere, and a Bukkit lookup
 * would find no server at all.
 */
class LivePortsTest {

    /** Friday 2 October 2026, 19:30 (the kit's zone): a night settling. */
    private static final long T0 = GamesBench.at(2026, 10, 2, 19, 30);

    private GamesBench bench;
    private GamesService games;
    private RaceNight night;
    /** What the achievements were told, by id. */
    private final Map<UUID, Boolean> byId = new LinkedHashMap<>();
    /** Who the achievements were told of as an online player. */
    private final List<Player> byPlayer = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", RaceNightSettings.defaults());
        games = bench.games();
        night = (RaceNight) games.game("race_night");
        games.progress(new GameProgress() {
            @Override
            public void raceNightFinished(UUID player, boolean won) {
                byId.put(player, won);
            }

            @Override
            public void raceNightFinished(Player player, boolean won) {
                byPlayer.add(player);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    @Test
    void aRacerOfflineWhenTheNightSettlesIsStillToldToTheAchievementsById() {
        UUID ava = new UUID(0, 1); // won the night, then logged off before it settled
        UUID ben = new UUID(0, 2); // raced it, and is gone too
        LivePorts ports = new LivePorts(night, "rn-20261002-1900");
        ports.progress(ava, true);
        ports.progress(ben, false);
        assertEquals(Map.of(ava, true, ben, false), byId, "both are counted though neither is online: Ava raced and"
                + " won it, Ben raced it");
        assertTrue(byPlayer.isEmpty(), "told by id, not as online players: " + byPlayer);
        assertEquals(0, bench.severe(), "nothing threw");
    }
}
