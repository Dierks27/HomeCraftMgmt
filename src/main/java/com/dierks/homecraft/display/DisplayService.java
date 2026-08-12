package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.storage.DisplayDao;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the in-game economy displays (Phase 7, §3.8): sign boards, holograms,
 * and map-TVs, each bound to a market commodity and re-rendered from the live
 * market on a config timer — never per-tick. Placements persist so they survive
 * restarts and re-render on chunk load.
 */
public final class DisplayService {

    public record Result(boolean ok, String error) {
        static Result fail(String e) {
            return new Result(false, e);
        }
        static Result okay() {
            return new Result(true, null);
        }
    }

    private final HomeCraftManagement plugin;
    private final DisplayDao dao;
    private BukkitTask task;

    /** Live hologram text-display entities we manage: display id → entity UUID. */
    private final Map<Long, UUID> holograms = new HashMap<>();

    public DisplayService(HomeCraftManagement plugin, DisplayDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Start the refresh timer. Renders immediately, then every configured interval. */
    public void start() {
        stop();
        long period = Math.max(2, plugin.config().displays().refreshSeconds()) * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Despawn our (non-persistent) hologram entities so nothing is orphaned.
        for (UUID uid : holograms.values()) {
            Entity e = plugin.getServer().getEntity(uid);
            if (e != null) {
                e.remove();
            }
        }
        holograms.clear();
    }

    /** Re-render every display from the current market snapshot. */
    public void tick() {
        List<DisplayDao.Display> displays;
        try {
            displays = dao.all();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read displays: " + e.getMessage());
            return;
        }
        for (DisplayDao.Display d : displays) {
            try {
                switch (d.kind()) {
                    case DisplayDao.SIGN -> renderSign(d);
                    case DisplayDao.HOLOGRAM -> renderHologram(d);
                    default -> {
                        // MAPTV handled in Part D.
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to render display " + d.id() + ": " + t.getMessage());
            }
        }
    }

    // ---- Sign board -----------------------------------------------------------

    /** Bind the sign at {@code loc} to a commodity and render it now. */
    public Result bindSign(Player admin, Location loc, String itemId) {
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign)) {
            return Result.fail("Look at a placed sign first.");
        }
        if (plugin.market().item(itemId) == null) {
            return Result.fail("Unknown commodity '" + itemId + "'.");
        }
        try {
            dao.upsert(DisplayDao.SIGN, loc, itemId, 1, 1, null, null, admin.getUniqueId(),
                    System.currentTimeMillis());
        } catch (SQLException e) {
            return Result.fail("Could not save the sign board.");
        }
        renderSignBlock(block, itemId);
        return Result.okay();
    }

    /** Remove any economy display (sign/hologram/map-TV) anchored at a block. */
    public Result removeAny(Location loc) {
        boolean removed = false;
        for (String kind : new String[] {DisplayDao.SIGN, DisplayDao.HOLOGRAM, DisplayDao.MAPTV}) {
            try {
                var existing = dao.at(kind, loc);
                if (existing.isPresent()) {
                    onRemoved(existing.get()); // despawn hook (holograms/map-TVs)
                    dao.deleteAt(kind, loc);
                    removed = true;
                }
            } catch (SQLException e) {
                return Result.fail("Could not remove the display.");
            }
        }
        return removed ? Result.okay() : Result.fail("No economy display is bound to that block.");
    }

    /** Cleanup hook for a display about to be removed (per-kind despawn). */
    private void onRemoved(DisplayDao.Display d) {
        if (DisplayDao.HOLOGRAM.equals(d.kind())) {
            removeHologramEntity(d.id());
        }
        // SIGN: nothing to despawn. MAPTV cleanup is added in Part D.
    }

    private void renderSign(DisplayDao.Display d) {
        World world = plugin.getServer().getWorld(d.world());
        if (world == null || !world.isChunkLoaded(d.x() >> 4, d.z() >> 4)) {
            return; // unloaded — will render when the chunk loads and the timer next fires
        }
        Block block = world.getBlockAt(d.x(), d.y(), d.z());
        if (!(block.getState() instanceof Sign)) {
            // The sign was removed/replaced — drop the stale binding.
            try {
                dao.deleteById(d.id());
            } catch (SQLException ignored) {
                // best-effort cleanup
            }
            return;
        }
        renderSignBlock(block, d.itemId());
    }

    private void renderSignBlock(Block block, String itemId) {
        String[] lines = signLines(itemId);
        if (lines == null || !(block.getState() instanceof Sign sign)) {
            return;
        }
        var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.line(i, Text.of(lines[i]));
        }
        sign.update(true, false);
    }

