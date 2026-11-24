package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.holdem.Holdem;
import com.dierks.homecraft.games.chance.holdem.HoldemSettings;
import com.dierks.homecraft.games.chance.holdem.HoldemTable;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Texas Hold'em's three tables, to pick one (27 slots; 0.57.0):
 *
 * <pre>
 *  4 how to play
 *  11 the friendly table (free play chips, NPC players)
 *  13 the tokens table   (one chip, one token)
 *  15 the money table    (one chip, one dollar; only for a player who may play for money here)
 *  22 Back / Close
 * </pre>
 *
 * A tap sits you down (or, at a full table, lets you watch). Nothing here moves tokens or money: buying chips is on the
 * table's own screen, through the play gate.
 */
public final class HoldemLobbyMenu extends GameMenu {

    private final Holdem holdem;

    public HoldemLobbyMenu(HomeCraftManagement plugin, Holdem game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.holdem = game;
        init(27, Text.of("&2Texas Hold'em &8- pick a table"));
    }

    @Override
    protected void build() {
        fill();
        set(4, rulesTile(holdem.rules()), null);
        HoldemSettings s = holdem.settings();
        table(11, HoldemTable.Kind.FRIENDLY, Material.LIME_WOOL, List.of(
                "&7Free play chips: nothing goes in,", "&7nothing comes out.",
                "&7NPC players join in when it's quiet.",
                "&7You get &f" + s.friendlyChips() + " &7play chips (more when they run out)."));
        table(13, HoldemTable.Kind.TOKENS, Material.SUNFLOWER, List.of(
                "&7Buy chips with tokens: &f1 chip = 1 token&7.",
                "&7Get up any time between hands: your chips", "&7come back as tokens.",
                "&7Buy in with " + buyIns(s) + " tokens.", "&7Real players only."));
        if (holdem.games().parlor().offered(viewer)) {
            Parlor.Rules r = holdem.games().parlor().rules();
            table(15, HoldemTable.Kind.MONEY, Material.GOLD_INGOT, List.of(
                    "&7Buy chips with money: &f1 chip = $1&7.",
                    "&7Get up any time between hands: your chips", "&7come back as money.",
                    "&7Buy in with up to " + Parlor.dollars(r.maxStake()) + ".", "&7Real players only."));
        }
        exitTile();
    }

    private static String buyIns(HoldemSettings s) {
        List<Integer> b = s.buyIns();
        if (b.size() == 1) {
            return Integer.toString(b.get(0));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < b.size(); i++) {
            out.append(i == 0 ? "" : i == b.size() - 1 ? " or " : ", ").append(b.get(i));
        }
        return out.toString();
    }

    private void table(int slot, HoldemTable.Kind kind, Material icon, List<String> about) {
        HoldemTable t = holdem.table(kind);
        List<String> lore = new ArrayList<>(about);
        lore.add("&7Blinds &f" + holdem.settings().blinds() + " &7chips.");
        List<HoldemTable.Seat> seats = t.seats();
        List<String> names = new ArrayList<>();
        for (HoldemTable.Seat seat : seats) {
            names.add(seat.npc() != null ? seat.name() : "&f" + seat.name());
        }
        lore.add("&7Seats taken: &f" + seats.size() + " of " + HoldemTable.MAX_SEATS
                + (names.isEmpty() ? "" : " &8(" + String.join("&8, ", names) + "&8)"));
        boolean full = seats.size() >= HoldemTable.MAX_SEATS && (kind != HoldemTable.Kind.FRIENDLY
                || t.npcCount() == 0);
        lore.add(full ? "&eFull: click to watch" : "&eClick to sit down");
        ItemStack tile = Menus.count(Menus.icon(icon, "&a" + kind.label, lore.toArray(new String[0])),
                Math.max(1, seats.size()));
        set(slot, tile, e -> {
            hold(com.dierks.homecraft.gui.games.ClickHold.SETTLE_MS);
            new HoldemMenu(plugin, holdem, t, viewer, back).open(viewer);
        });
    }
}
