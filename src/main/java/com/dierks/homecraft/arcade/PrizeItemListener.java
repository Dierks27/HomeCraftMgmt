package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.games.JoinSigns;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.UUID;

/**
 * What Prize Counter items do in the world.
 *
 * <ul>
 *   <li><b>Boosts</b>: right-click for the effect. Buying more stacks the time, up to three times
 *       one boost's length.</li>
 *   <li><b>Mini Radar</b>: right-click to lock onto the live hunt ({@link RadarService}).</li>
 *   <li><b>Mini Lure</b>: right-click to set it — the next wild Mini that rolls for anyone lands
 *       near you instead. One set at a time.</li>
 *   <li><b>Firework Shows</b>: right-click for a show that cannot hurt anyone, as long as its row's {@code seconds}
 *       ({@link FireworkShows}): the Firework Show's three seconds, the Grand Firework Show's fifteen with a finale
 *       (0.38.1). One long show at a time: another is refused and kept. A long show is never used by a tap on one of
 *       ours (the Arcade Machine, a join sign, a Mini), nor in a game or a Games world, and it ends when its player
 *       goes into one. A show stops when its player quits. The <b>Mega Firework Show</b> (a row with
 *       {@code launchers}, 0.38.1) plays a minute from the Firework Launchers nearby instead ({@link MegaShowService}),
 *       as the player's one long show; a tap on a launcher starts it too, and with none near enough it is kept.</li>
 *   <li><b>Hats</b>: right-click the air to put one on (on Bedrock, a tap on a plain block too, never on
 *       one of ours, such as the Arcade Machine or a Mini). A rented hat or cap, a key bound to its owner (0.38),
 *       is never placed, so a tap on a plain block wears it on both editions. What was on the head goes into the
 *       bag (a key is deleted: My Looks makes it again), and never onto the ground.</li>
 *   <li><b>Placed hats and trophies</b> remember exactly what they were and who placed them. Only
 *       that player (or an admin) may break one, and the same prize goes back into their bag (or
 *       their Mailbox), never a plain head that has lost its tag and could be sold. Pistons, water,
 *       lava and explosions can't move or break one (0.37.3).</li>
 * </ul>
 */
public final class PrizeItemListener implements Listener {

    /** The Mailbox source of a placed prize that didn't fit its breaker's bag (0.37.3). */
    static final String MAILBOX_SOURCE = "PRIZE";

