package com.dierks.homecraft.arcade;

import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * A prize head placed as a block (a hat or a trophy): what the skull remembers about it. It holds the exact item
 * ({@link Keys#PRIZE_ITEM}), so breaking it gives back the same prize and never a plain head that could be sold;
 * from 0.37.3 also who placed it ({@link Keys#TROPHY_PLACER}) and the plain prize id ({@link Keys#PLACED_PRIZE_ID}).
 *
 * <p>Every read looks at the material first, so the guards on hot paths (pistons, flowing water, explosions) cost a
 * material compare for any block that isn't a head. Every head counts, the mob heads too (0.38). A lookup that fails is "not a placed prize".
 */
public final class PlacedPrizes {

    private PlacedPrizes() {
    }

    /** The exact prize a placed head holds, or null for any other block (or one whose item can't be read). */
    public static ItemStack storedAt(Block b) {
        Skull skull = skull(b);
        if (skull == null) {
            return null;
        }
        String b64 = skull.getPersistentDataContainer().get(Keys.PRIZE_ITEM, PersistentDataType.STRING);
        return b64 == null ? null : Items.fromBase64(b64);
    }

    /**
     * Whether {@code b} is a placed prize head, without decoding the item it holds: what the piston, flow and
     * explosion guards ask about every block they see.
     */
    public static boolean isPlaced(Block b) {
        Skull skull = skull(b);
        return skull != null && skull.getPersistentDataContainer().has(Keys.PRIZE_ITEM, PersistentDataType.STRING);
    }

    /** Who placed the prize head at {@code b}, or null (not a placed prize, or placed before 0.37.3). */
    public static UUID placer(Block b) {
        Skull skull = skull(b);
        if (skull == null || Keys.TROPHY_PLACER == null) {
            return null;
        }
        String raw = skull.getPersistentDataContainer().get(Keys.TROPHY_PLACER, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The plain prize id of the head at {@code b}, or null (not a placed prize, or placed before 0.37.3). */
    public static String placedPrizeId(Block b) {
        Skull skull = skull(b);
        if (skull == null || Keys.PLACED_PRIZE_ID == null) {
            return null;
        }
        return skull.getPersistentDataContainer().get(Keys.PLACED_PRIZE_ID, PersistentDataType.STRING);
    }

    /**
     * Whether {@code m} is a head a prize can be placed as: a player head and every mob head and skull, floor or
     * wall (0.38). A placed prize of any head type gives its prize back; a creeper-head prize used to break into a
     * plain, sellable creeper head.
     */
    public static boolean headMaterial(Material m) {
        if (m == null) {
            return false;
        }
        return switch (m) {
            case PLAYER_HEAD, PLAYER_WALL_HEAD, CREEPER_HEAD, CREEPER_WALL_HEAD, DRAGON_HEAD, DRAGON_WALL_HEAD,
                 ZOMBIE_HEAD, ZOMBIE_WALL_HEAD, SKELETON_SKULL, SKELETON_WALL_SKULL, WITHER_SKELETON_SKULL,
                 WITHER_SKELETON_WALL_SKULL, PIGLIN_HEAD, PIGLIN_WALL_HEAD -> true;
            default -> false;
        };
    }

    /** The skull at {@code b} when it is a head ({@link #headMaterial}, floor or wall); null for anything else. */
    private static Skull skull(Block b) {
        if (b == null || Keys.PRIZE_ITEM == null) {
            return null;
        }
        try {
            if (!headMaterial(b.getType())) {
                return null;
            }
            return b.getState(false) instanceof Skull skull ? skull : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
