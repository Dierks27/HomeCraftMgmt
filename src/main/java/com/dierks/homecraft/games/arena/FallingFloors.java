package com.dierks.homecraft.games.arena;

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
 * Falling Floors (EVENTS-DROPPER-SPEC §B.3): TNT Run without any TNT. Three glass floors hang in
 * the sky, and every block you step on turns red and falls away half a second later. The last one
 * standing wins, and it works alone too: "how long can you last?".
 *
 * <p>Id {@code falling_floors}, alias {@code tnt_run}; kind {@link GameKind#TRIAL} (a free skill
 * game); it may be the featured game; ledger source {@code GAMES_FLOORS}; its tile goes on the
 * {@link Game.Tab#TOGETHER Together} tab. The round rules are already built and pure
 * ({@code games.arena.rules}).
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config says
 * and its screen says "Coming soon!". Its settings already parse and ship
 * ({@code games.falling_floors}, shipped off), so WP-F replaces this class's body without touching
 * the framework.
 */
public final class FallingFloors implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<FallingFloorsSettings> SPEC = new GameSpec<>("falling_floors", GameKind.TRIAL,
            FallingFloorsSettings.KEYS, FallingFloorsSettings.defaults(), FallingFloorsSettings::parse,
            FallingFloors::new, null);

    private final GameContext ctx;

    public FallingFloors(GameContext ctx) {
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
        return "Falling Floors";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_FLOORS;
    }

    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && settings().enabled();
    }

    @Override
    public List<String> aliases() {
        return List.of("tnt_run");
    }

    @Override
    public List<String> rules() {
        return List.of("Every block you step on falls away.",
                "Keep moving! Three floors, three chances.",
                "Last one standing wins - or play solo.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.YELLOW_STAINED_GLASS, "&e" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&e" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    FallingFloorsSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
