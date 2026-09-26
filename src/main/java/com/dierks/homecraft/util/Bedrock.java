package com.dierks.homecraft.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Is this player on Bedrock (through Geyser + Floodgate)?
 *
 * <p>Bedrock clients differ in two ways the menus care about: a custom-textured player head in an
 * inventory shows as a plain head unless the proxy registers it, and rapid inventory updates
 * stutter. Screens use this to pick a plain-material icon and a shorter animation.
 *
 * <p>Floodgate is a soft dependency, reached by reflection so the plugin builds and runs without
 * it. No Floodgate means every player is treated as Java.
 */
public final class Bedrock {

    private static volatile boolean resolved;
    private static Object api;
    private static Method isFloodgatePlayer;

    private Bedrock() {
    }

    public static boolean is(Player player) {
        return player != null && is(player.getUniqueId());
    }

    public static boolean is(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        resolve();
        if (isFloodgatePlayer == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, uuid));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (Bedrock.class) {
            if (resolved) {
                return;
            }
            try {
                Class<?> cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                api = cls.getMethod("getInstance").invoke(null);
                isFloodgatePlayer = cls.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                api = null;
                isFloodgatePlayer = null;
            }
            resolved = true;
        }
    }
}
