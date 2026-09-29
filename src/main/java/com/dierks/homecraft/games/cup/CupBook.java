package com.dierks.homecraft.games.cup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Weekly Cup's book kept in memory: entries, Cup times and settlements, with exactly the
 * once-only rules the database version must keep (§D2 rules). It is the reference the storage code is
 * written and tested against, and what the property tests drive with random weeks.
 *
 * <ul>
 *   <li>an entry is written once per player per Cup (course and week), only into the current
 *       week's Cup, and never into a Cup that is settled or voided;</li>
 *   <li>a run counts for each open Cup the player is in on that course whose week is in the run's
 *       {@link CupRules#runWeeks}, and only when it started after they entered;</li>
 *   <li>a Cup is settled or voided once: the second call returns {@code null} and pays nothing;</li>
 *   <li>{@link #due} lists every unsettled Cup whose week is over, so a rollover the server missed
 *       is settled at the next boot.</li>
 * </ul>
 *
 * <p>Tokens are not kept here; {@link CupRules#ledger} gives the movements a plan makes. Not
 * thread-safe: the plugin's version runs its transactions one at a time.
 */
public final class CupBook {

    private final Map<CupKey, Map<UUID, CupEntry>> entries = new LinkedHashMap<>();
    private final Map<CupKey, CupPlan> settled = new LinkedHashMap<>();

    /**
     * Enter {@code player} in {@code key}'s Cup, paying {@code fee}, if {@link CupRules#refusal}
     * allows it; the entry and the spend are one transaction in the plugin.
     *
     * @param currentWeek the Cup week at {@code now} ({@link CupRules#week}): a key of any other week
     *                    is refused, even before its settlement has run
     * @return {@code null} when they are in, or why not
     */
    public CupRefusal enter(CupKey key, UUID player, int fee, int balance, long now, long currentWeek,
                            boolean cupsOn, boolean courseOn) {
        CupPlan s = settled.get(key);
        CupRefusal r = CupRules.refusal(cupsOn, courseOn, key, currentWeek, s == null ? null : s.outcome(),
                in(key, player), fee, balance);
        if (r != null) {
            return r;
        }
        entries.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(player, CupEntry.entered(player, fee, now));
        return null;
    }

    /**
     * A counted run of {@code ms} by {@code player} on {@code course}, finished at {@code at}, for the
     * Cup weeks {@code weeks} ({@link CupRules#runWeeks}). In every one of those Cups that the player
     * is in and that is still open, it becomes their Cup time when it beats it and started after they
     * entered ({@link CupEntry#withRun}).
     *
     * @return whether any Cup time changed
     */
    public boolean run(String course, UUID player, long ms, long at, CupRules.Weeks weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<CupKey, Map<UUID, CupEntry>> c : entries.entrySet()) {
            CupKey key = c.getKey();
            if (!key.course().equals(course) || !weeks.contains(key.week()) || settled.containsKey(key)) {
                continue;
            }
            CupEntry e = c.getValue().get(player);
            if (e == null) {
                continue;
            }
            CupEntry next = e.withRun(ms, at);
            if (next != e) {
                c.getValue().put(player, next);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Settle {@code key}'s Cup with a top-up of {@code topup}: {@link CupRules#settle}, recorded.
     *
     * @return the plan, or {@code null} when the Cup was already settled or voided (nothing to pay)
     */
    public CupPlan settle(CupKey key, int topup) {
        if (settled.containsKey(key)) {
            return null;
        }
        CupPlan plan = CupRules.settle(key, entries(key), topup);
        settled.put(key, plan);
        return plan;
    }

    /**
     * Void {@code key}'s Cup mid-week: {@link CupRules#voided}, recorded, so the week can't be entered
     * or settled again. A Cup nobody has entered has nothing to give back and isn't voided: it stays
     * open, and anyone who enters from now on races the course as it now is.
     *
     * @return the refund plan, or {@code null} when the Cup was already settled or voided, or has no
     *         entries
     */
    public CupPlan voidCup(CupKey key, CupPlan.VoidReason reason) {
        if (settled.containsKey(key) || entries(key).isEmpty()) {
            return null;
        }
        CupPlan plan = CupRules.voided(key, entries(key), reason);
        settled.put(key, plan);
        return plan;
    }

    /**
     * The unsettled Cups with entries whose own seven days are over, oldest first
     * ({@link CupRules#due}).
     *
     * @param today the course day now, {@code edition.day(now)}
     */
    public List<CupKey> due(long today) {
        List<CupKey> open = new ArrayList<>();
        for (Map.Entry<CupKey, Map<UUID, CupEntry>> e : entries.entrySet()) {
            if (!e.getValue().isEmpty() && !settled.containsKey(e.getKey())) {
                open.add(e.getKey());
            }
        }
        return CupRules.due(open, today);
    }

    /** {@code key}'s settlement, or {@code null} while it is open. */
    public CupPlan settlement(CupKey key) {
        return settled.get(key);
    }

    /** Every settlement so far, in the order they were made. */
    public Collection<CupPlan> settlements() {
        return List.copyOf(settled.values());
    }

    /** {@code key}'s entries, in entry order. */
    public List<CupEntry> entries(CupKey key) {
        Map<UUID, CupEntry> m = entries.get(key);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    /** Whether {@code player} is in {@code key}'s Cup. */
    public boolean in(CupKey key, UUID player) {
        Map<UUID, CupEntry> m = entries.get(key);
        return m != null && m.containsKey(player);
    }

    /** {@code key}'s pool as the screens show it while the week runs. */
    public CupRules.LivePool live(CupKey key, int topup) {
        return CupRules.livePool(entries(key), topup);
    }
}
