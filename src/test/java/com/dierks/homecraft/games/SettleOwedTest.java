package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * fx2-C #7: what a game owes a player (a prize won that couldn't be paid then) is settled by the
 * framework a moment after every join and on every one-minute sweep, whether the game is open or
 * switched off: a switch closes new play, never what was already won. A game that failed is left
 * alone, and one that throws here fails as anywhere else.
 */
class SettleOwedTest {

    /** A game that owes: it records each settle it is asked for. */
    static final class Owing implements Game {
        final List<String> calls = new ArrayList<>();
        boolean open;
        boolean throwing;

        @Override
        public String id() {
            return "test_owing";
        }

        @Override
        public GameKind kind() {
            return GameKind.TRIAL;
        }

        @Override
        public String name() {
            return "Owing";
        }

        @Override
        public TokenService.Source source() {
            return TokenService.Source.GAMES_PARKOUR;
        }

        @Override
        public boolean configEnabled() {
            return open;
        }

        @Override
        public List<String> rules() {
            return List.of();
        }

        @Override
        public ItemStack tile(Player viewer) {
            return null;
        }

        @Override
        public void open(Player player, Runnable back) {
        }

        @Override
        public void settleOwed(Player player, boolean joined) {
            if (throwing) {
                throw new IllegalStateException("boom");
            }
            calls.add(player.getName() + (joined ? " joined" : " swept"));
        }
    }

    private Host host;
    private Owing owing;
    private GamesService games;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 10, 3, 12, 0));
        owing = new Owing();
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6));
        games = GamesKit.service(host, List.of(GamesKit.spec("test_owing", GameKind.TRIAL,
                new GamesKit.SkillSettings(true, 10), ctx -> owing, null)));
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void aSwitchedOffGameStillSettlesWhatItOwesAtEveryJoinAndEverySweep() {
        assertFalse(games.enabled(owing), "the game is switched off");
        games.onJoin(alex.player);
        host.runTasks();
        assertEquals(List.of("Alex joined"), owing.calls, "a moment after the join, though the game is off");
        games.sweep();
        assertEquals(List.of("Alex joined", "Alex swept"), owing.calls, "and on the one-minute sweep");
        owing.open = true;
        games.sweep();
        assertEquals(3, owing.calls.size(), "open, the same");
    }

    @Test
    void aGameThatThrowsHereFailsAndIsLeftAloneAfter() {
        owing.throwing = true;
        games.sweep();
        assertEquals(true, games.failed(owing), "a game that throws fails, the framework carries on");
        owing.throwing = false;
        games.sweep();
        assertEquals(List.of(), owing.calls, "a failed game isn't asked again until /hcm reload");
    }
}
