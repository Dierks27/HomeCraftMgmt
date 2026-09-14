package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.storage.CourierDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
    private final BuildingService buildings;

    /**
     * Live jobs by player, so the approach check can run every second without a query.
     *
     * <p>A cache, not a second source of truth: every write still goes through the DAO, and a
     * miss here just falls back to reading the row.
     */
    private final Map<UUID, CourierJob> live = new ConcurrentHashMap<>();

    /** Players with a waypoint search in flight, so a fast second click cannot take two runs. */
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    private BukkitTask sweeper;
    private BukkitTask approach;

    public CourierService(HomeCraftManagement plugin, CourierDao dao, BuildingService buildings) {
        this.plugin = plugin;
        this.dao = dao;
        this.buildings = buildings;
        this.waypoints = new WaypointService(plugin, buildings);
    }

    public BuildingService buildings() {
        return buildings;
    }

    public void start() {
        stop();
        if (!plugin.config().courier().enabled()) {
            return;
        }
        buildings.start();

        // Re-seed the live cache for everyone already online. start() runs on /hcm reload as
        // well as on enable, and stop() clears the cache — so without this a reload silently
        // stopped the approach check for every delivery in flight until its player relogged.
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            onJoin(online);
        }

        // Close out runs nobody finished, and take down the buildings they left behind. Jobs
        // carry their own deadline, so this only has to run often enough that a band slot
        // comes back in reasonable time — not on any tick.
        sweeper = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (dao.expireStale(System.currentTimeMillis()) > 0) {
                    live.values().removeIf(j -> j.expired(System.currentTimeMillis()));
                }
                buildings.sweep();
            } catch (SQLException e) {
                plugin.getLogger().warning("Courier expiry sweep failed: " + e.getMessage());
            }
        }, 20L * 60L, 20L * 60L);

        // The approach check. Cheap by construction: it walks only players who have a live
        // job, compares two numbers, and does nothing at all until someone is close enough.
        approach = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (live.isEmpty()) {
                return;
            }
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                CourierJob job = live.get(player.getUniqueId());
                if (job != null) {
                    considerPlacement(player, job);
                }
            }
        }, 40L, 20L);
    }

    public void stop() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        if (approach != null) {
            approach.cancel();
            approach = null;
        }
        // Buildings come down before the plugin does. A house that outlives the plugin that
        // knows how to remove it is the one outcome this module must never produce.
        buildings.stop();
        live.clear();
        searching.clear();
    }

    /**
     * Build the delivery site once the player is near enough for it to stream in with the
     * terrain rather than appear in front of them.
     *
     * <p>Horizontal distance only — a player on a mountain directly above the drop-off has
     * arrived for every purpose that matters here.
     */
    private void considerPlacement(Player player, CourierJob job) {
        if (!buildingsEnabled() || buildings.hasSite(job.id())) {
            return;
        }
        Location way = job.waypoint();
        if (way == null || player.getWorld() != way.getWorld()) {
            return;
        }
        double dx = player.getLocation().getX() - way.getX();
        double dz = player.getLocation().getZ() - way.getZ();
        int trigger = plugin.config().courier().building().placeAtBlocks();
        if (dx * dx + dz * dz > (double) trigger * trigger) {
            return;
        }
        buildings.placeFor(job).thenAccept(placed -> {
            if (!player.isOnline()) {
                return;
            }
            if (placed) {
                player.sendMessage(Text.of("&7Someone is expecting you at &fx " + job.wayX()
                        + ", z " + job.wayZ() + "&7."));
            } else if (buildings.wasRefused(job.id())) {
                // The promise was made at accept; the thing that decides whether it is true
                // runs 220 blocks later. If it turns out false, say so rather than letting the
                // player walk the rest of the way to an empty field.
                player.sendMessage(Text.of("&7No one could be found to take this one — "
                        + "hand it in at the PC instead."));
            }
        });
    }

    private boolean buildingsEnabled() {
        return plugin.config().courier().building().enabled();
    }

    /** Re-seed the live cache for a player who just joined mid-run. */
    public void onJoin(Player player) {
        CourierJob job = active(player);
        if (job != null) {
            live.put(player.getUniqueId(), job);
        }
    }

    public void onQuit(Player player) {
        live.remove(player.getUniqueId());
        searching.remove(player.getUniqueId());
    }

    /** The player's live job, or null. */
    public CourierJob active(Player player) {
        try {
            CourierJob job = dao.active(player.getUniqueId());
            if (job != null && job.expired(System.currentTimeMillis())) {
                dao.finish(job.id(), CourierJob.State.EXPIRED);
                live.remove(player.getUniqueId());
                scheduleRelease(job.id(), 0);
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
     * <p>Asynchronous, and the future always completes on the main thread. The cheap
     * validation below still runs immediately, but finding a drop-off means looking at terrain
     * up to four thousand blocks away that usually has to be generated first — see
     * {@link WaypointService}. Everything that decides the payout is still locked at the moment
     * the job row is written.
     *
     * @param cargo for a trade run, the stack being carried; ignored for a courier run
     */
    public CompletableFuture<Result> accept(Player player, CourierJob.Band band,
                                            CourierJob.Type type, ItemStack cargo) {
        PluginConfig.Courier cfg = plugin.config().courier();
        if (!cfg.enabled()) {
            return failed("The courier board is closed.");
        }
        if (!plugin.sandbox().check(player, "courier accept")) {
            return failed(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        if (active(player) != null) {
            return failed("You already have a run on. Finish or abandon it first.");
        }
        PluginConfig.CourierBand b = cfg.band(band);
        if (b == null || b.perDay() <= 0) {
            return failed("That band is not being offered.");
        }
        if (remaining(player, band) <= 0) {
            return failed("You have used all your " + band.display().toLowerCase()
                    + " runs for today.");
        }

        String cargoId = null;
        int cargoAmount = 0;
        double quote = 0;
        if (type == CourierJob.Type.TRADE_RUN) {
            if (cargo == null || cargo.getType().isAir()) {
                return failed("Hold the cargo you want to run in your main hand.");
            }
            MarketItem item = marketItemFor(cargo.getType());
            if (item == null) {
                return failed(pretty(cargo.getType()) + " is not something the market buys.");
            }
            cargoId = item.id();
            cargoAmount = cargo.getAmount();
            quote = plugin.market().quoteSell(cargoId, cargoAmount).total();
        }

        final String lockedCargoId = cargoId;
        final int lockedCargoAmount = cargoAmount;
        final double lockedQuote = quote;
        final Location from = player.getLocation();

        // Reserve the slot for the length of the search. Without this a player can click
        // three bands while the first is still looking and end up with three runs, because
        // the "you already have a run on" check reads a row that has not been written yet.
        if (!searching.add(player.getUniqueId())) {
            return failed("Still finding you a drop-off — hold on.");
        }

        return waypoints.find(player, from, b.min(), b.max())
                .thenApply(way -> {
                    searching.remove(player.getUniqueId());
                    if (way == null) {
                        return Result.fail("No usable drop-off out that way right now — try again.");
                    }
                    // Re-check: the search took time, and the player may have taken a run,
                    // logged off, or used up the band in another window meanwhile.
                    if (!player.isOnline()) {
                        return Result.fail("You went offline before a drop-off was found.");
                    }
                    if (active(player) != null) {
                        return Result.fail("You already have a run on.");
                    }
                    if (remaining(player, band) <= 0) {
                        return Result.fail("You have used all your "
                                + band.display().toLowerCase() + " runs for today.");
                    }

                    int distance = clampedDistance(cfg, from, way);
                    long now = System.currentTimeMillis();
                    CourierJob job = new CourierJob(0, player.getUniqueId(), type,
                            CourierJob.State.ACTIVE, band, from.getWorld().getName(),
                            from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                            way.getBlockX(), way.getBlockY(), way.getBlockZ(),
                            distance, lockedCargoId, lockedCargoAmount, lockedQuote,
                            TravelLedger.encode(TravelLedger.snapshot(player)),
                            today(), now, now + cfg.expireMinutes() * 60_000L);
                    try {
                        job = dao.insert(job);
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Failed to write courier job: " + e.getMessage());
                        return Result.fail("Could not take that run — try again.");
                    }
                    live.put(player.getUniqueId(), job);
                    // A short run can start inside the placement radius. Build it now, before
                    // the player turns to look, rather than letting it appear in front of them.
                    considerPlacement(player, job);
                    return new Result(true, null, job, 0, 0, 0, false);
                })
                .exceptionally(t -> {
                    searching.remove(player.getUniqueId());
                    plugin.getLogger().warning("Courier waypoint search failed: " + t);
                    return Result.fail("Could not find a drop-off — try again.");
                });
    }

    private CompletableFuture<Result> failed(String reason) {
        return CompletableFuture.completedFuture(Result.fail(reason));
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
        if (!arrived(player, job, cfg)) {
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
        live.remove(player.getUniqueId());
        // Let the building stand a moment. Taking it down on the same tick as the payout would
        // delete the ground under the player's feet while they are still reading the message.
        int linger = plugin.config().courier().building().lingerSeconds();
        // Tell the expiry sweep to leave it alone until then. Without this the 60-second sweep
        // reached it first — a delivered job is no longer ACTIVE, which is exactly what the
        // sweep looks for — and the house vanished within a second of the payout.
        buildings.holdUntil(job.id(), System.currentTimeMillis() + 1000L * linger);
        scheduleRelease(job.id(), linger);
        return new Result(true, null, job, fee, cargoPaid, blend.multiplier(), shortfall);
    }

    /**
     * True if the player is close enough to hand the crate over.
     *
     * <p>Two things worth knowing here. Distance is measured <b>horizontally</b>, because the
     * drop-off is a place on the map and not a height — a three-dimensional check failed a
     * player standing on the roof of the house they had just walked to. And when a building
     * has gone up, <b>the door counts as well as the waypoint</b>: rotation can put the front
     * step further from the waypoint than {@code turn_in_radius}, which would have left the
     * villager refusing a crate from someone standing directly in front of them.
     */
    private boolean arrived(Player player, CourierJob job, PluginConfig.Courier cfg) {
        double radius = cfg.turnInRadius();
        double limit = radius * radius;
        Location at = player.getLocation();
        if (within(at, job.wayX(), job.wayZ(), limit)) {
            return true;
        }
        DeliverySite site = buildings.siteFor(job.id());
        return site != null && within(at, site.doorX(), site.doorZ(), limit);
    }

    private boolean within(Location at, int x, int z, double limit) {
        double dx = at.getX() - (x + 0.5);
        double dz = at.getZ() - (z + 0.5);
        return dx * dx + dz * dz <= limit;
    }

    /** Take a delivery site down, now or after a delay. */
    private void scheduleRelease(long jobId, int afterSeconds) {
        if (!buildingsEnabled() || !buildings.hasSite(jobId)) {
            return;
        }
        if (afterSeconds <= 0) {
            buildings.release(jobId);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> buildings.release(jobId), 20L * afterSeconds);
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
        live.remove(player.getUniqueId());
        // Dropped on purpose, so there is nobody standing there to disturb: take it down now.
        scheduleRelease(job.id(), 0);
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
