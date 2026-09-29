package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Play-together lobbies (EVENTS-OWNER-DECISIONS D4), pure: the host is the first member; invites
 * accepted join up to the lobby's size (8 for a race by default, 4 for golf, never past the kind's
 * limit); only the host starts, and only with a friend in; leaving passes the host on in join order
 * and the last one out closes it; ready marks come and go; "race again" opens it again; and a player
 * is in at most one party across the whole registry.
 */
class PartyLobbyTest {

    private final UUID sam = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID ava = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID lee = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private final UUID mia = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private final UUID max = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    private PartyLobby race() {
        return new PartyLobby(1, PartyLobby.Kind.RACE, "river_run", sam, 8);
    }

    @Test
    void theHostIsTheFirstMemberAndInvitesJoinUpToTheSize() {
        PartyLobby lobby = new PartyLobby(1, PartyLobby.Kind.GOLF, "meadow", sam, 4);
        assertEquals(List.of(sam), lobby.members(), "the host is in from the start");
        assertTrue(lobby.isHost(sam), "and hosts");
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "gathering");
        assertNull(lobby.join(ava), "an accepted invite joins");
        assertNull(lobby.join(lee), "and another");
        assertEquals(PartyLobby.Why.ALREADY_IN, lobby.join(ava), "twice is once");
        assertNull(lobby.join(mia), "golf together holds 4");
        assertTrue(lobby.full(), "and is full");
        assertEquals(PartyLobby.Why.FULL, lobby.join(max), "a fifth golfer waits for the next group");
        assertEquals(List.of(sam, ava, lee, mia), lobby.members(), "join order");
        assertEquals(PartyLobby.Why.NOT_IN, lobby.join(null), "nobody is nobody");
    }

    @Test
    void theSizeIsClampedToTheKindsLimitAndAFriend() {
        assertEquals(8, race().max(), "a party race holds games.trials.party_max, shipped 8");
        assertEquals(12, new PartyLobby(1, PartyLobby.Kind.RACE, "r", sam, 99).max(), "never past 12 racers");
        assertEquals(4, new PartyLobby(1, PartyLobby.Kind.GOLF, "g", sam, 8).max(), "golf together: at most 4");
        assertEquals(PartyLobby.MIN_PLAYERS, new PartyLobby(1, PartyLobby.Kind.RACE, "r", sam, 1).max(),
                "and room for at least one friend");
        assertThrows(IllegalArgumentException.class, () -> new PartyLobby(1, PartyLobby.Kind.RACE, " ", sam, 8),
                "a party plays a course");
        assertThrows(IllegalArgumentException.class, () -> new PartyLobby(1, PartyLobby.Kind.RACE, "r", null, 8),
                "and has a host");
    }

    @Test
    void onlyTheHostStartsAndOnlyWithAFriendIn() {
        PartyLobby lobby = race();
        assertEquals(PartyLobby.Why.TOO_FEW, lobby.canStart(sam), "alone it is a solo run, not a party");
        lobby.join(ava);
        assertEquals(PartyLobby.Why.NOT_HOST, lobby.start(ava), "anyone can invite, only the host starts");
        assertEquals(PartyLobby.Why.NOT_IN, lobby.start(lee), "an outsider can't");
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "a refused start changes nothing");
        assertNull(lobby.canStart(sam), "the host with a friend can");
        assertNull(lobby.start(sam), "and does");
        assertEquals(PartyLobby.State.PLAYING, lobby.state(), "playing");
        assertEquals(1, lobby.starts(), "the first race");
        assertEquals(PartyLobby.Why.NOT_OPEN, lobby.join(lee), "nobody joins mid-race");
        assertEquals(PartyLobby.Why.NOT_OPEN, lobby.start(sam), "and it can't start twice");
        lobby.finish();
        assertEquals(PartyLobby.State.OPEN, lobby.state(), "race again: open once more");
        assertNull(lobby.join(lee), "a friend can join between races");
        assertNull(lobby.start(sam), "and the next race starts");
        assertEquals(2, lobby.starts(), "the second race");
    }

    @Test
    void readyMarksComeAndGoAndAStartClearsThem() {
        PartyLobby lobby = race();
        lobby.join(ava);
        assertFalse(lobby.allReady(), "nobody is ready yet");
        assertNull(lobby.ready(sam, true), "the host is ready");
        assertNull(lobby.ready(ava, true), "and Ava");
        assertTrue(lobby.allReady(), "everyone is");
        assertEquals(2, lobby.readyCount(), "two ready");
        assertNull(lobby.ready(ava, false), "Ava isn't after all");
        assertFalse(lobby.isReady(ava), "so she isn't");
        assertEquals(PartyLobby.Why.NOT_IN, lobby.ready(lee, true), "an outsider can't be ready");
        lobby.ready(ava, true);
        lobby.start(sam);
        assertEquals(0, lobby.readyCount(), "a start clears them: Ready now means ready to leave the warm-up");
        assertNull(lobby.ready(sam, true), "which a racer can still say while playing");
    }

    @Test
    void leavingPassesTheHostOnInJoinOrderAndTheLastOneOutClosesIt() {
        PartyLobby lobby = race();
        lobby.join(ava);
        lobby.join(lee);
        lobby.ready(ava, true);
        PartyLobby.Left left = lobby.leave(ava);
        assertTrue(left.wasIn() && !left.closed() && left.newHost() == null, "a guest leaves: nothing else changes");
        assertFalse(lobby.isReady(ava), "and her ready mark goes with her");
        left = lobby.leave(sam);
        assertEquals(lee, left.newHost(), "the host leaves: the next who joined hosts");
        assertTrue(lobby.isHost(lee), "Lee hosts now");
        assertEquals(PartyLobby.Why.TOO_FEW, lobby.canStart(lee), "and needs a friend again");
        assertFalse(lobby.leave(sam).wasIn(), "leaving twice is nothing");
        left = lobby.leave(lee);
        assertTrue(left.closed(), "the last one out closes it");
        assertEquals(PartyLobby.State.CLOSED, lobby.state(), "closed");
        assertNull(lobby.host(), "with nobody hosting");
        assertEquals(PartyLobby.Why.CLOSED, lobby.join(ava), "and nobody can join it");
        assertEquals(PartyLobby.Why.CLOSED, lobby.start(lee), "or start it");
    }

    @Test
    void aPlayerIsInAtMostOnePartyAcrossTheRegistry() {
        Parties parties = new Parties();
        PartyLobby race = parties.create(PartyLobby.Kind.RACE, "river_run", sam, 8);
        PartyLobby golf = parties.create(PartyLobby.Kind.GOLF, "meadow", lee, 4);
        assertFalse(race.id() == golf.id(), "each party has its own id");
        assertNull(parties.create(PartyLobby.Kind.GOLF, "meadow", sam, 4), "Sam hosts a race: no second party");
        assertNull(parties.join(race.id(), ava), "Ava joins the race");
        assertEquals(PartyLobby.Why.IN_ANOTHER, parties.join(golf.id(), ava), "and can't join the golf too");
        assertEquals(PartyLobby.Why.ALREADY_IN, parties.join(race.id(), ava), "or the race twice");
        assertSame(race, parties.of(ava), "she is in the race");
        assertEquals(PartyLobby.Why.CLOSED, parties.join(999, mia), "a party that isn't there");
        assertTrue(parties.leave(ava).wasIn(), "she leaves the race");
        assertNull(parties.of(ava), "and is in no party");
        assertNull(parties.join(golf.id(), ava), "so she can join the golf");

        assertTrue(parties.leave(sam).closed(), "the race's last player leaves: it closes");
        assertNull(parties.get(race.id()), "and is forgotten");
        assertEquals(List.of(golf), parties.all(), "only the golf is left");
        parties.close(golf.id());
        assertEquals(0, parties.size(), "closed and forgotten");
        assertNull(parties.of(lee), "everyone in it is out");
        assertNull(parties.of(ava), "every one");
        assertEquals(PartyLobby.State.CLOSED, golf.state(), "and it says so");

        parties.create(PartyLobby.Kind.RACE, "a", sam, 8);
        parties.create(PartyLobby.Kind.RACE, "b", ava, 8);
        parties.clear();
        assertEquals(0, parties.size(), "the games stopping drops every party");
        assertFalse(parties.leave(sam).wasIn(), "and nobody is in one");
    }
}
