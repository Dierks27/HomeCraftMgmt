package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.TokenPrizes;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A placed Arcade Trophy opens the Arcade (SHOP-V2-SPEC §5.8, D17): "act like the arcade mini". The tap goes through
 * the machine's own lines in {@code CustomBlockListener.onInteract}, so every rule the machine has holds for the
 * trophy too (same world, economy, cancelled-tap and build-rights rules), plus three stricter ones: sneaking passes
 * the tap through (so she can build next to her trophy shelf), {@code hcm.arcade.use} is checked, and a Bedrock hat
 * tap on it never wears the hat ({@code PrizeItemListener.ours}).
 *
 * <p>Only the Arcade Trophy opens the Arcade: the ladder's trophies and the Monthly wear the machine's head too until
 * the owner pastes their pictures, and a tap on one does nothing. The trophy is never converted: it keeps its name and
 * number, and breaking it gives back "Arcade Trophy #N".
 *
 * <p>This runs on every right-click on a block that isn't one of ours, so the material is tested first, and a
 * trophy placed since 0.37.3 is told by the plain id on its skull ({@link Keys#PLACED_PRIZE_ID}). One placed before
 * holds only the base64 item: it is decoded once, and its id is written back so the next tap reads the string.
 */
public final class ArcadeTrophies {

    private ArcadeTrophies() {
    }

    /** The one row whose placed trophy opens the Arcade. */
    public static final String ID = "arcade_trophy";

    /** Whether a placed prize of this id and kind opens the Arcade: the Arcade Trophy, and nothing else. Pure. */
    public static boolean opensArcade(String prizeId, PrizeType kind) {
        return ID.equalsIgnoreCase(prizeId) && kind == PrizeType.TROPHY;
    }

    /** What a tap on a placed Arcade Trophy does. */
    public enum Tap {
        /** Sneaking: the tap is left alone, so she can build next to her trophy shelf. */
        PASS,
        /** No {@code hcm.arcade.use}: refused, as {@code /hcm arcade} is. */
        REFUSE,
        /** Through the machine's own lines: the cancelled-tap, economy and build-rights checks, then the Arcade. */
        OPEN
    }

    /** {@link Tap} for one tap. Pure. */
    public static Tap tap(boolean sneaking, boolean mayUseArcade) {
        if (sneaking) {
            return Tap.PASS;
        }
        return mayUseArcade ? Tap.OPEN : Tap.REFUSE;
    }

    /**
     * Whether {@code b} is a placed Arcade Trophy: a player head (floor or wall; any other block costs one compare)
     * whose skull names {@code arcade_trophy}, or, placed before 0.37.3, holds an Arcade Trophy (decoded once, its id
     * written back). A lookup that fails is "no".
     */
    public static boolean isPlacedArcadeTrophy(Block b) {
        if (b == null || Keys.PLACED_PRIZE_ID == null || Keys.PRIZE_ITEM == null) {
            return false;
        }
        try {
            return isArcadeTrophy(b.getType(), () -> tags(b), ArcadeTrophies::decode);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** What the tap reads off a placed head's skull, and the one thing it writes. */
    interface SkullTags {
        /** {@link Keys#PLACED_PRIZE_ID}, or null (placed before 0.37.3, or not a prize). */
        String placedId();

        /** {@link Keys#PRIZE_ITEM}: the base64 prize, or null (not a placed prize). */
        String storedItem();

        /** Write {@link Keys#PLACED_PRIZE_ID}, so the next tap reads the string. */
        void writePlacedId(String id);
    }

    /** A decoded placed prize: its id and kind (either may be null). */
    record Decoded(String prizeId, PrizeType kind) {
    }

    /**
     * {@link #isPlacedArcadeTrophy} on its parts, so a test drives it: the material first (the skull is never read for
     * any other block), then the plain id; with none, the stored item decoded once, its id written back.
     */
    static boolean isArcadeTrophy(Material type, Supplier<SkullTags> skull, Function<String, Decoded> decode) {
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
            return false;
        }
        SkullTags tags = skull.get();
        if (tags == null) {
            return false;
        }
        String id = tags.placedId();
        if (id != null) {
            return ID.equalsIgnoreCase(id);
        }
        String stored = tags.storedItem();
        if (stored == null) {
            return false; // a plain head, a Mini, a Display Case: not a placed prize
        }
        Decoded d = decode.apply(stored);
        if (d == null || d.prizeId() == null) {
            return false;
        }
        tags.writePlacedId(d.prizeId()); // decoded once: the next tap reads the plain id, whatever prize it is
        return opensArcade(d.prizeId(), d.kind());
    }

    private static SkullTags tags(Block b) {
        if (!(b.getState(false) instanceof Skull skull)) {
            return null;
        }
        var pdc = skull.getPersistentDataContainer();
        return new SkullTags() {
            @Override
            public String placedId() {
                return pdc.get(Keys.PLACED_PRIZE_ID, PersistentDataType.STRING);
            }

            @Override
            public String storedItem() {
                return pdc.get(Keys.PRIZE_ITEM, PersistentDataType.STRING);
            }

            @Override
            public void writePlacedId(String id) {
                if (b.getState() instanceof Skull copy) {
                    copy.getPersistentDataContainer().set(Keys.PLACED_PRIZE_ID, PersistentDataType.STRING, id);
                    copy.update(true, false);
                }
            }
        };
    }

    private static Decoded decode(String b64) {
        ItemStack item = Items.fromBase64(b64);
        return item == null ? null : new Decoded(TokenPrizes.prizeId(item), PrizeItems.kindOf(item));
    }
}
