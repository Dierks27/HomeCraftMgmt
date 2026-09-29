package com.dierks.homecraft.games.clubhouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who is in the Clubhouse, why, and for how long (CLUBHOUSE-SPEC §2-§4, §9): pure, with the clock
 * passed in, so the timeouts and the restart hold are tested without a server.
 *
 * <p><b>Why they are here</b> ({@link Kind}): a visit ({@code /hcm play clubhouse}, or a Watch button:
 * a spectator, never seated), a party racer (waiting before a party race, or back after one: "Race
 * again" seats them from here), a Race Night racer (waiting to be seated, or here after the night)
 * or a golfer back after golf together. Every one of them is in a world session (the Clubhouse's
 * own, or one handed to it), so their things are safe and come back on every way out.
 *
 * <p><b>The timeouts</b> ({@link #second}):
 * <ul>
 *   <li>anyone here longer than {@code games.clubhouse.max_minutes} with no race and no party going
 *       for them is sent home, with a friendly warning a minute before;</li>
 *   <li>anyone here when the restart hold starts is warned and sent home a minute later (someone
 *       who arrives during the hold, from a race already going, gets the same minute), so nobody is
 *       ever here across a restart.</li>
 * </ul>
 */
public final class ClubVisits {

    /** Why someone is in the Clubhouse. */
    public enum Kind {
        /** Visiting or watching (a spectator): never seated, counted or paid. */
        VISIT,
        /** A party racer: waiting for the party's race, or back after it. */
        PARTY,
        /** A Race Night racer: waiting to be seated, or here after the night. */
        NIGHT,
        /** A golfer back after golf together. */
        GOLF
    }

    /** One minute: the warning before the timeout, and the restart hold's grace. */
    public static final long MINUTE = 60_000L;

    /** What {@link #second} says to do. */
    public enum What {
        /** "You've been here a while - you go home in a minute." */
        WARN_IDLE,
        /** Home: max_minutes with nothing going. */
        HOME_IDLE,
        /** "The server restarts at ... - the Clubhouse closes in a minute." */
        WARN_HOLD,
        /** Home: the restart hold's minute is up. */
        HOME_HOLD
    }

    /** One thing to do for one visitor. */
    public record Act(UUID player, What what) {
    }

    /** A visitor. */
    public static final class Visit {
        final UUID id;
        final String name;
        Kind kind;
        /** A spectator: taps Watch; never seated for a race (the party skips them). */
        boolean spectator;
        /** Which arrival spot they were given. */
        final int spot;
        final long since;
        long idleSince;
        boolean idleWarned;
        long holdWarnedAt = -1;
        /** When their arrival in the room is checked (a teleport that never landed sends them home). */
        long checkAt;
        boolean seen;

        Visit(UUID id, String name, Kind kind, int spot, long now) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.spot = spot;
            this.since = now;
            this.idleSince = now;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        public boolean spectator() {
            return spectator;
        }

        public int spot() {
            return spot;
        }

        public long since() {
            return since;
        }

        /** Whether they have been seen in the room since they were put there. */
        public boolean seen() {
            return seen;
        }
    }

    /** How long after being put in the room someone must be seen in it (a teleport that never landed). */
    public static final long ARRIVAL_MS = 10_000L;

    private final Map<UUID, Visit> visits = new LinkedHashMap<>();
    private int nextSpot;

    /**
     * {@code player} is in the Clubhouse now (their session is ready, or was handed here): a visitor
     * of {@code kind}, with the next arrival spot. Someone already here just changes kind.
     */
    public Visit enter(UUID player, String name, Kind kind, long now) {
        Visit v = visits.get(player);
        if (v != null) {
            v.kind = kind;
            return v;
        }
        v = new Visit(player, name, kind, nextSpot++, now);
        v.checkAt = now + ARRIVAL_MS;
        visits.put(player, v);
        return v;
    }

    /** Out of the Clubhouse (any way at all). @return the visit, or {@code null} if they weren't here */
    public Visit leave(UUID player) {
        return player == null ? null : visits.remove(player);
    }

    /** The visitor, or {@code null}. */
    public Visit get(UUID player) {
        return player == null ? null : visits.get(player);
    }

    public boolean in(UUID player) {
        return player != null && visits.containsKey(player);
    }

    /** Everyone here, in arrival order. */
    public List<Visit> all() {
        return new ArrayList<>(visits.values());
    }

    public int size() {
        return visits.size();
    }

    /** Everyone out (the game stopping: their sessions end with it). */
    public void clear() {
        visits.clear();
    }

    /** They were seen in the room: the arrival check is done. */
    public void seen(UUID player) {
        Visit v = visits.get(player);
        if (v != null) {
            v.seen = true;
        }
    }

    /** The arrival check starts again (they were moved back into the room). */
    public void recheck(UUID player, long now) {
        Visit v = visits.get(player);
        if (v != null) {
            v.seen = false;
            v.checkAt = now + ARRIVAL_MS;
        }
    }

    /** Whether someone put in the room was never seen there in time (their teleport never landed). */
    public boolean lost(UUID player, long now) {
        Visit v = visits.get(player);
        return v != null && !v.seen && now >= v.checkAt;
    }

    /** Mark or unmark a visitor as a spectator. */
    public void spectator(UUID player, boolean on) {
        Visit v = visits.get(player);
        if (v != null) {
            v.spectator = on;
        }
    }

    /**
     * Once a second: the timeouts. {@code busy} says whether a race or a party is going for a visitor
     * (their idle clock stands still); {@code holding} whether the restart hold is on.
     */
    public List<Act> second(long now, int maxMinutes, Predicate<UUID> busy, boolean holding) {
        List<Act> out = new ArrayList<>();
        long max = Math.max(2, maxMinutes) * MINUTE;
        for (Visit v : visits.values()) {
            if (holding) {
                if (v.holdWarnedAt < 0) {
                    v.holdWarnedAt = now;
                    out.add(new Act(v.id, What.WARN_HOLD));
                } else if (now - v.holdWarnedAt >= MINUTE) {
                    out.add(new Act(v.id, What.HOME_HOLD));
                }
                continue;
            }
            v.holdWarnedAt = -1;
            if (busy != null && busy.test(v.id)) {
                v.idleSince = now;
                v.idleWarned = false;
                continue;
            }
            long idle = now - v.idleSince;
            if (idle >= max) {
                out.add(new Act(v.id, What.HOME_IDLE));
            } else if (idle >= max - MINUTE && !v.idleWarned) {
                v.idleWarned = true;
                out.add(new Act(v.id, What.WARN_IDLE));
            }
        }
        return out;
    }
}
