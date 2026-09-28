package com.dierks.homecraft.games.cabinet.whack;

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
 * Whack-a-Zombie (spec §10b, R1.22).
 *
 * <p>A short timed round: zombies pop up, hit them for a point; hitting a villager loses one.
 * Bedrock players get longer pop windows. The score is points.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link WhackAZombieSettings#parse} without touching the framework.
 */
public final class WhackAZombie implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<WhackAZombieSettings> SPEC = new GameSpec<>("whack_a_zombie", GameKind.CABINET,
            WhackAZombieSettings.KEYS, WhackAZombieSettings.defaults(), WhackAZombieSettings::parse,
            WhackAZombie::new, null);

    private final GameContext ctx;

    public WhackAZombie(GameContext ctx) {
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
        return "Whack-a-Zombie";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_WHACK;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Hit the zombies when they pop up.",
                "Don't bonk the villagers!");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.ZOMBIE_HEAD, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    WhackAZombieSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
