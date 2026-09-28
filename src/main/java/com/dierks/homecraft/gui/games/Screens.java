package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesScreens;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Scores;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The shared games screens, as the framework and the games reach them (spec §8, R3.4): the Games
 * screen, one board's high scores, Take a break and the player picker. Installed once at enable
 * with {@code games.screens(new Screens(plugin))}; until then the service says "Coming soon!".
 *
 * <p>It also reads, for the screens, what each game publishes about itself through
 * {@link Game#feed} ({@link Published}). That is the same engine object the game plays with, so
 * "gives back about N of every 100 tokens" on the guide and the boards on the high-score list can
 * never disagree with the game or the website — and no screen needs a hook into a game's
 * internals.
 */
public final class Screens implements GamesScreens {

    private final HomeCraftManagement plugin;

    public Screens(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @Override
    public void games(Player player, Runnable back) {
        new GamesMenu(plugin, player, null, 0, back).open(player);
    }

    @Override
    public void scores(Player player, Game game, String board, boolean lowerIsBetter, Runnable back) {
        new ScoresMenu(plugin, game, player, board, lowerIsBetter, back).open(player);
    }

    @Override
    public void takeABreak(Player player, Runnable back) {
        new BreakMenu(plugin, player, back).open(player);
    }

    @Override
    public void pickPlayer(Player player, Game game, Predicate<Player> eligible, Consumer<Player> chosen,
                           Runnable back) {
        new InvitePicker(plugin, game, player, eligible, chosen, 0, back).open(player);
    }

    /**
     * What one game publishes (its {@link Game#feed} entries), read inside the game's guard. A
     * game that throws is switched off by the guard and reads as publishing nothing.
     */
    static Published published(GamesService games, Game game) {
        Published out = new Published();
        games.guard(game, () -> game.feed(out));
        return out;
    }

    /**
     * The lowest computed "gives back" of an open game of chance (a fraction, 0.8976), or
     * {@link Double#NaN} when it isn't open or publishes none. The lowest over its stakes is the
     * honest headline: no stake gives back less.
     */
    public static double giveBack(GamesService games, Game game) {
        if (games == null || game == null || game.kind() != GameKind.CHANCE || !games.enabled(game)) {
            return Double.NaN;
        }
        return published(games, game).lowestGiveBack();
    }

    /**
     * A {@link FeedWriter} that only listens: it keeps each game-of-chance give-back and each
     * published board, so a screen can show them. Pure (no Bukkit), so it is tested directly.
     */
    static final class Published implements FeedWriter {

        /** One board a game publishes, with how to read its scores. */
        record Board(String board, String name, String unit, boolean lowerIsBetter) {
        }

        private final List<Double> giveBacks = new ArrayList<>();
        private final List<Board> boards = new ArrayList<>();

        @Override
        public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                           Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            if (rtpByStake == null) {
                return;
            }
            for (Double v : rtpByStake.values()) {
                if (v != null && !v.isNaN() && v > 0) {
                    giveBacks.add(v);
                }
            }
        }

        @Override
        public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                            String holder) {
            if (board != null && !board.isBlank()) {
                boards.add(new Board(board, name, unit == null ? "points" : unit, lowerIsBetter));
            }
        }

        @Override
        public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                           String holder) {
            if (id != null && !id.isBlank()) {
                boards.add(new Board(Scores.course(id), name, "ms", true));
            }
        }

        @Override
        public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                         String holder) {
            if (id != null && !id.isBlank()) {
                boards.add(new Board(Scores.golf(id), name, "strokes", true));
            }
        }

        /** The lowest give-back published, or NaN for none. */
        double lowestGiveBack() {
            double low = Double.NaN;
            for (double v : giveBacks) {
                if (Double.isNaN(low) || v < low) {
                    low = v;
                }
            }
            return low;
        }

        /** The boards published, in the order the game wrote them. */
        List<Board> boards() {
            return List.copyOf(boards);
        }

        /** The unit the game's cabinet boards are kept in ({@code ms}, {@code flips}...), or null. */
        String cabinetUnit() {
            for (Board b : boards) {
                if (!b.board().startsWith("course:") && !b.board().startsWith("golf:")) {
                    return b.unit();
                }
            }
            return null;
        }
    }
}
