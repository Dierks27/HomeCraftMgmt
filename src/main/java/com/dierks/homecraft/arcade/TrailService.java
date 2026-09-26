package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Particle trails bought from the Prize Counter: time-limited, one switched on at a time, and
 * quiet — a couple of particles at the feet, only while the player is actually walking, so a
 * trail is something you notice following a friend and not a cloud around everybody standing
 * at the Mall. Toggled with {@code /hcm trail} (and from the Wallet).
 */
public final class TrailService implements Listener {

    /** At most one puff per this many milliseconds per player. */
    private static final long PUFF_MS = 150;

    private record Active(String prizeId, Particle particle, long expiresAt) {
    }

    private final HomeCraftManagement plugin;
    private final PrizeDao dao;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, Long> lastPuff = new HashMap<>();

    public TrailService(HomeCraftManagement plugin, PrizeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Add {@code days} to a trail (switching it on if the player has none on); false on failure. */
    public boolean grant(Player player, Prize trail, int days) {
        if (trail.type() != PrizeType.TRAIL) {
            return false;
        }
        try {
            dao.grantCosmetic(player.getUniqueId(), trail.id(), days * 86_400_000L, System.currentTimeMillis(), true);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not grant a trail: " + e.getMessage());
            return false;
        }
        load(player);
        return true;
    }

    /** One owned, unexpired trail and whether it is the one switched on. */
    public record Owned(Prize prize, long expiresAt, boolean on) {
    }

    /** The player's unexpired trails, in config order. */
    public List<Owned> owned(Player player) {
        List<Owned> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            Map<String, PrizeDao.Cosmetic> mine = new HashMap<>();
            for (PrizeDao.Cosmetic c : dao.cosmetics(player.getUniqueId())) {
                mine.put(c.id(), c);
            }
            for (Prize p : plugin.config().arcade().prizes()) {
                PrizeDao.Cosmetic c = mine.get(p.id());
                if (p.type() == PrizeType.TRAIL && c != null && c.expiresAt() > now) {
                    out.add(new Owned(p, c.expiresAt(), c.enabled()));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read trails: " + e.getMessage());
        }
        return out;
    }

    /** The trail switched on right now, or null. */
    public Owned current(Player player) {
        for (Owned o : owned(player)) {
            if (o.on()) {
                return o;
            }
        }
        return null;
    }

    /**
     * Switch trails on or off. Off → on picks the one that was last on, else the first owned.
     *
     * @return a message for the player
     */
    public String toggle(Player player) {
        List<Owned> mine = owned(player);
        if (mine.isEmpty()) {
            return "&7You don't have a trail. Get one at the Prize Counter!";
        }
        Owned on = current(player);
        try {
            if (on != null) {
                dao.enableOnly(player.getUniqueId(), null);
                load(player);
                return "&7Trail off.";
            }
            dao.enableOnly(player.getUniqueId(), mine.get(0).prize().id());
        } catch (SQLException e) {
            return "&cCouldn't change your trail — try again.";
        }
        load(player);
        return "&aTrail on: &f" + mine.get(0).prize().display();
    }

    /** Switch on one particular trail by prize id. */
    public String select(Player player, String prizeId) {
        for (Owned o : owned(player)) {
            if (o.prize().id().equalsIgnoreCase(prizeId)) {
                try {
                    dao.enableOnly(player.getUniqueId(), o.prize().id());
                } catch (SQLException e) {
                    return "&cCouldn't change your trail — try again.";
                }
                load(player);
                return "&aTrail on: &f" + o.prize().display();
            }
        }
        return "&7You don't have that trail.";
    }

    /** Refresh the in-memory switched-on trail for a player. */
    public void load(Player player) {
        Owned on = current(player);
        if (on == null) {
            active.remove(player.getUniqueId());
            return;
        }
        Particle particle = particle(on.prize().particle());
        if (particle == null) {
            active.remove(player.getUniqueId());
            return;
        }
        active.put(player.getUniqueId(), new Active(on.prize().id(), particle, on.expiresAt()));
    }

    private Particle particle(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Trail particle '" + name + "' is not a particle — that trail shows nothing.");
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        lastPuff.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (active.isEmpty()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return; // only while walking somewhere, not while looking around
        }
        Player player = event.getPlayer();
        Active a = active.get(player.getUniqueId());
        if (a == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (a.expiresAt() <= now) {
            active.remove(player.getUniqueId());
            player.sendMessage(Text.of("&7Your trail has run out. Get another at the Prize Counter!"));
            return;
        }
        Long last = lastPuff.get(player.getUniqueId());
        if (last != null && now - last < PUFF_MS) {
            return;
        }
        lastPuff.put(player.getUniqueId(), now);
        try {
            player.getWorld().spawnParticle(a.particle(), from.clone().add(0, 0.15, 0), 2, 0.15, 0.05, 0.15, 0.0);
        } catch (RuntimeException e) {
            active.remove(player.getUniqueId()); // a particle that needs data we do not give — stop trying
        }
    }
}
