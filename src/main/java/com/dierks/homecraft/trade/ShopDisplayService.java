package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.mini.VendingMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.storage.MiniVendingDao;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shop presentation for Vending Machines (and future stalls):
 * <ul>
 *   <li><b>Upper half as an ItemDisplay</b> — the {@code vending_upper} head item floats
 *       flush on top of the lower head block (no half-block gap), anchored by PDC.</li>
 *   <li><b>Glow</b> — a second, slightly larger "glow shell" display that glows in the
 *       block type's colour, visible only to players within {@code shops.glow.radius}
 *       (per-player visibility, refreshed every 20 ticks; through walls on purpose).</li>
 *   <li><b>Hologram</b> — a TextDisplay above the machine listing up to N items with
 *       prices, refreshed on every listing change.</li>
 *   <li><b>Peek</b> — an action-bar summary of the listings while a player's crosshair
 *       rests on the machine (block or display) within {@code shops.peek.range}.</li>
 * </ul>
 * Entities are non-persistent and tagged with their anchor; missing ones are rebuilt
 * on load / chunk load and orphans (no vending machine at the anchor) are removed.
 */
public final class ShopDisplayService implements Listener {

    private static final class Shop {
        final World world;
        final int x;
        final int y;
        final int z;
        UUID upper;
        UUID glow;
        UUID hologram;

        Shop(World world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Location block() {
            return new Location(world, x, y, z);
        }
    }

    private final HomeCraftManagement plugin;
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<UUID, String> peeking = new HashMap<>();
    private BukkitTask task;
    private BukkitTask peekTask;

    public ShopDisplayService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle ---------------------------------------------------------------

    public void start() {
        stopTasks();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        peekTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::peekTick, 10L, 10L);
    }

    public void stop() {
        stopTasks();
        for (Shop s : shops.values()) {
            removeEntities(s);
        }
        shops.clear();
        for (UUID id : new ArrayList<>(peeking.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendActionBar(Component.empty());
            }
        }
        peeking.clear();
    }

