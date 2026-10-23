package com.dierks.homecraft.gui.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ScoresMenu;
import com.dierks.homecraft.trade.TradeService;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Who do you want to trade with?" (SHOP-V2-SPEC §4.2): {@code /trade} with no name, and the PC's Trade site. The
 * {@code GiftPickerMenu} pattern: one tile per other online player the viewer can see, paper on Bedrock and a head
 * on Java, and why someone can't be asked in the NAME ("not here", "busy", "trades off"), because Bedrock shows
 * lore only on a long press; only the green ones tap. 36 a page, the arrows on 45/53, the way out on 49.
 *
 * <p>The bottom row has the viewer's own {@code Trade requests: on / off} switch (47), so a parent can switch
 * the daughter's off with one tap, no typing (red team O12).
 */
public final class TradePickerMenu extends Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    static final int TOGGLE = 47;

    private final TradeService trades;
    private final Player viewer;
    private final Runnable back;
    private final int page;

    public TradePickerMenu(HomeCraftManagement plugin, TradeService trades, Player viewer, Runnable back) {
        this(plugin, trades, viewer, back, 0);
    }

    private TradePickerMenu(HomeCraftManagement plugin, TradeService trades, Player viewer, Runnable back, int page) {
        super(plugin);
        this.trades = trades;
        this.viewer = viewer;
        this.back = back;
        this.page = Math.max(0, page);
        init(54, Text.of("&5Who do you want to trade with?"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        set(4, Menus.icon(Material.BUNDLE, "&aTrade", "&7Pick someone to trade with.",
                "&7You both put things on the table,", "&7then both press Ready."), null);
        List<Player> people = people();
        int p = clampPage(page, people.size());
        if (people.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No one else is online", "&8Try again when they are."), null);
        }
        boolean bedrock = Bedrock.is(viewer);
        int from = p * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE && from + i < people.size(); i++) {
            Player r = people.get(from + i);
            String note = trades.pickerNote(viewer, r);
            String name = tileName(r.getName(), note);
            String[] lore = note == null ? new String[]{"&eClick to ask"} : new String[0];
            ItemStack icon = bedrock ? Menus.icon(Material.PAPER, name, lore) : ScoresMenu.playerHead(r, name, lore);
            UUID id = r.getUniqueId();
            set(GRID_START + i, icon, note == null ? e -> ask(id) : null);
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new TradePickerMenu(plugin, trades, viewer, back, p - 1).open(viewer));
        }
        if (p < pages(people.size()) - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new TradePickerMenu(plugin, trades, viewer, back, p + 1).open(viewer));
        }
        boolean on = trades.takesTrades(viewer.getUniqueId());
        set(TOGGLE, Menus.icon(on ? Material.LIME_DYE : Material.GRAY_DYE, toggleName(on),
                on ? "&7Tap to stop people asking you." : "&7Tap to let people ask you again."), e -> {
            trades.setTakesTrades(viewer.getUniqueId(), !trades.takesTrades(viewer.getUniqueId()));
            refresh();
        });
        // Slot 49 is the way out and never an arrow (NavAnchorTest).
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                viewer.closeInventory();
            }
        });
    }

    private void ask(UUID id) {
        Player r = Bukkit.getPlayer(id); // looked up again: the tile is a snapshot
        viewer.closeInventory();
        trades.ask(viewer, r);
    }

    /** Everyone the picker lists, by name: online, not the viewer, and seen by the viewer. */
    private List<Player> people() {
        List<Player> out = new ArrayList<>();
        for (Player r : Bukkit.getOnlinePlayers()) {
            if (!r.getUniqueId().equals(viewer.getUniqueId()) && viewer.canSee(r)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** "&amp;a<name>" for someone who can be asked, else "&amp;7<name> &amp;8- <why>". */
    static String tileName(String name, String note) {
        return note == null ? "&a" + name : "&7" + name + " &8- " + note;
    }

    /** The switch's NAME. */
    static String toggleName(boolean on) {
        return on ? "&aTrade requests: on" : "&7Trade requests: off";
    }

    static int pages(int count) {
        return Math.max(1, (count + GRID_SIZE - 1) / GRID_SIZE);
    }

    static int clampPage(int page, int count) {
        return Math.max(0, Math.min(page, pages(count) - 1));
    }
}
