package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.QuestType;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * Reads the vanilla statistics behind the <b>pulled</b> quest types.
 *
 * <p>Most of what makes a good daily objective — catch fish, walk somewhere, breed something —
 * has no event in this plugin and would otherwise need a listener each. The server already
 * counts all of it, so a quest can instead snapshot a player's lifetime total when a period
 * opens and read the difference since. That is one task instead of five listeners, it survives
 * a restart for free (the statistic is the server's, not ours), and it cannot miss an event.
 *
 * <p>It also cannot be farmed by relogging, and it deliberately does <b>not</b> credit
 * anything a player did before the period opened: the baseline is the whole point.
 *
 * <p>The same snapshot-and-diff shape is what the Courier module needs for its travel
 * multiplier, which is why this is a class of its own rather than a switch inside
 * {@link QuestService}.
 */
public final class QuestStats {

    /** Centimetres per block, the unit vanilla counts distance in. */
    private static final int CM_PER_BLOCK = 100;

    /** The movement a player does under their own power — no mount, no boat, no elytra. */
    private static final Statistic[] ON_FOOT = {
            Statistic.WALK_ONE_CM,
            Statistic.SPRINT_ONE_CM,
            Statistic.CROUCH_ONE_CM,
    };

    /**
     * Hostiles that are not {@link Monster}s. {@code Slime}, {@code Ghast} and {@code Phantom}
     * sit on other branches of the entity hierarchy, so a {@code Monster}-only test quietly
     * fails to count a player's whole evening in the Nether. Resolved by name and skipped if a
     * version drops one, so this list can never break startup.
     */
    private static final String[] EXTRA_HOSTILES = {
            "SLIME", "MAGMA_CUBE", "GHAST", "PHANTOM", "SHULKER",
            "HOGLIN", "ZOGLIN", "ENDER_DRAGON", "WITHER",
    };

    private static volatile Set<EntityType> hostiles;

    private QuestStats() {
    }

    /** True if this type is read from statistics rather than pushed by a gameplay hook. */
    public static boolean isPulled(QuestType type) {
        return switch (type) {
            case CATCH_FISH, KILL_HOSTILES, BREED_ANIMALS, TRADE_VILLAGER, TRAVEL_ON_FOOT -> true;
            case SELL_MARKET, OPEN_CRATE, PRINT_MINI, OPEN_PACK, SCRATCH -> false;
        };
    }

    /**
     * The player's lifetime total for a pulled type, <b>in the unit its target is written in</b>
     * — blocks for distance, not the centimetres vanilla stores, so an admin writing
     * {@code target: 500} means 500 blocks and the GUI can say so.
     *
     * @return the total, or 0 for a pushed type (which has no statistic to read)
     */
    public static long read(Player player, QuestType type) {
        if (player == null) {
            return 0;
        }
        return switch (type) {
            case CATCH_FISH -> stat(player, Statistic.FISH_CAUGHT);
            case BREED_ANIMALS -> stat(player, Statistic.ANIMALS_BRED);
            case TRADE_VILLAGER -> stat(player, Statistic.TRADED_WITH_VILLAGER);
            case TRAVEL_ON_FOOT -> {
                long cm = 0;
                for (Statistic s : ON_FOOT) {
                    cm += stat(player, s);
                }
                yield cm / CM_PER_BLOCK;
            }
            case KILL_HOSTILES -> {
                long kills = 0;
                for (EntityType t : hostiles()) {
                    try {
                        kills += player.getStatistic(Statistic.KILL_ENTITY, t);
                    } catch (RuntimeException ignored) {
                        // a type the server refuses to key a statistic on is simply not counted
                    }
                }
                yield kills;
            }
            case SELL_MARKET, OPEN_CRATE, PRINT_MINI, OPEN_PACK, SCRATCH -> 0;
        };
    }

    private static long stat(Player player, Statistic statistic) {
        try {
            return Math.max(0, player.getStatistic(statistic));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Every hostile {@link EntityType}, resolved once. Built from the {@link Monster} interface
     * plus {@link #EXTRA_HOSTILES}, so it follows the API rather than a list that goes stale
     * every time a version adds a mob.
     */
    private static Set<EntityType> hostiles() {
        Set<EntityType> cached = hostiles;
        if (cached != null) {
            return cached;
        }
        Set<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (EntityType type : EntityType.values()) {
            try {
                Class<?> cls = type.getEntityClass();
                if (cls != null && Monster.class.isAssignableFrom(cls)) {
                    set.add(type);
                }
            } catch (RuntimeException ignored) {
                // an entity type with no class on this server is not a mob we can count
            }
        }
        for (String name : EXTRA_HOSTILES) {
            try {
                set.add(EntityType.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // dropped or renamed in this version — nothing to count, nothing to fix
            }
        }
        hostiles = set;
        return set;
    }
}
