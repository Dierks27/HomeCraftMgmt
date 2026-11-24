package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.chance.holdem.PokerCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: how the NPC players play Texas Hold'em at the friendly table, level by level: easy plays along gently (no
 * sense of the price, hardly a raise, never a bluff), medium and hard estimate their chances by pretend run-outs and
 * weigh them against the pot odds (hard more carefully, and it bluffs more), and the personality only colours it.
 * Every decision is one a real player could make: a raise is always between the least and everything.
 */
class CpuHoldemTest {

    private static List<PokerCard> c(String text) {
        return text.isBlank() ? List.of() : PokerCard.parse(text);
    }

    /** A view: our cards, the board, one opponent, the pot, what it costs to call, our stack. */
    private static CpuHoldem.View view(String hole, String board, int opponents, int pot, int toCall, int stack) {
        int bet = toCall;
        int min = Math.max(2, bet * 2);
        return new CpuHoldem.View(c(hole), c(board), opponents, pot, toCall, stack, stack > toCall,
                Math.min(min, stack), stack, bet, 2);
    }

    private static int count(CpuHoldem.View v, CpuLevel level, CpuPersonality p, CpuHoldem.Move move, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int n = 0;
        for (int i = 0; i < 300; i++) {
            CpuHoldem.Decision d = CpuHoldem.decide(v, level, p, r);
            n += d.move() == move ? 1 : 0;
            if (d.move() == CpuHoldem.Move.RAISE) {
                assertTrue(d.to() >= v.minRaiseTo() && d.to() <= v.maxRaiseTo(), "a raise it may make: " + d);
            }
        }
        return n;
    }

    @Test
    void theEstimateKnowsAGoodHandFromABadOne() {
        SplittableRandom r = new SplittableRandom(1);
        double aces = CpuHoldem.equity(c("As Ad"), List.of(), 1, 4000, r);
        double junk = CpuHoldem.equity(c("7c 2d"), List.of(), 1, 4000, r);
        assertTrue(aces > 0.80 && aces < 0.90, "Aces against one hand win about 85 in 100: " + aces);
        assertTrue(junk > 0.28 && junk < 0.40, "7-2 against one hand about 35: " + junk);
        double crowd = CpuHoldem.equity(c("As Ad"), List.of(), 5, 3000, r);
        assertTrue(crowd < aces - 0.2, "more players in, less chance: " + crowd);
        double made = CpuHoldem.equity(c("9h 8h"), c("7h 6h 5h"), 1, 1000, r);
        assertTrue(made > 0.95, "a made straight flush: " + made);
        assertEquals(0.99, CpuHoldem.madeStrength(c("9h 8h"), c("7h 6h 5h")), 1e-9);
        assertTrue(CpuHoldem.madeStrength(c("As Ad"), List.of()) > CpuHoldem.madeStrength(c("7c 2d"), List.of()));
    }

    @Test
    void easyNeverBluffsAndHardSometimesDoes() {
        // nothing at all on the river, nobody has put anything in: a check, or a bluff
        CpuHoldem.View nothing = view("7c 2d", "Ks Qh 9d 4c 3s", 1, 20, 0, 100);
        assertEquals(0, count(nothing, CpuLevel.EASY, CpuPersonality.CHATTY, CpuHoldem.Move.RAISE, 1),
                "easy never bluffs");
        assertTrue(count(nothing, CpuLevel.HARD, CpuPersonality.CHATTY, CpuHoldem.Move.RAISE, 2) > 10,
                "hard bluffs now and then");
        assertTrue(count(nothing, CpuLevel.HARD, CpuPersonality.BOLD, CpuHoldem.Move.RAISE, 3)
                > count(nothing, CpuLevel.HARD, CpuPersonality.CAREFUL, CpuHoldem.Move.RAISE, 3),
                "a bold one bluffs more than a careful one");
    }

