package com.dierks.homecraft.games.chance.wheel;

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
 * The Wheel (spec §5.5, R1.7).
 *
 * <p>One spin of a ring of 24 equally likely spaces: what you see is the odds. Each space is a
 * base multiple of the tokens put in (0, 1 or more); the engine scales the prizes per stake so the
 * wheel gives back what {@code rtp} says, no more.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link WheelSettings#parse} without touching the framework.
 */
public final class Wheel implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<WheelSettings> SPEC = new GameSpec<>("wheel", GameKind.CHANCE,
            WheelSettings.KEYS, WheelSettings.defaults(), WheelSettings::parse,
            Wheel::new, null);

    private final GameContext ctx;

    public Wheel(GameContext ctx) {
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
        return "The Wheel";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_WHEEL;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Spin the wheel. Every space is just as likely.",
                "Each space shows what it gives.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.COMPASS, "&a" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&a" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    WheelSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
