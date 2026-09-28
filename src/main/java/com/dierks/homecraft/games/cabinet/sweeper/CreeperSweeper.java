package com.dierks.homecraft.games.cabinet.sweeper;

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
 * Creeper Sweeper (spec §10b, R1.22).
 *
 * <p>Minesweeper on a 9 by 5 board: dig every safe square, flag the creepers; the first dig is
 * never a creeper. Three difficulties, each its own board; scores are times (lower is better).
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link CreeperSweeperSettings#parse} without touching the framework.
 */
public final class CreeperSweeper implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<CreeperSweeperSettings> SPEC = new GameSpec<>("creeper_sweeper", GameKind.CABINET,
            CreeperSweeperSettings.KEYS, CreeperSweeperSettings.defaults(), CreeperSweeperSettings::parse,
            CreeperSweeper::new, null);

    private final GameContext ctx;

    public CreeperSweeper(GameContext ctx) {
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
        return "Creeper Sweeper";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SWEEPER;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Dig every safe square. Don't dig a creeper!",
                "A number says how many creepers are next to it.",
                "Flag the squares you think hide one.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.CREEPER_HEAD, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    CreeperSweeperSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
