package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
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
 * The numbered holograms over a player's linked launchers (0.38.2; the owner: "a way to tag it so I can see what
 * launcher is what ... in real life by displaying it. Either on the tool or as a hologram"): while a player holds a
 * Firework Remote in either hand, each launcher in their set in their world that can fire now (its chunk loaded and
 * entity-ticking, the show's own test: {@link MegaShowService#ticking}, review LINK-LOADED-1) and within
 * {@value #RANGE} blocks, has "#3" over it in its number's colour (and the owner's name under it when it isn't
 * theirs). So a number over a launcher means a show would fire from it now, as My Show says.
 *
 * <p>Each is one {@link TextDisplay} that only its viewer sees: hidden from everyone as it spawns (inside the spawn's
 * consumer, so nobody else is ever sent it) and then shown to that one player, never saved with its chunk, and tagged
 * {@link Keys#REMOTE_MARKER} so one left anywhere is swept ({@link RemoteService}'s {@code EntitiesLoadEvent}). A
 * display has no hitbox, so a tap goes through it to the launcher, and it does almost nothing a tick. A renumber after
 * an unlink changes its text in place. They go when the Remote isn't held, at a quit, a world change, a chunk unload
 * and when the plugin stops. Geyser draws one as a nametag over the launcher (the text, its colours the nearest
 * Bedrock ones).
 *
 * <p>A chunk is asked first and never loaded for one; the 10-tick pass ({@link RemoteService}) is two item reads a
 * player, and for a holder at most ten chunk checks and a diff.
 */
final class RemoteMarkers {

    /** A launcher farther than this (blocks) from its viewer has no hologram (inside the display tracking range). */
    static final double RANGE = 96;
    /** How far above the launcher's block the number floats. */
    private static final double ABOVE = 1.6;

    private final HomeCraftManagement plugin;
    private final RemoteService remotes;
    /** Each viewer's holograms, by launcher. */
    private final Map<UUID, Map<Spot, Shown>> shown = new HashMap<>();
    /** Every live hologram's entity id, for the sweep. */
    private final Set<UUID> live = new HashSet<>();

    /** One hologram: its entity and the lines it shows. */
    private static final class Shown {
        private final TextDisplay display;
        private List<String> lines;

        private Shown(TextDisplay display, List<String> lines) {
            this.display = display;
            this.lines = lines;
        }
    }

    RemoteMarkers(HomeCraftManagement plugin, RemoteService remotes) {
        this.plugin = plugin;
        this.remotes = remotes;
    }

    /** Whether {@code p} holds a Firework Remote in either hand. */
    static boolean holdsRemote(Player p) {
        return PrizeItems.kindOf(p.getInventory().getItemInMainHand()) == PrizeType.REMOTE
                || PrizeItems.kindOf(p.getInventory().getItemInOffHand()) == PrizeType.REMOTE;
    }

    /** {@code p}'s holograms as they should be now: one per linked launcher in range while they hold a Remote. */
    void update(Player p) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || !holdsRemote(p)) {
            clear(id);
            return;
        }
        World w = p.getWorld();
        String world = w.getName();
        Location at = p.getLocation();
        List<Spot> set = remotes.linked(id);
        Map<Spot, List<String>> wanted = new LinkedHashMap<>();
        for (int i = 0; i < set.size(); i++) {
            Spot s = set.get(i);
            if (!s.world().equals(world) || !MegaShowService.ticking(w, s)) {
                continue; // never load a chunk for a number (it is asked first), nor number one that wouldn't fire
            }
            double dx = s.x() + 0.5 - at.getX();
            double dy = s.y() + 0.5 - at.getY();
            double dz = s.z() + 0.5 - at.getZ();
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) {
                continue;
            }
            UUID owner = remotes.ownerOf(s);
            wanted.put(s, RemoteRules.markerLines(i + 1, owner == null || owner.equals(id) ? null
                    : remotes.nameOf(owner)));
        }
        Map<Spot, Shown> mine = shown.computeIfAbsent(id, k -> new HashMap<>());
        for (Iterator<Map.Entry<Spot, Shown>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Spot, Shown> e = it.next();
            if (!wanted.containsKey(e.getKey()) || !e.getValue().display.isValid()) {
                remove(e.getValue()); // not wanted, or gone with its chunk
                it.remove();
            }
        }
        for (Map.Entry<Spot, List<String>> e : wanted.entrySet()) {
            Shown s = mine.get(e.getKey());
            if (s == null) {
                TextDisplay td = spawn(p, w, e.getKey(), e.getValue());
                if (td != null) {
                    mine.put(e.getKey(), new Shown(td, e.getValue()));
                }
            } else if (!s.lines.equals(e.getValue())) {
                s.display.text(text(e.getValue())); // a renumber: in place
                s.lines = e.getValue();
            }
        }
        if (mine.isEmpty()) {
            shown.remove(id);
        }
    }

    /**
     * One hologram over {@code s}, only {@code viewer} sees: hidden from everyone and never saved, both set inside the
     * spawn's consumer (before anyone is sent it), then shown to the viewer.
     */
    private TextDisplay spawn(Player viewer, World w, Spot s, List<String> lines) {
        try {
            TextDisplay td = w.spawn(new Location(w, s.x() + 0.5, s.y() + ABOVE, s.z() + 0.5), TextDisplay.class, d -> {
                d.setVisibleByDefault(false); // first: nobody else ever gets it
                d.setPersistent(false); // never saved with the chunk
                d.text(text(lines));
                d.setBillboard(Display.Billboard.CENTER); // Geyser and every Java client need a billboard
                d.setSeeThrough(true); // findable behind a wall, for this player only
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(0x40000000));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf())); // Geyser ignores it: a nametag's size
                d.getPersistentDataContainer().set(Keys.REMOTE_MARKER, PersistentDataType.STRING,
                        viewer.getUniqueId().toString());
            });
            viewer.showEntity(plugin, td);
            live.add(td.getUniqueId());
            return td;
        } catch (RuntimeException | LinkageError e) {
            return null; // cosmetic: the lore and My Show still say where it is
        }
    }

    private static net.kyori.adventure.text.Component text(List<String> lines) {
        return Text.of(String.join("\n", lines));
    }

    /** Every hologram of {@code viewer}'s comes down (not held, a quit, a world change: the next pass). */
    void clear(UUID viewer) {
        Map<Spot, Shown> mine = shown.remove(viewer);
        if (mine == null) {
            return;
        }
        for (Shown s : mine.values()) {
            remove(s);
        }
    }

    /** Every hologram comes down (the plugin is stopping). */
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
