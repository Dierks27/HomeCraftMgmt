package com.dierks.homecraft.games.clubhouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who in the Clubhouse is on their way to a game (0.38.3, {@code solo_first}): pure, with the clock passed
 * in, so the countdown, "Go now" and "Stay here" are tested without a server.
 *
 * <p>A player who clicked Start on a course or a golf course arrives in the Clubhouse first ("Off to Sky
 * Rings!"); {@link #start} begins their countdown ({@code games.clubhouse.first_seconds}, 1-30). Once a
 * second {@link #second} says how many whole seconds each has left ("Sky Rings in 3"), and 0 when the trip
 * is due. "Go now" and a countdown that ran out {@link #take} the trip (once); "Stay here" calls it off
 * ({@link #stay}): they hang out, and the Games item starts one again. While a trip is pending the idle clock
 * stands still ({@link #waiting}, the Clubhouse's {@code busy}).
 */
public final class ClubLobby {

    /** The fewest and most seconds a player waits before they're taken on. */
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 30;

    /** One player's pending trip: what to, and when it goes (epoch ms). */
    record Pending(ClubNext next, long goAt) {
    }

    /**
     * A second's work for one player: their trip and the whole seconds left (0 = go now).
     *
     * @param player who is on their way
     * @param next   where to
     * @param left   whole seconds left, never below 0
     */
    public record Tick(UUID player, ClubNext next, int left) {
    }

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /** {@code player} arrived for {@code next}: it goes {@code seconds} (clamped 1-30) from {@code now}. */
    public void start(UUID player, ClubNext next, long now, int seconds) {
        if (player == null || next == null) {
            return;
        }
        int s = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        pending.put(player, new Pending(next, now + s * 1_000L));
    }

    /** "Go now", or the countdown ran out: the trip, taken (once; {@code null} when there is none). */
    public ClubNext take(UUID player) {
        Pending p = player == null ? null : pending.remove(player);
        return p == null ? null : p.next();
    }

    /** "Stay here": the trip is called off; what it was ({@code null} when there was none). */
    public ClubNext stay(UUID player) {
        return take(player);
    }

    /** The player's pending trip, or {@code null}. */
    public ClubNext next(UUID player) {
        Pending p = player == null ? null : pending.get(player);
        return p == null ? null : p.next();
    }

    /** Whether a trip is pending: the idle clock stands still (the Clubhouse's {@code busy}). */
    public boolean waiting(UUID player) {
        return player != null && pending.containsKey(player);
    }

    /**
     * Once a second: every pending trip with its whole seconds left, {@code ceil((goAt - now) / 1000)},
     * never below 0 (0: due now). Nothing is taken here: the Clubhouse takes a due trip with {@link #take}.
     */
    public List<Tick> second(long now) {
        List<Tick> out = new ArrayList<>();
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            long ms = e.getValue().goAt() - now;
            int left = ms <= 0 ? 0 : (int) ((ms + 999L) / 1_000L);
            out.add(new Tick(e.getKey(), e.getValue().next(), left));
        }
        return out;
    }

    /** The player left the Clubhouse (any way at all): no trip. */
    public void forget(UUID player) {
        if (player != null) {
            pending.remove(player);
        }
    }

    /** Everyone's trips go (the Clubhouse stopped). */
    public void clear() {
        pending.clear();
    }

    /** How many trips are pending. */
    public int size() {
        return pending.size();
    }
}
