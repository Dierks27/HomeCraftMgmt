package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;

import java.util.List;

/**
 * The Prize Counter's shelves grouped by kind, on screen only (0.38.3, the owner: "a quick reorganization of the
 * Arcade"): each kind starts its own row while the tab still fits one page; otherwise the tab packs as before. Config
 * order, the tabs and the prices are untouched: nothing here is stored, and the website feed never sees it.
 *
 * <ul>
 *   <li>Boosts: the personal boosts and the Quest Reroll, then the two that change the world (Make it Day!, Sunny
 *       Day).</li>
 *   <li>Fireworks &amp; Trails: every firework item (the shows, the Launcher, the Remote and Party Time!), then the
 *       trails (a collector trail first, as it is first in config order).</li>
 *   <li>Looks: the hats and caps, then the golf balls.</li>
 * </ul>
 *
 * Any other tab is one kind, so its tiles land where they always did. A row of a type a tab doesn't expect (an
 * owner's own row moved there) gets its own row after the others. Pure, so the layout is tested without a server.
 */
final class PrizeShelves {

    /** Tiles in a row of the counter's grid. */
    static final int PER_ROW = 9;

    private PrizeShelves() {
    }

    /** The kind of a tile on its tab: 0, 1, then 2 for anything else (an owner's own row on that tab). */
    static int kind(PrizeTab tab, PrizeType type) {
        return switch (tab) {
            case BOOSTS -> type == PrizeType.BOOST || type == PrizeType.QUEST_REROLL ? 0
                    : type == PrizeType.COMMAND ? 1 : 2;
            case COSMETICS -> type == null ? 2 : switch (type) {
                case FIREWORK, BLOCK, REMOTE, PARTY -> 0;
                case TRAIL -> 1;
                default -> 2;
            };
            case LOOKS -> type == PrizeType.HAT ? 0 : type == PrizeType.BALL ? 1 : 2;
            default -> 0;
        };
    }

    /**
     * Each tile's grid slot (0 = the grid's first, row by row) for {@code types} in config order: grouped by kind,
     * each kind from a new row, config order kept inside a kind. {@code null} when that needs more than {@code rows}
     * rows: the caller pages the tiles packed, exactly as 0.38.2 did.
     */
    static int[] slots(PrizeTab tab, List<PrizeType> types, int rows) {
        int kinds = 3;
        int[] count = new int[kinds];
        for (PrizeType t : types) {
            count[kind(tab, t)]++;
        }
        int[] start = new int[kinds];
        int row = 0;
        for (int k = 0; k < kinds; k++) {
            start[k] = row * PER_ROW;
            row += (count[k] + PER_ROW - 1) / PER_ROW;
        }
        if (row > rows) {
            return null;
        }
        int[] next = start.clone();
        int[] out = new int[types.size()];
        for (int i = 0; i < types.size(); i++) {
            out[i] = next[kind(tab, types.get(i))]++;
        }
        return out;
    }
}
