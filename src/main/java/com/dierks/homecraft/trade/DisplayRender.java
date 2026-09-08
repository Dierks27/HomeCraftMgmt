package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.BlockSkins;
import org.bukkit.Location;

/**
 * Sync a Display Case after its listing changed. The case block keeps its pedestal
 * skin (its variant's {@code skins.display_case} texture) whether empty or loaded;
 * the loaded Mini is shown once, as a floating ItemDisplay above the case, by
 * {@link com.dierks.homecraft.effects.MiniEffectsService}. Safe to call on any block.
 */
public final class DisplayRender {

    private DisplayRender() {
    }

    public static void apply(HomeCraftManagement plugin, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        BlockSkins.apply(loc.getBlock(),
                plugin.config().displayCaseSkin(plugin.blockService().displayVariantAt(loc.getBlock())));
        if (plugin.effects() != null) {
            plugin.effects().refreshBlock(loc, true);
        }
    }
}
