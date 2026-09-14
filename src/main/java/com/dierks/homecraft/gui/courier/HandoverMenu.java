package com.dierks.homecraft.gui.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.courier.CourierJob;
import com.dierks.homecraft.courier.CourierService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The screen you get for right-clicking the person waiting for your crate.
 *
 * <p>Deliberately not the job board. The board is where you take work; this is the end of one
 * trip, so it shows what was agreed, what it is about to pay, and a single thing to press.
 */
public final class HandoverMenu extends Menu {

    private final Player player;

    public HandoverMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(45, Text.of("&2Delivery &8· &7hand it over"));
    }

    @Override
    protected void build() {
        CourierService courier = plugin.courier();
        CourierJob job = courier.active(player);
        if (job == null) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7Nothing to hand over",
                    "&7You have no run on right now."), null);
            set(40, Menus.icon(Material.BARRIER, "&cClose"),
                    e -> e.getWhoClicked().closeInventory());
            return;
        }

        set(4, Menus.icon(Material.FILLED_MAP, "&aYour run",
                "&7" + courier.describe(job),
                "&7Time left: &f" + Menus.duration(
                        Math.max(0, job.expiresAt() - System.currentTimeMillis())),
                "&8—",
                "&8Walking pays most. Flying pays least."), null);

        if (job.type() == CourierJob.Type.TRADE_RUN) {
            set(20, Menus.icon(Material.CHEST, "&6Cargo",
                    "&7" + job.cargoAmount() + "x " + job.cargoMaterial(),
                    "&7Quoted at &6" + plugin.economy().format(job.lockedCargoValue()),
                    "&8—",
                    "&8Sold into the market on hand-over,",
                    "&8at the price it is worth now."), null);
        }

        boolean needsCrate = plugin.config().courier().packageEnabled()
                && job.type() == CourierJob.Type.COURIER;
        boolean inHand = !needsCrate
                || com.dierks.homecraft.courier.CourierPackage.inHand(player, job.id());
        boolean carried = !needsCrate
                || com.dierks.homecraft.courier.CourierPackage.carried(player, job.id());

        if (needsCrate) {
            set(20, Menus.icon(inHand ? Material.LIME_DYE
                            : carried ? Material.YELLOW_DYE : Material.GRAY_DYE,
                    inHand ? "&aCrate in hand" : carried ? "&eCrate in your bag" : "&7No crate",
                    inHand ? "&7Ready to hand over."
                            : carried ? "&7Hold it out to them first."
                            : "&7You are not carrying this delivery.",
                    "&8—",
                    "&8They want it handed over, not described."), null);
        }

        set(22, Menus.icon(inHand ? Material.LIME_DYE : Material.GRAY_DYE,
                inHand ? "&aHand over the crate" : "&7Hand over the crate",
                inHand ? "&7They have been expecting this."
                        : carried ? "&7Put the crate in your hand first."
                        : "&7You do not have their crate.",
                "&8—", "&eClick to deliver"), e -> {
            CourierService.Result r = courier.turnIn(player, true);
            if (!r.ok()) {
                player.sendMessage(Text.of("&c" + r.error()));
                refresh();
                return;
            }
            player.closeInventory();
            report(r);
        });

        set(24, Menus.icon(Material.RED_DYE, "&cNot yet",
                "&7Keep the run and come back.",
                "&8—", "&eClick to step away"),
                e -> e.getWhoClicked().closeInventory());

        set(40, Menus.icon(Material.BARRIER, "&cClose"),
                e -> e.getWhoClicked().closeInventory());
    }

    /** What the run actually paid, and — when it matters — why it was less. */
    private void report(CourierService.Result r) {
        player.sendMessage(Text.of("&a✔ Delivered. &7Travel fee: &6"
                + plugin.economy().format(r.paid())
                + " &8(×" + String.format("%.2f", r.multiplier()) + " travel)"));
        if (r.cargoPaid() > 0) {
            player.sendMessage(Text.of("&7Cargo sold into the market for &6"
                    + plugin.economy().format(r.cargoPaid()) + "&7."));
        }
        if (r.shortfall()) {
            player.sendMessage(Text.of("&7Most of that distance was not travelled, "
                    + "so the fee was reduced."));
        }
    }
}
