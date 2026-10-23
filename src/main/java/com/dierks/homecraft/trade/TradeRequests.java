package com.dierks.homecraft.trade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trade requests (SHOP-V2-SPEC §4.2), modelled on the Games' invites ({@code games/Invites.java}), pure: one request
 * waiting for each player, one out from each, a 30 s cooldown for the pair after a no, a timeout or the asker
 * calling it off (not after asking: asking, cancelling and asking again was the spam path, red team D-m10), and a
 * count of what each player sent in the last minute. The clock is the caller's, so it is tested without a server.
 *
 * <p><b>A double accept runs once:</b> {@link #accept} removes the request as it hands it over, so a second tap,
 * command or crouch-click finds nothing.
 *
 * <p><b>A yes can wait</b> (red-team finding TW-1): when the target says yes while a window can't open over what one
 * of them has open (a chest, a crafting table, a carried stack), the request stays, marked {@link Request#yes()},
 * and the windows open the second both are free; it still runs out at its time like any other.
 */
public final class TradeRequests {

    /** The same two players can't be asked again for this long after a no, a timeout or a call-off. */
    public static final long PAIR_COOLDOWN_MS = 30_000L;
    /** The window the "three a minute" limit counts in. */
    static final long MINUTE_MS = 60_000L;

    /** One request: who asked whom, when, when it runs out, and whether the target already said yes. */
    public record Request(long id, UUID from, String fromName, UUID to, String toName, long at, long expiresAt,
                          boolean yes) {

        public boolean expired(long now) {
            return now >= expiresAt;
        }

        /** Whole seconds left (0 once it ran out). */
        public int secondsLeft(long now) {
            long left = expiresAt - now;
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }
    }

    /** Invitee → the request waiting for them. */
    private final Map<UUID, Request> byTarget = new LinkedHashMap<>();
    /** The unordered pair → when its cooldown ends. */
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    /** Asker → when they sent their recent requests. */
    private final Map<UUID, Deque<Long>> sent = new HashMap<>();
    private long nextId = 1;

    /**
     * Send a request the ask check has passed. A request still waiting for {@code to} is replaced (the check refuses
     * that case, so this is a backstop).
     */
    public Request send(UUID from, String fromName, UUID to, String toName, long now, int seconds) {
        Request r = new Request(nextId++, from, fromName, to, toName, now, now + Math.max(1, seconds) * 1000L,
                false);
        byTarget.put(to, r);
        Deque<Long> times = sent.computeIfAbsent(from, k -> new ArrayDeque<>());
        times.addLast(now);
        trim(times, now);
        return r;
    }

    /** The request waiting for {@code to}, or null (also once its time is up). */
    public Request incoming(UUID to, long now) {
        Request r = byTarget.get(to);
        return r == null || r.expired(now) ? null : r;
    }

    /** The request {@code from} has out, or null. */
    public Request outgoing(UUID from, long now) {
        for (Request r : byTarget.values()) {
            if (r.from().equals(from) && !r.expired(now)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Whether {@code player} has a request out, or one waiting for them, with anyone but {@code other} (§4.2 (6):
     * neither player may have a pending request with someone else).
     */
    public boolean pendingWithOther(UUID player, UUID other, long now) {
        Request in = incoming(player, now);
        if (in != null && !in.from().equals(other)) {
            return true;
        }
        Request out = outgoing(player, now);
        return out != null && !out.to().equals(other);
    }

    /** The pair is inside its cooldown. */
    public boolean coolingDown(UUID a, UUID b, long now) {
        Long until = cooldownUntil.get(pair(a, b));
        return until != null && now < until;
    }

    /** How many requests {@code from} sent in the last minute. */
    public int sentLastMinute(UUID from, long now) {
        Deque<Long> times = sent.get(from);
        if (times == null) {
            return 0;
        }
        trim(times, now);
        return times.size();
    }

    /**
     * {@code to} says yes to the request from {@code from} (any request waiting for them when null). The request is
     * removed as it is handed over, so a second accept gets null.
     */
    public Request accept(UUID to, UUID from, long now) {
        Request r = incoming(to, now);
        if (r == null || (from != null && !r.from().equals(from))) {
            return null;
        }
        byTarget.remove(to);
        return r;
    }

    /**
     * {@code to} says yes to the request from {@code from} (any when null) while a window can't open yet: the request
     * stays, marked {@link Request#yes()}, until {@link #accept} hands it over or it runs out. Null when there is none.
     */
    public Request sayYes(UUID to, UUID from, long now) {
        Request r = incoming(to, now);
        if (r == null || (from != null && !r.from().equals(from))) {
            return null;
        }
        if (!r.yes()) {
            r = new Request(r.id(), r.from(), r.fromName(), r.to(), r.toName(), r.at(), r.expiresAt(), true);
            byTarget.put(to, r);
        }
        return r;
    }

    /** {@code to} says no: the request goes and the pair cools down. */
    public Request deny(UUID to, UUID from, long now) {
        Request r = incoming(to, now);
        if (r == null || (from != null && !r.from().equals(from))) {
            return null;
        }
        byTarget.remove(to);
        cool(r, now);
        return r;
    }

    /** The asker calls their own request off: it goes and the pair cools down. */
    public Request withdraw(UUID from, long now) {
        Request r = outgoing(from, now);
        if (r == null) {
            return null;
        }
        byTarget.remove(r.to());
        cool(r, now);
        return r;
    }

    /** Every request from or to {@code player} goes (a quit, a world change, a death, a game): no cooldown. */
    public List<Request> dropAll(UUID player) {
        List<Request> out = new ArrayList<>();
        for (Request r : new ArrayList<>(byTarget.values())) {
            if (r.from().equals(player) || r.to().equals(player)) {
                byTarget.remove(r.to());
                out.add(r);
            }
        }
        return out;
    }

    /** Every request whose time is up goes, and each pair cools down; the old cooldowns and counts are forgotten. */
    public List<Request> expire(long now) {
        List<Request> out = new ArrayList<>();
        for (Request r : new ArrayList<>(byTarget.values())) {
            if (r.expired(now)) {
                byTarget.remove(r.to());
                cool(r, now);
                out.add(r);
            }
        }
        cooldownUntil.values().removeIf(until -> now >= until);
        sent.values().forEach(times -> trim(times, now));
        sent.values().removeIf(Deque::isEmpty);
        return out;
    }

    /** Every request still waiting. */
    public List<Request> all() {
        return new ArrayList<>(byTarget.values());
    }

    /** Forget everything (the plugin is stopping). */
    public void clear() {
        byTarget.clear();
        cooldownUntil.clear();
        sent.clear();
    }

    private void cool(Request r, long now) {
        cooldownUntil.put(pair(r.from(), r.to()), now + PAIR_COOLDOWN_MS);
    }

    private static void trim(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE_MS) {
            times.removeFirst();
        }
    }

    private static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}
