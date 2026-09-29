package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the race-mode and warm-up tests share: a fake {@link RaceLink} that remembers what it was
 * told, and fake players (an interface answered by a proxy: the few methods a test touches, never a
 * server).
 */
final class TrialFakes {

    private TrialFakes() {
    }

    /** A race's coordinator, as a test drives it: alive or not, its go tick, and what it heard. */
    static class Link implements RaceLink {
        boolean alive = true;
        long goTick = Long.MAX_VALUE;
        boolean normal;
        long warmupUntil;
        final List<String> heard = new ArrayList<>();

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public long goTick() {
            return goTick;
        }

        @Override
        public void progress(UUID racer, int reachedTargets, double toNext, long nanos) {
            heard.add("progress " + reachedTargets);
        }

        @Override
        public void finished(UUID racer, long raceMs, boolean counted, String voidReason) {
            heard.add("finished " + raceMs + " " + counted);
        }

        @Override
        public void left(UUID racer, EndReason why) {
            heard.add("left " + why);
        }

        @Override
        public boolean normalRun() {
            return normal;
        }

        @Override
        public long warmupUntil() {
            return warmupUntil;
        }
    }

    /** A player who is {@code id}, called {@code name}, whose chat lines (plain) go to {@code said}. */
    static Player player(UUID id, String name, List<String> said) {
        return player(id, name, said, () -> null);
    }

    /** {@link #player(UUID, String, List)} standing where {@code where} says (read on every ask). */
    static Player player(UUID id, String name, List<String> said, java.util.function.Supplier<org.bukkit.Location> where) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            org.bukkit.Location l = where.get();
                            return l == null ? null : l.clone();
                        }
                        case "getUniqueId" -> {
                            return id;
                        }
                        case "getName" -> {
                            return name;
                        }
                        case "isOnline" -> {
                            return true;
                        }
                        case "sendMessage" -> {
                            if (args != null && args.length == 1 && args[0] instanceof Component c) {
                                said.add(plain(c));
                            } else if (args != null && args.length == 1 && args[0] instanceof String s) {
                                said.add(s);
                            }
                            return null;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "FakePlayer[" + name + "]";
                        }
                        default -> {
                            return defaultOf(method.getReturnType());
                        }
                    }
                });
    }

    private static String plain(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c).replaceAll("§.", "");
    }

    private static Object defaultOf(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
