package com.dierks.homecraft.games;

import com.dierks.homecraft.games.arena.FallingFloors;
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
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.event.RaceNight;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.api.Slots;
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

    /**
     * Every game, in display order. Race Night and Falling Floors (EVENTS-DROPPER-SPEC C1) come last,
     * as "Coming soon" stubs until their packages are built. The Weekly Cup (EVENTS-OWNER-DECISIONS
     * D2) follows the course games it runs on and has no tile of its own.
     */
    public static final List<GameSpec<?>> SPECS = List.of(OreSlots.SPEC, TwentyOne.SPEC, Wheel.SPEC,
            HigherLower.SPEC, CoinFlip.SPEC, CreeperSweeper.SPEC, OreMerge.SPEC, Snake.SPEC, MiniMatch.SPEC,
            SimonSays.SPEC, WhackAZombie.SPEC, ConnectFour.SPEC, TicTacToe.SPEC, TimeTrials.SPEC, MiniGolf.SPEC,
            WeeklyCup.SPEC, DailyCourses.SPEC, RaceNight.SPEC, FallingFloors.SPEC);

    /**
     * Words {@code /hcm play} keeps for itself, so no course may take them as its id
     * ({@code /hcm play accept}, {@code /hcm play break}, ... and {@code /hcm play cup off}, the
     * Weekly Cup's switch, EVENTS-OWNER-DECISIONS D2).
     */
    public static final Set<String> RESERVED = Set.of("accept", "deny", "break", "leave", "invites", "news", "cup");

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

    /**
     * Whether {@code id} is taken by a game id, a reserved word, or a Fresh Courses id (its slots,
     * {@code fresh_courses} and {@code fresh_parkour_tiers}: {@link Slots#RESERVED}), so no hand-built course can
     * take one. It can't see a game's aliases (Twenty-One's "blackjack" lives on the built game): a
     * course id is checked with {@link #taken(String, GamesService)}. {@code /hcm play} doesn't ask
     * this: a slot's own course still resolves.
     */
    public static boolean taken(String id) {
        if (id == null) {
            return false;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        return spec(k) != null || RESERVED.contains(k) || Slots.reserved(k);
    }

    /**
     * Whether {@code id} is taken, so a course may not use it: a game id, a reserved word, or
     * anything {@code games} resolves to a game — an alias like "blackjack" included.
     */
    public static boolean taken(String id, GamesService games) {
        return taken(id) || (id != null && games != null && games.game(id) != null);
    }
}
