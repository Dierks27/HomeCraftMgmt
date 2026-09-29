package com.dierks.homecraft.games.clubhouse;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.gen.api.Box;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watch live (CLUBHOUSE-SPEC §10, §11): the keeper puts a watcher back inside the area or cancels a
 * spectator-menu teleport whose target is outside; riding along is only for players in that race; a
 * watcher reads the race's live positions; when the race ends they are brought back; and every way
 * out shows them to everyone again.
 */
class WatchLiveTest {

    /** A player who remembers what they read and on the action bar, standing where {@link #at} says. */
    static final class Fake {
        final UUID id = UUID.randomUUID();
        final String name;
        final List<String> said = new ArrayList<>();
        final List<String> bar = new ArrayList<>();
        Location at;
        final Player player;

        Fake(String name, Location at) {
            this.name = name;
            this.at = at;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, m, args) -> switch (m.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "isOnline" -> true;
                        case "getLocation" -> this.at.clone();
                        case "sendMessage" -> {
                            if (args[0] instanceof Component c) {
                                said.add(PlainTextComponentSerializer.plainText().serialize(c));
                            }
                            yield null;
                        }
                        case "sendActionBar" -> {
                            bar.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                            yield null;
                        }
                        case "hashCode" -> id.hashCode();
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    private static final World GAMES = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (proxy, m, args) -> switch (m.getName()) {
                case "getName" -> "games";
                case "hashCode" -> 7;
                case "equals" -> proxy == args[0];
                default -> null;
            });
    private static final WatchArea AREA = WatchArea.view(new Box(0, 60, 0, 63, 100, 63), 10, 65, 10, 0f);

    private GamesBench bench;
    private Clubhouse club;
    private WatchVisibilityTest.Viewers viewers;
    private WatchLive watch;
    private final Map<UUID, Player> online = new HashMap<>();
    private Fake watcher;
    private Fake racer;
    private Fake bystander;
    private LiveRace race;

