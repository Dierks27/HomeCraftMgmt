package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.storage.CourierDao;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The Courier engine: accept a run, travel, hand it over, get paid.
 *
 * <p>Everything that decides the payout is locked when the job is accepted — the clamped
 * distance, the cargo's quote, and the player's movement counters — so running a longer way
 * round earns nothing extra and the fee cannot be re-priced by anything that happens on the
 * way. The multiplier comes from {@link TravelLedger}, blended by how far the player moved
 * under each kind of power rather than by which one they used last.
 *
 * <p>Phase 1 is economy only: no buildings, no villagers, and turn-in is proximity to the
 * waypoint. Phase 2 replaces that with a placed depot.
 */
public final class CourierService {

    /** The outcome of accepting or handing in a job (a failure carries a player-facing reason). */
    public record Result(boolean ok, String error, CourierJob job, double paid, double cargoPaid,
                         double multiplier, boolean shortfall) {
        static Result fail(String error) {
            return new Result(false, error, null, 0, 0, 0, false);
        }
    }

    private final HomeCraftManagement plugin;
    private final CourierDao dao;
    private final WaypointService waypoints;
    private BukkitTask sweeper;

    public CourierService(HomeCraftManagement plugin, CourierDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.waypoints = new WaypointService(plugin);
    }

