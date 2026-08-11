package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.util.Items;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Optional;

/**
 * Renders a Display Case's trophy: when the base block is a player-head, copy the
 * loaded Mini's texture onto the block so it literally shows the Mini; clear it
 * when empty. Safe to call on any block (no-ops if it isn't a skull).
 */
public final class DisplayRender {

    private DisplayRender() {
    }

    public static void apply(HomeCraftManagement plugin, Location loc) {
        Block block = loc.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) {
            return;
        }
        Optional<MiniListingDao.Listing> opt = plugin.vending().at(loc);
        PlayerProfile profile = null;
        if (opt.isPresent()) {
            ItemStack item = Items.fromBase64(opt.get().itemB64());
            if (item != null && item.getItemMeta() instanceof SkullMeta meta) {
                profile = meta.getPlayerProfile();
            }
        }
        try {
            skull.setPlayerProfile(profile);
            skull.update(true, false);
        } catch (Throwable ignored) {
            // A bad/absent profile shouldn't break the block.
        }
    }
}
