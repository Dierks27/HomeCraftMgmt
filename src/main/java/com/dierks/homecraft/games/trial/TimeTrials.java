package com.dierks.homecraft.games.trial;

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
 * Time Trials (spec §11, R1.22, R2.15).
 *
 * <p>Parkour, elytra and boat courses in the Games world, built with admin commands and stored in
 * the database: checkpoints in order, fair-play checks, personal bests, a weekly best and a course
 * of the week. Each course is its own tile and its own {@code /hcm play} id, and pays under its
 * kind's ledger source.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link TimeTrialsSettings#parse} without touching the framework.
 */
public final class TimeTrials implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<TimeTrialsSettings> SPEC = new GameSpec<>("trials", GameKind.TRIAL,
            TimeTrialsSettings.KEYS, TimeTrialsSettings.defaults(), TimeTrialsSettings::parse,
            TimeTrials::new, null);

    private final GameContext ctx;

    public TimeTrials(GameContext ctx) {
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
        return "Time Trials";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_PARKOUR;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Race from the start to the finish.",
                "Reach every checkpoint in order.",
                "Beat your best time!");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.FEATHER, "&e" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&e" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    TimeTrialsSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
