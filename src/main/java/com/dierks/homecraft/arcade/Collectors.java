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
    public static final List<String> SHIPPED_IDS = List.of("cap_pumpkin_2026", "trail_leaves_2026",
            "cap_santa_2026", "trail_twinkle_2026");

    /** A collector trail's days: kept for good, 100 years, never a Long.MAX_VALUE that overflows. */
    static final int KEPT_DAYS = 36500;

    public static final List<Prize> ROWS = List.of(
            cap("cap_pumpkin_2026", "&6Pumpkin Cap 2026", "A pumpkin cap for Halloween!", 250, 2026,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new CapLook(0xF9801D, "wild", "emerald")),
            trail("trail_leaves_2026", "&6Autumn Leaves 2026", "Autumn leaves follow you!", 400, 2026,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), "ORANGE_POPLAR_LEAVES", leaves()),
            cap("cap_santa_2026", "&cSanta Cap 2026", "A cozy Santa cap for Christmas!", 250, 2026,
                    LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), new CapLook(0xB02E26, "rib", "quartz")),
            // the season runs into the new year: inSeason compares whole dates, so 6 Jan 2027 is still in it
            trail("trail_twinkle_2026", "&bTwinkle 2026", "Twinkly lights follow you!", 400, 2026,
                    LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 6), "ELECTRIC_SPARK", Material.SEA_LANTERN));

    /**
     * A collector cap: a Looks row (0.38; it was on Cosmetics before the Looks tab), one each for good
     * (LIFETIME 1), giftable, with a leather helmet as its icon and {@code look} as the cap. Its season name
     * comes from {@code from}'s month. The tab only says where its tile is drawn: nothing stores it.
     */
    static Prize cap(String id, String display, String desc, int price, int year, LocalDate from, LocalDate to,
                     CapLook look) {
        return new Prize(id, PrizeTab.LOOKS, display, List.of(desc), price, new Icon("", Material.LEATHER_HELMET),
                PrizeType.HAT, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                1, null, null, null, null, 0, 0, null, 0, "",
                List.of(), List.of(), null, null, List.of(),
                new Collector(year, from, to, true, look));
    }

    /**
     * A collector trail: a Cosmetics row, one each for good (LIFETIME 1, {@link #KEPT_DAYS}), giftable, drawn
     * with {@code particle} (one that takes no data) and shown as {@code icon}. Its season name comes from
     * {@code from}'s month.
     */
    static Prize trail(String id, String display, String desc, int price, int year, LocalDate from, LocalDate to,
                       String particle, Material icon) {
        return new Prize(id, PrizeTab.COSMETICS, display, List.of(desc), price, new Icon("", icon),
                PrizeType.TRAIL, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                1, null, null, null, null, 0, 0, particle, KEPT_DAYS, "",
                List.of(), List.of(), null, null, List.of(),
                new Collector(year, from, to, true, null));
    }

    /** A season's colour on its collector line: Halloween gold, Christmas red, Spring pink, Summer yellow. */
    public static String seasonColour(String season) {
        if (season == null) {
            return "&6";
        }
        return switch (season) {
            case "Christmas" -> "&c";
            case "Spring" -> "&d";
            case "Summer" -> "&e";
            default -> "&6"; // Halloween, and any other
        };
    }

    /**
     * The collector line on a cap's lore and a collector's tile: "&amp;cChristmas 2026 collector". The year is
     * the collector's, not the year its season ends in.
     */
    public static String collectorLine(Collector c) {
        return seasonColour(c.season()) + c.season() + " " + c.year() + " collector";
    }

    /**
     * The Autumn Leaves trail's icon: the orange poplar leaves block, looked up by name because it is new in
     * the 26.3 API and the plugin compiles against 26.2's (build.gradle); on 26.2 a plain leaves block stands in
     * (there the trail's particle is missing too, so the trail is not sold or given, and the collector self-test
     * says so at enable).
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
