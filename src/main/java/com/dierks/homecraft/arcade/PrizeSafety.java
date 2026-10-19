package com.dierks.homecraft.arcade;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Which prizes are kept safe, as plain rules over what an item is (HALLOWEEN-SPEC D9). The listener
 * that applies them, {@link PrizeSafetyListener}, reads the facts off the item; this decides.
 *
 * <ul>
 *   <li><b>Kept on death:</b> a prize ({@code hcm:token_prize}) that holds nothing but candy. A prize
 *       bundle holding anything else drops like any other item, or protecting it would make a
 *       keep-inventory, fireproof bag for whatever was put in it.</li>
 *   <li><b>Safe on the ground</b> (never despawns, never burns, no mob takes it): the same, except
 *       candy itself, which is meant to go.</li>
 *   <li><b>Owner-bound when dropped:</b> a collector (the Trick-or-Treat Bag included). With one of
 *       each for good, a lost one could never be bought again, so only the one who dropped it can
 *       pick it back up, and no hopper takes it. Other prizes are handed over by dropping them, so they
 *       are left alone.</li>
 * </ul>
 */
public final class PrizeSafety {

    private PrizeSafety() {
    }

    /** The contents rule: true when everything inside is candy. An empty bag holds nothing but candy. */
    static boolean holdsOnlyCandy(List<Boolean> contentsAreCandy) {
        if (contentsAreCandy == null) {
            return true;
        }
        for (Boolean candy : contentsAreCandy) {
            if (!Boolean.TRUE.equals(candy)) {
                return false;
            }
        }
        return true;
    }

    /** Kept on death: a prize that holds nothing but candy. */
    static boolean keepOnDeath(boolean prize, List<Boolean> contentsAreCandy) {
        return prize && holdsOnlyCandy(contentsAreCandy);
    }

    /** Safe on the ground: kept on death, and not candy (shower candy is meant to go). */
    static boolean keepOnGround(boolean prize, boolean candy, List<Boolean> contentsAreCandy) {
        return keepOnDeath(prize, contentsAreCandy) && !candy;
    }

    /** True for a prize id whose drop only the one who dropped it may pick up: a collector, or the bag. */
    static boolean ownerBound(String prizeId) {
        if (prizeId == null) {
            return false;
        }
        return Collectors.isCollectorId(prizeId)
                || prizeId.trim().toLowerCase(Locale.ROOT).startsWith("bag_trick_or_treat_");
    }

    /**
     * The prize-bundle insert guard (D15, E15): a prize bundle (the Trick-or-Treat Bag, or any bundle that is a
     * prize) takes only candy. So she can put her candy apples in her bag, and nothing else goes in. A plain
     * bundle is never refused. Even with a way round this, {@link #keepOnDeath} is false for a bundle holding
     * anything but candy, so nothing gains keep-inventory through it.
     */
    static boolean refusesInsert(boolean bundleIsPrize, boolean insertedIsCandy) {
        return bundleIsPrize && !insertedIsCandy;
    }

    /**
     * The same guard in a Creative player's own inventory, where the client sends each slot's new stack rather
     * than a click (and Geyser sends a Bedrock player's Creative moves the same way): a candy put in the bag
     * arrives as the bag set again with the candy inside. So a prize bundle being set is refused only when it
     * holds anything but candy, and anything else being set is a move, not an insert.
     */
    static boolean refusesCreativeSet(boolean setIsPrizeBundle, boolean holdsOnlyCandy) {
        return setIsPrizeBundle && !holdsOnlyCandy;
    }

    /**
     * A hopper (or a hopper minecart) leaves a dropped collector where it is. The game checks the
     * owner a drop was given only when a player walks over it, so without this a hopper would take it
     * and anyone could then open the hopper or the chest below it. A collector nobody owns on the
     * ground (one spilled from a broken chest, say) is left to vanilla: anyone could pick it up anyway.
     */
    static boolean hopperRefuses(String prizeId, boolean hasOwner) {
        return hasOwner && ownerBound(prizeId);
    }

    /**
     * Move every element of {@code from} that matches into {@code to}, in order, and return how many
     * moved. A moved element is no longer in {@code from}, so a second call moves nothing: an item is
     * never both dropped (where a grave plugin would copy it) and kept.
     */
    static <T> int moveIf(List<T> from, List<T> to, Predicate<? super T> matches) {
        int moved = 0;
        for (Iterator<T> it = from.iterator(); it.hasNext(); ) {
            T t = it.next();
            if (matches.test(t)) {
                it.remove();
                to.add(t);
                moved++;
            }
        }
        return moved;
    }

    /**
     * The death guard: take out of {@code drops} each guarded element that is also in {@code kept}
     * (equal, one drop for each kept copy), and return how many went. Such a stack would be kept AND
     * dropped, where a grave plugin would copy it: another plugin put it back after it was moved.
     */
    static <T> int dropKeptTwice(List<T> drops, List<T> kept, Predicate<? super T> guarded) {
        List<T> unmatched = new ArrayList<>(kept);
        int removed = 0;
        for (Iterator<T> it = drops.iterator(); it.hasNext(); ) {
            T t = it.next();
            if (guarded.test(t) && unmatched.remove(t)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
