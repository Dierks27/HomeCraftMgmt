package com.dierks.homecraft.games.cabinet.tictactoe;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Tic-Tac-Toe (spec §10b, R1.22).
 *
 * <p>Three in a row against the Arcade (easy, or hard: perfect play, so a draw is the best result)
 * or a friend. The day's first win on easy, or draw on hard, pays.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link TicTacToeSettings#parse} without touching the framework.
 */
public final class TicTacToe implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<TicTacToeSettings> SPEC = new GameSpec<>("tic_tac_toe", GameKind.CABINET,
            TicTacToeSettings.KEYS, TicTacToeSettings.defaults(), TicTacToeSettings::parse,
            TicTacToe::new, null);

    private final GameContext ctx;

    public TicTacToe(GameContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return "Tic-Tac-Toe";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_TICTACTOE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Get three in a row.",
                "Play the Arcade or a friend.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.ITEM_FRAME, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    TicTacToeSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
