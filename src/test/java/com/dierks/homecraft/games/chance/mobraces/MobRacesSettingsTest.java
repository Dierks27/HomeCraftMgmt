package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings.Odds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a winning pick pays: one whole amount per stake, its exact return ({@code pays / (6 x stake)}) inside the
 * 85-95 band and as near the target as whole amounts allow, for the shipped token stakes and for every dollar amount
 * the Game Parlor could be asked for; and the settings as they are read.
 */
class MobRacesSettingsTest {

    @Test
    void theShippedStakesGiveBackExactlyNinetyOfEveryHundred() {
        MobRacesSettings s = MobRacesSettings.defaults();
        assertTrue(s.enabled());
        assertEquals(List.of(5, 10, 20), s.stakes());
        assertEquals(List.of(5, 10, 20), s.open(), "every shipped stake is offered");
        assertEquals(30, s.dailyLimit());
        assertEquals(90.0, s.rtp());
        assertEquals(20, s.gatherSeconds());
        assertEquals(25, s.raceSeconds());
        int[][] pays = {{5, 27}, {10, 54}, {20, 108}};
        for (int[] p : pays) {
            Odds o = s.odds(p[0]);
            assertNotNull(o, "stake " + p[0]);
            assertEquals(p[1], o.pays(), p[0] + " in: a winning pick gets " + p[1]);
            assertEquals(0, o.ratio().compareTo(new RtpLimits.Ratio(9, 10)), "exactly 90 of every 100");
            assertEquals(0.9, o.rtp(), 1e-12);
            assertTrue(o.pays() <= GamesConfig.Common.defaults().maxPayoutFor(s.stakes()), "under max_payout");
        }
        assertEquals(0.9, s.lowestRtp(), 1e-12);
        assertEquals("gives back about 90 of every 100 tokens", RtpLimits.playerLine(s.lowestRtp()),
                "what the screens say is the exact return, floored");
        assertNull(s.odds(7), "a stake that isn't offered has no odds");
        assertEquals(List.of("enabled", "stakes", "daily_limit", "rtp", "gather_seconds", "race_seconds"),
                MobRacesSettings.KEYS);
        assertEquals("mob_races", MobRaces.SPEC.id());
        assertEquals(GameKind.CHANCE, MobRaces.SPEC.kind());
        assertEquals(MobRacesSettings.KEYS, MobRaces.SPEC.keys());
        assertNotNull(MobRaces.SPEC.settler(), "a round left open is finished from its own data");
    }

    /**
     * Every stake and every target: what is solved is inside the band, at or under the target whenever a whole amount
     * in the band is, and then the nearest one to it; over the target only when nothing under it is in the band, and
     * then the lowest that is. A stake with no whole amount in the band has no odds at all.
     */
    @Test
    void everySolvedStakeIsInTheBandAndAsNearTheTargetAsWholeAmountsAllow() {
        int solved = 0;
        int none = 0;
        for (double rtp = 85; rtp <= 95; rtp += 0.5) {
            RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
            for (int stake = 1; stake <= 300; stake++) {
                Odds o = MobRacesSettings.solve(stake, rtp, 100_000);
                // every whole amount in the band, by brute force
                List<Integer> inBand = new ArrayList<>();
                for (int pays = 1; pays <= 6 * stake; pays++) {
                    if (RtpLimits.inBand(new RtpLimits.Ratio(pays, 6L * stake))) {
                        inBand.add(pays);
                    }
                }
                String what = "stake " + stake + ", target " + rtp;
                if (inBand.isEmpty()) {
                    assertNull(o, what + ": nothing in the band, so no odds");
                    none++;
                    continue;
                }
                assertNotNull(o, what + ": " + inBand + " are in the band");
                solved++;
                assertEquals(stake, o.stake());
                assertTrue(RtpLimits.inBand(o.ratio()), what + ": " + o.pays() + " is in the band");
                assertTrue(o.pays() > stake, what + ": a win is more than what went in");
                Integer best = null;
                for (int pays : inBand) {
                    if (new RtpLimits.Ratio(pays, 6L * stake).compareTo(target) <= 0) {
                        best = pays; // the largest at or under the target
                    }
                }
                assertEquals(best != null ? best : inBand.get(0), o.pays(), what + ": the nearest under the target,"
                        + " or the lowest in the band when none is");
            }
        }
        assertTrue(solved > 5_000 && none > 0, solved + " solved, " + none + " with nothing in the band");
        assertNull(MobRacesSettings.solve(1, 90, 1000), "1 in: 5 back is 83%, 6 back is 100%: no odds");
        assertEquals(11, MobRacesSettings.solve(2, 90, 1000).pays(), "2 in: 11 back is 91.6%, the only one in the band");
        assertNull(MobRacesSettings.solve(0, 90, 1000));
        assertNull(MobRacesSettings.solve(-5, 90, 1000));
    }

