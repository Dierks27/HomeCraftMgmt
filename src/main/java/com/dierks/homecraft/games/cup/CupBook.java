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
 *   <li>an entry is written once per player per Cup (course and week), and never into a Cup that is
 *       settled or voided;</li>
 *   <li>a Cup is settled or voided once: the second call returns {@code null} and pays nothing;</li>
 *   <li>{@link #due} lists every unsettled Cup of a past week, so a rollover the server missed is
 *       settled at the next boot.</li>
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
     * @return {@code null} when they are in, or why not
     */
    public CupRefusal enter(CupKey key, UUID player, int fee, int balance, long now, boolean cupsOn,
                            boolean courseOn) {
        CupPlan s = settled.get(key);
        CupRefusal r = CupRules.refusal(cupsOn, courseOn, s == null ? null : s.outcome(), in(key, player),
                fee, balance);
        if (r != null) {
            return r;
        }
        entries.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(player, CupEntry.entered(player, fee, now));
        return null;
    }

    /**
     * A counted run by {@code player} on {@code key}'s course and week. It becomes their Cup time when
     * they are in, the Cup is still open, and it beats their time ({@link CupEntry#withRun}).
     *
     * @return whether their Cup time changed
     */
    public boolean run(CupKey key, UUID player, long ms, long at) {
        if (settled.containsKey(key)) {
            return false;
        }
        Map<UUID, CupEntry> m = entries.get(key);
        CupEntry e = m == null ? null : m.get(player);
        if (e == null) {
            return false;
        }
        CupEntry next = e.withRun(ms, at);
        if (next == e) {
            return false;
        }
        m.put(player, next);
        return true;
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

    /** The unsettled Cups with entries from weeks before {@code currentWeek}, oldest first. */
    public List<CupKey> due(long currentWeek) {
        List<CupKey> open = new ArrayList<>();
        for (Map.Entry<CupKey, Map<UUID, CupEntry>> e : entries.entrySet()) {
            if (!e.getValue().isEmpty() && !settled.containsKey(e.getKey())) {
                open.add(e.getKey());
            }
        }
        return CupRules.due(open, currentWeek);
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
