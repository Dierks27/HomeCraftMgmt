package com.dierks.homecraft.games.cabinet.match;

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
 * Mini Match (spec §10b, R1.22).
 *
 * <p>The memory game with the server's Mini faces: 8 pairs face down, flip two at a time. The
 * score is flips (lower is better).
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link MiniMatchSettings#parse} without touching the framework.
 */
public final class MiniMatch implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<MiniMatchSettings> SPEC = new GameSpec<>("mini_match", GameKind.CABINET,
            MiniMatchSettings.KEYS, MiniMatchSettings.defaults(), MiniMatchSettings::parse,
            MiniMatch::new, null);

    private final GameContext ctx;

    public MiniMatch(GameContext ctx) {
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
        return "Mini Match";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_MATCH;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Flip two cards. Find all the pairs.",
                "Fewer flips is better.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.PAINTING, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    MiniMatchSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
