package com.dierks.homecraft.games.chance.slots;

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
 * Ore Slots (spec §5.3, R1.4).
 *
 * <p>Three reels of ores on one payline: three the same pays that ore's line, a Wild stands in for
 * any ore, two the same pays the small line, and Stone never pays. Stone's reel weight is the one
 * thing the engine solves, so the game gives back what {@code rtp} says, no more.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link OreSlotsSettings#parse} without touching the framework.
 */
public final class OreSlots implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<OreSlotsSettings> SPEC = new GameSpec<>("ore_slots", GameKind.CHANCE,
            OreSlotsSettings.KEYS, OreSlotsSettings.defaults(), OreSlotsSettings::parse,
            OreSlots::new, null);

    private final GameContext ctx;

    public OreSlots(GameContext ctx) {
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
        return "Ore Slots";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_SLOTS;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Three reels of ores. Match them on the line.",
                "A Wild stands in for any ore.",
                "Two the same gives a little back too.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.DIAMOND_ORE, "&a" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&a" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    OreSlotsSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
