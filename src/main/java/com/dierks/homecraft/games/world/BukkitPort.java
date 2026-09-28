package com.dierks.homecraft.games.world;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The server side of the world-session state machine: {@link SessionCore.Port} over Bukkit
 * players, worlds, the scheduler and the saved-state adapter.
 *
 * <p>There is ONE per plugin, shared on purpose. Recovery must work with the games module on, off
 * or broken ({@link SessionRecoveryListener} is built from the database alone), and the world
 * sessions must see the same "who has a live row" set and the same in-memory sessions — so both
 * reach the one instance through {@link #of}. A new plugin instance (a reload), or the same one
 * enabled again after a disable, gets a new one.
 *
 * <p>Every async completion comes back on the main thread through the scheduler, and only while
 * the plugin is enabled; nothing here ever throws into Bukkit.
 */
final class BukkitPort implements SessionCore.Port<Player, ItemStack> {

    private static BukkitPort shared;

    private final HomeCraftManagement plugin;
    private final BukkitStateAdapter state = new BukkitStateAdapter();
    /** When each player was last hurt (server tick), for "Stand still and safe". */
    private final Map<UUID, Long> hurt = new HashMap<>();
    /**
     * {@link SessionCore.Port#mark}: in the player's PersistentDataContainer, which
     * {@link Player#saveData} writes into the player's own data file together with their inventory,
     * so after a crash it describes the inventory the player comes back with. It is only ever set
     * in the session world (at the clear and at the restore), so wherever it is read it describes
     * that world's inventory as last saved, whatever Multiverse-Inventories group the player is in.
     */
    private final NamespacedKey markKey;
    private SessionCore<Player, ItemStack> core;
    /** Our plugin is being disabled: from here on nothing may teleport or schedule. */
    private boolean disabling;

    private BukkitPort(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "games_session_mark");
    }

    /** The plugin's one port (and state machine), built on first use from the database alone. */
    static synchronized BukkitPort of(HomeCraftManagement plugin) {
        if (shared == null || shared.plugin != plugin || shared.disabling) {
            BukkitPort port = new BukkitPort(plugin);
            port.core = new SessionCore<>(new GamesDao(plugin.database()), port, plugin.getLogger());
            shared = port;
        }
        return shared;
    }

    SessionCore<Player, ItemStack> core() {
        return core;
    }

    HomeCraftManagement plugin() {
        return plugin;
    }

    /** Whether the game-mode change under way for this player is one of ours. */
    boolean ownModeChange(UUID player) {
        return state.ownModeChange(player);
    }

    /** Set ADVENTURE again as our own change (the game-mode guard). */
    void adventure(Player p) {
        state.gameMode(p, org.bukkit.GameMode.ADVENTURE);
    }

    /** The plugin is being disabled (PluginDisableEvent, just before onDisable). */
    void disabling() {
        disabling = true;
    }

    /** A player was hurt (the damage tracker). */
    void hurt(UUID player) {
        hurt.put(player, (long) Bukkit.getCurrentTick());
    }

    /** Drop what we remember about a player who left. */
    void forget(UUID player) {
        hurt.remove(player);
    }

    /** Run {@code task}, logging instead of throwing: nothing here may reach Bukkit as an exception. */
    void safely(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Games world sessions: " + what + " failed", e);
        }
    }

    static Place place(Location l) {
        if (l == null || l.getWorld() == null) {
            return null;
        }
        return new Place(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    static Location location(Place p) {
        if (p == null) {
            return null;
        }
        World w = Bukkit.getWorld(p.world());
        return w == null ? null : new Location(w, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
    }

    // ---- SessionCore.Port ---------------------------------------------------------------------------

    @Override
    public UUID id(Player p) {
        return p.getUniqueId();
    }

    @Override
    public String name(Player p) {
        return p.getName();
    }

    @Override
    public boolean online(Player p) {
        return p.isOnline();
    }

    @Override
    public boolean dead(Player p) {
        return p.isDead() || p.getHealth() <= 0;
    }

    @Override
    public String world(Player p) {
        return p.getWorld().getName();
    }

    @Override
    public Place location(Player p) {
        return place(p.getLocation());
    }

    @Override
    public boolean worldExists(String world) {
        return world != null && Bukkit.getWorld(world) != null;
    }

    @Override
    public Place spawn(String world) {
        World w = world == null ? null : Bukkit.getWorld(world);
        return w == null ? null : place(w.getSpawnLocation());
    }

    @Override
    public Place mainSpawn() {
        return place(Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    @Override
    public boolean gamesWorld(String world) {
        if (world == null) {
            return false;
        }
        for (String w : plugin.config().games().common().worlds()) {
            if (w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean loaded(Place place) {
        World w = place == null ? null : Bukkit.getWorld(place.world());
        return w != null && w.isChunkLoaded((int) Math.floor(place.x()) >> 4, (int) Math.floor(place.z()) >> 4);
    }

    @Override
    public long tick() {
        return Bukkit.getCurrentTick();
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean stopping() {
        return disabling || Bukkit.isStopping() || !plugin.isEnabled();
    }

    @Override
    public void later(long ticks, Runnable task) {
        if (disabling || !plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> safely("a scheduled step", task), Math.max(0, ticks));
        } catch (IllegalPluginAccessException e) {
            // disabling: the next start or join finishes whatever this was
        }
    }

    @Override
    public void teleport(Player p, Place to, Consumer<Boolean> done) {
        Location loc = location(to);
        if (loc == null) {
            later(0, () -> done.accept(false));
            return;
        }
        try {
            p.teleportAsync(loc, TeleportCause.PLUGIN).whenComplete((ok, err) -> {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Games: a teleport of " + p.getName() + " failed", err);
                }
                boolean arrived = err == null && Boolean.TRUE.equals(ok);
                later(0, () -> done.accept(arrived)); // back on the main thread, only while enabled
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Games: could not start a teleport of " + p.getName(), e);
            later(0, () -> done.accept(false));
        }
    }

    @Override
    public boolean teleportNow(Player p, Place to) {
        Location loc = location(to);
        try {
            return loc != null && p.teleport(loc, TeleportCause.PLUGIN);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Games: a teleport of " + p.getName() + " failed", e);
            return false;
        }
    }

    @Override
    public SessionCore.Standing standing(Player p, String gameId) {
        Entity vehicle = p.getVehicle();
        boolean ownBoat = vehicle != null && gameId != null && gameId.equals(WorldEntities.gameId(vehicle))
                && p.getUniqueId().equals(WorldEntities.owner(vehicle));
        InventoryView view = p.getOpenInventory();
        InventoryType type = view.getType();
        boolean otherScreen = type != InventoryType.CRAFTING && type != InventoryType.CREATIVE
                && !(view.getTopInventory().getHolder(false) instanceof Menu);
        Long hurtAt = hurt.get(p.getUniqueId());
        long sinceHurt = hurtAt == null ? Long.MAX_VALUE : Bukkit.getCurrentTick() - hurtAt;
        Entity body = p; // Player#isOnGround is deprecated (the client says it); good enough for a refusal
        return new SessionCore.Standing(p.isDead(), p.isSleeping(), p.isGliding(), vehicle != null, ownBoat,
                otherScreen, p.getFallDistance(), body.isOnGround(), p.getFireTicks(), p.isInLava(), p.isInWater(),
                sinceHurt);
    }

    @Override
    public void closeInventory(Player p) {
        p.closeInventory();
    }

    @Override
    public boolean handsFree(Player p) {
        return BukkitStateAdapter.handsFree(p);
    }

    @Override
    public SavedState capture(Player p, String sessionId, String gameId, String ref, String sessionWorld, Place from,
                              long now) {
        return state.capture(p, sessionId, gameId, ref, sessionWorld, from, now);
    }

    @Override
    public SessionCore.Restore<Player> prepare(SavedState s) {
        return state.prepare(s);
    }

    @Override
    public void clearForGame(Player p) {
        state.clearForGame(p);
    }

    @Override
    public List<ItemStack> takeExtras(Player p) {
        return BukkitStateAdapter.takeExtras(p);
    }

    @Override
    public void discardHeld(Player p) {
        BukkitStateAdapter.discardHeld(p);
    }

    @Override
    public void stripKit(Player p) {
        int removed = BukkitStateAdapter.stripKit(p);
        if (removed > 0) {
            plugin.getLogger().info("Games: removed " + removed + " game item(s) from " + p.getName()
                    + " outside a game.");
        }
    }

    @Override
    public List<ItemStack> give(Player p, List<ItemStack> items) {
        return BukkitStateAdapter.give(p, items);
    }

    @Override
    public int room(Player p, List<ItemStack> items) {
        return BukkitStateAdapter.room(p, items);
    }

    @Override
    public String mark(Player p) {
        try {
            return p.getPersistentDataContainer().get(markKey, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Games: could not read " + p.getName() + "'s game mark", e);
            return null; // as if unmarked: a crash-join is then overwrite-only, as before the mark
        }
    }

    @Override
    public void setMark(Player p, String mark) {
        try {
            if (mark == null) {
                p.getPersistentDataContainer().remove(markKey);
            } else {
                p.getPersistentDataContainer().set(markKey, PersistentDataType.STRING, mark);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Games: could not mark " + p.getName() + "'s data", e);
        }
    }

    @Override
    public byte[] encode(List<ItemStack> items) {
        return BukkitStateAdapter.encode(items);
    }

    @Override
    public List<ItemStack> decode(byte[] blob) {
        return BukkitStateAdapter.decode(blob);
    }

    @Override
    public String describe(ItemStack item) {
        return BukkitStateAdapter.describe(item);
    }

    @Override
    public void drop(Player p, List<ItemStack> items) {
        for (ItemStack i : items) {
            if (!BukkitStateAdapter.empty(i)) {
                p.getWorld().dropItem(p.getLocation(), i);
            }
        }
    }

    @Override
    public void save(Player p) {
        try {
            p.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Games: could not save " + p.getName() + "'s data", e);
        }
    }

    @Override
    public void dismount(Player p) {
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
    }

    @Override
    public void removeGameVehicle(Player p) {
        Entity vehicle = p.getVehicle();
        if (vehicle == null) {
            return;
        }
        boolean ours = WorldEntities.gameId(vehicle) != null && p.getUniqueId().equals(WorldEntities.owner(vehicle));
        vehicle.removePassenger(p);
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        if (ours) {
            vehicle.remove(); // never dropped as an item
        }
    }

    @Override
    public void tell(Player p, String line) {
        if (line == null || line.isEmpty() || !p.isOnline()) {
            return;
        }
        p.sendMessage(Text.of(line));
        if (line.toLowerCase(Locale.ROOT).startsWith("&c")) {
            Sounds.refused(p);
        }
    }
}
