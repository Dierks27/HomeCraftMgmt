package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.PlacedMiniDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mini heads placed as ordinary blocks: a registry ({@code placed_minis}) so their
 * per-rarity effects run like a Display Case's, a look-at hologram (name / rarity /
 * grade / mint # / owner) shown only to the player whose crosshair rests on the
 * head within {@code shops.peek.range}, and one guarantee — the exact copy always
 * drops back, whether the head is broken, blown up, pushed or washed away.
 */
public final class PlacedMiniService {

    private static final class Peek {
        String key;
        UUID entity;
    }

    private final HomeCraftManagement plugin;
    private final PlacedMiniDao dao;
    private final Map<String, PlacedMiniDao.Row> placed = new HashMap<>();
    private final Map<UUID, Peek> peeks = new HashMap<>();
    private BukkitTask peekTask;

    public PlacedMiniService(HomeCraftManagement plugin, PlacedMiniDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    // ---- lifecycle ---------------------------------------------------------------

    public void start() {
        stop();
        peekTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::peekTick, 10L, 10L);
    }

    public void stop() {
        if (peekTask != null) {
            peekTask.cancel();
            peekTask = null;
        }
        for (Peek p : peeks.values()) {
            removeEntity(p.entity);
        }
        peeks.clear();
    }

    /** Rebuild from the table, pruning rows whose block is no longer a Mini head, and register effects. */
    public void rebuild() {
        placed.clear();
        List<PlacedMiniDao.Row> rows;
        try {
            rows = dao.all();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load placed Minis: " + e.getMessage());
            return;
        }
        int pruned = 0;
        for (PlacedMiniDao.Row r : rows) {
            World w = Bukkit.getWorld(r.world());
            if (w == null) {
                continue;
            }
            Location loc = new Location(w, r.x(), r.y(), r.z());
            if (w.isChunkLoaded(r.x() >> 4, r.z() >> 4)) {
                ItemStack item = itemAt(loc.getBlock());
                if (item == null) {
                    forget(loc);
                    pruned++;
                    continue;
                }
                plugin.effects().registerPlacedHead(loc, item, false);
            }
            placed.put(key(loc), r);
        }
        if (pruned > 0) {
            plugin.getLogger().info("Placed Minis: pruned " + pruned + " stale row(s).");
        }
    }

    /** Register the placed heads inside a freshly loaded chunk (their effects respawn on demand). */
    public void onChunkLoaded(World world, int cx, int cz) {
        for (PlacedMiniDao.Row r : placed.values()) {
            if (!r.world().equals(world.getName()) || (r.x() >> 4) != cx || (r.z() >> 4) != cz) {
                continue;
            }
            Location loc = new Location(world, r.x(), r.y(), r.z());
            ItemStack item = itemAt(loc.getBlock());
            if (item != null) {
                plugin.effects().registerPlacedHead(loc, item, false);
            }
        }
    }

    // ---- placement / removal -----------------------------------------------------

    /** A Mini head was placed: stamp the full identity on the skull, record it, start its effects. */
    public void onPlaced(Block block, ItemStack item, Player placer) {
        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) {
            return;
        }
        MiniService.MiniRef ref = plugin.miniService().identify(item);
        if (ref == null) {
            return;
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        pdc.set(Keys.MINI_ID, PersistentDataType.STRING, ref.miniId());
        pdc.set(Keys.MINI_UID, PersistentDataType.STRING, ref.uid());
        pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, ref.mintNumber());
        pdc.set(Keys.MINI_GRADE, PersistentDataType.STRING, plugin.miniService().gradeOf(one).name());
        if (plugin.miniService().isShiny(one)) {
            pdc.set(Keys.MINI_FINISH, PersistentDataType.STRING, "SHINY");
        }
        pdc.set(Keys.MINI_OWNER, PersistentDataType.STRING, placer.getUniqueId().toString());
        pdc.set(Keys.MINI_ITEM, PersistentDataType.STRING, Items.toBase64(one));
        skull.update(true, false);

        Location loc = block.getLocation();
        try {
            dao.save(loc, ref.uid(), ref.miniId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to record placed Mini: " + e.getMessage());
        }
        placed.put(key(loc), new PlacedMiniDao.Row(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ(), ref.uid(), ref.miniId()));
        plugin.effects().registerPlacedHead(loc, one, true);
    }

    /**
     * The exact copy a placed head holds, rebuilt from the block PDC: the stored item
     * when present, else re-rendered from uid / mint / grade / finish (never a fresh
     * mint — no tally is touched). Null if the block isn't a Mini head.
     */
    public ItemStack itemAt(Block block) {
        if (!(block.getState() instanceof Skull skull)) {
            return null;
        }
        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        MiniService.MiniRef ref = plugin.miniService().refFrom(pdc);
        if (ref == null) {
            return null;
        }
        ItemStack stored = Items.fromBase64(pdc.get(Keys.MINI_ITEM, PersistentDataType.STRING));
        if (stored != null) {
            plugin.miniService().refreshLegacy(stored);
            return stored;
        }
        MiniDef def = plugin.miniService().def(ref.miniId());
        if (def == null) {
            return null;
        }
        Grade grade = Grade.parse(pdc.get(Keys.MINI_GRADE, PersistentDataType.STRING));
        boolean shiny = "SHINY".equalsIgnoreCase(pdc.get(Keys.MINI_FINISH, PersistentDataType.STRING));
        UUID uid;
        try {
            uid = ref.uid().isBlank() ? UUID.randomUUID() : UUID.fromString(ref.uid());
        } catch (IllegalArgumentException e) {
            uid = UUID.randomUUID();
        }
        return plugin.miniService().rebuild(def, ref.mintNumber(), uid, grade, shiny);
    }

