package com.dierks.homecraft.games.chance.bingo;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bingo Night's cards, calls and prizes (0.57.0), pure.
 *
 * <p><b>The expected-value proof</b> ({@link #everyPlaceGetsExactlyItsShareOverEveryWayTheCardsCanBeHeld}): for a
 * round's cards and its calls held fixed, the cards are handed to the places in play in every one of the {@code N!}
 * ways and the shuffle that settles leftover units is taken in every one of its {@code N!} orders, and what each place
 * is given is added up over all of them, in whole numbers: every place's total is exactly {@code N! (N - 1)! x prize},
 * an average of exactly {@code prize / N}. Every card is dealt by one rule from its own seed (so every way of handing
 * them out is equally likely) and the calls and the shuffle are drawn apart from the cards, so this holds for every
 * deal and every call order: a card's expected share of a round of {@code N} cards is exactly {@code pool / N}, inside
 * the band because the pool is ({@link #thePoolIsInsideTheBandForEveryPriceAndCardCount}). Whose the other cards are
 * (a player's, an NPC's, the room's) never comes into it.
 */
class BingoMathTest {

    // ---- the cards --------------------------------------------------------------------------------------------

    @Test
    void everyCardIsAClassicSeventyFiveBallCard() {
        for (long seed = 0; seed < 3_000; seed++) {
            BingoCard c = BingoCard.deal(seed);
            assertEquals(c, BingoCard.deal(seed), "the same seed, the same card");
            Set<Integer> seen = new HashSet<>();
            for (int sq = 0; sq < BingoCard.SQUARES; sq++) {
                int n = c.number(sq);
                if (sq == BingoCard.FREE) {
                    assertEquals(0, n, "the middle is free");
                    continue;
                }
                int col = sq % BingoCard.SIZE;
                assertTrue(n >= col * 15 + 1 && n <= col * 15 + 15, "column " + BingoCard.LETTERS.charAt(col) + ": " + n);
                assertTrue(seen.add(n), "no number twice: " + c);
                assertEquals(BingoCard.LETTERS.charAt(col), BingoCard.letter(n));
                assertEquals(sq, c.square(n));
            }
            assertEquals(24, seen.size());
        }
        assertEquals("B 1", BingoCard.ball(1));
        assertEquals("N 45", BingoCard.ball(45));
        assertEquals("O 75", BingoCard.ball(75));
        assertEquals(12, BingoCard.LINES.length, "five rows, five columns, two diagonals");
    }

    @Test
    void aLineAndFourCornersAreMadeOnTheCallTheirLastSquareIsCalled() {
        BingoCard c = BingoCard.of(
                1, 16, 31, 46, 61,
                2, 17, 32, 47, 62,
                3, 18, 0, 48, 63,
                4, 19, 34, 49, 64,
                5, 20, 35, 50, 65);
        // the calls: 3, 18, 48, 63 (the middle row, through the free square), then the corners 1, 61, 5, 65
        int[] calls = callsStartingWith(3, 18, 48, 63, 1, 61, 5, 65);
        int[] rank = BingoDraw.rank(calls);
        assertEquals(3, c.lineAt(rank), "the middle row is done on the fourth call: the free square counts");
        assertArrayEquals(new int[] {10, 11, 12, 13, 14}, c.line(rank));
        assertEquals(7, c.cornersAt(rank), "four corners on the eighth call");
        assertEquals(1, c.toGo(rank, 3), "one to go after three calls");
        assertEquals(0, c.toGo(rank, 4));
        assertTrue(c.daubed(BingoCard.FREE, rank, 0), "the free square from the start");
        assertFalse(c.daubed(0, rank, 4), "B 1 isn't called until the fifth");
        assertTrue(c.daubed(0, rank, 5));

        BingoDraw d = BingoDraw.of(List.of(c, BingoCard.of(
                6, 21, 36, 51, 66,
                7, 22, 37, 52, 67,
                8, 23, 0, 53, 68,
                9, 24, 38, 54, 69,
                10, 25, 39, 55, 70)), 0L, true);
        assertEquals(75, d.calls().length);
        assertEquals(new HashSet<>(List.of(Arrays.stream(d.calls()).boxed().toArray(Integer[]::new))).size(), 75,
                "every number once");
        assertEquals(d.lastCall(), Math.max(d.lineCall(), d.cornersCall()));
    }

    /** A call order that starts with {@code first}, then every other number in order. */
    private static int[] callsStartingWith(int... first) {
        List<Integer> out = new ArrayList<>();
        for (int n : first) {
            out.add(n);
        }
        for (int n = 1; n <= 75; n++) {
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- the prize --------------------------------------------------------------------------------------------

    @Test
    void thePoolIsInsideTheBandForEveryPriceAndCardCount() {
        for (double rtp : new double[] {85, 87.5, 90, 92.5, 95}) {
            RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
            for (int price = BingoPrize.MIN_PRICE; price <= BingoPrize.MAX_PRICE; price++) {
                for (int n = 1; n <= BingoSettings.CARDS_MAX; n++) {
                    int pool = BingoPrize.pool(price, n, rtp);
                    long in = (long) price * n;
                    RtpLimits.Ratio got = new RtpLimits.Ratio(pool, in);
                    assertTrue(pool >= 0 && RtpLimits.inBand(got), price + " x " + n + " at " + rtp + ": " + pool);
                    // the closest to the target from below: one more would pass it
                    assertTrue(got.compareTo(target) <= 0 || pool == Math.ceilDiv(in * 85, 100),
                            "never over the target unless the band's floor is: " + price + " x " + n);
                    assertTrue(new RtpLimits.Ratio(pool + 1, in).compareTo(target) > 0
                                    || new RtpLimits.Ratio(pool + 1, in).compareTo(RtpLimits.MAX_RATIO) > 0,
                            "the most at or under the target: " + price + " x " + n);
                }
            }
        }
        assertEquals(72, BingoPrize.pool(10, 8, 90), "as shipped: 8 cards of 10, a prize of 72");
        assertEquals(9, BingoPrize.pool(10, 1, 90), "one card of 10: 9");
        assertEquals(216, BingoPrize.pool(10, 24, 90));
        assertEquals(-1, BingoPrize.pool(5, 1, 90), "one card of 5 has no whole prize in the band (4 is 80, 5 is 100)");
        assertEquals(43, BingoPrize.corners(72, 60), "four corners' 60 of every 100, rounded down");
        assertEquals(29, BingoPrize.line(72, 60), "the line the rest");
        assertEquals(72, BingoPrize.line(72, 0), "no four corners: the line is all of it");
    }

    @Test
    void sharesAddUpToTheWholePartAndDifferByAtMostOne() {
        SplittableRandom r = new SplittableRandom(7);
        for (int round = 0; round < 2_000; round++) {
            int n = 1 + r.nextInt(30);
            int[] order = BingoDraw.order(r.nextLong(), n);
            List<Integer> places = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                places.add(i);
            }
            java.util.Collections.shuffle(places, new java.util.Random(r.nextLong()));
            int w = 1 + r.nextInt(n);
            int[] winners = places.subList(0, w).stream().mapToInt(Integer::intValue).toArray();
            int part = r.nextInt(500);
            int[] s = BingoPrize.shares(part, winners, order);
            assertEquals(part, Arrays.stream(s).sum(), "the part is paid out whole");
            int lo = Arrays.stream(s).min().orElse(0);
            int hi = Arrays.stream(s).max().orElse(0);
            assertTrue(hi - lo <= 1, "even shares: " + Arrays.toString(s));
            for (int i = 0; i < w; i++) {
                assertTrue(s[i] == part / w || s[i] == part / w + 1);
            }
        }
    }

    /**
     * The proof: with the round's calls held fixed, the cards are handed to the places in every one of the {@code N!}
     * ways, and the cards in play are shuffled for the leftover units in every one of the {@code N!} ways; what each
     * place is given is added up over all of them, in whole numbers. Every place's total is exactly
     * {@code N! x (N - 1)! x prize}: an average of exactly {@code prize / N}.
     */
    @Test
    void everyPlaceGetsExactlyItsShareOverEveryWayTheCardsCanBeHeld() {
        SplittableRandom r = new SplittableRandom(20261124L);
        int checked = 0;
        for (int n = 1; n <= 6; n++) {
            int rounds = n <= 4 ? 12 : n == 5 ? 4 : 1;
            List<int[]> perms = permutations(n);
            for (int round = 0; round < rounds; round++) {
                List<BingoCard> cards = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    cards.add(BingoCard.deal(r.nextLong()));
                }
                long seed = r.nextLong();
                for (boolean corners : new boolean[] {true, false}) {
                    int pool = BingoPrize.pool(10, n, 90);
                    int share = corners ? 60 : 0;
                    int line = BingoPrize.line(pool, share);
                    int four = BingoPrize.corners(pool, share);
                    long[] total = new long[n];
                    long ways = 0;
                    for (int[] perm : perms) {
                        List<BingoCard> held = new ArrayList<>();
                        for (int place = 0; place < n; place++) {
                            held.add(cards.get(perm[place]));
                        }
                        BingoDraw d = BingoDraw.of(held, seed, corners);
                        for (int[] shuffle : perms) {
                            BingoDraw s = new BingoDraw(d.seed(), d.calls(), d.lineCall(), d.lineWinners(),
                                    d.cornersCall(), d.cornersWinners(), shuffle);
                            int[][] got = BingoPrize.cardShares(s, n, line, four);
                            long sum = 0;
                            for (int place = 0; place < n; place++) {
                                total[place] += got[0][place] + got[1][place];
                                sum += got[0][place] + got[1][place];
                            }
                            assertEquals(pool, sum, "every round pays out its whole prize");
                            ways++;
                        }
                    }
                    assertEquals(factorial(n) * factorial(n), ways);
                    for (int place = 0; place < n; place++) {
                        assertEquals(factorial(n) * factorial(n - 1) * pool, total[place], "place " + place + " of "
                                + n + " (seed " + seed + ", corners " + corners + "): exactly prize / N on average");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 80, "checked " + checked + " rounds");
    }

    /** A sanity check on top of the proof: over many real rounds, each card's average is close to prize / N. */
    @Test
    void overManyRoundsACardGivesBackAboutTheTargetShare() {
        SplittableRandom r = new SplittableRandom(42);
        int n = 8;
        int pool = BingoPrize.pool(10, n, 90);
        long first = 0;
        long last = 0;
        int rounds = 40_000;
        for (int round = 0; round < rounds; round++) {
            List<BingoCard> cards = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                cards.add(BingoCard.deal(r.nextLong()));
            }
            BingoDraw d = BingoDraw.of(cards, r.nextLong(), true);
            int[][] got = BingoPrize.cardShares(d, n, BingoPrize.line(pool, 60), BingoPrize.corners(pool, 60));
            first += got[0][0] + got[1][0];
            last += got[0][n - 1] + got[1][n - 1];
        }
        double expect = (double) pool / n; // 9 of every 10
        assertEquals(expect, (double) first / rounds, expect * 0.05, "the first card");
        assertEquals(expect, (double) last / rounds, expect * 0.05, "the last card");
    }

    @Test
    void theMostCardsKeepALoneWinnerUnderTheCap() {
        assertEquals(27, BingoPrize.mostCards(10, 90, 250, 40), "27 cards of 10 is a prize of 243; 28 would be 252");
        assertEquals(24, BingoPrize.mostCards(10, 90, 500, 24));
        assertEquals(4, BingoPrize.mostCards(100, 90, 400, 40));
    }

    // ---- the round's data -------------------------------------------------------------------------------------

    @Test
    void aTicketCarriesEverythingItsRoundNeedsAndTheExitRuleReadsOnlyThat() {
        BingoTicket t = BingoTicket.bought(10, new long[] {11L, -22L});
        assertEquals("v=1;in=20;price=10;cards=11,-22", t.data());
        assertEquals(t, BingoTicket.read(t.data()));
        assertEquals(20, BingoTicket.settleOnExit(0L, 20, t.data()), "nothing drawn: the cards are given back");
        BingoTicket drawn = t.drawn(99L, 9, 27);
        assertEquals("v=1;in=20;price=10;cards=11,-22;calls=99;n=9;pays=27", drawn.data());
        assertEquals(drawn, BingoTicket.read(drawn.data()));
        assertEquals(27, BingoTicket.settleOnExit(0L, 20, drawn.data()), "drawn: what its cards made");
        assertEquals(0, BingoTicket.settleOnExit(0L, 20, t.drawn(99L, 9, 0).data()), "drawn, nothing made");
        assertEquals(BingoCard.deal(11L), t.dealt()[0], "its cards are dealt again from their seeds");
        assertThrows(IllegalArgumentException.class, () -> BingoTicket.settleOnExit(0L, 30, t.data()),
                "a round that holds something else is not this data's");
        assertThrows(IllegalArgumentException.class, () -> BingoTicket.read("v=1;in=20;price=10"));
        assertThrows(IllegalArgumentException.class, () -> BingoTicket.read("v=1;in=20;price=10;cards=1,2;calls=3"),
                "a drawn round says what it made");
        assertThrows(IllegalArgumentException.class, () -> BingoTicket.bought(10, new long[5]), "four cards at most");
        assertThrows(IllegalArgumentException.class, () -> BingoTicket.read("pick=3"));
    }

    // ---- the settings -----------------------------------------------------------------------------------------

    @Test
    void theSettingsKeepThePriceAndTheRoomInsideWhatCanBePaid() {
        BingoSettings d = BingoSettings.defaults();
        List<String> warns = new ArrayList<>();
        BingoSettings read = BingoSettings.parse(new GamesConfig.Node("games.bingo", Map.of("card_price", 5,
                "call_seconds", 0, "rtp", 99), warns::add), d);
        assertEquals(BingoPrize.MIN_PRICE, read.cardPrice(), "raised to the least");
        assertEquals(BingoSettings.CALL_MIN, read.callSeconds());
        assertEquals(95.0, read.rtp(), "held to the band's top");
        assertEquals(3, warns.size(), warns.toString());

        warns.clear();
        BingoSettings dear = BingoSettings.parse(new GamesConfig.Node("games.bingo", Map.of("card_price", 100),
                warns::add), d);
        assertEquals(4, dear.maxCards(), "a lone winner of 5 cards of 100 (450) would pass max_payout (400 here)");
        assertEquals(4, dear.roomCards(), "and the room is no bigger than that");
        assertEquals(2, warns.size(), warns.toString());
        assertEquals(10, d.price(false));
        assertEquals(10, d.price(true));
        assertEquals(2_000L, d.callMs());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static long factorial(int n) {
        long f = 1;
        for (int i = 2; i <= n; i++) {
            f *= i;
        }
        return f;
    }

    private static List<int[]> permutations(int n) {
        List<int[]> out = new ArrayList<>();
        permute(new int[n], new boolean[n], 0, out);
        return out;
    }

    private static void permute(int[] cur, boolean[] used, int at, List<int[]> out) {
        if (at == cur.length) {
            out.add(cur.clone());
            return;
        }
        for (int i = 0; i < cur.length; i++) {
            if (!used[i]) {
                used[i] = true;
                cur[at] = i;
                permute(cur, used, at + 1, out);
                used[i] = false;
            }
        }
    }
}
