package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.GamesAreaMigration;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comments config revisions 20, 21, 22, 23 and 24 make stale in the owner's file ({@link ShippedComments}, the v4
 * audit ECON02/ECON05, SHOP-V2-SPEC §7.6), against verbatim copies of 0.35.0's, 0.36.0's, 0.37.2's, 0.38.0's and
 * 0.38.1's bundled config.yml.
 *
 * <p>Pinned here: the owner's 0.36 file, migrated and backfilled, ends with exactly the bundled file's comments
 * on every key, so it no longer says "x 1760-8191 ... Keep the sky there free", "a Mountain Run about 50 KB",
 * "skill games pay small, capped rewards", "a short downhill sprint" or "Adventure Golf" in its header, and its
 * keep area says Golf v4 fits a plot and a Mountain Run v2 can't be kept; a 0.35 file's are refreshed too; a
 * comment the owner edited by one character is kept, word for word; each revision refreshes only its own;
 * the owner's 0.37.2 file ends with the token shop's three comments (the Prize Counter's header, the icon keys
 * and the collectors'); the owner's 0.38.0 file ends with revision 23's (the header's firework line); the owner's
 * 0.38.1 file ends with revision 24's (the header's launchers 1-10 and remote lines, and the launcher's 1-30 and the
 * Remote) and revision 25's (the slots' comment with the Long Drop); and every fingerprint is what the versions before
 * its revision shipped on that key (0.35.0, 0.36.0 or 0.37.2 for 20-22, 0.38.0 for 23, 0.38.1 for 24 and 25).
 */
class ShippedCommentsTest {

    private static YamlConfiguration migrated(YamlConfiguration c) {
        HomeCraftManagement.migrateConfig(c, "world");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        return LayoutFixtures.reread(c);
    }

    private static String fingerprint(YamlConfiguration c, String key) {
        return ShippedComments.fingerprint(c.getComments(key), c.getInlineComments(key));
    }

    @Test
    void theOwners036FileEndsWithExactlyTheBundledComments() {
        YamlConfiguration after = migrated(LayoutFixtures.v036());
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (String key : bundled.getKeys(true)) {
            assertEquals(bundled.getComments(key), after.getComments(key), key + "'s comment is this version's");
            assertEquals(bundled.getInlineComments(key), after.getInlineComments(key), key + "'s inline comment too");
        }
        String text = after.saveToString();
        for (String stale : List.of("x 1760-8191", "Keep the sky there", "about 50 KB", "about 6 MB",
                "pay small, capped rewards", "short downhill sprint", "a downhill race with 4-6",
                "Adventure Golf")) {
            assertFalse(text.contains(stale), "no \"" + stale + "\" is left in the migrated file");
        }
        assertTrue(text.contains("x 1760-9599, z 2880-10367, y 96 and up"), "the new don't-build-here bounds");
        assertTrue(text.contains("/hcm games gen tidy"), "and the slots' tidy note");
        assertTrue(text.contains("about 0.4 MB"), "and the archive's new size");
        assertTrue(String.join("\n", after.getComments("games.fresh")).contains("mini golf (Golf v4: Golf of the Week"
                + " and Tiny Golf)"), "the header names this version's golf");
        String keep = String.join("\n", after.getComments("games.fresh.keep.area"));
        assertTrue(keep.contains("Golf v4's") && keep.contains("128 x 16 x 224 does")
                && keep.contains("a Mountain Run v2") && keep.contains("can't be kept"), "and what fits a plot: " + keep);
    }

    @Test
    void theOwners0372FileEndsWithExactlyTheBundledCommentsAndTheShopsThree() {
        YamlConfiguration after = migrated(LayoutFixtures.v0372());
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (String key : bundled.getKeys(true)) {
            assertEquals(bundled.getComments(key), after.getComments(key), key + "'s comment is this version's");
            assertEquals(bundled.getInlineComments(key), after.getInlineComments(key), key + "'s inline comment too");
        }
        String prizes = String.join("\n", after.getComments("arcade.prizes"));
        assertTrue(prizes.contains("COSMETICS | LOOKS | PERKS") && prizes.contains("DAY | WEEK | MONTH | LIFETIME")
                && prizes.contains("ball       look (a block)") && prizes.contains("party      seconds")
                && prizes.contains("only_if (night | rain"), "the counter's header names 0.38's rows: " + prizes);
        assertFalse(prizes.contains("A hat with a blank texture is HIDDEN."), "and not 0.37's hat line");
        assertTrue(String.join("\n", after.getComments("arcade.icons")).contains("cosmetics, looks, perks, trophies,"
                + " minis, keepers)."), "the icon keys");
        String collectors = String.join("\n", after.getComments("arcade.collectors"));
        assertFalse(collectors.contains("0.37.1"), "the collectors' comment names no version: " + collectors);
        assertTrue(collectors.contains("no presents at all (looks, golf balls"), collectors);
    }