    /** A long firework show used while the player's long show is still on (0.38.1): refused, the item kept. */
    static final String SHOW_ON = "Wait for your show to finish!";
    /** A long firework show used in a game or a Games world (0.38.1): refused, the item kept. */
    static final String SHOW_NOT_HERE = "Save your show for after the games!";

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
                // The show is its row's now: a Mega show's launchers, or a show's length (a missing row plays the
                // short show).
                Prize row = plugin.config().arcade().prize(TokenPrizes.prizeId(item));
                if (MegaShow.isMega(row)) {
                    megaShow(event, player, hand, row); // first: a Mega show never plays around the player
                    return;
                }
                int seconds = FireworkShow.seconds(row);
                boolean longShow = FireworkShow.isLong(seconds);
                Block clicked = event.getClickedBlock();
                if (longShow && clicked != null && ours(plugin, clicked)) {
                    // review SHOW-OURS-2: the tap is that block's (the Arcade Machine's screen opens, a Mini is
                    // caught, a join sign starts its game), as for a hat; the long show stays in the hand
                    return;
                }
                cancel(event);
                FireworkShows shows = plugin.fireworkShows();
                if (shows == null) {
                    return;
                }
                // A long one in a game or a Games world, or while the player's long show is still on, is refused,
                // and the item stays in the hand.
                if (longShow && !shows.playsHere(player)) {
                    if (shows.refusalDue(player.getUniqueId())) {
                        player.sendMessage(Text.of("&7" + SHOW_NOT_HERE));
                        Sounds.refused(player);
                    }
                } else if (shows.start(player, seconds)) {
                    consume(player, hand);
                } else if (shows.refusalDue(player.getUniqueId())) {
                    player.sendMessage(Text.of("&7" + SHOW_ON));
                    Sounds.refused(player);
                }
            }
            case HAT -> {
                Block clicked = event.getClickedBlock();
                boolean bedrock = Bedrock.is(player);
                boolean bound = PrizeService.bound(item);
                boolean tap = clicked != null && (bound
                        ? keyWearsOnBlockTap(player.isSneaking(), clicked.getType().isInteractable(), ours(plugin, clicked))
                        : wearsOnBlockTap(bedrock, player.isSneaking(), clicked.getType().isInteractable(),
                        isHead(item.getType()) && !bound, bedrock && ours(plugin, clicked)));
                if (a == Action.RIGHT_CLICK_AIR || tap) {
                    cancel(event);
                    wear(plugin, player, hand);
                }
                // otherwise, on a block: a hat from before 0.38 may be placed as a head (onPlace remembers it); a
                // bound key never is (BoundItemGuard refuses the place)
            }
            default -> {
                // a trophy is placed like any head
            }
        }
    }

    /**
     * A Mega Firework Show (0.38.1): from the Firework Launchers near the player ({@link MegaShowService}). A tap on
     * another of ours is that block's (its listener ran first) and leaves the show in the hand; a tap on a launcher, a
     * plain block or the air starts it. Every refusal (in a game, the long show still on, no launcher near enough, the
     * launchers busy, the sky full) keeps the item, and is said at most once every 2 s, as 0.38.1's are; only a show
     * that started uses it up.
     */
    private void megaShow(PlayerInteractEvent event, Player player, EquipmentSlot hand, Prize row) {
        Block on = event.getClickedBlock();
        boolean launcher = on != null && launcher(plugin, on);
        if (!MegaShow.usesOnTap(on != null, on != null && !launcher && ours(plugin, on), launcher)) {
            return; // the Arcade Machine's screen opened, a Mini was caught, a join sign started its game: kept
        }
        cancel(event);
        MegaShowService shows = plugin.megaShows();
        String refused = shows == null ? MegaShow.CANT_START : shows.start(player, row);
        if (refused == null) {
            consume(player, hand);
            return;
        }
        if (plugin.fireworkShows() == null || plugin.fireworkShows().refusalDue(player.getUniqueId())) {
            player.sendMessage(Text.of("&7" + refused));
            Sounds.refused(player);
        }
    }

    /**
     * Whether {@code on} is a placed Firework Launcher ({@link MegaShow#onLauncher}): its tile's tag, and only without
     * one its placed-block record, as {@code CustomBlockListener} reads it. A lookup that fails is "no".
     */
    private static boolean launcher(HomeCraftManagement plugin, Block on) {
        if (CustomBlockService.isLauncher(on.getState(false))) {
            return true;
        }
        try {
            CustomBlockService blocks = plugin.blockService();
            return blocks != null && MegaShow.onLauncher(false,
                    blocks.at(on.getLocation()).map(PlacedBlock::type).orElse(null));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * A Bedrock tap on a block wears a hat too (HALLOWEEN-SPEC D2). Geyser turns a tap on a block into a
     * use-on-block only, so "right-click the air" never fires for a child looking at the ground. Not a
     * head (heads are still placed), not while sneaking, and not on a block that does something when
     * tapped (a chest or a door still opens). Java keeps the right-click in the air.
     */
    static boolean wearsOnBlockTap(boolean bedrock, boolean sneaking, boolean interactable, boolean head) {
        return wearsOnBlockTap(bedrock, sneaking, interactable, head, false);
    }

    /**
     * {@link #wearsOnBlockTap(boolean, boolean, boolean, boolean)}, and never on a block of ours ({@code ours}): a
     * HomeCraft block, a join sign, or a Mini's head (placed or wild). The Arcade Machine and a Mini are player
     * heads, which don't count as interactable, so a tap that opened the machine's screen, caught a wild Mini or
     * opened a placed one's card also put the hat in the hand on.
     */
    static boolean wearsOnBlockTap(boolean bedrock, boolean sneaking, boolean interactable, boolean head,
                                   boolean ours) {
        return bedrock && !sneaking && !interactable && !head && !ours;
    }

    /**
     * A bound key's tap on a block wears it, on both editions (SHOP-V2-SPEC §3.7): a key can never be placed, so
     * there is nothing else a tap on a plain block could mean. Not while sneaking, not on a block that does something
     * when tapped, and never on one of ours.
     */
    static boolean keyWearsOnBlockTap(boolean sneaking, boolean interactable, boolean ours) {
        return !sneaking && !interactable && !ours;
    }

    /** Every head a hat can be: a player's, and the game's own mob heads (P3, the Creeper and Dragon Hats). */
    static boolean isHead(Material m) {
        return PlacedPrizes.headMaterial(m) && !m.name().contains("_WALL_");
    }

    /**
     * Whether {@code clicked} is one of ours: a placed HomeCraft block (the upper head of a Vending Machine acts
     * for the machine, as in {@code CustomBlockListener}), a tagged join sign, a Mini's head, placed or wild
     * (the head {@code MiniHeadListener} acts on: its skull carries a Mini), or a placed Arcade Trophy, whose tap
     * opens the Arcade (§5.8). A lookup that fails is "no". Public for the Candy Apple shower's taps (the Bedrock
     * eat fallback and the bag), which leave a block of ours alone too.
     */
    public static boolean ours(HomeCraftManagement plugin, Block clicked) {
        try {
            CustomBlockService blocks = plugin.blockService();
            if (blocks != null && blocks.at(blocks.vendingLowerOf(clicked).orElse(clicked).getLocation()).isPresent()) {
                return true;
            }
            if (JoinSigns.isJoinSign(clicked)) {
                return true;
            }
            Material type = clicked.getType();
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                return false;
            }
            return miniHead(plugin, clicked) || ArcadeTrophies.isPlacedArcadeTrophy(clicked);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** A player head whose skull carries a Mini (placed or wild). */
    private static boolean miniHead(HomeCraftManagement plugin, Block clicked) {
        return clicked.getState(false) instanceof Skull skull && plugin.miniService() != null
                && plugin.miniService().refFrom(skull.getPersistentDataContainer()) != null;
    }

    /**
     * {@link #ours(HomeCraftManagement, Block)} on its facts, the order it asks them in: a placed HomeCraft block, a
     * join sign, and on a player head a Mini or a placed Arcade Trophy (a hat tap on the trophy opens the Arcade and
     * leaves the hat in the hand). Pure, for the test.
     */
    static boolean ours(boolean customBlock, boolean joinSign, boolean playerHead, boolean mini, boolean arcadeTrophy) {
        return customBlock || joinSign || (playerHead && (mini || arcadeTrophy));
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

    /**
     * Put a hat on. What was on the head: a bound key is deleted, never moved (it is in My Looks); anything else
     * goes into the bag, and with no room the wear is refused and the head left as it was. Nothing goes on the
     * ground any more (0.38). A key that isn't the wearer's, or whose look has ended, is taken away instead, once
     * the wardrobe knows ({@code LookService}: never on an unknown answer).
     */
    private static void wear(HomeCraftManagement plugin, Player player, EquipmentSlot hand) {
        PlayerInventory inv = player.getInventory();
        ItemStack held = inv.getItem(hand);
        if (held == null || held.getType().isAir()) {
            return;
        }
        if (PrizeService.bound(held) && plugin.looks() != null) {
            String refused = plugin.looks().keyRefusal(player, held);
            if (refused != null) {
                inv.setItem(hand, null);
                player.sendMessage(Text.of("&7" + refused));
                Sounds.refused(player);
                return;
            }
        }
        ItemStack old = inv.getHelmet();
        boolean hasOld = old != null && !old.getType().isAir();
        boolean heldSlotFrees = hand == EquipmentSlot.HAND && held.getAmount() == 1;
        OldHelmet goes = hasOld ? oldHelmet(PrizeService.bound(old), inv.firstEmpty() != -1, heldSlotFrees)
                : OldHelmet.NONE;
        if (goes == OldHelmet.REFUSE) {
            player.sendMessage(Text.of("&7Make room in your bag first: what's on your head goes there."));
            Sounds.refused(player);
            return;
        }
        // The old helmet's place is found before anything moves: a free bag slot, else the held stack's own.
        int slot = goes == OldHelmet.BAG ? (inv.firstEmpty() != -1 ? inv.firstEmpty() : inv.getHeldItemSlot()) : -1;
        ItemStack hat = held.clone();
        hat.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        inv.setItem(hand, held.getAmount() > 0 ? held : null);
        inv.setHelmet(hat);
        if (goes == OldHelmet.BAG) {
            inv.setItem(slot, old.clone());
        }
        if (plugin.looks() != null && (PrizeService.bound(hat) || goes == OldHelmet.DELETE)) {
            plugin.looks().marks(player); // a worn Keeper's mark starts (or stops) with its key
        }
        Sounds.received(player);
    }

    /** What happens to the helmet a hat replaces. Never the ground. */
    enum OldHelmet { NONE, DELETE, BAG, REFUSE }

    /**
     * The old helmet: a bound key is deleted ("Wear it" makes it again); anything else goes into the bag, which it
     * fits when there is a free bag slot, or when the held stack is the last one in the main hand, whose slot it then
     * takes (the off-hand isn't part of the bag); otherwise the wear is refused.
     */
    static OldHelmet oldHelmet(boolean oldBound, boolean bagHasRoom, boolean heldSlotFrees) {
        if (oldBound) {
            return OldHelmet.DELETE;
        }
        return keepsOldOn(bagHasRoom, heldSlotFrees) ? OldHelmet.REFUSE : OldHelmet.BAG;
    }

    /** A swap is refused when the old helmet has nowhere to go in the bag (review R1; 0.38: any helmet). */
    static boolean keepsOldOn(boolean bagHasRoom, boolean heldSlotFrees) {
        return !bagHasRoom && !heldSlotFrees;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShowDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(Keys.FIREWORK_SHOW, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    /** A player who quits takes their firework shows with them: the timers stop now (0.38.1). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.fireworkShows() != null) {
            plugin.fireworkShows().stop(event.getPlayer().getUniqueId());
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
    //
    // A placed head keeps its exact prize, who placed it and its prize id (PlacedPrizes). Pistons, flowing water
    // and lava and explosions never move or break one: CustomBlockListener's guards refuse them (0.37.3), so a
    // trophy can no longer be popped onto the ground for anyone to pick up.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!TokenPrizes.is(item) || !(event.getBlockPlaced().getState() instanceof Skull skull)) {
            return;
        }
        CustomBlockService blocks = plugin.blockService();
        if (!storesAsPlacedPrize(true, true, blocks != null && blocks.itemType(item) != null)) {
            return; // a HomeCraft block (a skinned Firework Launcher) is the block system's, as MiniHeadListener says
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        var pdc = skull.getPersistentDataContainer();
        pdc.set(Keys.PRIZE_ITEM, PersistentDataType.STRING, Items.toBase64(one));
        pdc.set(Keys.TROPHY_PLACER, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        String id = TokenPrizes.prizeId(item);
        if (id != null) {
            pdc.set(Keys.PLACED_PRIZE_ID, PersistentDataType.STRING, id);
        }
        skull.update(true, false);
    }

    /**
     * Whether a placed block is stored as a placed prize: a token prize placed as a head, unless it is a HomeCraft
     * block (0.38.1: a Firework Launcher, a token prize, wearing a head skin), which is the block system's, as
     * {@code MiniHeadListener} leaves one: stored as a prize too, its break would put one in the bag and drop another.
     */
    static boolean storesAsPlacedPrize(boolean tokenPrize, boolean skull, boolean homecraftBlock) {
        return tokenPrize && skull && !homecraftBlock;
    }

    /**
     * Only whoever placed a prize head (or an admin) may break it ({@link PlacedPrizeRules}); one placed before
     * 0.37.3 remembers no placer and stays breakable by anyone. Stop the plain head dropping; harmless if a later
     * handler cancels the break.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCheck(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (PlacedPrizes.storedAt(block) == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID placer = PlacedPrizes.placer(block);
        if (PlacedPrizeRules.breakRefused(player.getUniqueId().equals(placer), player.hasPermission("hcm.admin"),
                placer != null)) {
            event.setCancelled(true);
            String name = plugin.getServer().getOfflinePlayer(placer).getName();
            player.sendMessage(Text.of("&7That trophy belongs to " + (name == null ? "someone else" : name) + "."));
            Sounds.refused(player);
            return;
        }
        event.setDropItems(false);
    }

    /**
     * The real prize only once the break is final, or a cancelled break would duplicate it: into the breaker's bag,
     * and if it doesn't fit, their Mailbox. Never the ground, where anyone could take it. If even the Mailbox can't
     * be written, the break is called off and the prize stays standing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ItemStack stored = PlacedPrizes.storedAt(event.getBlock());
        if (stored == null) {
            return;
        }
        Player player = event.getPlayer();
        PlacedPrizeRules.Goes goes = PlacedPrizeRules.handOver(new PlacedPrizeRules.Hand<ItemStack>() {
            @Override
            public ItemStack toBag() {
                var left = player.getInventory().addItem(stored);
                return left.isEmpty() ? null : left.values().iterator().next();
            }

            @Override
            public void toMailbox(ItemStack rest) throws java.sql.SQLException {
                plugin.deliveries().queueReady(player.getUniqueId(), rest, label(rest), MAILBOX_SOURCE);
            }
        });
        switch (goes) {
            case BAG -> {
            }
            case MAILBOX -> player.sendMessage(
                    Text.of("&e✉ Your bag is full, so it went to your Mailbox (PC or Mailbox)."));
            case STAYS -> {
                plugin.getLogger().severe("Couldn't mail a placed prize to " + player.getName()
                        + ", so it stays standing");
                event.setCancelled(true);
                player.sendMessage(Text.of("&cCould not save - try again."));
            }
        }
    }

    /** A prize's name for its Mailbox row. */
    private String label(ItemStack item) {
        var row = plugin.config().arcade().prize(TokenPrizes.prizeId(item));
        return row != null ? Text.plain(row.display()) : "Arcade prize";
    }
}
