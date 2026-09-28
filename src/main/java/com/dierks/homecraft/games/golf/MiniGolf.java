package com.dierks.homecraft.games.golf;

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
 * Mini Golf (spec §12, R1.22, R2.16).
 *
 * <p>Mini golf in the Games world, where your Mini is the ball: putt in the direction you look
 * with the club's power; slime bounces, ice slides, water puts you back. Courses are built with
 * admin commands; each is its own tile and {@code /hcm play} id.
 *
 * <p>Not built yet: {@link #IMPLEMENTED} is false, so the game reads closed whatever its config
 * says and its screen says "Coming soon!". Its settings already parse and ship, so the owner
 * replaces this class's body and {@link MiniGolfSettings#parse} without touching the framework.
 */
public final class MiniGolf implements Game {

    /** False until the game is built: it reads closed and says "Coming soon!". */
    static final boolean IMPLEMENTED = false;

    public static final GameSpec<MiniGolfSettings> SPEC = new GameSpec<>("golf", GameKind.GOLF,
            MiniGolfSettings.KEYS, MiniGolfSettings.defaults(), MiniGolfSettings::parse,
            MiniGolf::new, null);

    private final GameContext ctx;

    public MiniGolf(GameContext ctx) {
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
        return "Mini Golf";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_GOLF;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Putt the ball into the hole in as few strokes as you can.",
                "Your Mini is the ball!");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.SNOWBALL, "&d" + name() + " &7- coming soon",
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        player.sendMessage(Text.of("&d" + name() + "&7: Coming soon!"));
    }

    /** The live settings (read on every use, never cached across a reload). */
    MiniGolfSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
