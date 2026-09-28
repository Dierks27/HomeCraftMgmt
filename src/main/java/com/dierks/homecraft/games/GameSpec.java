package com.dierks.homecraft.games;

import com.dierks.homecraft.config.GamesConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Everything the framework needs to know about one game before any instance exists (spec R3.1):
 * its id and kind, the config keys it ships, its shipped settings, how to read them, how to build
 * it, and how to settle a round it left open.
 *
 * <p>Each game class exposes one as {@code public static final GameSpec<XSettings> SPEC}, and
 * {@link GameCatalog#SPECS} lists them all. The config parser, the config test, the ledger and
 * the settle-on-exit sweep all work from these specs, so a game owner adds or changes a key in
 * their own package and nothing in the framework has to move.
 *
 * @param id       the config key under {@code games:} and the {@code /hcm play} id (snake_case)
 * @param kind     what sort of game it is
 * @param keys     the leaves it ships under {@code games.<id>}, in config order; a list is one
 *                 leaf, a map's keys are leaves ({@code reels.coal})
 * @param defaults the shipped settings: the bundled config.yml block parses to exactly these
 * @param parse    reads the section (a {@link GamesConfig.Node} at {@code games.<id>}) over the
 *                 defaults, clamping with one WARN per bad key; never throws. A game of chance
 *                 runs its RTP solve here, so a closed game is closed from the moment it loads.
 * @param create   builds the game
 * @param settler  settles an OPEN round on exit, or {@code null} for a game with no multi-step
 *                 rounds
 * @param <S>      the game's settings record
 */
public record GameSpec<S>(String id, GameKind kind, List<String> keys, S defaults,
                          BiFunction<GamesConfig.Node, S, S> parse,
                          Function<GameContext, Game> create, @Nullable ExitSettler settler) {

    public GameSpec {
        if (id == null || !id.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("a game id is snake_case: " + id);
        }
        if (kind == null || defaults == null || parse == null || create == null) {
            throw new IllegalArgumentException("game " + id + ": kind, defaults, parse and create are required");
        }
        keys = List.copyOf(keys);
    }
}
