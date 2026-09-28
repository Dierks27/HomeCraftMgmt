package com.dierks.homecraft.games.chance.twentyone;

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
 * Twenty-One (spec §5.4, R1.5).
 *
 * <p>Beat the Arcade's hand without going over 21: Hit, Stand or Double, the Arcade draws to 17.
 * The win multiplier is solved per stake so that even the best possible play gives back what
 * {@code rtp} says, no more.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link TwentyOneSettings#parse} without touching the framework.
 */
public final class TwentyOne implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<TwentyOneSettings> SPEC = new GameSpec<>("twenty_one", GameKind.CHANCE,
            TwentyOneSettings.KEYS, TwentyOneSettings.defaults(), TwentyOneSettings::parse,
            TwentyOne::new, null);

    private final GameContext ctx;

    public TwentyOne(GameContext ctx) {
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
        return "Twenty-One";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_TWENTY_ONE;
    }

    @Override
    public List<String> aliases() {
        return List.of("blackjack");
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Beat the Arcade's hand without going over 21.",
                "Hit for a card, Stand to stop.",
                "Double: put in the same again for one more card.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.PAPER, "&a" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&a" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    TwentyOneSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
