package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.cabinet.CabinetGame;

import java.util.Locale;

/**
 * A slot's seed for a course day (GEN-SPEC §4.0): the cabinets' daily-board HMAC over the day and
 * {@code "gen:<slot>"} (with {@code "#<reroll>"} after an admin reroll), keyed with the server's
 * secret ({@code GamesDao.secret()}).
 *
 * <p>The same HMAC as the cabinets on purpose: tomorrow's course can't be worked out from the
 * public source and today's date, and a seed an admin has seen (status shows it) can't be walked
 * back to the secret. The secret itself is never shown or logged — only seeds, in hex.
 */
public final class GenSeed {

    private GenSeed() {
    }

    /** The seed of {@code slot}'s layout on course day {@code day}, reroll {@code reroll} (0 for none). */
    public static long seed(long secret, long day, String slot, int reroll) {
        return CabinetGame.seed(secret, day, label(slot, reroll));
    }

    /** What the HMAC is taken over besides the day: {@code gen:<slot>}, or {@code gen:<slot>#<reroll>}. */
    public static String label(String slot, int reroll) {
        return "gen:" + (slot == null ? "" : slot) + (reroll > 0 ? "#" + reroll : "");
    }

    /** A seed as 16 lower-case hex digits, the way rows, status and pins show it. */
    public static String hex(long seed) {
        return String.format(Locale.ROOT, "%016x", seed);
    }

    /** The first four hex digits, for one-line status ("seed 3f2a..."). */
    public static String shortHex(long seed) {
        return hex(seed).substring(0, 4);
    }

    /**
     * A seed as typed or stored: 1 to 16 hex digits, an optional {@code 0x}, any case. {@code null}
     * for anything else. Never throws.
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("0x")) {
            s = s.substring(2);
        }
        boolean hex = s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        if (s.isEmpty() || s.length() > 16 || !hex) {
            return null;
        }
        return Long.parseUnsignedLong(s, 16);
    }
}
