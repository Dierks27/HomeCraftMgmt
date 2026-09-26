package com.dierks.homecraft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 14: the token economy (§3.9). Tokens stop touching money, the Prize Counter
 * gains tabs and real prizes, quests become per-player draws from a pool, and achievements
 * become a list.
 *
 * <p>Every rule is the same one the earlier revisions follow: a value that still holds what this
 * plugin shipped is ours, and is replaced; a value the admin changed is theirs, and is kept with a
 * {@link HomeCraftManagement#WARN} line that names it. The only exception is money in the Arcade —
 * a Scratch Ticket that costs or pays dollars cannot be kept whoever wrote it, so its table is
 * logged and replaced.
 *
 * <p>The new rows are read from the bundled config.yml rather than written out again here: a list
 * of maps is one value to the backfill, so these lists must be written by the migration, and
 * reading them from the file they ship in means the two can never disagree.
 */
final class ArcadeConfigMigration {

    /** The Starter Crate's reward tables as shipped, oldest first (the first was all money). */
    private static final List<List<Map<String, Object>>> SHIPPED_STARTER_REWARDS = List.of(
            List.of(row("type", "money", "amount", 100, "weight", 50),
                    row("type", "item", "material", "DIAMOND", "amount", 1, "weight", 20),
                    row("type", "item", "material", "GOLD_INGOT", "amount", 4, "weight", 20),
                    row("type", "mini", "mini", "piggy_mini", "weight", 5)),
            List.of(row("type", "card", "tag", "starter", "weight", 40),
                    row("type", "filament", "amount", 3, "weight", 30),
                    row("type", "pack", "pack", "starter", "weight", 20),
                    row("type", "mini", "tag", "starter", "weight", 10)));

    /** The money Scratch Ticket as shipped. */
    private static final List<Map<String, Object>> SHIPPED_MONEY_PAYOUTS = List.of(
            row("amount", 0, "weight", 50), row("amount", 100, "weight", 30),
            row("amount", 500, "weight", 15), row("amount", 2000, "weight", 5));

    /** The three Prize Counter rows revision 8 shipped: id → the fields that make it ours. */
    private static final Map<String, Map<String, Object>> SHIPPED_PRIZES = Map.of(
            "filament_bundle", row("cost_tokens", 6, "type", "filament", "amount", 8),
            "display_case", row("cost_tokens", 25, "type", "block", "block", "display_case"),
            "starter_pack", row("cost_tokens", 30, "type", "pack", "pack", "starter"));

    /** Quest rows as they stood after revision 12: id → (type, target, reward). */
    private static final Map<String, Object[]> SHIPPED_DAILY = Map.of(
            "fish_daily", new Object[] {"CATCH_FISH", 8L, 3},
            "walk_daily", new Object[] {"TRAVEL_ON_FOOT", 800L, 3});
    private static final Map<String, Object[]> SHIPPED_WEEKLY = Map.of(
            "hostiles_weekly", new Object[] {"KILL_HOSTILES", 120L, 12},
            "breed_weekly", new Object[] {"BREED_ANIMALS", 12L, 8},
            "trade_weekly", new Object[] {"TRADE_VILLAGER", 15L, 7});

    private static final List<Integer> SHIPPED_STREAK = List.of(1, 1, 2, 2, 3, 3, 5);

    /** The six map-shaped achievements: id → {reward, display, threshold}. */
    private static final Map<String, Object[]> SHIPPED_ACHIEVEMENTS = legacyAchievements();

    private static Map<String, Object[]> legacyAchievements() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        m.put("first_mini", new Object[] {3, "First Mini Collected", 0.0});
        m.put("first_sale", new Object[] {2, "First Market Sale", 0.0});
        m.put("first_pc", new Object[] {2, "Built Your First PC", 0.0});
        m.put("first_crate", new Object[] {1, "Opened Your First Crate", 0.0});
        m.put("first_pack", new Object[] {2, "Opened Your First Pack", 0.0});
        m.put("rich_10k", new Object[] {5, "Reached $10,000", 10000.0});
        return m;
    }

    private ArcadeConfigMigration() {
    }

    /** Run revision 14 against {@code c}, appending what it did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        YamlConfiguration defaults = bundled();
        if (defaults == null) {
            log.add(WARN + "Config migration: could not read the bundled config.yml, so the Arcade "
                    + "section was not upgraded. Reinstall the jar; the old values still load.");
            return;
        }
        prizes(c, defaults, log);
        crates(c, defaults, log);
        lotto(c, defaults, log);
        HomeCraftManagement.retune(c, "arcade.pity.tokens", 25, defaults.getInt("arcade.pity.tokens", 150), log);
        streak(c, defaults, log);
        quests(c, defaults, "daily", SHIPPED_DAILY, log);
        quests(c, defaults, "weekly", SHIPPED_WEEKLY, log);
        achievements(c, defaults, log);
    }

    /** The bundled config.yml, or null when the jar has lost it. */
    static YamlConfiguration bundled() {
        try (InputStream in = HomeCraftManagement.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration y = new YamlConfiguration();
                y.load(reader);
                return y;
            }
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    // ---- Prize Counter ------------------------------------------------------------------

    /**
     * Our three old rows are replaced by their v2 versions (the starter pack row goes: packs are
     * bought in the pack shop), an admin's rows stay as they are, and every new default row whose
     * id is not already there is added — otherwise an upgraded server would keep a three-row
     * counter and never see a boost, a hat or a perk.
     */
    private static void prizes(FileConfiguration c, YamlConfiguration defaults, List<String> log) {
        if (!(c.get("arcade.prizes", null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        List<Map<String, Object>> fresh = mapRows(defaults.getList("arcade.prizes"));
        List<Map<String, Object>> current = mapRows(raw);
        if (!current.isEmpty() && current.size() == raw.size() && current.stream().allMatch(r ->
                SHIPPED_PRIZES.containsKey(idOf(r)) && matches(r, SHIPPED_PRIZES.get(idOf(r))))) {
            // Nothing here but our own rows: take the new counter whole, in its own order.
            c.set("arcade.prizes", defaults.getList("arcade.prizes"));
            log.add("Config migration: the Prize Counter has tabs now — boosts, hunt gear, cosmetics, "
                    + "perks, a trophy, the Rare Card and Card trade-in (" + fresh.size() + " rows). The "
                    + "starter pack row is gone: packs are bought in the pack shop.");
            return;
        }
        Map<String, Map<String, Object>> freshById = new LinkedHashMap<>();
        for (Map<String, Object> r : fresh) {
            freshById.put(idOf(r), r);
        }
        List<Object> out = new ArrayList<>();
        Set<String> present = new HashSet<>();
        int replaced = 0;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                out.add(o);
                continue;
            }
            Map<String, Object> r = copy(m);
            String id = idOf(r);
            Map<String, Object> shipped = SHIPPED_PRIZES.get(id);
            if (shipped != null && matches(r, shipped)) {
                if (freshById.containsKey(id)) {
                    out.add(freshById.get(id));
                    present.add(id);
                    replaced++;
                } else {
                    log.add("Config migration: removed the " + id + " Prize Counter row. Packs are bought "
                            + "in the pack shop now, for dollars or tokens.");
                }
                continue;
            }
            if (shipped != null && !(freshById.containsKey(id) && sameRows(List.of(r), List.of(freshById.get(id))))) {
                log.add(WARN + "Config migration: kept your arcade.prizes → " + id + " row because you have "
                        + "changed it" + (freshById.containsKey(id) ? " (the new default costs "
                        + freshById.get(id).get("cost_tokens") + " tokens)" : "") + ".");
            }
            out.add(r);
            present.add(id);
        }
        int added = 0;
        for (Map<String, Object> r : fresh) {
            if (present.add(idOf(r))) {
                out.add(r);
                added++;
            }
        }
        if (replaced > 0 || added > 0 || out.size() != raw.size()) {
            c.set("arcade.prizes", out);
            log.add("Config migration: the Prize Counter has tabs now — " + added + " new row(s) (boosts, "
                    + "hunt gear, cosmetics, perks, a trophy, the Rare Card and Card trade-in)"
                    + (replaced > 0 ? ", and " + replaced + " of the old rows moved to their new prices" : "")
                    + ". Rows you wrote yourself are unchanged.");
        }
    }

    // ---- crates -------------------------------------------------------------------------

    /**
     * The shipped Starter Crate goes and the Arcade Crate takes its place. Any crate the admin
     * made or changed stays, but loses {@code paid_odds}: a crate tier bought with dollars is the
     * money boundary §11 #9 now forbids.
     */
    private static void crates(FileConfiguration c, YamlConfiguration defaults, List<String> log) {
        ConfigurationSection cs = c.getConfigurationSection("arcade.crates");
        if (cs == null) {
            return;
        }
        for (String key : new ArrayList<>(cs.getKeys(false))) {
            String base = "arcade.crates." + key;
            if (key.equalsIgnoreCase("starter") && isShippedStarter(c, base)) {
                c.set(base, null);
                log.add("Config migration: replaced the Starter Crate with the Arcade Crate (15 tokens). "
                        + "It pays out boosts, hunt gear, trails and hats, with every Mini as the rare jackpot, "
                        + "instead of the Cards and packs that pushed Mini output.");
                continue;
            }
            if (c.contains(base + ".paid_odds")) {
                Object old = c.get(base + ".paid_odds");
                c.set(base + ".paid_odds", null);
                log.add(WARN + "Config migration: removed " + base + ".paid_odds (" + old + "). The Arcade no "
                        + "longer takes dollars, so a crate can't sell better odds for money. The rest of "
                        + "this crate is yours and is unchanged.");
            }
        }
        if (!c.contains("arcade.crates.arcade_crate")) {
            ConfigurationSection def = defaults.getConfigurationSection("arcade.crates.arcade_crate");
            if (def != null) {
                c.set("arcade.crates.arcade_crate.display", def.get("display"));
                c.set("arcade.crates.arcade_crate.cost_tokens", def.get("cost_tokens"));
                c.set("arcade.crates.arcade_crate.rewards", def.get("rewards"));
            }
        }
    }

    private static boolean isShippedStarter(FileConfiguration c, String base) {
        Object cost = c.get(base + ".cost_tokens", null);
        if (!(cost instanceof Number n) || (n.intValue() != 1 && n.intValue() != 5)) {
            return false;
        }
        List<Map<String, Object>> rewards = mapRows(c.getList(base + ".rewards"));
        for (List<Map<String, Object>> shipped : SHIPPED_STARTER_REWARDS) {
            if (sameRows(rewards, shipped)) {
                return true;
            }
        }
        return false;
    }

    // ---- Scratch Ticket ------------------------------------------------------------------

    /** Money in, money out becomes tokens in, tokens out. The old table is logged. */
    private static void lotto(FileConfiguration c, YamlConfiguration defaults, List<String> log) {
        boolean moneyTicket = c.contains("arcade.lotto.ticket_cost_money");
        List<Map<String, Object>> payouts = mapRows(c.getList("arcade.lotto.payouts"));
        boolean moneyPayouts = payouts.stream().anyMatch(r -> r.containsKey("amount") && !r.containsKey("tokens"));
        if (!moneyTicket && !moneyPayouts) {
            return;
        }
        Object ticket = c.get("arcade.lotto.ticket_cost_money");
        boolean shipped = (ticket == null || (ticket instanceof Number n && n.intValue() == 250))
                && sameRows(payouts, SHIPPED_MONEY_PAYOUTS);
        StringBuilder table = new StringBuilder();
        for (Map<String, Object> r : payouts) {
            if (!table.isEmpty()) {
                table.append(", ");
            }
            table.append('$').append(r.get("amount")).append(" ×").append(r.get("weight"));
        }
        c.set("arcade.lotto.ticket_cost_money", null);
        c.set("arcade.lotto.payouts", defaults.getList("arcade.lotto.payouts"));
        if (!c.contains("arcade.lotto.ticket_tokens")) {
            c.set("arcade.lotto.ticket_tokens", defaults.getInt("arcade.lotto.ticket_tokens", 10));
        }
        if (!c.contains("arcade.lotto.jackpot")) {
            c.set("arcade.lotto.jackpot.seed", defaults.getInt("arcade.lotto.jackpot.seed", 50));
            c.set("arcade.lotto.jackpot.per_ticket", defaults.getInt("arcade.lotto.jackpot.per_ticket", 1));
            c.set("arcade.lotto.jackpot.cap", defaults.getInt("arcade.lotto.jackpot.cap", 1000));
        }
        log.add((shipped ? "" : WARN) + "Config migration: the Scratch Ticket costs and pays tokens now "
                + "(10 a ticket, with a growing jackpot). The old money table was: ticket $"
                + (ticket == null ? "?" : ticket) + "; payouts " + (table.isEmpty() ? "none" : table)
                + (shipped ? "." : " — yours, but a ticket that pays dollars can't be kept."));
    }

    // ---- login streak ---------------------------------------------------------------------

    private static void streak(FileConfiguration c, YamlConfiguration defaults, List<String> log) {
        String path = "arcade.tokens.login_streak.rewards";
        if (!c.isList(path)) {
            return;
        }
        List<Integer> current = c.getIntegerList(path);
        List<Integer> fresh = defaults.getIntegerList(path);
        if (current.equals(SHIPPED_STREAK)) {
            c.set(path, fresh);
            log.add("Config migration: " + path + " " + SHIPPED_STREAK + " → " + fresh + ".");
        } else if (!current.equals(fresh)) {
            log.add(WARN + "Config migration: kept " + path + " = " + current + " because you have changed it "
                    + "(the new default is " + fresh + ").");
        }
    }

    // ---- quests -------------------------------------------------------------------------

    /**
     * {@code quests.daily} becomes {@code quests.daily_pool} (and the same for weekly). A list
     * that is exactly what we shipped becomes the new pool; an admin's own list becomes the pool
     * as it is, and each player now draws from it.
     */
    private static void quests(FileConfiguration c, YamlConfiguration defaults, String period,
                               Map<String, Object[]> shipped, List<String> log) {
        String old = "arcade.quests." + period;
        String pool = "arcade.quests." + period + "_pool";
        if (!c.isList(old)) {
            return;
        }
        if (c.contains(pool)) {
            c.set(old, null);
            log.add("Config migration: removed " + old + " — " + pool + " is already there and is the one read.");
            return;
        }
        List<Map<String, Object>> rows = mapRows(c.getList(old));
        boolean ours = !rows.isEmpty() && rows.size() == shipped.size();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object[] s = shipped.get(idOf(r));
            if (s == null || !seen.add(idOf(r))
                    || !String.valueOf(r.get("type")).trim().equalsIgnoreCase((String) s[0])
                    || !(r.get("target") instanceof Number t) || t.longValue() != (Long) s[1]
                    || !(r.get("reward") instanceof Number w) || w.intValue() != (Integer) s[2]) {
                ours = false;
                break;
            }
        }
        c.set(old, null);
        if (ours) {
            c.set(pool, defaults.getList(pool));
            log.add("Config migration: " + old + " became " + pool + " — each player now draws "
                    + defaults.getInt("arcade.quests." + period + "_draw") + " " + period + " quests of "
                    + "different kinds from a bigger pool (farming, cooking, mining, biomes, deliveries).");
        } else {
            c.set(pool, rows);
            log.add(WARN + "Config migration: your " + old + " list is now " + pool + ", unchanged. Each "
                    + "player draws " + period + "_draw quests from it, no two of the same type — a pool "
                    + "with fewer types than that hands out fewer quests.");
        }
    }

    // ---- achievements ---------------------------------------------------------------------

    /**
     * The six-entry map becomes the 26-row list. Our six keep their ids — so every unlock already
     * earned still counts — and take the new rewards only where the old reward is still ours.
     */
    private static void achievements(FileConfiguration c, YamlConfiguration defaults, List<String> log) {
        ConfigurationSection old = c.getConfigurationSection("arcade.achievements");
        if (old == null) {
            return; // absent, or already a list
        }
        List<Map<String, Object>> fresh = mapRows(defaults.getList("arcade.achievements"));
        Set<String> known = SHIPPED_ACHIEVEMENTS.keySet();
        for (String key : old.getKeys(false)) {
            if (!known.contains(key.toLowerCase(Locale.ROOT))) {
                log.add(WARN + "Config migration: dropped arcade.achievements." + key + " — nothing ever "
                        + "fired it. Add it back as a list row with a type if you want it.");
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : fresh) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            String id = idOf(row);
            Object[] shipped = SHIPPED_ACHIEVEMENTS.get(id);
            ConfigurationSection mine = shipped == null ? null : old.getConfigurationSection(id);
            if (mine != null) {
                List<String> kept = new ArrayList<>();
                if (mine.get("reward") instanceof Number n && n.intValue() != (Integer) shipped[0]) {
                    row.put("reward", n.intValue());
                    kept.add("reward " + n.intValue());
                }
                String display = mine.getString("display");
                if (display != null && !display.equals(shipped[1])) {
                    row.put("display", display);
                    kept.add("display \"" + display + "\"");
                }
                if (mine.get("threshold") instanceof Number t && t.doubleValue() != (Double) shipped[2]) {
                    row.put("target", t.doubleValue() == Math.rint(t.doubleValue()) ? (Object) t.longValue() : t);
                    kept.add("threshold " + t);
                }
                if (!mine.getBoolean("enabled", true)) {
                    row.put("enabled", false);
                    kept.add("disabled");
                }
                if (!kept.isEmpty()) {
                    log.add(WARN + "Config migration: kept your " + id + " achievement settings ("
                            + String.join(", ", kept) + ") in the new list.");
                }
            }
            out.add(row);
        }
        c.set("arcade.achievements", out);
        log.add("Config migration: arcade.achievements is a list of " + out.size() + " now, in groups "
                + "(Getting Started, Mini Hunter, Collector, Adventure, Dedication, Work, Money, Arcade). "
                + "The six old ids are kept, so every unlock already earned still counts.");
    }

    // ---- helpers --------------------------------------------------------------------------

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static List<Map<String, Object>> mapRows(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                out.add(copy(m));
            } else if (o instanceof ConfigurationSection s) {
                out.add(copy(s.getValues(false)));
            }
        }
        return out;
    }

    private static String idOf(Map<String, Object> r) {
        Object id = r.get("id");
        return id == null ? "" : String.valueOf(id).trim().toLowerCase(Locale.ROOT);
    }

    /** Every field in {@code shipped} is in {@code r} with the same value (numbers by value). */
    private static boolean matches(Map<String, Object> r, Map<String, Object> shipped) {
        for (Map.Entry<String, Object> e : shipped.entrySet()) {
            if (!same(r.get(e.getKey()), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Two lists of rows with the same fields and values, in the same order. */
    private static boolean sameRows(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).keySet().equals(b.get(i).keySet()) || !matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof String x && b instanceof String y) {
            return x.trim().equalsIgnoreCase(y.trim());
        }
        return Objects.equals(a, b);
    }
}
