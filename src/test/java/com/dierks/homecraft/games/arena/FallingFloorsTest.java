package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.arena.FakeArenaHost.P;
import com.dierks.homecraft.games.arena.rules.ArenaRound;
import com.dierks.homecraft.games.arena.rules.ArenaText;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game's own pieces around the arena (the F review's #12), without a server: the 3-2-1 hold
 * keeps a player on their spawn but lets them look round; however a session ends, collisions and
 * the player's own team come back; an arrival is sent home when the floors failed their check on
 * the way; a settings change starts a new arena only between rounds; and the box guard's test.
 */
class FallingFloorsTest {

    private final FakeWorldPort world = new FakeWorldPort(FakeArenaHost.WORLD);
    private final FakeArenaHost host = new FakeArenaHost(world);

    private ArenaService booted() {
        ArenaService s = new ArenaService(host);
        s.start();
        for (int i = 0; i < 400 && s.round().phase() != ArenaRound.Phase.LOBBY; i++) {
            s.tick();
        }
        assertEquals(ArenaRound.Phase.LOBBY, s.round().phase(), "(built)");
        return s;
    }

    private static P in(ArenaService s, FakeArenaHost host, String name) {
        P p = host.player(name);
        ArenaSite.Spot spot = s.entrySpot();
        p.at(spot.x(), spot.y(), spot.z());
        p.session = true;
        assertNull(s.joined(p.id), name + " is in");
        return p;
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

    /** A player with an id and a name who records collisions ({@code setCollidable}). */
    private static final class Fake {
        final UUID id;
        final String name;
        boolean collidable = true;
        boolean online = true;
        final List<Boolean> set = new ArrayList<>();
        final Player player;

        Fake(String name) {
            this.name = name;
            this.id = UUID.nameUUIDFromBytes(name.getBytes());
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> this.name;
                        case "isOnline" -> online;
                        case "isCollidable" -> collidable;
                        case "setCollidable" -> {
                            collidable = (Boolean) a[0];
                            set.add(collidable);
                            yield null;
                        }
                        case "equals" -> proxy == a[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Player(" + this.name + ")";
                        default -> null;
                    });
        }
    }

    // ---- the 3-2-1 hold ---------------------------------------------------------------------------

    @Test
    void onTheSpawnsAPlayerIsHeldInPlaceButMayLookRound() {
        ArenaService s = booted();
        P alex = in(s, host, "Alex");
        s.solo(alex.id);
        for (int i = 0; i < 20 && s.round().phase() != ArenaRound.Phase.HOLD; i++) {
            s.tick();
        }
        assertTrue(s.held(alex.id), "(on a spawn, waiting for Go)");
        Fake f = new Fake("Alex");
        World w = world(FakeArenaHost.WORLD);
        Location from = new Location(w, alex.x, alex.y, alex.z, 10f, 0f);

        // the hold itself (a step undone, the new look kept, armed as the session's own so it is never a
        // void at Go) is the framework's: HoldSessionTest (final gate #14)
        PlayerMoveEvent step = new PlayerMoveEvent(f.player, from, new Location(w, alex.x + 0.4, alex.y, alex.z, 35f, 20f));
        assertTrue(FallingFloors.held(s, step), "a step on the spawn is held");
        assertEquals(alex.x + 0.4, step.getTo().getX(), "by the framework's hold, never a `to` of the game's own");

        PlayerMoveEvent look = new PlayerMoveEvent(f.player, from, new Location(w, alex.x, alex.y, alex.z, 90f, 0f));
        assertFalse(FallingFloors.held(s, look), "only looking round: nothing to undo");

        for (int i = 0; i < 100 && s.round().phase() != ArenaRound.Phase.PLAYING; i++) {
            s.tick();
        }
        PlayerMoveEvent run = new PlayerMoveEvent(f.player, from, new Location(w, alex.x + 0.4, alex.y, alex.z, 35f, 0f));
        assertFalse(FallingFloors.held(s, run), "after Go they run");
        assertFalse(FallingFloors.held(null, run), "the game stopped: nothing to hold");
    }

    // ---- however a session ends -----------------------------------------------------------------

