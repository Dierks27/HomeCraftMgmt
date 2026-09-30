package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.cabinet.CabinetGame;

import java.util.Locale;

/**
 * A slot's seed for an edition (GEN-SPEC §4.0, weekly addendum §1): the cabinets' daily-board HMAC,
 * keyed with the server's secret ({@code GamesDao.secret()}), over the edition key and the slot —
 * the edition's index as the HMAC's "day", and {@code "gen:<slot>@<N>"} (with {@code "#<reroll>"}
 * after an admin reroll) as its label, N being the cadence in days.
 *
 * <p>The cadence is in the label, so a daily and a weekly edition never share a seed even when
 * their indexes happen to match: {@code (secret, edition key, slot)} decides the layout, nothing
 * else.
 *
 * <p>The same HMAC as the cabinets on purpose: the next course can't be worked out from the public
 * source and the date, and a seed an admin has seen (status shows it) can't be walked back to the
 * secret. The secret itself is never shown or logged — only seeds, in hex.
 */
public final class GenSeed {

    private GenSeed() {
    }

    /**
     * The seed of {@code slot}'s layout in the {@code cadence}-day edition that starts on local day
     * {@code startDay}, reroll {@code reroll} (0 for none).
     */
    public static long seed(long secret, int cadence, long startDay, String slot, int reroll) {
        int n = Edition.clampCadence(cadence);
        return CabinetGame.seed(secret, Edition.index(n, startDay), label(slot, n, reroll));
    }

    /** The seed of {@code slot}'s layout in the daily edition of local day {@code day} (N = 1). */
    public static long seed(long secret, long day, String slot, int reroll) {
        return seed(secret, Edition.DAILY, day, slot, reroll);
    }

    /**
     * What the HMAC is taken over besides the edition's index: {@code gen:<slot>@<N>}, or
     * {@code gen:<slot>@<N>#<reroll>}.
     */
    public static String label(String slot, int cadence, int reroll) {
        return "gen:" + (slot == null ? "" : slot) + "@" + Edition.clampCadence(cadence) + (reroll > 0 ? "#" + reroll
                : "");
    }

    /** The label of a daily edition ({@link #label(String, int, int)} with N = 1). */
    public static String label(String slot, int reroll) {
        return label(slot, Edition.DAILY, reroll);
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
