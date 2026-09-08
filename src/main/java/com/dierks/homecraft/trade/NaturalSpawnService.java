package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.storage.MiniSpawnDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The NATURAL_SPAWN trigger: every {@code minis.loot.natural.interval_ticks}, for each
 * online player, each NATURAL_SPAWN loot source rolls its chance; on a hit a Mini is
 * minted (unowned, cap-aware) and placed as a head on a random surface spot 24–48
 * blocks away that the player could build on. Touching or breaking it hands the
 * exact copy to the finder (with the found-broadcast); untouched, it despawns after
 * {@code despawn_minutes} and the copy is retired. Spawns persist in
 * {@code mini_spawns} so lifetimes survive restarts and nothing leaks.
 */
public final class NaturalSpawnService {

    private static final BlockFace[] FACINGS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final HomeCraftManagement plugin;
    private final MiniSpawnDao dao;
    private final Map<String, MiniSpawnDao.Spawn> live = new HashMap<>();
    private BukkitTask spawnTask;
    private BukkitTask expireTask;

    public NaturalSpawnService(HomeCraftManagement plugin, MiniSpawnDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    // ---- lifecycle ---------------------------------------------------------------

    /** (Re)arm the spawn + expiry timers at the configured cadence. */
    public void start() {
        stop();
        Loot.Natural n = plugin.config().miniLoot().natural();
        long interval = Math.max(200, n.intervalTicks());
        spawnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::spawnTick, interval, interval);
        expireTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireTick, 200L, 200L);
    }

    public void stop() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
    }

    /** Reload live spawns from the datastore and re-register their effects. */
    public void rebuild() {
        live.clear();
        List<MiniSpawnDao.Spawn> rows;
        try {
            rows = dao.all();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load natural Mini spawns: " + e.getMessage());
            return;
        }
        for (MiniSpawnDao.Spawn s : rows) {
            World w = Bukkit.getWorld(s.world());
            if (w == null) {
                continue;
            }
            Location loc = new Location(w, s.x(), s.y(), s.z());
            if (w.isChunkLoaded(s.x() >> 4, s.z() >> 4) && !isWild(loc.getBlock())) {
                // The head is gone (edited away while we were off) — retire the copy.
                forget(loc, s, true);
                continue;
            }
            live.put(key(loc), s);
            ItemStack item = Items.fromBase64(s.itemB64());
            if (item != null) {
                plugin.effects().registerWild(loc, item, false);
            }
        }
    }

    // ---- spawning ----------------------------------------------------------------

    private void spawnTick() {
        Loot.MiniLoot loot = plugin.config().miniLoot();
        List<Loot.LootSource> sources = loot.sourcesFor(Loot.Trigger.NATURAL_SPAWN);
        if (sources.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            GameMode gm = player.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) {
                continue;
            }
            if (!plugin.sandbox().allowed(player.getWorld())) {
                continue;
            }
            for (Loot.LootSource source : sources) {
                if (source.chancePercent() <= 0
                        || ThreadLocalRandom.current().nextDouble() * 100.0 >= source.chancePercent()) {
                    continue;
                }
                if (spawnFor(player, source)) {
                    break; // at most one spawn per player per tick
                }
            }
        }
    }

    private boolean spawnFor(Player player, Loot.LootSource source) {
        MiniDef def = plugin.wildDrops().pick(source);
        if (def == null || def.type() != MiniType.HEAD) {
            return false; // only head Minis can sit in the world as a block
        }
        Location spot = findSpot(player, plugin.config().miniLoot().natural());
        if (spot == null) {
            return false;
        }
        Grade grade = plugin.wildDrops().rollGrade(source, def);
        boolean shiny = plugin.wildDrops().rollShiny(source);
        MiniService.Minted m = plugin.miniService().mintItem(def, grade, shiny, null);
        if (!m.ok()) {
            return false;
        }
        Block block = spot.getBlock();
        if (!placeHead(block, m.item())) {
            plugin.miniService().retire(m.item());
            return false;
        }
        long now = System.currentTimeMillis();
        long expires = now + plugin.config().miniLoot().natural().despawnMinutes() * 60_000L;
        try {
            dao.insert(spot, def.id(), m.uid(), m.mintNumber(), Items.toBase64(m.item()), now, expires);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to persist natural Mini spawn: " + e.getMessage());
        }
        live.put(key(spot), new MiniSpawnDao.Spawn(0, spot.getWorld().getName(), spot.getBlockX(), spot.getBlockY(),
                spot.getBlockZ(), def.id(), m.uid(), m.mintNumber(), Items.toBase64(m.item()), now, expires));
        plugin.effects().registerWild(spot, m.item(), true);
        plugin.announce().spawnHint(def.rarity());
        return true;
    }

    /**
     * A random surface spot {@code minDistance}–{@code maxDistance} blocks from the player:
     * solid ground, two air blocks above, no liquid, not a custom block, and (when town
     * perms are respected) somewhere the player is allowed to build.
     */
    private Location findSpot(Player player, Loot.Natural n) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist = n.minDistance() + ThreadLocalRandom.current().nextDouble() * (n.maxDistance() - n.minDistance());
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block ground = world.getHighestBlockAt(x, z);
            if (ground.getY() <= world.getMinHeight() || ground.getY() >= world.getMaxHeight() - 2) {
                continue;
            }
            if (!ground.getType().isSolid() || ground.isLiquid() || ground.getType() == Material.LIGHT) {
                continue;
            }
            Block spot = ground.getRelative(BlockFace.UP);
            if (!spot.getType().isAir() || !spot.getRelative(BlockFace.UP).getType().isAir()) {
                continue;
            }
            if (spot.isLiquid() || spot.getRelative(BlockFace.UP).isLiquid()) {
                continue;
            }
            Location loc = spot.getLocation();
            if (plugin.config().respectTownPerms() && !plugin.protection().canBuild(player, loc)) {
                continue;
            }
            if (plugin.blockService().at(loc).isPresent()) {
                continue;
            }
            return loc;
        }
        return null;
    }

    /** Place the Mini's head at the block, textured from the item and tagged as a claimable wild spawn. */
    private boolean placeHead(Block block, ItemStack item) {
        try {
            block.setType(Material.PLAYER_HEAD, false);
            BlockData data = block.getBlockData();
            if (data instanceof Rotatable rot) {
                rot.setRotation(FACINGS[ThreadLocalRandom.current().nextInt(FACINGS.length)]);
                block.setBlockData(rot, false);
            }
            BlockState state = block.getState();
            if (!(state instanceof Skull skull)) {
                return false;
            }
            if (item.getItemMeta() instanceof SkullMeta meta && meta.getPlayerProfile() != null) {
                skull.setPlayerProfile(meta.getPlayerProfile());
            }
            MiniService.MiniRef ref = plugin.miniService().identify(item);
            PersistentDataContainer pdc = skull.getPersistentDataContainer();
            if (ref != null) {
                pdc.set(Keys.MINI_ID, PersistentDataType.STRING, ref.miniId());
                pdc.set(Keys.MINI_UID, PersistentDataType.STRING, ref.uid());
                pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, ref.mintNumber());
            }
            pdc.set(Keys.MINI_ITEM, PersistentDataType.STRING, Items.toBase64(item));
            pdc.set(Keys.WILD_SPAWN, PersistentDataType.BYTE, (byte) 1);
            skull.update(true, false);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not place a wild Mini head: " + t.getMessage());
            return false;
        }
    }

    // ---- claiming + expiry -------------------------------------------------------

    /** True if the block is a naturally spawned, still-claimable wild Mini head. */
    public boolean isWild(Block block) {
        return block.getState() instanceof Skull skull
                && skull.getPersistentDataContainer().has(Keys.WILD_SPAWN, PersistentDataType.BYTE);
    }

    /**
     * Hand the wild Mini at {@code block} to {@code player}: gives the exact copy, records
     * ownership, clears the spawn + effects, announces the find. {@code removeBlock}
     * clears the head (false when a BlockBreakEvent is already removing it).
     *
     * @return true if a Mini was claimed.
     */
    public boolean claim(Player player, Block block, boolean removeBlock) {
        if (!(block.getState() instanceof Skull skull)) {
            return false;
        }
        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        if (!pdc.has(Keys.WILD_SPAWN, PersistentDataType.BYTE)) {
            return false;
        }
        ItemStack item = Items.fromBase64(pdc.get(Keys.MINI_ITEM, PersistentDataType.STRING));
        Location loc = block.getLocation();
        MiniSpawnDao.Spawn spawn = live.get(key(loc));
        if (spawn == null) {
            try {
                spawn = dao.at(loc).orElse(null);
            } catch (SQLException ignored) {
                spawn = null;
            }
        }
        if (item == null && spawn != null) {
            item = Items.fromBase64(spawn.itemB64());
        }
        if (removeBlock) {
            block.setType(Material.AIR, false);
        }
        forget(loc, spawn, false);
        if (item == null) {
            return false;
        }
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        MiniService.MiniRef ref = plugin.miniService().identify(item);
        if (ref != null && !ref.uid().isBlank()) {
            plugin.miniService().transferOwner(ref.uid(), player.getUniqueId());
        }
        if (plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_mini");
        }
        MiniDef def = ref == null ? null : plugin.miniService().def(ref.miniId());
        if (def != null) {
            Grade grade = plugin.miniService().gradeOf(item);
            player.sendMessage(Text.of("&b✦ You found a wild &f" + def.name() + " " + grade.symbol()
                    + (plugin.miniService().isShiny(item) ? " &f✦Shiny" : "") + "&b! &7(Mint #" + ref.mintNumber() + ")"));
            plugin.announce().found(player, def, item, Loot.Trigger.NATURAL_SPAWN.verb());
        }
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        } catch (Throwable ignored) {
            // cosmetic
        }
        return true;
    }

    private void expireTick() {
        long now = System.currentTimeMillis();
        for (MiniSpawnDao.Spawn s : new ArrayList<>(live.values())) {
            if (s.expiresAt() > now) {
                continue;
            }
            World w = Bukkit.getWorld(s.world());
            if (w == null) {
                continue;
            }
            Location loc = new Location(w, s.x(), s.y(), s.z());
            Block block = loc.getBlock();
            if (isWild(block)) {
                block.setType(Material.AIR, false);
            }
            forget(loc, s, true);
            MiniDef def = plugin.miniService().def(s.miniId());
            plugin.announce().slippedAway(def != null ? def.rarity() : com.dierks.homecraft.mini.Rarity.COMMON);
        }
    }

    /** Drop a spawn from the registry/table + effects; {@code retire} also burns the copy. */
    private void forget(Location loc, MiniSpawnDao.Spawn spawn, boolean retire) {
        live.remove(key(loc));
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete natural Mini spawn: " + e.getMessage());
        }
        plugin.effects().unregisterBlock(loc);
        if (retire && spawn != null) {
            ItemStack item = Items.fromBase64(spawn.itemB64());
            if (item != null) {
                plugin.miniService().retire(item);
            }
        }
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
