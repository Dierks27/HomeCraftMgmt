package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.UUID;

/**
 * The Clubhouse's things in the world that aren't blocks (CLUBHOUSE-SPEC §3, §4): the results board
 * (one text display) and the firework over 1st place.
 *
 * <p><b>Never two boards.</b> Both are game entities ({@link WorldEntities#tag}): never saved with
 * their chunk, and every one the Clubhouse ever made is swept when it starts, so a reload or a crash
 * can't leave a second board standing. The board is made again when its chunk had unloaded it, and
 * its text is set only when {@link ClubBoard#offer} says so (never per tick).
 *
 * <p><b>Kid-safe firework.</b> A burst of colour a few blocks above the podium, set off at once;
 * everyone in the Clubhouse is in a world session (no damage), and the Clubhouse cancels any damage
 * one of its fireworks could do to anyone else.
 */
final class BoardDisplay {

    private final Game game;
    private UUID board;
    private String text;

    BoardDisplay(Game game) {
        this.game = game;
    }

    /**
     * Show {@code text} at {@code at} in {@code worldName}: the display is made (again) if it isn't
     * there, and its text set. Nothing happens while the chunk isn't loaded.
     *
     * @return whether the board shows it now
     */
    boolean show(String worldName, ClubhouseSite.Spot at, String text) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null || at == null || !w.isChunkLoaded((int) Math.floor(at.x()) >> 4, (int) Math.floor(at.z()) >> 4)) {
            return false;
        }
        TextDisplay d = current(w, at);
        if (d == null) {
            Location l = new Location(w, at.x(), at.y(), at.z(), at.yaw(), 0f);
            d = w.spawn(l, TextDisplay.class, e -> {
                WorldEntities.tag(e, game, null);
                e.setBillboard(Display.Billboard.VERTICAL);
                e.setLineWidth(220);
                e.setShadowed(true);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(160, 0, 0, 0));
            });
            board = d.getUniqueId();
            this.text = null;
        }
        if (!text.equals(this.text)) {
            d.text(Text.of(text));
            this.text = text;
        }
        return true;
    }

    /** Whether the board stands now (its chunk loaded and the display there). */
    boolean standing(String worldName, ClubhouseSite.Spot at) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        return w != null && at != null && current(w, at) != null;
    }

    private TextDisplay current(World w, ClubhouseSite.Spot at) {
        if (board == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(board);
        if (e instanceof TextDisplay d && d.isValid() && d.getWorld().equals(w)
                && d.getLocation().distanceSquared(new Location(w, at.x(), at.y(), at.z())) < 0.25) {
            return d;
        }
        if (e != null && e.isValid()) {
            e.remove(); // moved (an owner set a new spot): the old one goes
        }
        board = null;
        return null;
    }

    /** Take the board away (the Clubhouse stopped or closed). */
    void remove() {
        if (board != null) {
            Entity e = Bukkit.getEntity(board);
            if (e != null) {
                e.remove();
            }
        }
        board = null;
        text = null;
    }

    /** A firework burst {@code up} blocks above {@code at}: colours only, set off at once. */
    void firework(String worldName, ClubhouseSite.Spot at, double up) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null || at == null) {
            return;
        }
        Location l = new Location(w, at.x(), at.y() + up, at.z());
        Firework fw = w.spawn(l, Firework.class, f -> {
            WorldEntities.tag(f, game, null);
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.YELLOW, Color.AQUA, Color.FUCHSIA).withFade(Color.WHITE).trail(false)
                    .flicker(true).build());
            meta.setPower(0);
            f.setFireworkMeta(meta);
        });
        fw.detonate();
    }

    /** Whether {@code e} is one of the Clubhouse's fireworks (its burst never hurts anyone). */
    static boolean ours(Entity e, Game game) {
        return e instanceof Firework && game.id().equals(WorldEntities.gameId(e));
    }
}
