package com.dierks.homecraft.games.gen.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A course's short, stable name for people (GEN-SPEC-KEEP §8): {@code <SLOTCODE>-<n>}, like
 * {@code HARD-40}, where {@code n} counts that slot's archived editions from 1 and is never used
 * twice. A child can read it off a tile, say it to an admin, and the admin types it into
 * {@code recall} or {@code keep}; the website shows it next to the short seed.
 *
 * <p>Why a code and not the edition key or the seed: {@code 7:40} means nothing to a player and a
 * seed is sixteen hex digits. The number is the slot's own edition count (stored with the archive
 * row and never reused, even after rows are pruned), so it only grows and "HARD-40" is always the
 * same course. Pure: the numbers are handed out by the archive's flip transaction.
 */
public final class CourseCode {

    /** The slot codes, by slot id, in slot order. */
    public static final Map<String, String> SLOT_CODES;

    private static final Pattern CODE = Pattern.compile("([A-Za-z]{2,8})-(\\d{1,9})");

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Slots.DAILY_PARKOUR_EASY.id(), "EASY");
        m.put(Slots.DAILY_PARKOUR_MEDIUM.id(), "PARK");
        m.put(Slots.DAILY_PARKOUR_HARD.id(), "HARD");
        m.put(Slots.SKY_RINGS.id(), "RINGS");
        m.put(Slots.DAILY_GOLF.id(), "GOLF");
        m.put(Slots.TINY_GOLF.id(), "TINY");
        m.put(Slots.ICE_BOAT.id(), "BOAT");
        SLOT_CODES = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * A code read back.
     *
     * @param slot the slot id it belongs to
     * @param n    its number (1 or more)
     */
    public record Parsed(String slot, int n) {

        /** The code as written: {@code HARD-40}. */
        public String code() {
            return format(slot, n);
        }
    }

    private CourseCode() {
    }

    /** The slot's code word ({@code HARD}), or {@code null} for an id with none. */
    public static String slotCode(String slotId) {
        return slotId == null ? null : SLOT_CODES.get(slotId.trim().toLowerCase(Locale.ROOT));
    }

    /** The code of {@code slotId}'s {@code n}-th edition: {@code HARD-40}; {@code null} for a slot with no code. */
    public static String format(String slotId, int n) {
        String c = slotCode(slotId);
        return c == null || n < 1 ? null : c + "-" + n;
    }

    /** A code as typed ({@code hard-40}, {@code HARD-40}), or {@code null} when it isn't one. Never throws. */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CODE.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String word = m.group(1).toUpperCase(Locale.ROOT);
        int n;
        try {
            n = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1) {
            return null;
        }
        for (Map.Entry<String, String> e : SLOT_CODES.entrySet()) {
            if (e.getValue().equals(word)) {
                return new Parsed(e.getKey(), n);
            }
        }
        return null;
    }

    /** Whether {@code text} reads as a code. */
    public static boolean is(String text) {
        return parse(text) != null;
    }
}
