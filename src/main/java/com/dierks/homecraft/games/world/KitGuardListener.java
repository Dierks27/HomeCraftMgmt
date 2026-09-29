package com.dierks.homecraft.games.world;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.gui.games.GameScreen;
import com.dierks.homecraft.util.Text;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world games' guard (spec §7.2 "During", §7.4, R2.1, R2.5, R2.7-R2.10, R3.10): what keeps a
 * session a sandbox, and the kit inside it.
 *
 * <p><b>For a player in a session</b> (any phase): no damage, hunger, fire or death (a death is
 * cancelled and the player revived, then leaves normally next tick); no pickups of items, arrows
 * or XP; no drops (on the way in, only a kit item's: closing a full inventory may have to drop
 * what was held, and a cancelled drop there is lost), no inventory moves (only games screens take
 * clicks), no opening any other inventory or HomeCraft screen (nothing may add items mid-game);
 * no swapping hands, placing, buckets, eating, throwing, portals, or using blocks and entities
 * (except their own boat);
 * no knockback, no effect from anything but the game or an admin's command (a splash potion, a
 * cloud, an arrow, a beacon), and no being reeled in by someone's fishing rod — a bystander can
 * neither spoil a run nor carry it across a gap;
 * foreign game-mode changes are cancelled and ADVENTURE re-asserted (Multiverse-Core re-applies a
 * world's mode after every world change, so this must never END a session); flight is switched
 * off again. Teleports are sorted into ours, harmless and the end (R2.8); a world change some
 * other way is caught at LOWEST, before Multiverse-Inventories swaps; the void sends them back.
 *
 * <p><b>Kit clicks</b> are owned here: HIGH without ignoreCancelled (air clicks arrive already
 * cancelled), main hand only, any click, one per tick; the click is cancelled and the game hears
 * it through {@link Game#onKitUse}. A rocket while gliding goes through; "Leave game" needs a
 * second click within 3 seconds.
 *
 * <p><b>For everyone</b>, while games are on: a kit item can't be spawned as an item, picked up by
 * a hopper or a mob, moved by someone else (an admin's inventory view), dropped, placed or used in
 * an anvil, smithing table or grindstone; a stray one is deleted on sight. Game entities can't be
 * hurt, broken, entered or dressed by anyone but their owner, and any that load with a chunk are
 * leftovers and removed.
 *
 * <p>Every handler is O(1) when nobody is in a session, and nothing here ever throws.
 */
public final class KitGuardListener implements Listener {

    /** "Leave game": the second click must come within this long, and not in the same breath. */
    static final long LEAVE_WINDOW_MS = 3_000;
    static final long LEAVE_MIN_MS = 250;
    /**
     * Where an effect on a player in a game may come from: the game itself (a plugin) and an
     * admin's {@code /effect}, which a game that cares sees for itself. Everything else — a splash
     * potion, a cloud, an arrow, a beacon, a conduit, a mob — is the world or someone else.
     */
    static final Set<EntityPotionEffectEvent.Cause> OWN_EFFECTS =
            EnumSet.of(EntityPotionEffectEvent.Cause.PLUGIN, EntityPotionEffectEvent.Cause.COMMAND);

    private final GamesService games;
    private final WorldSessions sessions;
    private final BukkitPort port;
    private final SessionCore<Player, ItemStack> core;
    private final Map<UUID, Long> leaveClicks = new HashMap<>();
    private final Map<UUID, Long> kitTick = new HashMap<>();

    KitGuardListener(GamesService games, WorldSessions sessions, BukkitPort port) {
        this.games = games;
        this.sessions = sessions;
        this.port = port;
        this.core = port.core();
    }

    // ---- who is who -----------------------------------------------------------------------------

    private boolean inSession(Entity e) {
        return e instanceof Player p && !core.none() && core.phase(p.getUniqueId()) != null;
    }

    private boolean playing(Player p) {
        return !core.none() && core.phase(p.getUniqueId()) == Session.Phase.ACTIVE;
    }

    /** Games on: the checks that cover everyone (kit items anywhere, game entities) run. */
    private boolean on() {
        return games.config().enabled() || !core.none();
    }

    private void safely(String what, Runnable r) {
        port.safely(what, r);
    }

    private static void deny(PlayerInteractEvent e) {
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setCancelled(true);
    }

    // ---- kit clicks (R3.10) ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onKitClick(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || !on() || !KitItems.isKit(e.getItem())) {
            return;
        }
        safely("a game item click", () -> kitClick(e));
    }

    private void kitClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            deny(e); // the client repeats a click for the off hand: only the main hand counts
            return;
        }
        Session s = core.session(p.getUniqueId());
        String gameId = KitItems.gameId(item);
        String action = KitItems.action(item);
        if (s == null) {
            deny(e);
            port.stripKit(p); // a kit item outside a game is always removed
            return;
        }
        if (!s.gameId().equals(gameId)) {
            deny(e);
            p.getInventory().setItemInMainHand(null); // another game's: not this one's to use
            return;
        }
        if (s.phase() != Session.Phase.ACTIVE) {
            deny(e);
            return;
        }
        if ("firework".equals(action) && p.isGliding()) {
            return; // the boost goes through
        }
        deny(e);
        long tick = port.tick();
        Long last = kitTick.put(p.getUniqueId(), tick);
        if (last != null && last == tick) {
            return; // one click can arrive twice (block, then air); it counts once
        }
        if ("leave".equals(action)) {
            leaveClick(p);
            return;
        }
        Game game = games.game(gameId);
        if (game != null) {
            Action a = e.getAction();
            sessions.kitUse(p, game, action, a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK);
        }
    }

    private void leaveClick(Player p) {
        long now = System.currentTimeMillis();
        Long first = leaveClicks.get(p.getUniqueId());
        if (first != null && now - first >= LEAVE_MIN_MS && now - first <= LEAVE_WINDOW_MS) {
            leaveClicks.remove(p.getUniqueId());
            sessions.leave(p, EndReason.QUIT_ITEM);
            return;
        }
        if (first == null || now - first > LEAVE_WINDOW_MS) {
            leaveClicks.put(p.getUniqueId(), now);
        }
        p.sendActionBar(Text.of("&eClick again to leave the game"));
    }

    // ---- death (R2.1) ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        if (core.none()) {
            return;
        }
        safely("a game death", () -> {
            Player p = e.getPlayer();
            List<ItemStack> keep = new ArrayList<>();
            for (ItemStack i : e.getDrops()) {
                if (!BukkitStateAdapter.empty(i) && !KitItems.isKit(i)) {
                    keep.add(i.clone());
                }
            }
            if (!core.dying(p, keep)) {
                return;
            }
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            e.setCancelled(true);
            e.setReviveHealth(max == null ? 20.0 : Math.max(1.0, max.getValue()));
            e.getDrops().clear();
            e.setDroppedExp(0);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathDone(PlayerDeathEvent e) {
        if (core.none()) {
            return;
        }
        safely("a game death", () -> {
            if (!e.isCancelled() && inSession(e.getPlayer())) {
                e.getDrops().removeIf(KitItems::isKit); // the kit never drops, whatever else happened
            }
            core.died(e.getPlayer(), e.isCancelled());
        });
    }

    // ---- protection -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (!on()) {
            return;
        }
        safely("a game damage check", () -> {
            Entity victim = e.getEntity();
            if (inSession(victim) || WorldEntities.gameId(victim) != null) {
                e.setCancelled(true);
                return;
            }
            if (e instanceof EntityDamageByEntityEvent by && inSession(attacker(by.getDamager()))) {
                e.setCancelled(true); // a player in a game can't hurt anyone either
            }
        });
    }

    private static Entity attacker(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : null;
        }
        return damager;
    }

    /** "Stand still and safe": remember when each player was last hurt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && games.config().enabled()) {
            port.hurt(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (inSession(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(EntityCombustEvent e) {
        if (inSession(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    // ---- bystanders: no effects, knockback or rods from outside the game --------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEffect(EntityPotionEffectEvent e) {
        if (inSession(e.getEntity()) && fromOutside(e.getCause(), e.getAction())) {
            e.setCancelled(true);
        }
    }

    /** Whether an effect being given comes from outside the game (taking one away is always fine). */
    static boolean fromOutside(EntityPotionEffectEvent.Cause cause, EntityPotionEffectEvent.Action action) {
        boolean gives = action == EntityPotionEffectEvent.Action.ADDED
                || action == EntityPotionEffectEvent.Action.CHANGED;
        return gives && !OWN_EFFECTS.contains(cause);
    }

    /** Every knockback — a wind charge, an explosion, a hit: the games themselves never knock anyone back. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onKnockback(EntityKnockbackEvent e) {
        if (inSession(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent e) {
        if (reelsIn(e.getState()) && inSession(e.getCaught())) {
            e.setCancelled(true);
        }
    }

    /** Whether a rod is pulling what it hooked towards the angler. */
    static boolean reelsIn(PlayerFishEvent.State state) {
        return state == PlayerFishEvent.State.CAUGHT_ENTITY;
    }

    // ---- items in and out -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (!on()) {
            return;
        }
        safely("a game pickup check", () -> {
            if (inSession(e.getEntity())) {
                e.setCancelled(true);
            } else if (KitItems.isKit(e.getItem().getItemStack())) {
                e.setCancelled(true);
                e.getItem().remove();
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupArrow(PlayerPickupArrowEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupXp(PlayerPickupExperienceEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (!on()) {
            return;
        }
        safely("a game drop check", () -> {
            Session.Phase phase = core.none() ? null : core.phase(e.getPlayer().getUniqueId());
            // Not while ENTERING: the kit isn't given yet, and a drop that closing an inventory makes
            // (no room for the cursor) falls back, when cancelled, to an addItem that discards it.
            if (phase != null && phase != Session.Phase.ENTERING) {
                e.setCancelled(true);
            } else if (KitItems.isKit(e.getItemDrop().getItemStack())) {
                e.getItemDrop().remove(); // a stray kit item: deleted, never dropped
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (on() && KitItems.isKit(e.getEntity().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopper(InventoryPickupItemEvent e) {
        if (on() && KitItems.isKit(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        safely("a game inventory check", () -> {
            if (inSession(p)) {
                if (!(e.getView().getTopInventory().getHolder(false) instanceof GameScreen)) {
                    e.setCancelled(true);
                }
                return;
            }
            // Nobody else moves a kit item: an admin looking into a player's inventory, a stray.
            if (on() && (KitItems.isKit(e.getCurrentItem()) || KitItems.isKit(e.getCursor()) || swapsKit(p, e))) {
                e.setCancelled(true);
            }
        });
    }

    private static boolean swapsKit(Player p, InventoryClickEvent e) {
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            return KitItems.isKit(p.getInventory().getItem(e.getHotbarButton()));
        }
        return e.getClick() == ClickType.SWAP_OFFHAND && KitItems.isKit(p.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        safely("a game inventory check", () -> {
            if (inSession(p)) {
                if (!(e.getView().getTopInventory().getHolder(false) instanceof GameScreen)) {
                    e.setCancelled(true);
                }
            } else if (on() && KitItems.isKit(e.getOldCursor())) {
                e.setCancelled(true);
            }
        });
    }

    /** Nothing else may add items mid-game: only games screens open (R2.5). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) {
        if (inSession(e.getPlayer()) && !(e.getInventory().getHolder(false) instanceof GameScreen)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (inSession(e.getPlayer())
                || (on() && (KitItems.isKit(e.getMainHandItem()) || KitItems.isKit(e.getOffHandItem())))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (inSession(e.getPlayer()) || (on() && KitItems.isKit(e.getItemInHand()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onThrow(PlayerLaunchProjectileEvent e) {
        if (inSession(e.getPlayer()) && !KitItems.isKit(e.getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** An anvil, smithing table or grindstone never works a kit item into something else. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareResultEvent e) {
        if (!on()) {
            return;
        }
        safely("a game item check", () -> {
            for (ItemStack i : e.getInventory().getContents()) {
                if (KitItems.isKit(i)) {
                    e.setResult(null);
                    return;
                }
            }
        });
    }

    // ---- blocks and entities (R2.10 b) ----------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (!inSession(e.getPlayer())) {
            return;
        }
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_BLOCK) {
            e.setUseInteractedBlock(Event.Result.DENY);
        }
        if ((a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) && !KitItems.isKit(e.getItem())) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        guardEntity(e.getPlayer(), e.getRightClicked(), () -> e.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntityAt(PlayerInteractAtEntityEvent e) {
        guardEntity(e.getPlayer(), e.getRightClicked(), () -> e.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (inSession(e.getPlayer()) || (on() && WorldEntities.gameId(e.getRightClicked()) != null)) {
            e.setCancelled(true);
        }
    }

    /** A session player touches only their own boat; nobody else touches a game entity. */
    private void guardEntity(Player p, Entity target, Runnable cancel) {
        if (!on()) {
            return;
        }
        safely("a game entity check", () -> {
            String gameId = WorldEntities.gameId(target);
            boolean own = gameId != null && p.getUniqueId().equals(WorldEntities.owner(target));
            if (inSession(p)) {
                Session s = core.session(p.getUniqueId());
                if (!own || s == null || !gameId.equals(s.gameId())) {
                    cancel.run();
                }
            } else if (gameId != null) {
                cancel.run();
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrame(PlayerItemFrameChangeEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPot(PlayerFlowerPotManipulateEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLectern(PlayerInsertLecternBookEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent e) {
        if (inSession(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleDamage(VehicleDamageEvent e) {
        if (on() && WorldEntities.gameId(e.getVehicle()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        if (on() && WorldEntities.gameId(e.getVehicle()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!on() || WorldEntities.gameId(e.getVehicle()) == null) {
            return;
        }
        if (!WorldEntities.mayEnter(e.getVehicle(), e.getEntered().getUniqueId())) { // WP-CH: or the owner's rider
            e.setCancelled(true);
        }
    }

    /** A rider leaving a game vehicle stays seated unless the game itself dismounts them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(EntityDismountEvent e) {
        if (e.isCancellable() && e.getEntity() instanceof Player p && ownVehicleExit(p, e.getDismounted())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.isCancellable() && e.getExited() instanceof Player p && ownVehicleExit(p, e.getVehicle())) {
            e.setCancelled(true);
        }
    }

    private boolean ownVehicleExit(Player p, Entity vehicle) {
        if (!playing(p) || core.dismountIsOurs(p.getUniqueId())) {
            return false;
        }
        Session s = core.session(p.getUniqueId());
        return s != null && s.gameId().equals(WorldEntities.gameId(vehicle));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!on()) {
            return;
        }
        safely("the game entity sweep", () -> {
            int removed = WorldEntities.removeLoaded(e.getEntities());
            if (removed > 0) {
                games.plugin().getLogger().info("Games: removed " + removed + " leftover game entit"
                        + (removed == 1 ? "y" : "ies") + " from a chunk that loaded.");
            }
        });
    }

    // ---- game mode, flight, teleports, worlds, the void -----------------------------------------

    /** Multiverse-Core re-applies a world's mode after every world change: cancel, never end (R2.7). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (!playing(p) || port.ownModeChange(p.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        port.later(1, () -> {
            if (playing(p)) {
                port.adventure(p); // the session's own mode (ADVENTURE, or a Clubhouse watcher's SPECTATOR: WP-CH)
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!groundsFlight(e.isFlying(), playing(p), p.getGameMode())) { // WP-CH: a watcher flies
            return;
        }
        e.setCancelled(true);
        port.later(1, () -> {
            if (groundsFlight(true, playing(p), p.getGameMode())) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        });
    }

    /**
     * Whether a player taking off is grounded: a session player, unless they are in spectator mode (a
     * Clubhouse watcher, WP-CH: flying is how they watch, and the session keeps that mode).
     */
    static boolean groundsFlight(boolean takingOff, boolean playing, GameMode mode) {
        return takingOff && playing && mode != GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (core.none()) {
            return;
        }
        safely("a game teleport check", () -> core.teleported(e.getPlayer(), BukkitPort.place(e.getFrom()),
                BukkitPort.place(e.getTo()), e.getCause().name()));
    }

    /** The backstop, before Multiverse-Inventories' LOW swap. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (core.none()) {
            return;
        }
        safely("a game world change", () -> core.worldChanged(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (core.none()) {
            return;
        }
        var to = e.getTo();
        if (to.getWorld() == null || to.getY() >= to.getWorld().getMinHeight()) {
            return;
        }
        safely("a game void check", () -> core.moved(e.getPlayer(), BukkitPort.place(to), to.getWorld().getMinHeight()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        leaveClicks.remove(e.getPlayer().getUniqueId());
        kitTick.remove(e.getPlayer().getUniqueId());
    }
}
