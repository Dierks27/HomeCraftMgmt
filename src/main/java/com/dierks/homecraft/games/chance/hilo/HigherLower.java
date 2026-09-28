package com.dierks.homecraft.games.chance.hilo;

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
 * Higher or Lower (spec §5.6, R1.6).
 *
 * <p>Guess whether the next card is higher or lower; each right guess grows the pot, cash out any
 * time. The per-guess return is solved per stake so the best play gives back what {@code rtp} says,
 * and every extra guess risks what you have.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link HigherLowerSettings#parse} without touching the framework.
 */
public final class HigherLower implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<HigherLowerSettings> SPEC = new GameSpec<>("higher_lower", GameKind.CHANCE,
            HigherLowerSettings.KEYS, HigherLowerSettings.defaults(), HigherLowerSettings::parse,
            HigherLower::new, null);

    private final GameContext ctx;

    public HigherLower(GameContext ctx) {
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
        return "Higher or Lower";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_HILO;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Guess if the next card is higher or lower.",
                "The same card again loses.",
                "Cash out any time after a right guess.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.MAP, "&a" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&a" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    HigherLowerSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
