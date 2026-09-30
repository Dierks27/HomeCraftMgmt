package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every open {@link PartyLobby} (EVENTS-OWNER-DECISIONS D4), with the one rule that spans lobbies: a
 * player is in at most one party at a time. Pure; one instance per coordinator (party races in Time
 * Trials, golf together in Mini Golf), or one shared by both, since the rule holds either way.
 *
 * <p>Nothing here touches tokens, sessions or invites. A coordinator sends the invite, and on
 * [Accept] calls {@link #join}; on Leave, a quit or the end of a session it calls {@link #leave}.
 * A lobby that closes (its last player left, or {@link #close}) is forgotten.
 */
public final class Parties {

    private final Map<Long, PartyLobby> byId = new LinkedHashMap<>();
    private final Map<UUID, Long> byPlayer = new HashMap<>();
    private long nextId = 1;

    /**
     * A new lobby hosted by {@code host}, or {@code null} when the host is already in a party
     * ({@link PartyLobby.Why#IN_ANOTHER}: they leave it first).
     */
    public PartyLobby create(PartyLobby.Kind kind, String course, UUID host, int max) {
        if (host == null || byPlayer.containsKey(host)) {
            return null;
        }
        PartyLobby lobby = new PartyLobby(nextId++, kind, course, host, max);
        byId.put(lobby.id(), lobby);
        byPlayer.put(host, lobby.id());
        return lobby;
    }

    /** The party the player is in, or {@code null}. */
    public PartyLobby of(UUID player) {
        Long id = player == null ? null : byPlayer.get(player);
        return id == null ? null : byId.get(id);
    }

    /** A party by id, or {@code null} once it closed. */
    public PartyLobby get(long id) {
        return byId.get(id);
    }

    /** An accepted invite: the player joins party {@code id}. {@code null} when they did, else why not. */
    public PartyLobby.Why join(long id, UUID player) {
        PartyLobby lobby = byId.get(id);
        if (lobby == null) {
            return PartyLobby.Why.CLOSED;
        }
        Long in = player == null ? null : byPlayer.get(player);
        if (in != null && in != id) {
            return PartyLobby.Why.IN_ANOTHER;
        }
        PartyLobby.Why no = lobby.join(player);
        if (no == null) {
            byPlayer.put(player, id);
        }
        return no;
    }

    /** The player leaves whatever party they are in (the host's passes on; the last one out closes it). */
    public PartyLobby.Left leave(UUID player) {
        PartyLobby lobby = of(player);
        if (lobby == null) {
            return PartyLobby.Left.NOT_IN;
        }
        byPlayer.remove(player);
        PartyLobby.Left left = lobby.leave(player);
        if (left.closed()) {
            byId.remove(lobby.id());
        }
        return left;
    }

    /** Close a party for good: everyone in it is out, and it is forgotten. */
    public void close(long id) {
        PartyLobby lobby = byId.remove(id);
        if (lobby == null) {
            return;
        }
        for (UUID p : lobby.members()) {
            byPlayer.remove(p);
        }
        lobby.close();
    }

    /** Close every party (the coordinator is stopping). */
    public void clear() {
        for (PartyLobby lobby : new ArrayList<>(byId.values())) {
            close(lobby.id());
        }
    }

    /** Every open party, oldest first. */
    public List<PartyLobby> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** How many parties are open. */
    public int size() {
        return byId.size();
    }
}
