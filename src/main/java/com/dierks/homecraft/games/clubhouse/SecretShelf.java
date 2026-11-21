package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.storage.GamesDao;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What stands in the secret trophy room for one visitor (0.43.0; the owner: "She just is never going to beat me in
 * these games so it would be amazing if she could have her own little trophy room ... that shows all her high scores
 * and such"). Every trophy is the visitor's own, read from what the games already keep ({@code game_scores}: each
 * board's best and its run count): no new scoring, no tokens.
 *
 * <ul>
 *   <li><b>Firsts</b>, easy to earn: a first arcade game, a first race, a first round of golf.</li>
 *   <li><b>Keep going</b>: {@value #PLAYED_1}, {@value #PLAYED_2}, {@value #PLAYED_3}, {@value #PLAYED_4} and
 *       {@value #PLAYED_5} games played (every run on every board, the weekly and daily copies left out).</li>
 *   <li><b>Bests</b>: one per board the visitor has a best on (a cabinet's Classic, a course, a golf course, Falling
 *       Floors...); a cabinet's cup is the medal its best reached (gold, silver, bronze), any other a silver cup.</li>
 * </ul>
 * Big cups are the grand ones. Pure: the rows, the games' kinds, medals and names in; the trophies out.
 */
final class SecretShelf {

    static final int PLAYED_1 = 5;
    static final int PLAYED_2 = 10;
    static final int PLAYED_3 = 25;
    static final int PLAYED_4 = 50;
    static final int PLAYED_5 = 100;

    /**
     * One trophy.
     *
     * @param key   stable ("best:snake:classic", "first:golf", "played:25"): what "new" is told by
     * @param metal 1 gold, 2 silver, 3 bronze
     * @param game  the game it is for (its Games-screen icon goes on the plaque), or {@code null}
     * @param label the adults' plaque: what, and the number
     * @param big   a grand one (drawn bigger)
     */
    record Trophy(String key, int metal, String game, String label, boolean big) {
    }

    /** The games' facts the shelf needs. */
    interface Games {
        /** A game's kind, or {@code null} for a game no longer there. */
        GameKind kind(String game);

        /** A game's name as players see it. */
        String name(String game);

        /** The medal a best on a board reached (3 gold, 2 silver, 1 bronze, 0 none): a cabinet's milestones. */
        int medal(String game, String board, long score);
    }

    private SecretShelf() {
    }

    /** The visitor's trophies, in the order they stand: firsts, games played, then each best. */
    static List<Trophy> trophies(List<GamesDao.Mine> rows, Games games) {
        List<Trophy> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        boolean cabinet = false;
        boolean race = false;
        boolean golf = false;
        long played = 0;
        List<Trophy> bests = new ArrayList<>();
        for (GamesDao.Mine r : rows) {
            if (r.board().startsWith("week:")) {
                continue; // a course's weekly copy of a run already on its own board
            }
            GameKind kind = games.kind(r.game());
            if (kind == null || kind.chance()) {
                continue; // a game no longer there; never a game of chance (not even toward games played)
            }
            if (!scoreBoard(kind, r.board())) {
                continue; // 0.45.0: a points tally (the Clubhouse's family week, a season's wins...), no board of bests
            }
            played += Math.max(0, r.runs());
            cabinet |= kind == GameKind.CABINET;
            race |= kind == GameKind.TRIAL && r.board().startsWith("course:");
            golf |= kind == GameKind.GOLF || r.board().startsWith("golf:");
            if (r.board().startsWith("daily:")) {
                continue; // today's board: a best on the Classic one is the trophy
            }
            int medal = kind == GameKind.CABINET ? games.medal(r.game(), r.board(), r.score()) : 0;
            int metal = kind == GameKind.CABINET ? (medal >= 3 ? 1 : medal == 2 ? 2 : 3) : 2;
            bests.add(new Trophy("best:" + r.game() + ":" + r.board(), metal, r.game(), "&6" + games.name(r.game())
                    + "\n&f" + score(r) + "\n&7your best", medal >= 3));
        }
        if (cabinet) {
            out.add(new Trophy("first:cabinet", 2, null, "&6Your first arcade game", false));
        }
        if (race) {
            out.add(new Trophy("first:race", 2, null, "&6Your first race", false));
        }
        if (golf) {
            out.add(new Trophy("first:golf", 2, null, "&6Your first round of golf", false));
        }
        int[] steps = {PLAYED_1, PLAYED_2, PLAYED_3, PLAYED_4, PLAYED_5};
        int[] metals = {3, 3, 2, 1, 1};
        for (int i = 0; i < steps.length; i++) {
            if (played >= steps[i]) {
                out.add(new Trophy("played:" + steps[i], metals[i], null, "&6" + steps[i] + " games played",
                        steps[i] >= PLAYED_5));
            }
        }
        out.addAll(bests);
        return out;
    }

    /**
     * Whether {@code board} is a board of plays and bests (0.45.0): a cabinet's Classic or today's, a course, a golf
     * course. {@code game_scores} also keeps points tallies in the same shape (the Clubhouse's {@code club:<week>},
     * {@code clubday:<day>} and {@code clubwins:<week>}, Falling Floors' and Race Night's weekly and season rows, Fresh
     * Courses' stars): before, each of those became a silver "best" cup, a new one every day, until they filled the
     * ledges and no real best was shown, and their rows counted as games played. The Floor Is Lava's three boards
     * ({@code lava:little}, {@code lava:group}, {@code lava:challenge}) are bests, one per mode, and each is a trophy.
     */
    static boolean scoreBoard(GameKind kind, String board) {
        if (board == null) {
            return false;
        }
        return switch (kind) {
            case CABINET -> !board.contains(":") || board.startsWith("daily:");
            case TRIAL -> board.startsWith("course:") || board.startsWith(LAVA);
            case GOLF -> board.startsWith("golf:");
            default -> false;
        };
    }

    /** The Floor Is Lava's boards ({@code LavaMode.BOARD_PREFIX}): a best per mode, never a tally. */
    static final String LAVA = com.dierks.homecraft.games.lava.rules.LavaMode.BOARD_PREFIX;

    /** A best as players read it: a course's time, golf's strokes, The Floor Is Lava's stars or rounds, else the number. */
    static String score(GamesDao.Mine r) {
        if (r.board().startsWith(LAVA)) {
            com.dierks.homecraft.games.lava.rules.LavaMode mode =
                    com.dierks.homecraft.games.lava.rules.LavaMode.of(r.board().substring(LAVA.length()));
            String what = mode == com.dierks.homecraft.games.lava.rules.LavaMode.CHALLENGE
                    ? r.score() + (r.score() == 1 ? " round" : " rounds")
                    : r.score() + (r.score() == 1 ? " star" : " stars");
            return mode == null ? String.valueOf(r.score()) : what + " (" + mode.label() + ")";
        }
        if (r.board().startsWith("course:")) {
            return TrialText.time(r.score());
        }
        if (r.board().startsWith("golf:")) {
            return r.score() + " strokes";
        }
        return String.valueOf(r.score());
    }

    /** The trophies the visitor hasn't seen yet (each bursts once, the first time they come in after earning it). */
    static List<Trophy> fresh(List<Trophy> trophies, Set<String> seen) {
        List<Trophy> out = new ArrayList<>();
        for (Trophy t : trophies) {
            if (seen == null || !seen.contains(t.key())) {
                out.add(t);
            }
        }
        return out;
    }

    /** The seen keys as stored ("a,b,c"), and back. */
    static String seenText(List<Trophy> trophies) {
        return seenText(Set.of(), trophies);
    }

    /**
     * What was seen before and the trophies shown now (0.45.0: only the ones that were drawn: a trophy with no place
     * on the shelf yet keeps its burst for the day it gets one).
     */
    static String seenText(Set<String> before, List<Trophy> shown) {
        Set<String> keys = new LinkedHashSet<>(before == null ? Set.of() : before);
        for (Trophy t : shown) {
            keys.add(t.key());
        }
        return String.join(",", keys);
    }

    static Set<String> seen(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null && !text.isBlank()) {
            for (String k : text.split(",")) {
                if (!k.isBlank()) {
                    out.add(k.trim());
                }
            }
        }
        return out;
    }

    /** Whether a player may go in: only with the permission; anyone else gets nothing at all (no message). */
    static boolean mayEnter(boolean hasPermission) {
        return hasPermission;
    }

    /** The games' facts from functions (tests). */
    static Games games(Function<String, GameKind> kinds, Function<String, String> names) {
        return new Games() {
            @Override
            public GameKind kind(String game) {
                return kinds.apply(game);
            }

            @Override
            public String name(String game) {
                return names.apply(game);
            }

            @Override
            public int medal(String game, String board, long score) {
                return 0;
            }
        };
    }
}
