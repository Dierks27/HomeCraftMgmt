package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Computed collectible value (Phase 10, Part F). A printed Mini's value is
 * {@code base(rarity) × grade_mult × finish_mult × scarcity_factor}, blended with
 * real market data (last sale) when available. A Card's value is a simpler
 * sealed-collectible figure from its rarity and what it can print. Used for info
 * tooltips and as the suggested floor in Vending/Auction listings.
 */
public final class MiniValue {

    /** A value breakdown: the pure formula value, the market-blended value, and last sale (nullable). */
    public record Value(double computed, double blended, Double lastSale) {
    }

    private static final double FINISH_SHINY_MULT = 1.5;

    private final HomeCraftManagement plugin;

    public MiniValue(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Full value of a specific printed Mini (its grade + finish), blended with last sale. */
    public Value miniValue(MiniDef def, Grade grade, boolean shiny) {
        double base = base(def);
        double gradeMult = grade != null ? grade.valueMultiplier() : 1.0;
        double finishMult = shiny ? FINISH_SHINY_MULT : 1.0;
        double computed = base * gradeMult * finishMult * scarcity(def);
        Double last = plugin.miniService().lastSalePrice(def.id());
        double blended = last != null ? computed * 0.5 + last * 0.5 : computed;
        return new Value(computed, blended, last);
    }

    /** Convenience: the market-blended value for a specific grade/finish. */
    public double blended(MiniDef def, Grade grade, boolean shiny) {
        return miniValue(def, grade, shiny).blended();
    }

    /** The suggested resale floor for a copy (the blended value, never below the base). */
    public double suggestedFloor(MiniDef def, Grade grade, boolean shiny) {
        return Math.max(base(def), miniValue(def, grade, shiny).blended());
    }

    /**
     * The suggested resale floor for a held Mini item, read from its PDC (grade +
     * finish), or null if the item isn't a minted Mini. Used to hint listing prices.
     */
    public Double suggestedFloorFor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(Keys.MINI_ID, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return null;
        }
        Grade grade = Grade.parse(pdc.get(Keys.MINI_GRADE, PersistentDataType.STRING));
        boolean shiny = "SHINY".equalsIgnoreCase(pdc.get(Keys.MINI_FINISH, PersistentDataType.STRING));
        return suggestedFloor(def, grade, shiny);
    }

    /** A sealed Card's value — a fraction of the base, lifted by scarcity of its Mini. */
    public double cardValue(MiniDef def) {
        return Math.max(1, base(def) * 0.5 * scarcity(def));
    }

    /** The Gray→Gold value range for a Mini type (for the Museum appraisal plaque). */
    public double[] gradeRange(MiniDef def) {
        return new double[] {
                miniValue(def, Grade.GRAY, false).computed(),
                miniValue(def, Grade.GOLD, false).computed()
        };
    }

    /** base(rarity): the Mini's own mint price, falling back to its rarity's default. */
    private double base(MiniDef def) {
        double p = def.price();
        if (p <= 0) {
            p = plugin.miniService().style(def.rarity()).defaultPrice();
        }
        return Math.max(1, p);
    }

    /** scarcity_factor(circulation vs cap): fewer in circulation ⇒ rarer ⇒ pricier (1.0–2.0). */
    private double scarcity(MiniDef def) {
        if (def.uncapped() || def.cap() <= 0) {
            return 1.0;
        }
        long cap = def.cap();
        long circ = plugin.miniService().counts(def.id()).circulation();
        double f = 1.0 + (double) (cap - circ) / cap;
        return Math.max(1.0, Math.min(2.0, f));
    }
}
