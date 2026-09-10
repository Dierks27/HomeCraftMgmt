package com.dierks.homecraft.market;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A mutable, half-fillable working copy of one {@code market.catalog} row, used by the
 * admin GUI while a commodity is being added or edited.
 *
 * <p>{@link MarketItem} is deliberately NOT used for this: it is a record whose
 * {@link MarketItem#label()} dereferences {@code material} unguarded, so a row whose
 * material has not been picked yet cannot be modelled as one without risking an NPE on
 * every preview icon.
 *
 * <p>Setters store what they are given and never clamp. Clamping here would hide the
 * mistake from {@link #problems(Set)} and silently write a different number than the admin
 * typed — the config parser already does exactly that, and this form exists to surface it
 * instead. Everything {@code problems()} reports is blocking; an empty list means the row
 * will survive a round-trip through the parser unchanged.
 */
public final class MarketDraft {

    /** Ids are addressed from chat commands, so keep them to a shell-safe, case-free set. */
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9_]{1,32}");

    private String id;
    private Material material;
    private String displayName;
    private double floor = 1.0;
    private double ceiling = 100.0;
    private long initialStock;
    private long fullStock = 1024;
    private long maxDailySell;
    private long maxDailyBuy;

    /** A blank draft — the Add flow. */
    public MarketDraft() {
    }

    /** An existing commodity, loaded for editing. */
    public static MarketDraft from(MarketItem item) {
        MarketDraft d = new MarketDraft();
        d.id = item.id();
        d.material = item.material();
        d.displayName = item.displayName();
        d.floor = item.floor();
        d.ceiling = item.ceiling();
        d.initialStock = item.initialStock();
        d.fullStock = item.fullStock();
        d.maxDailySell = item.maxDailySell();
        d.maxDailyBuy = item.maxDailyBuy();
        return d;
    }

    public String id() {
        return id;
    }

    public Material material() {
        return material;
    }

    public String displayName() {
        return displayName;
    }

    public double floor() {
        return floor;
    }

    public double ceiling() {
        return ceiling;
    }

    public long initialStock() {
        return initialStock;
    }

    public long fullStock() {
        return fullStock;
    }

    public long maxDailySell() {
        return maxDailySell;
    }

    public long maxDailyBuy() {
        return maxDailyBuy;
    }

    /** Normalised on the way in, so the duplicate check and the write agree with the parser. */
    public void setId(String v) {
        this.id = v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    public void setMaterial(Material v) {
        this.material = v;
    }

    /** Blank (or the literal "none") clears it, so the label falls back to the material. */
    public void setDisplayName(String v) {
        String t = v == null ? "" : v.trim();
        this.displayName = t.isEmpty() || t.equalsIgnoreCase("none") ? null : t;
    }

    public void setFloor(double v) {
        this.floor = v;
    }

    public void setCeiling(double v) {
        this.ceiling = v;
    }

    public void setInitialStock(long v) {
        this.initialStock = v;
    }

    public void setFullStock(long v) {
        this.fullStock = v;
    }

    public void setMaxDailySell(long v) {
        this.maxDailySell = v;
    }

    public void setMaxDailyBuy(long v) {
        this.maxDailyBuy = v;
    }

    /** '&'-coded label for icons; never touches a null material. */
    public String label() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return material == null ? "(no material)" : material.name();
    }

    /**
     * Everything that would stop this row being written, as '&c'-coded display lines.
     * Empty means safe to save.
     *
     * @param otherIds every OTHER id already in the catalog, normalised lower-case
     */
    public List<String> problems(Set<String> otherIds) {
        List<String> out = new ArrayList<>();
        if (id == null || id.isBlank()) {
            out.add("&cAn id is required.");
        } else if (!ID.matcher(id).matches()) {
            out.add("&cId must be a-z, 0-9 or _ (max 32).");
        } else if (otherIds != null && otherIds.contains(id)) {
            out.add("&cAnother commodity already uses the id '" + id + "'.");
        }

        if (material == null) {
            out.add("&cPick a material.");
        } else if (material.isAir()) {
            // The parser accepts air; trading it hands the buyer nothing after charging them.
            out.add("&c" + material.name() + " is not a real item.");
        }
        // The broader "is this actually an item?" test is Material.isItem(), which resolves
        // through Bukkit's item-type registry and therefore needs a running server. It is
        // enforced where a material is chosen — MaterialPickerMenu only ever offers
        // isItem() materials — so this method stays pure and unit-testable.

        if (floor <= 0) {
            out.add("&cFloor must be above 0.");
        }
        if (ceiling <= floor) {
            out.add("&cCeiling must be above the floor.");
        }
        if (fullStock < 2) {
            out.add("&cFull stock must be at least 2.");
        }
        if (initialStock < 0) {
            out.add("&cInitial stock cannot be negative.");
        } else if (initialStock >= fullStock) {
            // The parser would clamp this to 85% and warn — write what the admin sees instead.
            out.add("&cInitial stock must be below full stock.");
        }
        if (maxDailySell < 0 || maxDailyBuy < 0) {
            out.add("&cDaily caps cannot be negative.");
        }
        return out;
    }

    /**
     * The row as {@code market.catalog} wants it: config.yml's own key order, and the types
     * the parser round-trips cleanly. {@code material} goes out as its name — never the enum,
     * which SnakeYAML would write as a class-tagged object the parser cannot read back.
     *
     * <p>Both cap keys are always written, a deliberate 0 included: the revision-4 migration
     * only fills a cap that is not already a number, so an omitted 0 would be silently
     * replaced by the shipped default on the next upgrade.
     */
    public Map<String, Object> toRow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("material", material == null ? null : material.name());
        if (displayName != null && !displayName.isBlank()) {
            m.put("display_name", displayName);
        }
        m.put("floor", floor);
        m.put("ceiling", ceiling);
        m.put("initial_stock", initialStock);
        m.put("full_stock", fullStock);
        m.put("max_daily_sell", maxDailySell);
        m.put("max_daily_buy", maxDailyBuy);
        return m;
    }
}
