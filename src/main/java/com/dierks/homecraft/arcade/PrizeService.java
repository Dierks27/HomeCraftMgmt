package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService.Outcome;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Prize Counter (§3.9): fixed-price token purchases, sorted into tabs, with per-day /
 * per-week / lifetime limits and "you already own this".
 *
 * <p>The order of a purchase is the one the counter has always had, and it matters: every check
 * first, then build the item (so a prize that cannot be produced costs nothing), then charge,
 * then record — so the ledger line and the limit tick only for something that exists.
 *
 * <p>Nothing here touches a dollar. Every physical prize carries {@code hcm:token_prize}, which
 * every money surface refuses, and no row type may reach a money flow (no Courier extras, no
 * shipping, no market limits, no printer fees): §11 #9.
 */
public final class PrizeService {

    /** The Arcade trophy serial, in {@code arcade_state}. */
    private static final String TROPHY_SERIAL = "trophy_serial";

    private final HomeCraftManagement plugin;
    private final PrizeDao dao;

    public PrizeService(HomeCraftManagement plugin, PrizeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public PrizeDao dao() {
        return dao;
    }

    // ---- what a player sees ----------------------------------------------------------

    /**
     * Whether a row appears at all: enabled, its plugins present, and — for a hat — a texture to
     * show (a hat with no texture is a Steve head, which is not a prize).
     */
    public boolean visible(Prize p) {
        if (!p.enabled()) {
            return false;
        }
        for (String name : p.requiresPlugins()) {
            if (Bukkit.getPluginManager().getPlugin(name) == null) {
                return false;
            }
        }
        if (p.type() == PrizeType.HAT && (p.texture() == null || p.texture().isBlank())) {
            return false;
        }
        return true;
    }

    /** The rows a player sees on one tab. */
    public List<Prize> visible(PluginConfig.PrizeTab tab) {
        List<Prize> out = new ArrayList<>();
        for (Prize p : plugin.config().arcade().prizes(tab)) {
            if (visible(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The rows this player sees on one tab: {@link #visible(Prize)}, and a +1 Home row only for
     * someone it can add to (not unlimited, a base under 20, Essentials and LuckPerms present).
     */
    public List<Prize> visible(Player player, PluginConfig.PrizeTab tab) {
        List<Prize> out = new ArrayList<>();
        for (Prize p : visible(tab)) {
            if (visibleTo(player, p)) {
                out.add(p);
            }
        }
        return out;
    }

    public boolean visibleTo(Player player, Prize p) {
        if (!visible(p)) {
            return false;
        }
        return p.type() != PrizeType.HOME_SLOT || (plugin.homes() != null && plugin.homes().offered(player));
    }

    /** What this player's next one costs: a +1 Home gets dearer with each one bought. */
    public int price(UUID player, Prize p) {
        if (p.costs() == null || p.costs().isEmpty()) {
            return p.costTokens();
        }
        try {
            return p.priceAfter(dao.purchases(player, p.id(), "life"));
        } catch (SQLException e) {
            return p.priceAfter(Integer.MAX_VALUE);
        }
    }

    /**
     * True if the player already has this for good: the permission it grants, or a lifetime
     * limit already used up.
     */
    public boolean owned(Player player, Prize p) {
        if (p.ownedIfPermission() != null && player.hasPermission(p.ownedIfPermission())) {
            return true;
        }
        return p.limit() != null && p.limit().per() == LimitPer.LIFETIME && left(player.getUniqueId(), p) <= 0;
    }

    /** Buys left this period; {@link Integer#MAX_VALUE} when the row has no limit. */
    public int left(UUID player, Prize p) {
        if (p.type() == PrizeType.PITY) {
            return plugin.arcade().pityLeft(player); // one weekly limit, however the exchange is reached
        }
        if (p.limit() == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Math.max(0, p.limit().count() - dao.purchases(player, p.id(), periodKey(p.limit().per())));
        } catch (SQLException e) {
            return 0; // unknown is not "available"
        }
    }

    /** "1 left today", "0 left this week", or null when the row has no limit. */
    public String limitText(UUID player, Prize p) {
        if (p.type() == PrizeType.PITY) {
            return left(player, p) + " left this week";
        }
        if (p.limit() == null || p.limit().per() == LimitPer.LIFETIME) {
            return null;
        }
        int left = left(player, p);
        return left + " left " + (p.limit().per() == LimitPer.DAY ? "today" : "this week");
    }

    /** Why a row cannot be bought yet because it needs another one first, or null. */
    public String lockedBecause(Player player, Prize p) {
        if (p.requiresPrize() == null) {
            return null;
        }
        Prize needed = plugin.config().arcade().prize(p.requiresPrize());
        if (needed == null) {
            return null;
        }
        if (owned(player, needed)) {
            return null;
        }
        try {
            if (dao.everBought(player.getUniqueId(), needed.id())) {
                return null;
            }
        } catch (SQLException ignored) {
            // fall through to locked
        }
        return "Get " + Text.plain(needed.display()) + " first";
    }

    /** The limit period key, on the players' local calendar. */
    public String periodKey(LimitPer per) {
        return switch (per) {
            case DAY -> "d" + plugin.clock().dayKey();
            case WEEK -> "w" + plugin.clock().weekKey(weekStart());
            case LIFETIME -> "life";
        };
    }

    private java.time.DayOfWeek weekStart() {
        PluginConfig.Quests q = plugin.config().quests();
        return q == null || q.weekStartsOn() == null ? java.time.DayOfWeek.MONDAY : q.weekStartsOn();
    }

    /** Count one buy against a row's limit (no-op without a limit). */
    public void recordPurchase(UUID player, Prize p) {
        if (p.limit() == null && p.type() != PrizeType.COMMAND && p.requiresPrize() == null) {
            return;
        }
        try {
            dao.addPurchase(player, p.id(), p.limit() != null ? periodKey(p.limit().per()) : "life");
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not record a prize purchase: " + e.getMessage());
        }
    }

    // ---- buying ------------------------------------------------------------------------

    /**
     * Buy a row. {@code color} is the buyer's pick for a filament row that lets them choose.
     * PITY runs the pity exchange; TRADE_IN and QUEST_REROLL are screens, not purchases, and are
     * refused here (their menus call their own paths).
     */
    public Outcome buy(Player player, Prize p, DyeColor color) {
        if (!plugin.sandbox().check(player, "prize purchase " + p.id())) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        if (!visibleTo(player, p)) {
            return Outcome.fail("That isn't for sale right now.");
        }
        String locked = lockedBecause(player, p);
        if (locked != null) {
            return Outcome.fail(locked + ".");
        }
        if (owned(player, p)) {
            return Outcome.fail("You already have that.");
        }
        UUID id = player.getUniqueId();
        if (left(id, p) <= 0) {
            LimitPer per = p.type() == PrizeType.PITY ? LimitPer.WEEK : p.limit().per();
            return Outcome.fail("You've had all of those for " + (per == LimitPer.DAY ? "today"
                    : per == LimitPer.WEEK ? "this week" : "good") + ".");
        }
        int have = plugin.tokens().balance(id);
        int cost = price(id, p);
        if (have < cost) {
            return Outcome.fail("You need " + (cost - have) + " more tokens.");
        }
        Outcome out = switch (p.type()) {
            case PITY -> plugin.arcade().pity(player);
            case TRADE_IN, QUEST_REROLL -> Outcome.fail("Open that from the Prize Counter.");
            default -> deliver(player, p, color, true, 0);
        };
        if (out.ok()) {
            if (p.type() != PrizeType.PITY) { // the pity exchange counts its own week
                recordPurchase(id, p);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
            }
        }
        return out;
    }

    /**
     * Quest Reroll: swap one of today's unfinished dailies. Checks everything and finds the
     * replacement BEFORE charging, and refunds if the swap then fails, so a reroll with nothing
     * to swap to costs nothing.
     */
    public Outcome rerollQuest(Player player, Prize p, String questId) {
        if (!plugin.sandbox().check(player, "quest reroll")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        UUID id = player.getUniqueId();
        if (!visible(p) || p.type() != PrizeType.QUEST_REROLL) {
            return Outcome.fail("That isn't for sale right now.");
        }
        if (left(id, p) <= 0) {
            return Outcome.fail("You've already swapped a quest today.");
        }
        int have = plugin.tokens().balance(id);
        if (have < p.costTokens()) {
            return Outcome.fail("You need " + (p.costTokens() - have) + " more tokens.");
        }
        PluginConfig.Quest old = plugin.config().quests().byId(PluginConfig.QuestPeriod.DAILY, questId);
        if (old == null || plugin.quests().rerollCandidate(player, old) == null) {
            return Outcome.fail("There's no other quest to swap in. Nothing was charged.");
        }
        if (!plugin.tokens().spend(id, p.costTokens(), TokenService.Source.PRIZE, p.display())) {
            return Outcome.fail("You need " + p.costTokens() + " tokens.");
        }
        PluginConfig.Quest next = plugin.quests().reroll(player, questId);
        if (next == null) {
            plugin.tokens().grant(id, p.costTokens(), TokenService.Source.REFUND, p.display());
            return Outcome.fail("That quest couldn't be swapped. Nothing was charged.");
        }
        recordPurchase(id, p);
        return Outcome.won(com.dierks.homecraft.gui.Menus.icon(Material.WRITABLE_BOOK, "&f" + next.display()),
                "&f" + next.display());
    }

    /**
     * Give a row free, as a crate prize: no charge and no limit. A filament row with no colour of
     * its own gets a random one; a trail gets {@code daysOverride} days when that is above 0.
     */
    public Outcome grant(Player player, Prize p, int daysOverride) {
        return deliver(player, p, null, false, daysOverride);
    }

    /** Whether a row can be handed out by a crate at all right now. */
    public boolean grantable(Prize p) {
        if (!visible(p)) {
            return false;
        }
        return switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, TRAIL, HAT, TROPHY, FILAMENT, BLOCK, PACK -> true;
            default -> false; // perks, pity, trade-in and rerolls are bought, never won
        };
    }

    /**
     * Build, (optionally) charge, and hand over. The item is built BEFORE the charge, so a prize
     * that cannot be produced right now costs nothing.
     */
    private Outcome deliver(Player player, Prize p, DyeColor color, boolean charge, int daysOverride) {
        UUID id = player.getUniqueId();
        int cost = price(id, p);
        ItemStack item = null;
        String label = p.display();
        long trophyNumber = 0;
        switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, HAT -> item = PrizeItems.build(plugin, p);
            case TROPHY -> {
                try {
                    trophyNumber = dao.state(TROPHY_SERIAL, 0) + 1;
                } catch (SQLException e) {
                    return Outcome.fail("The trophy shelf is stuck — nothing charged. Tell an admin.");
                }
                item = PrizeItems.trophy(plugin, p, trophyNumber, player.getName());
                label = "&6Arcade Trophy #" + trophyNumber;
            }
            case FILAMENT -> {
                DyeColor dye = p.color() != null ? p.color() : color;
                if (dye == null && !charge) {
                    DyeColor[] all = DyeColor.values();
                    dye = all[ThreadLocalRandom.current().nextInt(all.length)];
                }
                if (dye == null) {
                    return Outcome.fail("Pick a filament colour first.");
                }
                item = plugin.miniService().filamentItems().filament(dye, p.amount());
                label = "&f" + p.amount() + " " + pretty(dye.name()) + " Filament";
            }
            case BLOCK -> {
                com.dierks.homecraft.block.CustomBlockType type;
                try {
                    type = com.dierks.homecraft.block.CustomBlockType.valueOf(p.blockKey());
                } catch (IllegalArgumentException e) {
                    return Outcome.fail("That prize isn't set up right — tell an admin.");
                }
                item = plugin.items().of(type);
                if (item == null) {
                    return Outcome.fail("That prize isn't available right now — nothing charged.");
                }
            }
            case PACK -> {
                var pack = plugin.packs() == null ? null : plugin.packs().pack(p.packId());
                if (pack == null) {
                    return Outcome.fail("That pack isn't sold any more — nothing charged.");
                }
                if (plugin.packs().soldOut(pack)) {
                    return Outcome.fail("That pack is sold out — nothing charged.");
                }
                // Bought here, it carries its price, so a pack that comes up short pays back.
                item = plugin.packs().packItem(p.packId(), charge
                        ? new com.dierks.homecraft.mini.PackItems.Paid(
                        com.dierks.homecraft.mini.PackItems.Currency.TOKENS, cost) : null);
            }
            case TRAIL, COMMAND -> {
                // nothing to build: applied after the charge
            }
            case HOME_SLOT -> {
                // Everything that could refuse is checked here, before the charge.
                String refused = plugin.homes() == null ? "That isn't for sale right now."
                        : plugin.homes().refuseReason(player);
                if (refused != null) {
                    return Outcome.fail(refused);
                }
            }
            default -> {
                return Outcome.fail("That prize can't be given like this.");
            }
        }
        if (charge && !plugin.tokens().spend(id, cost, TokenService.Source.PRIZE, p.display())) {
            return Outcome.fail("You need " + cost + " tokens.");
        }
        switch (p.type()) {
            case TRAIL -> {
                int days = daysOverride > 0 ? daysOverride : p.days();
                if (!plugin.trails().grant(player, p, days)) {
                    if (charge) {
                        plugin.tokens().grant(id, cost, TokenService.Source.REFUND, p.display());
                    }
                    return Outcome.fail("Couldn't add that trail — nothing charged. Tell an admin.");
                }
                label = p.display() + " &7(" + days + (days == 1 ? " day)" : " days)");
                item = icon(p);
            }
            case COMMAND -> {
                if (!runCommands(player, p)) {
                    if (charge) {
                        plugin.tokens().grant(id, cost, TokenService.Source.REFUND, p.display());
                    }
                    return Outcome.fail("That didn't work — nothing charged. Tell an admin.");
                }
                item = icon(p);
            }
            case HOME_SLOT -> {
                int now = plugin.homes().total(player) + 1;
                plugin.homes().refresh(player, 1); // this slot is recorded right after
                label = "&a+1 Home &7(you have " + now + " now)";
                item = icon(p);
            }
            case TROPHY -> {
                try {
                    dao.setState(TROPHY_SERIAL, trophyNumber);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not save the trophy serial: " + e.getMessage());
                }
                giveOrDrop(player, item);
                Bukkit.broadcast(Text.of("&6✦ &e" + player.getName() + " &6won Arcade Trophy #" + trophyNumber + "!"));
            }
            default -> giveOrDrop(player, item);
        }
        return Outcome.won(item.clone(), label);
    }

    /** Run a COMMAND row's console commands; false if any threw or was not handled. */
    private boolean runCommands(Player player, Prize p) {
        for (String raw : p.commands()) {
            String cmd = raw.replace("%player%", player.getName()).replace("%uuid%", player.getUniqueId().toString());
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (!ok) {
                    // Unknown or unhandled: the perk was not given, so the purchase must not stand.
                    plugin.getLogger().warning("Prize '" + p.id() + "' for " + player.getName() + ": '" + cmd
                            + "' was not handled by the server — refunding. Check the command in arcade.prizes.");
                    return false;
                }
                plugin.getLogger().info("Prize '" + p.id() + "' for " + player.getName() + ": ran '" + cmd + "'");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Prize '" + p.id() + "' command '" + cmd + "' failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** A display icon for a row: its material, named. For reveals and menus. */
    public static ItemStack icon(Prize p) {
        return com.dierks.homecraft.gui.Menus.icon(p.icon().material() != null ? p.icon().material() : Material.PAPER,
                p.display());
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
