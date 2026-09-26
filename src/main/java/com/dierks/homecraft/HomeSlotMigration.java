package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 16: the Second Home / Third Home rows become one +1 Home row.
 *
 * <p>The old rows granted {@code essentials.sethome.multiple.homes2} / {@code .homes3}. Essentials
 * gives a player their highest home tier, not the sum, so those gave a mayor with 5 homes nothing
 * at all. The shipped rows are replaced by {@code home_slot}, which adds to what a player already
 * has. What anyone paid for carries over: schema v31 credits their purchases as slots, and the
 * perk clears the homes2/homes3 nodes it granted. A row an admin changed is kept, with a warning.
 */
final class HomeSlotMigration {

    /** The two rows as 0.31.0 shipped them: id → (cost, command). */
    private static final Map<String, Object[]> SHIPPED = Map.of(
            "home_2", new Object[] {400, "lp user %player% permission set essentials.sethome.multiple.homes2 true"},
            "home_3", new Object[] {600, "lp user %player% permission set essentials.sethome.multiple.homes3 true"});

    private HomeSlotMigration() {
    }

    static void apply(FileConfiguration c, List<String> log) {
        if (!(c.get("arcade.prizes", null) instanceof List<?> raw)) {
            return;
        }
        List<Object> out = new ArrayList<>();
        int insertAt = -1;
        boolean hasSlot = false;
        List<String> kept = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                out.add(o);
                continue;
            }
            String id = String.valueOf(m.get("id")).trim().toLowerCase(Locale.ROOT);
            if (id.equals("home_slot")) {
                hasSlot = true;
            }
            Object[] shipped = SHIPPED.get(id);
            if (shipped != null) {
                if (isShipped(m, shipped)) {
                    if (insertAt < 0) {
                        insertAt = out.size();
                    }
                    continue; // dropped
                }
                kept.add(id);
            }
            out.add(o);
        }
        for (String id : kept) {
            log.add(WARN + "Config migration: kept your arcade.prizes → " + id + " row because you have changed it. "
                    + "It still grants a fixed Essentials tier, which gives a player with more homes than that "
                    + "nothing — the new home_slot row adds to what they have. Remove it by hand to switch.");
        }
        if (insertAt < 0) {
            return;
        }
        if (!hasSlot) {
            Map<String, Object> slot = bundledSlot();
            if (slot != null) {
                out.add(insertAt, slot);
            }
        }
        c.set("arcade.prizes", out);
        log.add("Config migration: the Second Home / Third Home rows are now one \"+1 Home\" row (home_slot) that adds "
                + "to the homes a player already has. Anyone who bought them keeps them as slots. Add hcm_<N>: <N> "
                + "tiers under sethome-multiple in plugins/Essentials/config.yml — the log names any that are missing.");
    }

    private static boolean isShipped(Map<?, ?> row, Object[] shipped) {
        if (!"command".equalsIgnoreCase(String.valueOf(row.get("type")).trim())) {
            return false;
        }
        if (!(row.get("cost_tokens") instanceof Number n) || n.intValue() != (Integer) shipped[0]) {
            return false;
        }
        return row.get("commands") instanceof List<?> cmds && cmds.size() == 1
                && Objects.equals(String.valueOf(cmds.get(0)).trim(), shipped[1]);
    }

    private static Map<String, Object> bundledSlot() {
        YamlConfiguration defaults = ArcadeConfigMigration.bundled();
        if (defaults == null) {
            return null;
        }
        for (Map<?, ?> row : defaults.getMapList("arcade.prizes")) {
            if ("home_slot".equals(String.valueOf(row.get("id")))) {
                Map<String, Object> m = new LinkedHashMap<>();
                row.forEach((k, v) -> m.put(String.valueOf(k), v));
                return m;
            }
        }
        return null;
    }
}
