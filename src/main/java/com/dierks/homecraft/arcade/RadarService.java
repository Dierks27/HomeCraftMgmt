package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.hunt.HuntMath;
import com.dierks.homecraft.hunt.HuntService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Mini Radar: attach it to the live hunt and every two seconds the action bar says how warm
 * you are — Cold, Warm, Hot, Burning! — with a note whose pitch climbs as you close in.
 *
 * <p>It tells you WHETHER you are getting closer, never where: no direction, no distance. The
 * charge is spent when it attaches and lasts until that Mini is caught or escapes. It changes
 * who finds a spawn, never how many there are.
 */
public final class RadarService {

    private final HomeCraftManagement plugin;
    /** Player → the hunt their radar is locked onto. */
    private final Map<UUID, String> attached = new HashMap<>();
    private BukkitTask task;

    public RadarService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Lock a radar onto the nearest live hunt in the player's world.
     *
     * @return false when there is nothing to lock onto (nothing is spent then)
     */
    public boolean activate(Player player) {
        HuntService hunt = plugin.hunt();
        HuntService.Hunt h = hunt == null ? null : hunt.nearest(player);
        if (h == null) {
            return false;
        }
        attached.put(player.getUniqueId(), h.key());
        ping(player, h);
        return true;
    }

    /** True while this player's radar is locked onto a live hunt. */
    public boolean active(UUID player) {
        String key = attached.get(player);
        return key != null && plugin.hunt() != null && plugin.hunt().byKey(key) != null;
    }

    private void tick() {
        HuntService hunt = plugin.hunt();
        for (Iterator<Map.Entry<UUID, String>> it = attached.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, String> e = it.next();
            Player player = plugin.getServer().getPlayer(e.getKey());
            HuntService.Hunt h = hunt == null ? null : hunt.byKey(e.getValue());
            if (h == null) {
                if (player != null) {
                    player.sendActionBar(Text.of("&7Mini Radar: the Mini is gone."));
                }
                it.remove();
                continue;
            }
            if (player != null) {
                ping(player, h);
            }
        }
    }

    private void ping(Player player, HuntService.Hunt h) {
        double d = plugin.hunt().distanceTo(player, h);
        HuntMath.Band band = d == Double.MAX_VALUE ? HuntMath.Band.COLD : HuntMath.band(d);
        String colour = switch (band) {
            case BURNING -> "&c&l";
            case HOT -> "&6";
            case WARM -> "&e";
            default -> "&b";
        };
        float pitch = switch (band) {
            case BURNING -> 2.0f;
            case HOT -> 1.5f;
            case WARM -> 1.1f;
            default -> 0.7f;
        };
        player.sendActionBar(Text.of("&7Mini Radar: " + colour + band.label()));
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
        } catch (Throwable ignored) {
            // cosmetic
        }
    }
}
