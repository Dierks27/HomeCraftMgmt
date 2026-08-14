package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.storage.DisplayDao;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
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
    private BukkitTask spinTask;

    /** A hologram's live entities: the text ticker and (optionally) the floating item. */
    private record Holo(UUID text, UUID item) {
    }

    /** Live hologram entities we manage: display id → its entity pair. */
    private final Map<Long, Holo> holograms = new HashMap<>();

    /** Bumped each refresh tick; map-TV renderers repaint only when it changes. */
    private volatile long version;

    /** Advancing spin angle (radians) for floating item displays. */
    private float spinAngle;

    public DisplayService(HomeCraftManagement plugin, DisplayDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Start the refresh timer. Renders immediately, then every configured interval. */
    public void start() {
        stop();
        attachMapTvs(); // re-add renderers to persisted map-TVs (renderers aren't saved)
        // Boot safety: wipe any display entities left in loaded chunks (e.g. leaked items
        // from an older map-TV rework) before the tick re-spawns the ones still bound in
        // the DB. Nothing stale ever resurrects on load or chunk reload.
        int strays = purgeOwnedDisplays();
        if (strays > 0) {
            plugin.getLogger().info("Cleared " + strays + " stale display "
                    + (strays == 1 ? "entity" : "entities") + " on start.");
        }
        long period = Math.max(2, plugin.config().displays().refreshSeconds()) * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, period);
        if (plugin.config().displays().hologram().itemDisplay() && plugin.config().displays().hologram().spin()) {
            spinTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::spinItems, 20L, 2L);
        }
    }

    /** Monotonic version bumped each refresh; map-TV renderers repaint only when it changes. */
    public long snapshotVersion() {
        return version;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (spinTask != null) {
            spinTask.cancel();
            spinTask = null;
        }
        // Despawn our (non-persistent) hologram entities so nothing is orphaned.
        for (Holo h : holograms.values()) {
            despawn(h.text());
            despawn(h.item());
        }
        holograms.clear();
    }

    private void despawn(UUID uid) {
        if (uid == null) {
            return;
        }
        Entity e = plugin.getServer().getEntity(uid);
        if (e != null) {
            e.remove();
        }
    }

    /** Re-render every display from the current market snapshot. */
    public void tick() {
        version++; // signals map-TV renderers to repaint this cycle
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
                        // MAPTV renders on the map itself (a MapRenderer) — nothing to tick here.
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
        } else if (DisplayDao.MAPTV.equals(d.kind())) {
            despawnDisplayEntitiesFor(d.id()); // sweep any stray v0.16.1 hologram pinned to it
            for (int id : parseMapIds(d.data())) {
                if (id >= 0) {
                    MapView view = mapById(id);
                    if (view != null) {
                        clearRenderers(view);
                    }
                }
            }
        }
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
        Holo h = holograms.get(d.id());
        Entity ent = h != null ? plugin.getServer().getEntity(h.text()) : null;
        boolean itemAlive = h != null && h.item() != null && isAlive(h.item());
        boolean wantItem = plugin.config().displays().hologram().itemDisplay();
        if (ent instanceof TextDisplay td && td.isValid() && (!wantItem || itemAlive)) {
            td.text(holoText(d.itemId()));     // cheap in-place update; item is static
            return;
        }
        // Missing (restart / chunk reload) — respawn if the anchor chunk is loaded.
        World world = plugin.getServer().getWorld(d.world());
        if (world == null || !world.isChunkLoaded(d.x() >> 4, d.z() >> 4)) {
            return;
        }
        removeHologramEntity(d.id()); // clear a half-spawned pair before re-spawning
        spawnHologram(world, d);
    }

    private void spawnHologram(World world, DisplayDao.Display d) {
        PluginConfig.HologramOpts opts = plugin.config().displays().hologram();
        Location textAt = new Location(world, d.x() + 0.5, d.y() + 1.2, d.z() + 0.5);
        float ts = opts.textScale();
        TextDisplay td = world.spawn(textAt, TextDisplay.class, e -> {
            e.text(holoText(d.itemId()));
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setPersistent(false); // we re-spawn from the DB; never saved to chunk data
            e.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(ts, ts, ts), new org.joml.Quaternionf()));
            e.getPersistentDataContainer().set(Keys.DISPLAY_ID, PersistentDataType.LONG, d.id());
        });

        UUID itemUid = null;
        if (opts.itemDisplay()) {
            ItemStack shown = holoItem(d.itemId(), opts);
            if (shown != null) {
                double dy = opts.above() ? 1.75 : 0.75;
                Location itemAt = new Location(world, d.x() + 0.5, d.y() + dy, d.z() + 0.5);
                float is = opts.itemScale();
                ItemDisplay id = world.spawn(itemAt, ItemDisplay.class, e -> {
                    e.setItemStack(shown);
                    e.setBillboard(opts.spin() ? Display.Billboard.FIXED : Display.Billboard.VERTICAL);
                    e.setPersistent(false);
                    e.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(is, is, is), new org.joml.Quaternionf()));
                    e.getPersistentDataContainer().set(Keys.DISPLAY_ID, PersistentDataType.LONG, d.id());
                });
                itemUid = id.getUniqueId();
            }
        }
        holograms.put(d.id(), new Holo(td.getUniqueId(), itemUid));
    }

    private void removeHologramEntity(long id) {
        Holo h = holograms.remove(id);
        if (h != null) {
            despawn(h.text());
            despawn(h.item());
        }
    }

    /** The item shown by a hologram: the config override, else the commodity's own item. */
    private ItemStack holoItem(String itemId, PluginConfig.HologramOpts opts) {
        if (opts.itemOverride() != null && opts.itemOverride().isItem()) {
            return new ItemStack(opts.itemOverride());
        }
        MarketItem item = plugin.market().item(itemId);
        if (item != null && item.material().isItem()) {
            return new ItemStack(item.material());
        }
        return null;
    }

    private boolean isAlive(UUID uid) {
        Entity e = uid != null ? plugin.getServer().getEntity(uid) : null;
        return e != null && e.isValid();
    }

    /** Slow-spin every floating hologram item display a small step; runs on a fast light timer. */
    private void spinItems() {
        if (holograms.isEmpty()) {
            return;
        }
        spinAngle += 0.12f;
        if (spinAngle > (float) (Math.PI * 2)) {
            spinAngle -= (float) (Math.PI * 2);
        }
        // Transformation uses JOML Quaternionf for its rotations; spin about Y.
        org.joml.Quaternionf leftRot = new org.joml.Quaternionf().rotationY(spinAngle);
        for (Holo h : holograms.values()) {
            if (h.item() != null) {
                spinItem(h.item(), leftRot);
            }
        }
    }

    /** Apply the current spin rotation to one ItemDisplay (no-op if it's gone). */
    private void spinItem(UUID uid, org.joml.Quaternionf leftRot) {
        if (plugin.getServer().getEntity(uid) instanceof ItemDisplay id && id.isValid()) {
            org.bukkit.util.Transformation t = id.getTransformation();
            id.setTransformation(new org.bukkit.util.Transformation(
                    t.getTranslation(), leftRot, t.getScale(), t.getRightRotation()));
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
            Holo h = holograms.get(d.id());
            Entity ent = h != null ? plugin.getServer().getEntity(h.text()) : null;
            if (!(ent instanceof TextDisplay td) || !td.isValid()) {
                removeHologramEntity(d.id());
                spawnHologram(chunk.getWorld(), d);
            }
        }
    }

    // ---- Map-TV chart wall ----------------------------------------------------

    /**
     * Build a map-TV bound to a commodity on the item frame the admin is looking at.
     * A {@code cols×rows} grid tiles across neighbouring frames (right + down on the
     * wall); each tile gets a fresh map rendering its window of the shared chart.
     */
    public Result bindMapTv(Player admin, ItemFrame anchor, String itemId, int cols, int rows) {
        if (plugin.market().item(itemId) == null) {
            return Result.fail("Unknown commodity '" + itemId + "'.");
        }
        cols = Math.max(1, Math.min(8, cols));
        rows = Math.max(1, Math.min(8, rows));
        BlockFace facing = anchor.getFacing();
        int[] right = rightStep(facing);
        if (right == null && (cols > 1 || rows > 1)) {
            return Result.fail("A grid needs the map on a vertical wall (N/S/E/W). Try a 1x1.");
        }
        World world = anchor.getWorld();
        Block anchorBlock = anchor.getLocation().getBlock();

        int placed = 0;
        int[] mapIds = new int[cols * rows];
        java.util.Arrays.fill(mapIds, -1);
        for (int gy = 0; gy < rows; gy++) {
            for (int gx = 0; gx < cols; gx++) {
                int bx = anchorBlock.getX() + (right != null ? right[0] * gx : gx);
                int by = anchorBlock.getY() - gy;
                int bz = anchorBlock.getZ() + (right != null ? right[2] * gx : 0);
                ItemFrame frame = frameAt(world, bx, by, bz, facing);
                if (frame == null) {
                    continue; // no frame here — leave that tile blank
                }
                MapView view = Bukkit.createMap(world);
                clearRenderers(view);
                view.setTrackingPosition(false);
                view.addRenderer(new MapTvRenderer(plugin, itemId, gx, gy, cols, rows));
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                if (map.getItemMeta() instanceof MapMeta mm) {
                    mm.setMapView(view);
                    map.setItemMeta(mm);
                }
                frame.setItem(map, false);
                mapIds[gy * cols + gx] = view.getId();
                placed++;
            }
        }
        if (placed == 0) {
            return Result.fail("Look at a framed spot on the wall first (need at least one item frame).");
        }

        try {
            dao.upsert(DisplayDao.MAPTV, anchorBlock.getLocation(), itemId, cols, rows,
                    facing.name(), joinMapIds(mapIds), admin.getUniqueId(), System.currentTimeMillis());
        } catch (SQLException e) {
            return Result.fail("Could not save the map-TV.");
        }
        version++; // force an immediate first paint
        return new Result(true, placed + "/" + (cols * rows) + " tiles");
    }

    /** Re-attach renderers to every persisted map-TV's maps (renderers aren't saved to disk). */
    private void attachMapTvs() {
        List<DisplayDao.Display> maptvs;
        try {
            maptvs = dao.byKind(DisplayDao.MAPTV);
        } catch (SQLException e) {
            return;
        }
        for (DisplayDao.Display d : maptvs) {
            int[] ids = parseMapIds(d.data());
            for (int idx = 0; idx < ids.length; idx++) {
                if (ids[idx] < 0) {
                    continue;
                }
                MapView view = mapById(ids[idx]);
                if (view == null) {
                    continue;
                }
                int gx = d.cols() > 0 ? idx % d.cols() : 0;
                int gy = d.cols() > 0 ? idx / d.cols() : 0;
                clearRenderers(view);
                view.setTrackingPosition(false);
                view.addRenderer(new MapTvRenderer(plugin, d.itemId(), gx, gy, d.cols(), d.rows()));
            }
        }
    }

    // ---- Map-TV interaction: right-click opens the browser chart ---------------

    /** The commodity a map-TV frame is bound to, if this item frame is one of its tiles. */
    public java.util.Optional<String> mapTvCommodityFor(ItemFrame frame) {
        if (frame == null) {
            return java.util.Optional.empty();
        }
        ItemStack item = frame.getItem();
        if (item == null || item.getType() != Material.FILLED_MAP
                || !(item.getItemMeta() instanceof MapMeta mm) || mm.getMapView() == null) {
            return java.util.Optional.empty();
        }
        int mapId = mm.getMapView().getId();
        List<DisplayDao.Display> maptvs;
        try {
            maptvs = dao.byKind(DisplayDao.MAPTV);
        } catch (SQLException e) {
            return java.util.Optional.empty();
        }
        for (DisplayDao.Display d : maptvs) {
            for (int id : parseMapIds(d.data())) {
                if (id == mapId) {
                    return java.util.Optional.of(d.itemId());
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** Right-click a map-TV: hand the player a clickable link to the live dashboard chart. */
    public void openMapTvChart(Player player, String itemId) {
        String url = dashboardUrl(itemId);
        player.sendMessage(Text.of("&b📺 Opening the live chart…"));
        player.sendMessage(Text.link("&a&n▶ Click to open the price chart", url));
    }

    /** The dashboard URL for a commodity: the configured base, deep-linked to the item id. */
    public String dashboardUrl(String itemId) {
        String base = plugin.config().displays().maptv().dashboardUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:" + plugin.config().webDashboard().port();
        }
        base = base.trim();
        if (itemId == null || itemId.isBlank()) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + "item=" + itemId;
    }

    // ---- Owned-display cleanup ------------------------------------------------

    /**
     * Despawn <b>every</b> display entity this plugin owns in loaded chunks — text
     * panels, holograms, and any leaked/stranded {@code ItemDisplay}/{@code TextDisplay}
     * (e.g. the spinning items a past map-TV rework left behind) — and clear our live
     * tracking. Scoped strictly to our own {@code DISPLAY_ID} PDC tag, so it can never
     * touch the arcade kiosk or any unrelated entity. Still-bound displays are re-spawned
     * from the DB on the next refresh tick; true orphans (no binding) simply stay gone.
     * Returns the count removed.
     */
    public int purgeOwnedDisplays() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (isOwnedDisplayEntity(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        holograms.clear(); // drop stale tracking; the tick re-spawns anything still bound
        return removed;
    }

    /** True if this entity carries our {@code DISPLAY_ID} tag (i.e. a plugin-owned display). */
    private boolean isOwnedDisplayEntity(Entity e) {
        return (e instanceof ItemDisplay || e instanceof TextDisplay)
                && e.getPersistentDataContainer().has(Keys.DISPLAY_ID, PersistentDataType.LONG);
    }

    /** Despawn any display entities in loaded chunks tagged to one specific display id. */
    private void despawnDisplayEntitiesFor(long displayId) {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof ItemDisplay) && !(e instanceof TextDisplay)) {
                    continue;
                }
                Long tag = e.getPersistentDataContainer().get(Keys.DISPLAY_ID, PersistentDataType.LONG);
                if (tag != null && tag == displayId) {
                    e.remove();
                }
            }
        }
    }

    /** The horizontal "right" step (unit x/z delta) for a wall the frame faces; null for floor/ceiling. */
    private int[] rightStep(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH -> new int[] {1, 0, 0};  // wall runs east–west
            case EAST, WEST -> new int[] {0, 0, 1};     // wall runs north–south
            default -> null;                            // UP/DOWN (floor/ceiling): single tile only
        };
    }

    private ItemFrame frameAt(World world, int bx, int by, int bz, BlockFace facing) {
        Location center = new Location(world, bx + 0.5, by + 0.5, bz + 0.5);
        for (Entity e : world.getNearbyEntities(center, 0.6, 0.6, 0.6)) {
            if (e instanceof ItemFrame frame && frame.getFacing() == facing) {
                Block b = frame.getLocation().getBlock();
                if (b.getX() == bx && b.getY() == by && b.getZ() == bz) {
                    return frame;
                }
            }
        }
        return null;
    }

    private void clearRenderers(MapView view) {
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
    }

    @SuppressWarnings("deprecation")
    private MapView mapById(int id) {
        return Bukkit.getMap(id);
    }

    private String joinMapIds(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
        }
        return sb.toString();
    }

    private int[] parseMapIds(String data) {
        if (data == null || data.isBlank()) {
            return new int[0];
        }
        String[] parts = data.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = -1;
            }
        }
        return out;
    }
}
