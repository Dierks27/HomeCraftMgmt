package com.dierks.homecraft.games.cabinet.simon;

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
 * Simon Says (spec §10b, R1.22).
 *
 * <p>Four coloured pads light up with a note each; repeat the pattern, one longer every round. The
 * score is the longest pattern repeated.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link SimonSaysSettings#parse} without touching the framework.
 */
public final class SimonSays implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<SimonSaysSettings> SPEC = new GameSpec<>("simon_says", GameKind.CABINET,
            SimonSaysSettings.KEYS, SimonSaysSettings.defaults(), SimonSaysSettings::parse,
            SimonSays::new, null);

    private final GameContext ctx;

    public SimonSays(GameContext ctx) {
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
        return "Simon Says";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SIMON;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Watch the pads light up, then play them back.",
                "Each round adds one more.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.NOTE_BLOCK, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    SimonSaysSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
