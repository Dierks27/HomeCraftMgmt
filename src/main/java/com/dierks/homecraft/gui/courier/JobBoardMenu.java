package com.dierks.homecraft.gui.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.courier.CourierJob;
import com.dierks.homecraft.courier.CourierService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The Courier job board — a Site on the PC (§2.2).
 *
 * <p>Two states and nothing else: a board of bands you can take a run in, or the one run you
 * are on. A player may only have one job at a time, which is what keeps the screen this
 * simple and the daily caps meaningful.
 */
public final class JobBoardMenu extends Menu {

    /** One tile per band, spaced so the three read as choices rather than a list. */
    private static final int[] BAND_SLOTS = {11, 13, 15};

    private final Player player;
    private final Runnable back;

    public JobBoardMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(45, Text.of("&2Courier &8· &7job board"));
    }

    @Override
    protected void build() {
        CourierService courier = plugin.courier();
        CourierJob job = courier.active(player);
        if (job != null) {
            buildActive(courier, job);
        } else {
            buildBoard(courier);
        }
        if (back != null) {
            set(40, Menus.icon(Material.BARRIER, "&cBack to the PC"), e -> back.run());
        } else {
            set(40, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        }
    }

    /** The board: what you could take, and what it would pay. */
    private void buildBoard(CourierService courier) {
        set(4, Menus.icon(Material.FILLED_MAP, "&2Courier runs",
                "&7Take a crate somewhere and get paid",
                "&7for the distance and how you travel.",
                "&8—",
                "&8Walking pays most. Flying pays least.",
                "&8Creative flight pays nothing."), null);

        CourierJob.Band[] bands = CourierJob.Band.values();
        for (int i = 0; i < BAND_SLOTS.length && i < bands.length; i++) {
            CourierJob.Band band = bands[i];
            int left = courier.remaining(player, band);
            double[] range = courier.estimate(band);
            var cfg = plugin.config().courier().band(band);
            if (cfg == null) {
                continue;
            }
            if (left <= 0) {
                set(BAND_SLOTS[i], Menus.icon(Material.GRAY_DYE, "&8" + band.display(),
                        "&8" + cfg.min() + "–" + cfg.max() + " blocks",
                        "&8—", "&8All used up for today."), null);
                continue;
            }
            set(BAND_SLOTS[i], Menus.icon(icon(band), "&a" + band.display(),
                    "&7Distance: &f" + cfg.min() + "–" + cfg.max() + " blocks",
                    "&7On foot: &6" + plugin.economy().format(range[0])
                            + " &7– &6" + plugin.economy().format(range[1]),
                    "&7Runs left today: &f" + left,
                    "&8—",
                    "&eLeft-click &7— courier run (you carry their crate)",
                    "&eRight-click &7— trade run (sells what you hold)"), e -> {
                boolean trade = e.isRightClick();
                ItemStack held = player.getInventory().getItemInMainHand();
                // Finding a drop-off means generating terrain that may be four thousand
                // blocks away, so the board says what it is doing and answers when it knows.
                player.closeInventory();
                player.sendMessage(Text.of("&7Finding you a drop-off…"));
                courier.accept(player, band,
                        trade ? CourierJob.Type.TRADE_RUN : CourierJob.Type.COURIER, held)
                        .thenAccept(r -> {
                            if (!r.ok()) {
                                player.sendMessage(Text.of("&c" + r.error()));
                            } else {
                                announce(r.job());
                            }
                        });
            });
        }
    }

    /** The run you are on: where it is, how long you have, and how to finish it. */
    private void buildActive(CourierService courier, CourierJob job) {
        Location way = job.waypoint();
        long left = Math.max(0, job.expiresAt() - System.currentTimeMillis());
        String distanceAway = way != null && player.getWorld() == way.getWorld()
                ? Math.round(player.getLocation().distance(way)) + " blocks away"
                : "in " + job.world();

        set(4, Menus.icon(Material.FILLED_MAP, "&aYour run",
                "&7" + courier.describe(job),
                "&7Drop-off: &f" + distanceAway,
                "&7Time left: &f" + Menus.duration(left),
                "&8—",
                "&8" + (way == null ? "" : "x " + job.wayX() + ", z " + job.wayZ())), null);

        if (job.type() == CourierJob.Type.TRADE_RUN) {
            set(20, Menus.icon(Material.CHEST, "&6Cargo",
                    "&7" + job.cargoAmount() + "x " + job.cargoMaterial(),
                    "&7Quoted at &6" + plugin.economy().format(job.lockedCargoValue()),
                    "&8—",
                    "&8Sold into the market when you arrive,",
                    "&8at the price it is worth then."), null);
        }

        if (plugin.config().courier().packageEnabled()
                && job.type() == CourierJob.Type.COURIER) {
            boolean carried = com.dierks.homecraft.courier.CourierPackage
                    .carried(player, job.id());
            set(20, Menus.icon(carried ? Material.CHEST : Material.GRAY_DYE,
                    carried ? "&6Crate" : "&7Crate missing",
                    carried ? "&7You are carrying it." : "&7It is not in your bag.",
                    "&8—",
                    carried ? "&8Hand it to them in person."
                            : "&8Check where you died, or a chest at home."), null);
        }

        set(22, Menus.icon(Material.LIME_DYE, "&aHand it over",
                "&7Only works at the drop-off.",
                "&8—", "&eClick when you get there"), e -> {
            var r = courier.turnIn(player);
            if (!r.ok()) {
                player.sendMessage(Text.of("&c" + r.error()));
                refresh();
                return;
            }
            player.closeInventory();
            report(r);
        });

        set(24, Menus.icon(Material.RED_DYE, "&cDrop this run",
                "&7Gives the slot back — it does not",
                "&7count against today's runs.",
                "&8—", "&eClick to abandon"), e -> {
            var r = courier.abandon(player);
            player.sendMessage(Text.of(r.ok() ? "&7Run dropped." : "&c" + r.error()));
            refresh();
        });
    }

    private void announce(CourierJob job) {
        player.sendMessage(Text.of("&a✔ Run accepted: &f" + plugin.courier().describe(job)));
        player.sendMessage(Text.of("&7Head for &fx " + job.wayX() + ", z " + job.wayZ()
                + "&7. Someone will be waiting for it."));
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

    private Material icon(CourierJob.Band band) {
        return switch (band) {
            case LOCAL -> Material.LEATHER_BOOTS;
            case REGIONAL -> Material.IRON_BOOTS;
            case LONG_HAUL -> Material.DIAMOND_BOOTS;
        };
    }
}
