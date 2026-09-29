package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.SessionBench;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The real {@link Clubhouse} on a bench, for the cross-feature journeys: built by the framework from
 * its spec with its shipped settings, on a shared no-push team ({@link Clubhouse#pushes}), with a
 * real {@link WatchLive} over a real {@link WatchVisibility} whose server side (who is online, hide
 * and show) is recorded here.
 *
 * <p>A bench Clubhouse has no room (there is no server to build one on), so the Clubhouse's own
 * {@code door} is closed; {@link SessionDoor} is the door the races use instead: a line-for-line copy
 * of {@code Clubhouse.handOut}, {@code handBack} and {@code takeIn} over the {@link SessionBench} rail
 * (the real world-session state machine), with who is in the Clubhouse, spectators and the no-push team
 * the Clubhouse's own ({@link Clubhouse#admit}, {@link ClubVisits}, {@link Clubhouse#onSessionEnd}).
 * The Clubhouse's kit is mirrored on the rail's bodies ({@code KitItems.put}: never over the player's
 * own things).
 */
public final class ClubBench {

    /** The Clubhouse's arrival spots, in the Games world. */
    public static SessionBench.Spot arrival(int n) {
        return new SessionBench.Spot("games", 5390 + (n % 8), 161, 4460);
    }

    private final GamesService games;
    private final Clubhouse club;
    private final SessionBench rail;
    private final Function<UUID, Player> online;
    private final WatchVisibility visibility;
    private final WatchLive watch;
    private final SessionDoor door = new SessionDoor();
    private final List<Runnable> nextTick = new ArrayList<>();
    /** Every hide and show the visibility asked the server for: viewer -> watcher. */
    public final List<String> hides = new ArrayList<>();
    public final List<String> shows = new ArrayList<>();
    private int nextSpot;

    /**
     * @param online    who is online (a watcher's lookup)
     * @param onlineIds everyone online now (the visibility's viewers)
     */
    public ClubBench(GamesService games, SessionBench rail, NoPush pushes, Function<UUID, Player> online,
                     Supplier<List<UUID>> onlineIds) {
        this.games = games;
        this.rail = rail;
        this.online = online;
        this.club = (Clubhouse) games.game(Clubhouse.SPEC.id());
        club.pushes(pushes);
        visibility = new WatchVisibility(new WatchVisibility.Viewers() {
            @Override
            public List<UUID> online() {
                return onlineIds.get();
            }

            @Override
            public void hide(UUID viewer, UUID watcher) {
                hides.add(viewer + ">" + watcher);
            }

            @Override
            public void show(UUID viewer, UUID watcher) {
                shows.add(viewer + ">" + watcher);
            }
        });
        watch = new WatchLive(club, visibility, online);
        watch.nextTick = nextTick::add;
        watch.teleport = (p, at) -> rail.teleport(p.getUniqueId(), spot(at));
        club.watch(watch);
    }

    private static SessionBench.Spot spot(Location at) {
        return new SessionBench.Spot(at.getWorld() == null ? "games" : at.getWorld().getName(), at.getX(), at.getY(),
                at.getZ());
    }

    public Clubhouse club() {
        return club;
    }

    public SessionDoor door() {
        return door;
    }

    public ClubVisits visits() {
        return club.visits();
    }

    public WatchVisibility visibility() {
        return visibility;
    }

    /** The next arrival spot (the room's {@code spawn(nextSpot++)}). */
    public SessionBench.Spot nextArrival() {
        return arrival(nextSpot++);
    }

    // ---- coming in ------------------------------------------------------------------------------------

    /**
     * {@code Clubhouse.enter} and {@code arrived}: into the Clubhouse's own session at an arrival spot;
     * once in, {@link #welcome} (a visitor of {@code kind}, the no-push team, the kit, the line).
     *
     * @return {@code null} when they are in, else the refusal
     */
    public String enter(UUID id, ClubVisits.Kind kind, boolean spectator) {
        String line = spectator ? ClubhouseText.SPECTATING : switch (kind) {
            case PARTY -> ClubhouseText.WAIT_PARTY;
            case NIGHT -> ClubhouseText.WAIT_NIGHT;
            default -> ClubhouseText.WELCOME;
        };
        return rail.enterNow(id, club, Clubhouse.REF, nextArrival(), p -> welcome(p, kind, spectator, line));
    }

    /** {@code Clubhouse.welcome}: admitted, the kit, the line. */
    public void welcome(Player p, ClubVisits.Kind kind, boolean spectator, String line) {
        admit(p.getUniqueId(), p.getName(), kind, spectator);
        kit(p.getUniqueId());
        if (line != null && !line.isBlank()) {
            p.sendMessage(Text.of(line));
        }
    }

    /** {@code Clubhouse.admit} (the real one): a visitor, on the no-push team. */
    public void admit(UUID id, String name, ClubVisits.Kind kind, boolean spectator) {
        club.admit(id, name, kind, spectator);
    }

    /**
     * {@code Clubhouse.giveKit}, mirrored on the body: the kit only is taken off, then Party (in a party
     * race's lobby), Results and Leave game, each without overwriting the player's own things.
     */
    public void kit(UUID id) {
        rail.stripKit(id);
        PartyLobby l = games.parties().of(id);
        if (l != null && l.kind() == PartyLobby.Kind.RACE) {
            rail.putKit(id, 0, "kit:clubhouse:party");
        }
        rail.putKit(id, 2, "kit:clubhouse:results");
        rail.putKit(id, 8, "kit:clubhouse:leave");
    }

    /** Their Clubhouse session ended (any way): the Clubhouse's own {@code onSessionEnd}. */
    public void onSessionEnd(Player p, EndReason why) {
        club.onSessionEnd(p, why);
    }

    // ---- watch live -------------------------------------------------------------------------------------

    /**
     * {@code WatchLive.start}, mirrored: the watcher is recorded on the real {@link WatchLive} (hidden
     * from everyone who isn't watching), then the session's own mode is SPECTATOR and they fly over the
     * race's view point.
     */
    public void watching(UUID id, LiveRace race) {
        watch.watching(id, race.key());
        WatchArea a = race.area();
        SessionBench.Spot view = new SessionBench.Spot(race.world(), a.viewX(), a.viewY(), a.viewZ());
        rail.teleport(id, view);
        rail.mode(id, "SPECTATOR");
    }

    public boolean watchingNow(UUID id) {
        return watch.watching(id);
    }

    public List<LiveRace> live() {
        return watch.live();
    }

    /** The races going on now ({@code watch.refresh}, once a second). */
    public void refresh(List<LiveRace> now) {
        watch.refresh(now);
    }

    /**
     * {@code watch.second()} (the next-tick tasks first). A watcher it stopped ({@code WatchLive.stop})
     * gets the server side mirrored: ADVENTURE again as the session's own change, and an arrival spot.
     */
    public void second() {
        List<Runnable> due = new ArrayList<>(nextTick);
        nextTick.clear();
        due.forEach(Runnable::run);
        Set<UUID> before = new HashSet<>();
        for (Map.Entry<UUID, Player> e : watchers().entrySet()) {
            before.add(e.getKey());
        }
        watch.second();
        for (UUID id : before) {
            if (!watch.watching(id) && club.visits().in(id)) {
                rail.mode(id, "ADVENTURE"); // WatchLive.stop: gameMode(ADVENTURE), then toSpawn
                rail.teleport(id, nextArrival());
                kit(id);
            }
        }
    }

    private Map<UUID, Player> watchers() {
        Map<UUID, Player> out = new LinkedHashMap<>();
        for (ClubVisits.Visit v : club.visits().all()) {
            if (watch.watching(v.id())) {
                out.put(v.id(), online.apply(v.id()));
            }
        }
        return out;
    }

    /**
     * The timeouts and the restart hold: a copy of {@code Clubhouse.second}'s loop over the real
     * {@link ClubVisits#second} (the idle clock stands still for a party member or a watcher), with its I/O
     * (the lines, and the trip home: the session's end) on the rail.
     *
     * @return what it said to do
     */
    public List<ClubVisits.Act> visitsSecond() {
        long now = games.clock().nowMillis();
        boolean holding = games.restartHold().holding(now);
        List<ClubVisits.Act> acts = club.visits().second(now, club.settings().maxMinutes(),
                id -> games.parties().of(id) != null || watch.watching(id), holding);
        for (ClubVisits.Act a : acts) {
            Player p = online.apply(a.player());
            if (p == null) {
                continue;
            }
            switch (a.what()) {
                case WARN_IDLE -> p.sendMessage(Text.of(ClubhouseText.IDLE_WARN));
                case HOME_IDLE -> sendHome(p, ClubhouseText.IDLE_HOME);
                case WARN_HOLD -> p.sendMessage(Text.of(ClubhouseText.holdWarn(games.restartHeld())));
                case HOME_HOLD -> sendHome(p, ClubhouseText.HOLD_HOME);
            }
        }
        return acts;
    }

    /** {@code Clubhouse.sendHome}: the line, then the session's end (FINISH). */
    private void sendHome(Player p, String line) {
        p.sendMessage(Text.of(line));
        rail.leave(p.getUniqueId(), EndReason.FINISH);
    }

    /** {@code Clubhouse.stop}: the real one (every watcher seen again, everyone off the no-push team, out). */
    public void stop() {
        club.stop();
    }

    // ---- the door -----------------------------------------------------------------------------------------

    /** Whether the player is in an ACTIVE Clubhouse session on the rail. */
    boolean inOurSession(UUID id) {
        Session s = rail.session(id);
        return s != null && Clubhouse.SPEC.id().equals(s.gameId()) && s.phase() == Session.Phase.ACTIVE;
    }

    /**
     * The Clubhouse's door on the rail: {@code Clubhouse.handOut}, {@code handBack} and {@code takeIn}
     * copied line for line, with the world session's calls made on the {@link SessionBench}.
     */
    public final class SessionDoor implements ClubDoor {
        /** Every result sheet the races put up, in order. */
        public final List<ClubBoard.Sheet> results = new ArrayList<>();
        public List<UUID> podium;
        /** What each player read on being taken in. */
        public final Map<UUID, String> lines = new LinkedHashMap<>();
        public final Map<UUID, Integer> handedOut = new LinkedHashMap<>();
        public final Map<UUID, Integer> takenIn = new LinkedHashMap<>();
        public final Map<UUID, Integer> handedBack = new LinkedHashMap<>();
        /** The Clubhouse closed (a failed check): {@link #takeIn} says no. */
        public boolean open = true;
        /** A hook run after each take-in (the ride's own). */
        public Consumer<UUID> afterTakeIn = id -> {
        };

        @Override
        public String world() {
            return "games";
        }

        @Override
        public boolean seatable(UUID player) {
            return online.apply(player) != null && club.visits().in(player) && inOurSession(player);
        }

        @Override
        public boolean spectator(UUID player) {
            ClubVisits.Visit v = club.visits().get(player);
            return v != null && v.spectator();
        }

        @Override
        public boolean handOut(Player p, Game to, String ref) {
            UUID id = p.getUniqueId();
            if (!club.visits().in(id) || !inOurSession(id) || to == null) {
                return false;
            }
            if (watch.watching(id)) {
                watch.stop(p, null); // off the course and back in adventure mode, then to the grid
                rail.mode(id, "ADVENTURE");
            }
            if (!rail.passTo(id, to, ref)) {
                return false;
            }
            club.onSessionEnd(p, EndReason.FINISH); // Clubhouse.gone: out, off the no-push team
            if (!rail.bankExtras(id)) {
                rail.stripKit(id);
            }
            handedOut.merge(id, 1, Integer::sum);
            return true;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
            UUID id = p.getUniqueId();
            handedBack.merge(id, 1, Integer::sum);
            if (rail.passTo(id, club, Clubhouse.REF)) {
                rail.stripKit(id);
                rail.teleport(id, nextArrival());
                welcome(p, kind, false, ClubhouseText.NO_GRID);
            } else {
                rail.leave(id, EndReason.FINISH);
            }
        }

        @Override
        public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
            if (!open || p == null || !p.isOnline()) {
                return false;
            }
            UUID id = p.getUniqueId();
            Session sess = rail.session(id);
            if (sess == null || sess.phase() != Session.Phase.ACTIVE || !world().equals(sess.world())) {
                return false; // not in a session in the Clubhouse's world: the caller sends them home
            }
            if (!rail.teleport(id, nextArrival())) {
                return false;
            }
            if (!rail.passTo(id, club, Clubhouse.REF)) {
                return false;
            }
            rail.stripKit(id); // the race's kit only (#3)
            welcome(p, kind, false, line);
            club.visits().recheck(id, games.clock().nowMillis());
            lines.put(id, line == null ? "" : line);
            takenIn.merge(id, 1, Integer::sum);
            afterTakeIn.accept(id);
            return true;
        }

        @Override
        public boolean partyAfter() {
            return club.settings().partyAfter();
        }

        @Override
        public boolean nightAfter() {
            return club.settings().raceNightAfter();
        }

        @Override
        public boolean golfAfter() {
            return club.settings().golfAfter();
        }

        @Override
        public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
            if (sheet != null) {
                results.add(sheet);
            }
        }

        @Override
        public void podium(List<UUID> topThree) {
            podium = List.copyOf(topThree);
        }

        /** The last sheet put up, or {@code null}. */
        public ClubBoard.Sheet last() {
            return results.isEmpty() ? null : results.getLast();
        }
    }
}
