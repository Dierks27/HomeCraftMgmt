package com.dierks.homecraft.mini;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Wild-Drops loot model. A {@link LootList} is a weighted bag of Mini ids; a
 * {@link LootSource} ties a world trigger (block break / mob kill / fishing /
 * natural spawn) and a match (material or entity, {@code *} = any) to either a
 * list <em>or</em> a tag pool (every Mini carrying that tag, weighted by rarity)
 * with a drop chance. Each source may override the grade odds and Shiny chance.
 */
public final class Loot {

    private Loot() {
    }

    /** What triggers a drop roll. */
    public enum Trigger {
        BLOCK_BREAK,
        MOB_KILL,
        FISHING,
        NATURAL_SPAWN;

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

        /** The "found a Mini while …" verb for the found-broadcast. */
        public String verb() {
            return switch (this) {
                case BLOCK_BREAK -> "while mining";
                case MOB_KILL -> "while fighting";
                case FISHING -> "while fishing";
                case NATURAL_SPAWN -> "while exploring";
            };
        }
    }

    /** One weighted reference to a Mini within a list. */
    public record LootEntry(String miniId, double weight) {
    }

    /** A named weighted bag of Minis. */
    public record LootList(String id, List<LootEntry> entries) {
    }

    /**
     * A trigger → pool binding with a percentage chance (e.g. 0.0001 = 0.0001%).
     * Exactly one of {@code listId} / {@code tag} is set. {@code gradeWeights} (may be
     * null = use the Mini's Printer odds) and {@code shinyPercent} (null = the global
     * {@code minis.loot.shiny_percent}) are optional per-source overrides.
     */
    public record LootSource(Trigger trigger, String match, String listId, String tag, double chancePercent,
                             Map<Grade, Double> gradeWeights, Double shinyPercent) {

        /** List-backed source with default grade/shiny odds (the pre-tag constructor). */
        public LootSource(Trigger trigger, String match, String listId, double chancePercent) {
            this(trigger, match, listId, null, chancePercent, null, null);
        }

        public boolean matches(String key) {
            return match == null || match.equals("*") || match.equalsIgnoreCase(key);
        }

        public boolean usesTag() {
            return tag != null && !tag.isBlank();
        }

        /** "list:<id>" or "tag:<name>" — for admin screens and logs. */
        public String poolLabel() {
            return usesTag() ? "tag:" + tag : "list:" + listId;
        }
    }

    /**
     * Natural-spawn tuning: cadence, lifetime, how far from a player a Mini lands, and the
     * two throttles that cap how often one shows up at all.
     *
     * <p>{@code playerCooldownMinutes} is the throttle that actually governs the feel of it.
     * The per-source {@code chance_percent} is a roll per player per tick, so the rate an
     * admin ends up with is a product of three numbers and is nobody's idea of tunable; the
     * cooldown says the thing you mean instead — "a player finds at most one wild Mini every
     * two hours" — and holds no matter what the chance is set to. {@code maxLive} is the
     * other end of the same idea: a ceiling on unclaimed Minis standing in the world.
     */
    public record Natural(int intervalTicks, int despawnMinutes, int minDistance, int maxDistance,
                          int playerCooldownMinutes, int maxLive) {
    }

    /** The full loot config: lists + sources + tag-pool rarity weights + Shiny odds + natural spawns. */
    public record MiniLoot(List<LootList> lists, List<LootSource> sources, Map<Rarity, Double> rarityWeights,
                           double shinyPercent, Natural natural) {

        /** Lists + sources with the built-in defaults for everything else (older call sites). */
        public MiniLoot(List<LootList> lists, List<LootSource> sources) {
            this(lists, sources, defaultRarityWeights(), 5.0, new Natural(24000, 3, 96, 128, 120, 2));
        }

        public static Map<Rarity, Double> defaultRarityWeights() {
            Map<Rarity, Double> m = new EnumMap<>(Rarity.class);
            m.put(Rarity.LEGENDARY, 1.0);
            m.put(Rarity.EPIC, 4.0);
            m.put(Rarity.RARE, 15.0);
            m.put(Rarity.UNCOMMON, 40.0);
            m.put(Rarity.COMMON, 100.0);
            return m;
        }

        public double rarityWeight(Rarity rarity) {
            Double w = rarityWeights == null ? null : rarityWeights.get(rarity);
            return w == null ? defaultRarityWeights().getOrDefault(rarity, 1.0) : Math.max(0, w);
        }

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
