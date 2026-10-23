package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.PrizeItems;
import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.LookDao.Look;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The look screen (SHOP-V2-SPEC §3.11, 27 slots): one look, named as its My Looks tile (13); what one tap does to it
 * (11: wear it or take it off, switch it on or off; not shown once it has ended); Buy again while the window is open
 * and the row is on the shelf (15, through the confirm at 100 tokens or more, as the counter's); Back (22). Opened
 * from My Looks' worn, on and ended tiles, from a rented tile at the Prize Counter, and from the {@code [Buy again]}
 * chat link ({@code /hcm looks renew <id>}): nothing is ever bought from chat.
 */
public final class LookMenu extends Menu {

    static final int ACTION = 11;
    static final int LOOK = 13;
    static final int BUY_AGAIN = 15;
    static final int BACK = 22;

    private final Player player;
    private final String id;
    private final Runnable back;

    public LookMenu(HomeCraftManagement plugin, Player player, String id, Runnable back) {
        super(plugin);
        this.player = player;
        this.id = id;
        this.back = back;
        Prize p = plugin.config().arcade().prize(id);
        init(27, Text.of("&d" + (p == null ? "My Looks" : Text.plain(p.display()))));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(BACK, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> goBack());
        Prize p = plugin.config().arcade().prize(id);
        Look right = plugin.looks().look(player.getUniqueId(), id);
        long now = plugin.clock().nowMillis();
        if (p == null || right == null || !LookRules.listed(right, now)) {
            set(LOOK, Menus.icon(Material.GRAY_DYE, "&7You don't have that look", "&7Find it at the Prize Counter."),
                    null);
            return;
        }
        boolean hat = p.type() == PrizeType.HAT;
        boolean live = LookRules.live(right, now);
        Prize worn = hat ? plugin.looks().worn(player) : null;
        boolean on = live && (hat ? worn != null && worn.id().equalsIgnoreCase(id) : right.enabled());
        MyLooksMenu.State state = MyLooksMenu.state(right, now, on);
        int days = LookRules.daysLeft(right.expiresAt(), now);
        String name = MyLooksMenu.tileName(hat, state, Text.plain(p.display()),
                LookRules.lastDay(right.expiresAt(), plugin.clock().zone()), days);
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        set(LOOK, Menus.count(look(p, name, lore), MyLooksMenu.amount(state, days)), null);

        if (live) {
            if (hat) {
                set(ACTION, on ? Menus.icon(Material.ORANGE_DYE, "&6Take it off", "&7It stays in My Looks.")
                        : Menus.icon(Material.LIME_DYE, "&aWear it", "&7Put it on your head."), e -> {
                            String why = on ? plugin.looks().takeOff(player, id) : plugin.looks().wear(player, id);
                            if (why != null) {
                                refuse(why);
                            }
                            refresh();
                        });
            } else {
                set(ACTION, on ? Menus.icon(Material.ORANGE_DYE, "&6Turn it off")
                        : Menus.icon(Material.LIME_DYE, "&aTurn it on"), e -> {
                            if (on) {
                                player.sendMessage(Text.of(plugin.trails().off(player)));
                                refresh();
                            } else {
                                player.closeInventory(); // so the burst is seen
                                player.sendMessage(Text.of(plugin.trails().select(player, id)));
                            }
                        });
            }
        }
        Window w = LookRules.window(right, now);
        if (w == Window.OPEN && p.rental() && plugin.prizes().visibleTo(player, p)) {
            int price = LookRules.price(p, w);
            set(BUY_AGAIN, Menus.icon(Material.SUNFLOWER, "&eBuy again - " + price + " tokens",
                    live ? "&7It adds " + LookRules.days(Math.max(1, p.days())) + " to your last day."
                            : "&7Yours again for " + LookRules.days(Math.max(1, p.days())) + "."),
                    e -> buyAgain(p, price));
        }
    }

    /** Buy again, through the confirm at 100 tokens or more (children misclick), as the Prize Counter does. */
    private void buyAgain(Prize p, int price) {
        if (price >= PrizeCounterMenu.CONFIRM_AT) {
            new ConfirmMenu(plugin, "&5Buy " + Text.plain(p.display()) + " again?",
                    Menus.icon(Material.SUNFLOWER, "&eBuy again - " + price + " tokens"),
                    List.of("&7Costs &6" + price + " tokens&7.",
                            "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + "&7."),
                    "&7Click to buy it.", () -> buy(p, price), this::reopen).open(player);
        } else {
            buy(p, price);
        }
    }

    private void buy(Prize p, int price) {
        boolean first = plugin.looks().firstBuy(player.getUniqueId(), p);
        var r = plugin.prizes().buy(player, p, null, price);
        if (!r.ok()) {
            refuse(r.error());
            reopen();
            return;
        }
        if (!first) {
            reopen(); // a renewal reopens; a first buy closed the screen for its moment (§3.5)
        }
    }

    /** The look's picture, never a tagged item (as My Looks draws it). */
    private ItemStack look(Prize p, String name, List<String> lore) {
        if (p.collector() != null && p.collector().look() != null) {
            return PrizeItems.capLook(p.collector().look(), name, lore);
        }
        if (p.keeper() && p.type() == PrizeType.HAT) {
            ItemStack keeper = PrizeItems.keeperLook(p, com.dierks.homecraft.arcade.Keepers.variant(
                    plugin.config().arcade().keepers(), p.id()), name, lore);
            if (keeper != null) {
                return keeper;
            }
        }
        ItemStack hat = PrizeItems.hatLook(p, name, lore);
        if (hat != null) {
            return hat;
        }
        if (p.type() == PrizeType.HAT && !p.keeper()) {
            return Heads.textured(!Bedrock.is(player) && p.texture() != null ? p.texture() : "", name, lore);
        }
        Material m = p.icon() != null && p.icon().material() != null ? p.icon().material() : Material.PAPER;
        return Menus.icon(m, name, lore.toArray(new String[0]));
    }

    private void refuse(String why) {
        player.sendMessage(Text.of("&c" + why));
        Sounds.refused(player);
    }

    private void goBack() {
        if (back != null) {
            back.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        new LookMenu(plugin, player, id, back).open(player);
    }
}