    @BeforeEach
    void setUp() {
        bench = new GamesBench(GamesBench.at(2026, 9, 29, 19, 0), List.of(Clubhouse.SPEC), "clubhouse",
                ClubhouseSettings.defaults());
        club = (Clubhouse) bench.games().game("clubhouse");
        viewers = new WatchVisibilityTest.Viewers();
        watch = new WatchLive(club, new WatchVisibility(viewers), online::get);
        club.watch(watch);
        watcher = new Fake("Wes", new Location(GAMES, 20, 70, 20));
        racer = new Fake("Sam", new Location(GAMES, 30, 65, 30));
        bystander = new Fake("Bea", new Location(GAMES, 200, 65, 200));
        for (Fake f : List.of(watcher, racer, bystander)) {
            online.put(f.id, f.player);
            viewers.online.add(f.id);
        }
        race = new LiveRace("party:7", "&dParty race: &fLoop", "games", AREA, Set.of(racer.id),
                List.of("&e1. &fSam &7lap 1/2"), LiveRace.positions(List.of("Sam", "Ava"), 5), true);
        watch.refresh(List.of(race));
        club.admit(watcher.id, "Wes", ClubVisits.Kind.VISIT, true);
        watch.watching(watcher.id, race.key());
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    @Test
    void theKeeperHoldsAWatcherInsideTheArea() {
        Location from = new Location(GAMES, 62, 70, 30);
        PlayerMoveEvent out = new PlayerMoveEvent(watcher.player, from, new Location(GAMES, 70, 70, 30, 90f, 10f));
        watch.moved(out);
        assertTrue(AREA.contains(out.getTo().getX(), out.getTo().getY(), out.getTo().getZ()), "put back inside: "
                + out.getTo());
        assertEquals(90f, out.getTo().getYaw(), "free to look round");
        PlayerMoveEvent in = new PlayerMoveEvent(watcher.player, from, new Location(GAMES, 40, 70, 30));
        watch.moved(in);
        assertEquals(40, in.getTo().getX(), 1e-9, "a move inside is left alone");
        PlayerMoveEvent racerMove = new PlayerMoveEvent(racer.player, from, new Location(GAMES, 500, 70, 30));
        watch.moved(racerMove);
        assertEquals(500, racerMove.getTo().getX(), 1e-9, "racers are never held by the keeper");
    }

    @Test
    void aSpectateTeleportOutsideIsCancelledAndInsideIsMadeTheSessionsOwn() {
        PlayerTeleportEvent out = new PlayerTeleportEvent(watcher.player, watcher.at, new Location(GAMES, 900, 70, 0),
                PlayerTeleportEvent.TeleportCause.SPECTATE);
        watch.teleported(out);
        assertTrue(out.isCancelled(), "a target outside the area is cancelled");
        assertTrue(watcher.bar.getLast().contains("outside the race"), watcher.bar.toString());
        List<Runnable> next = new ArrayList<>();
        watch.nextTick = next::add;
        PlayerTeleportEvent in = new PlayerTeleportEvent(watcher.player, watcher.at, new Location(GAMES, 30, 65, 30),
                PlayerTeleportEvent.TeleportCause.SPECTATE);
        watch.teleported(in);
        assertEquals(1, next.size(), "the session's own teleport there, next tick (never from inside the event)");
        assertTrue(in.isCancelled(), "inside: cancelled too, and made again as the session's own teleport next tick"
                + " (a foreign one that far would end the session)");
        PlayerTeleportEvent plugin = new PlayerTeleportEvent(watcher.player, watcher.at, new Location(GAMES, 900, 70, 0),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        watch.teleported(plugin);
        assertFalse(plugin.isCancelled(), "our own teleports are the session's business");
    }

    @Test
    void ridingAlongIsOnlyForPlayersInThatRace() {
        PlayerStartSpectatingEntityEvent ok = new PlayerStartSpectatingEntityEvent(watcher.player, watcher.player,
                racer.player);
        watch.spectating(ok);
        assertFalse(ok.isCancelled(), "a racer in the race: ride along");
        PlayerStartSpectatingEntityEvent no = new PlayerStartSpectatingEntityEvent(watcher.player, watcher.player,
                bystander.player);
        watch.spectating(no);
        assertTrue(no.isCancelled(), "anyone else: cancelled");
        assertTrue(watcher.bar.getLast().contains("ride along"), watcher.bar.toString());
    }

    @Test
    void aWatcherReadsTheLivePositionsAndIsHiddenFromNonWatchers() {
        watch.second();
        String line = watcher.bar.getLast();
        assertTrue(line.contains("1. Sam") && line.contains("2. Ava"), "the race's positions, read-only: " + line);
        assertTrue(line.contains("/hcm play clubhouse to go back"), "and how to come back: " + line);
        assertFalse(viewers.sees(racer.id, watcher.id), "a racer never sees them");
        assertFalse(viewers.sees(bystander.id, watcher.id), "nor does anyone who isn't watching");
    }

    @Test
    void whenTheRaceEndsTheyAreBroughtBackAndSeenAgain() {
        watch.refresh(List.of()); // the race is over
        watch.second();
        assertFalse(watch.watching(watcher.id), "back from watching");
        assertTrue(watcher.said.getLast().contains("back to the Clubhouse"), watcher.said.toString());
        assertTrue(viewers.sees(racer.id, watcher.id) && viewers.sees(bystander.id, watcher.id), "seen again");
        assertTrue(club.visits().in(watcher.id), "and still in the Clubhouse, for the results and the photo");
    }

    @Test
    void everyWayOutShowsTheWatcherAgain() {
        club.onSessionEnd(watcher.player, EndReason.QUIT_ITEM); // Leave, a quit, the hold, the Clubhouse off...
        assertFalse(watch.watching(watcher.id), "no longer watching");
        assertTrue(viewers.sees(racer.id, watcher.id), "shown again to everyone");
        assertFalse(club.visits().in(watcher.id), "out of the Clubhouse");
        watch.watching(racer.id, race.key()); // another watcher, then the Clubhouse stops
        club.stop();
        assertTrue(viewers.hidden.isEmpty(), "the Clubhouse stopping shows everyone again");
    }
}
