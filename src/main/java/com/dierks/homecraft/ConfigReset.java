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
 * the design (the Arcade, packs, the wild hunt, effects, the clock). Everything else — the market
 * and Mini catalogs above all — is the admin's own data and is refused.
 */
final class ConfigReset {

    /** Sections that can be reset. {@code arcade} also allows any {@code arcade.<child>}. */
    static final Set<String> ALLOWED = Set.of("arcade", "packs", "minis.loot.natural", "minis.effects", "clock");

    private ConfigReset() {
    }

    /** The section name as typed, trimmed and lower-cased. */
    static String normalise(String path) {
        return path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
    }

    static boolean allowed(String path) {
        String p = normalise(path);
        return ALLOWED.contains(p) || (p.startsWith("arcade.") && p.length() > "arcade.".length());
    }

    /** Whether resetting this section changes the quest pools (so today's draws should redraw). */
    static boolean touchesQuests(String path) {
        String p = normalise(path);
        return p.equals("arcade") || p.equals("arcade.quests") || p.startsWith("arcade.quests.");
    }

    /** What a reset would do: each changed leaf (old → new), and the leaves added and removed. */
    record Plan(Map<String, Object[]> changed, Map<String, Object> added, Map<String, Object> removed) {
        boolean none() {
            return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
        }
    }

    /** Compare {@code path} in the admin's file with the bundled file. Changes nothing. */
    static Plan plan(ConfigurationSection current, ConfigurationSection bundled, String path) {
        Map<String, Object> mine = leaves(current, path);
        Map<String, Object> ours = leaves(bundled, path);
        Map<String, Object[]> changed = new LinkedHashMap<>();
        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> removed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ours.entrySet()) {
            if (!mine.containsKey(e.getKey())) {
                added.put(e.getKey(), e.getValue());
            } else if (!Objects.equals(mine.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), new Object[] {mine.get(e.getKey()), e.getValue()});
            }
        }
        for (Map.Entry<String, Object> e : mine.entrySet()) {
            if (!ours.containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        return new Plan(changed, added, removed);
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
