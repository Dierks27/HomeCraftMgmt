package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.storage.DisplayDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;

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
                    default -> {
                        // HOLOGRAM / MAPTV handled in later parts.
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

    /** Cleanup hook for a display about to be removed (overridden behaviour per kind). */
    private void onRemoved(DisplayDao.Display d) {
        // SIGN: nothing to despawn. HOLOGRAM / MAPTV cleanup is added with those parts.
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
}
