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
 * Config revision 28 (the Clubhouse's side rooms, 0.41.0): an owner's file gains {@code arcade_room} and
 * {@code trophy_room} from the backfill, with their comments and the shipped kid set, and {@code origin}'s comment
 * stops saying the box is 32 x 16 x 32, unless the owner edited it; the origin itself never moves.
 */
class ClubhouseRoomsMigrationTest {

    private static final String KEY = "games.clubhouse.origin";

    @Test
    void anOwnersFileGainsTheSideRoomsAndTheNewOriginComment() {
        YamlConfiguration c = LayoutFixtures.v0381();
        Object origin = c.get(KEY);
        assertFalse(c.contains("games.clubhouse.arcade_room"), "fixture: no side rooms before 0.41.0");
        assertTrue(String.join(" ", c.getComments(KEY)).contains("32 x 16 x 32"), "fixture");
        HomeCraftManagement.migrateConfig(c, "world");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        YamlConfiguration after = LayoutFixtures.reread(c);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertEquals(HomeCraftManagement.CONFIG_REVISION, after.getInt("config_revision"));
        for (String key : List.of("games.clubhouse.arcade_room.enabled", "games.clubhouse.arcade_room.games",
                "games.clubhouse.trophy_room.enabled")) {
            assertEquals(bundled.get(key), after.get(key), key + " is the shipped value");
            assertEquals(bundled.getComments(key), after.getComments(key), key + " with its comment");
        }
        assertEquals(List.of("whack_a_zombie", "simon_says", "mini_match", "tic_tac_toe"),
                after.getStringList("games.clubhouse.arcade_room.games"));
        assertEquals(bundled.getComments(KEY), after.getComments(KEY), "the origin's comment is this version's");
        assertTrue(String.join(" ", after.getComments(KEY)).contains("32 x 16 x 48"));
        assertEquals(String.valueOf(origin), String.valueOf(after.get(KEY)), "the origin stays where it is");
    }

    @Test
    void aCommentTheOwnerEditedIsKept() {
        YamlConfiguration c = LayoutFixtures.v0381();
        List<String> mine = new ArrayList<>(c.getComments(KEY));
        mine.add("ours sits by the lake");
        c.setComments(KEY, mine);
        assertEquals(List.of(), ClubhouseRoomsMigration.apply(c));
        assertEquals(mine, c.getComments(KEY));
    }

    @Test
    void aSecondRunChangesNothing() {
        YamlConfiguration c = LayoutFixtures.v0381();
        assertEquals(List.of(KEY), ClubhouseRoomsMigration.apply(c));
        assertEquals(List.of(), ClubhouseRoomsMigration.apply(c));
    }
}
