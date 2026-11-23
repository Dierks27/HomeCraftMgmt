package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob Races' settings: the shipped ones, which stakes can be offered at all (one where not even the strongest
 * favourite a card can have can be paid inside the band and under the cap is left out), and the settings as they are
 * read. What each pick pays is the race card's ({@link RaceCardTest}).
 */
class MobRacesSettingsTest {

    @Test
    void theShippedSettings() {
        MobRacesSettings s = MobRacesSettings.defaults();
        assertTrue(s.enabled());
        assertEquals(List.of(5, 10, 20), s.stakes());
        assertEquals(List.of(5, 10, 20), s.open(), "every shipped stake is offered");
        assertEquals(30, s.dailyLimit());
        assertEquals(90.0, s.rtp());
        assertEquals(20, s.gatherSeconds());
        assertEquals(25, s.raceSeconds());
        assertEquals(GamesConfig.Common.defaults().maxPayoutFor(s.stakes()), s.cap(), "games.max_payout");
        assertTrue(s.offers(5) && !s.offers(7));
        assertEquals(List.of("enabled", "stakes", "daily_limit", "rtp", "gather_seconds", "race_seconds"),
                MobRacesSettings.KEYS, "no new key: the card needs none");
        assertEquals("mob_races", MobRaces.SPEC.id());
        assertEquals(GameKind.CHANCE, MobRaces.SPEC.kind());
        assertEquals(MobRacesSettings.KEYS, MobRaces.SPEC.keys());
        assertNotNull(MobRaces.SPEC.settler(), "a round left open is finished from its own data");
        assertEquals(s.withDailyLimit(2).dailyLimit(), 2);
    }

    /** A stake is offered when the strongest favourite a card can have has a pay there in the band under the cap. */
    @Test
    void aStakeIsOfferedWhenAFavouriteCanBePaidThere() {
        for (int stake = 1; stake <= 300; stake++) {
            for (int cap : new int[]{250, 1000, 100_000}) {
                boolean any = false;
                for (int pays = stake + 1; pays <= Math.min(cap, 10 * stake); pays++) {
                    any |= RtpLimits.inBand(new RtpLimits.Ratio((long) RaceCard.HIGH * pays,
                            (long) RaceCard.WHOLE * stake));
                }
                assertEquals(any, MobRacesSettings.offerable(stake, 90, cap), stake + " under " + cap);
            }
        }
        assertTrue(MobRacesSettings.offerable(1, 90, 250), "1 in: 2 back is 90% for a 45% favourite");
        assertFalse(MobRacesSettings.offerable(200, 90, 250), "a 45% favourite at 200 pays 400: over the cap");
        // dollars, under the Parlor's biggest payout: every chip amount up to the biggest stake can be offered
        Parlor.Rules rules = Parlor.Rules.defaults();
        for (int dollars = 5; dollars <= rules.maxStake(); dollars += 5) {
            assertTrue(MobRacesSettings.offerable(dollars, 90, rules.capFor(dollars)), "$" + dollars);
        }
    }

    @Test
    void theSettingsAreReadClampedAndSolved() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", true);
        map.put("stakes", List.of(20, 5, 5, 1, 300));
        map.put("daily_limit", 12);
        map.put("rtp", 99);
        map.put("gather_seconds", 500);
        map.put("race_seconds", 2);
        MobRacesSettings s = MobRacesSettings.parse(new GamesConfig.Node("games.mob_races", map, warns::add),
                MobRacesSettings.defaults());
        assertEquals(List.of(1, 5, 20, 300), s.stakes(), "sorted, once each");
        assertEquals(12, s.dailyLimit());
        assertEquals(95.0, s.rtp(), "the band is locked in code");
        assertEquals(MobRacesSettings.GATHER_MAX, s.gatherSeconds());
        assertEquals(MobRacesSettings.RACE_MIN, s.raceSeconds());
        assertEquals(300, s.cap(), "max_payout, never under the largest stake");
        assertEquals(List.of(1, 5, 20), s.open(), "300: a favourite would pay over 600, over the cap: left out");
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.rtp")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.gather_seconds")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.race_seconds")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.contains("games.mob_races.stakes 300 is off")), warns.toString());

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
        GamesConfig.Node low = new GamesConfig.Node("games.mob_races", Map.of("stakes", List.of(1000)), warns::add);
        MobRacesSettings none = MobRacesSettings.parse(low, d);
        assertEquals(1000, none.cap());
        assertTrue(none.open().isEmpty());
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
