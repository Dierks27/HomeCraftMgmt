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
 *   <li><b>The keeper.</b> A move out of the course's {@link WatchArea} is cancelled (never redirected:
 *       a changed destination is a foreign teleport to the world session), and one already outside is
 *       put back at the nearest point inside by the session's own armed teleport; a spectator-menu
 *       teleport whose target is outside is cancelled; the area stays inside the world's heights; and
 *       riding along in a racer's view is only for players in THAT race or group.</li>
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
        this.teleport = (p, at) -> club.games().sessions().teleport(p, at);
    }

    /** Runs a task on the next tick (the framework's, inside the Clubhouse's guard; a test's own). */
    java.util.function.Consumer<Runnable> nextTick;
    /**
     * The session's own teleport, armed and checked (a test's own): the ONLY way the keeper moves a
     * watcher. Anything else (a move event's changed {@code to}) is a foreign teleport to the session.
     */
    java.util.function.BiPredicate<Player, Location> teleport;
    /** Watchers a put-back is already on its way for (one at a time, not one per move packet). */
    private final java.util.Set<UUID> pulling = new java.util.HashSet<>();

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
        WatchArea a = area(race, w);
        Location at = new Location(w, a.viewX(), a.viewY(), a.viewZ(), a.viewYaw(), 20f);
        if (!teleport.test(p, at)) {
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
        pulling.remove(id);
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
        pulling.remove(id);
        visibility.gone(id);
    }

    /** The Clubhouse stops: everyone seen again. */
    void clear() {
        watching.clear();
        pulling.clear();
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
            putBack(p); // drifted out (riding along with a racer who left it): the session's own teleport
            p.sendActionBar(Text.of((r.positions().isEmpty() ? "&7Watching live" : r.positions()) + " " + HINT));
        }
        visibility.reconcile();
    }

    // ---- the keeper -------------------------------------------------------------------------------

    /**
     * A watcher's move out of the area is CANCELLED (review #1): the server puts them back where they
     * were, with no teleport event. It is never a changed {@code to}: that is an unarmed PLUGIN teleport,
     * which the world session takes as someone else's (a short hop sends them to the Clubhouse through
     * {@code onVoid}, a long one ends their session where they float). A watcher already outside (the
     * area moved under them) is also put back inside by the session's own armed teleport, next tick.
     */
    void moved(PlayerMoveEvent e) {
        if (watching.isEmpty()) {
            return;
        }
        Player p = e.getPlayer();
        LiveRace r = watched(p.getUniqueId());
        Location to = e.getTo();
        Location from = e.getFrom();
        if (r == null || to == null) {
            return;
        }
        WatchArea a = area(r, to.getWorld());
        WatchArea.Keep keep = from == null ? (a.contains(to.getX(), to.getY(), to.getZ()) ? WatchArea.Keep.LET
                : WatchArea.Keep.PULL) : a.keep(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
        if (keep == WatchArea.Keep.LET) {
            return;
        }
        e.setCancelled(true);
        if (keep == WatchArea.Keep.PULL) {
            pullSoon(p);
        }
    }

    /** Next tick, the session's own teleport to the nearest point inside the watched area (once). */
    private void pullSoon(Player p) {
        UUID id = p.getUniqueId();
        if (!pulling.add(id)) {
            return;
        }
        nextTick.accept(() -> {
            pulling.remove(id);
            if (watching(id)) {
                putBack(p);
            }
        });
    }

    /**
     * A watcher outside their area is put back at the nearest point inside, by the session's own armed
     * teleport (never the Clubhouse's arrival spot: they are still watching, in spectator mode).
     *
     * @return whether {@code p} is watching (then this was theirs to handle, moved or not)
     */
    boolean putBack(Player p) {
        LiveRace r = watched(p.getUniqueId());
        if (r == null) {
            return watching(p.getUniqueId());
        }
        Location l = p.getLocation();
        if (l == null) {
            return true;
        }
        double[] in = area(r, l.getWorld()).putBack(l.getX(), l.getY(), l.getZ());
        if (in != null) {
            try {
                p.setSpectatorTarget(null);
            } catch (RuntimeException | LinkageError ignored) {
                // nothing to stop
            }
            teleport.test(p, new Location(l.getWorld(), in[0], in[1], in[2], l.getYaw(), l.getPitch()));
        }
        return true;
    }

    /** The race's area inside its world's heights (review #1); as it is when the world can't be read. */
    static WatchArea area(LiveRace r, World w) {
        if (w == null) {
            return r.area();
        }
        try {
            return r.area().within(w.getMinHeight(), w.getMaxHeight());
        } catch (RuntimeException | LinkageError e) {
            return r.area();
        }
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
                || !area(r, to.getWorld()).contains(to.getX(), to.getY(), to.getZ())) {
            p.sendActionBar(Text.of(OUTSIDE));
            return;
        }
        Location target = to.clone();
        nextTick.accept(() -> {
            if (watching(p.getUniqueId())) {
                teleport.test(p, target);
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
