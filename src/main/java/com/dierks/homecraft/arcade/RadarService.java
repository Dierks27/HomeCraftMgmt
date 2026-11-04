package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.hunt.HuntMath;
import com.dierks.homecraft.hunt.HuntService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * The Mini Radar: lock it onto the nearest wild Mini and, every few seconds for a while, the action bar says how warm
 * you are — Cold, Warm, Hot (and Burning! when the owner turns that band on) — with a note whose pitch climbs as you
 * close in.
 *
 * <p>It tells you WHETHER you are getting closer, never where: no direction, no distance. The cadence, how long a
 * charge reads, the bands and the once-per-Mini rule are {@code arcade.radar} ({@link PluginConfig.Radar}), asked at
 * every check, so {@code /hcm reload} changes radars that are already on. The radar is spent when it locks on; it
 * reads until its time is up ("out of charge") or that Mini is caught or escapes. Who has one on and what each does
 * is {@link RadarLocks}; this class is checked once a second and does what it says. It changes who finds a spawn,
 * never how many there are.
 */
public final class RadarService {

    private final HomeCraftManagement plugin;
    private final RadarLocks locks = new RadarLocks();
    private BukkitTask task;

    public RadarService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Check every radar once a second; each one reads on its own cadence ({@link RadarLocks#check}). */
    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Lock a radar onto the nearest live hunt in the player's world, and read at once.
     *
     * @return ON when it locked on (spend the radar); NO_MINI or USED when it didn't (nothing is spent then)
     */
    public RadarLocks.Lock activate(Player player) {
        HuntService hunt = plugin.hunt();
        HuntService.Hunt h = hunt == null ? null : hunt.nearest(player);
        RadarLocks.Lock lock = locks.lock(player.getUniqueId(), h == null ? null : h.key(),
                System.currentTimeMillis(), settings());
        if (lock == RadarLocks.Lock.ON) {
            ping(player, h);
        }
        return lock;
    }

    /** True while this player's radar is locked onto a live hunt and still has charge. */
    public boolean active(UUID player) {
        return locks.active(player, System.currentTimeMillis(), this::live, settings());
    }

    private PluginConfig.Radar settings() {
        PluginConfig.Radar r = plugin.config() == null ? null : plugin.config().radar();
        return r == null ? PluginConfig.Radar.DEFAULTS : r;
    }

    private boolean live(String huntKey) {
        HuntService hunt = plugin.hunt();
        return hunt != null && hunt.byKey(huntKey) != null;
    }

    private void tick() {
        for (RadarLocks.Due due : locks.check(System.currentTimeMillis(), this::live, settings())) {
            Player player = plugin.getServer().getPlayer(due.player());
            if (player == null) {
                continue;
            }
            switch (due.step()) {
                case GONE -> player.sendActionBar(Text.of("&7Mini Radar: the Mini is gone."));
                case SPENT -> player.sendActionBar(Text.of("&7Mini Radar: out of charge."));
                case READ -> {
                    HuntService.Hunt h = plugin.hunt() == null ? null : plugin.hunt().byKey(due.huntKey());
                    if (h != null) {
                        ping(player, h);
                    }
                }
            }
        }
    }

    private void ping(Player player, HuntService.Hunt h) {
        double d = plugin.hunt().distanceTo(player, h);
        HuntMath.Band band = d == Double.MAX_VALUE ? HuntMath.Band.COLD : HuntMath.band(d, settings().bands());
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
