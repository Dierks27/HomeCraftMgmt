package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Prize Counter (§3.9): the known-outcome half of the Arcade, one screen per tab.
 *
 * <p>Every tile puts the important words in its NAME — what it is and what it costs — because
 * Bedrock shows lore only on tap-and-hold. The lore says whether you can have it: "Need 12 more",
 * "1 left today", "You have this!". Expensive rows show even when you cannot afford them, with a
 * progress bar ("180 / 400"), because something to save up for is the point of a sink. Anything
 * costing {@value #CONFIRM_AT}+ tokens asks "are you sure?" first, because children misclick.
 */
public final class PrizeCounterMenu extends Menu {

    /** At or above this price a purchase goes through a confirm screen. */
    static final int CONFIRM_AT = 100;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    private static final int[] TAB_SLOTS = {2, 3, 4, 5, 6, 7};

    private final Player player;
    private final Runnable back;
    private final PrizeTab tab;
    private final int page;

    public PrizeCounterMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        this(plugin, player, back, PrizeTab.BOOSTS, 0);
    }

    public PrizeCounterMenu(HomeCraftManagement plugin, Player player, Runnable back, PrizeTab tab, int page) {
        super(plugin);
        this.player = player;
        this.back = back;
        this.tab = tab == null ? PrizeTab.BOOSTS : tab;
        this.page = Math.max(0, page);
        init(54, Text.of("&5&lPrize Counter &8· &5" + this.tab.label()));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        int tokens = plugin.tokens().balance(player.getUniqueId());
        set(0, Menus.icon(Material.SUNFLOWER, "&eYou have &6" + tokens + " tokens",
                "&7Everything here has a fixed price.",
                "&7No luck needed — you see what you get."), null);

        PrizeTab[] tabs = PrizeTab.values();
        for (int i = 0; i < tabs.length && i < TAB_SLOTS.length; i++) {
            PrizeTab t = tabs[i];
            boolean current = t == tab;
            set(TAB_SLOTS[i], Menus.glint(Menus.icon(tabMaterial(t), (current ? "&a&l" : "&f") + t.label(),
                    current ? "&7You're here." : "&eClick to look"), current),
                    current ? null : e -> new PrizeCounterMenu(plugin, player, back, t, 0).open(player));
        }

        List<Prize> rows = plugin.prizes().visible(tab);
        if (rows.isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&7Nothing here yet", "&8Check back soon!"), null);
        }
        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE && from + i < rows.size(); i++) {
            Prize p = rows.get(from + i);
            set(GRID_START + i, tile(p, tokens), e -> click(p));
        }
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new PrizeCounterMenu(plugin, player, back, tab, page - 1).open(player));
        }
        if (from + GRID_SIZE < rows.size()) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new PrizeCounterMenu(plugin, player, back, tab, page + 1).open(player));
        }
        // Slot 49 is the way out and never an arrow (NavAnchorTest).
        if (back != null) {
            set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back.run());
        } else {
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        }
    }

    private static Material tabMaterial(PrizeTab t) {
        return switch (t) {
            case BOOSTS -> Material.SUGAR;
            case HUNT -> Material.COMPASS;
            case COSMETICS -> Material.FIREWORK_ROCKET;
            case PERKS -> Material.NAME_TAG;
            case TROPHIES -> Material.GOLD_BLOCK;
            case MINIS -> Material.PAPER;
        };
    }

    /** One row's tile: its name and price, then whether and why you can or can't have it. */
    private ItemStack tile(Prize p, int tokens) {
        boolean owned = plugin.prizes().owned(player, p);
        String locked = plugin.prizes().lockedBecause(player, p);
        int left = plugin.prizes().left(player.getUniqueId(), p);
        boolean free = p.type() == PrizeType.TRADE_IN;
        int cost = p.costTokens();
        boolean afford = free || tokens >= cost;

        String name = free ? p.display() : p.display() + " &7- &6" + cost + " tokens";
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        String limit = plugin.prizes().limitText(player.getUniqueId(), p);
        boolean available = true;
        if (owned) {
            lore.add("&a✔ You have this!");
            available = false;
        } else if (locked != null) {
            lore.add("&7" + locked);
            available = false;
        } else if (left <= 0) {
            lore.add("&7None left " + (p.limit() != null && p.limit().per() == com.dierks.homecraft.config.PluginConfig.LimitPer.DAY
                    ? "today" : "this week") + ".");
            available = false;
        } else if (!afford) {
            lore.add("&cNeed " + (cost - tokens) + " more tokens");
            if (cost >= CONFIRM_AT) {
                lore.add("&7" + tokens + " / " + cost + " " + bar(tokens, cost));
            }
        } else {
            lore.add(free ? "&eClick to open" : "&eClick to buy");
        }
        if (limit != null && !owned) {
            lore.add("&e" + limit);
        }
        ItemStack icon = (p.type() == PrizeType.HAT || p.type() == PrizeType.TROPHY) && p.texture() != null
                && !p.texture().isBlank() ? Heads.base(p.texture()) : new ItemStack(p.icon().material());
        var meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(Text.of(line));
            }
            meta.lore(l);
            icon.setItemMeta(meta);
        }
        return Menus.glint(icon, available && afford);
    }

    private static String bar(int have, int need) {
        int cells = 10;
        int filled = need <= 0 ? cells : (int) Math.min(cells, Math.floor((double) have / need * cells));
        StringBuilder sb = new StringBuilder("&8[");
        for (int i = 0; i < cells; i++) {
            sb.append(i < filled ? "&a|" : "&7|");
        }
        return sb.append("&8]").toString();
    }

    private void click(Prize p) {
        switch (p.type()) {
            case TRADE_IN -> new TradeInMenu(plugin, player, this::reopen).open(player);
            case QUEST_REROLL -> new QuestRerollMenu(plugin, player, p, this::reopen).open(player);
            default -> {
                if (p.choosesColor()) {
                    new FilamentColorMenu(plugin, player, p, this::reopen).open(player);
                } else if (p.costTokens() >= CONFIRM_AT) {
                    new ConfirmMenu(plugin, "&5Buy " + Text.plain(p.display()) + "?",
                            tile(p, plugin.tokens().balance(player.getUniqueId())),
                            List.of("&7Costs &6" + p.costTokens() + " tokens&7.",
                                    "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + "&7."),
                            "&7Click to buy it.", () -> buy(p), this::reopen).open(player);
                } else {
                    buy(p);
                }
            }
        }
    }

    private void buy(Prize p) {
        var r = plugin.prizes().buy(player, p, null);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(player);
            reopen();
            return;
        }
        if (p.type() == PrizeType.PITY) {
            new RevealMenu(plugin, player, r, this::reopen).open(player);
            return;
        }
        player.sendMessage(Text.of("&a✔ You got " + r.label() + "&a!"));
        Sounds.paid(player);
        reopen();
    }

    private void reopen() {
        new PrizeCounterMenu(plugin, player, back, tab, page).open(player);
    }
}
