package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.FireworkShow.Burst;
import com.dierks.homecraft.arcade.FireworkShow.Spot;
import com.dierks.homecraft.util.Fireworks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Firework Shows playing now (0.38.1): each one a timer that plays its {@link FireworkShow#schedule} around its
 * player with harmless fireworks ({@link Fireworks}, tagged so they hurt nobody).
 *
 * <p><b>One long show at a time.</b> A player may have one show of {@value FireworkShow#LONG_SECONDS} seconds or longer
 * playing; using another while it plays is refused ({@link #start} is false, so the item isn't used up), which keeps a
 * pile of Grand Firework Shows from filling the sky (and the server) all at once. A short show is never refused,
 * stacked or not, so a pile of Firework Shows plays exactly as before. A show stops when its player quits
 * ({@link #stop(UUID)}), and every timer is cancelled when the plugin stops ({@link #stop()}). Plain memory: a restart
 * ends a show early, which costs nobody anything they could keep.
 */
public final class FireworkShows {

    private final HomeCraftManagement plugin;
    /** Whose long show is on. */
    private final Guard guard = new Guard();
    /** Every show playing, by its number, for a quit or a stop. */
    private final Map<Long, Playing> playing = new HashMap<>();

    /** One show playing: whose, and its timer. */
    private static final class Playing {
        private final UUID player;
        private BukkitTask task;

        private Playing(UUID player) {
            this.player = player;
        }
    }

    public FireworkShows(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /**
     * Play a show of {@code seconds} around {@code player}; false (nothing started) when it is a long one and their
     * long show is still on. The first burst goes off on the next tick, as it always has.
     */
    public boolean start(Player player, int seconds) {
        UUID id = player.getUniqueId();
        long show = guard.tryStart(id, FireworkShow.isLong(seconds));
        if (show == 0) {
            return false;
        }
        List<Burst> bursts = FireworkShow.schedule(seconds);
        long last = bursts.get(bursts.size() - 1).tick();
        long[] tick = {0};
        int[] next = {0};
        Playing p = new Playing(id);
        playing.put(show, p);
        // The timer runs from the next tick, after this returns, so p.task is set before its first step.
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long t = tick[0];
            tick[0] += FireworkShow.BURST_TICKS;
            if (!player.isOnline()) {
                end(show);
                return;
            }
            while (next[0] < bursts.size() && bursts.get(next[0]).tick() <= t) {
                burst(player, bursts.get(next[0]++));
            }
            if (t >= last) {
                end(show); // its own show only, never a later one
            }
        }, 0L, FireworkShow.BURST_TICKS);
        return true;
    }

    /** Whether {@code player}'s long show is on. */
    public boolean longShowOn(UUID player) {
        return guard.longOn(player);
    }

    /** {@code player} quit: their shows stop now. */
    public void stop(UUID player) {
        List<Long> theirs = new ArrayList<>();
        for (Map.Entry<Long, Playing> e : playing.entrySet()) {
            if (e.getValue().player.equals(player)) {
                theirs.add(e.getKey());
            }
        }
        for (long show : theirs) {
            end(show);
        }
    }

    /** Every show stops now: the plugin is stopping. */
    public void stop() {
        for (Playing p : playing.values()) {
            if (p.task != null) {
                p.task.cancel();
            }
        }
        playing.clear();
        guard.endAll();
    }

    /** Show {@code show} is over: its timer is cancelled and, if it was a long one, its player may start another. */
    private void end(long show) {
        Playing p = playing.remove(show);
        if (p == null) {
            return;
        }
        if (p.task != null) {
            p.task.cancel();
        }
        guard.end(p.player, show);
    }

    /** One burst around where the player is now. */
    private static void burst(Player player, Burst b) {
        Location at = player.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < b.fireworks(); i++) {
            Spot s = FireworkShow.spot(b.kind(), i, b.fireworks(), r);
            Fireworks.spawn(at.clone().add(s.dx(), s.dy(), s.dz()), s.power());
        }
    }

    /**
     * One long show a player at a time: each show's number (0 = refused) and whose long show is on. Plain state, so a
     * test drives it. A long show is on until {@link #end} is called with its own number, which only its own timer
     * (or the player's quit) does; a short show never holds anything.
     */
    static final class Guard {
        private long last;
        private final Map<UUID, Long> longShows = new HashMap<>();

        /** Start one: its number, or 0 (nothing started) when it is long and the player's long show is on. */
        long tryStart(UUID player, boolean isLong) {
            if (isLong && longShows.containsKey(player)) {
                return 0;
            }
            long show = ++last;
            if (isLong) {
                longShows.put(player, show);
            }
            return show;
        }

        /** Whether {@code player}'s long show is on. */
        boolean longOn(UUID player) {
            return longShows.containsKey(player);
        }

        /** Show {@code show} of {@code player} is over; any other show (a short one, an old one) frees nothing. */
        void end(UUID player, long show) {
            longShows.remove(player, show);
        }

        /** Whatever is on is over (the plugin is stopping). */
        void endAll() {
            longShows.clear();
        }
    }
}
