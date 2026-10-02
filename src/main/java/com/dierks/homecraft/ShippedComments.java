package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The comments in the owner's config.yml that config revisions 20 and 21 make stale, refreshed from the
 * bundled file (the v4 audit, ECON02).
 *
 * <p><b>Why.</b> A revision moves values, and the backfill only comments keys it adds, so a comment on a key
 * that was already there keeps describing the old version. After 0.37's two revisions the owner's file still
 * said "skill games pay small, capped rewards" (revision 20 is the token balance), and "Everything the games
 * build stands ... x 1760-8191, z 4096-10367, y 128-303 ... Keep the sky there free" with a Mountain Run
 * archived at "about 50 KB" (revision 21 moves golf to x 8768 and the Ice Boat north to z 2880, y 96, and a
 * v2 run is about 0.4 MB), so the file's own "don't build here" leaves out both new areas. Revision 20
 * refreshes the comments of the keys it moves itself ({@link EconomyMigration}); these are the rest.
 *
 * <p><b>Only the plugin's own words.</b> A comment is replaced only while it is exactly, byte for byte, one
 * an earlier version of this plugin shipped on that key (0.35.0's or 0.36.0's), told apart by a fingerprint
 * of its text ({@link #fingerprint}), its inline comment included. A comment the owner wrote or edited, even
 * by one character, is never touched. {@code ShippedCommentsTest} pins every fingerprint to 0.35.0's and
 * 0.36.0's config.yml.
 *
 * <p>Pure on a {@link FileConfiguration}; quiet (the values' own lines say what moved); a server without the
 * comment API keeps its comments.
 */
final class ShippedComments {

    /**
     * One comment a revision makes stale.
     *
     * @param key      the key it sits on
     * @param revision the config revision whose change makes it stale (it is refreshed in that step)
     * @param shipped  the {@link #fingerprint}s of what 0.35.0 and 0.36.0 shipped on it
     */
    record Stale(String key, int revision, Set<String> shipped) {

        Stale {
            shipped = Set.copyOf(shipped);
        }
    }

    /** {@code GamesAreaMigration.REVISION}: the v4 areas (Golf v4 and the Mountain Run v2). */
    private static final int REV_AREAS = com.dierks.homecraft.games.gen.GamesAreaMigration.REVISION;

    /** Every comment revisions 20 and 21 make stale, in file order (frozen: 0.35.0's and 0.36.0's texts). */
    static final List<Stale> STALE = List.of(
            // "skill games pay small, capped rewards" (0.35 = 0.36): the token balance pays about a token a minute
            new Stale("games", EconomyMigration.REVISION, Set.of("50d8aba597d7fd59")),
            // "x 4096-5535, z 4096-4671" (0.35) and "x 1760-8191, z 4096-10367, y 128-303" (0.36): the golf column at
            // x 8768 and the Ice Boat at z 2880, y 96 are outside both
            new Stale("games.fresh.world", REV_AREAS, Set.of("cf98000938cc4089", "450d9f6b116ee1a1")),
            // the slots without the bigger halves, their new spots and the old areas emptied by themselves
            new Stale("games.fresh.slots", REV_AREAS, Set.of("d0f43fc28a015b8a", "b21c017d24e981a6")),
            // "well under 5 MB" (0.35) and "a Mountain Run about 50 KB ... about 6 MB" (0.36): now 0.4 MB, 25 MB
            new Stale("games.fresh.archive", REV_AREAS, Set.of("58fe358d73bbc064", "0081a1a17d71e8e9")),
            // "turn on the Ice Boat first": now switch Race Night on first, so the week it builds is the Winding Road
            new Stale("games.race_night", REV_AREAS, Set.of("4e8655d09535e299")),
            // 0.36: "a short downhill sprint: 5, with finish_window_seconds: 90" - a v2 run is 2-3 minutes
            new Stale("games.race_night.races", REV_AREAS, Set.of("8a1f285efb3ce377", "50903249169a894f")),
            // the windows and the race now grow with the track by themselves
            new Stale("games.race_night.max_race_minutes", REV_AREAS, Set.of("5f2d54fc3b5d2ba7")));

    private ShippedComments() {
    }

    /**
     * Revision {@code revision}'s comments on {@code c} (defaults-free): each {@link #STALE} one of that revision
     * whose key is in the file and whose comment is still one an earlier version shipped takes the bundled
     * file's block and inline comments.
     *
     * @return the keys refreshed, in file order
     */
    static List<String> refresh(FileConfiguration c, int revision) {
        List<String> out = new ArrayList<>();
        YamlConfiguration bundled = ArcadeConfigMigration.bundled();
        if (bundled == null) {
            return out;
        }
        for (Stale s : STALE) {
            if (s.revision() != revision || c.get(s.key(), null) == null || !bundled.contains(s.key())) {
                continue;
            }
            try {
                if (s.shipped().contains(fingerprint(c.getComments(s.key()), c.getInlineComments(s.key())))) {
                    c.setComments(s.key(), bundled.getComments(s.key()));
                    c.setInlineComments(s.key(), bundled.getInlineComments(s.key()));
                    out.add(s.key());
                }
            } catch (Throwable ignored) {
                // Comment API unavailable on this server: the values still moved.
            }
        }
        return out;
    }

    /**
     * A comment's fingerprint: the first 16 hex digits of the SHA-256 of its block lines and then its inline
     * lines, each ended by a newline (a blank line as empty), the two parts split by U+0001.
     */
    static String fingerprint(List<String> block, List<String> inline) {
        StringBuilder sb = new StringBuilder();
        for (String l : block) {
            sb.append(l == null ? "" : l).append('\n');
        }
        sb.append('\u0001');
        for (String l : inline) {
            sb.append(l == null ? "" : l).append('\n');
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((h[i] >> 4) & 0xF, 16)).append(Character.forDigit(h[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }
}
