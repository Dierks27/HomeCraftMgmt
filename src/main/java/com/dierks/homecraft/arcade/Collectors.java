package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.CapLook;
import com.dierks.homecraft.config.PluginConfig.Collector;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The collectors: kept for good, sold only in their season, stamped with the year. Append only.
 *
 * <p>They live here, in the jar, rather than as {@code arcade.prizes} rows, so an owned collector
 * trail can never lose its row (an owned trail shows only while a row with its id exists), and a
 * 2026 collector's price and season can never be edited. The year is in the id as well as the name:
 * ownership is keyed by id, so next year's cap is a new id and a second collectible, not a re-buy of
 * this one. The owner's two switches are {@code arcade.collectors.on_sale} and {@code gifting}.
 */
public final class Collectors {

    private Collectors() {
    }

    /** Every collector id ever shipped. Never remove or rename one: an owned trail shows only while its row exists. */
    public static final List<String> SHIPPED_IDS = List.of("cap_pumpkin_2026", "trail_leaves_2026");

    public static final List<Prize> ROWS = List.of(
            new Prize("cap_pumpkin_2026", PrizeTab.COSMETICS, "&6Pumpkin Cap 2026",
                    List.of("A pumpkin cap for Halloween!"), 250, new Icon("", Material.LEATHER_HELMET),
                    PrizeType.HAT, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                    1, null, null, null, null, 0, 0, null, 0, "",
                    List.of(), List.of(), null, null, List.of(),
                    new Collector(2026, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), true,
                            new CapLook(0xF9801D, "wild", "emerald"))),
            new Prize("trail_leaves_2026", PrizeTab.COSMETICS, "&6Autumn Leaves 2026",
                    List.of("Autumn leaves follow you!"), 400, new Icon("", leaves()),
                    PrizeType.TRAIL, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                    1, null, null, null, null, 0, 0, "ORANGE_POPLAR_LEAVES", 36500, "",
                    List.of(), List.of(), null, null, List.of(),
                    new Collector(2026, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), true, null)));

    /**
     * The Autumn Leaves trail's icon: the orange poplar leaves block, looked up by name because it is new in
     * the 26.3 API and the plugin compiles against 26.2's (build.gradle); on 26.2 a plain leaves block stands in
     * (there the trail's particle is missing too, and the collector self-test says so at enable).
     */
    static Material leaves() {
        Material poplar = Material.matchMaterial("ORANGE_POPLAR_LEAVES");
        return poplar != null ? poplar : Material.OAK_LEAVES;
    }

    /** True for an id the catalogue owns (case-insensitive). */
    public static boolean isCollectorId(String id) {
        if (id == null) {
            return false;
        }
        for (String shipped : SHIPPED_IDS) {
            if (shipped.equalsIgnoreCase(id.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for an id no config row may use: a collector's, or a Halloween candy or bag id (those are
     * known by their own item key, so a config row with such an id could never be what it says).
     */
    public static boolean reserved(String id) {
        if (id == null) {
            return false;
        }
        String n = id.trim().toLowerCase(Locale.ROOT);
        return isCollectorId(n) || n.startsWith("candy_") || n.startsWith("bag_trick_or_treat_");
    }

    /** The one warning for a config row {@link #reserved} skips. */
    public static String skipWarning(String id) {
        return "Arcade prize '" + id + "' is one of the plugin's own collectors - your row is skipped "
                + "(collectors are built in; see arcade.collectors).";
    }
}
