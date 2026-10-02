package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load-time check that a one-time reward paid whole fits each daily cap it counts toward
 * ({@link RewardCeilings}, BALANCE-SPEC §5.2 #6). It replaced the config comment "Keep each at or
 * under 4".
 *
 * <p>Pinned here: the shipped block gives no WARN; Golf of the Week's 25 under a golf cap of 20 gives
 * exactly one, naming both keys and what it can pay; a reward over the skill cap names the skill cap;
 * a cap of 0 gives none (nothing pays: the owner's choice); only the configured cadence's amount is
 * checked; the Star Chart goals, a Falling Floors milestone and a cabinet medal are checked too; and a
 * WARN closes nothing and changes no value.
 */
class RewardCeilingsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = RewardCeilingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> games, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> m = games;
        for (int i = 0; i < parts.length - 1; i++) {
            m = (Map<String, Object>) m.get(parts[i]);
        }
        m.put(parts[parts.length - 1], value);
    }

    /** The shipped games block (on) with {@code kv} set, parsed: its WARNs go to {@code warns}. */
    private static GamesConfig.Parsed with(List<String> warns, Object... kv) throws Exception {
        Map<String, Object> games = shipped();
        put(games, "enabled", true);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            put(games, (String) kv[i], kv[i + 1]);
        }
        return GamesConfig.parse(games, warns::add, null);
    }

    /**
     * The shipped block with every cap the check reads opened wide (whatever the token balance ships, no
     * reward is over one), then {@code kv}: each case sets the reward and the cap it is about, so it
     * reads the same whatever the shipped numbers are.
     */
    private static GamesConfig.Parsed isolated(List<String> warns, Object... kv) throws Exception {
        List<Object> all = new ArrayList<>(List.of("skill_daily_cap", 1000, "trials.daily_cap", 1000,
                "golf.daily_cap", 1000, "fresh.daily_cap", 100, "falling_floors.daily_cap", 100));
        for (String cab : TokenBalance.SOLO_CABINETS) {
            all.add(cab + ".daily_cap");
            all.add(1000);
        }
        all.addAll(List.of(kv));
        return with(warns, all.toArray());
    }

    @Test
    void theShippedBlockGivesNoWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed p = with(warns);
        assertEquals(List.of(), warns, "the shipped caps hold every reward paid whole");
        assertEquals(List.of(), RewardCeilings.problems(p), "and the check finds nothing");
        assertEquals(List.of(), RewardCeilings.problems(GamesConfig.Parsed.DEFAULTS), "nor in the Java defaults");
        List<String> open = new ArrayList<>();
        isolated(open);
        assertEquals(List.of(), open, "nor with every cap opened wide");
    }

    @Test
    void golfOfTheWeekOverTheGolfCapGivesOneWarnNamingBothKeys() throws Exception {
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed p = isolated(warns, "golf.daily_cap", 20, "fresh.rewards.clear_weekly.fresh_golf", 25,
                "fresh.rewards.clear_weekly.fresh_tiny_golf", 10);
        assertEquals(List.of("games.fresh.rewards.clear_weekly.fresh_golf 25 is more than games.golf.daily_cap 20, "
                + "so it can only ever pay 20 - raise the cap or lower the reward"), warns, "exactly one WARN");
        assertTrue(p.enabled(), "the games stay on");
        assertEquals(Set.of(), p.unreadable(), "nothing is closed");
        assertEquals(20, p.settings(com.dierks.homecraft.games.golf.MiniGolf.SPEC).dailyCap(), "the cap is as set");
        assertEquals(25, p.settings(com.dierks.homecraft.games.gen.DailyCourses.SPEC).dailyClear("fresh_golf"),
                "and the reward is as set: nothing is clamped");
    }

    @Test
    void aRewardOverTheSkillCapNamesTheSkillCap() throws Exception {
        List<String> warns = new ArrayList<>();
        isolated(warns, "skill_daily_cap", 101, "fresh.rewards.clear_weekly.fresh_golf", 100, "golf.daily_cap", 150,
                "fresh.rewards.clear_weekly.fresh_parkour_hard", 100, "trials.daily_cap", 99);
        // fresh.daily_cap tops out at 100 and the other rewards stay under 101
        assertTrue(warns.isEmpty() || warns.stream().noneMatch(w -> w.contains("skill_daily_cap 101")),
                "fixture: nothing is over a skill cap of 101: " + warns);
        assertEquals(List.of("games.fresh.rewards.clear_weekly.fresh_parkour_hard 100 is more than games.trials.daily_cap "
                + "99, so it can only ever pay 99 - raise the cap or lower the reward"), warns,
                "the smaller cap is the one named: the one it would be paid");
        warns.clear();
        isolated(warns, "skill_daily_cap", 22, "fresh.rewards.clear_weekly.fresh_golf", 25,
                "fresh.rewards.clear_weekly.fresh_tiny_golf", 10);
        assertTrue(warns.contains("games.fresh.rewards.clear_weekly.fresh_golf 25 is more than games.skill_daily_cap 22, "
                + "so it can only ever pay 22 - raise the cap or lower the reward"), "Golf of the Week's 25: " + warns);
        assertTrue(warns.stream().allMatch(w -> w.contains(" is more than games.skill_daily_cap 22, ")),
                "every WARN is about the skill cap, the only small one: " + warns);
    }

    @Test
    void aCapOfZeroGivesNoWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        isolated(warns, "golf.daily_cap", 0, "fresh.rewards.clear_weekly.fresh_golf", 25);
        assertEquals(List.of(), warns, "golf paying nothing is the owner's choice, not a reward stranded");
        with(warns, "skill_daily_cap", 0);
        assertEquals(List.of(), warns, "nor the skill games paying nothing at all");
    }

    @Test
    void onlyTheConfiguredCadencesAmountIsChecked() throws Exception {
        List<String> warns = new ArrayList<>();
        isolated(warns, "trials.daily_cap", 40, "fresh.rewards.clear_weekly.fresh_parkour", 15,
                "fresh.rewards.clear_daily.fresh_parkour", 45);
        assertTrue(warns.stream().noneMatch(w -> w.contains("fresh_parkour ")),
                "the daily end pays nothing on a weekly cadence: " + warns);
        warns.clear();
        isolated(warns, "fresh.cadence", "daily", "trials.daily_cap", 40, "fresh.rewards.clear_daily.fresh_parkour", 45);
        assertTrue(warns.contains("games.fresh.rewards.clear_daily.fresh_parkour 45 is more than games.trials.daily_cap "
                + "40, so it can only ever pay 40 - raise the cap or lower the reward"), "on a daily one it does: " + warns);
        warns.clear();
        isolated(warns, "fresh.cadence", 3, "trials.daily_cap", 12, "fresh.rewards.clear_daily.fresh_parkour_hard", 10,
                "fresh.rewards.clear_weekly.fresh_parkour_hard", 20);
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.fresh.rewards.clear_daily/clear_weekly."
                + "fresh_parkour_hard (at a 3-day cadence) 13 is more than games.trials.daily_cap 12")),
                "every 3 days Hard Parkour pays round(10 + 10 * 2/6) = 13: " + warns);
    }

    @Test
    void theStarChartFloorsAndCabinetMedalsAreCheckedToo() throws Exception {
        List<String> warns = new ArrayList<>();
        isolated(warns, "fresh.daily_cap", 8, "fresh.star_goals.weekly_tokens", List.of(5, 10));
        assertEquals(List.of("games.fresh.star_goals.weekly_tokens (the 12-star goal) 10 is more than "
                + "games.fresh.daily_cap 8, so it can only ever pay 8 - raise the cap or lower the reward"), warns,
                "the 12-star goal's 10 doesn't fit a Star Chart cap of 8");
        warns.clear();
        isolated(warns, "falling_floors.daily_cap", 12, "falling_floors.milestone_rewards", List.of(5, 10, 15));
        assertEquals(List.of("games.falling_floors.milestone_rewards (the 120 s milestone) 15 is more than "
                + "games.falling_floors.daily_cap 12, so it can only ever pay 12 - raise the cap or lower the reward"),
                warns, "the 120 s milestone's 15 doesn't fit 12");
        warns.clear();
        isolated(warns, "snake.daily_cap", 4, "snake.milestone_reward", 5);
        assertEquals(List.of("games.snake.milestone_reward 5 is more than games.snake.daily_cap 4, so it can only "
                + "ever pay 4 - raise the cap or lower the reward"), warns, "a medal of 5 doesn't fit Snake's 4");
    }

    @Test
    void aPartialOrUncappedRewardIsNeverChecked() throws Exception {
        List<String> warns = new ArrayList<>();
        isolated(warns, "skill_daily_cap", 20, "snake.daily_reward", 30, "trials.first_clear.extreme", 500,
                "featured_bonus", 90, "race_night.prizes", List.of(30, 20, 10), "trials.weekly_best_bonus", 90,
                "golf.par_reward", 90);
        assertTrue(warns.stream().noneMatch(w -> w.contains("daily_reward") || w.contains("first_clear")
                || w.contains("featured_bonus") || w.contains("race_night") || w.contains("weekly_best_bonus")
                || w.contains("par_reward")), "a daily goal, today's pick, a best and par pay what is left; a first "
                + "clear and a Race Night prize are not capped: " + warns);
    }
}
