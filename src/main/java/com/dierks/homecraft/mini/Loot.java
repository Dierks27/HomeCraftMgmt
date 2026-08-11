package com.dierks.homecraft.mini;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Wild-Drops loot model. A {@link LootList} is a weighted bag of Mini ids; a
 * {@link LootSource} ties a world trigger (block break / mob kill / fishing) and
 * a match (material or entity, {@code *} = any) to a list with a drop chance. A
 * Mini can appear in many lists — edit it once, it updates everywhere.
 */
public final class Loot {

    private Loot() {
    }

    /** What triggers a drop roll. */
    public enum Trigger {
        BLOCK_BREAK,
        MOB_KILL,
        FISHING;

        public static Trigger parse(String s) {
            if (s == null) {
                return BLOCK_BREAK;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return BLOCK_BREAK;
            }
        }
    }

    /** One weighted reference to a Mini within a list. */
    public record LootEntry(String miniId, double weight) {
    }

    /** A named weighted bag of Minis. */
    public record LootList(String id, List<LootEntry> entries) {
    }

    /** A trigger→list binding with a percentage chance (e.g. 0.0001 = 0.0001%). */
    public record LootSource(Trigger trigger, String match, String listId, double chancePercent) {
        public boolean matches(String key) {
            return match == null || match.equals("*") || match.equalsIgnoreCase(key);
        }
    }

    /** The full loot config: lists + sources. */
    public record MiniLoot(List<LootList> lists, List<LootSource> sources) {

        public LootList listById(String id) {
            for (LootList l : lists) {
                if (l.id().equalsIgnoreCase(id)) {
                    return l;
                }
            }
            return null;
        }

        public List<LootSource> sourcesFor(Trigger trigger) {
            List<LootSource> out = new ArrayList<>();
            for (LootSource s : sources) {
                if (s.trigger() == trigger) {
                    out.add(s);
                }
            }
            return out;
        }
    }
}