    @Test
    void theOwners0380FileEndsWithExactlyTheBundledCommentsAndTheNewFireworkLine() {
        YamlConfiguration before = LayoutFixtures.v0380();
        assertTrue(String.join("\n", before.getComments("arcade.prizes"))
                .contains("firework   a harmless three-second firework show."), "fixture: 0.38.0's firework line");
        YamlConfiguration after = migrated(before);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (String key : bundled.getKeys(true)) {
            assertEquals(bundled.getComments(key), after.getComments(key), key + "'s comment is this version's");
            assertEquals(bundled.getInlineComments(key), after.getInlineComments(key), key + "'s inline comment too");
        }
        String prizes = String.join("\n", after.getComments("arcade.prizes"));
        assertTrue(prizes.contains("firework   a harmless firework show; seconds 3-30, default 3."), prizes);
        assertTrue(prizes.contains("launchers (1-10): a Mega Firework Show from Firework Launchers"),
                "and the Mega show's launchers (0.38.1, 1-10 from 0.38.2)");
        assertTrue(prizes.contains("block: display_case, pallet, vending, mailbox, printer, pc, arcade or"
                + " firework_launcher)"), "and the launcher's block");
        assertFalse(prizes.contains("three-second"), "and not 0.38.0's line");
        assertEquals(bundled.getComments("arcade.launcher"), after.getComments("arcade.launcher"),
                "the new arcade.launcher comes with its comment");
        assertEquals(bundled.getInlineComments("skins.firework_launcher"),
                after.getInlineComments("skins.firework_launcher"), "and skins.firework_launcher with its own");
    }

    @Test
    void theOwners0381FileEndsWithExactlyTheBundledCommentsAndTheRemote() {
        YamlConfiguration before = LayoutFixtures.v0381();
        assertTrue(String.join("\n", before.getComments("arcade.prizes")).contains("launchers (1-3)"),
                "fixture: 0.38.1's launchers line");
        assertTrue(String.join("\n", before.getComments("arcade.launcher")).contains("(1-10, admins too)"),
                "fixture: 0.38.1's launcher comment");
        YamlConfiguration after = migrated(before);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (String key : bundled.getKeys(true)) {
            assertEquals(bundled.getComments(key), after.getComments(key), key + "'s comment is this version's");
            assertEquals(bundled.getInlineComments(key), after.getInlineComments(key), key + "'s inline comment too");
        }
        String prizes = String.join("\n", after.getComments("arcade.prizes"));
        assertTrue(prizes.contains("launchers (1-10): a Mega Firework Show from Firework Launchers (arcade.launcher)")
                && prizes.contains("Remote, else the nearest in range."), prizes);
        assertTrue(prizes.contains("remote     the Firework Remote: tap launchers to link them for your shows"), prizes);
        assertFalse(prizes.contains("launchers (1-3)"), "and not 0.38.1's line");
        String launcher = String.join("\n", after.getComments("arcade.launcher"));
        assertTrue(launcher.contains("(1-30, admins too)") && launcher.contains("Firework Remote")
                && launcher.contains("Mega, Super, Ultra and Ultimate"), launcher);
        String slots = String.join("\n", after.getComments("games.fresh.slots"));
        assertTrue(slots.contains("X the Long Drop") && slots.contains("64 x 144 x 16"), "revision 25's: " + slots);
        assertEquals(bundled.getComments("arcade.holiday_shows"), after.getComments("arcade.holiday_shows"),
                "the new holiday section comes with its comment (the backfill's)");
    }

    @Test
    void a035FilesStaleCommentsAreRefreshedToo() {
        YamlConfiguration after = migrated(LayoutFixtures.v035());
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (ShippedComments.Stale s : ShippedComments.STALE) {
            assertEquals(bundled.getComments(s.key()), after.getComments(s.key()), s.key() + " is this version's");
        }
        assertFalse(after.saveToString().contains("x 4096-5535, z 4096-4671"), "0.35's bounds are gone");
    }

