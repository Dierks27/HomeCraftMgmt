package com.dierks.homecraft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code /hcm config reset <section>}: put one section of config.yml back to the bundled
 * defaults.
 *
 * <p>The config migrations keep any value that isn't what we shipped, on the assumption an admin
 * chose it. That is right for real choices and wrong for values an earlier setup pass wrote, which
 * look exactly the same. This is the explicit way out, for the sections where the defaults are
 * the design (the Arcade, the Games, packs, the wild hunt, effects, the clock). Everything else — the market
 * and Mini catalogs above all — is the admin's own data and is refused.
 */
final class ConfigReset {

    /**
     * Sections that can be reset. {@code arcade} also allows any {@code arcade.<child>}, and
     * {@code games} any {@code games.<child>} (one game's block, or {@code games.break}).
     */
    static final Set<String> ALLOWED = Set.of("arcade", "games", "packs", "minis.loot.natural", "minis.effects",
            "clock");

    private ConfigReset() {
    }

    /** The section name as typed, trimmed and lower-cased. */
    static String normalise(String path) {
        return path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
    }

    static boolean allowed(String path) {
        String p = normalise(path);
        return ALLOWED.contains(p) || (p.startsWith("arcade.") && p.length() > "arcade.".length())
                || (p.startsWith("games.") && p.length() > "games.".length());
    }

    /** Whether resetting this section changes the quest pools (so today's draws should redraw). */
    static boolean touchesQuests(String path) {
        String p = normalise(path);
        return p.equals("arcade") || p.equals("arcade.quests") || p.startsWith("arcade.quests.");
    }

    /**
     * What a reset would do: each changed leaf (old → new), and the leaves added and removed; and the
     * keys that say where the Games places stand, which it leaves as they are although they differ from
     * the bundled file ({@link #kept}), with their value on disk ({@code null}: not set).
     */
    record Plan(Map<String, Object[]> changed, Map<String, Object> added, Map<String, Object> removed,
                Map<String, Object> kept) {
        boolean none() {
            return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
        }
    }

    /** The Games worlds ({@code games.worlds}) and the one Fresh Courses builds in ({@code games.fresh.world}). */
    static final List<String> WORLD_PATHS = List.of(
            com.dierks.homecraft.config.GamesConfig.PATH + ".worlds",
            com.dierks.homecraft.config.GamesConfig.PATH + "." + com.dierks.homecraft.config.GamesConfig.FRESH_BLOCK
                    + ".world");

    /**
     * The keys under {@code path} that say where the Games places stand, which a reset leaves exactly as
     * they are on disk: every course's and Classic's {@code origin} and {@code half_gap}, {@code keep.area}
     * and {@code keep.plot_gap}, the Clubhouse's and the arena's {@code origin}
     * ({@code LayoutGuard.spotPaths}), and the Games worlds ({@link #WORLD_PATHS}).
     *
     * <p>Putting any of them back would move what is built: a course rerolled at the bundled spot (or in
     * the bundled world) with its blocks left standing, "drain first" for a Dropper or a golf course, a
     * second Clubhouse. On a server that kept 0.35's spots every bundled origin is such a move; on any
     * server, so is one the owner moved by hand, and the Games world the owner chose (a void world,
     * {@code sky}). Places move by hand only (README "Moving an area by hand": clear first), never by a reset.
     */
    static List<String> kept(FileConfiguration current, String path) {
        List<String> out = new ArrayList<>();
        String p = normalise(path);
        List<String> where = new ArrayList<>(com.dierks.homecraft.games.gen.LayoutGuard.spotPaths(current));
        where.addAll(WORLD_PATHS);
        for (String k : where) {
            if (k.equals(p) || k.startsWith(p + ".") || p.startsWith(k + ".")) {
                out.add(k);
            }
        }
        return out;
    }

    /** Compare {@code path} in the admin's file with the bundled file. Changes nothing. */
    static Plan plan(ConfigurationSection current, ConfigurationSection bundled, String path) {
        return plan(current, bundled, path, List.of());
    }

