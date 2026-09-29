package com.dierks.homecraft.games.event;

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
 * Race Night (EVENTS-DROPPER-SPEC §A, EVENTS-RECONCILED decisions 1-2): boat races for everyone at
 * once, at set times or whenever an admin starts one. Three short races on one track, points for
 * everyone in every race, and small token prizes from the server. Entry is free: nobody can lose
 * tokens.
 *
 * <p>Id {@code race_night}, alias {@code race}; kind {@link GameKind#TRIAL} (a free skill game, no
 * new kind); never the featured game; ledger source {@code GAMES_RACE_NIGHT}; its tile goes on the
 * {@link Game.Tab#TOGETHER Together} tab.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config says
 * and its screen says "Coming soon!". Its settings already parse and ship ({@code games.race_night},
 * shipped off), so WP-R2 replaces this class's body without touching the framework.
 */
public final class RaceNight implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<RaceNightSettings> SPEC = new GameSpec<>("race_night", GameKind.TRIAL,
            RaceNightSettings.KEYS, RaceNightSettings.defaults(), RaceNightSettings::parse, RaceNight::new, null);

    private final GameContext ctx;

    public RaceNight(GameContext ctx) {
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
        return "Race Night";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_RACE_NIGHT;
    }

    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && settings().enabled();
    }

    @Override
    public List<String> aliases() {
        return List.of("race");
    }

    /** A night at set times is never "today's pick". */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return List.of("Boat races for everyone at once.",
                "Three short races: every race gives points.",
                "Free to enter. The top racers win a few tokens.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.OAK_BOAT, "&6" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of(new GameTile(Tab.TOGETHER, tile(viewer), id(), 0));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&6" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    RaceNightSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
