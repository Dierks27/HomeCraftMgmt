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
 * The comments config revisions 20 and 21 make stale in the owner's file ({@link ShippedComments}, the v4
 * audit ECON02/ECON05), against verbatim copies of 0.35.0's and 0.36.0's bundled config.yml.
 *
 * <p>Pinned here: the owner's 0.36 file, migrated and backfilled, ends with exactly the bundled file's comments
 * on every key, so it no longer says "x 1760-8191 ... Keep the sky there free", "a Mountain Run about 50 KB",
 * "skill games pay small, capped rewards" or "a short downhill sprint"; a 0.35 file's are refreshed too; a
 * comment the owner edited by one character is kept, word for word; each revision refreshes only its own;
 * and every fingerprint is what 0.35.0 or 0.36.0 shipped on that key.
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
                "pay small, capped rewards", "short downhill sprint", "a downhill race with 4-6")) {
            assertFalse(text.contains(stale), "no \"" + stale + "\" is left in the migrated file");
        }
        assertTrue(text.contains("x 1760-9599, z 2880-10367, y 96 and up"), "the new don't-build-here bounds");
        assertTrue(text.contains("/hcm games gen tidy"), "and the slots' tidy note");
        assertTrue(text.contains("about 0.4 MB"), "and the archive's new size");
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

        YamlConfiguration c = LayoutFixtures.v036();
        assertEquals(List.of("games"), ShippedComments.refresh(c, EconomyMigration.REVISION), "20: the header");
        assertEquals(List.of("games.fresh.world", "games.fresh.slots", "games.fresh.archive", "games.race_night",
                "games.race_night.races", "games.race_night.max_race_minutes"),
                ShippedComments.refresh(c, GamesAreaMigration.REVISION), "21: the areas' and the run's");
        assertEquals(List.of(), ShippedComments.refresh(c, GamesAreaMigration.REVISION), "and a second run has none left");
    }

    @Test
    void everyFingerprintIsWhat035Or036ShippedAndNoneIsThisVersions() throws Exception {
        YamlConfiguration v035 = LayoutFixtures.v035();
        YamlConfiguration v036 = LayoutFixtures.v036();
        YamlConfiguration bundled = LayoutFixtures.bundled();
        Set<String> keys = new HashSet<>();
        for (ShippedComments.Stale s : ShippedComments.STALE) {
            assertTrue(keys.add(s.key()), s.key() + " once");
            Set<String> shipped = new HashSet<>();
            for (YamlConfiguration old : List.of(v035, v036)) {
                if (!old.getComments(s.key()).equals(bundled.getComments(s.key()))) {
                    shipped.add(fingerprint(old, s.key()));
                }
            }
            assertEquals(shipped, s.shipped(), s.key() + ": exactly the stale texts 0.35.0 and 0.36.0 shipped");
            assertFalse(s.shipped().contains(fingerprint(bundled, s.key())), s.key() + ": never this version's");
            YamlConfiguration saved = new YamlConfiguration();
            saved.loadFromString(v036.saveToString());
            assertEquals(fingerprint(v036, s.key()), fingerprint(saved, s.key()),
                    s.key() + ": a file the plugin saved before reads the same");
        }
        assertNotEquals(ShippedComments.fingerprint(List.of("a"), List.of()),
                ShippedComments.fingerprint(List.of(), List.of("a")), "block and inline are told apart");
    }
}