    /** The four sign lines for a commodity: name, price, trend, stock. Null if unknown. */
    private String[] signLines(String itemId) {
        MarketItem item = plugin.market().item(itemId);
        MarketState state = plugin.market().state(itemId);
        if (item == null || state == null) {
            return null;
        }
        double change = plugin.market().change24h(itemId);
        String name = stripToWidth(item.label());
        return new String[] {
                "&1&l" + name,
                "&2" + plugin.economy().format(plugin.market().price(itemId)),
                Trend.color(change) + Trend.label(change),
                "&8Stock: &7" + state.stock()
        };
    }

    /** Strip colour codes and clamp a name to ~15 chars so it fits a sign line. */
    private String stripToWidth(String label) {
        String plain = label == null ? "" : label.replaceAll("(?i)&[0-9a-fk-or]", "");
        return plain.length() <= 15 ? plain : plain.substring(0, 15);
    }

    // ---- Hologram (text-display entity) ---------------------------------------

    /** Bind a floating hologram above {@code loc} to a commodity and spawn it now. */
    public Result bindHologram(Player admin, Location loc, String itemId) {
        if (plugin.market().item(itemId) == null) {
            return Result.fail("Unknown commodity '" + itemId + "'.");
        }
        try {
            dao.upsert(DisplayDao.HOLOGRAM, loc, itemId, 1, 1, null, null, admin.getUniqueId(),
                    System.currentTimeMillis());
        } catch (SQLException e) {
            return Result.fail("Could not save the hologram.");
        }
        // Re-read for the authoritative row id (getGeneratedKeys is unreliable on upsert).
        DisplayDao.Display d;
        try {
            d = dao.at(DisplayDao.HOLOGRAM, loc).orElse(null);
        } catch (SQLException e) {
            return Result.fail("Could not save the hologram.");
        }
        if (d == null) {
            return Result.fail("Could not save the hologram.");
        }
        removeHologramEntity(d.id());          // clear any prior entity at this binding
        if (loc.getWorld() != null) {
            spawnHologram(loc.getWorld(), d);
        }
        return Result.okay();
    }

    private void renderHologram(DisplayDao.Display d) {
        UUID uid = holograms.get(d.id());
        Entity ent = uid != null ? plugin.getServer().getEntity(uid) : null;
        if (ent instanceof TextDisplay td && td.isValid()) {
            td.text(holoText(d.itemId()));     // cheap in-place update
            return;
        }
        // Missing (restart / chunk reload) — respawn if the anchor chunk is loaded.
        World world = plugin.getServer().getWorld(d.world());
        if (world == null || !world.isChunkLoaded(d.x() >> 4, d.z() >> 4)) {
            return;
        }
        spawnHologram(world, d);
    }

    private void spawnHologram(World world, DisplayDao.Display d) {
        Location at = new Location(world, d.x() + 0.5, d.y() + 1.2, d.z() + 0.5);
        TextDisplay td = world.spawn(at, TextDisplay.class, e -> {
            e.text(holoText(d.itemId()));
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false); // we re-spawn from the DB; never saved to chunk data
            e.getPersistentDataContainer().set(Keys.DISPLAY_ID, PersistentDataType.LONG, d.id());
        });
        holograms.put(d.id(), td.getUniqueId());
    }

    private void removeHologramEntity(long id) {
        UUID uid = holograms.remove(id);
        if (uid != null) {
            Entity e = plugin.getServer().getEntity(uid);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Two-line hologram text: coloured name, then price + trend + stock. */
    private net.kyori.adventure.text.Component holoText(String itemId) {
        MarketItem item = plugin.market().item(itemId);
        MarketState state = plugin.market().state(itemId);
        if (item == null || state == null) {
            return Text.of("&cUnknown commodity");
        }
        double change = plugin.market().change24h(itemId);
        return Text.of(item.label()
                + "\n&6" + plugin.economy().format(plugin.market().price(itemId))
                + "  " + Trend.color(change) + Trend.label(change)
                + " &8· &7" + state.stock());
    }

    /**
     * Respawn any holograms anchored in a freshly-loaded chunk (called from the
     * chunk-load listener) so they reappear immediately rather than on the next tick.
     */
    public void onChunkLoad(Chunk chunk) {
        if (holograms.isEmpty() && chunk == null) {
            return;
        }
        List<DisplayDao.Display> holos;
        try {
            holos = dao.byKind(DisplayDao.HOLOGRAM);
        } catch (SQLException e) {
            return;
        }
        for (DisplayDao.Display d : holos) {
            if (!d.world().equals(chunk.getWorld().getName())) {
                continue;
            }
            if ((d.x() >> 4) != chunk.getX() || (d.z() >> 4) != chunk.getZ()) {
                continue;
            }
            UUID uid = holograms.get(d.id());
            Entity ent = uid != null ? plugin.getServer().getEntity(uid) : null;
            if (!(ent instanceof TextDisplay td) || !td.isValid()) {
                spawnHologram(chunk.getWorld(), d);
            }
        }
    }
}
