package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 27 (graves, 0.39.0): an owner's file gains the {@code graves:} section from the backfill, with its
 * comment, and the courier package's {@code keep_on_death} comment stops saying "the grave is the recovery path",
 * unless the owner edited it.
 */
class GravesMigrationTest {

    private static final String KEY = "courier.package.keep_on_death";

    @Test
    void anOwnersFileGainsTheGravesSectionAndTheNewCourierComment() {
        YamlConfiguration c = LayoutFixtures.v0381();
        assertFalse(c.contains("graves"), "fixture: no graves before 0.39.0");
        assertTrue(String.join(" ", c.getComments(KEY)).contains("the grave is the recovery path"), "fixture");
        HomeCraftManagement.migrateConfig(c, "world");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        YamlConfiguration after = LayoutFixtures.reread(c);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertEquals(27, after.getInt("config_revision"));
        for (String key : List.of("graves.enabled", "graves.xp_percent", "graves.unlock_after_minutes",
                "graves.expire_after_hours", "graves.sneak_opens_menu", "graves.disabled_worlds")) {
            assertEquals(bundled.get(key), after.get(key), key + " is the shipped value");
        }
        assertEquals(bundled.getComments("graves"), after.getComments("graves"), "the section with its comment");
        assertEquals(bundled.getComments(KEY), after.getComments(KEY), "the courier comment is this version's");
        assertFalse(String.join(" ", after.getComments(KEY)).contains("recovery path"));
    }

    @Test
    void aCommentTheOwnerEditedIsKept() {
        YamlConfiguration c = LayoutFixtures.v0381();
        List<String> mine = new ArrayList<>(c.getComments(KEY));
        mine.add("my kids get theirs back");
        c.setComments(KEY, mine);
        assertEquals(List.of(), GravesMigration.apply(c));
        assertEquals(mine, c.getComments(KEY));
    }

    @Test
    void aSecondRunChangesNothing() {
        YamlConfiguration c = LayoutFixtures.v0381();
        assertEquals(List.of(KEY), GravesMigration.apply(c));
        assertEquals(List.of(), GravesMigration.apply(c));
    }
}
