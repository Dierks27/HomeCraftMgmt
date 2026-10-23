package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeRules.Claim;
import com.dierks.homecraft.trade.TradeRules.Context;
import com.dierks.homecraft.trade.TradeRules.Refuse;
import com.dierks.homecraft.trade.TradeRules.SideNow;
import com.dierks.homecraft.trade.TradeRules.Where;
import com.dierks.homecraft.trade.TradeSession.Side;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Bound keys never go through the trade window (SHOP-V2-SPEC §4.9 rows 2-3, D9, D29): a rented hat, a Keeper, and
 * a shulker box holding either are refused when they are put on the table, and again on the real stack at the swap
 * (a stack that became one after it went on the table): the swap then moves nothing.
 */
class TradeBoundTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Where OK = new Where(true, false, true, true, GameMode.SURVIVAL);

    /** {@code PrizeService.bound}: an {@code hcm:bound_to} key (a rental), or a {@code keeper_} prize id. */
    private static ItemFacts rentedCap() {
        return new ItemFacts("Pink Cap", "LEATHER_HELMET", 1, false, "cap_pink", true, true, false, false, false,
                false, Set.of("hcm:bound_to", "hcm:token_prize"), null, null, false, List.of());
    }

    private static ItemFacts keeper() {
        return new ItemFacts("Legend Crown", "LEATHER_HELMET", 1, false, "keeper_legend_crown", true, true, false,
                false, false, false, Set.of("hcm:token_prize"), null, null, false, List.of());
    }

    private static ItemFacts shulker(ItemFacts inside) {
        return ItemFacts.plain("Shulker box", "SHULKER_BOX", 1).holding(List.of(ItemFacts.plain("Dirt", "DIRT", 64),
                inside));
    }

    private static final Context CTX = new Context(Refuse.NONE, "Helper", id -> Claim.NONE);

    @Test
    void refusedAtAdd() {
        assertEquals(TradeRules.RENTED, TradeRules.refusal(rentedCap(), CTX).message());
        assertEquals("&7Legend Crown is yours for good - it can't be traded.",
                TradeRules.refusal(keeper(), CTX).message());
        assertEquals(TradeRules.RENTED, TradeRules.refusal(shulker(rentedCap()), CTX).message(), "[X] boxed up too");
        assertEquals(2, TradeRules.refusal(shulker(keeper()), CTX).row(), "[X] a Keeper in a shulker");
        assertNull(TradeRules.refusal(shulker(ItemFacts.plain("Diamond", "DIAMOND", 3)), CTX));
    }

    /** A stand-in stack: its kind and its facts (which the swap reads again on the real stack). */
    private record S(String kind, int amount, ItemFacts facts) {
    }

    private static final TradeSwap.Items<S> ITEMS = new TradeSwap.Items<>() {
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
            return 64;
        }

        @Override
        public S withAmount(S item, int amount) {
            return new S(item.kind(), amount, item.facts());
        }

        @Override
        public String encode(S item) {
            return item.kind();
        }

        @Override
        public ItemFacts facts(S item) {
            return item.facts();
        }
    };

    @Test
    void refusedAgainOnTheRealStackAtTheSwapAndNothingMoves() {
        for (ItemFacts bound : List.of(rentedCap(), keeper(), shulker(rentedCap()), shulker(keeper()))) {
            S[] bagA = new S[36];
            S[] bagB = new S[36];
            // on the table it was a plain shulker; by the swap the real one holds a bound key
            S plain = new S("box", 1, ItemFacts.plain("Shulker box", "SHULKER_BOX", 1));
            S real = new S("box", 1, bound);
            bagA[0] = real;
            bagB[3] = new S("emerald", 5, ItemFacts.plain("Emerald", "EMERALD", 5));
            TradeSession<S> s = new TradeSession<>(OWNER, HELPER, 0, 1);
            s.put(Side.A, 0, plain, 1, 0);
            s.put(Side.B, 3, bagB[3], 5, 0);
            s.tapReady(Side.A, 10, false, true);
            s.tapReady(Side.B, 10, false, true);
            TradeSwap.Bags<S> bags = new TradeSwap.Bags<>() {
                @Override
                public S get(Side side, int slot) {
                    return (side == Side.A ? bagA : bagB)[slot];
                }

                @Override
                public void set(Side side, int slot, S item) {
                    (side == Side.A ? bagA : bagB)[slot] = item;
                }

                @Override
                public List<S> storage(Side side) {
                    return new ArrayList<>(Arrays.asList(side == Side.A ? bagA : bagB));
                }

                @Override
                public int add(Side side, S item) {
                    throw new AssertionError("nothing may be given");
                }

                @Override
                public void save(Side side) {
                }

                @Override
                public void mark(Side side, long id, long at) {
                    throw new AssertionError("nothing may be marked");
                }
            };
            TradeSwap.Verifier verify = () -> TradeRules.verify(
                    new SideNow(OWNER, "Owner", true, true, "world", OK, TradeSwap.held(s.offer(Side.A), i -> bagA[i],
                            ITEMS), 0, true, id -> Claim.NONE),
                    new SideNow(HELPER, "Helper", true, true, "world", OK, TradeSwap.held(s.offer(Side.B),
                            i -> bagB[i], ITEMS), 0, true, id -> Claim.NONE),
                    true, Refuse.NONE, Set.of());
            TradeSwap.Result<S> r = new TradeSwap<>(bags, ITEMS, null, null, null).run(s,
                    new TradeSwap.Party(OWNER, "Owner"), new TradeSwap.Party(HELPER, "Helper"), "world", 0, 2_000,
                    verify, id -> Claim.NONE, id -> Claim.NONE);
            assertEquals(TradeSwap.Status.FAILED, r.status(), bound.name());
            assertSame(real, bagA[0], "[X] the bound key never left the bag");
            assertEquals("emerald", bagB[3].kind());
            assertEquals(Map.of(Side.A, r.told().get(Side.A), Side.B, r.told().get(Side.B)), r.told());
        }
    }
}
