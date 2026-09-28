package com.dierks.homecraft.games;

import com.dierks.homecraft.games.cabinet.connect.ConnectFour;
import com.dierks.homecraft.games.cabinet.match.MiniMatch;
import com.dierks.homecraft.games.cabinet.merge.OreMerge;
import com.dierks.homecraft.games.cabinet.simon.SimonSays;
import com.dierks.homecraft.games.cabinet.snake.Snake;
import com.dierks.homecraft.games.cabinet.sweeper.CreeperSweeper;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToe;
import com.dierks.homecraft.games.cabinet.whack.WhackAZombie;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.chance.hilo.HigherLower;
import com.dierks.homecraft.games.chance.slots.OreSlots;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.wheel.Wheel;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.TimeTrials;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one list of games, in display order (spec §3.3, R3.1).
 *
 * <p>Written once, with every game, so the list never changes as the games are built: a game not
 * built yet is a real class whose settings parse and whose switch reads false ("Coming soon"),
 * and its owner replaces the body of their own class. The config parser, the config test, the
 * framework and the website feed all iterate {@link #SPECS}.
 */
public final class GameCatalog {

    /** Every game, in display order. */
    public static final List<GameSpec<?>> SPECS = List.of(OreSlots.SPEC, TwentyOne.SPEC, Wheel.SPEC,
            HigherLower.SPEC, CoinFlip.SPEC, CreeperSweeper.SPEC, OreMerge.SPEC, Snake.SPEC, MiniMatch.SPEC,
            SimonSays.SPEC, WhackAZombie.SPEC, ConnectFour.SPEC, TicTacToe.SPEC, TimeTrials.SPEC, MiniGolf.SPEC);

    /**
     * Words {@code /hcm play} keeps for itself, so no course may take them as its id
     * ({@code /hcm play accept}, {@code /hcm play break}, ...).
     */
    public static final Set<String> RESERVED = Set.of("accept", "deny", "break", "leave", "invites");

    private GameCatalog() {
    }

    /** The spec with this id, or {@code null}. */
    public static GameSpec<?> spec(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (GameSpec<?> s : SPECS) {
            if (s.id().equals(k)) {
                return s;
            }
        }
        return null;
    }

    /** Whether {@code id} is taken: a game id or a reserved word (a course may not use it). */
    public static boolean taken(String id) {
        return id != null && (spec(id) != null || RESERVED.contains(id.trim().toLowerCase(Locale.ROOT)));
    }
}