    /** {@link #plan(ConfigurationSection, ConfigurationSection, String)}, leaving {@code keep} ({@link #kept}) as it is. */
    static Plan plan(ConfigurationSection current, ConfigurationSection bundled, String path, List<String> keep) {
        Map<String, Object> mine = leaves(current, path);
        Map<String, Object> ours = leaves(bundled, path);
        Map<String, Object[]> changed = new LinkedHashMap<>();
        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> removed = new LinkedHashMap<>();
        Map<String, Object> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ours.entrySet()) {
            String held = under(e.getKey(), keep);
            if (held != null) {
                if (!Objects.equals(mine.get(e.getKey()), e.getValue())) {
                    kept.put(held, current.get(held, null));
                }
            } else if (!mine.containsKey(e.getKey())) {
                added.put(e.getKey(), e.getValue());
            } else if (!Objects.equals(mine.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), new Object[] {mine.get(e.getKey()), e.getValue()});
            }
        }
        for (Map.Entry<String, Object> e : mine.entrySet()) {
            String held = under(e.getKey(), keep);
            if (held != null) {
                if (!ours.containsKey(e.getKey())) {
                    kept.put(held, current.get(held, null));
                }
            } else if (!ours.containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        return new Plan(changed, added, removed, kept);
    }

    /** The path of {@code keep} that {@code leaf} is or is under, or {@code null}. */
    private static String under(String leaf, List<String> keep) {
        for (String k : keep) {
            if (leaf.equals(k) || leaf.startsWith(k + ".")) {
                return k;
            }
        }
        return null;
    }

    /** Every leaf value under {@code path} (a list is one leaf), by full path. */
    private static Map<String, Object> leaves(ConfigurationSection c, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c.isConfigurationSection(path)) {
            ConfigurationSection s = c.getConfigurationSection(path);
            for (String key : s.getKeys(true)) {
                if (!s.isConfigurationSection(key)) {
                    out.put(path + "." + key, s.get(key));
                }
            }
        } else if (c.get(path, null) != null) {
            out.put(path, c.get(path));
        }
        return out;
    }

    /**
     * {@link #apply(FileConfiguration, FileConfiguration, String)}, then every path of {@code keep}
     * ({@link #kept}) put back exactly as it was on disk (one that wasn't set is left unset).
     */
    static void apply(FileConfiguration current, FileConfiguration bundled, String path, List<String> keep) {
        Map<String, Object> held = new LinkedHashMap<>();
        for (String k : keep) {
            Object v = current.get(k, null);
            held.put(k, v instanceof List<?> l ? new ArrayList<>(l) : v instanceof ConfigurationSection ? null : v);
        }
        apply(current, bundled, path);
        for (Map.Entry<String, Object> e : held.entrySet()) {
            current.set(e.getKey(), e.getValue());
        }
    }

    /**
     * Replace {@code path} in {@code current} with the bundled version, comments included. The
     * section keeps its place in the file: its children are cleared and refilled rather than the
     * section being removed and re-added at the end.
     */
    static void apply(FileConfiguration current, FileConfiguration bundled, String path) {
        if (current.isConfigurationSection(path)) {
            ConfigurationSection s = current.getConfigurationSection(path);
            for (String key : new ArrayList<>(s.getKeys(false))) {
                s.set(key, null);
            }
        }
        if (bundled.isConfigurationSection(path)) {
            ConfigurationSection s = bundled.getConfigurationSection(path);
            if (!current.isConfigurationSection(path)) {
                current.set(path, null);
                current.createSection(path);
            }
            for (String key : s.getKeys(true)) {
                String full = path + "." + key;
                if (s.isConfigurationSection(key)) {
                    if (!current.isConfigurationSection(full)) {
                        current.createSection(full);
                    }
                } else {
                    current.set(full, s.get(key));
                }
                comments(bundled, current, full);
            }
        } else {
            current.set(path, bundled.get(path));
        }
        comments(bundled, current, path);
    }

    private static void comments(ConfigurationSection from, ConfigurationSection to, String key) {
        try {
            to.setComments(key, from.getComments(key));
            to.setInlineComments(key, from.getInlineComments(key));
        } catch (Throwable ignored) {
            // no comment API: values still reset
        }
    }

    /** A value short enough for one chat line. */
    static String brief(Object v) {
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
