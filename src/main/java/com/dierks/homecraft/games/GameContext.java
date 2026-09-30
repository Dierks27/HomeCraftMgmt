package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;

/**
 * What every game is built with (spec R3.1): the plugin and the games framework. A game reaches
 * everything it needs through {@link GamesService} (its settings, the gate, rounds, rewards,
 * scores, sessions, listeners and tasks), so games never register Bukkit listeners or tasks of
 * their own and never read another game's state.
 *
 * @param plugin the plugin (config, tokens, clock, sandbox)
 * @param games  the games framework
 */
public record GameContext(HomeCraftManagement plugin, GamesService games) {
}
