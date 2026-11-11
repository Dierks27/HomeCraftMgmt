package com.dierks.homecraft.web;

import com.dierks.homecraft.mini.MiniDef;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code /api/minis} JSON: the Minis catalog with how many of each have been printed,
 * for the website's Minis page and the Minis on its home page. Built on the main thread
 * alongside the market feed and cached; the HTTP handler only serves the string.
 *
 * <p>One entry per {@link MiniDef}, in catalog (config) order:
 * <pre>{"generatedAt":ms,"minis":[{"id":…,"name":…,"rarity":…,"category":…,"series":…,
 * "cap":N,"printed":N,"soldOut":B[,"skin":"https://textures.minecraft.net/texture/…"]
 * [,"eventOnly":true[,"earnFrom":ms,"earnTo":ms]]},…]}</pre>
 * <ul>
 *   <li>{@code name} and {@code series} with legacy colour codes stripped;</li>
 *   <li>{@code rarity} the enum name ({@code COMMON} … {@code LEGENDARY});</li>
 *   <li>{@code category} upper-case, {@code MISC} when blank;</li>
 *   <li>{@code cap} {@code -1} for uncapped; {@code printed} the mint tally, never negative;</li>
 *   <li>{@code soldOut} — see {@link #soldOut};</li>
 *   <li>{@code skin} only when the texture holds a usable skin URL ({@link MiniSkin});</li>
 *   <li>{@code eventOnly} (0.38.8) only on a holiday Mini ({@code event_only: true}: earned at its own
 *       event, never from a pack, a Card, the Printer or a drop), always {@code true}; with it
 *       {@code earnFrom} and {@code earnTo}, the first and the last millisecond it can be earned
 *       (its {@code earn_from} day's first and its {@code earn_to} day's last, in
 *       {@code clock.time_zone}). The two dates are left out together when either is not set, they
 *       are back to front, or the builder was given no zone. An ordinary Mini has none of the
 *       three, and is written byte for byte as before.</li>
 * </ul>
 *
 * <p><b>Privacy:</b> catalog facts and counts only. Nothing here can name a player — no
 * owners or holders, no UUIDs, no balances, no per-copy provenance — and nothing else from
 * the definition (price, tags, texture) is published. The builder is only ever handed the
 * definitions and a per-Mini count, so it has no player data to leak.
 *
 * <p>No Bukkit — unit-tested without a server.
 */
public final class MinisFeed {

    private MinisFeed() {
    }

    /**
     * Capped and every copy printed. A negative cap is uncapped and never sells out; a cap of
     * {@code 0} is sold out from the start; more printed than the cap (an admin mint) is still
     * sold out.
     */
    public static boolean soldOut(long cap, long printed) {
        return cap >= 0 && printed >= cap;
    }

    /**
     * The feed as compact JSON (no whitespace).
     *
     * @param generatedAt when this snapshot was built (epoch ms)
     * @param defs        the catalog, in the order to publish; {@code null} gives an empty list
     * @param printed     Mini id → copies minted so far; a missing (or {@code null}) entry is 0
     */
    public static String json(long generatedAt, Collection<MiniDef> defs, Map<String, Long> printed) {
        return json(generatedAt, defs, printed, null);
    }

    /**
     * The feed with a holiday Mini's earn dates as epoch milliseconds in {@code zone}
     * ({@code clock.time_zone}); with a {@code null} zone a holiday Mini carries {@code eventOnly} alone.
     */
    public static String json(long generatedAt, Collection<MiniDef> defs, Map<String, Long> printed, ZoneId zone) {
        int count = defs == null ? 0 : defs.size();
        StringBuilder sb = new StringBuilder(Math.max(1024, 64 + count * 256));
        sb.append('{');
        sb.append("\"generatedAt\":").append(generatedAt).append(',');
        sb.append("\"minis\":[");
        boolean first = true;
        if (defs != null) {
            for (MiniDef def : defs) {
                if (def == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                mini(sb, def, printedCount(printed, def.id()), zone);
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void mini(StringBuilder sb, MiniDef def, long printed, ZoneId zone) {
        long cap = def.cap() < 0 ? -1 : def.cap();
        sb.append('{');
        sb.append("\"id\":").append(Json.string(def.id())).append(',');
        sb.append("\"name\":").append(Json.string(Json.plain(def.name()))).append(',');
        sb.append("\"rarity\":").append(Json.string(def.rarity() == null ? "COMMON" : def.rarity().name())).append(',');
        sb.append("\"category\":").append(Json.string(category(def.category()))).append(',');
        sb.append("\"series\":").append(Json.string(Json.plain(def.series()))).append(',');
        sb.append("\"cap\":").append(cap).append(',');
        sb.append("\"printed\":").append(printed).append(',');
        sb.append("\"soldOut\":").append(soldOut(cap, printed));
        Optional<String> skin = MiniSkin.url(def.texture());
        if (skin.isPresent()) {
            sb.append(",\"skin\":").append(Json.string(skin.get()));
        }
        if (def.eventOnly()) {
            sb.append(",\"eventOnly\":true");
            LocalDate from = def.earnFrom();
            LocalDate to = def.earnTo();
            if (zone != null && from != null && to != null && !to.isBefore(from)) {
                sb.append(",\"earnFrom\":").append(dayStart(from, zone));
                sb.append(",\"earnTo\":").append(dayStart(to.plusDays(1), zone) - 1);
            }
        }
        sb.append('}');
    }

    /** The first millisecond of a local day. */
    private static long dayStart(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static String category(String raw) {
        String c = Json.plain(raw).toUpperCase(Locale.ROOT);
        return c.isBlank() ? "MISC" : c;
    }

    private static long printedCount(Map<String, Long> printed, String id) {
        if (printed == null || id == null) {
            // Map.of() and friends refuse a null key outright.
            return 0;
        }
        Long n = printed.get(id);
        return n == null ? 0 : Math.max(0, n);
    }
}