    /** Dollars are solved the same way, under the Parlor's biggest payout for that stake. */
    @Test
    void everyDollarStakeTheParlorAcceptsIsInTheBand() {
        Parlor.Rules rules = Parlor.Rules.defaults();
        for (int dollars = 5; dollars <= rules.maxStake(); dollars += 5) {
            Odds o = MobRacesSettings.solve(dollars, 90, rules.capFor(dollars));
            assertNotNull(o, "$" + dollars);
            assertEquals(dollars * 27 / 5, o.pays(), "$" + dollars + " in: a winning pick gets $" + dollars * 27 / 5);
            assertEquals(0.9, o.rtp(), 1e-12, "exactly 90 of every 100");
            assertTrue(o.pays() <= rules.capFor(dollars), "under the Parlor's biggest payout");
        }
        // every whole-dollar stake an admin could allow, under every payout cap: in the band, or not offered
        int offered = 0;
        for (int cap : new int[]{50, 100, 500, 5_000, Parlor.PAYOUT_CEILING}) {
            for (int dollars = 1; dollars <= Parlor.STAKE_CEILING; dollars++) {
                int most = Math.max(cap, dollars);
                for (double rtp : new double[]{85, 90, 92.5, 95}) {
                    Odds o = MobRacesSettings.solve(dollars, rtp, most);
                    if (o == null) {
                        continue;
                    }
                    offered++;
                    assertTrue(RtpLimits.inBand(o.ratio()), "$" + dollars + " under $" + most + ": " + o.pays());
                    assertTrue(o.pays() <= most, "$" + dollars + ": never over the biggest payout");
                }
            }
        }
        assertTrue(offered > 5_000, "plenty of stakes are offered: " + offered);
        assertNull(MobRacesSettings.solve(20, 90, 100), "a cap under what a win must pay closes the stake: 100 of 120"
                + " is 83%");
        assertNotNull(MobRacesSettings.solve(20, 90, 102), "102 of 120 is 85%: just in");
    }

    @Test
    void theSettingsAreReadClampedAndSolved() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", true);
        map.put("stakes", List.of(20, 5, 5, 1));
        map.put("daily_limit", 12);
        map.put("rtp", 99);
        map.put("gather_seconds", 500);
        map.put("race_seconds", 2);
        MobRacesSettings s = MobRacesSettings.parse(new GamesConfig.Node("games.mob_races", map, warns::add),
                MobRacesSettings.defaults());
        assertEquals(List.of(1, 5, 20), s.stakes(), "sorted, once each");
        assertEquals(12, s.dailyLimit());
        assertEquals(95.0, s.rtp(), "the band is locked in code");
        assertEquals(MobRacesSettings.GATHER_MAX, s.gatherSeconds());
        assertEquals(MobRacesSettings.RACE_MIN, s.raceSeconds());
        assertEquals(List.of(5, 20), s.open(), "1 can't land in the band: left out");
        assertEquals(28, s.odds(5).pays(), "28 of 30 is 93.3%, the nearest under 95");
        assertEquals(114, s.odds(20).pays(), "114 of 120 is exactly 95");
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.rtp")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.gather_seconds")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.race_seconds")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.stakes 1 is off")), warns.toString());

        // nothing set: exactly as shipped, without a word
        warns.clear();
        MobRacesSettings d = MobRacesSettings.defaults();
        assertEquals(d, MobRacesSettings.parse(new GamesConfig.Node("games.mob_races", Map.of(), warns::add), d));
        assertTrue(warns.isEmpty(), warns.toString());

        // junk closes the game, with a WARN, and never throws
        GamesConfig.Node junk = new GamesConfig.Node("games.mob_races", Map.of("stakes", "lots"), warns::add);
        MobRacesSettings.parse(junk, d);
        assertTrue(junk.invalid(), "junk is junk");
        assertFalse(warns.isEmpty());

        // a stake nothing can be paid on closes the game, and says so
        warns.clear();
        MobRacesSettings one = MobRacesSettings.parse(new GamesConfig.Node("games.mob_races",
                Map.of("stakes", List.of(1)), warns::add), d);
        assertTrue(one.odds().isEmpty() && one.open().isEmpty());
        assertTrue(Double.isNaN(one.lowestRtp()));
        assertTrue(warns.stream().anyMatch(w -> w.contains("Mob Races is closed")), warns.toString());
    }

    @Test
    void aRaceIsAboutALapEveryEightSecondsAndAtLeastOne() {
        assertEquals(1, MobRacesSettings.laps(MobRacesSettings.RACE_MIN));
        assertEquals(3, MobRacesSettings.laps(25));
        assertEquals(3, MobRacesSettings.defaults().laps());
        assertEquals(7, MobRacesSettings.laps(MobRacesSettings.RACE_MAX));
        assertEquals(500, MobRacesSettings.defaults().raceTicks());
        for (int seconds = MobRacesSettings.RACE_MIN; seconds <= MobRacesSettings.RACE_MAX; seconds++) {
            double blocksASecond = MobRacesSettings.laps(seconds) * RaceTrack.LAP / seconds;
            assertTrue(blocksASecond > 1.4 && blocksASecond < 2.7, seconds + " s: " + blocksASecond
                    + " blocks a second, a steady trot for a tiny mob");
        }
    }

    @Test
    void theSixRacersEachHaveANumberAColourAndASize() {
        assertEquals(6, Racer.values().length);
        assertEquals(Racer.COUNT, Racer.values().length);
        java.util.Set<Character> codes = new java.util.HashSet<>();
        java.util.Set<String> dyes = new java.util.HashSet<>();
        java.util.Set<String> mobs = new java.util.HashSet<>();
        for (Racer r : Racer.values()) {
            assertSame(r, Racer.of(r.lane()));
            assertEquals(r.lane() + 1, r.number());
            assertTrue(codes.add(r.code()), r + " has a text colour of its own");
            assertTrue(dyes.add(r.dye()), r + " has a dye of its own");
            assertTrue(mobs.add(r.mob()), r + " is a mob of its own");
            assertTrue(r.scale() >= 0.25 && r.scale() <= 0.35, r + " is drawn at a quarter to a third: " + r.scale());
            assertEquals("&" + r.code() + r.number() + " " + r.mobName(), r.label());
            assertNotNull(org.bukkit.entity.EntityType.valueOf(r.mob()), r + " is a mob this server has");
            assertNotNull(org.bukkit.Material.getMaterial(r.dye() + "_BANNER"), r + "'s banner");
            assertEquals(3, r.rgb().length);
        }
    }
}
