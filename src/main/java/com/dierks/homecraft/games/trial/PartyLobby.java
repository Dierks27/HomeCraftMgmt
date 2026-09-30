package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A group who play one course together, any time (EVENTS-OWNER-DECISIONS D4: "so it's not always
 * lonely"): a party race on any time-trial course ({@link Kind#RACE}, raced through race mode and a
 * {@link RaceLink}), or golf together ({@link Kind#GOLF}). Pure: no Bukkit, no clock. It only knows
 * who is in, who hosts, who is ready and whether it may start; the coordinators (WP-R1 for races,
 * WP-R2 for golf) do the inviting, the seating and the playing.
 *
 * <p><b>The rules</b> (D4):
 * <ul>
 *   <li>The host makes it and is its first member. Anyone in it may invite (through the existing
 *       Invites system, whose per-pair cooldown and invite switches stay); an accepted invite is a
 *       {@link #join}. A lobby holds up to {@link #max()}: a race up to {@code games.trials.party_max}
 *       (shipped 8, never more than {@link Kind#limit()}), golf up to 4.</li>
 *   <li>The lobby shows who's in ({@link #members()}, join order) and who's {@link #isReady ready}.
 *       Only the host can {@link #start}, and only with at least {@value #MIN_PLAYERS} in.</li>
 *   <li>Anyone can {@link #leave} at any time. A lobby whose host leaves passes to the next member
 *       who joined; the last one out closes it. During play, leaving (or a disconnect) is the
 *       coordinator's DNF; the others carry on.</li>
 *   <li>After a race or a round, {@link #finish} opens the lobby again: "race again" with the same
 *       friends. Nothing here moves tokens: party play is free, and each player's run is a normal
 *       run (no party prizes, no fees).</li>
 * </ul>
 * Main thread only, like everything in the games.
 */
public final class PartyLobby {

    /** The fewest players a party can start with: one friend. */
    public static final int MIN_PLAYERS = 2;

    /** What the party plays. */
    public enum Kind {
        /** A party race on a time-trial course: up to {@code games.trials.party_max}, never more than 12. */
        RACE(12),
        /** Golf together: everyone plays the same hole at once, up to 4. */
        GOLF(4);

        private final int limit;

        Kind(int limit) {
            this.limit = limit;
        }

        /** The most players a lobby of this kind may ever hold, whatever config says. */
        public int limit() {
            return limit;
        }
    }

    /** Where a lobby is in its life. */
    public enum State {
        /** Gathering: players join, leave and get ready. */
        OPEN,
        /** Racing or playing: nobody new joins until it is {@link #finish finished}. */
        PLAYING,
        /** Ended: empty, or closed by its coordinator. */
        CLOSED
    }

    /** Why a lobby said no, in plain words the caller colours. */
    public enum Why {
        FULL("That party is full."),
        ALREADY_IN("You're already in this party."),
        IN_ANOTHER("You're in another party - leave it first."),
        NOT_IN("You're not in that party."),
        NOT_HOST("Only the party's host can start."),
        NOT_OPEN("That party has already started."),
        CLOSED("That party has ended."),
        TOO_FEW("Invite a friend first - a party needs 2 players.");

        private final String message;

        Why(String message) {
            this.message = message;
        }

        /** What the player reads. */
        public String message() {
            return message;
        }
    }

    /**
     * What a {@link #leave} did.
     *
     * @param wasIn   whether the player was in the lobby at all
     * @param newHost who hosts now, when the host left and someone was left to take over; else {@code null}
     * @param closed  whether the lobby closed (they were the last one out)
     */
    public record Left(boolean wasIn, UUID newHost, boolean closed) {

        static final Left NOT_IN = new Left(false, null, false);
    }

    private final long id;
    private final Kind kind;
    private final String course;
    private final int max;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> ready = new LinkedHashSet<>();
    private UUID host;
    private State state = State.OPEN;
    private int starts;

    /**
     * A new lobby with its host as the first member.
     *
     * @param id     unique for this server run ({@link Parties} hands them out)
     * @param course the course it plays (a time-trial course id, or a golf course id)
     * @param max    the most players: clamped to {@value #MIN_PLAYERS} up to {@link Kind#limit()}
     */
    public PartyLobby(long id, Kind kind, String course, UUID host, int max) {
        if (kind == null || host == null || course == null || course.isBlank()) {
            throw new IllegalArgumentException("a party needs a kind, a course and a host");
        }
        this.id = id;
        this.kind = kind;
        this.course = course.trim();
        this.max = Math.max(MIN_PLAYERS, Math.min(kind.limit(), max));
        this.host = host;
        members.add(host);
    }

    public long id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** The course it plays. */
    public String course() {
        return course;
    }

    /** Who hosts now (the one who may start), or {@code null} once it closed. */
    public UUID host() {
        return host;
    }

    /** The most players it holds. */
    public int max() {
        return max;
    }

    public State state() {
        return state;
    }

    /** Everyone in it, in the order they joined (after a handoff the host may be anywhere in it). */
    public List<UUID> members() {
        return Collections.unmodifiableList(new ArrayList<>(members));
    }

    public int size() {
        return members.size();
    }

    public boolean has(UUID player) {
        return player != null && members.contains(player);
    }

    public boolean isHost(UUID player) {
        return player != null && player.equals(host);
    }

    public boolean full() {
        return members.size() >= max;
    }

    /** Whether the player marked themselves ready (in the lobby, or in a race's shared warm-up). */
    public boolean isReady(UUID player) {
        return player != null && ready.contains(player);
    }

    /** How many members are ready. */
    public int readyCount() {
        return ready.size();
    }

    /** Whether every member is ready (and there is someone in it). */
    public boolean allReady() {
        return !members.isEmpty() && ready.containsAll(members);
    }

    /** How many times it has started (1 during the first race, 2 after "race again"...). */
    public int starts() {
        return starts;
    }

    /** An accepted invite: the player joins. {@code null} when they did, else why not (nothing changes). */
    public Why join(UUID player) {
        if (player == null) {
            return Why.NOT_IN;
        }
        if (state == State.CLOSED) {
            return Why.CLOSED;
        }
        if (members.contains(player)) {
            return Why.ALREADY_IN;
        }
        if (state == State.PLAYING) {
            return Why.NOT_OPEN;
        }
        if (full()) {
            return Why.FULL;
        }
        members.add(player);
        return null;
    }

    /**
     * The player leaves (Leave, a quit, the coordinator dropping them). The host's lobby passes to
     * the next member who joined; the last one out closes it.
     */
    public Left leave(UUID player) {
        if (player == null || !members.remove(player)) {
            return Left.NOT_IN;
        }
        ready.remove(player);
        if (members.isEmpty()) {
            close();
            return new Left(true, null, true);
        }
        if (player.equals(host)) {
            host = members.iterator().next();
            return new Left(true, host, false);
        }
        return new Left(true, null, false);
    }

    /** The player marks themselves ready, or not ready. */
    public Why ready(UUID player, boolean on) {
        if (state == State.CLOSED) {
            return Why.CLOSED;
        }
        if (!has(player)) {
            return Why.NOT_IN;
        }
        if (on) {
            ready.add(player);
        } else {
            ready.remove(player);
        }
        return null;
    }

    /** Why {@code by} can't start the party now, or {@code null} when they can. Changes nothing. */
    public Why canStart(UUID by) {
        if (state == State.CLOSED) {
            return Why.CLOSED;
        }
        if (state == State.PLAYING) {
            return Why.NOT_OPEN;
        }
        if (!has(by)) {
            return Why.NOT_IN;
        }
        if (!isHost(by)) {
            return Why.NOT_HOST;
        }
        if (members.size() < MIN_PLAYERS) {
            return Why.TOO_FEW;
        }
        return null;
    }

    /**
     * The host starts it: {@link State#PLAYING}, and every "ready" is cleared (from now on it means
     * ready to leave the shared warm-up). {@code null} when it started, else why not (nothing changes).
     */
    public Why start(UUID by) {
        Why no = canStart(by);
        if (no != null) {
            return no;
        }
        state = State.PLAYING;
        starts++;
        ready.clear();
        return null;
    }

    /** The race or round is over: open again for "race again" with whoever is still in. */
    public void finish() {
        if (state == State.PLAYING) {
            state = members.isEmpty() ? State.CLOSED : State.OPEN;
            ready.clear();
        }
    }

    /**
     * Close it for good (the coordinator stopped, the course closed). Everyone is out. A lobby kept
     * in {@link Parties} is closed through {@link Parties#close}, which also forgets who was in it.
     */
    public void close() {
        state = State.CLOSED;
        members.clear();
        ready.clear();
        host = null;
    }
}