    /** Whether {@code player} may take this head (owner, admin, or allowed to build there). */
    public boolean mayBreak(Player player, Block block) {
        if (player.hasPermission("hcm.admin")) {
            return true;
        }
        if (block.getState() instanceof Skull skull) {
            String owner = skull.getPersistentDataContainer().get(Keys.MINI_OWNER, PersistentDataType.STRING);
            if (owner != null && owner.equals(player.getUniqueId().toString())) {
                return true;
            }
        }
        return !plugin.config().respectTownPerms() || plugin.protection().canBuild(player, block.getLocation());
    }

    /**
     * Take the head out of the world and drop the exact copy at {@code at} (a break,
     * explosion, piston or flowing water). Clears the block when {@code clearBlock}.
     * Returns true if a Mini was dropped.
     */
    public boolean dropAndForget(Block block, boolean clearBlock) {
        ItemStack item = itemAt(block);
        Location loc = block.getLocation();
        forget(loc);
        if (clearBlock) {
            block.setType(Material.AIR, false);
        }
        if (item == null) {
            return false;
        }
        loc.getWorld().dropItemNaturally(loc.toCenterLocation(), item);
        return true;
    }

    /** True if the block is a registered (or PDC-tagged) placed Mini head. */
    public boolean isPlacedMini(Block block) {
        return block != null && block.getState() instanceof Skull skull
                && plugin.miniService().refFrom(skull.getPersistentDataContainer()) != null
                && !skull.getPersistentDataContainer().has(Keys.WILD_SPAWN, PersistentDataType.BYTE);
    }

    private void forget(Location loc) {
        placed.remove(key(loc));
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete placed Mini row: " + e.getMessage());
        }
        plugin.effects().unregisterBlock(loc);
    }

    // ---- look-at hologram --------------------------------------------------------

    private void peekTick() {
        double range = plugin.config().shops().peekRange();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Block target;
            try {
                target = player.getTargetBlockExact((int) Math.ceil(range));
            } catch (Throwable t) {
                target = null;
            }
            String key = target != null && placed.containsKey(key(target.getLocation())) && isPlacedMini(target)
                    ? key(target.getLocation()) : null;
            Peek peek = peeks.get(player.getUniqueId());
            if (key == null) {
                if (peek != null) {
                    removeEntity(peek.entity);
                    peeks.remove(player.getUniqueId());
                }
                continue;
            }
            if (peek != null && key.equals(peek.key)) {
                Entity e = peek.entity == null ? null : Bukkit.getEntity(peek.entity);
                if (e != null && e.isValid()) {
                    continue; // still looking at the same head
                }
            }
            if (peek != null) {
                removeEntity(peek.entity);
            }
            Peek np = new Peek();
            np.key = key;
            np.entity = spawnPeek(player, target);
            peeks.put(player.getUniqueId(), np);
        }
        // Drop peeks of players who left.
        peeks.entrySet().removeIf(en -> {
            if (Bukkit.getPlayer(en.getKey()) == null) {
                removeEntity(en.getValue().entity);
                return true;
            }
            return false;
        });
    }

    private UUID spawnPeek(Player viewer, Block block) {
        ItemStack item = itemAt(block);
        MiniService.MiniRef ref = plugin.miniService().identify(item);
        MiniDef def = ref == null ? null : plugin.miniService().def(ref.miniId());
        if (def == null) {
            return null;
        }
        Grade grade = plugin.miniService().gradeOf(item);
        boolean shiny = plugin.miniService().isShiny(item);
        String ownerName = "—";
        if (block.getState() instanceof Skull skull) {
            String owner = skull.getPersistentDataContainer().get(Keys.MINI_OWNER, PersistentDataType.STRING);
            if (owner != null) {
                try {
                    String n = Bukkit.getOfflinePlayer(UUID.fromString(owner)).getName();
                    ownerName = n != null ? n : owner.substring(0, 8);
                } catch (IllegalArgumentException ignored) {
                    // malformed owner id
                }
            }
        }
        var style = plugin.miniService().style(def.rarity());
        Component text = Component.text(def.name() + " " + grade.symbol(), style.nameColor())
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.newline())
                .append(Component.text(def.rarity().name(), style.nameColor()))
                .append(Component.text("  ·  " + grade.display() + (shiny ? "  ✦ Shiny" : ""), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Mint #" + ref.mintNumber() + (def.uncapped() ? "" : " of " + def.cap())
                        + "  ·  " + ownerName, NamedTextColor.GRAY));
        Location at = block.getLocation().toCenterLocation().add(0, 0.95, 0);
        try {
            TextDisplay td = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(0x60000000));
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.getPersistentDataContainer().set(Keys.EFFECT_ENTITY, PersistentDataType.BYTE, (byte) 1);
                d.getPersistentDataContainer().set(Keys.PEEK_VIEWER, PersistentDataType.STRING,
                        viewer.getUniqueId().toString());
            });
            viewer.showEntity(plugin, td);
            return td.getUniqueId();
        } catch (Throwable t) {
            return null;
        }
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

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
