package com.dierks.homecraft.games;

import java.util.List;

/**
 * The featured game of the day (spec §8.3): one skill game or course, stable all day and changing
 * at local midnight, picked by a hash of the day key ({@code games.featured: auto}) or pinned by
 * id. Its tile says "Today's pick" and its first finish of the day pays {@code featured_bonus}
 * (once a day across all games). Never a game of chance: nothing may reward playing one (R1.17).
 */
public final class Featured {

    private final GamesService games;

    public Featured(GamesService games) {
        this.games = games;
    }

    /**
     * The pick for a day: {@code candidates} (the enabled, featurable play ids, in catalog order)
     * indexed by a mix of the day key. Pure, so the whole server agrees on it without storing it.
     *
     * @return the pick, or {@code null} when there are no candidates
     */
    public static String pick(List<String> candidates, long dayKey) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        long z = dayKey * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return candidates.get((int) Math.floorMod(z, (long) candidates.size()));
    }

    /** Today's featured play id (a game or course id), or {@code null} when there is none. */
    public String today() {
        // F1b: games.featured pinned (if still enabled and featurable) else pick(candidates, dayKey).
        return null;
    }

    /** Whether {@code playId} is today's pick. */
    public boolean isFeatured(String playId) {
        String today = today();
        return today != null && today.equalsIgnoreCase(playId);
    }

    /** When today's pick changes (the next local midnight, epoch ms). */
    public long until() {
        // F1b: clock.startOfDay(clock.dayKey() + 1).
        return 0;
    }
}
