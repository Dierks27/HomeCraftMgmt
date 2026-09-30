package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #7: a Race Night prize that couldn't be paid when the night settled is owed, and the owed
 * prizes are paid by the framework at the next join and once a minute for everyone online, whether
 * Race Night is open or not: switching Race Night (or Time Trials) off after a prize night never
 * leaves a "your prize is waiting" line with nothing behind it.
 */
class RaceNightOwedTest {

    /** Saturday 3 October 2026, noon (the kit's zone). */
    private static final long T0 = GamesBench.at(2026, 10, 3, 12, 0);
    private static final long MIN = 60_000L;

    private GamesBench bench;
    private GamesService games;
    private EventDao dao;

    /** Race Night with its switch off (as it ships), after one prize night. */
    private static RaceNightSettings off() {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(false, List.of(), "loop", d.races(), d.laps(), d.announceMinutes(),
                d.joinMinutes(), d.adminJoinMinutes(), d.minRacers(), d.maxRacers(), d.finishWindowSeconds(),
                d.maxRaceMinutes(), d.breakSeconds(), d.warmupSeconds(), d.points(), d.finishPoints(),
                d.stillRacingPoints(), d.prizes(), d.finisherPrize(), d.prizeEventsPerWeek(), d.season(),
                d.standRadius());
    }

    @BeforeEach
    void setUp() {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", off());
        games = bench.games();
        dao = new EventDao(bench.db(), bench.dao());
        ((RaceNight) games.game("race_night")).dao(dao);
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    /** Friday's prize night: Ava came 2nd and was owed her 3 tokens. */
    private String owedNight(Player ava) throws Exception {
        String id = "rn-20261002-1900";
        dao.open(new EventDao.EventRow(id, "loop", T0 - 17 * 60 * MIN, T0 - 17 * 60 * MIN + 10 * MIN, EventDao.OPEN,
                NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8).encode(), 0, false, "", "",
                T0 - 18 * 60 * MIN, null, ""));
        assertTrue(dao.join(id, ava.getUniqueId(), "Ava", T0 - 17 * 60 * MIN), "Ava joined");
        dao.settle(id, List.of(new EventDao.Placed(ava.getUniqueId(), 2, 16, 3)), EventCopy.nightBoard(id),
                T0 - 16 * 60 * MIN);
        dao.setState(id, EventDao.DONE, "", T0 - 16 * 60 * MIN);
        assertEquals(1, dao.owed(ava.getUniqueId()).size(), "fixture: her prize is owed");
        return id;
    }

    @Test
    void anOwedPrizeIsPaidAtTheNextJoinWithRaceNightSwitchedOff() throws Exception {
        Player ava = bench.player("Ava");
        String id = owedNight(ava);
        assertFalse(games.enabled(games.game("race_night")), "Race Night is switched off again");
        int before = bench.balance(ava.getUniqueId());
        games.onJoin(ava);
        bench.runTasks();
        assertEquals(before + 3, bench.balance(ava.getUniqueId()), "her 3 tokens are paid at her join all the same");
        assertTrue(dao.owed(ava.getUniqueId()).isEmpty(), "and nothing is owed any more");
        EventDao.EntryRow row = dao.entries(id).get(0);
        assertNotNull(row.paidAt(), "the entry records the payment");
        games.onJoin(ava);
        bench.runTasks();
        assertEquals(before + 3, bench.balance(ava.getUniqueId()), "a second join pays nothing more");
    }

    @Test
    void anOwedPrizeIsPaidWithinAMinuteOnceTheRacerCanEarnWithRaceNightSwitchedOff() throws Exception {
        Player ava = bench.player("Ava");
        owedNight(ava);
        int before = bench.balance(ava.getUniqueId());
        bench.move(2 * MIN);
        bench.sweep(); // the framework's one-minute sweep, Race Night off
        assertEquals(before + 3, bench.balance(ava.getUniqueId()),
                "online where tokens can be earned: paid by the next sweep, though Race Night is off");
    }
}