    @Test
    void hardWeighsThePriceAndEasyDoesnt() {
        // a weak pair on the river: a small bet to call is worth it, a big one isn't
        CpuHoldem.View cheap = view("8c 7c", "Ks Qh 7d 4c 2s", 1, 100, 10, 300);
        CpuHoldem.View dear = view("8c 7c", "Ks Qh 7d 4c 2s", 1, 100, 250, 300);
        int hardCheap = count(cheap, CpuLevel.HARD, CpuPersonality.CHATTY, CpuHoldem.Move.FOLD, 4);
        int hardDear = count(dear, CpuLevel.HARD, CpuPersonality.CHATTY, CpuHoldem.Move.FOLD, 4);
        assertTrue(hardCheap < 30, "hard calls a cheap bet with second pair: folded " + hardCheap);
        assertTrue(hardDear > 200, "and folds it to a big one: folded " + hardDear);
        // easy: a pair is a pair, at any price that isn't most of its chips
        CpuHoldem.View easyDear = view("8c 7c", "Ks Qh 7d 4c 2s", 1, 100, 100, 300);
        assertEquals(0, count(easyDear, CpuLevel.EASY, CpuPersonality.CHATTY, CpuHoldem.Move.FOLD, 5),
                "easy plays along with its pair, whatever the pot odds say");
        assertEquals(300, count(dear, CpuLevel.EASY, CpuPersonality.CHATTY, CpuHoldem.Move.FOLD, 5),
                "but a push for most of its chips moves it off a pair: gentle, never stubborn");
    }

    @Test
    void easyHardlyRaisesAndNeverPushesWithoutAMonster() {
        CpuHoldem.View strong = view("As Ad", "Ah 7d 2c", 1, 20, 0, 100); // three Aces
        int easyAllIn = count(strong, CpuLevel.EASY, CpuPersonality.BOLD, CpuHoldem.Move.ALL_IN, 6);
        assertEquals(0, easyAllIn, "easy doesn't shove three of a kind");
        int easyRaise = count(strong, CpuLevel.EASY, CpuPersonality.CHATTY, CpuHoldem.Move.RAISE, 6);
        int hardRaise = count(strong, CpuLevel.HARD, CpuPersonality.CHATTY, CpuHoldem.Move.RAISE, 6)
                + count(strong, CpuLevel.HARD, CpuPersonality.CHATTY, CpuHoldem.Move.ALL_IN, 6);
        assertTrue(easyRaise == 0, "easy only raises a near-certain hand: " + easyRaise);
        assertTrue(hardRaise > 150, "hard raises its strong hands: " + hardRaise);
    }

    @Test
    void aCarefulOneFoldsMoreThanABoldOne() {
        CpuHoldem.View marginal = view("Jc 8d", "", 2, 6, 4, 200); // before the flop, facing a raise
        int careful = count(marginal, CpuLevel.MEDIUM, CpuPersonality.CAREFUL, CpuHoldem.Move.FOLD, 7);
        int bold = count(marginal, CpuLevel.MEDIUM, CpuPersonality.BOLD, CpuHoldem.Move.FOLD, 7);
        assertTrue(careful >= bold, "careful folds at least as often: " + careful + " vs " + bold);
    }

    @Test
    void theLevelsAreTheirOwnStyles() {
        assertEquals(0, CpuHoldem.style(CpuLevel.EASY).sims(), "easy only looks at what it has made");
        assertTrue(CpuHoldem.style(CpuLevel.HARD).sims() > CpuHoldem.style(CpuLevel.MEDIUM).sims());
        assertTrue(CpuHoldem.style(CpuLevel.HARD).bluff() > CpuHoldem.style(CpuLevel.MEDIUM).bluff());
        assertEquals(0.0, CpuHoldem.style(CpuLevel.EASY).bluff());
        assertEquals(0.0, CpuHoldem.style(CpuLevel.EASY).oddsSense(), "no sense of the price");
        assertEquals(1.0, CpuHoldem.style(CpuLevel.HARD).oddsSense());
    }
}
