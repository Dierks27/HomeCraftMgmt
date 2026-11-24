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
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.chance.hilo.HigherLower;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.slots.DeepMine;
import com.dierks.homecraft.games.chance.slots.FarmSlots;
import com.dierks.homecraft.games.chance.slots.MobSlots;
import com.dierks.homecraft.games.chance.slots.Motherlode;
import com.dierks.homecraft.games.chance.slots.NetherSlots;
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
     * D2) follows the course games it runs on and has no tile of its own. The Clubhouse (WP-CH) is
     * last: a place, not a game to play, with no tile of its own either; the Hub (0.44.0) after it, another place.
     * The Game Parlor's three more slot machines (Nether Slots, The Motherlode, Farm Slots) follow the first
     * five games of chance, so those keep their order; its last two (Deep Mine, Mob Slots) follow those, and Mob
     * Races (the Parlor's model racetrack) follows the slot machines.
     * The Floor Is Lava comes after them: a room of its own in the Games world. The NPC players (stage 1 of "CPU
     * players") are after them: a foundation every game asks, with no tile of its own. The Arcade Room's free kid race
     * (plan 11) follows them, then the Game Parlor's Sports Board (0.55.0), and Texas Hold'em (0.57.0) is last: each
     * one's block was appended at the end of {@code games:} in its turn.
     */
    public static final List<GameSpec<?>> SPECS = List.of(OreSlots.SPEC, TwentyOne.SPEC, Wheel.SPEC,
            HigherLower.SPEC, CoinFlip.SPEC, NetherSlots.SPEC, Motherlode.SPEC, FarmSlots.SPEC,
            DeepMine.SPEC, MobSlots.SPEC, MobRaces.SPEC, CreeperSweeper.SPEC, OreMerge.SPEC, Snake.SPEC, MiniMatch.SPEC,
            SimonSays.SPEC, WhackAZombie.SPEC, ConnectFour.SPEC, TicTacToe.SPEC, TimeTrials.SPEC, MiniGolf.SPEC,
            WeeklyCup.SPEC, DailyCourses.SPEC, RaceNight.SPEC, FallingFloors.SPEC, Clubhouse.SPEC,
            com.dierks.homecraft.games.hub.Hub.SPEC, com.dierks.homecraft.games.lava.FloorIsLava.SPEC,
            com.dierks.homecraft.games.cpu.CpuPlayers.SPEC, com.dierks.homecraft.games.kidrace.KidRaces.SPEC,
            com.dierks.homecraft.games.chance.sportsbook.Sportsbook.SPEC,
            com.dierks.homecraft.games.chance.holdem.Holdem.SPEC);

    /**
     * Words {@code /hcm play} keeps for itself, so no course may take them as its id
     * ({@code /hcm play accept}, {@code /hcm play break}, ... and {@code /hcm play cup off}, the
     * Weekly Cup's switch, EVENTS-OWNER-DECISIONS D2; and the Clubhouse's {@code watch}, {@code cheer},
     * {@code cheers} and {@code rider}, WP-CH).
     */
    public static final Set<String> RESERVED = Set.of("accept", "deny", "break", "leave", "invites", "news", "cup",
            "watch", "cheer", "cheers", "rider", "npc");

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
