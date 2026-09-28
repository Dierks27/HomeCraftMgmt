package com.dierks.homecraft.games.cabinet.snake;

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
 * Snake (spec §10b, R1.22, R3.14).
 *
 * <p>Snake on a 7 by 5 field, steered with turn-left and turn-right buttons; it speeds up a little
 * every few apples. Bedrock players get a slower tick. The score is apples eaten.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link SnakeSettings#parse} without touching the framework.
 */
public final class Snake implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<SnakeSettings> SPEC = new GameSpec<>("snake", GameKind.CABINET,
            SnakeSettings.KEYS, SnakeSettings.defaults(), SnakeSettings::parse,
            Snake::new, null);

    private final GameContext ctx;

    public Snake(GameContext ctx) {
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
        return "Snake";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SNAKE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Steer the snake to the apples.",
                "Don't run into the walls or yourself.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.LIME_WOOL, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    SnakeSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