    private void stopTasks() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (peekTask != null) {
            peekTask.cancel();
            peekTask = null;
        }
    }

    /** Rebuild the registry from the placed-block table, sweep orphans, migrate legacy upper heads. */
    public void rebuild() {
        stop();
        start();
        for (PlacedBlock pb : plugin.blockService().findByType(CustomBlockType.MINI_VENDING_MACHINE)) {
            World w = Bukkit.getWorld(pb.world());
            if (w == null) {
                continue;
            }
            register(new Location(w, pb.x(), pb.y(), pb.z()));
        }
        for (World w : Bukkit.getWorlds()) {
            sweepOrphans(w.getEntities());
        }
        for (Shop s : shops.values()) {
            if (s.world.isChunkLoaded(s.x >> 4, s.z >> 4)) {
                migrateLegacyUpperHead(s);
                ensure(s);
            }
        }
    }

    public void register(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        String key = key(loc);
        if (!shops.containsKey(key)) {
            shops.put(key, new Shop(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }
        Shop s = shops.get(key);
        if (s.world.isChunkLoaded(s.x >> 4, s.z >> 4)) {
            ensure(s);
        }
    }

    public void unregister(Location loc) {
        Shop s = shops.remove(key(loc));
        if (s != null) {
            removeEntities(s);
        }
        // Any stragglers with this anchor (e.g. from a crash) go too.
        if (loc.getWorld() != null) {
            String anchor = key(loc);
            for (Entity e : loc.getWorld().getNearbyEntities(loc.toCenterLocation(), 2, 3, 2)) {
                if (anchor.equals(e.getPersistentDataContainer().get(Keys.SHOP_ANCHOR, PersistentDataType.STRING))) {
                    e.remove();
                }
            }
        }
    }

    /** Listings changed: redraw the hologram now. */
    public void refresh(Location loc) {
        Shop s = shops.get(key(loc));
        if (s != null && s.world.isChunkLoaded(s.x >> 4, s.z >> 4)) {
            removeEntity(s.hologram);
            s.hologram = null;
            ensure(s);
        }
    }

    /** Resolve a shop display entity to its machine's block, if it is one. */
    public Optional<Location> anchorOf(Entity entity) {
        String anchor = entity.getPersistentDataContainer().get(Keys.SHOP_ANCHOR, PersistentDataType.STRING);
        if (anchor == null) {
            return Optional.empty();
        }
        String[] p = anchor.split(":");
        if (p.length != 4) {
            return Optional.empty();
        }
        World w = Bukkit.getWorld(p[0]);
        try {
            return w == null ? Optional.empty()
                    : Optional.of(new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ---- entities ----------------------------------------------------------------

    private void ensure(Shop s) {
        PluginConfig.Shops cfg = plugin.config().shops();
        Block block = s.world.getBlockAt(s.x, s.y, s.z);
        float yaw = yawOf(block);
        Location base = new Location(s.world, s.x + 0.5, s.y + 0.75, s.z + 0.5);
        String anchor = key(s.block());

        if (!valid(s.upper)) {
            ItemStack head = Heads.base(plugin.config().skinNamed("vending_upper"));
            s.upper = spawnHead(base, head, yaw, 1.0f, false, null, anchor);
        }
        if (cfg.glowEnabled()) {
            if (!valid(s.glow)) {
                ItemStack head = Heads.base(plugin.config().skinNamed("vending_upper"));
                s.glow = spawnHead(base, head, yaw, 1.03f, true, cfg.glowColor("vending"), anchor);
            }
        } else if (s.glow != null) {
            removeEntity(s.glow);
            s.glow = null;
        }
        if (cfg.hologramEnabled() && cfg.hologramLines() > 0) {
            if (!valid(s.hologram)) {
                s.hologram = spawnHologram(s, anchor);
            }
        } else if (s.hologram != null) {
            removeEntity(s.hologram);
            s.hologram = null;
        }
    }

    private UUID spawnHead(Location at, ItemStack head, float yaw, float scale, boolean glow, String color,
                           String anchor) {
        try {
            ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, disp -> {
                disp.setItemStack(head);
                disp.setBillboard(Display.Billboard.FIXED);
                disp.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f),
                        new Quaternionf().rotationY((float) Math.toRadians(yaw)),
                        new Vector3f(scale, scale, scale), new Quaternionf()));
                disp.setPersistent(false);
                disp.getPersistentDataContainer().set(Keys.SHOP_ANCHOR, PersistentDataType.STRING, anchor);
                disp.getPersistentDataContainer().set(Keys.VENDING_UPPER, PersistentDataType.BYTE, (byte) 1);
                if (glow) {
                    disp.setGlowing(true);
                    disp.setGlowColorOverride(color(color));
                    disp.setVisibleByDefault(false);
                }
            });
            return d.getUniqueId();
        } catch (Throwable t) {
            return null;
        }
    }

    private UUID spawnHologram(Shop s, String anchor) {
        Component text = hologramText(s.block(), plugin.config().shops().hologramLines());
        if (text == null) {
            return null;
        }
        Location at = new Location(s.world, s.x + 0.5, s.y + 1.45, s.z + 0.5);
        try {
            TextDisplay td = s.world.spawn(at, TextDisplay.class, d -> {
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(0x40000000));
                d.setBrightness(new Display.Brightness(15, 15)); // signage is lit, not ambient
                d.setPersistent(false);
                d.getPersistentDataContainer().set(Keys.SHOP_ANCHOR, PersistentDataType.STRING, anchor);
            });
            return td.getUniqueId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** "Piggy Mini ★★ $400" lines, up to {@code lines}, or null for an empty machine. */
    private Component hologramText(Location loc, int lines) {
        List<MiniVendingDao.Listing> listings = plugin.vending().vendingAt(loc);
        if (listings.isEmpty()) {
            return Text.of("&dVending Machine &8· &7empty");
        }
        Component out = Text.of("&dVending Machine");
        int shown = 0;
        for (MiniVendingDao.Listing l : listings) {
            if (shown >= lines) {
                break;
            }
            out = out.append(Component.newline()).append(listingLine(l));
            shown++;
        }
        if (listings.size() > shown) {
            out = out.append(Component.newline())
                    .append(Component.text("+" + (listings.size() - shown) + " more", NamedTextColor.GRAY));
        }
        return out;
    }

    private Component listingLine(MiniVendingDao.Listing l) {
        MiniDef def = plugin.miniService().def(l.miniId());
        ItemStack item = Items.fromBase64(l.itemB64());
        String name = def != null ? def.name() : l.miniId();
        String stars = item != null ? " " + plugin.miniService().gradeOf(item).symbol() : "";
        var color = def != null ? plugin.miniService().style(def.rarity()).nameColor() : NamedTextColor.WHITE;
        return Component.text(name + stars, color).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" " + plugin.economy().format(l.price()), NamedTextColor.GOLD));
    }

    /** "Piggy Mini ★★ $400 · Chick Mini $150 · +3 more" for the action bar. */
    private Component peekText(Location loc) {
        List<MiniVendingDao.Listing> listings = plugin.vending().vendingAt(loc);
        if (listings.isEmpty()) {
            return Text.of("&dVending Machine &7— nothing for sale");
        }
        Component out = Component.empty();
        int shown = 0;
        for (MiniVendingDao.Listing l : listings) {
            if (shown >= 3) {
                break;
            }
            if (shown > 0) {
                out = out.append(Component.text(" · ", NamedTextColor.DARK_GRAY));
            }
            out = out.append(listingLine(l));
            shown++;
        }
        if (listings.size() > shown) {
            out = out.append(Component.text(" · +" + (listings.size() - shown) + " more", NamedTextColor.GRAY));
        }
        return out;
    }

    // ---- ticks -------------------------------------------------------------------

    private void tick() {
        PluginConfig.Shops cfg = plugin.config().shops();
        double r2 = cfg.glowRadius() * cfg.glowRadius();
        for (Shop s : new ArrayList<>(shops.values())) {
            if (!s.world.isChunkLoaded(s.x >> 4, s.z >> 4)) {
                s.upper = null;
                s.glow = null;
                s.hologram = null;
                continue;
            }
            if (plugin.blockService().at(s.block()).isEmpty()) {
                unregister(s.block()); // block gone behind our back
                continue;
            }
            ensure(s);
            if (!cfg.glowEnabled() || !valid(s.glow)) {
                continue;
            }
            Entity glow = Bukkit.getEntity(s.glow);
            Location centre = s.block().toCenterLocation();
            for (Player p : s.world.getPlayers()) {
                boolean near = p.getLocation().distanceSquared(centre) <= r2;
                boolean sees = p.canSee(glow);
                if (near && !sees) {
                    p.showEntity(plugin, glow);
                } else if (!near && sees) {
                    p.hideEntity(plugin, glow);
                }
            }
        }
    }

    private void peekTick() {
        double range = plugin.config().shops().peekRange();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location target = null;
            try {
                Block b = player.getTargetBlockExact((int) Math.ceil(range));
                if (b != null && shops.containsKey(key(b.getLocation()))) {
                    target = b.getLocation();
                } else {
                    Entity e = player.getTargetEntity((int) Math.ceil(range));
                    if (e != null) {
                        target = anchorOf(e).filter(l -> shops.containsKey(key(l))).orElse(null);
                    }
                }
            } catch (Throwable t) {
                target = null;
            }
            String prev = peeking.get(player.getUniqueId());
            if (target == null) {
                if (prev != null) {
                    player.sendActionBar(Component.empty());
                    peeking.remove(player.getUniqueId());
                }
                continue;
            }
            peeking.put(player.getUniqueId(), key(target));
            player.sendActionBar(peekText(target));
        }
        peeking.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    // ---- events ------------------------------------------------------------------

    /** Right-clicking the floating upper half opens the machine below it. */
    @EventHandler(ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Optional<Location> anchor = anchorOf(event.getRightClicked());
        if (anchor.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Location loc = anchor.get();
        Optional<PlacedBlock> placed = plugin.blockService().at(loc);
        if (placed.isEmpty() || placed.get().type() != CustomBlockType.MINI_VENDING_MACHINE) {
            return;
        }
        if (!plugin.sandbox().check(player, "use vending machine")) {
            return;
        }
        if (plugin.config().respectTownPerms() && !plugin.protection().canBuild(player, loc)) {
            player.sendMessage(Text.of("&cYou can't use this here."));
            return;
        }
        boolean owner = placed.get().owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
        new VendingMenu(plugin, player, loc, owner).open(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(Keys.SHOP_ANCHOR, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk c = event.getChunk();
        for (Shop s : shops.values()) {
            if (s.world.equals(c.getWorld()) && (s.x >> 4) == c.getX() && (s.z >> 4) == c.getZ()) {
                migrateLegacyUpperHead(s);
                ensure(s);
            }
        }
        if (plugin.placedMinis() != null) {
            plugin.placedMinis().onChunkLoaded(c.getWorld(), c.getX(), c.getZ());
        }
        if (plugin.pallets() != null) {
            for (PlacedBlock pb : plugin.blockService().findByType(CustomBlockType.PALLET)) {
                if (pb.world().equals(c.getWorld().getName()) && (pb.x() >> 4) == c.getX() && (pb.z() >> 4) == c.getZ()) {
                    plugin.pallets().refreshSkin(new Location(c.getWorld(), pb.x(), pb.y(), pb.z()));
                }
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        sweepOrphans(event.getEntities());
    }

    /** Remove tagged shop entities whose anchor is no longer a vending machine (or that we don't track). */
    private void sweepOrphans(List<Entity> entities) {
        Set<UUID> ours = new HashSet<>();
        for (Shop s : shops.values()) {
            if (s.upper != null) {
                ours.add(s.upper);
            }
            if (s.glow != null) {
                ours.add(s.glow);
            }
            if (s.hologram != null) {
                ours.add(s.hologram);
            }
        }
        for (Entity e : entities) {
            if (!e.getPersistentDataContainer().has(Keys.SHOP_ANCHOR, PersistentDataType.STRING)) {
                continue;
            }
            if (ours.contains(e.getUniqueId())) {
                continue;
            }
            Optional<Location> anchor = anchorOf(e);
            if (anchor.isEmpty() || !shops.containsKey(key(anchor.get()))) {
                e.remove();
                continue;
            }
            // Ours by anchor but not by id (a previous session's entity) — replace cleanly.
            e.remove();
        }
    }

    /** Pass-1 machines carried a real upper head block; it is replaced by the ItemDisplay. */
    private void migrateLegacyUpperHead(Shop s) {
        Block above = s.world.getBlockAt(s.x, s.y + 1, s.z);
        if (plugin.blockService().isVendingUpper(above)) {
            above.setType(Material.AIR, false);
            plugin.getLogger().info("Vending Machine at " + key(s.block()) + ": replaced the legacy upper head block "
                    + "with a floating display.");
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private void removeEntities(Shop s) {
        removeEntity(s.upper);
        removeEntity(s.glow);
        removeEntity(s.hologram);
        s.upper = null;
        s.glow = null;
        s.hologram = null;
    }

    private void removeEntity(UUID id) {
        if (id == null) {
            return;
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
    }

    private static boolean valid(UUID id) {
        if (id == null) {
            return false;
        }
        Entity e = Bukkit.getEntity(id);
        return e != null && e.isValid();
    }

    /** The lower head's facing as a yaw for the display (a wall head or non-head block faces south). */
    private static float yawOf(Block block) {
        if (block.getBlockData() instanceof Rotatable rot) {
            BlockFace f = rot.getRotation();
            return (float) Math.toDegrees(Math.atan2(-f.getModX(), f.getModZ()));
        }
        return 0f;
    }

    private static Color color(String name) {
        if (name == null) {
            return Color.WHITE;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("#") && n.length() == 7) {
            try {
                return Color.fromRGB(Integer.parseInt(n.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return Color.WHITE;
            }
        }
        return switch (n) {
            case "aqua", "cyan" -> Color.AQUA;
            case "gold", "yellow" -> Color.YELLOW;
            case "light_purple", "pink", "magenta" -> Color.FUCHSIA;
            case "purple" -> Color.PURPLE;
            case "green", "lime" -> Color.LIME;
            case "blue" -> Color.BLUE;
            case "red" -> Color.RED;
            case "orange" -> Color.ORANGE;
            default -> Color.WHITE;
        };
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