    @Test
    void howeverASessionEndsCollisionsAndThePlayersOwnTeamComeBack() {
        Map<String, String> teams = new HashMap<>();
        Set<String> names = new HashSet<>(Set.of("red"));
        NoPush noPush = new NoPush(() -> new NoPush.Board() {
            @Override
            public String teamOf(String entry) {
                return teams.get(entry);
            }

            @Override
            public void ensureNoCollision(String team) {
                names.add(team);
            }

            @Override
            public boolean exists(String team) {
                return names.contains(team);
            }

            @Override
            public void add(String team, String entry) {
                teams.put(entry, team);
            }

            @Override
            public void remove(String team, String entry) {
                teams.remove(entry, team);
            }

            @Override
            public Set<String> entries(String team) {
                Set<String> out = new HashSet<>();
                teams.forEach((e, t) -> {
                    if (t.equals(team)) {
                        out.add(e);
                    }
                });
                return out;
            }
        });
        Fake sam = new Fake("Sam");
        teams.put("Sam", "red");
        noPush.on(sam.player);
        sam.collidable = false; // a round had them
        FallingFloors.collisionsBack(sam.player, noPush);
        assertEquals(List.of(true), sam.set, "collisions back");
        assertEquals("red", teams.get("Sam"), "off the no-push team, back on their own");
        assertFalse(noPush.isOn(sam.id), "nothing left to undo");

        FallingFloors.collisionsBack(sam.player, noPush);
        assertEquals(List.of(true), sam.set, "a second end changes nothing");
        assertEquals("red", teams.get("Sam"), "and leaves their team alone");

        Fake gone = new Fake("Gone");
        gone.online = false;
        gone.collidable = false;
        FallingFloors.collisionsBack(gone.player, null);
        assertEquals(List.of(), gone.set, "an offline player isn't touched (and no team helper is fine)");
    }

    // ---- arriving -------------------------------------------------------------------------------

    @Test
    void anArrivalIsSentHomeWhenTheArenaClosedOrItsFloorsFailedTheirCheck() {
        UUID kid = UUID.nameUUIDFromBytes("Kid".getBytes());
        assertEquals(ArenaText.closed(), FallingFloors.arrival(null, kid), "the game stopped on the way");
        ArenaService s = new ArenaService(host);
        s.start(); // not verified yet
        assertEquals(FloorsText.FIXING, FallingFloors.arrival(s, kid), "floors not checked: home again");
        ArenaService open = booted();
        assertNull(FallingFloors.arrival(open, kid), "verified: in");
        assertTrue(open.round().isMember(kid), "and in the arena");
    }

    // ---- settings changed ------------------------------------------------------------------------

    @Test
    void aSettingsChangeStartsANewArenaOnlyBetweenRounds() {
        ArenaService s = booted();
        FallingFloorsSettings d = host.settings;
        FallingFloorsSettings moved = new FallingFloorsSettings(true, List.of(6400, 176, 4352), d.fadeTicks(),
                d.minPlayers(), d.maxPlayers(), d.solo(), d.roundSeconds(), d.resetBlocksPerTick(), d.dailyReward(),
                d.milestones(), d.milestoneRewards(), d.dailyCap());
        assertFalse(FallingFloors.startAgain(s, d, FakeArenaHost.WORLD), "the same settings: the same arena");
        assertTrue(FallingFloors.startAgain(s, moved, FakeArenaHost.WORLD), "a new origin in the lobby: a new arena");
        assertTrue(FallingFloors.startAgain(s, d, "other"), "another world: a new arena");
        assertFalse(FallingFloors.startAgain(null, moved, FakeArenaHost.WORLD), "no arena: nothing to start again");

        P alex = in(s, host, "Alex");
        s.solo(alex.id);
        assertTrue(s.round().phase().inRound(), "(a round is going)");
        assertFalse(FallingFloors.startAgain(s, moved, FakeArenaHost.WORLD),
                "the round going finishes on the arena it started on");
    }

    // ---- the box guard ---------------------------------------------------------------------------

    @Test
    void theGuardCoversTheBoxInItsWorldOnly() {
        ArenaService s = booted();
        int x = s.box().minX();
        int y = s.box().minY();
        int z = s.box().minZ();
        assertTrue(FallingFloors.inBox(s, "GAMES", x, y, z), "the box's corner, any case of the world's name");
        assertFalse(FallingFloors.inBox(s, "other", x, y, z), "another world");
        assertFalse(FallingFloors.inBox(s, FakeArenaHost.WORLD, x - 1, y, z), "just outside");
        assertFalse(FallingFloors.inBox(null, FakeArenaHost.WORLD, x, y, z), "no arena");
    }
}
