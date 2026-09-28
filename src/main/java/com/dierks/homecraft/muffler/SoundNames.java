package com.dierks.homecraft.muffler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sound keys as people read them: {@code minecraft:entity.zombie_villager.converted} is
 * "Zombie Villager — converted". The key itself is still shown underneath in the menus, because
 * two sounds can share a friendly name and the key is what a player reports when one misbehaves.
 */
public final class SoundNames {

    /** A namespaced key: lower-case namespace, a colon, a path. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** Where a sound's key sits in the text of the game's own sound objects (see {@link #fromHolderText}). */
    private static final Pattern LOCATION = Pattern.compile("location=([a-z0-9_.-]+:[a-z0-9_./-]+)");
    private static final Pattern RESOURCE_KEY = Pattern.compile("/ ([a-z0-9_.-]+:[a-z0-9_./-]+)\\]");

    private SoundNames() {
    }

    /** {@code chicken} → {@code minecraft:chicken}; an already-namespaced key is lower-cased and kept. */
    public static String normalise(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) {
            return null;
        }
        return k.indexOf(':') >= 0 ? k : "minecraft:" + k;
    }

    /** Whether this reads as a real namespaced sound key. */
    public static boolean isKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** The path of a {@code minecraft:} key ({@code entity.chicken.ambient}); null for any other namespace. */
    public static String vanillaPath(String key) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon < 0) {
            return key;
        }
        return key.startsWith("minecraft:") ? key.substring(colon + 1) : null;
    }

    /**
     * "Chicken — ambient" for {@code minecraft:entity.chicken.ambient}, "Note Block — basedrum"
     * for {@code minecraft:block.note_block.basedrum}. A sound from another namespace keeps it as
     * a prefix ("mypack: Door — creak") so it can't pass for a vanilla one.
     */
    public static String friendly(String key) {
        if (key == null || key.isBlank()) {
            return "Unknown sound";
        }
        String k = key.trim();
        String prefix = "";
        String path = k;
        int colon = k.indexOf(':');
        if (colon >= 0) {
            String ns = k.substring(0, colon);
            path = k.substring(colon + 1);
            if (!ns.equals("minecraft")) {
                prefix = ns + ": ";
            }
        }
        String[] parts = path.split("\\.");
        List<String> words = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                words.add(p);
            }
        }
        if (words.isEmpty()) {
            return prefix + path;
        }
        // Drop the category word (entity/block/item/ui/…) when there is something after it.
        if (words.size() > 1 && isCategory(words.get(0))) {
            words.remove(0);
        }
        String subject = title(words.get(0));
        if (words.size() == 1) {
            return prefix + subject;
        }
        String action = String.join(" ", words.subList(1, words.size())).replace('_', ' ');
        return prefix + subject + " — " + action;
    }

    /**
     * Recover a sound key from the text of the game's own sound holder, for when ProtocolLib
     * cannot hand the sound over as an API object (a resource pack's sound is not in the
     * registry). Both shapes the game prints are understood:
     * {@code Reference{ResourceKey[minecraft:sound_event / minecraft:entity.cow.ambient]=SoundEvent[location=minecraft:entity.cow.ambient, …]}}
     * and {@code Direct{SoundEvent[location=mypack:door.creak, …]}}. Null when neither is there.
     */
    public static String fromHolderText(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = LOCATION.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = RESOURCE_KEY.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Does a search match? Every word typed must appear somewhere in the key, so "zombie door"
     * finds {@code entity.zombie.attack_wooden_door}. Spaces inside a word are not a thing a
     * key has, so "note block" is read as the two words "note" and "block".
     */
    public static boolean matches(String key, String query) {
        if (key == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!word.isEmpty() && !k.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCategory(String word) {
        return switch (word) {
            case "entity", "block", "item", "ui", "ambient", "music", "music_disc", "weather", "event",
                    "enchant", "particle" -> true;
            default -> false;
        };
    }

    private static String title(String word) {
        StringBuilder out = new StringBuilder(word.length());
        boolean up = true;
        for (char c : word.toCharArray()) {
            if (c == '_' || c == '-') {
                out.append(' ');
                up = true;
            } else if (up) {
                out.append(Character.toUpperCase(c));
                up = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