    @Test
    void aCommentTheOwnerEditedIsKeptWordForWord() {
        YamlConfiguration c = LayoutFixtures.v036();
        List<String> world = new ArrayList<>(c.getComments("games.fresh.world"));
        world.set(world.size() - 1, world.get(world.size() - 1) + " (mine: the skybase is at x 9000)");
        c.setComments("games.fresh.world", world);
        c.setInlineComments("games.fresh.archive", List.of("keep forever"));
        List<String> header = new ArrayList<>(c.getComments("games"));
        header.add("my note");
        c.setComments("games", header);
        YamlConfiguration after = migrated(c);
        assertEquals(world, after.getComments("games.fresh.world"), "the owner's world comment stays");
        assertEquals(List.of("keep forever"), after.getInlineComments("games.fresh.archive"),
                "an inline comment of the owner's keeps the block above it too");
        assertTrue(after.getComments("games.fresh.archive").stream().anyMatch(l -> l != null && l.contains("50 KB")),
                "(which is still 0.36's)");
        assertEquals(header, after.getComments("games"), "and their header");
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertEquals(bundled.getComments("games.fresh.slots"), after.getComments("games.fresh.slots"),
                "while the plugin's own words beside them are refreshed");
    }

    @Test
    void eachRevisionRefreshesOnlyItsOwn() {
        YamlConfiguration at20 = LayoutFixtures.v036();
        at20.set("config_revision", EconomyMigration.REVISION); // past the token balance, not the areas
        List<String> header = at20.getComments("games");
        HomeCraftManagement.migrateConfig(at20, "world");
        assertEquals(header, at20.getComments("games"), "revision 20's comment isn't touched past 20");
        assertEquals(LayoutFixtures.bundled().getComments("games.fresh.world"), at20.getComments("games.fresh.world"),
                "while 21's are");

        YamlConfiguration at21 = LayoutFixtures.v0372();
        List<String> icons = new ArrayList<>(at21.getComments("arcade.icons"));
        icons.add("my keys");
        at21.setComments("arcade.icons", icons);
        HomeCraftManagement.migrateConfig(at21, "world");
        assertEquals(icons, at21.getComments("arcade.icons"), "22 keeps an icon comment the owner edited");
        assertEquals(LayoutFixtures.bundled().getComments("arcade.prizes"), at21.getComments("arcade.prizes"),
                "while refreshing the counter's header beside it");

        YamlConfiguration c = LayoutFixtures.v036();
        assertEquals(List.of("arcade.icons", "arcade.prizes"), ShippedComments.refresh(c, ShopMigration.REVISION),
                "22 on a 0.36 file: the two it has (no collectors yet)");
        assertEquals(List.of("arcade.icons", "arcade.prizes", "arcade.collectors"),
                ShippedComments.refresh(LayoutFixtures.v0372(), ShopMigration.REVISION), "and all three on 0.37.2's");
        assertEquals(List.of("games"), ShippedComments.refresh(c, EconomyMigration.REVISION), "20: the header");
        assertEquals(List.of("arcade.prizes"), ShippedComments.refresh(LayoutFixtures.v0380(),
                GrandShowMigration.REVISION), "23 on 0.38.0's file: the counter's header");
        assertEquals(List.of(), ShippedComments.refresh(LayoutFixtures.v0372(), GrandShowMigration.REVISION),
                "and nothing on 0.37.2's, whose header is 22's to refresh");
        assertEquals(List.of("arcade.launcher", "arcade.prizes"), ShippedComments.refresh(LayoutFixtures.v0381(),
                FireworkRemoteMigration.REVISION), "24 on 0.38.1's file: the launcher's and the counter's header");
        assertEquals(List.of(), ShippedComments.refresh(LayoutFixtures.v0380(), FireworkRemoteMigration.REVISION),
                "and nothing on 0.38.0's (no launcher yet, and its header is 23's to refresh)");
        assertEquals(List.of("games.fresh.slots"), ShippedComments.refresh(LayoutFixtures.v0381(),
                com.dierks.homecraft.games.gen.DropperMixMigration.REVISION),
                "25 on 0.38.1's file (0.38.4's slots text is the same): the slots' comment, for the Long Drop");
        assertEquals(List.of(), ShippedComments.refresh(LayoutFixtures.v036(),
                com.dierks.homecraft.games.gen.DropperMixMigration.REVISION),
                "and nothing on 0.36's, whose slots comment is 21's to refresh");
        YamlConfiguration at23 = LayoutFixtures.v0381();
        List<String> mine = new ArrayList<>(at23.getComments("arcade.launcher"));
        mine.add("my launchers are by the lake");
        at23.setComments("arcade.launcher", mine);
        HomeCraftManagement.migrateConfig(at23, "world");
        assertEquals(mine, at23.getComments("arcade.launcher"), "24 keeps a launcher comment the owner edited");
        assertEquals(LayoutFixtures.bundled().getComments("arcade.prizes"), at23.getComments("arcade.prizes"),
                "while refreshing the counter's header");
        YamlConfiguration at22 = LayoutFixtures.v0380();
        List<String> counter = new ArrayList<>(at22.getComments("arcade.prizes"));
        counter.add("my rows are at the bottom");
        at22.setComments("arcade.prizes", counter);
        HomeCraftManagement.migrateConfig(at22, "world");
        assertEquals(counter, at22.getComments("arcade.prizes"), "23 keeps a header the owner edited");
        assertEquals(List.of("games.fresh", "games.fresh.world", "games.fresh.slots", "games.fresh.archive",
                "games.fresh.keep.area", "games.race_night", "games.race_night.races",
                "games.race_night.max_race_minutes"),
                ShippedComments.refresh(c, GamesAreaMigration.REVISION), "21: the areas' and the run's");
        assertEquals(List.of(), ShippedComments.refresh(c, GamesAreaMigration.REVISION), "and a second run has none left");
    }

