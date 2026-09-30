package com.dierks.homecraft.config;

import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.cup.CupOptIn;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.event.RaceNightSettings;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The events batch as it ships, in one place (EVENTS-RECONCILED, the owner's choices): everything is
 * off or harmless except what the owner wants on.
 * <ul>
 *   <li>warm-ups ON ({@code games.trials.warmup_seconds: 180}), party races up to 8;</li>
 *   <li>the Weekly Cup ON, and on by default on Fresh parkour, Sky Rings, Ice Boat and Dropper
 *       courses (the shipped weekly Fresh schedule keeps one layout a whole Cup week), never golf;</li>
 *   <li>the two droppers ON inside {@code games.fresh}, which itself ships OFF (the Ice Boat stays off);</li>
 *   <li>Race Night OFF (with the 5/3/2 + 1 prizes when it is on), Falling Floors OFF;</li>
 *   <li>the Clubhouse ON (it builds itself in its own empty box, and moves no tokens), 30 minutes a
 *       visit, and back to it after a party race, Race Night and golf together; a boat run with a
 *       rider in the back counts as normal ({@code games.trials.rider_runs_count: true}).</li>
 * </ul>
 * Each is read from the bundled config.yml through the real parser, so a config.yml edit that
 * switches one of them fails here, not on the owner's server.
 */
class EventsShippedDefaultsTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = EventsShippedDefaultsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    @Test
    void theEventsBatchShipsOffOrHarmlessExceptWhatTheOwnerWantsOn() throws Exception {
        YamlConfiguration yml = bundled();
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(yml.getConfigurationSection("games"), warns::add, null);
        assertEquals(List.of(), warns, "the shipped games block reads without a WARN");
        for (var spec : List.of(TimeTrials.SPEC, WeeklyCup.SPEC, DailyCourses.SPEC, RaceNight.SPEC, FallingFloors.SPEC,
                Clubhouse.SPEC)) {
            assertTrue(GameCatalog.SPECS.contains(spec), spec.id() + " is parsed from config.yml, not only defaulted");
            assertTrue(parsed.readable(spec.id()), spec.id() + "'s shipped block is readable");
        }

        TimeTrialsSettings trials = parsed.settings(TimeTrials.SPEC);
        assertEquals(180, yml.getInt("games.trials.warmup_seconds"), "config.yml: warm-ups of 3:00");
        assertEquals(180, trials.warmupSeconds(), "warm-ups ship ON at 180 seconds (D3)");
        assertTrue(trials.warmupsOn(), "and are offered");
        assertEquals(8, trials.partyMax(), "a party race holds up to 8 (D4)");
        assertTrue(yml.getBoolean("games.trials.rider_runs_count"), "config.yml: a run with a rider counts");
        assertTrue(trials.riderRunsCount(), "ride along ships with the driver's run counting as normal (WP-CH)");

        CupSettings cup = parsed.settings(WeeklyCup.SPEC);
        assertTrue(yml.getBoolean("games.cup.enabled"), "config.yml: the Cup is on");
        assertTrue(cup.enabled(), "the Weekly Cup ships ON (D2)");
        assertEquals(5, cup.entry(), "a 5-token entry");
        assertEquals(10, cup.serverTopup(), "a top-up of 10");

        DailySettings fresh = parsed.settings(DailyCourses.SPEC);
        assertFalse(fresh.enabled(), "Fresh Courses ships OFF: nothing is built until the owner turns it on");
        assertTrue(fresh.slot("fresh_dropper_easy").enabled(), "Easy Dropper ships on inside games.fresh");
        assertTrue(fresh.slot("fresh_dropper").enabled(), "the Dropper ships on inside games.fresh");
        assertFalse(fresh.slot("fresh_boat").enabled(), "the Ice Boat ships off");

        DayOfWeek weekStart = DayOfWeek.valueOf(yml.getString("arcade.quests.week_starts").toUpperCase(java.util.Locale.ROOT));
        ZoneId zone = ZoneId.of(yml.getString("clock.time_zone"));
        boolean weekly = CupRules.freshEligible(fresh.edition(zone, weekStart));
        assertTrue(weekly, "the shipped Fresh schedule (weekly, from the quests' week start) keeps a layout a whole Cup week");
        for (String kind : List.of("parkour", "elytra", "boat", "dropper")) {
            assertTrue(CupOptIn.on(null, new CupOptIn.Course(true, false, kind), weekly),
                    "a Fresh " + kind + " course runs a Cup by default");
        }
        assertFalse(CupOptIn.on(null, new CupOptIn.Course(true, false, "golf"), weekly), "never golf");
        assertFalse(CupOptIn.on(null, new CupOptIn.Course(false, false, "parkour"), weekly),
                "a hand-built course only after /hcm games cup on <id>");

        RaceNightSettings night = parsed.settings(RaceNight.SPEC);
        assertFalse(yml.getBoolean("games.race_night.enabled"), "config.yml: Race Night is off");
        assertFalse(night.enabled(), "Race Night ships OFF");
        assertEquals(List.of(5, 3, 2), night.prizes(), "prizes 5/3/2 when it is on");
        assertEquals(1, night.finisherPrize(), "plus 1 for every other finisher");

        FallingFloorsSettings floors = parsed.settings(FallingFloors.SPEC);
        assertFalse(yml.getBoolean("games.falling_floors.enabled"), "config.yml: Falling Floors is off");
        assertFalse(floors.enabled(), "Falling Floors ships OFF");

        ClubhouseSettings club = parsed.settings(Clubhouse.SPEC);
        assertTrue(yml.getBoolean("games.clubhouse.enabled"), "config.yml: the Clubhouse is on");
        assertTrue(club.enabled(), "the Clubhouse ships ON: it builds itself in an empty box (CLUBHOUSE-SPEC §5)");
        assertEquals(30, yml.getInt("games.clubhouse.max_minutes"), "config.yml: 30 minutes");
        assertEquals(30, club.maxMinutes(), "a visit with nothing going lasts up to 30 minutes");
        assertTrue(yml.getBoolean("games.clubhouse.party_after"), "config.yml: party_after");
        assertTrue(club.partyAfter(), "party racers come back to the Clubhouse after the race");
        assertTrue(yml.getBoolean("games.clubhouse.race_night_after"), "config.yml: race_night_after");
        assertTrue(club.raceNightAfter(), "everyone at the track goes to the Clubhouse at the end of Race Night");
        assertTrue(yml.getBoolean("games.clubhouse.golf_after"), "config.yml: golf_after");
        assertTrue(club.golfAfter(), "a golf-together group goes to the Clubhouse when its round ends");
        assertEquals(ClubhouseSettings.ORIGIN, yml.getIntegerList("games.clubhouse.origin"),
                "config.yml: the shipped corner (ExtraBoxesApartTest keeps it apart from everything else)");
        assertEquals(ClubhouseSettings.defaults(), club, "the shipped block is exactly the Clubhouse's defaults");
    }
}
