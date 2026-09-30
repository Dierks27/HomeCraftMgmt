package com.dierks.homecraft.games;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * A game's own admin commands, under {@code /hcm games <name> ...} (the time-trial course editor,
 * the golf course editor). The framework owns {@code /hcm games}, checks
 * {@code hcm.games.admin} before calling in, and routes by {@link #name()}; the game owns
 * everything after its name. Run inside the game's guard, so a bug here switches off that game
 * only.
 */
public interface GameAdmin {

    /** The word after {@code /hcm games} ({@code course}, {@code golf}). */
    String name();

    /** Handle {@code /hcm games <name> <args...>}; {@code args} are the words AFTER the name. */
    void handle(CommandSender sender, String[] args);

    /** Tab completions for the word being typed; {@code args} are the words after the name. */
    List<String> tab(CommandSender sender, String[] args);

    /** One {@code &}-coded usage line per subcommand, for {@code /hcm games} help. */
    List<String> help();
}
