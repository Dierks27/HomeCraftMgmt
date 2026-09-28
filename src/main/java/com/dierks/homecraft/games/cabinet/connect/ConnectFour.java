package com.dierks.homecraft.games.cabinet.connect;

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
 * Connect Four (spec §10b, R1.22, R3.14).
 *
 * <p>Four in a row on a 7 by 5 board, against the Arcade (easy, normal or hard) or a friend. Only
 * the day's first win against the Arcade on normal or hard pays; friend games never pay, so they
 * can't be farmed.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link ConnectFourSettings#parse} without touching the framework.
 */
public final class ConnectFour implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<ConnectFourSettings> SPEC = new GameSpec<>("connect_four", GameKind.CABINET,
            ConnectFourSettings.KEYS, ConnectFourSettings.defaults(), ConnectFourSettings::parse,
            ConnectFour::new, null);

    private final GameContext ctx;

    public ConnectFour(GameContext ctx) {
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
        return "Connect Four";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_CONNECT;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Drop your pieces. Four in a row wins.",
                "Play the Arcade or a friend.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.RED_CONCRETE, "&b" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&b" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    ConnectFourSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
