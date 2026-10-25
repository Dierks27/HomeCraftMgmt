package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.FireworkShow.Burst;
import com.dierks.homecraft.arcade.FireworkShow.Spot;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Whereabouts;
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
 *
 * <p><b>A long show stays out of the games</b> (review SHOW-GAMES-1), as Party Time does: it ends when its player goes
 * into a game or a Games world ({@link #playsHere}), so the rest of a Grand show never bursts on a course, and one isn't
 * started there (the item is kept). A short show plays as it always has.
 *
 * <p>A Mega Firework Show (0.38.1) is a long show too, played at its launchers by {@link MegaShowService}: it holds its
 * player's one long show here ({@link #holdLong}) for as long as it plays.
 *
 * <p>In a holiday window (0.38.2) a long show takes that holiday's look, read once when it starts
 * ({@link #themeToday}); a short show is never themed.
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
        boolean isLong = FireworkShow.isLong(seconds);
        long show = guard.tryStart(id, isLong, System.currentTimeMillis());
        if (show == 0) {
            return false;
        }
        List<Burst> bursts = FireworkShow.schedule(seconds);
        long last = bursts.get(bursts.size() - 1).tick();
        // A holiday's look, decided as it starts: the show keeps it to the end. Never for a short show.
        HolidayShows.Theme theme = isLong ? themeToday(plugin) : null;
        long[] tick = {0};
        int[] next = {0};
        Playing p = new Playing(id);
        playing.put(show, p);
        // The timer runs from the next tick, after this returns, so p.task is set before its first step.
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long t = tick[0];
            tick[0] += FireworkShow.BURST_TICKS;
            if (!player.isOnline() || (isLong && !playsHere(player))) {
                end(show); // a quit, or a long show's player went into a game: the rest is no show for a course
                return;
            }
            while (next[0] < bursts.size() && bursts.get(next[0]).tick() <= t) {
                int n = next[0]++;
                burst(player, bursts.get(n), n, theme);
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

    /**
     * Take {@code player}'s one long show for a show played somewhere else (the Mega Firework Show, at the launchers:
     * {@link MegaShowService}): its number, or 0 (nothing held) when their long show is on. It is held until
     * {@link #releaseLong} with that number, their quit or the stop, so a Grand show and a Mega show never play
     * together for one player. Its start stamps the refusal gap as a long show's does.
     */
    public long holdLong(UUID player) {
        return guard.tryStart(player, true, System.currentTimeMillis());
    }

    /** The long show {@link #holdLong} numbered {@code show} is over; any other number (an old one) frees nothing. */
    public void releaseLong(UUID player, long show) {
        guard.end(player, show);
    }

    /** Whether a long show plays where {@code player} is now: not in a game, nor in a Games world. */
    public boolean playsHere(Player player) {
        return playsHere(Whereabouts.inGameSession(plugin, player), Whereabouts.gamesWorld(plugin, player.getWorld()));
    }

    /**
     * {@link #playsHere(Player)} on its facts: not in a game session (on its way in or out too) and not in a Games
     * world, as Party Time's guests ({@code PartyService.guest}): a game's course is no place for rockets. Pure.
     */
    static boolean playsHere(boolean inGameSession, boolean gamesWorld) {
        return !inGameSession && !gamesWorld;
    }

    /**
     * Whether a refused show should say so now: at most once every {@value Guard#REFUSAL_GAP_MS} ms a player, since
     * Bedrock repeats a held tap's use and each repeat is refused again. A long show's start counts as the last
     * refusal, so the repeats of the very tap that started it are quiet too.
     */
    public boolean refusalDue(UUID player) {
        return guard.refusalDue(player, System.currentTimeMillis());
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
        guard.forget(player);
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

    /**
     * Burst number {@code index} around where the player is now: each firework in the holiday's look when there is one
     * ({@link FireworkShow#look}), else its random one, as always.
     */
    private static void burst(Player player, Burst b, int index, HolidayShows.Theme theme) {
        Location at = player.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < b.fireworks(); i++) {
            Spot s = FireworkShow.spot(b.kind(), i, b.fireworks(), r);
            Location where = at.clone().add(s.dx(), s.dy(), s.dz());
            MegaShow.Look look = FireworkShow.look(theme, b.kind(), index, i);
            if (look == null) {
                Fireworks.spawn(where, s.power());
            } else {
                Fireworks.spawn(where, s.power(), look.colours(), look.fade(), look.type(), look.flicker(),
                        look.trail());
            }
        }
    }

    /**
     * The holiday's theme today ({@code arcade.holiday_shows} on the local date in {@code clock.time_zone}), or null:
     * no window today, or nothing to ask (a test's plugin, or the config not read yet). Asked when a long show starts.
     */
    public static HolidayShows.Theme themeToday(HomeCraftManagement plugin) {
        try {
            return plugin == null || plugin.config() == null ? null
                    : plugin.config().holidayShows().themeOn(plugin.clock().today());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One long show a player at a time: each show's number (0 = refused) and whose long show is on. Plain state, so a
     * test drives it. A long show is on until {@link #end} is called with its own number, which only its own timer
     * (or the player's quit) does; a short show never holds anything.
     */
    static final class Guard {
        /** The least time between two refusal lines to one player. */
        static final long REFUSAL_GAP_MS = 2000;

        private long last;
        private final Map<UUID, Long> longShows = new HashMap<>();
        private final Map<UUID, Long> refusedAt = new HashMap<>();

        /**
         * Start one at {@code now} (ms): its number, or 0 (nothing started) when it is long and the player's long show
         * is on. A long start is stamped as the player's last refusal (review SHOW-REFUSE-3): a held use repeats at
         * once, and its first repeat, refused, would otherwise say "Wait for your show to finish!" as the show begins.
         */
        long tryStart(UUID player, boolean isLong, long now) {
            if (isLong && longShows.containsKey(player)) {
                return 0;
            }
            long show = ++last;
            if (isLong) {
                longShows.put(player, show);
                refusedAt.put(player, now);
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

        /**
         * Whether a refusal at {@code now} (ms) is said: the first, and then once {@link #REFUSAL_GAP_MS} have passed
         * since the last one said or the player's last long show began.
         */
        boolean refusalDue(UUID player, long now) {
            Long at = refusedAt.get(player);
            if (at != null && now - at < REFUSAL_GAP_MS) {
                return false;
            }
            refusedAt.put(player, now);
            return true;
        }

        /** The player quit: nothing of theirs is kept. */
        void forget(UUID player) {
            longShows.remove(player);
            refusedAt.remove(player);
        }

        /** Whatever is on is over (the plugin is stopping). */
        void endAll() {
            longShows.clear();
            refusedAt.clear();
        }
    }
}
