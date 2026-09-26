package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 15: packs hold one Card, and roll by rarity odds instead of a hand-kept list.
 *
 * <ul>
 *   <li>Every pack becomes {@code count: 1} — the owner's decision, so it applies to an admin's
 *       own packs too, with a line saying so.</li>
 *   <li>The two shipped packs, while their placeholder pools (piggy/chick) are still what we
 *       shipped, become the bundled rarity-odds rows: new Minis join them automatically.</li>
 *   <li>Their prices move 250 → 100 and 750 → 300 only where still shipped.</li>
 *   <li>A pool the admin changed stays a hand-picked pool, with a warning naming it.</li>
 * </ul>
 */
final class PackConfigMigration {

    /** The placeholder pools revision 3 shipped: pack id → (card → weight). */
    private static final Map<String, Map<String, Double>> SHIPPED_POOLS = Map.of(
            "starter", Map.of("piggy_mini", 50.0, "chick_mini", 50.0),
            "premium", Map.of("piggy_mini", 45.0, "chick_mini", 45.0, "golden_idol", 1.0));

    /** Pack id → {shipped price, new price}. */
    private static final Map<String, double[]> SHIPPED_PRICES = Map.of(
            "starter", new double[] {250, 100},
            "premium", new double[] {750, 300});

    private static final Map<String, String> SHIPPED_DISPLAY = Map.of(
            "starter", "Starter Pack",
            "premium", "Premium Pack");

    private PackConfigMigration() {
    }

    static void apply(FileConfiguration c, List<String> log) {
        if (!(c.get("packs", null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the bundled packs
        }
        YamlConfiguration defaults = ArcadeConfigMigration.bundled();
        Map<String, Map<String, Object>> fresh = new HashMap<>();
        if (defaults != null) {
            for (Map<?, ?> row : defaults.getMapList("packs")) {
                fresh.put(String.valueOf(row.get("id")).toLowerCase(Locale.ROOT), copy(row));
            }
        }
        List<Object> out = new ArrayList<>();
        boolean changed = false;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                out.add(o);
                continue;
            }
            Map<String, Object> row = copy(m);
            String id = String.valueOf(row.get("id")).trim().toLowerCase(Locale.ROOT);
            Map<String, Double> shippedPool = SHIPPED_POOLS.get(id);
            boolean shippedPoolHere = shippedPool != null && shippedPool.equals(pool(row.get("pool")));

            // Price first: it decides nothing else, and an admin's price survives either way.
            double[] prices = SHIPPED_PRICES.get(id);
            boolean shippedPrice = prices != null && row.get("price") instanceof Number n && n.doubleValue() == prices[0];

            if (shippedPoolHere && fresh.containsKey(id)) {
                Map<String, Object> next = new LinkedHashMap<>(fresh.get(id));
                if (!shippedPrice) {
                    next.put("price", row.get("price")); // theirs
                    log.add(WARN + "Config migration: kept packs → " + id + " price = " + row.get("price")
                            + " because you have changed it (the new default is " + fresh.get(id).get("price") + ").");
                }
                Object display = row.get("display");
                if (display != null && !Objects.equals(SHIPPED_DISPLAY.get(id), String.valueOf(display))) {
                    next.put("display", display); // their own name
                }
                out.add(next);
                changed = true;
                log.add("Config migration: the " + id + " pack now holds 1 Card and rolls by rarity odds "
                        + "instead of the placeholder list, so every Mini (and every new one) can come out of it"
                        + (shippedPrice ? "; its price is " + fresh.get(id).get("price") + " (was " + (long) prices[0] + ")" : "")
                        + ".");
                continue;
            }
            if (shippedPrice) {
                row.put("price", prices[1]);
                changed = true;
                log.add("Config migration: packs → " + id + " price " + (long) prices[0] + " → " + (long) prices[1] + ".");
            } else if (prices != null && row.get("price") instanceof Number n && n.doubleValue() != prices[1]) {
                log.add(WARN + "Config migration: kept packs → " + id + " price = " + n + " because you have "
                        + "changed it (the new default is " + (long) prices[1] + ").");
            }
            if (!(row.get("count") instanceof Number cnt) || cnt.intValue() != 1) {
                log.add("Config migration: packs → " + id + " count " + row.get("count") + " → 1. Every pack holds "
                        + "one Card now.");
                row.put("count", 1);
                changed = true;
            }
            if (row.get("pool") instanceof List<?> l && !l.isEmpty()) {
                log.add(WARN + "Config migration: kept packs → " + id + " as a hand-picked pool (" + l.size()
                        + " Card(s)) because you have changed it. It won't pick up new Minis by itself; clear its "
                        + "pool in /hcm packs to roll by rarity odds instead.");
            }
            out.add(row);
        }
        if (changed) {
            c.set("packs", out);
        }
    }

    private static Map<String, Double> pool(Object raw) {
        Map<String, Double> out = new HashMap<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> e && e.get("card") != null && e.get("weight") instanceof Number w) {
                    out.merge(String.valueOf(e.get("card")).trim().toLowerCase(Locale.ROOT), w.doubleValue(), Double::sum);
                } else {
                    return Map.of("?", -1.0); // something we never shipped
                }
            }
        }
        return out;
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
