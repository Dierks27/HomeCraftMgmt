package com.dierks.homecraft.arcade.homes;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The arithmetic of the +1 Home perk, kept free of Bukkit, LuckPerms and Essentials so it can be
 * tested.
 *
 * <p>EssentialsX ({@code Settings.getHomeLimit(User)}, checked against its source) gives a player
 * the <b>highest</b> {@code sethome-multiple} tier they hold, never the sum:
 * <pre>
 *   limit = 1
 *   if has("essentials.sethome.multiple")        limit = value("default")
 *   for each tier key in sethome-multiple:
 *       if has("essentials.sethome.multiple." + key) and value(key) &gt; limit: limit = value(key)
 *   value(key) = sethome-multiple.key, else sethome-multiple.default, else 3
 * </pre>
 * The tier loop is not gated on {@code essentials.sethome.multiple}: a tier node alone counts.
 * So a perk that grants "homes2" gives a mayor with 5 homes nothing. The perk instead works out
 * the player's own limit (their <b>base</b>, ignoring the {@code hcm_} tiers it manages itself),
 * adds the slots bought, and grants exactly one tier, {@code hcm_<base+bonus>}, whose value is that
 * total. Because the result is higher than every other tier the player holds, it is the one
 * EssentialsX picks.
 */
public final class HomeMath {

    /** Node prefix every Essentials home tier uses. */
    public static final String MULTIPLE = "essentials.sethome.multiple";
    public static final String UNLIMITED = MULTIPLE + ".unlimited";
    /** The tier names this plugin manages: {@code hcm_2}, {@code hcm_6}, … */
    public static final String OWN_TIER = "hcm_";
    public static final String OWN_NODE_PREFIX = MULTIPLE + "." + OWN_TIER;
    /** A base this high (or unlimited) has nothing to gain from one more home: the row is hidden. */
    public static final int OFFER_BELOW = 20;
    private static final int ESSENTIALS_FALLBACK = 3;

    private HomeMath() {
    }

    /**
     * The home limit EssentialsX would give, from the {@code sethome-multiple} tiers (name → value)
     * and what the player has, skipping the tier names in {@code ignoredTiers} and every
     * {@code hcm_} tier.
     */
    public static int base(Map<String, Integer> tiers, Predicate<String> has, Set<String> ignoredTiers) {
        int limit = 1;
        if (has.test(MULTIPLE)) {
            limit = value(tiers, "default");
        }
        for (String tier : tiers.keySet()) {
            if (tier.startsWith(OWN_TIER) || ignoredTiers.contains(tier)) {
                continue;
            }
            if (has.test(MULTIPLE + "." + tier) && limit < value(tiers, tier)) {
                limit = value(tiers, tier);
            }
        }
        return limit;
    }

    /** {@code getHomeLimit(set)}: the tier's number, else the default's, else 3. */
    static int value(Map<String, Integer> tiers, String tier) {
        Integer v = tiers.get(tier);
        if (v != null) {
            return v;
        }
        Integer d = tiers.get("default");
        return d != null ? d : ESSENTIALS_FALLBACK;
    }

    /** Whether the +1 Home row is offered at all: not to unlimited players or a base of 20+. */
    public static boolean offered(int base, boolean unlimited) {
        return !unlimited && base < OFFER_BELOW;
    }

    /** The tier name for a total: {@code hcm_6}. */
    public static String tier(int total) {
        return OWN_TIER + total;
    }

    public static String node(int total) {
        return MULTIPLE + "." + tier(total);
    }

    /**
     * The line an admin must add under {@code sethome-multiple} for a total, or null when the
     * tier is already defined with that value.
     */
    public static String missingTierLine(Map<String, Integer> tiers, int total) {
        Integer v = tiers.get(tier(total));
        return v != null && v == total ? null : "  " + tier(total) + ": " + total;
    }

    /** The nodes to add and remove to go from what the player has to what they should have. */
    public record Change(Set<String> add, Set<String> remove) {
        public boolean none() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    /**
     * What to write so that, of the managed nodes, the player holds exactly {@code desired} (or
     * none when it is null). {@code current} is the player's own true nodes that start with
     * {@link #OWN_NODE_PREFIX}; {@code alsoRemove} are other nodes to clear (the old perk's
     * homes2/homes3). Nothing to do is an empty change — so a refresh never writes, and never
     * triggers another refresh, when the player is already right.
     */
    public static Change change(Set<String> current, String desired, Set<String> alsoRemove) {
        Set<String> add = new LinkedHashSet<>();
        Set<String> remove = new LinkedHashSet<>();
        if (desired != null && !current.contains(desired)) {
            add.add(desired);
        }
        for (String n : current) {
            if (!n.equals(desired)) {
                remove.add(n);
            }
        }
        remove.addAll(alsoRemove);
        return new Change(add, remove);
    }
}
