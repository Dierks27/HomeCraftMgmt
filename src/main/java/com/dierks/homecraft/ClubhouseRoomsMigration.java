package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Config revision 28: the Clubhouse's side rooms (0.41.0, the Arcade Room and the Trophy Room).
 *
 * <p>The new keys ({@code games.clubhouse.arcade_room.enabled}, {@code .games} and
 * {@code games.clubhouse.trophy_room.enabled}) need no step: the backfill writes them, each with its comment, as it
 * writes every new key. What a revision is for is the one shipped comment the rooms make wrong:
 * {@code games.clubhouse.origin} said the box "is 32 x 16 x 32", and it is 32 x 16 x 48 now. That comment is refreshed
 * from the bundled file only while it is still exactly the text 0.35.0 to 0.40.0 shipped ({@link ShippedComments}); a
 * comment the owner edited is kept word for word. Quiet: no value moves (the origin stays where it is: the main room
 * keeps its corner, the side rooms go south of it). A second run changes nothing.
 */
final class ClubhouseRoomsMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 28. */
    static final int REVISION = 28;

    private ClubhouseRoomsMigration() {
    }

    /** Revision 28 on {@code c} (defaults-free); returns the comments refreshed. */
    static List<String> apply(FileConfiguration c) {
        return ShippedComments.refresh(c, REVISION);
    }
}
