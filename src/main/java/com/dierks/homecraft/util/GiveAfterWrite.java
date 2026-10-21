package com.dierks.homecraft.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Write first, then give (0.37.3, spec D22, §4.15 (5)): the one order every hand-over out of storage follows, so a
 * failed write can never leave a second copy (the old order handed the item over and then deleted its row, and a
 * delete that failed kept the row too). Used by taking a Mini back out of a Display Case or a Vending Machine,
 * emptying a Pallet and collecting from the Mailbox.
 *
 * <ol>
 *   <li><b>Plan:</b> how much fits in the bag now; the rest goes (or stays) in the Mailbox.</li>
 *   <li><b>Write</b>, with nothing given yet: the row's delete (or its new status or remainder) and a Mailbox row
 *       for each stack that won't fit, in one transaction. A failed write gives nothing.</li>
 *   <li><b>Give</b> what fits. If the bag takes less than planned, that remainder is written back to the
 *       Mailbox.</li>
 * </ol>
 *
 * <p>Nothing goes on the ground: the Mailbox keeps a row until it is collected, survives a crash and never drops.
 * The plan and the order are pure ({@link #run}), so they are tested without a server; {@link #room} and
 * {@link #give} are the live bag's side.
 */
public final class GiveAfterWrite {

    private GiveAfterWrite() {
    }

    /** A hand-over's own write: see {@link Steps#write}. */
    @FunctionalInterface
    public interface Write {
        boolean write(List<Integer> toMailbox) throws SQLException;
    }

    /** One hand-over's own writes and its bag. */
    public interface Steps {
        /**
         * The write made before anything is given: the row's delete (or new status), plus a Mailbox row for each
         * amount in {@code toMailbox} (one per stack; empty when everything fits), in one transaction.
         *
         * @return false when there was nothing left to take (the row is already gone): nothing is given
         * @throws SQLException when it couldn't be saved: nothing is given, and nothing was changed
         */
        boolean write(List<Integer> toMailbox) throws SQLException;

        /** Hand {@code amount} to the bag; returns how many it wouldn't take. */
        int give(int amount);

        /** Write a remainder the bag wouldn't take after all back to the Mailbox (one amount per stack). */
        void writeBack(List<Integer> amounts) throws SQLException;

        /** {@link #writeBack} failed: {@code amount} was taken out and couldn't be put anywhere (log it). */
        void lost(int amount, SQLException e);

        /** {@link #write} failed: nothing was given and nothing changed (say why in the console). */
        default void notSaved(SQLException e) {
        }
    }

    /** How a hand-over ended. */
    public enum Status {
        /** Written, and handed over (to the bag, the Mailbox, or both). */
        DONE,
        /** The write failed: nothing was given and nothing changed. */
        NOT_SAVED,
        /** There was nothing left to take: nothing was given. */
        GONE
    }

    /**
     * @param given  how many went into the bag
     * @param mailed how many went to (or stayed in) the Mailbox
     * @param lost   how many were taken out and could be put nowhere (a second failed write; logged)
     */
    public record Outcome(Status status, int given, int mailed, int lost) {

        public boolean ok() {
            return status == Status.DONE;
        }
    }

    /**
     * Hand {@code amount} over in the one safe order.
     *
     * @param room     how many the bag can take right now ({@link #room})
     * @param maxStack the biggest stack of this item, for splitting what goes to the Mailbox
     */
    public static Outcome run(int amount, int room, int maxStack, Steps steps) {
        int give = toBag(amount, room);
        List<Integer> toMailbox = stacks(amount - give, maxStack);
        try {
            if (!steps.write(toMailbox)) {
                return new Outcome(Status.GONE, 0, 0, 0);
            }
        } catch (SQLException e) {
            steps.notSaved(e);
            return new Outcome(Status.NOT_SAVED, 0, 0, 0);
        }
        int mailed = amount - give;
        int refused = give > 0 ? Math.max(0, Math.min(give, steps.give(give))) : 0;
        int lost = 0;
        if (refused > 0) {
            try {
                steps.writeBack(stacks(refused, maxStack));
                mailed += refused;
            } catch (SQLException e) {
                steps.lost(refused, e);
                lost = refused;
            }
        }
        return new Outcome(Status.DONE, give - refused, mailed, lost);
    }

    /** How many of {@code amount} go into a bag with {@code room} for that many. */
    public static int toBag(int amount, int room) {
        return Math.max(0, Math.min(amount, room));
    }

    /** {@code amount} split into stacks of at most {@code maxStack} (one when {@code maxStack} is below 1). */
    public static List<Integer> stacks(int amount, int maxStack) {
        int max = Math.max(1, maxStack);
        List<Integer> out = new ArrayList<>();
        for (int left = amount; left > 0; left -= max) {
            out.add(Math.min(left, max));
        }
        return out;
    }

    /** The biggest stack of {@code item} that {@code inv} holds in one slot. */
    public static int maxStack(Inventory inv, ItemStack item) {
        return Math.max(1, Math.min(item.getMaxStackSize(), inv.getMaxStackSize()));
    }

    /**
     * How many of {@code item} the bag can take right now: an empty slot takes a full stack, a slot holding the same
     * item takes the rest of its stack. The slots {@code addItem} fills (a player's 36 bag slots, never the armour or
     * the off-hand).
     */
    public static int room(Inventory inv, ItemStack item) {
        int max = maxStack(inv, item);
        long room = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                room += max;
            } else if (s.isSimilar(item)) {
                room += Math.max(0, max - s.getAmount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /** Put {@code amount} of {@code template} into the bag, a stack at a time; returns how many it wouldn't take. */
    public static int give(Inventory inv, ItemStack template, int amount) {
        int refused = 0;
        for (int n : stacks(amount, maxStack(inv, template))) {
            ItemStack stack = template.clone();
            stack.setAmount(n);
            for (ItemStack left : inv.addItem(stack).values()) {
                refused += left.getAmount();
            }
        }
        return refused;
    }

    /** {@code template} as a stack of {@code amount}. */
    public static ItemStack withAmount(ItemStack template, int amount) {
        ItemStack stack = template.clone();
        stack.setAmount(amount);
        return stack;
    }
}
