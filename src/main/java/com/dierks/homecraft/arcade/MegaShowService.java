package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.MegaShow.Look;
import com.dierks.homecraft.arcade.MegaShow.Shot;
import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Text;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Mega Firework Shows playing now (0.38.1; the Super, Ultra and Ultimate tiers from 0.38.2): each one a timer that
 * plays its {@link MegaShow#plan} from up to {@value MegaShow#MAX_LAUNCHERS} Firework Launchers with harmless fireworks
 * ({@link Fireworks}, tagged so they hurt nobody).
 *
 * <p><b>Where.</b> A user with a Firework Remote set ({@link RemoteService}) fires from its first launchers that can
 * fire, as many as the row's {@code launchers}, in link order (#1 is the first lane), wherever they are: one in another
 * world, gone, or whose chunk isn't loaded and ticking is skipped, and one line says how many and why; none that can
 * is refused and kept. Without a set, a show fires from the nearest launchers (anyone's) within
 * {@code arcade.launcher.range} of whoever uses it, ordered left to right, as 0.38.1 did. Either way they are found
 * once at the start from the placed-block records (one indexed read) and only in chunks that are loaded and ticking: a
 * chunk is never loaded for a show. With fewer than the row asks for it fires from those ({@link MegaShow#forLaunchers}).
 *
 * <p><b>For its minute.</b> Walking away, teleporting or dying doesn't cut it short: it plays at its launchers. It ends
 * early only when none of its launchers' chunks is still ticking (the area unloaded, or its world did), when its user
 * quits ({@link #onQuit}) or when the plugin stops ({@link #stop()}). No refund when it is cut short (as Party Time);
 * plain memory, so a restart ends it. A launcher broken (or changed) during a show is skipped from then on.
 *
 * <p><b>One at a time.</b> A launcher is in one show at a time: a show that would use a launcher already in one is
 * refused, never moved on to farther free ones ({@link Claims}). The user's one long show ({@link FireworkShows}) is
 * held for the whole show, so a Grand show and a Mega show never play together for one player, and the server plays at
 * most {@value MegaShow#MAX_RUNNING} at once. Every refusal comes before anything is held or used up.
 *
 * <p>Each tick a show does an index step, a few chunk checks and its spawns: never the database, never a chunk load.
 */
public final class MegaShowService implements Listener {

    private final HomeCraftManagement plugin;
    /** Which launchers are in which show. */
    private final Claims claims = new Claims();
    /** Every show playing, by its number. */
    private final Map<Long, Playing> playing = new HashMap<>();

    /** One show playing: whose, the long show it holds, and its timer. */
    private static final class Playing {
        private final UUID user;
        private final long held;
        private BukkitTask task;

        private Playing(UUID user, long held) {
            this.user = user;
            this.held = held;
        }
    }

    public MegaShowService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /**
     * Start {@code player}'s Mega show of {@code row}: null when it started (the caller uses the item up), otherwise
     * the line to say, and nothing was held, claimed or used. The order is {@link MegaShow#refusal}'s.
     */
    public String start(Player player, Prize row) {
        FireworkShows shows = plugin.fireworkShows();
        if (shows == null || !MegaShow.isMega(row)) {
            return MegaShow.CANT_START;
        }
        UUID id = player.getUniqueId();
        boolean here = shows.playsHere(player);
        boolean longOn = shows.longShowOn(id);
        int want = row.extra().launchers();
        List<Spot> linked = plugin.remotes() == null ? List.of() : plugin.remotes().linked(id);
        MegaShow.Pick pick = !here || longOn ? MegaShow.Pick.NONE
                : linked.isEmpty() ? MegaShow.Pick.of(near(player, want)) // 0.38.1: the nearest within range
                : pickLinked(player, linked, want); // the first that can fire, in link order
        List<Spot> found = pick.spots();
        String none = linked.isEmpty() ? MegaShow.NO_LAUNCHER : MegaShow.noneLinkedLine(pick);
        String refused = MegaShow.refusal(here, longOn, found.size(), none, claims.busy(found), claims.running());
        if (refused != null) {
            return refused;
        }
        // Main thread, so neither can fail now; if one ever did, the other is given back and nothing is used.
        long held = shows.holdLong(id);
        if (held == 0) {
            return PrizeItemListener.SHOW_ON;
        }
        long show = claims.tryClaim(id, found);
        if (show == 0) {
            shows.releaseLong(id, held);
            return MegaShow.BUSY;
        }
        // A linked set fires in its own order (#1 is the first lane); the nearest ones left to right as the user sees them.
        List<Spot> ordered = linked.isEmpty() ? MegaShow.leftToRight(found, player.getX(), player.getZ(),
                player.getYaw()) : found;
        int lanes = MegaShow.lanes(row);
        List<Shot> shots = MegaShow.forLaunchers(MegaShow.plan(lanes, MegaShow.seconds(row)), lanes, ordered.size());
        // A holiday's look, decided as it starts: the show keeps it to the end.
        HolidayShows.Theme theme = FireworkShows.themeToday(plugin);
        String name = Text.plain(row.display());
        int end = MegaShow.endTick(shots);
        String world = player.getWorld().getName();
        // Each launcher's block as it is now: one broken or changed later is skipped.
        Material[] looks = new Material[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            Spot s = ordered.get(i);
            looks[i] = player.getWorld().getBlockAt(s.x(), s.y(), s.z()).getType();
        }
        int[] tick = {-1};
        int[] next = {0};
        Playing p = new Playing(id, held);
        playing.put(show, p);
        // The timer runs from the next tick, after this returns, so p.task is set before its first step.
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int t = ++tick[0];
            World w = plugin.getServer().getWorld(world);
            if (w == null || !anyTicking(w, ordered)) {
                end(show); // its world went, or none of its launchers' chunks is ticking any more
                return;
            }
            while (next[0] < shots.size() && shots.get(next[0]).tick() <= t) {
                fire(w, ordered, looks, shots.get(next[0]++), lanes, theme);
            }
            if (t >= end) {
                end(show); // its own show only, never a later one
            }
        }, 0L, 1L);
        player.sendMessage(Text.of(MegaShow.startLine(name, theme, ordered.size())));
        String skipped = MegaShow.skippedLine(pick);
        if (skipped != null) {
            player.sendMessage(Text.of(skipped));
        }
        if (plugin.prizes() != null) {
            plugin.prizes().announce(player, MegaShow.announceLine(player.getName(), name, theme));
        }
        return null;
    }

    /**
     * The launchers a linked set's show fires from ({@link MegaShow#pickLinked}): one records read for "recorded", and
     * for each in the user's world its chunk asked first (never loaded), then its block. A record whose block is air
     * there is stale and forgotten, and every gone one leaves the sets ({@link RemoteService#launcherGone}).
     */
    private MegaShow.Pick pickLinked(Player player, List<Spot> linked, int want) {
        CustomBlockService blocks = plugin.blockService();
        if (blocks == null) {
            return MegaShow.Pick.NONE;
        }
        World w = player.getWorld();
        Map<Spot, UUID> recorded = new HashMap<>();
        for (PlacedBlock pb : blocks.findByType(CustomBlockType.FIREWORK_LAUNCHER)) {
            recorded.put(new Spot(pb.world(), pb.x(), pb.y(), pb.z()), pb.owner());
        }
        // No records at all is a read that failed (findByType is empty on a database error), never every launcher
        // gone: each is then known by its block alone, so a set is never unlinked for a database hiccup.
        Predicate<Spot> isRecorded = recorded.isEmpty() ? s -> true : recorded::containsKey;
        MegaShow.Pick pick = MegaShow.pickLinked(linked, w.getName(), isRecorded, s -> ticking(w, s),
                s -> {
                    Block b = w.getBlockAt(s.x(), s.y(), s.z());
                    if (b.getType().isAir()) {
                        blocks.removeAt(b.getLocation());
                        return false;
                    }
                    return true;
                }, want);
        if (plugin.remotes() != null) {
            for (Spot gone : pick.gone()) {
                plugin.remotes().launcherGone(gone, recorded.get(gone));
            }
        }
        return pick;
    }

    /** Whether the launcher at {@code block} is in a show right now (its tap says so). */
    public boolean busy(Block block) {
        return claims.busy(spotOf(block));
    }

    /** A player who quits takes their Mega shows with them: the rockets already up finish, nothing more goes up. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        for (long show : claims.showsOf(event.getPlayer().getUniqueId())) {
            end(show);
        }
    }

    /** Every show stops now: the plugin is stopping. */
    public void stop() {
        for (long show : new ArrayList<>(playing.keySet())) {
            end(show);
        }
        claims.releaseAll();
    }

    /** Show {@code show} is over: its timer, its launchers and its user's long show are free. */
    private void end(long show) {
        Playing p = playing.remove(show);
        claims.release(show);
        if (p == null) {
            return;
        }
        if (p.task != null) {
            p.task.cancel();
        }
        FireworkShows shows = plugin.fireworkShows();
        if (shows != null) {
            shows.releaseLong(p.user, p.held);
        }
    }

    /**
     * The launchers a show of {@code launchers} fires from with no linked set: every recorded launcher in the player's
     * world whose chunk is loaded and ticking (asked in that order, so no chunk is ever loaded), nearest first within
     * range. A record whose block is air there is stale (a WorldEdit clear) and is forgotten (and leaves every set).
     */
    private List<Spot> near(Player player, int launchers) {
        CustomBlockService blocks = plugin.blockService();
        if (blocks == null) {
            return List.of();
        }
        World w = player.getWorld();
        String name = w.getName();
        List<Spot> out = new ArrayList<>();
        for (PlacedBlock pb : blocks.findByType(CustomBlockType.FIREWORK_LAUNCHER)) {
            if (!pb.world().equals(name) || !w.isChunkLoaded(pb.x() >> 4, pb.z() >> 4)) {
                continue; // never load one
            }
            if (w.getChunkAt(pb.x() >> 4, pb.z() >> 4).getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) {
                continue; // a rocket there would hang frozen
            }
            Block b = w.getBlockAt(pb.x(), pb.y(), pb.z());
            if (b.getType().isAir()) {
                blocks.removeAt(b.getLocation());
                if (plugin.remotes() != null) {
                    plugin.remotes().launcherGone(new Spot(pb.world(), pb.x(), pb.y(), pb.z()), pb.owner());
                }
                continue;
            }
            out.add(new Spot(pb.world(), pb.x(), pb.y(), pb.z()));
        }
        return MegaShow.nearest(out, name, player.getX(), player.getY(), player.getZ(),
                plugin.config().launcher().range(), launchers);
    }

    /** Whether any of a show's launchers is still where rockets fly: its chunk loaded and entity-ticking. */
    private static boolean anyTicking(World w, List<Spot> spots) {
        for (Spot s : spots) {
            if (ticking(w, s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A launcher's chunk is loaded (asked first: never load it) and entity-ticking: whether it can fire now. The one
     * test for it, so My Show's tiles and the holograms say what a show will do (review LINK-LOADED-1: a chunk at the
     * edge of the view distance is loaded but not ticking, and was "here" on My Show while the show skipped it as not
     * loaded).
     */
    public static boolean ticking(World w, Spot s) {
        return w.isChunkLoaded(s.x() >> 4, s.z() >> 4)
                && w.getChunkAt(s.x() >> 4, s.z() >> 4).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    /**
     * One rocket from its launcher's top, in its lane's look in a plan of {@code lanes} (the holiday's in a window):
     * skipped while its chunk isn't ticking, or when the launcher is gone.
     */
    private static void fire(World w, List<Spot> ordered, Material[] looks, Shot shot, int lanes,
                             HolidayShows.Theme theme) {
        Spot s = ordered.get(shot.launcher());
        if (!ticking(w, s) || w.getBlockAt(s.x(), s.y(), s.z()).getType() != looks[shot.launcher()]) {
            return;
        }
        Look look = MegaShow.look(shot, lanes, theme);
        Fireworks.spawn(new Location(w, s.x() + 0.5, s.y() + 1.1, s.z() + 0.5), look.colours(), look.fade(),
                look.type(), look.flicker(), look.trail(), shot.height().ticks());
    }

    private static Spot spotOf(Block b) {
        return new Spot(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    /**
     * Which launchers are in which show, and whose each show is: plain state, so a test drives it. A show holds its
     * launchers from its claim until {@link #release} with its own number, which only its own end (its timer, its
     * user's quit, or the stop) calls; an old number changes nothing (red-team ECON-2's shape, as Party Time).
     */
    static final class Claims {
        private long last;
        private final Map<Long, UUID> users = new LinkedHashMap<>();
        private final Map<Spot, Long> spots = new HashMap<>();

        /**
         * Claim {@code want} for {@code user}'s new show: its number, or 0 (nothing claimed) when there is nothing to
         * claim, one of them is in a show, or {@value MegaShow#MAX_RUNNING} shows are on.
         */
        long tryClaim(UUID user, Collection<Spot> want) {
            if (want.isEmpty() || busy(want) || running() >= MegaShow.MAX_RUNNING) {
                return 0;
            }
            long show = ++last;
            users.put(show, user);
            for (Spot s : want) {
                spots.put(s, show);
            }
            return show;
        }

        /** Whether the launcher at {@code s} is in a show. */
        boolean busy(Spot s) {
            return spots.containsKey(s);
        }

        /** Whether any of {@code want} is in a show. */
        boolean busy(Collection<Spot> want) {
            for (Spot s : want) {
                if (busy(s)) {
                    return true;
                }
            }
            return false;
        }

        /** How many shows are on. */
        int running() {
            return users.size();
        }

        /** Whether show {@code show} is on. */
        boolean on(long show) {
            return users.containsKey(show);
        }

        /** Show {@code show} is over: its launchers are free. Any other number (an old one) frees nothing. */
        boolean release(long show) {
            if (users.remove(show) == null) {
                return false;
            }
            spots.values().removeIf(v -> v == show);
            return true;
        }

        /** {@code user}'s shows that are on (for a quit). */
        List<Long> showsOf(UUID user) {
            List<Long> out = new ArrayList<>();
            for (Map.Entry<Long, UUID> e : users.entrySet()) {
                if (e.getValue().equals(user)) {
                    out.add(e.getKey());
                }
            }
            return out;
        }

        /** Whatever is on is over (the plugin is stopping). */
        void releaseAll() {
            users.clear();
            spots.clear();
        }
    }
}
