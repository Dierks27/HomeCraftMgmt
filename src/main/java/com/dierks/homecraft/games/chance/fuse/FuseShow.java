package com.dierks.homecraft.games.chance.fuse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 0.57.0: Creeper Fuse in the room, kept small: a bar at the top of the screen for everyone in the Game Parlor (and
 * everyone in the fuse) with the multiple as it climbs, and, over the machine, a creeper's head that swells and
 * flashes faster as the fuse burns down, then goes off.
 *
 * <p><b>Going off is only a show.</b> A puff of explosion particles and the bang's sound at the head: no explosion is
 * made, so nothing is broken and nobody is hurt. The head is a display, not a mob (it can't move, be hit, or go off by
 * itself), and a game entity ({@link WorldEntities#tag}): never saved, swept at every start.
 *
 * <p>The machine is found by its tap ({@code machine:creeper_fuse}, the Clubhouse's), near whoever is in the Parlor,
 * once each fuse. With no machine placed there is no head; the bar and the screen are the game either way. Nothing
 * here touches a token: it only reads {@link CreeperFuse}.
 */
final class FuseShow {

    /** How far over the machine's foot the head hangs: above its name. */
    private static final double HEAD_UP = 2.9;
    /** How far the machine is looked for around each player in the Parlor. */
    private static final double FIND_RADIUS = 48;

    private final HomeCraftManagement plugin;
    private final CreeperFuse fuse;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, String> said = new HashMap<>();
    private UUID head;
    private Location at;
    private int ticks;

    FuseShow(HomeCraftManagement plugin, CreeperFuse fuse) {
        this.plugin = plugin;
        this.fuse = fuse;
    }

    /** Every tick: the bar's line while there is a fuse, and the head's swell while it burns. */
    void tick() {
        ticks++;
        if (fuse.phase() == CreeperFuse.Phase.LIT) {
            bars();
            swell();
        } else if (ticks % 10 == 0) {
            bars();
        }
    }

    /** Something changed (a put-in, a cash-out, the end): the bars at once. */
    void changed() {
        bars();
    }

    /** The fuse is lit: find the machine, hang the head over it, and hiss. */
    void lit() {
        removeHead();
        at = findMachine();
        if (at == null) {
            return;
        }
        World w = at.getWorld();
        ItemDisplay d = w.spawn(at, ItemDisplay.class, e -> {
            WorldEntities.tag(e, fuse, null);
            e.setItemStack(new ItemStack(Material.CREEPER_HEAD));
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setGlowColorOverride(Color.WHITE);
            e.setTransformation(scale(0.8f));
        });
        head = d.getUniqueId();
        w.playSound(at, Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f);
    }

    /** It went off (a bang and smoke, nothing more) or burned out (a fizzle); the head comes down a moment later. */
    void over(boolean burnedOut) {
        bars();
        if (at == null) {
            return;
        }
        World w = at.getWorld();
        if (burnedOut) {
            w.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            w.spawnParticle(Particle.SMOKE, at, 30, 0.3, 0.3, 0.3, 0.02);
        } else {
            w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
            w.spawnParticle(Particle.EXPLOSION, at, 3, 0.4, 0.4, 0.4, 0.0);
            w.spawnParticle(Particle.POOF, at, 25, 0.5, 0.5, 0.5, 0.05);
        }
        Entity e = head == null ? null : Bukkit.getEntity(head);
        if (e instanceof ItemDisplay d) {
            d.setGlowing(false);
            d.setTransformation(scale(burnedOut ? 0.8f : 0.01f));
        }
        UUID gone = head;
        head = null;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Entity x = gone == null ? null : Bukkit.getEntity(gone);
            if (x != null) {
                x.remove();
            }
        }, 30L);
    }

    /** Take everything down (the game stopped). */
    void clear() {
        removeHead();
        for (Map.Entry<UUID, BossBar> b : bars.entrySet()) {
            Player p = Bukkit.getPlayer(b.getKey());
            if (p != null) {
                p.hideBossBar(b.getValue());
            }
        }
        bars.clear();
        said.clear();
    }

    /** A player left: their bar goes with them. */
    void forget(UUID id) {
        bars.remove(id);
        said.remove(id);
    }

    // ---- the bars --------------------------------------------------------------------------------------------

    /** The bar for everyone in the Parlor or in the fuse while there is one; nobody's while the machine is idle. */
    private void bars() {
        Set<UUID> want = new HashSet<>();
        if (fuse.phase() != CreeperFuse.Phase.IDLE) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (fuse.inParlor(id) || fuse.entry(id) != null) {
                    want.add(id);
                }
            }
        }
        for (UUID id : new HashSet<>(bars.keySet())) {
            if (!want.contains(id)) {
                BossBar b = bars.remove(id);
                said.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null && b != null) {
                    p.hideBossBar(b);
                }
            }
        }
        float progress = (float) Math.max(0, Math.min(1, fuse.progress()));
        BossBar.Color colour = switch (fuse.phase()) {
            case GATHERING -> BossBar.Color.YELLOW;
            case LIT -> BossBar.Color.GREEN;
            default -> fuse.burnedOut() ? BossBar.Color.WHITE : BossBar.Color.RED;
        };
        for (UUID id : want) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            String line = fuse.barLine(id);
            BossBar b = bars.get(id);
            if (b == null) {
                b = BossBar.bossBar(Text.of(line), progress, colour, BossBar.Overlay.PROGRESS);
                bars.put(id, b);
                said.put(id, line);
                p.showBossBar(b);
                continue;
            }
            if (!line.equals(said.get(id))) {
                b.name(Text.of(line));
                said.put(id, line);
            }
            b.progress(progress);
            b.color(colour);
        }
    }

    // ---- the head --------------------------------------------------------------------------------------------

    /** The head swells as the fuse burns, and flashes faster and faster; a hiss now and then. */
    private void swell() {
        Entity e = head == null ? null : Bukkit.getEntity(head);
        if (!(e instanceof ItemDisplay d)) {
            return;
        }
        double p = fuse.progress();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(scale((float) (0.8 + 0.6 * p)));
        int every = Math.max(2, (int) Math.round(12 - 10 * p));
        if (ticks % every == 0) {
            d.setGlowing(!d.isGlowing());
        }
        if (ticks % 40 == 0 && at != null) {
            at.getWorld().playSound(at, Sound.ENTITY_CREEPER_PRIMED, 0.5f, (float) (0.8 + 0.6 * p));
        }
    }

    private void removeHead() {
        Entity e = head == null ? null : Bukkit.getEntity(head);
        if (e != null) {
            e.remove();
        }
        head = null;
    }

    /** The Creeper Fuse machine near anyone in the Parlor: over its tap target, or {@code null} when there is none. */
    private Location findMachine() {
        String tap = "machine:" + CreeperFuse.SPEC.id();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!fuse.inParlor(p.getUniqueId()) && fuse.entry(p.getUniqueId()) == null) {
                continue;
            }
            for (Entity e : p.getNearbyEntities(FIND_RADIUS, FIND_RADIUS / 2, FIND_RADIUS)) {
                if (e instanceof Interaction && Keys.CLUB_TAP != null && tap.equals(e.getPersistentDataContainer()
                        .get(Keys.CLUB_TAP, PersistentDataType.STRING))) {
                    return e.getLocation().add(0, HEAD_UP, 0);
                }
            }
        }
        return null;
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }
}
