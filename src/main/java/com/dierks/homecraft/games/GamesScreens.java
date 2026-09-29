package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The shared games screens, as seen from the framework and the games.
 *
 * <p>The screens themselves (the Games screen, high scores, Take a break, the player picker) live
 * in {@code gui/games} and are built separately from the games that open them. Games reach them
 * only through this interface, via {@link GamesService#screens()}, so a game never depends on a
 * particular screen class and the service still works — saying "Coming soon!" — before the
 * screens are installed with {@link GamesService#screens(GamesScreens)}.
 */
public interface GamesScreens {

    /** The Games screen: every open game, in tabs. */
    void games(Player player, Runnable back);

    /**
     * One board's high scores: the player's best and the top ten (names are shown in game; only
     * the website feed leaves them out).
     */
    void scores(Player player, Game game, String board, boolean lowerIsBetter, Runnable back);

    /** Take a break: the player's own limit and pause, and what they cover. */
    void takeABreak(Player player, Runnable back);

    /**
     * Pick another online player for a two-player game (Coin Flip, a friend game). Only players
     * {@code eligible} accepts are listed; the picker never says why someone is missing.
     */
    void pickPlayer(Player player, Game game, Predicate<Player> eligible, Consumer<Player> chosen, Runnable back);

    /**
     * The Fresh Courses screen (GEN-SPEC §5.4): one tile per course of the current set, the Classics,
     * the Star Chart and how stars work. "Coming soon!" until the screen is built.
     */
    default void today(Player player, Runnable back) {
        player.sendMessage(com.dierks.homecraft.util.Text.of("&7Coming soon!"));
    }

    /** The parkour level picker ({@code /hcm play fresh_parkour_tiers}). "Coming soon!" until it is built. */
    default void parkourTiers(Player player, Runnable back) {
        player.sendMessage(com.dierks.homecraft.util.Text.of("&7Coming soon!"));
    }

    /** What the service uses until the real screens are installed: a plain "Coming soon!". */
    GamesScreens NONE = new GamesScreens() {
        private void soon(Player player) {
            player.sendMessage(com.dierks.homecraft.util.Text.of("&7Coming soon!"));
        }

        @Override
        public void games(Player player, Runnable back) {
            soon(player);
        }

        @Override
        public void scores(Player player, Game game, String board, boolean lowerIsBetter, Runnable back) {
            soon(player);
        }

        @Override
        public void takeABreak(Player player, Runnable back) {
            soon(player);
        }

        @Override
        public void pickPlayer(Player player, Game game, Predicate<Player> eligible, Consumer<Player> chosen,
                               Runnable back) {
            soon(player);
        }
    };
}
