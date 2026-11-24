package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Arcade Room's games (0.41.0): {@code games.clubhouse.arcade_room.games} as it may be played. Only an arcade
 * cabinet ({@link GameKind#CABINET}) is ever a cabinet in the room: a game of chance is refused whatever the list says
 * (its WARN says so), and so is a course, golf, or an id that isn't a game. Pure: the games' kinds come in.
 */
public final class KidSet {

    /**
     * Every arcade cabinet game, in the order the Arcade Room gives the rest of its places to the ones not listed (plan
     * 11; the owner: "Do we have all the games that the kids can play in the arcade as it looks very empty"): the
     * shipped four first, then the four more.
     */
    public static final List<String> CABINET_GAMES = List.of("whack_a_zombie", "simon_says", "mini_match",
            "tic_tac_toe", "snake", "connect_four", "creeper_sweeper", "ore_merge");

    /**
     * 0.57.0: the kid games played on a screen of their own that are not arcade cabinet games by kind (they are played
     * together, so their kind is the kid race's), and may still have a cabinet in the Arcade Room: listed in
     * {@code arcade_room.games}, or put where the owner stands ({@code /hcm games clubhouse cabinet kid_bingo}). Its tap
     * starts them as a cabinet's does ({@link #cabinet}). None of them is in {@link #CABINET_GAMES}: they take no spare
     * place of the generated room by themselves.
     */
    public static final Set<String> KID_SCREENS = Set.of("kid_bingo");

    private KidSet() {
    }

    /** Whether a game of {@code kind} called {@code id} may stand in the Arcade Room as a cabinet (never chance). */
    public static boolean cabinet(String id, GameKind kind) {
        return kind == GameKind.CABINET || (kind != null && !kind.chance() && id != null
                && KID_SCREENS.contains(id.toLowerCase(Locale.ROOT)));
    }

    /**
     * The generated Arcade Room's places, in order (pure; plan 11): the listed games first, as they are listed; then
     * The Floor Is Lava's cabinet ({@code lava}, {@code null} for none) in the next place, where 0.45.0 put it; then each
     * game of {@code more} not listed yet, in order, while there are places. A game never has two cabinets: a place
     * nothing is left for stays empty (0.42.0 took the list round again, and Simon Says stood there twice).
     *
     * @param listed the room's list, already checked ({@link #resolve})
     * @param more   the games that may fill the rest (each an open arcade cabinet game)
     * @param places how many places the room has
     */
    public static List<String> fill(List<String> listed, List<String> more, String lava, int places) {
        List<String> out = new ArrayList<>();
        for (String g : listed == null ? List.<String>of() : listed) {
            if (out.size() < places && g != null && !out.contains(g)) {
                out.add(g);
            }
        }
        if (lava != null && out.size() < places && !out.contains(lava)) {
            out.add(lava);
        }
        for (String g : more == null ? List.<String>of() : more) {
            if (out.size() < places && g != null && !out.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * Why {@code id} can't be a cabinet in the Arcade Room, or {@code null} when it can.
     *
     * @param kindOf a game's kind by id, {@code null} for no such game
     */
    public static String refusal(String id, Function<String, GameKind> kindOf) {
        if (id == null || id.isBlank()) {
            return "no game named";
        }
        GameKind kind = kindOf.apply(id.trim().toLowerCase(Locale.ROOT));
        if (kind == null) {
            return "there is no game called " + id;
        }
        if (kind.chance()) {
            return id + " is a game of chance, and those never go in the Arcade Room";
        }
        if (kind != GameKind.CABINET && !KID_SCREENS.contains(id.trim().toLowerCase(Locale.ROOT))) {
            return id + " isn't an arcade cabinet game";
        }
        return null;
    }

    /**
     * The cabinets, in order: each listed id that may be one, at most {@code max}; every one refused is told to
     * {@code warn} once.
     */
    public static List<String> resolve(List<String> ids, Function<String, GameKind> kindOf, int max,
                                       Consumer<String> warn) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            String why = refusal(id, kindOf);
            if (why != null) {
                if (warn != null) {
                    warn.accept("games.clubhouse.arcade_room.games: " + why + " - left out");
                }
                continue;
            }
            if (!out.contains(id) && out.size() < max) {
                out.add(id);
            }
        }
        return out;
    }
}
