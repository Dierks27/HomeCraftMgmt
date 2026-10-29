package com.dierks.homecraft.golem;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "#1".."#N" holograms over a golem's linked chests (0.38.4), shown to a player while they hold the Copper Golem
 * Brush in the main hand with a "pick the chests" session open on that golem: one per linked chest block (both
 * halves of a double chest, numbered in link order) in the player's world, within {@value #RANGE} blocks, whose
 * chunk is loaded (never loaded for one). The Firework Remote's holograms ({@code arcade.RemoteMarkers}) over
 * chests instead of launchers: each is one {@link TextDisplay} only its viewer sees (hidden from everyone as it
 * spawns, inside the spawn's consumer, then shown to that one player), never saved with its chunk, and tagged
 * {@link Keys#GOLEM_MARKER} so one left anywhere is swept ({@link GolemService}'s {@code EntitiesLoadEvent}). A
 * display has no hitbox, so a tap goes through it to the chest. Geyser draws one as a name tag over the chest.
 */
final class GolemMarkers {

    /** A chest farther than this (blocks) from its viewer has no hologram (inside the display tracking range). */
    static final double RANGE = 96;
    /** How far above the chest's block the number floats (a chest is lower than a launcher). */
    private static final double ABOVE = 1.3;

    private final HomeCraftManagement plugin;
    private final GolemService golems;
    /** Each viewer's holograms, by packed chest position. */
    private final Map<UUID, Map<Long, Shown>> shown = new HashMap<>();
    /** Every live hologram's entity id, for the sweep. */
    private final Set<UUID> live = new HashSet<>();

    /** One hologram: its entity and the line it shows. */
    private static final class Shown {
        private final TextDisplay display;
        private String line;

        private Shown(TextDisplay display, String line) {
            this.display = display;
            this.line = line;
        }
    }

    GolemMarkers(HomeCraftManagement plugin, GolemService golems) {
        this.plugin = plugin;
        this.golems = golems;
    }

    /** {@code p}'s holograms as they should be now: one per linked chest in range while their session is open. */
    void update(Player p) {
        UUID id = p.getUniqueId();
        GolemRules.Session session = p.isOnline() ? golems.sessionOf(id) : null;
        GolemService.Assignment a = session == null ? null : golems.assignmentOf(session.golem());
        if (a == null || !GolemBrush.holdsInMainHand(p)) {
            clear(id);
            return;
        }
        World w = p.getWorld();
        Location at = p.getLocation();
        Map<Long, String> wanted = new LinkedHashMap<>();
        if (a.world().equals(w.getName())) {
            int n = 0;
            for (long packed : a.chests()) {
                n++;
                int x = GolemRules.x(packed);
                int y = GolemRules.y(packed);
                int z = GolemRules.z(packed);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue; // never load a chunk for a number (it is asked first)
                }
                double dx = x + 0.5 - at.getX();
                double dy = y + 0.5 - at.getY();
                double dz = z + 0.5 - at.getZ();
                if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) {
                    continue;
                }
                wanted.put(packed, "&6&l#" + n);
            }
        }
        Map<Long, Shown> mine = shown.computeIfAbsent(id, k -> new HashMap<>());
        for (Iterator<Map.Entry<Long, Shown>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Shown> e = it.next();
            if (!wanted.containsKey(e.getKey()) || !e.getValue().display.isValid()) {
                remove(e.getValue()); // not wanted, or gone with its chunk
                it.remove();
            }
        }
        for (Map.Entry<Long, String> e : wanted.entrySet()) {
            Shown s = mine.get(e.getKey());
            if (s == null) {
                TextDisplay td = spawn(p, w, e.getKey(), e.getValue());
                if (td != null) {
                    mine.put(e.getKey(), new Shown(td, e.getValue()));
                }
            } else if (!s.line.equals(e.getValue())) {
                s.display.text(Text.of(e.getValue())); // a renumber after an unlink: in place
                s.line = e.getValue();
            }
        }
        if (mine.isEmpty()) {
            shown.remove(id);
        }
    }

    /**
     * One hologram over the chest at {@code packed}, only {@code viewer} sees: hidden from everyone and never saved,
     * both set inside the spawn's consumer (before anyone is sent it), then shown to the viewer.
     */
    private TextDisplay spawn(Player viewer, World w, long packed, String line) {
        try {
            Location at = new Location(w, GolemRules.x(packed) + 0.5, GolemRules.y(packed) + ABOVE,
                    GolemRules.z(packed) + 0.5);
            TextDisplay td = w.spawn(at, TextDisplay.class, d -> {
                d.setVisibleByDefault(false); // first: nobody else ever gets it
                d.setPersistent(false); // never saved with the chunk
                d.text(Text.of(line));
                d.setBillboard(Display.Billboard.CENTER); // Geyser and every Java client need a billboard
                d.setSeeThrough(true); // findable behind a wall, for this player only
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(0x40000000));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf())); // Geyser ignores it: a nametag's size
                d.getPersistentDataContainer().set(Keys.GOLEM_MARKER, PersistentDataType.STRING,
                        viewer.getUniqueId().toString());
            });
            viewer.showEntity(plugin, td);
            live.add(td.getUniqueId());
            return td;
        } catch (RuntimeException | LinkageError e) {
            return null; // cosmetic: the chat line said which chest it was
        }
    }

    /** Every hologram of {@code viewer}'s comes down (the session ended, a quit, a world change: the next pass). */
    void clear(UUID viewer) {
        Map<Long, Shown> mine = shown.remove(viewer);
        if (mine == null) {
            return;
        }
        for (Shown s : mine.values()) {
            remove(s);
        }
    }

    /** Every hologram comes down (the plugin is stopping, or the brush was switched off). */
    void clearAll() {
        for (UUID viewer : new ArrayList<>(shown.keySet())) {
            clear(viewer);
        }
        live.clear();
    }

    /** Whether {@code e} is one of the holograms up now (a tagged one that isn't is left over, and swept). */
    boolean live(Entity e) {
        return live.contains(e.getUniqueId());
    }

    private void remove(Shown s) {
        live.remove(s.display.getUniqueId());
        try {
            if (s.display.isValid()) {
                s.display.remove();
            }
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
