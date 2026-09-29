package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.GameContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live host's routing (the F review's #12), with the server's answers faked: a player in the
 * arena's session is moved by the session's own teleport, anyone else by a plain one, and the
 * answer is whether it was made (so a failed spawn teleport can drop them from the round); the kit
 * is only ever handed to a player in a session, so nobody else's inventory is touched.
 */
class LiveArenaHostTest {

    private static final World GAMES = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (proxy, m, a) -> switch (m.getName()) {
                case "getName" -> "games";
                case "equals" -> proxy == a[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            });

    /** The host, with the server's answers in maps and everything it did in {@link #did}. */
    private static final class Probe extends LiveArenaHost {
        final Map<UUID, Player> online = new HashMap<>();
        final Set<UUID> sessions = new HashSet<>();
        final List<String> did = new ArrayList<>();
        boolean refuse;

        Probe() {
            super(new FallingFloors(new GameContext(null, null)));
        }

        @Override
        Player online(UUID player) {
            return online.get(player);
        }

        @Override
        World bukkitWorld(String name) {
            return "games".equals(name) ? GAMES : null;
        }

        @Override
        boolean inSession(Player p) {
            return sessions.contains(p.getUniqueId());
        }

        @Override
        boolean sessionTeleport(Player p, Location to) {
            did.add("session teleport " + p.getName() + " to y " + to.getY());
            return !refuse;
        }

        @Override
        boolean plainTeleport(Player p, Location to) {
            did.add("plain teleport " + p.getName() + " to y " + to.getY());
            return !refuse;
        }

        @Override
        void fill(Player p, Kit kit) {
            did.add("kit " + p.getName() + " " + kit.kind());
        }

        UUID add(String name, boolean inSession) {
            UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            online.put(id, (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "equals" -> proxy == a[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    }));
            if (inSession) {
                sessions.add(id);
            }
            return id;
        }
    }

    private static final ArenaSite.Spot SPOT = new ArenaSite.Spot(5400.5, 201, 4370.5, 0f);

    @Test
    void aSessionPlayerMovesByTheSessionsOwnTeleportAnyoneElseByAPlainOne() {
        Probe h = new Probe();
        UUID kid = h.add("Kid", true);
        UUID admin = h.add("Admin", false);
        assertTrue(h.teleport(kid, "games", SPOT), "made");
        assertTrue(h.teleport(admin, "games", SPOT), "made");
        assertEquals(List.of("session teleport Kid to y 201.0", "plain teleport Admin to y 201.0"), h.did,
                "the arena's player through the session (it becomes their safe point); an admin plainly");
    }

    @Test
    void aTeleportThatWasntMadeSaysSo() {
        Probe h = new Probe();
        UUID kid = h.add("Kid", true);
        assertFalse(h.teleport(UUID.nameUUIDFromBytes("Offline".getBytes()), "games", SPOT), "offline: not made");
        assertFalse(h.teleport(kid, "nowhere", SPOT), "the world isn't loaded: not made");
        assertFalse(h.teleport(kid, "games", null), "nowhere to go: not made");
        assertEquals(List.of(), h.did, "and nothing was tried");
        h.refuse = true;
        assertFalse(h.teleport(kid, "games", SPOT), "the server refused it: not made");
    }

    @Test
    void theKitIsOnlyEverHandedToAPlayerInASession() {
        Probe h = new Probe();
        UUID kid = h.add("Kid", true);
        UUID admin = h.add("Admin", false);
        h.kit(admin, new ArenaHost.Kit(ArenaHost.KitKind.WATCH, false, false));
        h.kit(UUID.nameUUIDFromBytes("Offline".getBytes()), new ArenaHost.Kit(ArenaHost.KitKind.WATCH, false, false));
        h.kit(kid, null);
        assertEquals(List.of(), h.did, "an admin watching keeps their things; offline or no kit: nothing");
        h.kit(kid, new ArenaHost.Kit(ArenaHost.KitKind.LOBBY, false, true));
        assertEquals(List.of("kit Kid LOBBY"), h.did, "the arena's own player gets it");
    }
}
