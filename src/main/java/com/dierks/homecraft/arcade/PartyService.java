package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Whereabouts;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Party Time (SHOP-V2-SPEC §5.11, Q10): one party at a time on the server. For the row's {@code seconds}, every
 * second two harmless fireworks ({@link Fireworks}, tagged so they hurt nobody) burst around each guest: every
 * online player in an economy world who isn't in a game or a Games world and doesn't hide other people's trails
 * and parties (the host always gets theirs). One broadcast, which counts against the host's once-a-minute
 * announcements ({@link PrizeService#announce}).
 *
 * <p>A buy while a party is on is refused before any charge ({@link PrizeService#PARTY_ON}). Whether one is on lives
 * here, in memory, so a restart ends a party early, which costs nobody anything they could keep. A party is on from
 * its start until its own timer's last round ends it (red-team finding ECON-2): never a wall-clock end that a late
 * tick can outrun, and a timer only ever ends its own party and itself, so it can never cut a later one short.
 */
public final class PartyService {

    /** A round of fireworks every this many ticks: one a second. */
    static final long ROUND_TICKS = 20;
    /** Fireworks around each guest per round. */
    static final int PER_GUEST = 2;

    private final HomeCraftManagement plugin;
    /** Which party is on, and who threw it. */
    private final Schedule schedule = new Schedule();
    /** The party's timer, once its first round ran (for a stop). */
    private BukkitTask task;
    /**
     * Whether a player hides other people's trails and parties (the hide-others switch, §5.6). Nobody does until
     * the switch is plugged in ({@link #hidesOthers(Predicate)}).
     */
    private Predicate<UUID> hidesOthers = id -> false;

    public PartyService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Whether a party is on right now. */
    public boolean running() {
        return schedule.running();
    }

    /** Plug in the per-viewer hide-others switch (§5.6): no fireworks are spawned around a player it says hides. */
    public void hidesOthers(Predicate<UUID> hides) {
        this.hidesOthers = hides == null ? id -> false : hides;
    }

    /**
     * Throw a party for {@code host} lasting {@code seconds} (5-30), with its broadcast; false (nothing started)
     * while another is on.
     */
    public boolean start(Player host, int seconds) {
        long party = schedule.tryStart(host.getUniqueId());
        if (party == 0) {
            return false;
        }
        int[] left = {Schedule.rounds(seconds)};
        plugin.getServer().getScheduler().runTaskTimer(plugin, self -> {
            if (left[0]-- <= 0 || !schedule.on(party)) {
                self.cancel(); // this timer, never the field: a later party's timer is its own
                schedule.end(party);
                return;
            }
            task = self;
            round();
        }, 0L, ROUND_TICKS);
        plugin.prizes().announce(host, "&d✦ " + host.getName() + " started a party!");
        return true;
    }

    /** End the party now: the plugin is stopping. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        schedule.endAll();
    }

    /** One round: two fireworks around each guest. */
    private void round() {
        UUID host = schedule.host();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!guest(plugin.sandbox().allowed(p.getWorld()), Whereabouts.inGameSession(plugin, p),
                    Whereabouts.gamesWorld(plugin, p.getWorld()), hidesOthers.test(id), id.equals(host))) {
                continue;
            }
            for (int i = 0; i < PER_GUEST; i++) {
                Fireworks.spawn(around(p.getLocation()));
            }
        }
    }

    /** A spot 2.5 to 4.5 blocks from the guest, a little above their feet, so the rocket bursts in view. */
    private static Location around(Location at) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        double dist = 2.5 + r.nextDouble(2.0);
        return at.clone().add(Math.cos(angle) * dist, 0.5, Math.sin(angle) * dist);
    }

    /**
     * Whether a player gets fireworks this round: in an economy world, not in a game or a Games world (a game's
     * course is no place for rockets), and not hiding other people's parties, unless it is the host's own.
     */
    static boolean guest(boolean economyWorld, boolean inGame, boolean gamesWorld, boolean hidesOthers,
                         boolean host) {
        return economyWorld && !inGame && !gamesWorld && (host || !hidesOthers);
    }

    /**
     * One party at a time: which one is on (its number, 0 = none) and its host. Plain state, so a test drives it. A
     * party is on until {@link #end} is called with its own number, which only its own timer does.
     */
    static final class Schedule {
        private long on;
        private long last;
        private UUID host;

        /** Start one: its number, or 0 (nothing started) while another is on. */
        long tryStart(UUID host) {
            if (running()) {
                return 0;
            }
            this.host = host;
            this.on = ++last;
            return on;
        }

        boolean running() {
            return on != 0;
        }

        /** Whether party {@code party} is the one on. */
        boolean on(long party) {
            return party != 0 && on == party;
        }

        UUID host() {
            return host;
        }

        /** Party {@code party} is over; a call for any other party (an old timer's) changes nothing. */
        boolean end(long party) {
            if (!on(party)) {
                return false;
            }
            on = 0;
            host = null;
            return true;
        }

        /** Whatever is on is over (the plugin is stopping). */
        void endAll() {
            on = 0;
            host = null;
        }

        /** How many rounds a party of {@code seconds} has: one a second, 5 to 30 (the row's clamp). */
        static int rounds(int seconds) {
            return Math.min(30, Math.max(5, seconds));
        }
    }
}
