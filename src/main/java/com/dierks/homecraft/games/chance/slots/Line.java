package com.dierks.homecraft.games.chance.slots;

/**
 * The lines Ore Slots pays on its one payline (spec §5.3, R1.4), top line first.
 *
 * <p>Only the best line pays: the one that gives the most tokens at the stake (after
 * {@code games.max_payout}), and on a tie the one listed first here. A Wild stands in for any ore,
 * so Wild, Wild, Diamond is three diamonds and Wild, Wild, Stone is two the same; three Wilds
 * count for every line and pay the best of them (shipped: the Wild line).
 */
public enum Line {

    THREE_WILDS("wild", "Three wilds", "3 wild", Symbol.WILD),
    THREE_DIAMONDS("diamond", "Three diamonds", "3 diamond", Symbol.DIAMOND),
    THREE_GOLD("gold", "Three gold", "3 gold", Symbol.GOLD),
    THREE_IRON("iron", "Three iron", "3 iron", Symbol.IRON),
    THREE_COPPER("copper", "Three copper", "3 copper", Symbol.COPPER),
    THREE_COAL("coal", "Three coal", "3 coal", Symbol.COAL),
    /** At least two of the three are the same ore, a Wild counting as a match. */
    TWO_THE_SAME("two", "Two the same", "two the same", null);

    private final String key;
    private final String label;
    private final String combo;
    private final Symbol symbol;

    Line(String key, String label, String combo, Symbol symbol) {
        this.key = key;
        this.label = label;
        this.combo = combo;
        this.symbol = symbol;
    }

    /** Its key under {@code games.ore_slots.pays}. */
    public String key() {
        return key;
    }

    /** Its player-facing name ("Three diamonds"). */
    public String label() {
        return label;
    }

    /** How the website names it ("3 diamond", "two the same"). */
    public String combo() {
        return combo;
    }

    /** The symbol three of which make it; {@code null} for two the same. */
    public Symbol symbol() {
        return symbol;
    }

    /** Whether these three symbols make this line (whatever it pays). */
    public boolean matches(Symbol a, Symbol b, Symbol c) {
        if (this == THREE_WILDS) {
            return a == Symbol.WILD && b == Symbol.WILD && c == Symbol.WILD;
        }
        if (symbol != null) {
            return counts(a, symbol) && counts(b, symbol) && counts(c, symbol);
        }
        for (Symbol ore : Symbol.values()) {
            if (ore.ore() && (counts(a, ore) ? 1 : 0) + (counts(b, ore) ? 1 : 0) + (counts(c, ore) ? 1 : 0) >= 2) {
                return true;
            }
        }
        return false;
    }

    /** A Wild counts as any ore. */
    private static boolean counts(Symbol s, Symbol ore) {
        return s == ore || s == Symbol.WILD;
    }
}
