package com.dierks.homecraft.trade;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where what a player gets lands (SHOP-V2-SPEC §4.6 step 2): partial stacks of the same item first, then empty
 * slots, each stack at most its own limit; what a player gives frees room for what they get; and what won't fit is
 * the overflow for the Mailbox.
 */
class TradeRoomTest {

    /** A stand-in stack: what it is, how many, and its own stack limit. */
    private record S(String kind, int amount, int max) {
    }

    private static final TradeRoom.Stacks<S> OPS = new TradeRoom.Stacks<>() {
        @Override
        public int amount(S item) {
            return item.amount();
        }

        @Override
        public boolean similar(S a, S b) {
            return a.kind().equals(b.kind());
        }

        @Override
        public int maxStack(S item) {
            return item.max();
        }
    };

    private static List<S> bag(S... filled) {
        List<S> out = new ArrayList<>(Arrays.asList(filled));
        while (out.size() < 36) {
            out.add(null);
        }
        return out;
    }

    private static List<S> full(String kind) {
        List<S> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            out.add(new S(kind + i, 1, 64));
        }
        return out;
    }

    @Test
    void aFullBagGivingOneStackAndGettingThreeSendsTwoToTheMailbox() {
        TradeRoom.Plan plan = TradeRoom.plan(full("dirt"), Map.of(4, 1),
                List.of(new S("diamond", 3, 64), new S("emerald", 5, 64), new S("gold", 7, 64)), OPS);
        assertArrayEquals(new int[]{3, 0, 0}, plan.fits(), "the freed slot takes the first");
        assertEquals(2, plan.overflowStacks(), "the other two go to the Mailbox");
        assertEquals(5, plan.overflow(1));
        assertEquals(7, plan.overflow(2));
    }

    @Test
    void partialStacksFillFirst() {
        List<S> storage = bag(new S("stone", 10, 64), null, new S("diamond", 60, 64), new S("diamond", 62, 64));
        TradeRoom.Plan plan = TradeRoom.plan(storage, Map.of(), List.of(new S("diamond", 10, 64)), OPS);
        assertTrue(plan.allFit());
        // 4 into slot 2, 2 into slot 3, the last 4 into the first empty slot (1)
        assertArrayEquals(new int[]{10}, plan.fits());
        List<S> full = full("x");
        full.set(7, new S("diamond", 60, 64));
        TradeRoom.Plan merge = TradeRoom.plan(full, Map.of(), List.of(new S("diamond", 10, 64)), OPS);
        assertEquals(4, merge.fits()[0], "a full bag still tops up a partial stack");
        assertEquals(6, merge.overflow(0));
    }

    @Test
    void givingAWholeSlotFreesItForWhatComesIn() {
        List<S> storage = full("dirt");
        TradeRoom.Plan none = TradeRoom.plan(storage, Map.of(), List.of(new S("diamond", 1, 64)), OPS);
        assertEquals(1, none.overflowStacks(), "nothing given, nowhere to put it");
        TradeRoom.Plan half = TradeRoom.plan(bag(new S("dirt", 64, 64)), Map.of(0, 32),
                List.of(new S("diamond", 1, 64)), OPS);
        assertTrue(half.allFit());
        List<S> one = full("dirt");
        one.set(0, new S("dirt", 64, 64));
        TradeRoom.Plan part = TradeRoom.plan(one, Map.of(0, 32), List.of(new S("diamond", 1, 64)), OPS);
        assertEquals(1, part.overflowStacks(), "giving half a stack frees no slot");
    }

    @Test
    void unstackablesTakeASlotEach() {
        List<S> storage = full("dirt");
        storage.set(0, null);
        storage.set(1, null);
        TradeRoom.Plan plan = TradeRoom.plan(storage, Map.of(),
                List.of(new S("sword", 1, 1), new S("sword", 1, 1), new S("sword", 1, 1)), OPS);
        assertArrayEquals(new int[]{1, 1, 0}, plan.fits(), "two empty slots, two swords");
        TradeRoom.Plan big = TradeRoom.plan(bag(), Map.of(), List.of(new S("potion", 3, 1)), OPS);
        assertEquals(3, big.fits()[0], "a stack over its limit is spread over three slots, as addItem does");
    }

    @Test
    void sixteenAndSixteen() {
        List<S> storage = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            storage.add(i < 16 ? new S("mine" + i, 1, 64) : i < 20 ? null : new S("kept" + i, 64, 64));
        }
        Map<Integer, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < 16; i++) {
            out.put(i, 1);
        }
        List<S> in = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            in.add(new S("theirs" + i, 1, 64));
        }
        TradeRoom.Plan plan = TradeRoom.plan(storage, out, in, OPS);
        assertTrue(plan.allFit(), "sixteen out frees sixteen slots for sixteen in");
        TradeRoom.Plan noRoom = TradeRoom.plan(storage, Map.of(), in, OPS);
        assertEquals(12, noRoom.overflowStacks(), "with nothing given only the four empty slots take any");
    }
}
