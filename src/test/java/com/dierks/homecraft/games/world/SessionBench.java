package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The real world-session state machine ({@link SessionCore}) for the cross-feature journeys (the
 * GamesBench pattern): one {@link FakeServer} "rail" whose bodies share the ids of a
 * {@code GamesBench}'s players, so a player's things, game mode and saved-state row are walked
 * through every hand-over (the Clubhouse, a race, golf) while the games themselves run on the
 * framework bench.
 *
 * <p>Every session's hooks are {@code WorldSessions.hooks}' own: ready runs the game's
 * {@code onReady}, an end runs {@code game.onSessionEnd} and a void {@code game.onVoid}, each inside
 * the framework's guard (a throw switches that game off, and a game that failed ends the session).
 *
 * <p>{@link #crash()} is what a real crash leaves: a new server and a new state machine over the
 * same database; nothing of the old one runs again (no leave, no quit).
 */
public final class SessionBench {

    /** A spot in a named world (the rail's {@link Place}, for tests outside this package). */
    public record Spot(String world, double x, double y, double z) {

        Place place() {
            return Place.of(world, x, y, z);
        }

        static Spot of(Place p) {
            return p == null ? null : new Spot(p.world(), p.x(), p.y(), p.z());
        }
    }

    private GamesService games;
    private final GamesDao dao;
    private final Logger log = Logger.getAnonymousLogger();
    private final List<LogRecord> logs = new ArrayList<>();
    private FakeServer server = new FakeServer();
    private SessionCore<FakeServer.Body, String> core;
    private final Map<UUID, FakeServer.Body> bodies = new LinkedHashMap<>();
    private final Map<UUID, Player> players = new HashMap<>();
    /** Every body's things as they were when it was made, to check "home with their things once". */
    private final Map<UUID, List<String>> own = new HashMap<>();

    /** The rail over {@code dao} (the bench's database), its sessions' games in {@code games}. */
    public SessionBench(GamesService games, GamesDao dao) {
        this.games = games;
        this.dao = dao;
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        core = new SessionCore<>(dao, server, log);
    }

    /** A session ended (after its game heard it): who, which game held it, and why. */
    public record Ended(UUID player, String gameId, EndReason reason) {
    }

    /** Told of every session's end, after its game's own hook (a bench that mirrors a game's own side). */
    private final List<Consumer<Ended>> endListeners = new ArrayList<>();

    public void onEnded(Consumer<Ended> listener) {
        endListeners.add(listener);
    }

    /** The framework the sessions' hooks guard with (a new one after a reboot). */
    public void games(GamesService now) {
        this.games = now;
    }

    // ---- players ------------------------------------------------------------------------------------

    /**
     * A body for {@code player} (the same id), standing at {@code home} in {@code gameMode} and holding
     * {@code items} (slot 0 up).
     */
    public void body(Player player, Spot home, String gameMode, String... items) {
        FakeServer.Body b = new FakeServer.Body(player.getUniqueId(), player.getName(), home.place());
        b.gameMode = gameMode;
        for (int i = 0; i < items.length; i++) {
            b.slots[i] = items[i];
        }
        bodies.put(b.id, b);
        players.put(b.id, player);
        own.put(b.id, List.copyOf(b.items()));
    }

    private FakeServer.Body b(UUID id) {
        FakeServer.Body b = bodies.get(id);
        if (b == null) {
            throw new IllegalArgumentException("no body for " + id);
        }
        return b;
    }

    /** The bench's player for a body. */
    public Player player(UUID id) {
        return players.get(id);
    }

    /** What the body held when it was made (its own things). */
    public List<String> own(UUID id) {
        return own.getOrDefault(id, List.of());
    }

    // ---- the server -----------------------------------------------------------------------------

    /** One tick: the state machine's scheduled steps that are due. */
    public void step() {
        server.step();
    }

    public void steps(int n) {
        server.steps(n);
    }

    /** Every teleport on its way arrives. */
    public void arriveAll() {
        server.arriveAll();
    }

    /** How many teleports are on their way. */
    public int trips() {
        return server.trips.size();
    }

    /** Every async teleport ever started on this server. */
    public int started() {
        return server.started;
    }

    /** The synchronous teleports made so far (a watcher's landing, a games-off trip home). */
    public List<Spot> syncTeleports() {
        List<Spot> out = new ArrayList<>();
        for (Place p : server.syncTeleports) {
            out.add(Spot.of(p));
        }
        return out;
    }

    /** The server is (or isn't) shutting down. */
    public void stopping(boolean on) {
        server.stopping = on;
    }

    /** SEVERE lines the state machine logged. */
    public long severe() {
        return logs.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
    }

    /** Every line the state machine logged, for a failure message. */
    public String logged() {
        StringBuilder out = new StringBuilder();
        for (LogRecord r : logs) {
            out.append(r.getLevel()).append(' ').append(r.getMessage()).append('\n');
        }
        return out.toString();
    }

    /**
     * A crash: the old server and state machine are dropped with nothing run (no leave, no quit), and
     * a new server and state machine start over the same database. Bodies stay as the crash left them.
     */
    public void crash() {
        FakeServer next = new FakeServer();
        next.tick = server.tick + 200;
        next.now = server.now + 60_000;
        server = next;
        core = new SessionCore<>(dao, server, log);
    }

    // ---- the state machine --------------------------------------------------------------------------

    /**
     * {@code WorldSessions.enter}: into {@code game} at {@code start}, the game's {@code onReady} once in.
     *
     * @return {@code null} when the entry started, else the refusal
     */
    public String enter(UUID id, Game game, String ref, Spot start, Consumer<Player> onReady) {
        return core.enter(b(id), game.id(), ref, start.place(), hooks(game, onReady));
    }

    /** {@link #enter}, then the tick and the trip: in, saved, cleared and ready now. */
    public String enterNow(UUID id, Game game, String ref, Spot start, Consumer<Player> onReady) {
        String why = enter(id, game, ref, start, onReady);
        if (why == null) {
            server.step();
            server.arriveAll();
        }
        return why;
    }

    /** {@code WorldSessions.passTo}: the ACTIVE session handed to {@code to}, in place. */
    public boolean passTo(UUID id, Game to, String ref) {
        return core.passTo(b(id), to.id(), ref, hooks(to, null));
    }

    /** {@code WorldSessions.bankExtras}. */
    public boolean bankExtras(UUID id) {
        return core.bankExtras(b(id));
    }

    /** {@code WorldSessions.stripKit}: the kit only. */
    public void stripKit(UUID id) {
        server.stripKit(b(id));
    }

    /**
     * {@code WorldSessions.gameMode}: the session's own mode (the state machine keeps it) and the
     * body put in it, as {@code BukkitPort.gameMode} does.
     */
    public boolean mode(UUID id, String mode) {
        FakeServer.Body p = b(id);
        if (!core.mode(p, mode)) {
            return false;
        }
        p.gameMode = mode;
        boolean flies = "SPECTATOR".equals(mode) || "CREATIVE".equals(mode);
        p.allowFlight = flies;
        p.flying = "SPECTATOR".equals(mode);
        return true;
    }

    /** The session's own mode now, or {@code null}. */
    public String sessionMode(UUID id) {
        return core.mode(id);
    }

    /** {@code WorldSessions.teleport}: the session's own (armed) teleport within its world. */
    public boolean teleport(UUID id, Spot to) {
        return core.teleport(b(id), to.place());
    }

    public void leave(UUID id, EndReason why) {
        core.leave(b(id), why);
    }

    /** The player quits (the quit event): their session restores in place. */
    public void quit(UUID id) {
        FakeServer.Body p = b(id);
        core.quit(p);
        p.online = false;
    }

    /** Every session ends ({@code WorldSessions.stop}). */
    public void stop() {
        core.stop();
    }

    /** The player joins (again): online, then the join's recovery a tick later. */
    public void joined(UUID id) {
        FakeServer.Body p = b(id);
        p.online = true;
        core.joined(p);
    }

    /** The worlds-up pass over these players. */
    public void worldsReady(List<UUID> online) {
        List<FakeServer.Body> list = new ArrayList<>();
        for (UUID id : online) {
            list.add(b(id));
        }
        core.worldsReady(list);
    }

    /** The player's live session, or {@code null}. */
    public Session session(UUID id) {
        return core.session(id);
    }

    /** Whether the player is back from every game: no session, nothing on the way, no live row. */
    public boolean home(UUID id) {
        return core.home(id);
    }

    /** The player's live saved-state row, or {@code null}. */
    public SavedState row(UUID id) {
        return core.rowOrNull(id);
    }

    /** The items kept in the player's row for them ({@code carry}), decoded. */
    public List<String> carry(UUID id) {
        SavedState s = row(id);
        return s == null || s.carry() == null ? List.of() : server.decode(s.carry());
    }

    // ---- the body -----------------------------------------------------------------------------------

    /** Something reaches the player mid-session (an auction win, an inbox Mini): the first empty slot. */
    public boolean deliver(UUID id, String item) {
        FakeServer.Body p = b(id);
        int slot = p.firstEmpty();
        if (slot < 0) {
            return false;
        }
        p.slots[slot] = item;
        return true;
    }

    /**
     * A kit item into {@code slot} the way {@code KitItems.put} does it: the player's own thing there
     * moves to the first empty storage slot ({@link KitItems#moveTo}); with no room, the kit item waits.
     */
    public boolean putKit(UUID id, int slot, String kitItem) {
        FakeServer.Body p = b(id);
        String there = p.slots[slot];
        boolean mine = there != null && !there.startsWith("kit:");
        int to = KitItems.moveTo(mine, mine ? p.firstEmpty() : -1);
        if (to == KitItems.NO_ROOM) {
            return false;
        }
        if (to >= 0) {
            p.slots[to] = there;
        }
        p.slots[slot] = kitItem;
        return true;
    }

    /** Put {@code item} in {@code slot} (a test's own setup), or empty it with {@code null}. */
    public void set(UUID id, int slot, String item) {
        b(id).slots[slot] = item;
    }

    /** Every item in the inventory, slot order. */
    public List<String> items(UUID id) {
        return b(id).items();
    }

    /** The slot's item, or {@code null}. */
    public String slot(UUID id, int slot) {
        return b(id).slots[slot];
    }

    /** How many of {@code item} the inventory holds. */
    public long count(UUID id, String item) {
        return b(id).items().stream().filter(item::equals).count();
    }

    /** Whether any kit item is anywhere on them (the inventory, the cursor, the ender chest). */
    public boolean holdsKit(UUID id) {
        return b(id).holdsKit();
    }

    /** What was dropped at their feet. */
    public List<String> dropped(UUID id) {
        return List.copyOf(b(id).dropped);
    }

    public List<String> ender(UUID id) {
        return b(id).ender;
    }

    public String gameMode(UUID id) {
        return b(id).gameMode;
    }

    public void gameMode(UUID id, String mode) {
        b(id).gameMode = mode;
    }

    public boolean flying(UUID id) {
        return b(id).flying;
    }

    public void flying(UUID id, boolean allow, boolean on) {
        b(id).allowFlight = allow;
        b(id).flying = on;
    }

    public Spot place(UUID id) {
        return Spot.of(b(id).place);
    }

    /** Stand somewhere (someone else's move, or where a watcher flew). */
    public void place(UUID id, Spot at) {
        b(id).place = at.place();
    }

    public int applies(UUID id) {
        return b(id).applies;
    }

    public int saves(UUID id) {
        return b(id).saves;
    }

    public int level(UUID id) {
        return b(id).level;
    }

    public void xp(UUID id, int level, float exp, int total) {
        FakeServer.Body p = b(id);
        p.level = level;
        p.exp = exp;
        p.totalXp = total;
    }

    public int totalXp(UUID id) {
        return b(id).totalXp;
    }

    public float walk(UUID id) {
        return b(id).walk;
    }

    public float fly(UUID id) {
        return b(id).fly;
    }

    public void speeds(UUID id, float walk, float fly) {
        b(id).walk = walk;
        b(id).fly = fly;
    }

    /** What the state machine told the player (plain lines, colour codes kept). */
    public List<String> messages(UUID id) {
        return List.copyOf(b(id).messages);
    }

    /** The mark in the player's own data. */
    public String mark(UUID id) {
        return b(id).mark;
    }

    public void mark(UUID id, String mark) {
        b(id).mark = mark;
    }

    /** Drop a thing the player holds (to make room): the first slot holding {@code item} empties. */
    public boolean drop(UUID id, String item) {
        FakeServer.Body p = b(id);
        for (int i = 0; i < p.slots.length; i++) {
            if (item.equals(p.slots[i])) {
                p.slots[i] = null;
                return true;
            }
        }
        return false;
    }

    /** How many storage slots are free. */
    public int free(UUID id) {
        FakeServer.Body p = b(id);
        int n = 0;
        for (int i = 0; i < FakeServer.STORAGE; i++) {
            if (p.slots[i] == null) {
                n++;
            }
        }
        return n;
    }

    // ---- the hooks ------------------------------------------------------------------------------------

    /** {@code WorldSessions.hooks}: the session's calls into its game, each inside the game's guard. */
    private SessionCore.Hooks<FakeServer.Body> hooks(Game game, Consumer<Player> onReady) {
        return new SessionCore.Hooks<>() {
            @Override
            public void ready(FakeServer.Body p) {
                if (onReady != null) {
                    games.guard(game, () -> onReady.accept(players.get(p.id)));
                }
                offIfFailed(p);
            }

            @Override
            public void ended(FakeServer.Body p, EndReason reason) {
                games.guard(game, () -> game.onSessionEnd(players.get(p.id), reason));
                for (Consumer<Ended> l : List.copyOf(endListeners)) {
                    l.accept(new Ended(p.id, game.id(), reason));
                }
            }

            @Override
            public void voided(FakeServer.Body p) {
                games.guard(game, () -> game.onVoid(players.get(p.id)));
                offIfFailed(p);
            }

            private void offIfFailed(FakeServer.Body p) {
                if (games.failed(game)) {
                    core.leave(p, EndReason.GAME_OFF);
                }
            }
        };
    }
}
