package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What Prize Counter items do in the world.
 *
 * <ul>
 *   <li><b>Boosts</b>: right-click for the effect. Buying more stacks the time, up to three times
 *       one boost's length.</li>
 *   <li><b>Mini Radar</b>: right-click to lock onto the live hunt ({@link RadarService}).</li>
 *   <li><b>Mini Lure</b>: right-click to set it — the next wild Mini that rolls for anyone lands
 *       near you instead. One set at a time.</li>
 *   <li><b>Firework Show</b>: right-click for a three-second volley that cannot hurt anyone.</li>
 *   <li><b>Hats</b>: right-click the air to put one on.</li>
 *   <li><b>Placed hats and trophies</b> remember exactly what they were, so breaking one (or
 *       blowing it up, pushing it, washing it away) gives the same prize back — never a plain head
 *       that has lost its tag and could be sold.</li>
 * </ul>
 */
public final class PrizeItemListener implements Listener {

    private final HomeCraftManagement plugin;

    public PrizeItemListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- using an item ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        PrizeType kind = PrizeItems.kindOf(item);
        if (kind == null) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        if (hand == EquipmentSlot.OFF_HAND && PrizeItems.kindOf(player.getInventory().getItemInMainHand()) != null) {
            // The client sends an off-hand use right after the main-hand one; with a prize in
            // each hand that would spend both on one click.
            cancel(event);
            return;
        }
        switch (kind) {
            case BOOST -> {
                cancel(event);
                if (applyBoost(player, item)) {
                    consume(player, hand);
                }
            }
            case RADAR -> {
                cancel(event);
                if (plugin.radar() != null && plugin.radar().active(player.getUniqueId())) {
                    player.sendMessage(Text.of("&7Your Mini Radar is already on. Keep this one for the next hunt!"));
                    Sounds.refused(player);
                    return;
                }
                if (plugin.radar() != null && plugin.radar().activate(player)) {
                    consume(player, hand);
                    player.sendMessage(Text.of("&bMini Radar on! &7Watch the bar at the bottom of your screen."));
                } else {
                    player.sendMessage(Text.of("&7No wild Mini right now. Your radar is still full."));
                    Sounds.refused(player);
                }
            }
            case LURE -> {
                cancel(event);
                if (plugin.hunt() == null) {
                    return;
                }
                if (plugin.hunt().lureArmed(player.getUniqueId())) {
                    player.sendMessage(Text.of("&7You already have a Mini Lure set. Wait for a wild Mini!"));
                    Sounds.refused(player);
                    return;
                }
                if (plugin.hunt().armLure(player)) {
                    consume(player, hand);
                    player.sendMessage(Text.of("&dMini Lure set! &7The next wild Mini will appear near you."));
                    Sounds.received(player);
                }
            }
            case FIREWORK -> {
                cancel(event);
                consume(player, hand);
                show(player);
            }
            case HAT -> {
                if (a == Action.RIGHT_CLICK_AIR) {
                    cancel(event);
                    wear(player, hand);
                }
                // on a block: let it be placed as a head (onPlace remembers it)
            }
            default -> {
                // a trophy is placed like any head
            }
        }
    }

    private static void cancel(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    private static void consume(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        if (held != null && held.getAmount() > 0) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    /**
     * Apply a boost. Another of the same effect adds its time on top, up to three boosts' worth,
     * so a stack of Speed Boosts can be saved and chained but not stretched into an afternoon.
     */
    private boolean applyBoost(Player player, ItemStack item) {
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String key = pdc.get(Keys.BOOST_EFFECT, PersistentDataType.STRING);
        Integer amp = pdc.get(Keys.BOOST_AMPLIFIER, PersistentDataType.INTEGER);
        Integer minutes = pdc.get(Keys.BOOST_MINUTES, PersistentDataType.INTEGER);
        PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
        if (type == null || minutes == null) {
            player.sendMessage(Text.of("&cThis boost doesn't work any more — tell an admin."));
            return false;
        }
        int amplifier = amp == null ? 0 : amp;
        int add = minutes * 60 * 20;
        int max = add * 3;
        PotionEffect current = player.getPotionEffect(type);
        int ticks = add;
        if (current != null && current.getAmplifier() == amplifier && !current.isInfinite()) {
            if (current.getDuration() >= max) {
                player.sendMessage(Text.of("&7That boost is already as long as it can go."));
                return false;
            }
            ticks = Math.min(max, current.getDuration() + add);
        }
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
        var row = plugin.config().arcade().prize(TokenPrizes.prizeId(item));
        String name = row != null ? Text.plain(row.display()) : "Boost";
        player.sendMessage(Text.of("&a✦ " + name + " &7for &f" + (ticks / 1200) + " min&7."));
        Sounds.received(player);
        return true;
    }

    /** A three-second volley of fireworks around the player, tagged so they cannot hurt anyone. */
    private void show(Player player) {
        Color[] palette = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.WHITE};
        for (int burst = 0; burst < 6; burst++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    Location at = player.getLocation().add(r.nextDouble(-3, 3), 0.5, r.nextDouble(-3, 3));
                    try {
                        player.getWorld().spawn(at, Firework.class, fw -> {
                            FireworkMeta meta = fw.getFireworkMeta();
                            meta.addEffect(FireworkEffect.builder()
                                    .withColor(palette[r.nextInt(palette.length)], palette[r.nextInt(palette.length)])
                                    .withFade(Color.WHITE)
                                    .with(FireworkEffect.Type.values()[r.nextInt(FireworkEffect.Type.values().length)])
                                    .flicker(r.nextBoolean()).trail(true).build());
                            meta.setPower(1);
                            fw.setFireworkMeta(meta);
                            fw.getPersistentDataContainer().set(Keys.FIREWORK_SHOW, PersistentDataType.BYTE, (byte) 1);
                        });
                    } catch (RuntimeException ignored) {
                        // cosmetic
                    }
                }
            }, burst * 10L);
        }
    }

    /** Put a hat on, handing back whatever was on the player's head. */
    private static void wear(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null) {
            return;
        }
        ItemStack hat = held.clone();
        hat.setAmount(1);
        ItemStack old = player.getInventory().getHelmet();
        player.getInventory().setHelmet(hat);
        held.setAmount(held.getAmount() - 1);
        if (old != null && !old.getType().isAir()) {
            player.getInventory().addItem(old).values()
                    .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }
        Sounds.received(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShowDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(Keys.FIREWORK_SHOW, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    // ---- prizes are not ingredients ----------------------------------------------------
    //
    // A recipe matches on the item type alone, so a Mini Lure (a heart of the sea) and eight
    // Water Breathing boosts (nautilus shells) would craft an untagged Conduit to sell on a
    // Pallet. Every way a prize could become something else — crafting, a crafter, brewing, a
    // villager trade — refuses it instead.

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        for (ItemStack in : event.getInventory().getMatrix()) {
            if (TokenPrizes.carries(in)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof org.bukkit.inventory.InventoryHolder holder) {
            for (ItemStack in : holder.getInventory().getContents()) {
                if (TokenPrizes.carries(in)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(org.bukkit.event.inventory.BrewEvent event) {
        if (TokenPrizes.carries(event.getContents().getIngredient())) {
            event.setCancelled(true);
        }
    }

    /** A villager trade is taken from its result slot, however the inputs got there. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof org.bukkit.inventory.MerchantInventory merchant)
                || event.getClickedInventory() != merchant
                || event.getSlotType() != org.bukkit.event.inventory.InventoryType.SlotType.RESULT) {
            return;
        }
        if (TokenPrizes.carries(merchant.getItem(0)) || TokenPrizes.carries(merchant.getItem(1))) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage(Text.of("&c" + TokenPrizes.REFUSAL));
                Sounds.refused(p);
            }
        }
    }

    // ---- prize heads placed as blocks --------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!TokenPrizes.is(item) || !(event.getBlockPlaced().getState() instanceof Skull skull)) {
            return;
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        skull.getPersistentDataContainer().set(Keys.PRIZE_ITEM, PersistentDataType.STRING, Items.toBase64(one));
        skull.update(true, false);
    }

    /** Stop the plain head dropping; harmless if a later handler cancels the break. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCheck(BlockBreakEvent event) {
        if (storedAt(event.getBlock()) != null) {
            event.setDropItems(false);
        }
    }

    /** Drop the real prize only once the break is final, or a cancelled break would duplicate it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ItemStack stored = storedAt(event.getBlock());
        if (stored != null) {
            dropAt(event.getBlock(), stored);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        dropAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        dropAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        List<Block> candidates = new ArrayList<>(event.getBlocks());
        BlockFace dir = event.getDirection();
        candidates.add(event.getBlock().getRelative(dir, event.getBlocks().size() + 1));
        for (Block b : candidates) {
            ItemStack stored = storedAt(b);
            if (stored != null) {
                b.setType(Material.AIR, false);
                dropAt(b, stored);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        ItemStack stored = storedAt(to);
        if (stored != null) {
            to.setType(Material.AIR, false);
            dropAt(to, stored);
        }
    }

    private void dropAll(List<Block> blocks) {
        List<Block> found = new ArrayList<>();
        for (Block b : blocks) {
            if (storedAt(b) != null) {
                found.add(b);
            }
        }
        for (Block b : found) {
            ItemStack stored = storedAt(b);
            blocks.remove(b);
            b.setType(Material.AIR, false);
            dropAt(b, stored);
        }
    }

    private static ItemStack storedAt(Block b) {
        Material t = b.getType();
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) {
            return null;
        }
        if (!(b.getState(false) instanceof Skull skull)) {
            return null;
        }
        String b64 = skull.getPersistentDataContainer().get(Keys.PRIZE_ITEM, PersistentDataType.STRING);
        return b64 == null ? null : Items.fromBase64(b64);
    }

    private static void dropAt(Block b, ItemStack item) {
        if (item != null) {
            b.getWorld().dropItemNaturally(b.getLocation().toCenterLocation(), item);
        }
    }
}
