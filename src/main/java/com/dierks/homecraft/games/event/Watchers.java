package com.dierks.homecraft.games.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is watching Race Night from anywhere (EVENTS-DROPPER-SPEC §A.5): the Race Night screen's
 * <b>Watch</b> button. A watcher gets the leader in a bossbar ("Race Night · race 2 of 3 · leader:
 * Sam") and the finish lines in chat, with no world session, so they can keep playing or building.
 * A second tap stops it; so does the end of the night, {@value #AFTER_MS} ms after the results.
 * Pure: no Bukkit.
 */
public final class Watchers {

    /** Watching stops this long after the night's results. */
    public static final long AFTER_MS = 30_000L;

    /** Watcher → when they stop (0: while the night lasts). */
    private final Map<UUID, Long> until = new LinkedHashMap<>();

    /** A tap on Watch: start watching, or stop. @return whether they are watching now */
    public boolean toggle(UUID player) {
        if (until.remove(player) != null) {
            return false;
        }
        until.put(player, 0L);
        return true;
    }

    /** Whether the player is watching. */
    public boolean watching(UUID player) {
        return until.containsKey(player);
    }

    /** Stop watching (a quit). */
    public void remove(UUID player) {
        until.remove(player);
    }

    /** Everyone watching, in the order they started. */
    public Set<UUID> ids() {
        return Set.copyOf(until.keySet());
    }

    /** The night ended at {@code now}: everyone stops watching {@value #AFTER_MS} ms later. */
    public void nightEnded(long now) {
        until.replaceAll((k, v) -> now + AFTER_MS);
    }

    /** Who stops watching at {@code now} (they are no longer watching). */
    public List<UUID> expire(long now) {
        List<UUID> out = new java.util.ArrayList<>();
        until.entrySet().removeIf(e -> {
            boolean gone = e.getValue() > 0 && now >= e.getValue();
            if (gone) {
                out.add(e.getKey());
            }
            return gone;
        });
        return out;
    }

    /** Nobody watches (the game stopped). */
    public void clear() {
        until.clear();
    }

    /**
     * A watcher's bossbar: "&amp;bRace Night &amp;7· race 2 of 3 · leader: &amp;fSam", or what is on
     * before and after the racing.
     */
    public static String bar(EventMachine.Phase phase, int race, int of, String leader, int racers) {
        return bar(phase, race, of, leader, racers, false);
    }

    /** The same, on a night whose track is a Mountain Run when {@code downhill}: its warm-up is "warm-up runs". */
    public static String bar(EventMachine.Phase phase, int race, int of, String leader, int racers, boolean downhill) {
        if (phase == null) {
            return "&bRace Night";
        }
        String lead = leader == null ? "" : " &7· leader: &f" + leader;
        return switch (phase) {
            case SCHEDULED, OPEN -> "&bRace Night &7· " + EventCopy.racers(racers) + " in so far";
            case WARMUP -> "&bRace Night &7· " + EventCopy.warmup(downhill).toLowerCase(Locale.ROOT) + " · "
                    + EventCopy.racers(racers);
            case GRID, RACING -> "&bRace Night &7· race " + Math.max(1, race) + " of " + of + lead;
            case BREAK -> "&bRace Night &7· break after race " + race + " of " + of + lead;
            case SETTLING, DONE -> "&6Race Night is over" + (leader == null ? "" : " &7· winner: &f" + leader);
            case CALLED_OFF -> "&7Race Night was called off";
        };
    }
}