    @Test
    void everyFingerprintIsWhatTheVersionsBeforeItsRevisionShippedAndNoneIsThisVersions() throws Exception {
        YamlConfiguration v035 = LayoutFixtures.v035();
        YamlConfiguration v036 = LayoutFixtures.v036();
        YamlConfiguration v0372 = LayoutFixtures.v0372();
        YamlConfiguration v0380 = LayoutFixtures.v0380();
        YamlConfiguration v0381 = LayoutFixtures.v0381();
        YamlConfiguration bundled = LayoutFixtures.bundled();
        Set<String> keys = new HashSet<>();
        for (ShippedComments.Stale s : ShippedComments.STALE) {
            assertTrue(keys.add(s.key() + "@" + s.revision()), s.key() + " once a revision");
            // a revision meets every file below it: 20 and 21 a 0.35.0 or 0.36.0 file, 22 a 0.37.2 one too, 23 0.38.0's,
            // 24 0.38.1's, 25 0.38.4's (whose slots comment is 0.38.1's). A text an earlier revision's entry on the same
            // key already refreshes is never this one's (22 refreshed the older headers before 23 met them)
            Set<String> earlier = new HashSet<>();
            for (ShippedComments.Stale e : ShippedComments.STALE) {
                if (e.key().equals(s.key()) && e.revision() < s.revision()) {
                    earlier.addAll(e.shipped());
                }
            }
            Set<String> shipped = new HashSet<>();
            for (YamlConfiguration old : List.of(v035, v036, v0372, v0380, v0381)) {
                if (old.getInt("config_revision", 0) >= s.revision()) {
                    continue; // a file this revision never meets
                }
                if (old.contains(s.key()) && !old.getComments(s.key()).equals(bundled.getComments(s.key()))
                        && !earlier.contains(fingerprint(old, s.key()))) {
                    shipped.add(fingerprint(old, s.key()));
                }
            }
            assertEquals(shipped, s.shipped(), s.key() + " (revision " + s.revision() + "): exactly the stale texts"
                    + " the versions before it shipped (where they had the key)");
            assertFalse(s.shipped().contains(fingerprint(bundled, s.key())), s.key() + ": never this version's");
            for (YamlConfiguration old : List.of(v036, v0372, v0380, v0381)) {
                YamlConfiguration saved = new YamlConfiguration();
                saved.loadFromString(old.saveToString());
                assertEquals(fingerprint(old, s.key()), fingerprint(saved, s.key()),
                        s.key() + ": a file the plugin saved before reads the same");
            }
        }
        assertNotEquals(ShippedComments.fingerprint(List.of("a"), List.of()),
                ShippedComments.fingerprint(List.of(), List.of("a")), "block and inline are told apart");
    }
}
