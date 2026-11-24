package com.dierks.homecraft.games.chance.holdem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tests' decks: the cards a hand should deal, put where a real dealer would deal them from. */
public final class Decks {

    private Decks() {
    }

    /**
     * A deck that deals {@code holes.get(i)} ("As Kd") to player {@code i} of {@code n} with the button at
     * {@code button}, then {@code board} (up to five cards: the flop, the turn, the river) after the burns; every other
     * card fills the gaps in order.
     */
    public static int[] deck(int n, int button, List<String> holes, String board) {
        Integer[] at = new Integer[PokerCard.DECK];
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (i >= holes.size() || holes.get(i) == null) {
                continue;
            }
            List<PokerCard> two = PokerCard.parse(holes.get(i));
            int k = (i - button - 1 + 2 * n) % n; // their place in the deal, from left of the button
            put(at, used, k, two.get(0));
            put(at, used, n + k, two.get(1));
        }
        List<PokerCard> b = board == null || board.isBlank() ? List.of() : PokerCard.parse(board);
        int[] places = {2 * n + 1, 2 * n + 2, 2 * n + 3, 2 * n + 5, 2 * n + 7};
        for (int k = 0; k < b.size(); k++) {
            put(at, used, places[k], b.get(k));
        }
        List<Integer> rest = new ArrayList<>();
        for (int c = 0; c < PokerCard.DECK; c++) {
            if (!used.contains(c)) {
                rest.add(c);
            }
        }
        int r = 0;
        int[] out = new int[PokerCard.DECK];
        for (int k = 0; k < PokerCard.DECK; k++) {
            out[k] = at[k] != null ? at[k] : rest.get(r++);
        }
        return out;
    }

    private static void put(Integer[] at, Set<Integer> used, int k, PokerCard c) {
        if (!used.add(c.code())) {
            throw new IllegalArgumentException(c + " twice");
        }
        at[k] = c.code();
    }
}
