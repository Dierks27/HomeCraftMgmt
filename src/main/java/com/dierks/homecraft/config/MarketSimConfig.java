package com.dierks.homecraft.config;

import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemOverride;
import com.dierks.homecraft.market.sim.ItemParams;
import com.dierks.homecraft.market.sim.RealQuotes;
import com.dierks.homecraft.market.sim.RealSymbol;
import com.dierks.homecraft.market.sim.Season;
import com.dierks.homecraft.market.sim.SeasonCalendar;
import com.dierks.homecraft.market.sim.SimLimits;
import com.dierks.homecraft.market.sim.SimSettings;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the live market's settings: the {@code market.sim} section (spec §12) and the optional
 * per-row keys of {@code market.catalog} ({@code sim}, {@code volatility}, {@code sim_weight},
 * {@code news_name}). The {@link PluginConfig.Market} record is left exactly as it was; these
 * keys live beside it in {@link Parsed}.
 *
 * <p><b>Validation.</b> Every value is squeezed into its allowed range and each value that had
 * to change logs one WARN naming its full key ({@code market.sim.hot.percent [10, 40] goes past
 * the limit locked in code (15) - using [10, 15]}). The §2.2 code limits always apply (they are
 * also structural in {@link SimSettings}, so nothing built here can be wilder than the code
 * allows). Pairs are sorted. {@code news.hours} must be {@code HH:MM-HH:MM}. A bad season date
 * drops that season; an unknown {@code real_world.provider} or a non-https {@code url} keeps
 * {@code real_world} off; an empty or unusable headline list falls back to the shipped one; a
 * sound key that is not namespaced ({@code minecraft:...}) falls back to the shipped sound; an
 * unknown key is reported and ignored. A missing key or section reads as the shipped value, so
 * the bundled block parses to exactly {@link SimSettings#defaults()} without a single WARN.
 *
 * <p><b>Never throws.</b> A config this parser cannot make sense of turns the live market OFF
 * (every multiplier exactly 1.0) with a WARN, rather than failing the plugin's load.
 *
 * <p>The only Bukkit type is {@link ConfigurationSection}: the section is turned into plain maps
 * and lists up front ({@link #tree}), and the rules run on those in
 * {@link #parse(Object, List, Consumer)}. Pinned by {@code MarketSimConfigTest},
 * {@code SoundKeysTest} and {@code ConfigMigrationTest}.
 */
public final class MarketSimConfig {

    /** Where the section lives in config.yml. */
    public static final String PATH = "market.sim";
    /** The catalog whose rows may carry the per-item keys. */
    public static final String CATALOG = "market.catalog";

    private static final double ANY = Double.MAX_VALUE;
    private static final int ANY_INT = Integer.MAX_VALUE;
    /** The replay after downtime runs on the main thread: a week of ticks is plenty. */
    private static final int MAX_CATCHUP_HOURS = 168;
    /** A real-world fetch that has not answered in a minute is not going to. */
    private static final int MAX_TIMEOUT_SECONDS = 60;

    private static final Pattern COLOUR = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    /** {@code namespace:path}, the only form {@code AnnounceService.sound} resolves faithfully. */
    private static final Pattern SOUND_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern HH_MM = Pattern.compile("(\\d{1,2}):(\\d{2})");

    // ---- the keys, in config order ------------------------------------------------------

    private static final List<String> TOP = List.of("enabled", "tick_minutes", "max_catchup_hours",
            "max_up_percent", "max_down_percent", "keep_days", "drift", "hot", "deal", "slots_per_items",
            "cooldown_days", "popular_weight", "news", "announce", "headlines", "same_plural", "seasons",
            "real_world");
    private static final List<String> DRIFT = List.of("max_percent", "calm_percent", "lively_percent",
            "lively_from", "half_life_hours");
    private static final List<String> HOT = List.of("enabled", "percent", "hold_hours", "ramp_hours",
            "fade_hours", "gap_hours", "last_call_hours", "sell_limit");
    private static final List<String> DEAL = List.of("enabled", "percent", "hold_hours", "ramp_hours",
            "fade_hours", "gap_hours", "last_call_hours", "min_stock_percent", "buy_limit_share");
    private static final List<String> NEWS = List.of("enabled", "percent", "min_percent", "half_life_hours",
            "lasts_hours", "gap_hours", "extra_hours", "max_gap_hours", "max_per_day", "hours",
            "wait_for_players", "join_delay_minutes", "max_hold_hours", "item_cooldown_hours",
            "min_stock_percent", "buy_limit_share", "wanted_share", "wanted_every_days");
    private static final List<String> ANNOUNCE = List.of("chat", "title", "action_bar", "particles",
            "min_gap_minutes", "max_per_day", "endings", "intro", "sound_up", "sound_down", "sound_story",
            "sound_other", "volume", "pitch_up", "pitch_down", "catch_up", "catch_up_lines", "catch_up_hours");
    private static final List<String> SEASONS = List.of("enabled", "predictable_max_percent", "ramp_days", "list");
    private static final List<String> REAL = List.of("enabled", "provider", "url", "fetch_time", "gain",
            "max_percent", "ignore_above_percent", "announce_above_percent", "half_life_hours", "lasts_hours",
            "timeout_seconds", "symbols");

    /** Each nested section's keys, by its key under {@code market.sim}. */
    private static final Map<String, List<String>> SECTIONS = sections();

    /**
     * Every leaf the parser reads, relative to {@code market.sim}, in config order. The bundled
     * config.yml ships exactly these (a list such as {@code hot.percent} or
     * {@code seasons.list} is one leaf).
     */
    static final List<String> KEYS = leafKeys();

    private MarketSimConfig() {
    }

    /**
     * The parsed live-market config.
     *
     * @param settings  the {@code market.sim} section
     * @param overrides per-item keys by lower-case catalog id; only rows that set at least one
     *                  of {@code sim}, {@code volatility}, {@code sim_weight}, {@code news_name}
     */
    public record Parsed(SimSettings settings, Map<String, ItemOverride> overrides) {

        /** The shipped section with no per-item keys. */
        public static final Parsed DEFAULTS = new Parsed(SimSettings.defaults(), Map.of());
        /** The live market switched off: every multiplier is exactly 1.0. */
        public static final Parsed OFF = new Parsed(SimSettings.defaults().withEnabled(false), Map.of());

        public Parsed {
            settings = settings == null ? SimSettings.defaults() : settings;
            overrides = overrides == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        }

        /** The row's per-item keys, or {@link ItemOverride#NONE} when it sets none. */
        public ItemOverride override(String id) {
            if (id == null) {
                return ItemOverride.NONE;
            }
            ItemOverride o = overrides.get(id.trim().toLowerCase(Locale.ROOT));
            return o == null ? ItemOverride.NONE : o;
        }

        /** {@code market.sim.enabled}. */
        public boolean enabled() {
            return settings.enabled();
        }
    }

    // ---- entry points -------------------------------------------------------------------

    /**
     * Read {@code market.sim} and the per-row keys of {@code market.catalog} from the config
     * root ({@code plugin.getConfig()}). Every problem goes to {@code warn}, one line each.
     */
    public static Parsed parse(ConfigurationSection root, Consumer<String> warn) {
        Consumer<String> w = quiet(warn);
        if (root == null) {
            return Parsed.DEFAULTS;
        }
        try {
            return parse(root.get(PATH), root.getMapList(CATALOG), w);
        } catch (RuntimeException e) {
            w.accept(PATH + " could not be read (" + e + ") - the live market is off until it is fixed");
            return Parsed.OFF;
        }
    }

    /**
     * The rules, on plain Java values: {@code sim} is the {@code market.sim} value as nested
     * {@code Map}s, {@code List}s and scalars, or the section itself ({@code null} = not set:
     * the shipped section); {@code catalog} is the {@code market.catalog} rows.
     */
    public static Parsed parse(Object sim, List<? extends Map<?, ?>> catalog, Consumer<String> warn) {
        Consumer<String> w = quiet(warn);
        try {
            List<? extends Map<?, ?>> rows = catalog == null ? List.of() : catalog;
            Object plain = sim instanceof ConfigurationSection section ? tree(section) : sim;
            SimSettings settings = settings(plain, catalogIds(rows), w);
            return new Parsed(settings, overrides(rows, w));
        } catch (RuntimeException e) {
            w.accept(PATH + " could not be read (" + e + ") - the live market is off until it is fixed");
            return Parsed.OFF;
        }
    }

    /** A config section as plain nested maps, keys in file order. */
    static Map<String, Object> tree(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key, null);
            out.put(key, value instanceof ConfigurationSection child ? tree(child) : value);
        }
        return out;
    }

    // ---- market.sim -----------------------------------------------------------------------

    private static SimSettings settings(Object sim, Set<String> catalogIds, Consumer<String> w) {
        SimSettings d = SimSettings.defaults();
        if (sim == null) {
            return d;
        }
        if (sim instanceof Boolean on) {
            w.accept(PATH + " should be a section of settings - reading it as enabled: " + on
                    + " for now; write " + PATH + ".enabled: " + on + " instead");
            return d.withEnabled(on);
        }
        if (!(sim instanceof Map<?, ?> map)) {
            w.accept(PATH + " should be a section of settings, not \"" + sim
                    + "\" - the live market is off until it is fixed");
            return d.withEnabled(false);
        }
        Node root = new Node(PATH, map, w);
        root.unknownKeys(TOP);

        boolean enabled = root.bool("enabled", d.enabled());
        int tickMinutes = root.whole("tick_minutes", d.tickMinutes(),
                SimLimits.MIN_TICK_MINUTES, SimLimits.MAX_TICK_MINUTES, true);
        int catchup = root.whole("max_catchup_hours", d.maxCatchupHours(), 0, MAX_CATCHUP_HOURS, false);
        double maxUp = root.num("max_up_percent", d.maxUpPercent(),
                0, (SimLimits.MAX_MULTIPLIER - 1.0) * 100.0, true);
        double maxDown = root.num("max_down_percent", d.maxDownPercent(),
                0, (1.0 - SimLimits.MIN_MULTIPLIER) * 100.0, true);
        int keepDays = root.whole("keep_days", d.keepDays(), 1, ANY_INT, false);

        SimSettings.Drift drift = drift(root.child("drift"), d.drift());
        SimSettings.Story hot = story(root.child("hot"), d.hot(), true);
        SimSettings.Story deal = story(root.child("deal"), d.deal(), false);

        int slots = root.whole("slots_per_items", d.slotsPerItems(), 1, ANY_INT, false);
        int cooldown = root.whole("cooldown_days", d.cooldownDays(), 0, ANY_INT, false);
        double popular = root.num("popular_weight", d.popularWeight(), 0, ANY, false);

        SimSettings.News news = news(root.child("news"), d.news());
        SimSettings.Announce announce = announce(root.child("announce"), d.announce());
        SimSettings.Headlines headlines = headlines(root.child("headlines"));
        List<String> samePlural = samePlural(root, d.samePlural());
        SimSettings.Seasons seasons = seasons(root.child("seasons"), d.seasons(), w);
        SimSettings.Real real = real(root.child("real_world"), d.real());

        unknownItems(seasons, real, catalogIds, w);
        return new SimSettings(enabled, tickMinutes, catchup, maxUp, maxDown, keepDays, drift, hot, deal,
                slots, cooldown, popular, news, announce, headlines, samePlural, seasons, real);
    }

    private static SimSettings.Drift drift(Node n, SimSettings.Drift d) {
        n.unknownKeys(DRIFT);
        return new SimSettings.Drift(
                n.num("max_percent", d.maxPercent(), 0, SimLimits.DRIFT_MAX * 100.0, true),
                n.num("calm_percent", d.calmPercent(), 0, ANY, false),
                n.num("lively_percent", d.livelyPercent(), 0, ANY, false),
                n.num("lively_from", d.livelyFrom(), 0, ANY, false),
                n.num("half_life_hours", d.halfLifeHours(), 0, ANY, false));
    }

    /**
     * {@code hot:} or {@code deal:}. A key only the other story has ({@code sell_limit} under
     * {@code deal}, {@code min_stock_percent} under {@code hot}) is reported and ignored.
     */
    private static SimSettings.Story story(Node n, SimSettings.Story d, boolean hot) {
        n.unknownKeys(hot ? HOT : DEAL);
        return new SimSettings.Story(
                n.bool("enabled", d.enabled()),
                n.range("percent", d.percent(), 0, SimLimits.STORY_MAX * 100.0, true),
                n.range("hold_hours", d.holdHours(), 0, ANY, false),
                n.num("ramp_hours", d.rampHours(), 0, ANY, false),
                n.num("fade_hours", d.fadeHours(), 0, ANY, false),
                n.range("gap_hours", d.gapHours(), 0, ANY, false),
                n.num("last_call_hours", d.lastCallHours(), 0, ANY, false),
                hot ? n.bool("sell_limit", d.sellLimit()) : d.sellLimit(),
                hot ? d.minStockPercent() : n.num("min_stock_percent", d.minStockPercent(), 0, 100, false),
                hot ? d.buyLimitShare() : n.num("buy_limit_share", d.buyLimitShare(), 0, 1, false));
    }

    private static SimSettings.News news(Node n, SimSettings.News d) {
        n.unknownKeys(NEWS);
        return new SimSettings.News(
                n.bool("enabled", d.enabled()),
                n.range("percent", d.percent(), 0, SimLimits.NEWS_MAX * 100.0, true),
                n.num("min_percent", d.minPercent(), 0, SimLimits.NEWS_MAX * 100.0, true),
                n.num("half_life_hours", d.halfLifeHours(), 0, ANY, false),
                n.num("lasts_hours", d.lastsHours(), 0, ANY, false),
                n.num("gap_hours", d.gapHours(), 0, ANY, false),
                n.num("extra_hours", d.extraHours(), 0, ANY, false),
                n.num("max_gap_hours", d.maxGapHours(), 0, ANY, false),
                n.whole("max_per_day", d.maxPerDay(), 0, ANY_INT, false),
                hours(n, "hours", d.hours()),
                n.bool("wait_for_players", d.waitForPlayers()),
                n.range("join_delay_minutes", d.joinDelayMinutes(), 0, ANY, false),
                n.num("max_hold_hours", d.maxHoldHours(), 0, ANY, false),
                n.num("item_cooldown_hours", d.itemCooldownHours(), 0, ANY, false),
                n.num("min_stock_percent", d.minStockPercent(), 0, 100, false),
                n.num("buy_limit_share", d.buyLimitShare(), 0, 1, false),
                n.num("wanted_share", d.wantedShare(), 0, 1, false),
                n.whole("wanted_every_days", d.wantedEveryDays(), 0, ANY_INT, false));
    }

    /** {@code HH:MM-HH:MM}; anything else is 07:00-21:00 with a WARN. */
    private static SimSettings.Hours hours(Node n, String key, SimSettings.Hours d) {
        String raw = n.text(key);
        if (raw == null) {
            return d;
        }
        SimSettings.Hours h = SimSettings.Hours.parse(raw);
        if (h == null) {
            n.warn(n.key(key) + " \"" + raw + "\" should look like 07:00-21:00 - using " + SimSettings.Hours.DEFAULT);
            return SimSettings.Hours.DEFAULT;
        }
        return h;
    }

    private static SimSettings.Announce announce(Node n, SimSettings.Announce d) {
        n.unknownKeys(ANNOUNCE);
        return new SimSettings.Announce(
                n.bool("chat", d.chat()),
                n.bool("title", d.title()),
                n.bool("action_bar", d.actionBar()),
                n.bool("particles", d.particles()),
                n.whole("min_gap_minutes", d.minGapMinutes(), 0, ANY_INT, false),
                n.whole("max_per_day", d.maxPerDay(), 0, ANY_INT, false),
                n.bool("endings", d.endings()),
                n.bool("intro", d.intro()),
                sound(n, "sound_up", d.soundUp()),
                sound(n, "sound_down", d.soundDown()),
                sound(n, "sound_story", d.soundStory()),
                sound(n, "sound_other", d.soundOther()),
                n.num("volume", d.volume(), 0, ANY, false),
                n.num("pitch_up", d.pitchUp(), 0, ANY, false),
                n.num("pitch_down", d.pitchDown(), 0, ANY, false),
                n.bool("catch_up", d.catchUp()),
                n.whole("catch_up_lines", d.catchUpLines(), 0, ANY_INT, false),
                n.whole("catch_up_hours", d.catchUpHours(), 0, ANY_INT, false));
    }

    /**
     * A namespaced sound key, lower-cased. A plain enum-style name ({@code BLOCK_NOTE_BLOCK_BELL})
     * is refused: {@code AnnounceService.sound} would turn it into {@code block.note.block.bell},
     * which does not exist, and silently play the XP-orb sound instead.
     */
    private static String sound(Node n, String key, String d) {
        String raw = n.text(key);
        if (raw == null || raw.isBlank()) {
            return d;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (!SOUND_KEY.matcher(k).matches()) {
            n.warn(n.key(key) + " \"" + raw + "\" is not a namespaced sound key like " + d
                    + " (a plain name plays the XP-orb sound instead) - using " + d);
            return d;
        }
        return k;
    }

    private static SimSettings.Headlines headlines(Node n) {
        n.unknownKeys(Headlines.LISTS);
        return new SimSettings.Headlines(
                templates(n, Headlines.LIST_UP),
                templates(n, Headlines.LIST_DOWN),
                templates(n, Headlines.LIST_HOT),
                templates(n, Headlines.LIST_DEAL),
                templates(n, Headlines.LIST_WANTED),
                templates(n, Headlines.LIST_REAL_UP).get(0),
                templates(n, Headlines.LIST_REAL_DOWN).get(0));
    }

    /**
     * One headline list: its usable templates, or the shipped list (with a WARN) when it is
     * empty or none survive. {@code real_up}/{@code real_down} are one template each, written
     * as a plain string.
     */
    private static List<String> templates(Node n, String list) {
        Object raw = n.raw(list);
        if (raw == null) {
            return Headlines.shipped(list);
        }
        List<String> tpls;
        if (raw instanceof List<?> l) {
            tpls = strings(l);
        } else if (raw instanceof Map<?, ?>) {
            n.warn(n.key(list) + " should be a list of headlines - using the shipped ones");
            return Headlines.shipped(list);
        } else {
            tpls = List.of(String.valueOf(raw));
        }
        if (tpls.isEmpty()) {
            n.warn(n.key(list) + " is empty - using the shipped ones");
            return Headlines.shipped(list);
        }
        List<String> ok = Headlines.validOrShipped(list, tpls, n.warn);
        return ok.isEmpty() ? Headlines.shipped(list) : ok;
    }

    private static List<String> samePlural(Node root, List<String> d) {
        Object raw = root.raw("same_plural");
        if (raw == null) {
            return d;
        }
        if (!(raw instanceof List<?> l)) {
            root.warn(root.key("same_plural") + " should be a list of item ids - using the shipped one");
            return d;
        }
        return strings(l);
    }

    private static SimSettings.Seasons seasons(Node n, SimSettings.Seasons d, Consumer<String> w) {
        n.unknownKeys(SEASONS);
        boolean on = n.bool("enabled", d.enabled());
        double max = n.num("predictable_max_percent", d.predictableMaxPercent(),
                0, SimLimits.PREDICTABLE_MAX * 100.0, true);
        int rampDays = n.whole("ramp_days", d.rampDays(), 0, ANY_INT, false);
        Object raw = n.raw("list");
        List<Season> list;
        if (raw == null) {
            list = d.list();
        } else if (raw instanceof List<?> l) {
            list = SeasonCalendar.parse(maps(l), max, w);
        } else {
            n.warn(n.key("list") + " should be a list of seasons - using the shipped ones");
            list = d.list();
        }
        return new SimSettings.Seasons(on, max, rampDays, list);
    }

    private static SimSettings.Real real(Node n, SimSettings.Real d) {
        n.unknownKeys(REAL);
        boolean on = n.bool("enabled", d.enabled());

        String provider = n.text("provider");
        provider = provider == null || provider.isBlank() ? d.provider() : provider.trim().toLowerCase(Locale.ROOT);
        if (RealQuotes.defaultUrl(provider) == null) {
            n.warn(n.key("provider") + " \"" + provider + "\" should be stooq or yahoo"
                    + " - real_world stays off until it is fixed");
            on = false;
            provider = d.provider();
        }
        String url = n.text("url");
        url = url == null ? d.url() : url.trim();
        if (!url.isEmpty() && (!RealQuotes.validUrl(url) || !url.contains("{symbol}"))) {
            n.warn(n.key("url") + " \"" + url + "\" must start with https:// and contain {symbol}"
                    + " - real_world stays off until it is fixed");
            on = false;
            url = "";
        }
        return new SimSettings.Real(on, provider, url,
                time(n, "fetch_time", d.fetchTime()),
                n.num("gain", d.gain(), 0, ANY, false),
                n.num("max_percent", d.maxPercent(), 0, SimLimits.PREDICTABLE_MAX * 100.0, true),
                n.num("ignore_above_percent", d.ignoreAbovePercent(), 0, ANY, false),
                n.num("announce_above_percent", d.announceAbovePercent(), 0, ANY, false),
                n.num("half_life_hours", d.halfLifeHours(), 0, ANY, false),
                n.num("lasts_hours", d.lastsHours(), 0, ANY, false),
                n.whole("timeout_seconds", d.timeoutSeconds(), 1, MAX_TIMEOUT_SECONDS, false),
                symbols(n, d.symbols()));
    }

    /**
     * {@code HH:MM}. YAML 1.1 reads an unquoted {@code 17:30} as the base-60 number 1050, so a
     * whole number under 1440 is taken as minutes after midnight.
     */
    private static LocalTime time(Node n, String key, LocalTime d) {
        Object raw = n.raw(key);
        if (raw == null) {
            return d;
        }
        if (raw instanceof Number num) {
            double v = num.doubleValue();
            if (v == Math.rint(v) && v >= 0 && v < 24 * 60) {
                int minutes = (int) v;
                return LocalTime.of(minutes / 60, minutes % 60);
            }
        } else {
            Matcher m = HH_MM.matcher(String.valueOf(raw).trim());
            if (m.matches()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                if (h <= 23 && min <= 59) {
                    return LocalTime.of(h, min);
                }
            }
        }
        n.warn(n.key(key) + " \"" + raw + "\" should be a time like \"17:30\" - using " + d);
        return d;
    }

    /**
     * {@code real_world.symbols}. A row needs an {@code item}; a second row for the same item
     * is dropped; a symbol that is not {@code [A-Za-z0-9.=^_-]{1,16}} is blanked (that provider
     * is off for the item); the {@code name} ({@code {real}}) must be headline-safe and at most
     * 24 characters.
     */
    private static List<RealSymbol> symbols(Node n, List<RealSymbol> d) {
        Object raw = n.raw("symbols");
        if (raw == null) {
            return d;
        }
        String base = n.key("symbols");
        if (!(raw instanceof List<?> rows)) {
            n.warn(base + " should be a list of { item, stooq, yahoo, name } rows - using the shipped ones");
            return d;
        }
        List<RealSymbol> out = new ArrayList<>();
        Set<String> items = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String where = base + " #" + (i + 1);
            if (!(rows.get(i) instanceof Map<?, ?> row)) {
                n.warn(where + " is not a { item, stooq, yahoo, name } row - skipped");
                continue;
            }
            String item = plain(row.get("item")).toLowerCase(Locale.ROOT);
            if (item.isEmpty()) {
                n.warn(where + " has no item - skipped");
                continue;
            }
            where = base + "[" + item + "]";
            if (!items.add(item)) {
                n.warn(where + " appears twice - the second is skipped");
                continue;
            }
            String stooq = symbol(n, row, "stooq", where);
            String yahoo = symbol(n, row, "yahoo", where);
            out.add(new RealSymbol(item, stooq, yahoo, realName(n, row.get("name"), item, where)));
        }
        return List.copyOf(out);
    }

    private static String symbol(Node n, Map<?, ?> row, String key, String where) {
        String s = plain(row.get(key));
        if (s.isEmpty() || RealQuotes.validSymbol(s)) {
            return s;
        }
        n.warn(where + "." + key + " \"" + s + "\" is not a valid symbol - " + key + " is off for this item");
        return "";
    }

    private static String realName(Node n, Object raw, String item, String where) {
        String fallback = item.replace('_', ' ');
        String name = plain(raw);
        if (name.isEmpty()) {
            n.warn(where + ".name is missing - using \"" + fallback + "\"");
            return fallback;
        }
        String bad = Headlines.textProblem(name, true);
        if (bad != null) {
            n.warn(where + ".name \"" + name + "\" " + bad + " - using \"" + fallback + "\"");
            return fallback;
        }
        if (name.length() > Headlines.CHECK_NAME_LENGTH) {
            String cut = name.substring(0, Headlines.CHECK_NAME_LENGTH).trim();
            n.warn(where + ".name \"" + name + "\" is longer than " + Headlines.CHECK_NAME_LENGTH
                    + " characters - using \"" + cut + "\"");
            return cut;
        }
        return name;
    }

    /** One WARN per layer listing the item ids it names that the catalog does not have. */
    private static void unknownItems(SimSettings.Seasons seasons, SimSettings.Real real, Set<String> catalogIds,
                                     Consumer<String> w) {
        if (catalogIds.isEmpty()) {
            return; // no catalog to check against
        }
        List<String> seasonIds = SeasonCalendar.unknownIds(seasons.list(), catalogIds);
        if (!seasonIds.isEmpty()) {
            w.accept(PATH + ".seasons.list names item(s) that are not in " + CATALOG + " (ignored): "
                    + String.join(", ", seasonIds));
        }
        Set<String> realIds = new TreeSet<>();
        for (RealSymbol s : real.symbols()) {
            if (!catalogIds.contains(s.item())) {
                realIds.add(s.item());
            }
        }
        if (!realIds.isEmpty()) {
            w.accept(PATH + ".real_world.symbols names item(s) that are not in " + CATALOG + " (ignored): "
                    + String.join(", ", realIds));
        }
    }

    // ---- market.catalog rows ------------------------------------------------------------

    /**
     * The per-row keys. Rows whose id {@code PluginConfig.readMarket} skips (missing, blank,
     * a repeat, or reserved with {@code @}) are skipped here too, silently: it already warned.
     */
    static Map<String, ItemOverride> overrides(List<? extends Map<?, ?>> rows, Consumer<String> w) {
        Map<String, ItemOverride> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> row : rows) {
            String id = catalogId(row);
            if (id == null || !seen.add(id)) {
                continue;
            }
            String where = CATALOG + "[" + id + "]";

            Boolean sim = null;
            Object rawSim = row.get("sim");
            if (rawSim != null) {
                sim = bool(rawSim);
                if (sim == null) {
                    w.accept(where + ".sim should be true or false, not \"" + rawSim + "\" - ignored");
                }
            }
            Double volatility = rowNumber(row, "volatility", where, 0, SimLimits.VOLATILITY_MAX, true, w);
            Double weight = rowNumber(row, "sim_weight", where, 0, ANY, false, w);
            String newsName = newsName(row.get("news_name"), where, w);

            if (sim != null || volatility != null || weight != null || newsName != null) {
                out.put(id, new ItemOverride(sim, volatility, weight, newsName));
            }
        }
        return out;
    }

    private static Double rowNumber(Map<?, ?> row, String key, String where, double lo, double hi, boolean locked,
                                    Consumer<String> w) {
        Object raw = row.get(key);
        if (raw == null) {
            return null;
        }
        Double v = number(raw);
        if (v == null) {
            w.accept(where + "." + key + " should be a number, not \"" + raw + "\" - ignored");
            return null;
        }
        return clamp(where + "." + key, v, lo, hi, locked, w);
    }

    /** Colour codes stripped; headline-safe; cut to 24 characters. {@code null} = not set. */
    private static String newsName(Object raw, String where, Consumer<String> w) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) {
            w.accept(where + ".news_name should be a name like \"Diamonds\" - ignored");
            return null;
        }
        String name = COLOUR.matcher(String.valueOf(raw)).replaceAll("").trim();
        if (name.isEmpty()) {
            return null;
        }
        String bad = Headlines.textProblem(name, false);
        if (bad != null) {
            w.accept(where + ".news_name \"" + name + "\" " + bad + " - ignored");
            return null;
        }
        if (name.length() > ItemParams.NEWS_NAME_MAX) {
            String cut = name.substring(0, ItemParams.NEWS_NAME_MAX).trim();
            w.accept(where + ".news_name \"" + name + "\" is longer than " + ItemParams.NEWS_NAME_MAX
                    + " characters - using \"" + cut + "\"");
            return cut;
        }
        return name;
    }

    /** The ids of the rows {@code PluginConfig.readMarket} can accept. */
    private static Set<String> catalogIds(List<? extends Map<?, ?>> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<?, ?> row : rows) {
            String id = catalogId(row);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** The row's lower-case id, or {@code null} when it has none or it is reserved ({@code @...}). */
    private static String catalogId(Map<?, ?> row) {
        if (row == null || row.get("id") == null) {
            return null;
        }
        String id = String.valueOf(row.get("id")).trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() || id.startsWith("@") ? null : id;
    }

    // ---- values ---------------------------------------------------------------------------

    /**
     * One section being read: its dotted path (for WARNs), its keys, and the sink. A missing
     * section is an empty one, so every read falls back to the shipped value.
     */
    private static final class Node {

        private final String path;
        private final Map<?, ?> map;
        private final Consumer<String> warn;

        Node(String path, Map<?, ?> map, Consumer<String> warn) {
            this.path = path;
            this.map = map == null ? Map.of() : map;
            this.warn = warn;
        }

        String key(String k) {
            return path + "." + k;
        }

        void warn(String line) {
            warn.accept(line);
        }

        Object raw(String k) {
            return map.get(k);
        }

        /** The nested section {@code k}; one that is not a section reads as the shipped one. */
        Node child(String k) {
            Object v = map.get(k);
            if (v != null && !(v instanceof Map<?, ?>)) {
                warn(key(k) + " should be a section of settings, not \"" + v + "\" - using the shipped ones");
                v = null;
            }
            return new Node(key(k), (Map<?, ?>) v, warn);
        }

        void unknownKeys(List<String> known) {
            for (Object k : map.keySet()) {
                String s = String.valueOf(k);
                if (!known.contains(s)) {
                    warn(key(s) + " is not a live-market setting - ignored (a typo?)");
                }
            }
        }

        /** A scalar as text; {@code null} when unset (a section or list is refused with a WARN). */
        String text(String k) {
            Object v = map.get(k);
            if (v == null) {
                return null;
            }
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                warn(key(k) + " should be a single value - using the shipped one");
                return null;
            }
            return String.valueOf(v).trim();
        }

        boolean bool(String k, boolean d) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Boolean b = MarketSimConfig.bool(v);
            if (b == null) {
                warn(key(k) + " should be true or false, not \"" + v + "\" - using " + d);
                return d;
            }
            return b;
        }

        double num(String k, double d, double lo, double hi, boolean locked) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Double x = number(v);
            if (x == null) {
                warn(key(k) + " should be a number, not \"" + v + "\" - using " + fmt(d));
                return d;
            }
            return clamp(key(k), x, lo, hi, locked, warn);
        }

        int whole(String k, int d, int lo, int hi, boolean locked) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Double x = number(v);
            if (x == null) {
                warn(key(k) + " should be a whole number, not \"" + v + "\" - using " + d);
                return d;
            }
            double r = Math.rint(x);
            if (r != x) {
                warn(key(k) + " " + fmt(x) + " should be a whole number - using " + fmt(r));
            }
            return (int) clamp(key(k), r, lo, hi, locked, warn);
        }

        /** A {@code [low, high]} pair (a single number is {@code [n, n]}), sorted and clamped. */
        SimSettings.Range range(String k, SimSettings.Range d, double lo, double hi, boolean locked) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Double a = null;
            Double b = null;
            if (v instanceof List<?> l && l.size() == 2) {
                a = number(l.get(0));
                b = number(l.get(1));
            } else if (!(v instanceof List<?>)) {
                a = number(v);
                b = a;
            }
            if (a == null || b == null) {
                warn(key(k) + " should be a pair like " + pair(d.min(), d.max()) + ", not " + v
                        + " - using " + pair(d.min(), d.max()));
                return d;
            }
            if (a > b) {
                warn(key(k) + " " + pair(a, b) + " is backwards - using " + pair(b, a));
                double t = a;
                a = b;
                b = t;
            }
            double ca = Math.max(lo, Math.min(hi, a));
            double cb = Math.max(lo, Math.min(hi, b));
            if (ca != a || cb != b) {
                List<String> why = new ArrayList<>(2);
                if (b > hi) {
                    why.add(pastLimit(hi, locked));
                }
                if (a < lo) {
                    why.add("cannot go below " + fmt(lo));
                }
                warn(key(k) + " " + pair(a, b) + " " + String.join(" and ", why) + " - using " + pair(ca, cb));
            }
            return new SimSettings.Range(ca, cb);
        }
    }

    /** {@code v} squeezed into {@code [lo, hi]}, with one WARN naming {@code key} when it moved. */
    private static double clamp(String key, double v, double lo, double hi, boolean locked, Consumer<String> w) {
        if (v > hi) {
            w.accept(key + " " + fmt(v) + " " + pastLimit(hi, locked) + " - using " + fmt(hi));
            return hi;
        }
        if (v < lo) {
            w.accept(key + " " + fmt(v) + (lo == 0 ? " cannot be negative" : " is below the lowest allowed ("
                    + fmt(lo) + ")") + " - using " + fmt(lo));
            return lo;
        }
        return v;
    }

    private static String pastLimit(double hi, boolean locked) {
        return locked ? "goes past the limit locked in code (" + fmt(hi) + ")"
                : "is above the highest allowed (" + fmt(hi) + ")";
    }

    /** A finite number from a number or a numeric string ({@code "25%"} reads as 25). */
    static Double number(Object raw) {
        double d;
        if (raw instanceof Number n) {
            d = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                d = Double.parseDouble(s.trim().replace("%", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(d) ? d : null;
    }

    /** A boolean, or a quoted {@code "true"}/{@code "false"}; {@code null} for anything else. */
    private static Boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("on")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("off")) {
                return false;
            }
        }
        return null;
    }

    private static String plain(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /** Non-null elements as trimmed strings, order kept. */
    private static List<String> strings(List<?> in) {
        List<String> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (o != null && !(o instanceof Map<?, ?>) && !(o instanceof List<?>)) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }

    /** List elements that are maps; anything else becomes {@code null} so row numbers stay right. */
    private static List<Map<?, ?>> maps(List<?> in) {
        List<Map<?, ?>> out = new ArrayList<>(in.size());
        for (Object o : in) {
            out.add(o instanceof Map<?, ?> m ? m : null);
        }
        return out;
    }

    /** {@code 25}, {@code 1.5}: whole numbers without the {@code .0}. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.valueOf(v);
    }

    private static String pair(double a, double b) {
        return "[" + fmt(a) + ", " + fmt(b) + "]";
    }

    private static Consumer<String> quiet(Consumer<String> warn) {
        return warn == null ? s -> { } : warn;
    }

    private static Map<String, List<String>> sections() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("drift", DRIFT);
        m.put("hot", HOT);
        m.put("deal", DEAL);
        m.put("news", NEWS);
        m.put("announce", ANNOUNCE);
        m.put("headlines", Headlines.LISTS);
        m.put("seasons", SEASONS);
        m.put("real_world", REAL);
        return Collections.unmodifiableMap(m);
    }

    private static List<String> leafKeys() {
        List<String> out = new ArrayList<>();
        for (String k : TOP) {
            List<String> sub = SECTIONS.get(k);
            if (sub == null) {
                out.add(k);
            } else {
                for (String s : sub) {
                    out.add(k + "." + s);
                }
            }
        }
        return List.copyOf(out);
    }
}
