package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Arcade Room's games (0.41.0): {@code games.clubhouse.arcade_room.games} as it may be played. Only an arcade
 * cabinet ({@link GameKind#CABINET}) is ever a cabinet in the room: a game of chance is refused whatever the list says
 * (its WARN says so), and so is a course, golf, or an id that isn't a game. Pure: the games' kinds come in.
 */
public final class KidSet {

    private KidSet() {
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
        if (kind != GameKind.CABINET) {
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
