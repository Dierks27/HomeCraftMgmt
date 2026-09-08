package com.dierks.homecraft.block;

import com.dierks.homecraft.util.Heads;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Re-skin an already-placed head block with a configured texture value. Uses the
 * plugin's one head-building path ({@link Heads#textured}) to produce the profile
 * and copies it onto the block — the same mechanism the Display Case trophy render
 * uses. No-ops safely on anything that isn't a skull; a blank texture clears the
 * profile (plain head).
 */
public final class BlockSkins {

    private BlockSkins() {
    }

    /** Apply {@code texture} (Base64 value; blank = plain head) to the block if it is a skull. */
    public static void apply(Block block, String texture) {
        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) {
            return;
        }
        PlayerProfile profile = null;
        if (texture != null && !texture.isBlank()
                && Heads.textured(texture, null, null).getItemMeta() instanceof SkullMeta meta) {
            profile = meta.getPlayerProfile();
        }
        try {
            skull.setPlayerProfile(profile);
            skull.update(true, false);
        } catch (Throwable ignored) {
            // A bad/absent profile shouldn't break the block.
        }
    }
}
