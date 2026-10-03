package com.dierks.homecraft.command;

import org.bukkit.configuration.ConfigurationSection;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A world's game mode as Multiverse-Core has it, for {@code /hcm games check}, read without depending on
 * Multiverse: its API by reflection, and as a last resort its own {@code worlds.yml}.
 *
 * <p>Why by shape and not by name: Multiverse-Core 5 shades vavr under its own package
 * ({@code org.mvplugins.multiverse.external.vavr.control.Option}), so an {@code Option} recognised by
 * vavr's package name was never unwrapped, and the check said "can't tell its game mode" on 5.8.1 while
 * {@code /mv info} said ADVENTURE (live test, 2 Oct). So any {@code Optional}-like value is unwrapped by
 * what it can do: a no-arg {@code getOrNull()}, or {@code isEmpty()}/{@code isDefined()} plus
 * {@code get()}. And a mode is only believed when it is one of the four game modes, so anything else
 * reads as "can't tell" and the next way is tried.
 */
final class MvGameMode {

    /** The game modes there are, by name. */
    private static final List<String> MODES = List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");

    /**
     * Multiverse-Core 5's {@code WorldManager} finders, tried in turn: every world it knows (loaded or
     * not), only the loaded ones, then by name or alias.
     */
    static final List<String> FINDERS = List.of("getWorld", "getLoadedWorld", "getWorldByNameOrAlias");

    private MvGameMode() {
    }

    /**
     * The game mode Multiverse-Core 5's {@code WorldManager} ({@code worlds}) has for {@code world}
     * ({@code ADVENTURE}), each of {@link #FINDERS} tried until one answers; {@code null} when none can.
     */
    static String fromWorldManager(Object worlds, String world) {
        if (worlds == null || world == null) {
            return null;
        }
        for (String finder : FINDERS) {
            try {
                String mode = name(call(unwrap(call(worlds, finder, world)), "getGameMode"));
                if (mode != null) {
                    return mode;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // that finder isn't there, or it failed: the next one
            }
        }
        return null;
    }

    /**
     * The game mode Multiverse-Core 5's {@code worlds.yml} ({@code file}) has for {@code world}, or
     * {@code null}. Each world is a top-level section keyed by its name with every dot written
     * {@code [dot]} (or, since 5.7, by its namespaced key: {@code minecraft:games} is the world
     * {@code games}, {@code myplugin:games} is {@code myplugin_games}), and its mode is {@code gamemode},
     * written in any case. The exact name wins over one that only matches when case is ignored.
     */
    static String fromWorldsFile(ConfigurationSection file, String world) {
        if (file == null || world == null || world.isBlank()) {
            return null;
        }
        ConfigurationSection loose = null;
        for (String key : file.getKeys(false)) {
            ConfigurationSection section = file.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String named = worldOf(key);
            if (named.equals(world)) {
                return known(section.getString("gamemode", ""));
            }
            if (loose == null && named.equalsIgnoreCase(world)) {
                loose = section;
            }
        }
        return loose == null ? null : known(loose.getString("gamemode", ""));
    }

    /**
     * The world a {@code worlds.yml} key names: {@code world[dot]a} is {@code world.a},
     * {@code minecraft:games} is {@code games}.
     */
    static String worldOf(String key) {
        String k = key.replace("[dot]", ".");
        int colon = k.indexOf(':');
        if (colon < 0) {
            return k;
        }
        String namespace = k.substring(0, colon);
        String path = k.substring(colon + 1);
        return namespace.equals("minecraft") ? path : namespace + "_" + path;
    }

    /**
     * A game mode's name ({@code ADVENTURE}) from whatever holds it (an enum, a string in any case, or
     * either wrapped in an {@code Optional}-like value); {@code null} when it isn't one of the four.
     */
    static String name(Object mode) throws ReflectiveOperationException {
        Object m = unwrap(mode);
        return m == null ? null : known(m instanceof Enum<?> e ? e.name() : String.valueOf(m));
    }

    /** {@code text} as a game mode's name ({@code ADVENTURE}), in any case; {@code null} when it isn't one. */
    private static String known(String text) {
        String n = text.trim().toUpperCase(Locale.ROOT);
        return MODES.contains(n) ? n : null;
    }

    /**
     * {@code o} unwrapped when it is {@code Optional}-like: a {@link Optional}, or anything with a no-arg
     * {@code getOrNull()}, or with {@code isEmpty()} or {@code isDefined()}/{@code isPresent()} plus
     * {@code get()} (vavr's {@code Option} wherever it is shaded); an empty one gives {@code null}. A plain
     * value comes back as it is.
     */
    static Object unwrap(Object o) throws ReflectiveOperationException {
        if (o == null) {
            return null;
        }
        if (o instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        Method orNull = noArg(o, "getOrNull", null);
        if (orNull != null) {
            return orNull.invoke(o);
        }
        Method get = noArg(o, "get", null);
        if (get == null) {
            return o;
        }
        Method isEmpty = noArg(o, "isEmpty", boolean.class);
        if (isEmpty != null) {
            return (Boolean) isEmpty.invoke(o) ? null : get.invoke(o);
        }
        Method isDefined = noArg(o, "isDefined", boolean.class);
        if (isDefined == null) {
            isDefined = noArg(o, "isPresent", boolean.class);
        }
        if (isDefined != null) {
            return (Boolean) isDefined.invoke(o) ? get.invoke(o) : null;
        }
        return o;
    }

    /** {@code o}'s public no-arg method {@code name} (returning {@code returns}, when given), or {@code null}. */
    private static Method noArg(Object o, String name, Class<?> returns) {
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0
                    && (returns == null || m.getReturnType() == returns)) {
                m.trySetAccessible();
                return m;
            }
        }
        return null;
    }

    /** A public method by name taking these arguments, called; a {@code null} target gives {@code null}. */
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && accepts(m, args)) {
                m.trySetAccessible();
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    /** Whether {@code m} takes these arguments ({@code getWorld(String)}, not {@code getWorld(World)}). */
    private static boolean accepts(Method m, Object[] args) {
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }
}
