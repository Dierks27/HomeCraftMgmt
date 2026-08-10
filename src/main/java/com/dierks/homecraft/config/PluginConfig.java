package com.dierks.homecraft.config;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Typed view over {@code config.yml}. Phase 1 fully wires the {@code crafting}
 * section (Workbench + PC definitions and their reloadable, empty-by-default
 * recipes). The {@code market}/{@code shipping}/{@code minis} sections are left
 * as raw config for later phases.
 *
 * <p>{@link #load()} is safe to call repeatedly — it re-reads the current
 * {@link FileConfiguration}, so {@code /hcm reload} just re-invokes it.
 */
public final class PluginConfig {

    /** SHAPED = 3x3 grid via shape+symbols; SHAPELESS = unordered ingredient list. */
    public enum RecipeType {SHAPED, SHAPELESS}

    /** One shapeless ingredient: a material and how many are required. */
    public record Ingredient(Material material, int amount) {
    }

    /** A vanilla-style shaped recipe (used for the bootstrap Workbench recipe). */
    public record Shaped(List<String> shape, Map<Character, Material> ingredients) {
        public boolean isEmpty() {
            return shape == null || shape.isEmpty();
        }
    }

    /** The PC's Workbench recipe: shaped or shapeless, empty until an admin fills it. */
    public record PcRecipe(RecipeType type,
                           List<String> shape,
                           Map<Character, Material> ingredients,
                           List<Ingredient> shapeless) {
        public boolean isEmpty() {
            return type == RecipeType.SHAPED
                    ? (shape == null || shape.isEmpty())
                    : (shapeless == null || shapeless.isEmpty());
        }
    }

    public record Workbench(Material baseBlock, String displayName, List<String> lore, Shaped recipe) {
    }

    public record Pc(Material baseBlock, String displayName, List<String> lore, String headTexture, PcRecipe recipe) {
    }

    /** A daily sell allowance (0 = unlimited on that axis). */
    public record RankLimit(String permission, double maxMoneyPerDay, long maxUnitsPerDay) {
    }

    /** Anti-whale daily sell limits, with an optional per-permission override table. */
    public record SellLimits(boolean enabled, double maxMoneyPerDay, long maxUnitsPerDay,
                             String bypassPermission, List<RankLimit> ranks) {
    }

    /** The finite-stock market tuning + catalog (Phase 2.5). */
    public record Market(double elasticity, double inertia, double spread,
                         List<MarketItem> catalog, SellLimits sellLimits,
                         int priceHistoryIntervalMinutes) {
    }

    /** How shipping is priced. */
    public enum ShippingMode {PERCENTAGE, FLAT}

    /**
     * One shipping tier. {@code realHours} is the real-time delivery delay;
     * {@code primeFlat} forces a flat fee regardless of order size (Prime-style).
     */
    public record ShippingTier(String id, String label, double realHours,
                               double percent, double flat, boolean primeFlat) {
    }

    /** Amazon shipping config (Phase 3). Tiers ordered cheapest → fastest. */
    public record Shipping(ShippingMode mode, List<ShippingTier> tiers) {
    }

    private final HomeCraftManagement plugin;
    private final Logger log;

    private boolean respectTownPerms = true;
    private Workbench workbench;
    private Pc pc;
    private Market market;
    private Shipping shipping;

    public PluginConfig(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public boolean respectTownPerms() {
        return respectTownPerms;
    }

    public Workbench workbench() {
        return workbench;
    }

    public Pc pc() {
        return pc;
    }

    public Shipping shipping() {
        return shipping;
    }

    public Market market() {
        return market;
    }

    /** (Re)parse config.yml into the typed views above. */
    public void load() {
        FileConfiguration c = plugin.getConfig();

        this.respectTownPerms = c.getBoolean("crafting.respect_town_perms", true);

        // ---- Workbench ----
        Material wbBase = material(c.getString("crafting.workbench.base_block"), Material.CRAFTER, "crafting.workbench.base_block");
        String wbName = c.getString("crafting.workbench.display_name", "&6Mini Workbench");
        List<String> wbLore = c.getStringList("crafting.workbench.lore");
        Shaped wbRecipe = readShaped(c, "crafting.workbench.recipe");
        this.workbench = new Workbench(wbBase, wbName, wbLore, wbRecipe);

        // ---- PC ----
        Material pcBase = material(c.getString("crafting.pc.base_block"), Material.PLAYER_HEAD, "crafting.pc.base_block");
        String pcName = c.getString("crafting.pc.display_name", "&bPersonal Computer");
        List<String> pcLore = c.getStringList("crafting.pc.lore");
        String texture = c.getString("crafting.pc.head_texture", "");
        PcRecipe pcRecipe = readPcRecipe(c, "crafting.pc.recipe");
        this.pc = new Pc(pcBase, pcName, pcLore, texture, pcRecipe);

        // ---- Market (Phase 2.5 — finite stock) ----
        this.market = readMarket(c);

        // ---- Shipping (Phase 3) ----
        this.shipping = readShipping(c);
    }

    private Shipping readShipping(FileConfiguration c) {
        ShippingMode mode;
        try {
            mode = ShippingMode.valueOf(c.getString("shipping.mode", "PERCENTAGE").toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warning("Invalid shipping.mode; defaulting to PERCENTAGE.");
            mode = ShippingMode.PERCENTAGE;
        }
        List<ShippingTier> tiers = new ArrayList<>();
        // Ordered cheapest → fastest for display.
        tiers.add(readTier(c, "three_day", "3-Day", 72));
        tiers.add(readTier(c, "two_day", "2-Day", 48));
        tiers.add(readTier(c, "one_day", "1-Day", 24));
        return new Shipping(mode, tiers);
    }

    private ShippingTier readTier(FileConfiguration c, String key, String label, double defaultHours) {
        String base = "shipping.tiers." + key;
        double hours = c.getDouble(base + ".real_hours", defaultHours);
        double percent = c.getDouble(base + ".percent", 0);
        double flat = c.getDouble(base + ".flat", 0);
        boolean prime = c.getBoolean(base + ".prime_flat", false);
        return new ShippingTier(key, label, Math.max(0, hours), Math.max(0, percent), Math.max(0, flat), prime);
    }

    private Market readMarket(FileConfiguration c) {
        double elasticity = c.getDouble("market.elasticity", 1.0);
        double inertia = c.getDouble("market.inertia", 0.2);
        double spread = c.getDouble("market.spread", 0.10);
        long defaultFullStock = c.getLong("market.default_full_stock", 1024);
        int historyMinutes = c.getInt("market.price_history.interval_minutes", 30);

        List<MarketItem> catalog = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> row : c.getMapList("market.catalog")) {
            Object idObj = row.get("id");
            Object matObj = row.get("material");
            if (idObj == null || matObj == null) {
                log.warning("Skipping market.catalog entry missing 'id' or 'material'.");
                continue;
            }
            String id = String.valueOf(idObj).trim().toLowerCase();
            if (id.isEmpty() || !seen.add(id)) {
                log.warning("Skipping market.catalog entry with empty/duplicate id '" + id + "'.");
                continue;
            }
            Material material = material(String.valueOf(matObj), null, "market.catalog[" + id + "].material");
            if (material == null) {
                continue;
            }
            String displayName = row.get("display_name") != null ? String.valueOf(row.get("display_name")) : null;

            double floor = number(row.get("floor"), 0.0);
            double ceiling = number(row.get("ceiling"), Math.max(floor, 1.0) * 100.0);
            if (floor < 0) {
                floor = 0;
            }
            if (ceiling < floor) {
                log.warning("market.catalog[" + id + "] ceiling < floor; raising ceiling to floor.");
                ceiling = floor;
            }
            long initialStock = Math.max(0L, (long) number(row.get("initial_stock"), 0));
            long fullStock = (long) number(row.get("full_stock"), defaultFullStock);
            if (fullStock < 1) {
                fullStock = 1; // avoid divide-by-zero in the pricing curve
            }

            catalog.add(new MarketItem(id, material, displayName, floor, ceiling, initialStock, fullStock));
        }
        return new Market(elasticity, inertia, spread, catalog, readSellLimits(c), Math.max(1, historyMinutes));
    }

    private SellLimits readSellLimits(FileConfiguration c) {
        boolean enabled = c.getBoolean("market.sell_limits.enabled", true);
        double maxMoney = c.getDouble("market.sell_limits.max_money_per_day", 0);
        long maxUnits = c.getLong("market.sell_limits.max_units_per_day", 0);
        String bypass = c.getString("market.sell_limits.bypass_permission", "hcm.market.limit.bypass");

        List<RankLimit> ranks = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("market.sell_limits.ranks")) {
            Object perm = row.get("permission");
            if (perm == null || String.valueOf(perm).isBlank()) {
                continue;
            }
            ranks.add(new RankLimit(
                    String.valueOf(perm),
                    number(row.get("max_money_per_day"), 0),
                    (long) number(row.get("max_units_per_day"), 0)));
        }
        return new SellLimits(enabled, maxMoney, maxUnits, bypass, ranks);
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private Shaped readShaped(FileConfiguration c, String path) {
        List<String> shape = c.getStringList(path + ".shape");
        Map<Character, Material> ing = readSymbolMap(c, path + ".ingredients");
        return new Shaped(shape, ing);
    }

    private PcRecipe readPcRecipe(FileConfiguration c, String path) {
        RecipeType type;
        try {
            type = RecipeType.valueOf(c.getString(path + ".type", "SHAPED").toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warning("Invalid recipe type at " + path + ".type; defaulting to SHAPED.");
            type = RecipeType.SHAPED;
        }
        List<String> shape = c.getStringList(path + ".shape");
        Map<Character, Material> ing = readSymbolMap(c, path + ".ingredients");

        List<Ingredient> shapeless = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList(path + ".ingredients")) {
            // Only relevant when ingredients is a LIST (shapeless); symbol maps are handled above.
            Object mat = row.get("material");
            if (mat == null) {
                continue;
            }
            Material m = material(String.valueOf(mat), null, path + ".ingredients");
            if (m == null) {
                continue;
            }
            int amount = row.get("amount") instanceof Number n ? n.intValue() : 1;
            shapeless.add(new Ingredient(m, Math.max(1, amount)));
        }
        return new PcRecipe(type, shape, ing, shapeless);
    }

    /** Reads an {@code ingredients} section shaped like {@code A: IRON_INGOT} into a char->Material map. */
    private Map<Character, Material> readSymbolMap(FileConfiguration c, String path) {
        Map<Character, Material> out = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection(path);
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            if (key.isEmpty()) {
                continue;
            }
            Material m = material(sec.getString(key), null, path + "." + key);
            if (m != null) {
                out.put(key.charAt(0), m);
            }
        }
        return out;
    }

    private Material material(String name, Material fallback, String where) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim().toUpperCase());
        if (m == null) {
            log.warning("Unknown material '" + name + "' at " + where + (fallback != null ? "; using " + fallback : ""));
            return fallback;
        }
        return m;
    }
}
