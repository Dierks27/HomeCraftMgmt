package com.dierks.homecraft.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The featured game of the day (spec §8.3): one skill game or course, stable all day and changing
 * at local midnight, picked by a hash of the day key ({@code games.featured: auto}) or pinned by
 * id. Its tile says "Today's pick" and its first finish of the day pays {@code featured_bonus}
 * (once a day across all games). Never a game of chance: nothing may reward playing one (R1.17).
 */
public final class Featured {

    private final GamesService games;
    /** Today's pick, worked out once a day (and again after a reload or when it closes). */
    private Pick cached;

    /** A day's pick: the play id and the game it belongs to. */
    private record Pick(long day, String setting, String playId, String gameId) {
    }

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

    /**
     * Today's featured play id (a game or course id), or {@code null} when there is none. A pinned
     * {@code games.featured} id wins while it names an open skill game or course; otherwise (and
     * with {@code auto}) it is {@link #pick} over {@link #candidates()}. Worked out once a day, so
     * it stays put even if a course is added in the afternoon.
     */
    public String today() {
        long day = games.host().clock().dayKey();
        String setting = games.config().common().featured();
        Pick p = cached;
        if (p != null && p.day() == day && p.setting().equals(setting) && stillOpen(p)) {
            return p.playId();
        }
        p = choose(day, setting);
        cached = p;
        return p == null ? null : p.playId();
    }

    /** Whether {@code playId} is today's pick. */
    public boolean isFeatured(String playId) {
        String today = today();
        return today != null && today.equalsIgnoreCase(playId);
    }

    /** When today's pick changes (the next local midnight, epoch ms). */
    public long until() {
        return games.host().clock().startOfDay(games.host().clock().dayKey() + 1);
    }

    /**
     * What {@code auto} picks from, in catalog order: every open game that may be featured (never
     * a game of chance) — its courses when it has some (by id), else the game itself.
     */
    public List<String> candidates() {
        List<String> out = new ArrayList<>();
        for (Game g : games.games()) {
            if (!featurable(g)) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (Game.Playable p : games.guard(g, g::playables, List.<Game.Playable>of())) {
                ids.add(p.id());
            }
            if (ids.isEmpty()) {
                if (!(g.kind() == GameKind.TRIAL || g.kind() == GameKind.GOLF)) {
                    out.add(g.id()); // a world game with no course has nothing to play
                }
            } else {
                Collections.sort(ids);
                out.addAll(ids);
            }
        }
        return out;
    }

    /** Forget today's pick (a reload, a game switched off): it is worked out again when asked. */
    void forget() {
        cached = null;
    }

    private Pick choose(long day, String setting) {
        if (!"auto".equals(setting)) {
            GamesService.Target t = games.resolve(setting);
            if (t != null && featurable(t.game())) {
                return new Pick(day, setting, t.playable() == null ? t.game().id() : t.playable().id(), t.game().id());
            }
        }
        String id = pick(candidates(), day);
        if (id == null) {
            return null;
        }
        GamesService.Target t = games.resolve(id);
        return new Pick(day, setting, id, t == null ? id : t.game().id());
    }

    private boolean stillOpen(Pick p) {
        Game g = games.game(p.gameId());
        return g != null && featurable(g);
    }

    private boolean featurable(Game g) {
        return g != null && !g.kind().chance() && games.guard(g, g::featurable, false) && games.enabled(g);
    }
}
