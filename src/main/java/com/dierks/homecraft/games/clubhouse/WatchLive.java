package com.dierks.homecraft.games.clubhouse;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watch live (CLUBHOUSE-SPEC §10, §11): a Clubhouse visitor flies round the course being raced, in
 * spectator mode, inside their own Clubhouse session, and comes back to the Clubhouse when the race
 * or golf group ends (or with {@code /hcm play clubhouse}).
 *
 * <ul>
 *   <li><b>In their own session.</b> The trip out is the session's own teleport (checked), within
 *       the Clubhouse's world, then SPECTATOR as the session's own game mode (the game-mode guard
 *       keeps it, and every way out puts back the mode they came in with: it is in their saved
 *       state). Coming back always sets ADVENTURE again.</li>
 *   <li><b>The keeper.</b> Leaving the course's {@link WatchArea} (moving, or the spectator menu's
 *       teleport) puts them back at the nearest point inside, or cancels a teleport whose target is
 *       outside; riding along in a racer's view is only for players in THAT race or group.</li>
 *   <li><b>Never in the way.</b> A watcher is hidden from every player who isn't watching
 *       ({@link WatchVisibility}), and shown again on every way out.</li>
 *   <li><b>What they see.</b> The race's live positions on the action bar, with
 *       "/hcm play clubhouse to go back" (spectator mode can't use items).</li>
 * </ul>
 * Everything runs inside the Clubhouse's guard.
 */
final class WatchLive {

    /** What a watcher reads on the action bar, after the positions. */
    static final String HINT = "&8| &7/hcm play clubhouse to go back";
    static final String STARTED = "&bWatching live! &7Fly round the course. &e/hcm play clubhouse &7brings you"
            + " back, and &e/hcm play cheer &7cheers the racers on.";
    static final String OVER = "&7That's the end of it - back to the Clubhouse for the results!";
    static final String BACK = "&7Back in the Clubhouse.";
    static final String OUTSIDE = "&7That's outside the race - stay near the course.";
    static final String RIDE_ONLY = "&7You can ride along with the players in this race only.";
    static final String NOTHING = "&7No race or golf group is going right now.";
    static final String ELSEWHERE = "&7That course is in another world, so it can't be watched from the Clubhouse.";

    private final Clubhouse club;
    private final WatchVisibility visibility;
    private final java.util.function.Function<UUID, Player> online;
    /** Watcher to the race they watch ({@link LiveRace#key()}). */
    private final Map<UUID, String> watching = new HashMap<>();
    private List<LiveRace> live = List.of();

    WatchLive(Clubhouse club, WatchVisibility visibility) {
        this(club, visibility, Bukkit::getPlayer);
    }

    /** With the server's player lookup ({@code online}) passed in: a test's own. */
    WatchLive(Clubhouse club, WatchVisibility visibility, java.util.function.Function<UUID, Player> online) {
        this.club = club;
        this.visibility = visibility;
        this.online = online;
        this.nextTick = task -> club.games().later(club, 1, task);
    }

    /** Runs a task on the next tick (the framework's, inside the Clubhouse's guard; a test's own). */
    java.util.function.Consumer<Runnable> nextTick;

    /** A test's watcher: {@code player} watches the race {@code key} (as {@link #start} leaves them). */
    void watching(UUID player, String key) {
        watching.put(player, key);
        visibility.watch(player);
    }

    /** The races and groups going on now (made once a second). */
    void refresh(List<LiveRace> now) {
        live = now == null ? List.of() : List.copyOf(now);
    }

    List<LiveRace> live() {
        return live;
    }

    /** The race with this key going on now, or {@code null}. */
    LiveRace race(String key) {
        for (LiveRace r : live) {
            if (r.key().equals(key)) {
                return r;
            }
        }
        return null;
    }

    /** The race {@code player} is racing or playing in now, or {@code null}. */
    LiveRace raceOf(UUID player) {
        for (LiveRace r : live) {
            if (r.racers().contains(player)) {
                return r;
            }
        }
        return null;
    }

    /** Whether the player is watching live. */
    boolean watching(UUID player) {
        return watching.containsKey(player);
    }

    /** What the player watches, or {@code null}. */
    LiveRace watched(UUID player) {
        String key = watching.get(player);
        return key == null ? null : race(key);
    }

    // ---- out and back ---------------------------------------------------------------------------

    /**
     * Start watching {@code race}: to its view point inside the session (checked), then spectator
     * mode, hidden from everyone who isn't watching. {@code null} when they are watching, else why not.
     */
    String start(Player p, LiveRace race) {
        if (race == null) {
            return NOTHING;
        }
        Session s = club.games().sessions().session(p);
        if (s == null || s.phase() != Session.Phase.ACTIVE || !club.id().equals(s.gameId())) {
            return ClubhouseText.CLOSED;
        }
        World w = Bukkit.getWorld(race.world());
        if (w == null || !w.getName().equals(s.world())) {
            return ELSEWHERE;
        }
        WatchArea a = race.area();
        Location at = new Location(w, a.viewX(), a.viewY(), a.viewZ(), a.viewYaw(), 20f);
        if (!club.games().sessions().teleport(p, at)) {
            return "&cCouldn't take you there right now.";
        }
        if (!club.games().sessions().gameMode(p, GameMode.SPECTATOR)) {
            club.toSpawn(p);
            return "&cCouldn't start watching right now.";
        }
        watching.put(p.getUniqueId(), race.key());
        visibility.watch(p.getUniqueId());
        p.sendMessage(Text.of("&b" + race.title()));
        p.sendMessage(Text.of(STARTED));
        return null;
    }

    /** Back to the Clubhouse: adventure mode again, an arrival spot, seen by everyone again. */
    void stop(Player p, String line) {
        UUID id = p.getUniqueId();
        if (watching.remove(id) == null) {
            return;
        }
        try {
            p.setSpectatorTarget(null);
        } catch (RuntimeException | LinkageError ignored) {
            // nobody to stop riding along with
        }
        club.games().sessions().gameMode(p, GameMode.ADVENTURE);
        club.toSpawn(p);
        visibility.unwatch(id);
        club.kitAgain(p);
        if (line != null) {
            p.sendMessage(Text.of(line));
        }
    }

    /** Gone (any way out of the Clubhouse): seen by everyone again; the session's end puts their mode back. */
    void gone(UUID id) {
        watching.remove(id);
        visibility.gone(id);
    }

    /** The Clubhouse stops: everyone seen again. */
    void clear() {
        watching.clear();
        visibility.clear();
    }

    /**
     * Once a second: a watcher whose race is over comes back; one who drifted out of the area (riding
     * along with a racer who left it) is put back; the positions line; and anyone new hidden from.
     */
    void second() {
        for (Map.Entry<UUID, String> e : new ArrayList<>(watching.entrySet())) {
            Player p = online.apply(e.getKey());
            if (p == null) {
                gone(e.getKey());
                continue;
            }
            LiveRace r = race(e.getValue());
            if (r == null) {
                stop(p, OVER);
                continue;
            }
            Location l = p.getLocation();
            if (!r.area().contains(l.getX(), l.getY(), l.getZ())) {
                try {
                    p.setSpectatorTarget(null);
                } catch (RuntimeException | LinkageError ignored) {
                    // nothing to stop
                }
                double[] in = r.area().nearestInside(l.getX(), l.getY(), l.getZ());
                club.games().sessions().teleport(p, new Location(l.getWorld(), in[0], in[1], in[2], l.getYaw(),
                        l.getPitch()));
            }
            p.sendActionBar(Text.of((r.positions().isEmpty() ? "&7Watching live" : r.positions()) + " " + HINT));
        }
        visibility.reconcile();
    }

    // ---- the keeper -------------------------------------------------------------------------------

    /** A watcher's move out of the area stops at its edge. */
    void moved(PlayerMoveEvent e) {
        if (watching.isEmpty()) {
            return;
        }
        LiveRace r = watched(e.getPlayer().getUniqueId());
        Location to = e.getTo();
        if (r == null || r.area().contains(to.getX(), to.getY(), to.getZ())) {
            return;
        }
        double[] in = r.area().nearestInside(to.getX(), to.getY(), to.getZ());
        Location held = to.clone();
        held.setX(in[0]);
        held.setY(in[1]);
        held.setZ(in[2]);
        e.setTo(held);
    }

    /**
     * The spectator menu's teleport: cancelled, and if its target is inside the area made again as the
     * session's own teleport (a foreign one that far would end the session); outside, it only says so.
     */
    void teleported(PlayerTeleportEvent e) {
        if (watching.isEmpty() || e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        Player p = e.getPlayer();
        LiveRace r = watched(p.getUniqueId());
        if (r == null) {
            return;
        }
        e.setCancelled(true);
        Location to = e.getTo();
        if (to == null || to.getWorld() == null || !to.getWorld().getName().equals(r.world())
                || !r.area().contains(to.getX(), to.getY(), to.getZ())) {
            p.sendActionBar(Text.of(OUTSIDE));
            return;
        }
        Location target = to.clone();
        nextTick.accept(() -> {
            if (watching(p.getUniqueId())) {
                club.games().sessions().teleport(p, target);
            }
        });
    }

    /** Riding along in someone's view: only a player in the race or group being watched. */
    void spectating(PlayerStartSpectatingEntityEvent e) {
        LiveRace r = watching.isEmpty() ? null : watched(e.getPlayer().getUniqueId());
        if (r == null) {
            return;
        }
        Entity target = e.getNewSpectatorTarget();
        if (!(target instanceof Player racer) || !r.racers().contains(racer.getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Text.of(RIDE_ONLY));
        }
    }

    /** Someone joined: everyone sees them (a crash boot, or a quit while hidden), and they don't see watchers. */
    void joined(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(p)) {
                try {
                    other.showPlayer(club.plugin(), p);
                } catch (RuntimeException | LinkageError ignored) {
                    // nothing to show
                }
            }
        }
        visibility.arrived(p.getUniqueId());
    }

    /** Someone changed world or started a session: if they aren't watching, they don't see watchers. */
    void arrived(Player p) {
        visibility.arrived(p.getUniqueId());
    }

    /** How many are watching (status). */
    int count() {
        return watching.size();
    }

    /** The server's hide and show, for {@link WatchVisibility}. */
    static WatchVisibility.Viewers viewers(org.bukkit.plugin.Plugin plugin) {
        return new WatchVisibility.Viewers() {
            @Override
            public List<UUID> online() {
                List<UUID> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(p.getUniqueId());
                }
                return out;
            }

            @Override
            public void hide(UUID viewer, UUID watcher) {
                Player v = Bukkit.getPlayer(viewer);
                Player w = Bukkit.getPlayer(watcher);
                if (v != null && w != null) {
                    v.hidePlayer(plugin, w);
                }
            }

            @Override
            public void show(UUID viewer, UUID watcher) {
                Player v = Bukkit.getPlayer(viewer);
                Player w = Bukkit.getPlayer(watcher);
                if (v != null && w != null) {
                    v.showPlayer(plugin, w);
                }
            }
        };
    }
}
