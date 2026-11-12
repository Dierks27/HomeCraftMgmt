package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Config revision 27: graves (0.39.0, HCM-GRAVES §9).
 *
 * <p>The new {@code graves:} section needs no step: the backfill writes it, every key with its comment, the way it
 * writes every new section. What a revision is for here is the one shipped comment graves make wrong:
 * {@code courier.package.keep_on_death} said "the grave is the recovery path", and a grave never takes a courier
 * package (it stays in the drops, as the packages' own setting decides). That comment is refreshed from the bundled
 * file, only while it is still exactly the text every version from 0.35.0 to 0.38.8 shipped ({@link ShippedComments});
 * a comment the owner edited is kept word for word. Quiet: no value moves. A second run changes nothing.
 */
final class GravesMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 27. */
    static final int REVISION = 27;

    private GravesMigration() {
    }

    /** Revision 27 on {@code c} (defaults-free); returns the comments refreshed. */
    static List<String> apply(FileConfiguration c) {
        return ShippedComments.refresh(c, REVISION);
    }
}
