package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.hunt.HuntMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who has a Mini Radar on, and what each one does at a check, kept free of the server so it can be tested.
 * {@link RadarService} asks it once a second and does what it says.
 *
 * <p>A radar locks onto one wild Mini (its hunt key) at the moment it is used, and reads at once. After that it
 * reads every {@code interval_seconds}, until {@code lasts_seconds} have passed since it locked on (then it is
 * spent, and says so once) or until that Mini is caught or gets away. The settings are asked at every check, so a
 * reload changes a radar that is already on.
 *
 * <p>With {@code one_per_hunt}, a player's radar works once per wild Mini: every Mini a player has locked onto is
 * remembered while it is out, and a second radar on it is refused before anything is spent. A Mini that is caught
 * or gets away is forgotten at the next check.
 *
 * <p>All of it is memory only, like the hunt cooldowns: a restart starts a new one with nothing on and nothing used.
 * A wild Mini is kept across a restart, so a radar running on it stops without a word, and a player who used a
 * radar on it before the restart can use one more on it after. That is a narrow window (a restart while that Mini
 * is out, at most a hunt's few minutes) and is accepted so that the radar adds no table (the schema stays as it
 * was, and the way back to the release before stays the jar alone).
 */
public final class RadarLocks {

    /** What using a radar did. */
    public enum Lock {
        /** It locked on (spend the radar). */
        ON,
        /** No wild Mini to lock onto (nothing is spent). */
        NO_MINI,
        /** This player already used a radar on this Mini and only one is allowed (nothing is spent). */
        USED
    }

    /** What a radar does at one check. */
    public enum Step {
        /** Show a reading. */
        READ,
        /** Its time is up: say so once; it is off now. */
        SPENT,
        /** Its Mini was caught or got away: say so once; it is off now. */
        GONE
    }

    /** One radar's step at a check: whose it is, the hunt it is locked onto, and what to do. */
    public record Due(UUID player, String huntKey, Step step) {
    }

    /** One radar that is on: its hunt, when it locked on, and when it last read (milliseconds). */
    private record Charge(String huntKey, long lockedAt, long lastReadingAt) {
    }

    private final Map<UUID, Charge> charges = new HashMap<>();
    /** Player → the hunts they have locked a radar onto, while those hunts are out. */
    private final Map<UUID, Set<String>> used = new HashMap<>();

    /**
     * A radar used at {@code now} on {@code huntKey} (null when no wild Mini is out). A radar that locks on reads at
     * once, so {@link #check} counts its next reading from {@code now}.
     */
    public Lock lock(UUID player, String huntKey, long now, PluginConfig.Radar settings) {
        if (huntKey == null) {
            return Lock.NO_MINI;
        }
        Set<String> mine = used.computeIfAbsent(player, k -> new HashSet<>());
        if (settings.onePerHunt() && mine.contains(huntKey)) {
            return Lock.USED;
        }
        mine.add(huntKey);
        charges.put(player, new Charge(huntKey, now, now));
        return Lock.ON;
    }

    /** True while this player's radar is on: locked onto a Mini that is still out, with charge left at {@code now}. */
    public boolean active(UUID player, long now, Predicate<String> live, PluginConfig.Radar settings) {
        Charge c = charges.get(player);
        return c != null && live.test(c.huntKey()) && HuntMath.charged(c.lockedAt(), settings.lastsSeconds(), now);
    }

    /**
     * Every radar's step at {@code now}: GONE when its Mini is no longer out, SPENT when its time is up, READ when a
     * reading is due; a radar with nothing due this second is left out. GONE and SPENT turn it off. Hunts that are
     * no longer out are forgotten for {@code one_per_hunt}.
     */
    public List<Due> check(long now, Predicate<String> live, PluginConfig.Radar settings) {
        List<Due> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Charge>> it = charges.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Charge> e = it.next();
            Charge c = e.getValue();
            if (!live.test(c.huntKey())) {
                out.add(new Due(e.getKey(), c.huntKey(), Step.GONE));
                it.remove();
            } else if (!HuntMath.charged(c.lockedAt(), settings.lastsSeconds(), now)) {
                out.add(new Due(e.getKey(), c.huntKey(), Step.SPENT));
                it.remove();
            } else if (HuntMath.readingDue(c.lastReadingAt(), settings.intervalSeconds(), now)) {
                out.add(new Due(e.getKey(), c.huntKey(), Step.READ));
                e.setValue(new Charge(c.huntKey(), c.lockedAt(), now));
            }
        }
        for (Iterator<Set<String>> it = used.values().iterator(); it.hasNext(); ) {
            Set<String> mine = it.next();
            mine.removeIf(key -> !live.test(key));
            if (mine.isEmpty()) {
                it.remove();
            }
        }
        return out;
    }

    /** True while {@code player} is remembered as having used a radar on {@code huntKey}. */
    public boolean usedOn(UUID player, String huntKey) {
        Set<String> mine = used.get(player);
        return mine != null && mine.contains(huntKey);
    }
}
