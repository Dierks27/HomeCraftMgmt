package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.golf.MiniGolfSettings;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The load-time check that every one-time reward paid whole fits each daily cap it counts toward
 * (BALANCE-SPEC §5.2 #6, the owner's 2 Oct token balance).
 *
 * <p><b>Why.</b> A reward paid whole ({@link SkillRewards#payWhole}) is all or nothing under the
 * game's {@code daily_cap} and {@code games.skill_daily_cap}, and one that is bigger than a whole
 * day's cap can never fit: {@code GamesDao.wholePay} pays it that cap, once, and it is done. Until
 * 0.37 the config said so in a comment ("Keep each at or under 4"). Now the plugin says it when it
 * loads, naming both keys, so a cap lowered below a reward (or a reward raised above a cap) is seen
 * the day it is made, not the day a player is short-paid.
 *
 * <p><b>What it checks</b>, only the rewards paid whole and only at the amounts that will be paid:
 * <ul>
 *   <li>each Fresh Courses course's first finish of a set at the configured cadence
 *       ({@link DailySettings.SlotConfig#dailyClear()}), against {@code games.trials.daily_cap} or
 *       {@code games.golf.daily_cap} (by {@link Slots.Def#golf()});</li>
 *   <li>each Star Chart goal's tokens at the configured cadence, against {@code games.fresh.daily_cap};</li>
 *   <li>each Falling Floors {@code milestone_rewards} entry, against its {@code daily_cap};</li>
 *   <li>each cabinet's {@code milestone_reward}, against its {@code daily_cap};</li>
 * </ul>
 * and every one of them against {@code games.skill_daily_cap}. A partial reward (a daily goal, today's
 * pick) or an uncapped one (a first clear, a Race Night prize) can't be stranded this way, so it isn't
 * checked; nor is the other cadence's end of a table, which pays nothing until the cadence changes.
 * That keeps one clamped value to one WARN.
 *
 * <p><b>Falling Floors' milestones together</b> as well as each on its own: one solo round passes every milestone
 * up to how long it lasted, so the one that first reaches the last passes them all at once (ECON-R3-00).
 *
 * <p><b>And the Weekly Cup's family rule</b> (BALANCE-SPEC §3.2): while the Cup is on, a
 * {@code server_topup} below twice its {@code entry} gives one WARN naming both keys, since third of a Cup
 * of 3 where everyone set a time then gets back less than they paid (the v4 audit, ECON01). An owner may
 * choose that (a smaller mint); the WARN says what it costs.
 *
 * <p><b>It only speaks.</b> A cap of 0 means "these pay nothing" and is the owner's choice, so it gives
 * no WARN; a reward over a cap gives exactly one, naming the smallest cap it passes (the one it would
 * be paid). Nothing is clamped, closed or changed. Pure: no Bukkit.
 */
public final class RewardCeilings {

    private RewardCeilings() {
    }

    /**
     * One cap a reward counts toward.
     *
     * @param key    its full config key
     * @param tokens its value
     */
    record Cap(String key, int tokens) {
    }

    /**
     * Every WARN for {@code p}: one line per whole-paid reward that is bigger than a cap it counts
     * toward, in config order. Empty for the shipped settings.
     */
    public static List<String> problems(GamesConfig.Parsed p) {
        List<String> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        Cap skill = new Cap(GamesConfig.PATH + ".skill_daily_cap", p.common().skillDailyCap());

        for (GameSpec<?> spec : GameCatalog.SPECS) {
            Object s = p.settings(spec);
            if (s instanceof CabinetSettings cab && cab.milestoneReward() > 0) {
                String base = GamesConfig.PATH + "." + GamesConfig.block(spec.id());
                check(out, base + ".milestone_reward", cab.milestoneReward(),
                        new Cap(base + ".daily_cap", cab.dailyCap()), skill);
            }
        }

        DailySettings fresh = p.settings(DailyCourses.SPEC);
        TimeTrialsSettings trials = p.settings(TimeTrials.SPEC);
        MiniGolfSettings golf = p.settings(MiniGolf.SPEC);
        String freshBase = GamesConfig.PATH + "." + GamesConfig.block(DailyCourses.SPEC.id());
        int cadence = fresh.cadenceDays();
        for (DailySettings.SlotConfig slot : fresh.slots()) {
            Slots.Def def = slot.def();
            if (def == null) {
                continue;
            }
            Cap game = def.golf()
                    ? new Cap(capKey(MiniGolf.SPEC.id()), golf.dailyCap())
                    : new Cap(capKey(TimeTrials.SPEC.id()), trials.dailyCap());
            check(out, clearKey(freshBase, def.id(), cadence), slot.dailyClear(), game, skill);
        }
        Cap star = new Cap(freshBase + ".daily_cap", fresh.dailyCap());
        String goalKey = freshBase + ".star_goals."
                + (DailyStars.nearerWeekly(cadence) ? "weekly_tokens" : "daily_tokens");
        for (DailyStars.Goal goal : fresh.goals().forCadence(cadence)) {
            check(out, goalKey + " (the " + goal.stars() + "-star goal)", goal.tokens(), star, skill);
        }

        FallingFloorsSettings floors = p.settings(FallingFloors.SPEC);
        String floorsBase = GamesConfig.PATH + "." + GamesConfig.block(FallingFloors.SPEC.id());
        List<Integer> secs = floors.milestones();
        List<Integer> toks = floors.milestoneRewards();
        Cap floorsCap = new Cap(floorsBase + ".daily_cap", floors.dailyCap());
        int before = out.size();
        for (int i = 0; i < toks.size(); i++) {
            String which = i < secs.size() ? " (the " + secs.get(i) + " s milestone)" : "";
            check(out, floorsBase + ".milestone_rewards" + which, toks.get(i), floorsCap, skill);
        }
        if (out.size() == before) {
            together(out, floorsBase + ".milestone_rewards", secs, toks, floorsCap, skill);
        }

        family(out, p.settings(WeeklyCup.SPEC));
        return out;
    }

    /**
     * One WARN when Falling Floors' milestones together pass a cap they count toward, though each fits on its own
     * (the v4 audit, ECON-R3-00): a solo round claims every milestone it passes, in order, each paid whole, so the
     * round that first lasts to the last milestone passes all of them at once, and whatever the cap can't hold
     * then waits for another day's run that long again. A cap of 0 is the owner's "pays nothing", as above.
     */
    private static void together(List<String> out, String key, List<Integer> secs, List<Integer> toks, Cap... caps) {
        int sum = 0;
        int count = 0;
        int longest = 0;
        for (int i = 0; i < toks.size() && i < secs.size(); i++) {
            if (secs.get(i) > 0 && toks.get(i) > 0) {
                sum += toks.get(i);
                count++;
                longest = Math.max(longest, secs.get(i));
            }
        }
        Cap smallest = null;
        for (Cap c : caps) {
            if (c.tokens() <= 0) {
                return;
            }
            if (smallest == null || c.tokens() < smallest.tokens()) {
                smallest = c;
            }
        }
        if (count >= 2 && smallest != null && sum > smallest.tokens()) {
            out.add(key + " " + sum + " together is more than " + smallest.key() + " " + smallest.tokens()
                    + ", and one round that first lasts " + longest + " s passes them all, so the last waits for"
                    + " another day - raise the cap to at least " + sum
                    + " or lower the rewards");
        }
    }

    /**
     * One WARN when the Cup is on with a top-up below twice its entry: 3 in at E with a top-up T share 3E + T
     * as 50/30/20, and 20% of it is E only when T is at least 2E.
     */
    private static void family(List<String> out, CupSettings cup) {
        if (cup == null || !cup.enabled() || cup.serverTopup() >= 2 * cup.entry()) {
            return;
        }
        String base = GamesConfig.PATH + "." + GamesConfig.block(WeeklyCup.SPEC.id());
        int third = CupRules.split(3 * cup.entry() + cup.serverTopup(), CupRules.shares(3))[2];
        out.add(base + ".server_topup " + cup.serverTopup() + " is less than twice " + base + ".entry " + cup.entry()
                + ", so in a Cup of 3 where everyone sets a time, third gets back " + third + " of the " + cup.entry()
                + " they paid - set server_topup to at least " + 2 * cup.entry() + " (or lower the entry) so nobody in"
                + " a Cup of 2 or 3 loses");
    }

    /** {@code games.<game's block>.daily_cap}. */
    private static String capKey(String gameId) {
        return GamesConfig.PATH + "." + GamesConfig.block(gameId) + ".daily_cap";
    }

    /** {@link #problems}, each line to {@code warn}; never throws. */
    public static void warn(GamesConfig.Parsed p, Consumer<String> warn) {
        if (warn == null) {
            return;
        }
        for (String line : problems(p)) {
            warn.accept(line);
        }
    }

    /**
     * The key a Fresh course's first finish is read from at an N-day cadence: {@code clear_weekly}
     * (7 or more), {@code clear_daily} (1), or both ends for the days in between.
     */
    static String clearKey(String freshBase, String slot, int cadence) {
        int n = Edition.clampCadence(cadence);
        if (n >= Edition.WEEKLY) {
            return freshBase + ".rewards.clear_weekly." + slot;
        }
        if (n == Edition.DAILY) {
            return freshBase + ".rewards.clear_daily." + slot;
        }
        return freshBase + ".rewards.clear_daily/clear_weekly." + slot + " (at a " + n + "-day cadence)";
    }

    /** One WARN when {@code tokens} is bigger than the smallest of {@code caps} and none of them is 0. */
    private static void check(List<String> out, String key, int tokens, Cap... caps) {
        if (tokens <= 0) {
            return;
        }
        Cap smallest = null;
        for (Cap c : caps) {
            if (c.tokens() <= 0) {
                return; // a cap of 0 pays nothing at all: the owner's choice, not a reward stranded
            }
            if (smallest == null || c.tokens() < smallest.tokens()) {
                smallest = c;
            }
        }
        if (smallest != null && tokens > smallest.tokens()) {
            out.add(key + " " + tokens + " is more than " + smallest.key() + " " + smallest.tokens()
                    + ", so it can only ever pay " + smallest.tokens() + " - raise the cap or lower the reward");
        }
    }
}
