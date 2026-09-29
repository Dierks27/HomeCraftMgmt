package com.dierks.homecraft.games;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;

/**
 * A test player seen with less than they have (the final gate's group B): the same player (id, name,
 * what they hear, online) with some permissions taken away, or standing in another world. Tests outside
 * this package use it with {@link GamesBench}'s players to walk the play gate's permission and world
 * steps (a child whose parent took {@code hcm.games.play} away, a player in the nether or a creative
 * build world).
 */
public final class PlayerAs {

    private PlayerAs() {
    }

    /**
     * {@code base}, but without the permissions in {@code denied}, and standing in the world named
     * {@code world} ({@code null}: where they are).
     */
    public static Player limited(Player base, Set<String> denied, String world) {
        World w = world == null ? null : world(world);
        return (Player) Proxy.newProxyInstance(PlayerAs.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "hasPermission":
                            if (a != null && a.length == 1 && a[0] instanceof String node && denied.contains(node)) {
                                return false;
                            }
                            break;
                        case "getWorld":
                            if (w != null) {
                                return w;
                            }
                            break;
                        case "equals":
                            return proxy == a[0] || base.equals(a[0]);
                        case "hashCode":
                            return base.hashCode();
                        default:
                            break;
                    }
                    try {
                        return m.invoke(base, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A world with only a name. */
    public static World world(String name) {
        return GamesKit.world(name);
    }
}
