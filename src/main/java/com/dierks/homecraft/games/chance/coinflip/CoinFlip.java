package com.dierks.homecraft.games.chance.coinflip;

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
 * Coin Flip (spec §5.7, R1.8).
 *
 * <p>Two players both online put in the same tokens and one coin decides; the winner gets a share
 * of both set by {@code rtp} and the rest is gone (a token sink). Ships off: it is the one game
 * where tokens pass between players, so the owner turns it on if he wants it.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link CoinFlipSettings#parse} without touching the framework.
 */
public final class CoinFlip implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<CoinFlipSettings> SPEC = new GameSpec<>("coin_flip", GameKind.CHANCE,
            CoinFlipSettings.KEYS, CoinFlipSettings.defaults(), CoinFlipSettings::parse,
            CoinFlip::new, null);

    private final GameContext ctx;

    public CoinFlip(GameContext ctx) {
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
        return "Coin Flip";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_COIN_FLIP;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Two players put in the same tokens.",
                "One flip: each of you has a 1 in 2 chance.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.GOLD_NUGGET, "&a" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&a" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    CoinFlipSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
