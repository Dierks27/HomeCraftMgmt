package com.dierks.homecraft.games.cabinet.merge;

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
 * Ore Merge (spec §10b, R1.22).
 *
 * <p>2048 with ores on a 4 by 4 grid: slide, and two the same merge into the next ore up, from
 * coal to a dragon egg. The score is the sum of every merge.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link OreMergeSettings#parse} without touching the framework.
 */
public final class OreMerge implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<OreMergeSettings> SPEC = new GameSpec<>("ore_merge", GameKind.CABINET,
            OreMergeSettings.KEYS, OreMergeSettings.defaults(), OreMergeSettings::parse,
            OreMerge::new, null);

    private final GameContext ctx;

    public OreMerge(GameContext ctx) {
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
        return "Ore Merge";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_MERGE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Slide the ores. Two the same merge into the next one.",
                "Can you make a diamond?");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.IRON_INGOT, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    OreMergeSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
