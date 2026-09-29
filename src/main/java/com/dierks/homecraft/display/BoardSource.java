package com.dierks.homecraft.display;

import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The running games as {@link BoardDisplay} asks about them, and a board's rows with the players'
 * names: the leaderboard displays' one reach into the games module. Every read of a game runs in
 * that game's guard, so a display can never break a game (or be broken by one: it shows nothing).
 */
final class BoardSource implements BoardDisplay.Lookup {

    private final GamesService games;

    BoardSource(GamesService games) {
        this.games = games;
    }

    @Override
    public GenTag liveTag(String slotId) {
        GenService e = DailyLookup.engine(games);
        return e == null ? null : e.liveTag(slotId);
    }

    @Override
    public int cadence() {
        return DailyLookup.edition(games).cadenceDays();
    }

    /** A skill cabinet's board as it publishes it on the website (its {@code Game.feed}). */
    @Override
    public BoardDisplay.Cabinet cabinet(String id) {
        Game g = games.game(id);
        if (g == null || g.kind() != GameKind.CABINET || !g.id().equalsIgnoreCase(id)) {
            return null;
        }
        BoardDisplay.Cabinet[] seen = new BoardDisplay.Cabinet[1];
        FeedWriter capture = new FeedWriter() {
            @Override
            public void chance(String cid, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            }

            @Override
            public void cabinet(String cid, String name, String board, String unit, boolean lowerIsBetter, Long best,
                                String holder) {
                if (seen[0] == null && board != null && !board.isBlank()) {
                    seen[0] = new BoardDisplay.Cabinet(name, board, unit, lowerIsBetter);
                }
            }

            @Override
            public void course(String cid, String name, String kind, String tier, Long recordMs, Long recordAt,
                               String holder) {
            }

            @Override
            public void golf(String cid, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                             String holder) {
            }
        };
        games.guard(g, () -> g.feed(capture));
        return seen[0] != null ? seen[0] : new BoardDisplay.Cabinet(g.name(), "classic", "points", false);
    }

    @Override
    public boolean chance(String id) {
        Game g = games.game(id);
        return g != null && g.kind().chance();
    }

    @Override
    public String trialCourse(String id) {
        Game g = games.game(Slots.GAME_TRIALS);
        if (g instanceof TimeTrials t) {
            Course c = games.guard(t, () -> t.course(id), null);
            return c == null || c.generated() ? null : c.name();
        }
        return null;
    }

    @Override
    public String golfCourse(String id) {
        Game g = games.game(Slots.GAME_GOLF);
        if (g instanceof MiniGolf m) {
            GolfCourse c = games.guard(m, () -> m.course(id), null);
            return c == null || c.generated() ? null : c.name();
        }
        return null;
    }

    /** The board's best rows with their players' names (names are fine in game), ranked, ties sharing one. */
    List<BoardDisplay.Row> rows(BoardDisplay.Resolved r, int limit) {
        if (r == null || r.board() == null) {
            return List.of();
        }
        List<GamesDao.ScoreRow> top = games.scores().top(r.game(), r.board(), r.lower(), limit);
        List<String> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (GamesDao.ScoreRow row : top) {
            String name;
            try {
                name = Bukkit.getOfflinePlayer(row.player()).getName();
            } catch (RuntimeException e) {
                name = null;
            }
            names.add(name);
            values.add(row.score());
        }
        return BoardDisplay.ranked(names, values);
    }

    /**
     * Every id a leaderboard can show, for tab completion: the skill cabinets, the hand-built courses
     * and golf courses, the Fresh Courses slots and the Classics.
     */
    List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Game g : games.games()) {
            if (g.kind().chance()) {
                continue;
            }
            if (g.kind() == GameKind.CABINET) {
                out.add(g.id());
            } else if (g.kind() == GameKind.TRIAL || g.kind() == GameKind.GOLF) {
                Collection<Game.Playable> ps = games.guard(g, g::playables, List.of());
                for (Game.Playable p : ps == null ? List.<Game.Playable>of() : ps) {
                    if (!Slots.reserved(p.id()) && !out.contains(p.id())) {
                        out.add(p.id());
                    }
                }
            }
        }
        out.addAll(Slots.ids());
        out.addAll(Slots.classicIds());
        return out;
    }
}
