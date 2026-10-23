package com.dierks.homecraft.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where what a player gets will land (SHOP-V2-SPEC §4.6 step 2), pure: a copy of their bag, with what they give
 * taken out, gets what they receive the way {@code addItem} adds it (into a partial stack of the same item first,
 * then the first empty slot, a stack at a time, each stack at most its own and the bag's limit). What doesn't fit
 * is the planned overflow, for the Mailbox. The plan is only a plan: the swap hands over the real stacks and sends
 * anything {@code addItem} still won't take to the Mailbox too ({@link TradeSwap}).
 */
public final class TradeRoom {

    private TradeRoom() {
    }

    /** What the plan asks of an item. */
    public interface Stacks<I> {
        int amount(I item);

        /** The same item, amount aside ({@code isSimilar}). */
        boolean similar(I a, I b);

        /** The biggest stack of it one slot holds: its own limit ({@code max_stack_size}) and the bag's. */
        int maxStack(I item);
    }

    /**
     * The plan for one receiver.
     *
     * @param fits per incoming stack, how many of it go into the bag (the rest is overflow)
     */
    public record Plan(int[] fits, int[] amounts) {

        /** How many of incoming stack {@code i} go to the Mailbox. */
        public int overflow(int i) {
            return amounts[i] - fits[i];
        }

        /** How many incoming stacks don't fit whole (each overflow is one Mailbox row, or more for a big one). */
        public int overflowStacks() {
            int n = 0;
            for (int i = 0; i < fits.length; i++) {
                if (overflow(i) > 0) {
                    n++;
                }
            }
            return n;
        }

        public boolean allFit() {
            return overflowStacks() == 0;
        }
    }

    /**
     * Plan one receiver's bag.
     *
     * @param storage  the bag's storage slots as they are (null or a zero amount = empty)
     * @param takeOut  bag slot → how many the receiver gives away from it
     * @param incoming what they get, in order (each with its amount)
     */
    public static <I> Plan plan(List<I> storage, Map<Integer, Integer> takeOut, List<I> incoming, Stacks<I> ops) {
        int n = storage.size();
        List<I> kind = new ArrayList<>(n);
        int[] amount = new int[n];
        for (int i = 0; i < n; i++) {
            I s = storage.get(i);
            int amt = s == null ? 0 : ops.amount(s);
            kind.add(amt > 0 ? s : null);
            amount[i] = Math.max(0, amt);
        }
        for (Map.Entry<Integer, Integer> e : takeOut.entrySet()) {
            int slot = e.getKey();
            if (slot >= 0 && slot < n && kind.get(slot) != null) {
                amount[slot] = Math.max(0, amount[slot] - e.getValue());
                if (amount[slot] == 0) {
                    kind.set(slot, null);
                }
            }
        }
        int[] fits = new int[incoming.size()];
        int[] amounts = new int[incoming.size()];
        for (int k = 0; k < incoming.size(); k++) {
            I item = incoming.get(k);
            int left = Math.max(0, ops.amount(item));
            amounts[k] = left;
            int max = Math.max(1, ops.maxStack(item));
            while (left > 0) {
                int slot = partial(kind, amount, item, max, ops);
                if (slot >= 0) {
                    int add = Math.min(left, max - amount[slot]);
                    amount[slot] += add;
                    left -= add;
                    continue;
                }
                slot = kind.indexOf(null);
                if (slot < 0) {
                    break;
                }
                int add = Math.min(left, max);
                kind.set(slot, item);
                amount[slot] = add;
                left -= add;
            }
            fits[k] = amounts[k] - left;
        }
        return new Plan(fits, amounts);
    }

    /** The first slot holding the same item with room left, or -1 ({@code firstPartial}). */
    private static <I> int partial(List<I> kind, int[] amount, I item, int max, Stacks<I> ops) {
        for (int i = 0; i < kind.size(); i++) {
            I k = kind.get(i);
            if (k != null && amount[i] < max && ops.similar(k, item)) {
                return i;
            }
        }
        return -1;
    }
}
