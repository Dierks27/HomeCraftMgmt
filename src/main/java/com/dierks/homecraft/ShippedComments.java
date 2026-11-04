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
 * The comments in the owner's config.yml that config revisions 20, 21, 22, 23, 24 and 25 make stale, refreshed from
 * the bundled file (the v4 audit, ECON02; SHOP-V2-SPEC §7.6).
 *
 * <p><b>Why.</b> A revision moves values, and the backfill only comments keys it adds, so a comment on a key
 * that was already there keeps describing the old version. After 0.37's two revisions the owner's file still
 * said "skill games pay small, capped rewards" (revision 20 is the token balance), and "Everything the games
 * build stands ... x 1760-8191, z 4096-10367, y 128-303 ... Keep the sky there free" with a Mountain Run
 * archived at "about 50 KB" (revision 21 moves golf to x 8768 and the Ice Boat north to z 2880, y 96, and a
 * v2 run is about 0.4 MB), so the file's own "don't build here" leaves out both new areas. Revision 20
 * refreshes the comments of the keys it moves itself ({@link EconomyMigration}); these are the rest, with the
 * {@code games.fresh} header ("Adventure Golf": its golf is Golf v4 now) and {@code keep.area}'s (which courses fit a
 * plot: Golf v4 does, a Mountain Run v2 can't be kept). Revision 22 (the token shop, {@link ShopMigration}) makes
 * three more stale: the Prize Counter's header (no Looks tab, no MONTH, "a hat with a blank texture is HIDDEN"), the
 * icon keys (no {@code looks} or {@code keepers}) and the collectors' (it names 0.37.1's two, and "no presents"
 * now covers looks and golf balls too). Revision 23 (the Grand and Mega Firework Shows, {@link GrandShowMigration})
 * makes 0.38.0's Prize Counter header stale once more: its firework line said "a harmless three-second firework show",
 * and a firework row has {@code seconds} now, and {@code launchers} for a Mega show, whose launcher is a
 * {@code block} row's {@code firework_launcher}. Revision 24 (the Firework Remote and the bigger shows,
 * {@link FireworkRemoteMigration}) makes two of 0.38.1's stale: the Prize Counter's header (its firework line said
 * "launchers (1-3)", and it has no {@code remote}) and {@code arcade.launcher}'s (it said "(1-10, admins too)" and
 * named only the Mega show). The owner's file holds exactly 0.38.1's texts there: revision 23 refreshed the header to
 * 0.38.1's, and the backfill gave the new {@code arcade.launcher} 0.38.1's comment. Revision 25 (Sky Rings v3,
 * {@link com.dierks.homecraft.games.gen.GamesAreaMigration}, and the Long Drop,
 * {@link com.dierks.homecraft.games.gen.DropperMixMigration}) makes two of 0.37.2's stale again: the slots' (it named
 * only the v4 areas as grown, said nothing of Sky Rings' size and said "E easy, M medium, H hard" with no X) and
 * {@code keep.area}'s (it said every Sky Rings course fits a plot). 0.37.2 through 0.38.4 shipped one text on each.
 *
 * <p><b>Only the plugin's own words.</b> A comment is replaced only while it is exactly, byte for byte, one
 * an earlier version of this plugin shipped on that key (0.35.0's, 0.36.0's, 0.37.2's, 0.38.0's or 0.38.1's), told
 * apart by a fingerprint of its text ({@link #fingerprint}), its inline comment included. A comment the owner wrote or
 * edited, even by one character, is never touched. {@code ShippedCommentsTest} pins every fingerprint to 0.35.0's,
 * 0.36.0's, 0.37.2's, 0.38.0's and 0.38.1's config.yml. A key is on the list once for each revision that makes it
 * stale: the Prize Counter's header is revision 22's (0.35.0-0.37.2's text), revision 23's (0.38.0's) and revision
 * 24's (0.38.1's).
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
     * @param shipped  the {@link #fingerprint}s of what the versions before that revision shipped on it (0.35.0,
     *                 0.36.0 and 0.37.2 for revisions 20-22; 0.38.0 for 23; 0.38.1 for 24)
     */
    record Stale(String key, int revision, Set<String> shipped) {

        Stale {
            shipped = Set.copyOf(shipped);
        }
    }

    /** {@code GamesAreaMigration.V4}: the v4 areas (Golf v4 and the Mountain Run v2). */
    private static final int REV_AREAS = com.dierks.homecraft.games.gen.GamesAreaMigration.V4;

    /**
     * {@code GamesAreaMigration.REVISION}, which is {@code DropperMixMigration.REVISION}: Sky Rings v3's areas
     * (512 x 176 x 512, the Classic moved west) and the Long Drop (the Dropper's taller halves and its X level).
     */
    private static final int REV_RINGS = com.dierks.homecraft.games.gen.GamesAreaMigration.REVISION;

    /** {@code ShopMigration.REVISION}: the token shop. */
    private static final int REV_SHOP = ShopMigration.REVISION;

    /** {@code GrandShowMigration.REVISION}: the Grand Firework Show and the gentler candy. */
    private static final int REV_GRAND = GrandShowMigration.REVISION;

    /** {@code FireworkRemoteMigration.REVISION}: the Firework Remote and the bigger Mega shows. */
    private static final int REV_REMOTE = FireworkRemoteMigration.REVISION;

    /**
     * Every comment revisions 20, 21, 22, 23, 24 and 25 make stale, in file order (frozen: 0.35.0's, 0.36.0's,
     * 0.37.2's, 0.38.0's and 0.38.1's texts).
     */
    static final List<Stale> STALE = List.of(
            // the launcher's: "(1-10, admins too)" and only the Mega show (0.38.1); 15 a player and the Remote now
            new Stale("arcade.launcher", REV_REMOTE, Set.of("4b66f235d1bfb564")),
            // the icon keys without looks and keepers (0.35.0 = 0.36.0 = 0.37.2)
            new Stale("arcade.icons", REV_SHOP, Set.of("c2c17c8139179f82")),
            // the Prize Counter's header: no LOOKS tab, no MONTH, ball, party or only_if, "a hat with a blank texture
            // is HIDDEN" (0.35.0 = 0.36.0 = 0.37.2)
            new Stale("arcade.prizes", REV_SHOP, Set.of("ce1063df21e19949")),
            // the Prize Counter's header again: "firework   a harmless three-second firework show." (0.38.0), and a
            // firework row has seconds now (and launchers, and a block row may be a firework_launcher)
            new Stale("arcade.prizes", REV_GRAND, Set.of("ef25d85f3f0a27a8")),
            // the Prize Counter's header once more: "launchers (1-3): a Mega Firework Show from the nearest" and no
            // remote (0.38.1); launchers 1-10, linked with the Firework Remote, and the remote type now
            new Stale("arcade.prizes", REV_REMOTE, Set.of("7a00e71ad596b7f3")),
            // 0.37.1's two collectors named, and "no presents" without looks and golf balls (0.37.1 = 0.37.2)
            new Stale("arcade.collectors", REV_SHOP, Set.of("155cbe62c18e8000")),
            // "skill games pay small, capped rewards" (0.35 = 0.36): the token balance pays about a token a minute
            new Stale("games", EconomyMigration.REVISION, Set.of("50d8aba597d7fd59")),
            // "mini golf" (0.35) and "Adventure Golf" (0.36): Golf of the Week and Tiny Golf are Golf v4 now
            new Stale("games.fresh", REV_AREAS, Set.of("afc4d5a1820c28e6", "b9c18e478e6664ef")),
            // "x 4096-5535, z 4096-4671" (0.35) and "x 1760-8191, z 4096-10367, y 128-303" (0.36): the golf column at
            // x 8768 and the Ice Boat at z 2880, y 96 are outside both
            new Stale("games.fresh.world", REV_AREAS, Set.of("cf98000938cc4089", "450d9f6b116ee1a1")),
            // the slots without the bigger halves, their new spots and the old areas emptied by themselves
            new Stale("games.fresh.slots", REV_AREAS, Set.of("d0f43fc28a015b8a", "b21c017d24e981a6")),
            // the slots again: Sky Rings not among the grown areas, its 512 x 176 x 512 halves unsaid; "E easy,
            // M medium, H hard" without the X, the droppers' halves without the Dropper's 144 (0.37.2-0.38.4)
            new Stale("games.fresh.slots", REV_RINGS, Set.of("da5555ee01b0e940")),
            // "well under 5 MB" (0.35) and "a Mountain Run about 50 KB ... about 6 MB" (0.36): now 0.4 MB, 25 MB
            new Stale("games.fresh.archive", REV_AREAS, Set.of("58fe358d73bbc064", "0081a1a17d71e8e9")),
            // the plots, without what fits one (0.35, 0.36): Golf v4 does; a Mountain Run v2 can't be kept
            new Stale("games.fresh.keep.area", REV_AREAS, Set.of("a49d2cd2068a4432", "fdd47af4be7d3f8b")),
            // the plots again: "every parkour, Sky Rings and dropper course" fits (0.37.2-0.38.4); a Sky Rings v3
            // course can't be kept now
            new Stale("games.fresh.keep.area", REV_RINGS, Set.of("cf5d4a7ce0dd8204")),
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