    public void start() {
        stop();
        if (!plugin.config().courier().enabled()) {
            return;
        }
        // Close out runs nobody finished. Jobs carry their own deadline, so this only has to
        // run often enough that a band slot comes back in reasonable time — not on any tick.
        sweeper = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                dao.expireStale(System.currentTimeMillis());
            } catch (SQLException e) {
                plugin.getLogger().warning("Courier expiry sweep failed: " + e.getMessage());
            }
        }, 20L * 60L, 20L * 60L);
    }

    public void stop() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
    }

    /** The player's live job, or null. */
    public CourierJob active(Player player) {
        try {
            CourierJob job = dao.active(player.getUniqueId());
            if (job != null && job.expired(System.currentTimeMillis())) {
                dao.finish(job.id(), CourierJob.State.EXPIRED);
                return null;
            }
            return job;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read courier job: " + e.getMessage());
            return null;
        }
    }

    /** How many runs of a band the player has left today. */
    public int remaining(Player player, CourierJob.Band band) {
        PluginConfig.CourierBand cfg = plugin.config().courier().band(band);
        if (cfg == null) {
            return 0;
        }
        try {
            return Math.max(0, cfg.perDay() - dao.usedToday(player.getUniqueId(), band, today()));
        } catch (SQLException e) {
            return 0;
        }
    }

    /** What a run of this band pays on foot, before any multiplier — for the board's estimate. */
    public double[] estimate(CourierJob.Band band) {
        PluginConfig.Courier cfg = plugin.config().courier();
        PluginConfig.CourierBand b = cfg.band(band);
        if (b == null) {
            return new double[] {0, 0};
        }
        return new double[] {fee(cfg, b.min(), 1.0), fee(cfg, b.max(), 1.0)};
    }

    /**
     * Take a run. The waypoint is rolled now, not when the board was drawn, so the distance
     * the player is paid for is the one actually generated for them.
     *
     * @param cargo for a trade run, the stack being carried; ignored for a courier run
     */
    public Result accept(Player player, CourierJob.Band band, CourierJob.Type type, ItemStack cargo) {
        PluginConfig.Courier cfg = plugin.config().courier();
        if (!cfg.enabled()) {
            return Result.fail("The courier board is closed.");
        }
        if (!plugin.sandbox().check(player, "courier accept")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        if (active(player) != null) {
            return Result.fail("You already have a run on. Finish or abandon it first.");
        }
        PluginConfig.CourierBand b = cfg.band(band);
        if (b == null || b.perDay() <= 0) {
            return Result.fail("That band is not being offered.");
        }
        if (remaining(player, band) <= 0) {
            return Result.fail("You have used all your " + band.display().toLowerCase()
                    + " runs for today.");
        }

        String cargoId = null;
        int cargoAmount = 0;
        double quote = 0;
        if (type == CourierJob.Type.TRADE_RUN) {
            if (cargo == null || cargo.getType().isAir()) {
                return Result.fail("Hold the cargo you want to run in your main hand.");
            }
            MarketItem item = marketItemFor(cargo.getType());
            if (item == null) {
                return Result.fail(pretty(cargo.getType()) + " is not something the market buys.");
            }
            cargoId = item.id();
            cargoAmount = cargo.getAmount();
            quote = plugin.market().quoteSell(cargoId, cargoAmount).total();
        }

        Location from = player.getLocation();
        Location way = waypoints.find(player, from, b.min(), b.max());
        if (way == null) {
            return Result.fail("No usable drop-off out that way right now — try again.");
        }
        int distance = clampedDistance(cfg, from, way);
        long now = System.currentTimeMillis();

        CourierJob job = new CourierJob(0, player.getUniqueId(), type, CourierJob.State.ACTIVE, band,
                from.getWorld().getName(),
                from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                way.getBlockX(), way.getBlockY(), way.getBlockZ(),
                distance, cargoId, cargoAmount, quote,
                TravelLedger.encode(TravelLedger.snapshot(player)),
                today(), now, now + cfg.expireMinutes() * 60_000L);
        try {
            job = dao.insert(job);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to write courier job: " + e.getMessage());
            return Result.fail("Could not take that run — try again.");
        }
        return new Result(true, null, job, 0, 0, 0, false);
    }

    /**
     * Hand the run in. The player has to be standing near the waypoint; what they are paid
     * depends on how they got there.
     */
    public Result turnIn(Player player) {
        PluginConfig.Courier cfg = plugin.config().courier();
        if (!plugin.sandbox().check(player, "courier turn-in")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        CourierJob job = active(player);
        if (job == null) {
            return Result.fail("You have no run on.");
        }
        Location way = job.waypoint();
        if (way == null || player.getWorld() != way.getWorld()) {
            return Result.fail("You are not at the drop-off.");
        }
        if (player.getLocation().distance(way) > cfg.turnInRadius()) {
            return Result.fail("You are not at the drop-off yet.");
        }

        // The travel multiplier, blended by how far they moved under each kind of power.
        TravelLedger.Blend blend = TravelLedger.since(player, job.snapshot(), cfg.travel());
        double fee = fee(cfg, job.lockedDistance(), blend.multiplier());

        // Anti-teleport. Deliberately quiet: the smaller number is the message, and a player
        // who took a nether portal shortcut has not cheated, they have just not walked it.
        boolean shortfall = blend.blocks() < cfg.teleportFloor() * job.lockedDistance();
        if (shortfall) {
            fee *= cfg.teleportPayout();
            plugin.getLogger().fine(() -> "Courier job " + job.id() + " for " + player.getName()
                    + ": tracked " + Math.round(blend.blocks()) + "b of a " + job.lockedDistance()
                    + "b run (" + TravelLedger.describe(player, job.snapshot()) + ") — travel fee reduced.");
        }

        // The cargo goes through the market as a real sale, so stock moves +N exactly as if
        // they had sold it at home and the daily sell limits apply as normal. It is paid at
        // the LIVE rate, not the quote locked at acceptance: the locked figure is what the
        // board promised and what the job row records, but paying above the market would make
        // the difference newly minted money, and only the travel fee is meant to be new.
        double cargoPaid = 0;
        if (job.type() == CourierJob.Type.TRADE_RUN) {
            if (!hasCargo(player, job)) {
                return Result.fail("You are not carrying the cargo any more.");
            }
            var sale = plugin.market().sell(player, job.cargoMaterial(), job.cargoAmount());
            if (!sale.ok()) {
                return Result.fail(sale.error());
            }
            cargoPaid = sale.amount();
        }

        try {
            if (!dao.finish(job.id(), CourierJob.State.DELIVERED)) {
                return Result.fail("That run has already been closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close courier job: " + e.getMessage());
            return Result.fail("Could not close that run — tell an admin.");
        }

        if (fee > 0) {
            plugin.economy().deposit(player, fee);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        return new Result(true, null, job, fee, cargoPaid, blend.multiplier(), shortfall);
    }

    /** Give up on the run. The band slot comes back — an abandoned job is not a spent one. */
    public Result abandon(Player player) {
        CourierJob job = active(player);
        if (job == null) {
            return Result.fail("You have no run on.");
        }
        try {
            dao.finish(job.id(), CourierJob.State.EXPIRED);
        } catch (SQLException e) {
            return Result.fail("Could not drop that run — try again.");
        }
        return new Result(true, null, job, 0, 0, 0, false);
    }

    // ---- helpers --------------------------------------------------------------

    /** {@code (base + perBlock × distance) × multiplier}. */
    private double fee(PluginConfig.Courier cfg, int distance, double multiplier) {
        return (cfg.base() + cfg.perBlock() * distance) * multiplier;
    }

    /** Horizontal straight line, clamped to the configured band of payable distances. */
    private int clampedDistance(PluginConfig.Courier cfg, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        return (int) Math.round(Math.max(cfg.minDistance(), Math.min(cfg.maxDistance(), flat)));
    }

    /** The market catalog entry that buys this material, or null. */
    private MarketItem marketItemFor(Material material) {
        for (MarketItem item : plugin.market().catalog()) {
            if (item.material() == material) {
                return item;
            }
        }
        return null;
    }

    private boolean hasCargo(Player player, CourierJob job) {
        MarketItem item = plugin.market().item(job.cargoMaterial());
        if (item == null) {
            return false;
        }
        int have = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == item.material()) {
                have += stack.getAmount();
            }
        }
        return have >= job.cargoAmount();
    }

    /** UTC epoch-day, the same key every other daily tally here uses. */
    private long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    private String pretty(Material material) {
        String n = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A short player-facing summary of a live job, for the board and for chat. */
    public String describe(CourierJob job) {
        return job.band().display() + " · " + job.lockedDistance() + " blocks"
                + (job.type() == CourierJob.Type.TRADE_RUN ? " · trade run" : " · courier");
    }
}
