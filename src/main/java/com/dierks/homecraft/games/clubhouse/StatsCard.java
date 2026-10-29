package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * My stats (0.38.3): a player's own numbers, on the Clubhouse's west wall where only they see them, and on the My
 * stats screen. Their personal best and place on each course they have played, their counted runs this week, their
 * best round of golf, the tokens they won from games this week, and (0.38.4) their family week points and place.
 *
 * <p><b>Tokens from games</b> are the token ledger's earnings this week from the skill games, the courses, Fresh
 * Courses' goals, the Dropper, Race Night, Falling Floors and Weekly Cup prizes ({@link #GAME_SOURCES}): never the
 * games of chance, never a Cup entry paid or given back. Tokens are only ever tokens: nothing here touches money.
 *
 * <p>Pure: the snapshot and what was read in, the card out.
 */
public final class StatsCard {

    /**
     * The ledger sources that count as "tokens won from games": every {@code GAMES_*} source but the Weekly Cup's
     * entry and its refund (a player's own tokens going in and coming back).
     */
    public static final Set<String> GAME_SOURCES = gameSources();
    /** Personal bests on the wall (the screen shows more; 0.38.4: three, the family week's line took one row). */
    static final int WALL_BESTS = 3;

    private StatsCard() {
    }

    /** A personal best: the course, the time, the place on its board (0: not known). */
    public record Best(String course, long ms, int rank) {
    }

    /**
     * One player's numbers.
     *
     * @param bests        their best on each course they played, best place first
     * @param runsThisWeek their counted runs this week
     * @param bestGolf     their best round (fewest strokes against par), or {@code null}
     * @param tokensWon    tokens won from games this week
     * @param weekPoints   0.38.4: their family week points
     * @param weekRank     their place on the family week's board (0: none yet)
     */
    public record Mine(List<Best> bests, int runsThisWeek, FameBoard.GolfRound bestGolf, int tokensWon,
                       long weekPoints, int weekRank) {

        public Mine {
            bests = List.copyOf(bests == null ? List.of() : bests);
        }

        /** Without the family week (0.38.3's shape, kept for the tests). */
        public Mine(List<Best> bests, int runsThisWeek, FameBoard.GolfRound bestGolf, int tokensWon) {
            this(bests, runsThisWeek, bestGolf, tokensWon, 0, 0);
        }
    }

    private static Set<String> gameSources() {
        Set<String> out = new LinkedHashSet<>();
        for (TokenService.Source s : TokenService.Source.values()) {
            if (s.name().startsWith("GAMES_") && s != TokenService.Source.GAMES_CUP_ENTRY
                    && s != TokenService.Source.GAMES_CUP_REFUND) {
                out.add(s.name());
            }
        }
        return Set.copyOf(out);
    }

    /**
     * {@code p}'s numbers over the courses of {@code s}: a best on each course's own board with its place; the
     * counted runs on this week's boards (a board that is all this week's); the best round on a golf course's board
     * (fewest strokes against par, a tie to the earlier); the earnings from {@link #GAME_SOURCES} since the week
     * began. {@code null} in, {@code null} out (not read yet).
     */
    public static Mine build(ClubData.Snapshot s, ClubData.Personal p) {
        if (s == null || p == null) {
            return null;
        }
        Map<String, GamesDao.PlayerBoard> trials = byBoard(p.trials());
        List<Best> bests = new ArrayList<>();
        int runs = 0;
        Set<String> counted = new LinkedHashSet<>();
        for (ClubData.CourseBoards c : s.courses()) {
            GamesDao.PlayerBoard b = trials.get(c.record());
            if (b != null) {
                bests.add(new Best(c.name(), b.score(), p.ranks().getOrDefault(c.record(), 0)));
            }
            GamesDao.PlayerBoard w = trials.get(c.week());
            if (w != null && c.weekOnly() && counted.add(c.week())) {
                runs += w.runs();
            }
        }
        bests.sort(Comparator.comparingInt((Best b) -> b.rank() <= 0 ? Integer.MAX_VALUE : b.rank())
                .thenComparing(b -> b.course().toLowerCase(Locale.ROOT)));
        Map<String, GamesDao.PlayerBoard> golf = byBoard(p.golf());
        FameBoard.GolfRound bestGolf = null;
        long bestAt = 0;
        for (ClubData.GolfBoards g : s.golf()) {
            GamesDao.PlayerBoard b = golf.get(g.board());
            if (b == null) {
                continue;
            }
            int vs = (int) b.score() - g.par();
            if (bestGolf == null || vs < bestGolf.vsPar() || (vs == bestGolf.vsPar() && b.at() < bestAt)) {
                bestGolf = new FameBoard.GolfRound(g.name(), null, (int) b.score(), vs);
                bestAt = b.at();
            }
        }
        long won = 0;
        for (TokenDao.SourceTotal t : p.totals()) {
            if (GAME_SOURCES.contains(t.source())) {
                won += Math.max(0, t.earned());
            }
        }
        return new Mine(bests, runs, bestGolf, (int) Math.min(Integer.MAX_VALUE, won), p.weekPoints(), p.weekRank());
    }

    private static Map<String, GamesDao.PlayerBoard> byBoard(List<GamesDao.PlayerBoard> boards) {
        Map<String, GamesDao.PlayerBoard> out = new HashMap<>();
        for (GamesDao.PlayerBoard b : boards) {
            out.put(b.board(), b);
        }
        return out;
    }

    /**
     * The wall's card for {@code name}: the title, runs this week, tokens from games, the family week, best golf, up to
     * {@value #WALL_BESTS} personal bests, and "Only you can see this". {@code null}: counting.
     */
    public static String board(String name, Mine m) {
        StringBuilder b = new StringBuilder(ClubBoardText.statsTitle(name));
        if (m == null) {
            return b.append('\n').append(ClubBoardText.COUNTING).append('\n').append(ClubBoardText.ONLY_YOU).toString();
        }
        b.append('\n').append(ClubBoardText.runs(m.runsThisWeek()));
        b.append('\n').append(ClubBoardText.tokens(m.tokensWon()));
        b.append('\n').append(ClubBoardText.statsWeek(m.weekPoints(), m.weekRank())); // 0.38.4
        FameBoard.GolfRound g = m.bestGolf();
        b.append('\n').append(g == null ? ClubBoardText.bestGolf(null, 0, 0)
                : ClubBoardText.bestGolf(g.course(), g.strokes(), g.vsPar()));
        if (m.bests().isEmpty()) {
            b.append('\n').append(ClubBoardText.NO_BESTS);
        }
        for (int i = 0; i < Math.min(WALL_BESTS, m.bests().size()); i++) {
            Best best = m.bests().get(i);
            b.append('\n').append(ClubBoardText.best(best.course(), best.ms(), best.rank()));
        }
        return b.append('\n').append(ClubBoardText.ONLY_YOU).toString();
    }
}
