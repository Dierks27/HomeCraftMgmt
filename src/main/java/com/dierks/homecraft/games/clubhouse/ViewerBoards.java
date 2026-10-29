package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Boards that only their viewer sees (0.38.3: My stats on the Clubhouse's west wall): one text display per viewer,
 * all at the same spot, each hidden from everyone as it spawns (inside the spawn's consumer, so nobody else is ever
 * sent it) and then shown to its one viewer, as the Firework Remote's numbers are ({@code arcade.RemoteMarkers}).
 *
 * <p>Each is a game entity tagged for its viewer ({@code WorldEntities.tag}), never saved, swept at every
 * Clubhouse start and by the chunk-load sweep, locked in place like the shared boards ({@link BoardDisplay}), and
 * removed when the viewer leaves the Clubhouse or quits, when the room closes or moves, when the boards are
 * switched off and when the Clubhouse stops. Its text is set only when it changed. It never throws: a board is
 * decoration, and the My stats screen says the same.
 */
final class ViewerBoards {

    private final Game game;
    private final Plugin plugin;
    private final Map<UUID, Shown> shown = new HashMap<>();

    /** A viewer's display, and the text it shows. */
    private static final class Shown {
        private final UUID entity;
        private String text;

        private Shown(UUID entity) {
            this.entity = entity;
        }
    }

    ViewerBoards(Game game, Plugin plugin) {
        this.game = game;
        this.plugin = plugin;
    }

    /**
     * Show {@code text} to {@code viewer} at {@code at} in {@code worldName}: their display is made (again) if it
     * isn't there or isn't right, and its text set when it changed. Nothing happens while the chunk isn't loaded.
     *
     * @return whether it shows now
     */
    boolean show(Player viewer, String worldName, WallBoards.Wall at, String text) {
        try {
            World w = worldName == null ? null : Bukkit.getWorld(worldName);
            if (viewer == null || w == null || at == null || text == null
                    || !w.isChunkLoaded((int) Math.floor(at.x()) >> 4, (int) Math.floor(at.z()) >> 4)) {
                return false;
            }
            UUID id = viewer.getUniqueId();
            Shown s = shown.get(id);
            TextDisplay d = s == null ? null : current(s, w, at);
            if (d == null) {
                remove(id);
                d = w.spawn(new Location(w, at.x(), at.y(), at.z(), at.yaw(), 0f), TextDisplay.class, e -> {
                    e.setVisibleByDefault(false); // first: nobody else ever gets it
                    BoardDisplay.lockInPlace(e, game, id, at, TextDisplay.TextAlignment.LEFT);
                });
                viewer.showEntity(plugin, d);
                s = new Shown(d.getUniqueId());
                shown.put(id, s);
            }
            if (!text.equals(s.text)) {
                d.text(Text.of(text));
                s.text = text;
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false; // decoration: the My stats screen says the same
        }
    }

    /** {@code s}'s display if it is still there and right ({@link BoardDisplay#still}), else {@code null}. */
    private static TextDisplay current(Shown s, World w, WallBoards.Wall at) {
        Entity e = Bukkit.getEntity(s.entity);
        if (e instanceof TextDisplay d && d.isValid() && d.getWorld().equals(w)
                && BoardDisplay.still(d.getBillboard() == org.bukkit.entity.Display.Billboard.FIXED,
                d.getLocation().getYaw(), d.getLocation().distanceSquared(new Location(w, at.x(), at.y(), at.z())),
                at)) {
            return d;
        }
        return null;
    }

    /** {@code viewer}'s board comes down (they left the Clubhouse, or quit). */
    void remove(UUID viewer) {
        Shown s = viewer == null ? null : shown.remove(viewer);
        if (s == null) {
            return;
        }
        try {
            Entity e = Bukkit.getEntity(s.entity);
            if (e != null && e.isValid()) {
                e.remove();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // gone with its chunk: the chunk-load sweep takes it
        }
    }

    /** Every viewer's board comes down. */
    void clear() {
        for (UUID viewer : new ArrayList<>(shown.keySet())) {
            remove(viewer);
        }
    }

    /** How many are up (for the status line). */
    int size() {
        return shown.size();
    }
}
