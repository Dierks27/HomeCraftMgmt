package com.dierks.homecraft.gui;

import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.MarketEvent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Every exact string the live market shows a player, in one place (spec §6.2, §6.3, §7.1 –
 * §7.4): tile suffixes and lore, sign / hologram / TV badges, the QuantityMenu extras, the chat
 * announcements, titles and action bars, the join catch-up, the news menu, the {@code @news}
 * board and the plain PlaceholderAPI forms.
 *
 * <p><b>Explicit values in, '&amp;'-coded strings out.</b> Nothing here reads the market, the
 * clock or the config. The caller hands over what it already knows:
 * <ul>
 *   <li>{@code name} is the plain singular label ({@code Iron Ingot}); {@code item} the plural
 *       news name ({@code Iron Ingots}) — see {@code Headlines.name}/{@code Headlines.plural};</li>
 *   <li>money ({@code usual}, {@code before}, {@code after}) arrives already formatted and
 *       uncoloured ({@code economy().format(x)}, not {@code Menus.money}) — the templates colour
 *       it;</li>
 *   <li>time arrives in words ({@code left} from {@code Headlines.left}, {@code ago} from
 *       {@code Headlines.ago});</li>
 *   <li>{@code pct} is the signed percent against the usual price ({@code 11.7} = +11.7%);</li>
 *   <li>{@code limit} is the event cap ({@code PriceMood.eventBuyCap/eventSellCap}); 0 or less
 *       means "no limit", and the "Limit N a day" part is left out.</li>
 * </ul>
 *
 * <p><b>Conventions.</b> A {@code String} method returns {@code ""} when there is nothing to
 * show; a {@code List} method returns an empty list; {@link #titleFor} is the one
 * {@link Optional}. A kind a renderer does not handle (a DEAL handed to {@link #newsLines}) gives
 * the empty value rather than throwing, so a mapping slip on the main thread costs one
 * announcement, not the tick. A {@code {pct}} in a template is a whole number in the direction
 * the badge claims — never a wrong sign: a HOT that drift has pulled below usual reads "+0%".
 *
 * <p><b>Glyphs.</b> Only ★ ✦ » « ▲ ▼ · • → … — every one below U+FFFF, so Bedrock players see
 * them too ({@code BedrockGlyphTest}, {@code MarketLabelsTest}).
 *
 * <p>Plain Java: depends only on {@link Badge}, {@link EventKind} and {@link MarketEvent}, so it
 * is unit-tested without a server.
 */
public final class MarketLabels {

    /** "Usually $X" shows on an unbadged item once its price is this many percent off usual. */
    public static final double USUAL_MIN_PCT = 3.0;
    /** A sign line holds 15 characters; {@link #signLine} never builds a longer one. */
    public static final int SIGN_LINE_MAX = 15;
    /** Store slot 47: the latest headline is cut to this many characters. */
    public static final int SLOT_HEADLINE_MAX = 34;
    /** MarketNewsMenu: a news entry's name (its headline) is cut to this many characters. */
    public static final int NEWS_ENTRY_NAME_MAX = 40;
    /** The {@code @news} board wraps at this many characters a line… */
    public static final int BOARD_WIDTH = 32;
    /** …and gives the headline at most this many lines. */
    public static final int BOARD_HEADLINE_LINES = 3;
    /** {@code %hcm_news%}: at most this many characters. */
    public static final int PAPI_NEWS_MAX = 60;
    /** Store slot 47 names at most this many HOT items. */
    public static final int HOT_NAMES_MAX = 3;
    /** Seasons: at most this many effects are listed before "…". */
    public static final int SEASON_EFFECTS_MAX = 3;
    /** MarketMenu slot 50 and the catch-up show at most this many recent summaries. */
    public static final int LATEST_MAX = 3;

    /** Title timing (§6.2). */
    public static final int TITLE_FADE_IN_MS = 300;
    public static final int TITLE_STAY_MS = 2500;
    public static final int TITLE_FADE_OUT_MS = 700;
    /** The action bar is sent at these tick offsets (about 5 s in all). */
    public static final List<Long> ACTION_BAR_TICKS = List.of(0L, 40L, 80L);

    /** The quiet-market line (PAPI {@code %hcm_news%}, the {@code @news} board, the news button). */
    public static final String CALM = "The Crate Market is calm today.";
    /** The stale-quote guard's refusal (MarketMenu sell flow, CheckoutMenu). */
    public static final String PRICES_MOVED = "&ePrices just moved! Take another look.";
    /** MarketMenu slot 50 and the picker's slot 0 (BELL). */
    public static final String NEWS_BUTTON = "&e» Market News";
    /** MarketMenu slot 50's last lore line. */
    public static final String NEWS_BUTTON_CLICK = "&eClick for all the news";
    /** MarketNewsMenu slot 4 (BELL). */
    public static final String NEWS_MENU_HEADER = "&e&l» Crate Market News";
    /** The {@code @news} board's first line. */
    public static final String BOARD_HEADER = "&e&l» CRATE NEWS «";
    /** Hover on an announcement's price line; its click runs {@link #priceCommand}. */
    public static final String PRICE_HOVER = "&7Click to see the price";
    /** {@code bindSign("@news")} refuses with this. */
    public static final String NEWS_NO_SIGN = "The news board needs a TV or a hologram - a sign is too small.";
    /** The join catch-up's first line. */
    public static final String CATCH_UP_HEADER = "&6&l» While you were away:";

    private static final String NEWS_FLASH_HEADER = "&e&l» NEWS FLASH «";
    private static final String HOT_HEADER = "&6&l★ HOT ITEM ★";
    private static final String DEAL_HEADER = "&a&l✦ DEAL ✦";
    private static final String WANTED_HEADER = "&e&l» MARKET NEWS «";
    private static final String SEASON_HEADER = "&2&l» SEASON NEWS «";
    private static final String REAL_HEADER = "&d&l» REAL-WORLD NEWS «";

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** Legacy '&amp;'/'§' colour and format codes (the same pattern {@code Json.plain} strips). */
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    private MarketLabels() {
    }

    /** A title and its subtitle, both '&amp;'-coded. */
    public record Title(String title, String subtitle) {
    }

    // ---- tiles (Store and Sell screens, §7.1) ----------------------------------------------

    /**
     * Appended to the tile's name: {@code " &6&l★ HOT"}, {@code " &a&l✦ DEAL"}, {@code " &a▲"},
     * {@code " &c▼"}; WANTED only on the sell screen ({@code " &e&l» WANTED"}) — the store keeps
     * its "OUT OF STOCK".
     */
    public static String nameSuffix(Badge badge, boolean sellScreen) {
        return switch (orNone(badge)) {
            case HOT -> " &6&l★ HOT";
            case DEAL -> " &a&l✦ DEAL";
            case UP -> " &a▲";
            case DOWN -> " &c▼";
            case WANTED -> sellScreen ? " &e&l» WANTED" : "";
            case NONE -> "";
        };
    }

    /** HOT and DEAL tiles shimmer ({@code Menus.glint(item, glint(badge))}). */
    public static boolean glint(Badge badge) {
        return badge == Badge.HOT || badge == Badge.DEAL;
    }

    /**
     * The Store (buy) tile's extra lore, inserted right after the price line.
     *
     * @param fading HOT "cooling off" / DEAL "ending soon" (the event's FADING phase)
     * @param pct    signed percent against usual
     * @param usual  the usual price, formatted, uncoloured
     * @param left   time left in words ({@code about a day}); blank leaves the "(… left)" out
     * @param limit  the DEAL/DOWN "Limit N a day"; 0 or less leaves it out
     */
    public static List<String> storeLore(Badge badge, boolean fading, double pct, String usual, String left,
                                         long limit) {
        List<String> out = new ArrayList<>(3);
        switch (orNone(badge)) {
            case HOT -> {
                out.add("&6★ HOT! &7Price is up &6+" + wholeUp(pct) + "%&7. Usually &f" + s(usual) + "&7.");
                out.add("&7Better for selling than buying." + leftTag(left));
                if (fading) {
                    out.add("&7Cooling off…");
                }
            }
            case DEAL -> {
                out.add("&a✦ DEAL! &f" + wholeDown(pct) + "% off&7. Usually &f" + s(usual) + "&7.");
                String limitLine = limit > 0
                        ? "&7Limit &f" + limit + " &7a day while it's on sale." + leftTag(left)
                        : leftTag(left).strip();
                if (!limitLine.isEmpty()) {
                    out.add(limitLine);
                }
                if (fading) {
                    out.add("&7(ending soon)");
                }
            }
            case UP -> out.add("&a▲ Price going up fast! &7Usually &f" + s(usual) + "&7.");
            case DOWN -> {
                out.add("&c▼ Price went down! &a&lGood time to buy!");
                out.add("&7Usually &f" + s(usual) + "&7." + (limit > 0 ? " Limit &f" + limit + " &7a day." : ""));
            }
            case WANTED -> {
                // The store keeps its "OUT OF STOCK"; WANTED is a sell-screen badge.
            }
            case NONE -> {
                if (showUsual(pct)) {
                    out.add(usually(usual));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The Sell tile's extra lore, inserted right after the "Crate pays" line. Arguments as
     * {@link #storeLore}; {@code limit} is the HOT/UP "Up to N a day at this price".
     */
    public static List<String> sellLore(Badge badge, boolean fading, double pct, String usual, String left,
                                        long limit) {
        List<String> out = new ArrayList<>(4);
        switch (orNone(badge)) {
            case HOT -> {
                out.add("&6★ HOT! &eCrate pays &6+" + wholeUp(pct) + "% &eextra!");
                out.add("&e&lSell now!" + leftTag(left));
                if (limit > 0) {
                    out.add(upTo(limit));
                }
                if (fading) {
                    out.add("&eCooling off soon - sell now!");
                }
            }
            case DEAL -> out.add("&a✦ On sale in the store, so Crate pays less right now.");
            case UP -> {
                out.add("&a▲ Price going up fast! &e&lSell now!");
                if (limit > 0) {
                    out.add(upTo(limit));
                }
            }
            case DOWN -> out.add("&c▼ Price went down. &7Maybe wait to sell.");
            case WANTED -> out.add("&e» WANTED! &7Crate has none. &eSell some for its top price!");
            case NONE -> {
                if (showUsual(pct)) {
                    out.add(usually(usual));
                }
            }
        }
        return List.copyOf(out);
    }

    /** {@code &7Usually &f{usual}}: the unbadged tile line and the TV's line 6. */
    public static String usually(String usual) {
        return "&7Usually &f" + s(usual);
    }

    /** An unbadged item shows "Usually" once {@code |pct| >= 3}. NaN never does. */
    public static boolean showUsual(double pct) {
        return Math.abs(pct) >= USUAL_MIN_PCT;
    }

    /**
     * The QuantityMenu preview extra (§7.1), or nothing:
     * sell HOT/UP {@code &6★ Crate pays extra right now!}; sell DEAL/DOWN
     * {@code &7Crate pays less right now (on sale).}; buy DEAL/DOWN
     * {@code &a✦ On sale right now! &7Limit {n} a day.}; buy HOT/UP
     * {@code &7The price is up right now.}
     */
    public static List<String> previewLines(Badge badge, boolean sell, long limit) {
        Badge b = orNone(badge);
        boolean upish = b == Badge.HOT || b == Badge.UP;
        boolean downish = b == Badge.DEAL || b == Badge.DOWN;
        if (sell && upish) {
            return List.of("&6★ Crate pays extra right now!");
        }
        if (sell && downish) {
            return List.of("&7Crate pays less right now (on sale).");
        }
        if (!sell && downish) {
            return List.of("&a✦ On sale right now!" + (limit > 0 ? " &7Limit " + limit + " a day." : ""));
        }
        if (!sell && upish) {
            return List.of("&7The price is up right now.");
        }
        return List.of();
    }

    // ---- displays (§7.1, §7.3) ------------------------------------------------------------

    /** The sign's line-3 prefix: {@code &6★HOT }, {@code &a✦DEAL }, {@code &a▲NEWS }, {@code &c▼NEWS }. */
    public static String signPrefix(Badge badge) {
        return switch (orNone(badge)) {
            case HOT -> "&6★HOT ";
            case DEAL -> "&a✦DEAL ";
            case UP -> "&a▲NEWS ";
            case DOWN -> "&c▼NEWS ";
            case WANTED, NONE -> "";
        };
    }

    /**
     * Sign line 3: {@link #signPrefix} + the trend ({@code Trend.color(ch) + Trend.label(ch)}).
     * If the badge would push the line past {@value #SIGN_LINE_MAX} visible characters (a
     * 24 h change of 1000% or more), the trend is shown alone rather than cut.
     */
    public static String signLine(Badge badge, String trendColour, String trendLabel) {
        String trend = s(trendColour) + s(trendLabel);
        String line = signPrefix(badge) + trend;
        return visibleLength(line) <= SIGN_LINE_MAX ? line : trend;
    }

    /**
     * Appended to a hologram's name line: {@code " &6★ HOT"}, {@code " &a✦ DEAL"},
     * {@code " &a▲ NEWS"}, {@code " &c▼ NEWS"}.
     */
    public static String holoSuffix(Badge badge) {
        return switch (orNone(badge)) {
            case HOT -> " &6★ HOT";
            case DEAL -> " &a✦ DEAL";
            case UP -> " &a▲ NEWS";
            case DOWN -> " &c▼ NEWS";
            case WANTED, NONE -> "";
        };
    }

    /** The TV panel's line 5 ({@code ""} for no badge; line 6 is {@link #usually} then). */
    public static String tvLine(Badge badge, boolean fading) {
        return switch (orNone(badge)) {
            case HOT -> fading ? "&6★ HOT &7(cooling off)" : "&6&l★ HOT ★";
            case DEAL -> fading ? "&a✦ DEAL &7(ending soon)" : "&a&l✦ DEAL ✦";
            case UP -> "&a&l▲ PRICE UP!";
            case DOWN -> "&c&l▼ PRICE DOWN!";
            case WANTED -> "&e&l» WANTED";
            case NONE -> "";
        };
    }

    /**
     * The {@code @news} hologram / TV board (§7.3): the header; the newest headline wrapped at
     * {@value #BOARD_WIDTH} characters (at most {@value #BOARD_HEADLINE_LINES} lines, the last
     * cut with …) and {@code &8{age}}; {@code &6★ Hot: {names}} and {@code &a✦ Deal: {names}},
     * each cut to {@value #BOARD_WIDTH}. With no headline and nothing hot or on sale, the header
     * and {@code &7The Crate Market is calm today.}
     */
    public static List<String> newsBoard(String headline, String age, List<String> hot, List<String> deals) {
        List<String> out = new ArrayList<>();
        out.add(BOARD_HEADER);
        String h = plain(headline);
        String hotNames = joinNames(hot, Integer.MAX_VALUE);
        String dealNames = joinNames(deals, Integer.MAX_VALUE);
        if (h.isEmpty() && hotNames.isEmpty() && dealNames.isEmpty()) {
            out.add("&7" + CALM);
            return List.copyOf(out);
        }
        if (!h.isEmpty()) {
            for (String line : wrap(h, BOARD_WIDTH, BOARD_HEADLINE_LINES)) {
                out.add("&f" + line);
            }
            if (!s(age).isBlank()) {
                out.add("&8" + age);
            }
        }
        if (!hotNames.isEmpty()) {
            out.add("&6" + cut("★ Hot: " + hotNames, BOARD_WIDTH));
        }
        if (!dealNames.isEmpty()) {
            out.add("&a" + cut("✦ Deal: " + dealNames, BOARD_WIDTH));
        }
        return List.copyOf(out);
    }

    // ---- chat announcements (§6.2) --------------------------------------------------------

    /**
     * A NEWS FLASH (UP or DOWN):
     * <pre>
     * &amp;e&amp;l» NEWS FLASH «
     * &amp;f{headline}
     * &amp;a▲ {Name} is going UP! &amp;fCrate pays {sellBefore} → {sellAfter} &amp;7(+{pct}%) &amp;e&amp;lSell now!
     * &amp;c▼ {Name} is going DOWN! &amp;f{buyBefore} → {buyAfter} &amp;7(-{pct}%) &amp;b&amp;lGood time to buy!
     *     &amp;7Limit {n} a day.
     * </pre>
     * For UP pass the sell prices, for DOWN the buy prices. {@code {pct}} is the event's
     * announced percent ({@link MarketEvent#pct()}, or its strength if that is unset). Line 3 is
     * the one that carries the price hover. Any other kind gives an empty list.
     */
    public static List<String> newsLines(MarketEvent e, String name, String headline, String before, String after,
                                         long limitPerDay) {
        if (e == null || !e.kind().news()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        out.add(NEWS_FLASH_HEADER);
        addHeadline(out, headline);
        boolean up = e.kind() == EventKind.UP;
        out.add((up ? "&a▲ " : "&c▼ ") + newsBody(e, name, before, after, limitPerDay));
        return List.copyOf(out);
    }

    /**
     * The flash's line 3 without its arrow, for the event's stored {@code line}
     * ({@code /api/news} strips it to "Wheat is going UP! Crate pays $3.29 → $4.01 (+22%) Sell now!").
     */
    public static String newsLine(MarketEvent e, String name, String before, String after, long limitPerDay) {
        if (e == null || !e.kind().news()) {
            return "";
        }
        return (e.kind() == EventKind.UP ? "&a" : "&c") + newsBody(e, name, before, after, limitPerDay);
    }

    /**
     * A HOT or DEAL, announced at full strength:
     * <pre>
     * &amp;6&amp;l★ HOT ITEM ★
     * &amp;f{hot headline}
     * &amp;6{Name}: Crate pays about +{pct}% for {left}. &amp;e&amp;lSell now!
     *
     * &amp;a&amp;l✦ DEAL ✦
     * &amp;f{deal headline}
     * &amp;a{Name} is about {pct}% off for {left}. &amp;7Limit {n} a day. &amp;e&amp;lOrder at a PC!
     * </pre>
     * Any other kind gives an empty list.
     */
    public static List<String> storyLines(MarketEvent e, String name, String headline, String left, long limitPerDay) {
        if (e == null || !e.kind().story()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        out.add(e.kind() == EventKind.HOT ? HOT_HEADER : DEAL_HEADER);
        addHeadline(out, headline);
        out.add(storyLine(e, name, left, limitPerDay));
        return List.copyOf(out);
    }

    /** A HOT/DEAL announcement's line 3, which is also its stored {@code line}. */
    public static String storyLine(MarketEvent e, String name, String left, long limitPerDay) {
        if (e == null || !e.kind().story()) {
            return "";
        }
        double pct = pct(e);
        if (e.kind() == EventKind.HOT) {
            return "&6" + s(name) + ": Crate pays about +" + wholeUp(pct) + "% for " + s(left) + ". &e&lSell now!";
        }
        return "&a" + s(name) + " is about " + wholeDown(pct) + "% off for " + s(left) + "."
                + (limitPerDay > 0 ? " &7Limit " + limitPerDay + " a day." : "") + " &e&lOrder at a PC!";
    }

    /**
     * A WANTED flash:
     * <pre>
     * &amp;e&amp;l» MARKET NEWS «
     * &amp;f{wanted headline} &amp;eBe the first to sell some!
     * </pre>
     */
    public static List<String> wantedLines(String headline) {
        String h = s(headline).strip();
        return List.of(WANTED_HEADER, h.isEmpty() ? wantedLine() : "&f" + h + " " + wantedLine());
    }

    /** A WANTED flash's stored {@code line}: {@code &eBe the first to sell some!} */
    public static String wantedLine() {
        return "&eBe the first to sell some!";
    }

    /**
     * A season starting:
     * <pre>
     * &amp;2&amp;l» SEASON NEWS «
     * &amp;a{season headline} &amp;7({Wheat -4%, …} until {Oct 31})
     * </pre>
     *
     * @param effects {@link #seasonEffects}
     * @param until   {@link #untilDate} of the season's last day
     */
    public static List<String> seasonLines(String headline, String effects, String until) {
        String detail = seasonLine(effects, until);
        String h = s(headline).strip();
        String line = "&a" + h + (detail.isEmpty() ? "" : (h.isEmpty() ? "&7(" : " &7(") + detail + ")");
        return List.of(SEASON_HEADER, line);
    }

    /** A season's stored {@code line}: {@code Wheat -4% until Oct 31}. */
    public static String seasonLine(String effects, String until) {
        String e = s(effects).strip();
        String u = s(until).strip();
        if (u.isEmpty()) {
            return e;
        }
        return e.isEmpty() ? "until " + u : e + " until " + u;
    }

    /**
     * A season's effects in words, in config order: {@code Wheat -4%}, {@code Gold Ingot +3%,
     * Diamond +3%}; {@code "*"} reads "Everything". Whole percents are written without decimals,
     * others to one ({@code +4.5%}); zero entries are skipped; after
     * {@value #SEASON_EFFECTS_MAX} it ends with {@code , …}.
     *
     * @param percent item id (or {@code "*"}) to a whole percent ({@code Season.percent()})
     * @param nameOf  item id to its plain name; {@code null} (or a {@code null} answer) uses the id
     */
    public static String seasonEffects(Map<String, Double> percent, Function<String, String> nameOf) {
        if (percent == null || percent.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int shown = 0;
        boolean more = false;
        for (Map.Entry<String, Double> e : percent.entrySet()) {
            Double v = e.getValue();
            if (e.getKey() == null || v == null || !Double.isFinite(v) || v == 0.0) {
                continue;
            }
            if (shown == SEASON_EFFECTS_MAX) {
                more = true;
                break;
            }
            String id = e.getKey();
            String name;
            if ("*".equals(id)) {
                name = "Everything";
            } else {
                String n = nameOf == null ? null : nameOf.apply(id);
                name = n == null || n.isBlank() ? id : plain(n);
            }
            parts.add(name + " " + signedPercent(v));
            shown++;
        }
        return String.join(", ", parts) + (more ? ", …" : "");
    }

    /** A day as {@code Oct 31} (English, the same on every JDK and locale). */
    public static String untilDate(LocalDate day) {
        if (day == null) {
            return "";
        }
        return MONTHS[day.getMonthValue() - 1] + " " + day.getDayOfMonth();
    }

    /**
     * A real-world move:
     * <pre>
     * &amp;d&amp;l» REAL-WORLD NEWS «
     * &amp;f{real_up|real_down headline}
     * &amp;a▲ So Crate's {Name} went up a little too. &amp;7(+{pct}%)
     * &amp;c▼ So Crate's {Name} went down a little too. &amp;7(-{pct}%)
     * &amp;7» But Crate's {Name} price stayed about the same.
     * </pre>
     * The direction and size come from the event's percent ({@link #pct}). Line 3 never claims
     * a move Crate's price did not make: when that percent rounds to 0 (the item is sold out and
     * pinned at its ceiling, or the mood was already at its limit) it says the price stayed
     * about the same. Any other kind gives an empty list.
     */
    public static List<String> realLines(MarketEvent e, String name, String headline) {
        if (e == null || e.kind() != EventKind.REAL) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        out.add(REAL_HEADER);
        addHeadline(out, headline);
        out.add(realLead(e, true) + realBody(e, name));
        return List.copyOf(out);
    }

    /** A real-world move's stored {@code line}: its line 3 without the arrow. */
    public static String realLine(MarketEvent e, String name) {
        if (e == null || e.kind() != EventKind.REAL) {
            return "";
        }
        return realLead(e, false) + realBody(e, name);
    }

    /**
     * "Last call!" before a HOT/DEAL starts cooling:
     * {@code &6» Last call! &f{Name} &7is only HOT for about {h} more hours.} /
     * {@code &a» Last call! &f{Name} &7is only on sale for about {h} more hours.}
     * {@code h} is {@code msLeft} rounded to whole hours, at least 1 ("1 more hour").
     */
    public static String lastCall(EventKind kind, String name, long msLeft) {
        if (kind == null || !kind.story()) {
            return "";
        }
        long h = Math.max(1L, Math.round(msLeft / 3_600_000.0));
        String hours = h + (h == 1 ? " more hour." : " more hours.");
        return kind == EventKind.HOT
                ? "&6» Last call! &f" + s(name) + " &7is only HOT for about " + hours
                : "&a» Last call! &f" + s(name) + " &7is only on sale for about " + hours;
    }

    /**
     * The quiet line when a HOT/DEAL is over: {@code &7★ {Name} cooled off. Prices are back to
     * normal.} / {@code &7✦ The {Name} sale is over.}
     */
    public static String ending(EventKind kind, String name) {
        if (kind == EventKind.HOT) {
            return "&7★ " + s(name) + " cooled off. Prices are back to normal.";
        }
        if (kind == EventKind.DEAL) {
            return "&7✦ The " + s(name) + " sale is over.";
        }
        return "";
    }

    /** The log-only line for a DEAL stopped because it sold out: {@code &7✦ The {Name} sale sold out!} */
    public static String soldOut(String name) {
        return "&7✦ The " + s(name) + " sale sold out!";
    }

    /** The one-time intro, two lines. */
    public static List<String> intro() {
        return List.of("&6&l★ The Crate Market is LIVE! ★",
                "&ePrices now move a little on their own. Watch for &6★ HOT&e, &a✦ DEAL &eand &e» NEWS FLASH&e! "
                        + "&7(/hcm market news)");
    }

    /** The NEWS FLASH / HOT / DEAL title; nothing for other kinds. */
    public static Optional<Title> titleFor(EventKind kind, String name) {
        if (kind == null) {
            return Optional.empty();
        }
        String n = s(name);
        return switch (kind) {
            case UP -> Optional.of(new Title("&e&lNEWS FLASH", "&a▲ " + n + " going UP! ▲"));
            case DOWN -> Optional.of(new Title("&e&lNEWS FLASH", "&c▼ " + n + " going DOWN! ▼"));
            case HOT -> Optional.of(new Title("&6&l★ HOT ITEM ★", "&6" + n));
            case DEAL -> Optional.of(new Title("&a&l✦ DEAL ✦", "&a" + n + " on sale!"));
            default -> Optional.empty();
        };
    }

    /**
     * The action bar: {@code &e» NEWS: &f{Name} } + {@code &agoing UP!} / {@code &cgoing DOWN!} /
     * {@code &6is HOT!} / {@code &aon SALE!}; for WANTED {@code &e» Crate wants {item}!}
     * ({@code item} the plural). {@code ""} for other kinds.
     */
    public static String actionBar(EventKind kind, String name, String item) {
        if (kind == null) {
            return "";
        }
        String head = "&e» NEWS: &f" + s(name) + " ";
        return switch (kind) {
            case UP -> head + "&agoing UP!";
            case DOWN -> head + "&cgoing DOWN!";
            case HOT -> head + "&6is HOT!";
            case DEAL -> head + "&aon SALE!";
            case WANTED -> "&e» Crate wants " + s(item) + "!";
            default -> "";
        };
    }

    /** The hover's click command: {@code /hcm market price <id>}. */
    public static String priceCommand(String itemId) {
        return "/hcm market price " + s(itemId);
    }

    /**
     * The one INFO console line for an announcement:
     * {@code [Market] NEWS FLASH wheat UP +22% ($3.46 → $4.22) sim}; other kinds
     * {@code [Market] HOT oak_log +12% sim}. {@code id} is the item id (or the tag); the price
     * pair is left out when either side is blank.
     */
    public static String consoleLine(MarketEvent e, String id, String before, String after) {
        if (e == null) {
            return "";
        }
        EventKind k = e.kind();
        StringBuilder sb = new StringBuilder("[Market] ");
        if (k.news()) {
            sb.append("NEWS FLASH ").append(s(id)).append(' ').append(k.name());
        } else {
            sb.append(k.name()).append(' ').append(s(id));
        }
        if (k.mood() || k == EventKind.REAL) {
            sb.append(' ').append(signedWhole(pct(e)));
        }
        if (!s(before).isBlank() && !s(after).isBlank()) {
            sb.append(" (").append(before).append(" → ").append(after).append(')');
        }
        sb.append(' ').append(e.source().id());
        return sb.toString();
    }

    // ---- summaries, catch-up, news menu (§6.3, §7.2) --------------------------------------

    /**
     * One event in a line (catch-up, MarketMenu slot 50, the news menu):
     * {@code &a▲ &f{Name} went up {pct}%}, {@code &c▼ &f{Name} went down {pct}%},
     * {@code &6★ &f{Name} got HOT}, {@code &a✦ &f{Name} went on sale},
     * {@code &e» &fCrate is looking for {item}}, {@code &2» &f{Season} started},
     * {@code &d» &fReal-world {real} went up/down}.
     *
     * @param name the word the kind's summary uses: the item's {@code Name} for UP/DOWN/HOT/DEAL,
     *             the plural {@code item} for WANTED, the season's name for SEASON, the
     *             real-world name ({@code gold}) for REAL
     */
    public static String summary(MarketEvent e, String name) {
        if (e == null) {
            return "";
        }
        String n = s(name);
        return switch (e.kind()) {
            case UP -> "&a▲ &f" + n + " went up " + wholeUp(pct(e)) + "%";
            case DOWN -> "&c▼ &f" + n + " went down " + wholeDown(pct(e)) + "%";
            case HOT -> "&6★ &f" + n + " got HOT";
            case DEAL -> "&a✦ &f" + n + " went on sale";
            case WANTED -> "&e» &fCrate is looking for " + n;
            case SEASON -> "&2» &f" + n + " started";
            case REAL -> "&d» &fReal-world " + n + (realUp(e) ? " went up" : " went down");
        };
    }

    /** {@code {summary} &8({ago})}; a blank {@code ago} leaves the age out. */
    public static String withAge(String summary, String ago) {
        return s(summary) + (s(ago).isBlank() ? "" : " &8(" + ago + ")");
    }

    /** A catch-up bullet: {@code &7 • {summary} &8({ago})}. */
    public static String catchUpLine(String summary, String ago) {
        return "&7 • " + withAge(summary, ago);
    }

    /** {@code &8   …and {n} more: /hcm market news}; {@code ""} for none. */
    public static String catchUpMore(int more) {
        return more > 0 ? "&8   …and " + more + " more: /hcm market news" : "";
    }

    /**
     * One "Right now" entry: {@code &6★ {Name} &7({left} left)}, {@code &a✦ …}, {@code &a▲ …},
     * {@code &c▼ …}; {@code ""} for WANTED or no badge.
     */
    public static String rightNowPart(Badge badge, String name, String left) {
        String mark = switch (orNone(badge)) {
            case HOT -> "&6★ ";
            case DEAL -> "&a✦ ";
            case UP -> "&a▲ ";
            case DOWN -> "&c▼ ";
            case WANTED, NONE -> "";
        };
        if (mark.isEmpty()) {
            return "";
        }
        return mark + s(name) + (s(left).isBlank() ? "" : " &7(" + left + " left)");
    }

    /** The running season as a "Right now" entry: {@code &2» {Season} &7({left} left)}. */
    public static String rightNowSeason(String seasonName, String left) {
        if (s(seasonName).isBlank()) {
            return "";
        }
        return "&2» " + seasonName + (s(left).isBlank() ? "" : " &7(" + left + " left)");
    }

    /** {@code &7 Right now: {a} &8· {b}}; {@code ""} when every part is blank. */
    public static String rightNow(List<String> parts) {
        List<String> ok = nonBlank(parts);
        return ok.isEmpty() ? "" : "&7 Right now: " + String.join(" &8· ", ok);
    }

    /**
     * The whole join catch-up (§6.3): when there is news, the header, the bullets
     * ({@link #catchUpLine}) and "…and N more"; then the "Right now" line if there is one. An
     * empty list means there is nothing to say.
     */
    public static List<String> catchUp(List<String> bullets, int more, String rightNow) {
        List<String> out = new ArrayList<>();
        List<String> lines = nonBlank(bullets);
        if (!lines.isEmpty()) {
            out.add(CATCH_UP_HEADER);
            out.addAll(lines);
            String tail = catchUpMore(more);
            if (!tail.isEmpty()) {
                out.add(tail);
            }
        }
        if (!s(rightNow).isBlank()) {
            out.add(rightNow);
        }
        return List.copyOf(out);
    }

    /**
     * MarketMenu slot 50's lore: the latest (at most {@value #LATEST_MAX}) summaries with ages
     * ({@link #withAge}), {@code &6★ Hot: {names}}, {@code &a✦ Deals: {names}}, then
     * {@code &eClick for all the news}. With nothing to show, the calm line instead.
     */
    public static List<String> newsButtonLore(List<String> latest, List<String> hot, List<String> deals) {
        List<String> out = new ArrayList<>();
        List<String> lines = nonBlank(latest);
        out.addAll(lines.subList(0, Math.min(LATEST_MAX, lines.size())));
        String hotNames = joinNames(hot, Integer.MAX_VALUE);
        String dealNames = joinNames(deals, Integer.MAX_VALUE);
        if (!hotNames.isEmpty()) {
            out.add("&6★ Hot: " + hotNames);
        }
        if (!dealNames.isEmpty()) {
            out.add("&a✦ Deals: " + dealNames);
        }
        if (out.isEmpty()) {
            out.add("&7" + CALM);
        }
        out.add(NEWS_BUTTON_CLICK);
        return List.copyOf(out);
    }

    /**
     * StoreMenu slot 47 ("Sell to Crate") extra lore: {@code &6★ Hot right now: &f{≤3 names}}
     * and {@code &e» {latest headline, cut to 34}}, each only when there is one.
     */
    public static List<String> sellButtonLore(List<String> hotNames, String latestHeadline) {
        List<String> out = new ArrayList<>(2);
        String names = joinNames(hotNames, HOT_NAMES_MAX);
        if (!names.isEmpty()) {
            out.add("&6★ Hot right now: &f" + names);
        }
        String h = plain(latestHeadline);
        if (!h.isEmpty()) {
            out.add("&e» " + cut(h, SLOT_HEADLINE_MAX));
        }
        return List.copyOf(out);
    }

    /** MarketNewsMenu slot 8: {@code &7News in chat: &aON} / {@code &cOFF}. */
    public static String muteToggle(boolean newsOn) {
        return "&7News in chat: " + (newsOn ? "&aON" : "&cOFF");
    }

    /** A MarketNewsMenu entry's name: its headline, cut to {@value #NEWS_ENTRY_NAME_MAX}. */
    public static String newsEntryName(String headline) {
        return "&f" + cut(plain(headline), NEWS_ENTRY_NAME_MAX);
    }

    /**
     * A MarketNewsMenu entry's lore: {@code &7{ago}}; {@code &7{before} → {after} ({±pct}%)}
     * when both prices are given; {@code &aStill going!} or {@code &7All over.}; then
     * {@code &eClick to sell some} (UP/HOT) or {@code &eClick to order some} (DOWN/DEAL).
     */
    public static List<String> newsEntryLore(MarketEvent e, String ago, String before, String after, boolean going) {
        List<String> out = new ArrayList<>(4);
        if (!s(ago).isBlank()) {
            out.add("&7" + ago);
        }
        if (e != null && !s(before).isBlank() && !s(after).isBlank()) {
            out.add("&7" + before + " → " + after + " (" + signedWhole(pct(e)) + ")");
        }
        out.add(going ? "&aStill going!" : "&7All over.");
        if (e != null) {
            switch (e.kind()) {
                case UP, HOT -> out.add("&eClick to sell some");
                case DOWN, DEAL -> out.add("&eClick to order some");
                default -> {
                    // season, real and wanted entries do not open anything
                }
            }
        }
        return List.copyOf(out);
    }

    // ---- admin and commands (§7.2, §8) ----------------------------------------------------

    /**
     * The admin menus' mood line: {@code &7Usual: &6{usual} &8· &7Mood: &f{±pct}% &8({badge or
     * "quiet"})}, the percent as {@link #moodPct}.
     */
    public static String adminMood(String usual, double pct, Badge badge) {
        Badge b = orNone(badge);
        return "&7Usual: &6" + s(usual) + " &8· &7Mood: &f" + moodPct(pct) + " &8(" + (b.shown() ? b.name() : "quiet")
                + ")";
    }

    /** {@code /hcm market price}'s extra line: {@code &7  usual: &f{usual} &7 mood: &f{±pct}%}. */
    public static String priceMood(String usual, double pct) {
        return "&7  usual: &f" + s(usual) + " &7 mood: &f" + moodPct(pct);
    }

    /**
     * {@code /hcm market price}'s status line: {@code &7  status: {badge tag} &8({left} left)};
     * {@code ""} with no badge.
     */
    public static String priceStatus(Badge badge, double pct, String left) {
        String tag = badgeTag(badge, pct);
        if (tag.isEmpty()) {
            return "";
        }
        return "&7  status: " + tag + (s(left).isBlank() ? "" : " &8(" + left + " left)");
    }

    // ---- badges as text (PAPI §7.4, status lines) -----------------------------------------

    /**
     * The coloured badge with its percent: {@code &6★ HOT +12%}, {@code &a✦ DEAL -14%},
     * {@code &a▲ UP +22%}, {@code &c▼ DOWN -18%}, {@code &e» WANTED}; {@code ""} for none.
     */
    public static String badgeTag(Badge badge, double pct) {
        return switch (orNone(badge)) {
            case HOT -> "&6★ HOT +" + wholeUp(pct) + "%";
            case DEAL -> "&a✦ DEAL -" + wholeDown(pct) + "%";
            case UP -> "&a▲ UP +" + wholeUp(pct) + "%";
            case DOWN -> "&c▼ DOWN -" + wholeDown(pct) + "%";
            case WANTED -> "&e» WANTED";
            case NONE -> "";
        };
    }

    /** {@code %hcm_badge_<item>%}: {@link #badgeTag} without colours ({@code ★ HOT +12%}). */
    public static String papiBadge(Badge badge, double pct) {
        return plain(badgeTag(badge, pct));
    }

    /**
     * {@code %hcm_status_<item>%}: {@code HOT}, {@code DEAL}, {@code UP}, {@code DOWN},
     * {@code WANTED} or {@code ""}.
     */
    public static String papiStatus(Badge badge) {
        Badge b = orNone(badge);
        return b.shown() ? b.name() : "";
    }

    /** {@code %hcm_news%}: the headline without colours, cut to {@value #PAPI_NEWS_MAX}, or the calm line. */
    public static String papiNews(String headline) {
        String h = plain(headline);
        return h.isEmpty() ? CALM : cut(h, PAPI_NEWS_MAX);
    }

    /**
     * {@code %hcm_endsin_<item>%}: under 48 hours in whole hours rounded up ({@code 20h},
     * at least {@code 1h}), from 48 hours in rounded days ({@code 2d}); {@code ""} when nothing
     * is running ({@code ms <= 0}).
     */
    public static String endsIn(long ms) {
        if (ms <= 0) {
            return "";
        }
        long hour = 3_600_000L;
        if (ms < 48 * hour) {
            return Math.max(1L, (ms + hour - 1) / hour) + "h";
        }
        return Math.round(ms / (24.0 * hour)) + "d";
    }

    // ---- numbers --------------------------------------------------------------------------

    /**
     * The mood percent to one decimal with its sign: {@code +11.7%}, {@code -3.2%}; anything
     * that rounds to zero (or NaN) is {@code 0%}.
     */
    public static String moodPct(double pct) {
        if (!Double.isFinite(pct)) {
            return "0%";
        }
        double r = Math.round(pct * 10.0) / 10.0;
        if (r == 0.0) {
            return "0%";
        }
        return (r > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", r) + "%";
    }

    /** A whole signed percent: {@code +22%}, {@code -18%}, {@code 0%}. */
    public static String signedWhole(double pct) {
        long r = Double.isFinite(pct) ? Math.round(pct) : 0L;
        if (r == 0L) {
            return "0%";
        }
        return (r > 0 ? "+" : "") + r + "%";
    }

    /** The size of a rise as a whole number, never below 0 (a fall reads 0). */
    public static long wholeUp(double pct) {
        return Double.isFinite(pct) ? Math.max(0L, Math.round(pct)) : 0L;
    }

    /** The size of a fall as a whole number, never below 0 (a rise reads 0). */
    public static long wholeDown(double pct) {
        return Double.isFinite(pct) ? Math.max(0L, Math.round(-pct)) : 0L;
    }

    /**
     * The signed percent an announcement quotes for {@code e}: the size is its stored
     * {@code pct} when set, else its strength as a percent ({@code 0.12} = 12); the sign is the
     * kind's (HOT/UP +, DEAL/DOWN -), a REAL's own strength sign, or for other kinds the stored
     * sign. So a row whose {@code pct} was stored unsigned still reads the right way.
     *
     * <p>A REAL that carries its before and after prices (every row the simulator writes) is
     * what Crate's price really did, so its stored {@code pct} counts even when it is 0: a
     * sold-out or clamped item whose price could not move reads 0, never its strength.
     */
    public static double pct(MarketEvent e) {
        if (e == null) {
            return 0.0;
        }
        double p = e.pct();
        boolean measured = e.kind() == EventKind.REAL && hasPrices(e);
        double raw = Double.isFinite(p) && (p != 0.0 || measured) ? p : e.strength() * 100.0;
        if (!Double.isFinite(raw)) {
            return 0.0;
        }
        int sign = e.kind().sign();
        if (sign == 0 && e.kind() == EventKind.REAL && e.strength() != 0.0) {
            sign = e.strength() > 0 ? 1 : -1;
        }
        if (raw == 0.0) {
            return 0.0; // never -0.0: it would print as "-0.00" / "-0%"
        }
        return sign == 0 ? raw : sign * Math.abs(raw);
    }

    // ---- text -----------------------------------------------------------------------------

    /** Legacy '&amp;'/'§' colour and format codes stripped and the ends trimmed; {@code null} gives {@code ""}. */
    public static String plain(String s) {
        return s == null ? "" : LEGACY_CODE.matcher(s).replaceAll("").strip();
    }

    /** Characters a player sees: code points once the colour codes are gone (spaces count). */
    public static int visibleLength(String s) {
        if (s == null) {
            return 0;
        }
        String p = LEGACY_CODE.matcher(s).replaceAll("");
        return p.codePointCount(0, p.length());
    }

    /**
     * {@code text} (plain) in at most {@code max} characters: unchanged if it fits, else the
     * first {@code max - 1} (trailing spaces dropped) and {@code …}.
     */
    public static String cut(String text, int max) {
        String t = text == null ? "" : text;
        if (max <= 0) {
            return "";
        }
        int n = t.codePointCount(0, t.length());
        if (n <= max) {
            return t;
        }
        int end = t.offsetByCodePoints(0, max - 1);
        return t.substring(0, end).stripTrailing() + "…";
    }

    /**
     * Word-wrap plain {@code text} at {@code width} characters into at most {@code maxLines}
     * lines. A word longer than a line is split; if the text does not fit, the last line is cut
     * with {@code …}.
     */
    public static List<String> wrap(String text, int width, int maxLines) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || width <= 0 || maxLines <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : t.split("\\s+")) {
            String w = word;
            while (w.codePointCount(0, w.length()) > width) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                int cutAt = w.offsetByCodePoints(0, width);
                lines.add(w.substring(0, cutAt));
                w = w.substring(cutAt);
            }
            if (w.isEmpty()) {
                continue;
            }
            int now = line.codePointCount(0, line.length());
            int add = w.codePointCount(0, w.length());
            if (line.length() == 0) {
                line.append(w);
            } else if (now + 1 + add <= width) {
                line.append(' ').append(w);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(w);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.size() <= maxLines) {
            return List.copyOf(lines);
        }
        List<String> out = new ArrayList<>(lines.subList(0, maxLines));
        String last = out.get(maxLines - 1);
        // There is more after the last line: make room for the ellipsis if it is full.
        String withMore = last.codePointCount(0, last.length()) < width
                ? last + "…"
                : cut(last + "…", width);
        out.set(maxLines - 1, withMore);
        return List.copyOf(out);
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String newsBody(MarketEvent e, String name, String before, String after, long limitPerDay) {
        double pct = pct(e);
        if (e.kind() == EventKind.UP) {
            return s(name) + " is going UP! &fCrate pays " + s(before) + " → " + s(after) + " &7(+" + wholeUp(pct)
                    + "%) &e&lSell now!";
        }
        return s(name) + " is going DOWN! &f" + s(before) + " → " + s(after) + " &7(-" + wholeDown(pct)
                + "%) &b&lGood time to buy!" + (limitPerDay > 0 ? " &7Limit " + limitPerDay + " a day." : "");
    }

    private static String realBody(MarketEvent e, String name) {
        double pct = pct(e);
        if (!realMoved(e)) {
            return "But Crate's " + s(name) + " price stayed about the same.";
        }
        return pct >= 0
                ? "So Crate's " + s(name) + " went up a little too. &7(+" + wholeUp(pct) + "%)"
                : "So Crate's " + s(name) + " went down a little too. &7(-" + wholeDown(pct) + "%)";
    }

    /** A REAL's line 3 colour and arrow: up, down, or none when Crate's price did not move. */
    private static String realLead(MarketEvent e, boolean arrow) {
        if (!realMoved(e)) {
            return arrow ? "&7» " : "&7";
        }
        boolean up = pct(e) >= 0;
        return (up ? "&a" : "&c") + (arrow ? (up ? "▲ " : "▼ ") : "");
    }

    /** Whether Crate's price moved by at least a whole percent, so "went up/down" is true. */
    private static boolean realMoved(MarketEvent e) {
        return Math.round(Math.abs(pct(e))) >= 1;
    }

    /** Which way the real-world price went: the strength's sign (the headline's list), else the pct's. */
    private static boolean realUp(MarketEvent e) {
        double st = e.strength();
        return st != 0.0 && Double.isFinite(st) ? st > 0 : !(pct(e) < 0);
    }

    /** Both prices recorded (positive and finite), as the simulator stores them. */
    private static boolean hasPrices(MarketEvent e) {
        double b = e.priceBefore();
        double a = e.priceAfter();
        return b > 0 && a > 0 && Double.isFinite(b) && Double.isFinite(a);
    }

    private static void addHeadline(List<String> out, String headline) {
        String h = s(headline).strip();
        if (!h.isEmpty()) {
            out.add("&f" + h);
        }
    }

    private static String upTo(long limit) {
        return "&7Up to &f" + limit + " &7a day at this price.";
    }

    private static String leftTag(String left) {
        return s(left).isBlank() ? "" : " &8(" + left + " left)";
    }

    /** {@code +4%}, {@code -4.5%}: whole numbers without decimals, others to one. */
    private static String signedPercent(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        String num = r == Math.rint(r)
                ? Long.toString(Math.abs((long) r))
                : String.format(Locale.ROOT, "%.1f", Math.abs(r));
        return (r < 0 ? "-" : "+") + num + "%";
    }

    /** Plain names joined with ", ", at most {@code max}, then {@code , …}. */
    private static String joinNames(List<String> names, int max) {
        List<String> ok = new ArrayList<>();
        for (String n : nonBlank(names)) {
            ok.add(plain(n));
        }
        if (ok.size() <= max) {
            return String.join(", ", ok);
        }
        return String.join(", ", ok.subList(0, max)) + ", …";
    }

    private static List<String> nonBlank(List<String> in) {
        if (in == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !plain(s).isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static Badge orNone(Badge b) {
        return b == null ? Badge.NONE : b;
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }
}
