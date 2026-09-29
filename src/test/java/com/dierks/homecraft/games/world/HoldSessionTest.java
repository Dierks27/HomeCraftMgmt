package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3-2-1 hold on a spawn or a start (final gate, #14), through the real world-session state machine.
 *
 * <p>A game holds a player by sending a move back to where it began, with the new look. The server
 * makes that changed {@code to} a PLUGIN teleport, and one nobody armed reads as someone else's short
 * hop: the session voids the player a tick later. A move handled after the scheduler's last tick of
 * the hold has its void due on the Go tick, where it runs AFTER the game's own (older) task has said
 * Go: a Falling Floors player was out the moment Go appeared, a timed Dropper run was bonked, a parkour
 * run sent back to the start. So the hold is the framework's ({@link WorldSessions#hold}): it arms the
 * spot as the session's own before it redirects, and no game changes a move's {@code to} itself.
 */
class HoldSessionTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    private static final Place SPAWN = new Place("games", 200.5, 90, 300.5, 10f, 0f);

    private Connection conn;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quiet();
    private FakeServer.Body ava;
    private Round round;
    private final World games = world("games");
    private final Player avaPlayer = player();

    private static Logger quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        return l;
    }

    /** A round on the spawns: held until Go; a void heard once it plays puts the player out. */
    private static final class Round implements SessionCore.Hooks<FakeServer.Body> {
        boolean playing;
        int voided;
        final List<String> out = new ArrayList<>();

        @Override
        public void ready(FakeServer.Body p) {
        }

        @Override
        public void ended(FakeServer.Body p, EndReason reason) {
        }

        @Override
        public void voided(FakeServer.Body p) {
            voided++;
            if (playing) {
                out.add(p.name); // ArenaService.voided, then ArenaTick outs them (FELL); a Dropper's bonk
            }
        }
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Player(Ava)";
                    default -> null;
                });
    }

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        GamesDao dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        server = new FakeServer();
        core = new SessionCore<>(dao, server, log);
        ava = new FakeServer.Body("Ava", HOME);
        round = new Round();
        assertNull(core.enter(ava, "floors", "", SPAWN, round), "into the round");
        server.step();
        server.arriveAll();
        core.teleported(ava, HOME, SPAWN, "PLUGIN"); // the trip's own teleport event spends its arming
        assertEquals(Session.Phase.ACTIVE, core.phase(ava.id), "on her spawn, held for the 3-2-1");
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private Location at(Place p) {
        return new Location(games, p.x(), p.y(), p.z(), p.yaw(), p.pitch());
    }

    /**
     * Ava holds W: one move, held by the framework the way the games hold, then what the server does
     * with it (a changed {@code to} is a PLUGIN teleport from where the move had already put her).
     */
    private PlayerMoveEvent heldStep() {
        Location from = at(SPAWN);
        Location to = new Location(games, SPAWN.x() + 0.3, SPAWN.y(), SPAWN.z(), 35f, 20f);
        PlayerMoveEvent e = new PlayerMoveEvent(avaPlayer, from, to);
        assertTrue(WorldSessions.hold(e, place -> core.hold(ava, place)), "a step is held");
        if (!e.getTo().equals(to)) {
            Place moved = BukkitPort.place(to);
            Place back = BukkitPort.place(e.getTo());
            ava.place = back;
            core.teleported(ava, moved, back, "PLUGIN");
        }
        return e;
    }

    @Test
    void aStepHeldOnTheLastTickBeforeGoNeverPutsThePlayerOutAtGo() {
        // the round's own repeating task is older than anything a move schedules: on the next tick it
        // runs first and says Go (ArenaRound.holdTick, TimeTrials' countdown)
        server.later(1, () -> round.playing = true);
        PlayerMoveEvent e = heldStep(); // handled after the scheduler's last tick of the hold
        server.step(); // Go
        server.step();
        assertTrue(round.playing, "(Go)");
        assertTrue(round.out.isEmpty(), "nobody is out, or bonked, or sent back at Go: " + round.out);
        assertEquals(0, round.voided, "the hold is the session's own teleport: never a void");
        assertEquals(Session.Phase.ACTIVE, core.phase(ava.id), "and her session goes on");
        assertEquals(SPAWN.x(), e.getTo().getX(), 1e-9, "still on her spawn");
        assertEquals(35f, e.getTo().getYaw(), "but looking where she turned");
        assertEquals(20f, e.getTo().getPitch(), "up and down too");
    }

    @Test
    void holdingWThroughTheWholeCountdownNeverVoids() {
        for (int tick = 0; tick < 60; tick++) {
            heldStep();
            server.step();
        }
        round.playing = true;
        heldStep();
        server.steps(3);
        assertEquals(0, round.voided, "sixty held steps, none of them anyone else's teleport");
    }

    @Test
    void aHoldsArmingIsSpentByItsOwnTeleportSoAForeignOneStillCounts() {
        heldStep();
        server.step();
        assertEquals(0, round.voided, "the hold: ours");
        core.teleported(ava, SPAWN, SPAWN, "PLUGIN"); // someone else's teleport to the very same spot
        server.step();
        assertEquals(1, round.voided, "is still someone else's: the hold armed one teleport, its own");
    }

    @Test
    void onlyLookingAroundIsntHeldAndArmsNothing() {
        PlayerMoveEvent look = new PlayerMoveEvent(avaPlayer, at(SPAWN),
                new Location(games, SPAWN.x(), SPAWN.y(), SPAWN.z(), 90f, 0f));
        assertFalse(WorldSessions.hold(look, place -> core.hold(ava, place)), "a look moves nobody");
        assertEquals(90f, look.getTo().getYaw(), "and it goes ahead");
        core.teleported(ava, SPAWN, SPAWN, "PLUGIN");
        server.step();
        assertEquals(1, round.voided, "nothing was armed for it");
    }

    @Test
    void aHoldOutsideASessionArmsNothing() {
        core.leave(ava, EndReason.COMMAND);
        server.arriveAll();
        assertFalse(core.hold(ava, SPAWN), "no session: nothing to arm");
    }

    // ---- no game changes a move's `to` itself ------------------------------------------------------

    @Test
    void noGameRedirectsAMoveItselfOnlyTheFrameworksHoldDoes() throws IOException {
        Path root = Path.of("src/main/java/com/dierks/homecraft/games");
        List<String> redirects = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(".setTo(") && !rel.equals("world/WorldSessions.java")) {
                        redirects.add(rel + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(redirects.isEmpty(), "a game holds a player with games.sessions().hold(e) (armed as the session's"
                + " own) or cancels the move; a changed `to` of its own is someone else's teleport to the session,"
                + " a void a tick later:\n" + String.join("\n", redirects));
    }
}
