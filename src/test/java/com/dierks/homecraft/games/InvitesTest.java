package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invites to two-player games (spec §3.1.8, §5.7, R3.8).
 *
 * <p>Pinned here: an invite waits for one answer and the game hears it exactly once (accept, deny,
 * expiry or a quit); one pending invite per invitee and one out per inviter; the same two players
 * wait 30 seconds between invites; an invite runs out after its time (by its own task, or when
 * next looked at); Coin Flip invites are off until the player turns them on, friend games on; the
 * invitee reads how to answer; a player who can't be asked is simply not asked.
 */
class InvitesTest {

    private Host host;
    private TestGame flip;
    private TestGame connect;
    private GamesService games;
    private Fake alex;
    private Fake sam;
    private Fake kim;
    private final List<String> answers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        flip = new TestGame("coin_flip", GameKind.CHANCE, "Coin Flip", TokenService.Source.ARCADE_COIN_FLIP);
        connect = GamesKit.skill("connect_four", "Connect Four");
        games = GamesKit.service(host, List.of(
                GamesKit.spec(flip, new ChanceSettings(true, List.of(5), 5), null),
                GamesKit.spec(connect, new SkillSettings(true, 1), null)));
        alex = online("Alex");
        sam = online("Sam");
        kim = online("Kim");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake online(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        return f;
    }

    private Invite send(Fake from, Fake to, TestGame game) {
        return games.invites().send(from.player, to.player, game, game.name() + " with " + from.name, 60,
                (invite, yes) -> answers.add(invite.from() + ":" + yes));
    }

    @Test
    void anInviteWaitsForOneAnswerAndTheGameHearsItOnce() {
        Invite invite = send(alex, sam, connect);
        assertNotNull(invite, "friend-game invites are on by default");
        assertEquals(invite, games.invites().pending(sam.id), "it waits for Sam");
        assertTrue(sam.heard().contains("Alex invites you: Connect Four with Alex"), "Sam reads what it is");
        assertTrue(sam.heard().contains("[Accept]"), "a Java player gets something to click");
        assertTrue(games.invites().accept(sam.player), "Sam accepts");
        assertEquals(List.of(alex.id + ":true"), answers, "the game hears yes, once");
        assertNull(games.invites().pending(sam.id), "nothing waits any more");
        assertFalse(games.invites().accept(sam.player), "a second accept finds nothing");
        assertFalse(games.invites().deny(sam.player), "nor does a deny");
        assertEquals(1, answers.size(), "still one answer");
    }

    @Test
    void aDenyAnswersNoAndTellsTheInviterGently() {
        send(alex, sam, connect);
        assertTrue(games.invites().deny(sam.player), "Sam says no");
        assertEquals(List.of(alex.id + ":false"), answers, "the game hears no");
        assertTrue(alex.heard().contains("Sam can't play right now."), "Alex is told gently");
    }

    @Test
    void oneInviteWaitsPerInviteeAndOneIsOutPerInviter() {
        assertNotNull(send(alex, sam, connect), "Alex asks Sam");
        assertNull(send(kim, sam, connect), "Sam already has one waiting");
        assertNull(send(alex, kim, connect), "Alex already has one out");
        assertNotNull(send(kim, alex, connect), "Kim can still ask Alex");
    }

    @Test
    void theSamePairWaitsThirtySecondsBetweenInvites() {
        send(alex, sam, connect);
        games.invites().deny(sam.player);
        host.move(29_999);
        assertNull(send(sam, alex, connect), "the same two, either way round, wait 30 seconds");
        host.move(1);
        assertNotNull(send(sam, alex, connect), "then they may ask again");
    }

    @Test
    void anInviteRunsOutAndAnswersNo() {
        send(alex, sam, connect);
        host.runTasks();
        assertNull(games.invites().pending(sam.id), "its task lapses it");
        assertEquals(List.of(alex.id + ":false"), answers, "the game hears no");
        assertTrue(alex.heard().contains("Your invite ran out."), "the inviter is told");
        answers.clear();
        host.move(60_000);
        send(kim, sam, connect);
        host.tasks.clear(); // as if its task never ran
        host.move(60_000);
        assertNull(games.invites().pending(sam.id), "an invite past its time is gone when next looked at");
        assertEquals(List.of(kim.id + ":false"), answers, "and still answered once");
    }

    @Test
    void coinFlipInvitesAreOffUntilTurnedOnAndFriendGamesAreOn() {
        assertFalse(games.invites().accepts(sam.id, "coin_flip"), "Coin Flip invites are off by default");
        assertTrue(games.invites().accepts(sam.id, "connect_four"), "friend games are on");
        assertNull(send(alex, sam, flip), "so nobody can ask Sam to flip");
        games.invites().setAccepts(sam.id, "coin_flip", true);
        assertNotNull(send(alex, sam, flip), "once Sam turns them on, they can");
        games.invites().setAccepts(kim.id, "connect_four", false);
        assertFalse(games.invites().accepts(kim.id, "connect_four"), "a player can turn friend games off");
    }

    @Test
    void aQuitOrAWorldChangeCallsItOffBothWays() {
        send(alex, sam, connect);
        send(kim, alex, connect);
        games.invites().cancel(alex.id);
        assertNull(games.invites().pending(sam.id), "the invite Alex sent is gone");
        assertNull(games.invites().pending(alex.id), "and the one Alex had waiting");
        assertEquals(2, answers.size(), "both games heard no");
        assertTrue(sam.heard().contains("called off"), "Sam is told");
    }
}
