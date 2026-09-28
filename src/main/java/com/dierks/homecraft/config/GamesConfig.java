package com.dierks.homecraft.config;

import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.PlayGate;
import com.dierks.homecraft.games.RtpLimits;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Reads the {@code games:} section (spec §13, R1.9, R3.1): the common keys, then each game's own
 * block through its {@link GameSpec}.
 *
 * <p>Modelled on {@link MarketSimConfig}: an out-of-range number is squeezed into its allowed range
 * with one WARN naming its full key ({@code games.click_cooldown_ms 100 is below the lowest allowed
 * (250) - using 250}); a missing key or section reads as shipped, so the bundled block parses to
 * exactly the defaults without a single WARN; unknown keys are reported. The guardrails that must
 * never move are locked in code, not config: the RTP band ({@link RtpLimits}), the click-cooldown
 * floor, the ceiling on {@code chance_daily_tokens}, the one-day minimum wait before a raised limit.
 *
 * <p><b>Never throws, and junk fails closed.</b> A value this parser can't use at all (a word where
 * a number belongs, a list of the wrong length, an {@code enabled: maybe}, a game's own validation
 * failing) is one WARN and closes what it belongs to: junk in the common keys turns the games OFF,
 * junk in a game's block closes that game ({@link Parsed#readable}) - never a guess at what was
 * meant. A {@code games} value that isn't a section turns the games OFF. A bare switch where a
 * section belongs ({@code games: true}, {@code ore_slots: false}) reads as that section's
 * {@code enabled} (the config migration rewrites it so before the backfill runs, so this is only
 * seen if that write failed). A game whose own parser throws is closed on its own; the rest load.
 *
 * <p>Each game reads its own block with a {@link Node} in its own package, so a game owner adds a
 * key without touching this class; {@link #KEYS} (pinned by {@code GamesConfigTest} against the
 * bundled file) is built from the common keys and every spec's.
 */
public final class GamesConfig {

    /** Where the section lives in config.yml. */
    public static final String PATH = "games";

    /** The common keys, relative to {@code games}, in config order. */
    public static final List<String> COMMON = List.of("enabled", "worlds", "play_worlds", "click_cooldown_ms",
            "chance_daily_tokens", "max_payout", "skill_daily_cap", "featured", "featured_bonus",
            "break.daily_choices", "break.pause_days", "break.raise_delay_days");

    /**
     * Every leaf the parser reads, relative to {@code games}, in config order: {@link #COMMON},
     * then each game's keys as {@code <id>.<key>}. The bundled config.yml ships exactly these.
     */
    public static final List<String> KEYS = keys();

    /** The most tokens a day's limit on games of chance may allow, whatever config says. */
    public static final int MAX_CHANCE_DAILY_TOKENS = 10_000;

    private GamesConfig() {
    }

    // ---- the parsed config ----------------------------------------------------------------

    /**
     * The common keys.
     *
     * @param enabled             {@code games.enabled}: the whole module (ships false)
     * @param worlds              worlds courses may be built in (world games pay tokens there)
     * @param playWorlds          extra worlds the menu games may be played in (besides economy worlds)
     * @param clickCooldownMs     games of chance: clicks closer together than this do nothing (floor 250)
     * @param chanceDailyTokens   the server's daily limit on tokens put into games of chance (0 = off)
     * @param maxPayout           the most any single payout may be (a game never caps below its
     *                            largest stake: {@link #maxPayoutFor})
     * @param skillDailyCap       the most tokens all skill games together pay a player a day
     * @param featured            {@code auto}, or the id of the game or course to feature every day
     * @param featuredBonus       tokens for the first finish of the featured game each day
     * @param breakDailyChoices   the daily limits a player can pick
     * @param breakPauseDays      the pauses a player can pick, in days
     * @param breakRaiseDelayDays how long a raised limit waits before it starts (at least 1)
     */
    public record Common(boolean enabled, List<String> worlds, List<String> playWorlds, int clickCooldownMs,
                         int chanceDailyTokens, int maxPayout, int skillDailyCap, String featured, int featuredBonus,
                         List<Integer> breakDailyChoices, List<Integer> breakPauseDays, int breakRaiseDelayDays) {

        public Common {
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
            playWorlds = playWorlds == null ? List.of() : List.copyOf(playWorlds);
            clickCooldownMs = (int) Math.max(PlayGate.COOLDOWN_FLOOR_MS, clickCooldownMs);
            chanceDailyTokens = Math.max(0, Math.min(MAX_CHANCE_DAILY_TOKENS, chanceDailyTokens));
            featured = featured == null || featured.isBlank() ? "auto" : featured.trim().toLowerCase(Locale.ROOT);
            breakDailyChoices = breakDailyChoices == null ? List.of() : List.copyOf(breakDailyChoices);
            breakPauseDays = breakPauseDays == null ? List.of() : List.copyOf(breakPauseDays);
            breakRaiseDelayDays = Math.max(com.dierks.homecraft.games.Breaks.MIN_RAISE_DELAY_DAYS, breakRaiseDelayDays);
        }

        /** The shipped common keys (the games ship OFF). */
        public static Common defaults() {
            return new Common(false, List.of("games"), List.of(), 600, 100, 250, 6, "auto", 1,
                    List.of(10, 25, 50, 100), List.of(1, 7, 30), 7);
        }

        public Common withEnabled(boolean on) {
            return new Common(on, worlds, playWorlds, clickCooldownMs, chanceDailyTokens, maxPayout, skillDailyCap,
                    featured, featuredBonus, breakDailyChoices, breakPauseDays, breakRaiseDelayDays);
        }

        /** Whether the featured game is picked by the day ({@code featured: auto}). */
        public boolean featuredAuto() {
            return "auto".equals(featured);
        }

        /**
         * The payout cap for a game with these stakes: {@code max_payout}, but never below its
         * largest stake (a "win" capped under the tokens put in would be no win at all).
         */
        public int maxPayoutFor(List<Integer> stakes) {
            int top = 0;
            if (stakes != null) {
                for (Integer s : stakes) {
                    if (s != null) {
                        top = Math.max(top, s);
                    }
                }
            }
            return Math.max(maxPayout, top);
        }
    }

    /**
     * The parsed {@code games:} section.
     *
     * @param common     the common keys
     * @param settings   each game's settings record, by game id
     * @param unreadable ids of games whose block held junk (or made their parser throw): closed
     *                   until it is fixed
     */
    public record Parsed(Common common, Map<String, Object> settings, Set<String> unreadable) {

        /** The shipped section: the games off, every game's shipped settings. */
        public static final Parsed DEFAULTS = new Parsed(Common.defaults(), shippedSettings(), Set.of());
        /** The games switched off (a config this parser can't use). */
        public static final Parsed OFF = new Parsed(Common.defaults().withEnabled(false), shippedSettings(), Set.of());

        public Parsed {
            common = common == null ? Common.defaults().withEnabled(false) : common;
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            unreadable = unreadable == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(unreadable));
        }

        /** {@code games.enabled}. */
        public boolean enabled() {
            return common.enabled();
        }

        /** A game's settings; its shipped defaults when its block is missing or couldn't be read. */
        public <S> S settings(GameSpec<S> spec) {
            Object s = settings.get(spec.id());
            if (s != null && spec.defaults().getClass().isInstance(s)) {
                @SuppressWarnings("unchecked")
                S typed = (S) s;
                return typed;
            }
            return spec.defaults();
        }

        /** Whether the game's block could be read (a game with junk in its block is closed). */
        public boolean readable(String id) {
            return !unreadable.contains(id);
        }

        /** The same with {@code games.enabled} set. */
        public Parsed withEnabled(boolean on) {
            return new Parsed(common.withEnabled(on), settings, unreadable);
        }
    }

    // ---- entry points -------------------------------------------------------------------------

    /**
     * Read {@code games} from the config root ({@code plugin.getConfig()}). Every problem goes to
     * {@code warn}, one line each; never throws.
     */
    public static Parsed parse(ConfigurationSection root, Consumer<String> warn) {
        return parse(root, warn, null);
    }

    /** As {@link #parse(ConfigurationSection, Consumer)}, with a sink for INFO lines (an RTP solve's). */
    public static Parsed parse(ConfigurationSection root, Consumer<String> warn, Consumer<String> info) {
        Consumer<String> w = quiet(warn);
        if (root == null) {
            return Parsed.DEFAULTS;
        }
        try {
            return parse(root.get(PATH), w, info);
        } catch (RuntimeException e) {
            w.accept(PATH + " could not be read (" + e + ") - the games are off until it is fixed");
            return Parsed.OFF;
        }
    }

    /**
     * The rules, on plain Java values: {@code games} is the section as nested {@code Map}s,
     * {@code List}s and scalars, or the section itself ({@code null} = not set: the shipped one).
     */
    public static Parsed parse(Object games, Consumer<String> warn, Consumer<String> info) {
        Consumer<String> w = quiet(warn);
        Consumer<String> i = quiet(info);
        try {
            Object plain = games instanceof ConfigurationSection section ? tree(section) : games;
            if (plain == null) {
                return Parsed.DEFAULTS;
            }
            if (!(plain instanceof Map<?, ?> map)) {
                Boolean on = readSwitch(plain);
                if (on != null) {
                    w.accept(PATH + " should be a section of settings - reading it as " + PATH + ".enabled: " + on
                            + " and the rest as shipped; write " + PATH + ".enabled: " + on + " instead");
                    return on ? Parsed.DEFAULTS.withEnabled(true) : Parsed.OFF;
                }
                w.accept(PATH + " should be a section of settings, not \"" + plain
                        + "\" - the games are off until it is fixed");
                return Parsed.OFF;
            }
            Node root = new Node(PATH, map, w, i, null, "the games are off until it is fixed", new boolean[1]);
            root.unknownKeys(KEYS);
            Common common = common(root);
            if (root.invalid()) {
                common = common.withEnabled(false);
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            Set<String> unreadable = new LinkedHashSet<>();
            for (GameSpec<?> spec : GameCatalog.SPECS) {
                Node n = gameNode(root, spec.id(), common);
                try {
                    settings.put(spec.id(), parseOne(spec, n));
                    if (n.invalid()) {
                        unreadable.add(spec.id());
                    }
                } catch (RuntimeException | LinkageError e) {
                    w.accept(PATH + "." + spec.id() + " could not be read (" + e
                            + ") - that game is off until it is fixed");
                    unreadable.add(spec.id());
                    settings.put(spec.id(), spec.defaults());
                }
            }
            return new Parsed(common, settings, unreadable);
        } catch (RuntimeException e) {
            w.accept(PATH + " could not be read (" + e + ") - the games are off until it is fixed");
            return Parsed.OFF;
        }
    }

    /** A config section as plain nested maps, keys in file order. */
    public static Map<String, Object> tree(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key, null);
            out.put(key, value instanceof ConfigurationSection child ? tree(child) : value);
        }
        return out;
    }

    /**
     * A switch as this parser reads it: a boolean, or {@code true}/{@code yes}/{@code on} and
     * {@code false}/{@code no}/{@code off} as text; {@code null} for anything else. Public for the
     * config migration, which rewrites a bare {@code games: false} (or {@code games.<id>: false})
     * as its {@code enabled} key before the backfill runs.
     */
    public static Boolean readSwitch(Object raw) {
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

    // ---- the common keys ----------------------------------------------------------------------

    private static Common common(Node n) {
        Common d = Common.defaults();
        boolean enabled = n.enabled(d.enabled());
        List<String> worlds = n.stringList("worlds", d.worlds());
        List<String> playWorlds = n.stringList("play_worlds", d.playWorlds());
        int cooldown = n.whole("click_cooldown_ms", d.clickCooldownMs(), (int) PlayGate.COOLDOWN_FLOOR_MS, 60_000, false);
        int chanceTokens = n.whole("chance_daily_tokens", d.chanceDailyTokens(), 0, MAX_CHANCE_DAILY_TOKENS, true);
        int maxPayout = n.whole("max_payout", d.maxPayout(), 1, 1_000_000);
        int skillCap = n.whole("skill_daily_cap", d.skillDailyCap(), 0, 1000);
        String featured = featured(n, d.featured());
        int featuredBonus = n.whole("featured_bonus", d.featuredBonus(), 0, 100);
        Node b = n.child("break");
        List<Integer> choices = sortedDistinct(b.intList("daily_choices", d.breakDailyChoices(), 1, MAX_CHANCE_DAILY_TOKENS));
        List<Integer> pauses = sortedDistinct(b.intList("pause_days", d.breakPauseDays(), 1, 365));
        int raiseDelay = b.whole("raise_delay_days", d.breakRaiseDelayDays(),
                com.dierks.homecraft.games.Breaks.MIN_RAISE_DELAY_DAYS, 365, true);
        return new Common(enabled, worlds, playWorlds, cooldown, chanceTokens, maxPayout, skillCap, featured,
                featuredBonus, choices, pauses, raiseDelay);
    }

    /** {@code auto} or an id; never a game of chance (nothing may reward playing one, R1.17). */
    private static String featured(Node n, String d) {
        String raw = n.text("featured", d);
        String id = raw == null || raw.isBlank() ? "auto" : raw.trim().toLowerCase(Locale.ROOT);
        GameSpec<?> spec = GameCatalog.spec(id);
        if (spec != null && spec.kind().chance()) {
            n.warn(n.key("featured") + " \"" + raw + "\" is a game of chance, which is never featured - using auto");
            return "auto";
        }
        return id;
    }

    // ---- each game ----------------------------------------------------------------------------

    private static <S> S parseOne(GameSpec<S> spec, Node n) {
        S s = spec.parse().apply(n, spec.defaults());
        if (s == null || !spec.defaults().getClass().isInstance(s)) {
            throw new IllegalStateException("its parser returned " + s);
        }
        return s;
    }

    /**
     * The node for {@code games.<id>}, with its own junk flag (junk here closes this game only). A
     * bare switch where the section belongs reads as its {@code enabled}; an {@code enabled} that
     * is not a switch closes the game (one WARN, here, so the game's own read stays quiet).
     */
    private static Node gameNode(Node root, String id, Common common) {
        String key = root.key(id);
        String closed = key + " is off until it is fixed";
        Object v = root.raw(id);
        if (v == null) {
            return new Node(key, Map.of(), root.warn, root.info, common, closed, new boolean[1]);
        }
        if (v instanceof Map<?, ?> m) {
            Object en = m.get("enabled");
            if (en != null && readSwitch(en) == null) {
                Map<Object, Object> copy = new LinkedHashMap<>(m);
                copy.put("enabled", false);
                Node n = new Node(key, copy, root.warn, root.info, common, closed, new boolean[1]);
                n.invalid(key + ".enabled should be true or false, not \"" + en + "\"");
                return n;
            }
            return new Node(key, m, root.warn, root.info, common, closed, new boolean[1]);
        }
        Boolean on = readSwitch(v);
        Node n = new Node(key, Map.of("enabled", on != null && on), root.warn, root.info, common, closed,
                new boolean[1]);
        if (on != null) {
            root.warn(key + " should be a section of settings - reading it as " + key + ".enabled: " + on
                    + " and the rest as shipped; write " + key + ".enabled: " + on + " instead");
        } else {
            n.invalid(key + " should be a section of settings, not \"" + v + "\"");
        }
        return n;
    }

    // ---- the reader ---------------------------------------------------------------------------

    /**
     * One section being read: its dotted path (for WARNs), its keys, the sinks, and the already
     * parsed common keys. A missing section is an empty one, so every read falls back to the
     * shipped value. Every read clamps into its range with one WARN naming the full key, and none
     * throws.
     */
    public static final class Node {

        private final String path;
        private final Map<?, ?> map;
        private final Consumer<String> warn;
        private final Consumer<String> info;
        private final Common common;
        /** What junk here closes, said at the end of its WARN. */
        private final String closes;
        /** Shared with every child: whether junk was found anywhere in this section. */
        private final boolean[] junk;

        /**
         * A node over plain values, for a game's own tests:
         * {@code new Node("games.snake", map, warns::add)} (shipped common keys, no INFO sink).
         */
        public Node(String path, Map<?, ?> map, Consumer<String> warn) {
            this(path, map, warn, null, Common.defaults(), path + " is off until it is fixed", new boolean[1]);
        }

        private Node(String path, Map<?, ?> map, Consumer<String> warn, Consumer<String> info, Common common,
                     String closes, boolean[] junk) {
            this.path = path;
            this.map = map == null ? Map.of() : map;
            this.warn = quiet(warn);
            this.info = quiet(info);
            this.common = common;
            this.closes = closes;
            this.junk = junk;
        }

        private Node sub(String k, Map<?, ?> m) {
            return new Node(key(k), m, warn, info, common, closes, junk);
        }

        /** This section's full path ({@code games.ore_slots}). */
        public String path() {
            return path;
        }

        /** A key's full path ({@code games.ore_slots.rtp}). */
        public String key(String k) {
            return path + "." + k;
        }

        /** One WARN line (start it with the full key). */
        public void warn(String line) {
            warn.accept(line);
        }

        /** One INFO line (an RTP solve that could only land above its target, R1.1). */
        public void info(String line) {
            info.accept(line);
        }

        /**
         * A value that can't be used at all: one WARN ({@code line}, then what it closes), and what
         * this section belongs to - the game, or every game for a common key - stays closed until
         * it is fixed. For a game's own checks too (a Wheel space between 0 and 1).
         */
        public void invalid(String line) {
            junk[0] = true;
            warn.accept(line + " - " + closes);
        }

        /** Whether junk was found in this section (or any section read through it). */
        public boolean invalid() {
            return junk[0];
        }

        /**
         * The common keys ({@code max_payout}, ...), for a game whose settings depend on them.
         * {@code null} only while the common keys themselves are being read.
         */
        public Common common() {
            return common;
        }

        /** The raw value, or {@code null} when unset. */
        public Object raw(String k) {
            return map.get(k);
        }

        /** Whether the key is set in this section. */
        public boolean has(String k) {
            return map.get(k) != null;
        }

        /** The keys set in this section, in file order. */
        public Set<String> keys() {
            Set<String> out = new LinkedHashSet<>();
            for (Object k : map.keySet()) {
                out.add(String.valueOf(k));
            }
            return out;
        }

        /**
         * The nested section {@code k}. A value that is not a section reads as that section's
         * {@code enabled} switch with the rest shipped (one WARN); one that isn't a switch either
         * is junk.
         */
        public Node child(String k) {
            Object v = map.get(k);
            if (v == null) {
                return sub(k, Map.of());
            }
            if (v instanceof Map<?, ?> m) {
                return sub(k, m);
            }
            Boolean on = readSwitch(v);
            if (on != null) {
                warn(key(k) + " should be a section of settings - reading it as " + key(k) + ".enabled: " + on
                        + " and the rest as shipped");
            } else {
                invalid(key(k) + " should be a section of settings, not \"" + v + "\"");
            }
            return sub(k, Map.of("enabled", on != null && on));
        }

        /**
         * Report every key set here that is not one of {@code leaves} (dotted, relative to this
         * section, as {@link GameSpec#keys()}) or a section holding some of them, all the way
         * down. The value is ignored.
         */
        public void unknownKeys(List<String> leaves) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (leaves.contains(k)) {
                    continue;
                }
                List<String> sub = new ArrayList<>();
                for (String leaf : leaves) {
                    if (leaf.startsWith(k + ".")) {
                        sub.add(leaf.substring(k.length() + 1));
                    }
                }
                if (sub.isEmpty()) {
                    warn(key(k) + " is not a games setting - ignored (a typo?)");
                } else if (e.getValue() instanceof Map<?, ?> m) {
                    sub(k, m).unknownKeys(sub);
                }
                // A scalar where a section belongs is reported by whoever reads that section.
            }
        }

        /** A scalar as trimmed text, or {@code d} when unset (a section or list is refused with a WARN). */
        public String text(String k, String d) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                invalid(key(k) + " should be a single value");
                return d;
            }
            return String.valueOf(v).trim();
        }

        /** true/false (or yes/no, on/off); anything else is junk. */
        public boolean bool(String k, boolean d) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Boolean b = readSwitch(v);
            if (b == null) {
                invalid(key(k) + " should be true or false, not \"" + v + "\"");
                return d;
            }
            return b;
        }

        /**
         * The section's {@code enabled} switch, failing CLOSED: unset reads as {@code d}, a value
         * that is not a switch reads as false and is junk (the only reason to touch a switch is to
         * turn something off).
         */
        public boolean enabled(boolean d) {
            Object v = map.get("enabled");
            if (v == null) {
                return d;
            }
            Boolean b = readSwitch(v);
            if (b == null) {
                invalid(key("enabled") + " should be true or false, not \"" + v + "\"");
                return false;
            }
            return b;
        }

        /** A whole number in {@code [lo, hi]}. */
        public int whole(String k, int d, int lo, int hi) {
            return whole(k, d, lo, hi, false);
        }

        /**
         * A whole number in {@code [lo, hi]}; a fraction is rounded with a WARN.
         *
         * @param locked whether {@code hi} is a limit locked in code (said so in the WARN)
         */
        public int whole(String k, int d, int lo, int hi, boolean locked) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Double x = number(v);
            if (x == null) {
                invalid(key(k) + " should be a whole number, not \"" + v + "\"");
                return d;
            }
            double r = Math.rint(x);
            if (r != x) {
                warn(key(k) + " " + fmt(x) + " should be a whole number - using " + fmt(r));
            }
            return (int) clamp(k, r, lo, hi, locked);
        }

        /** A number in {@code [lo, hi]}. */
        public double num(String k, double d, double lo, double hi) {
            return num(k, d, lo, hi, false);
        }

        /** A number in {@code [lo, hi]} ({@code locked}: {@code hi} is locked in code). */
        public double num(String k, double d, double lo, double hi, boolean locked) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            Double x = number(v);
            if (x == null) {
                invalid(key(k) + " should be a number, not \"" + v + "\"");
                return d;
            }
            return clamp(k, x, lo, hi, locked);
        }

        /** A game of chance's {@code rtp}, a percent inside the band locked in {@link RtpLimits}. */
        public double rtp(String k, double d) {
            return num(k, d, RtpLimits.MIN_PERCENT, RtpLimits.MAX_PERCENT, true);
        }

        /** A non-empty list of whole numbers, each in {@code [lo, hi]} (a single number is a list of one). */
        public List<Integer> intList(String k, List<Integer> d, int lo, int hi) {
            return intList(k, d, lo, hi, 0);
        }

        /**
         * A list of whole numbers, each in {@code [lo, hi]}. Anything that is not such a list - or
         * not exactly {@code size} long, when {@code size > 0} - is junk (the shipped list is
         * returned); values out of range are clamped with one WARN for the list.
         */
        public List<Integer> intList(String k, List<Integer> d, int lo, int hi, int size) {
            List<Double> raw = numbers(k, size, true);
            if (raw == null) {
                return d;
            }
            List<Integer> out = new ArrayList<>(raw.size());
            boolean moved = false;
            for (double x : raw) {
                double c = Math.max(lo, Math.min(hi, x));
                moved |= c != x;
                out.add((int) c);
            }
            if (moved) {
                warn(key(k) + " " + raw.stream().map(GamesConfig::fmt).toList() + " has values outside "
                        + lo + "-" + hi + " - using " + out);
            }
            return List.copyOf(out);
        }

        /** A non-empty list of numbers, each in {@code [lo, hi]}. */
        public List<Double> doubleList(String k, List<Double> d, double lo, double hi) {
            return doubleList(k, d, lo, hi, 0);
        }

        /** As {@link #intList(String, List, int, int, int)}, for numbers that need not be whole. */
        public List<Double> doubleList(String k, List<Double> d, double lo, double hi, int size) {
            List<Double> raw = numbers(k, size, false);
            if (raw == null) {
                return d;
            }
            List<Double> out = new ArrayList<>(raw.size());
            boolean moved = false;
            for (double x : raw) {
                double c = Math.max(lo, Math.min(hi, x));
                moved |= c != x;
                out.add(c);
            }
            if (moved) {
                warn(key(k) + " " + raw.stream().map(GamesConfig::fmt).toList() + " has values outside "
                        + fmt(lo) + "-" + fmt(hi) + " - using " + out.stream().map(GamesConfig::fmt).toList());
            }
            return List.copyOf(out);
        }

        /** A list of names (a single name is a list of one); may be empty. */
        public List<String> stringList(String k, List<String> d) {
            Object v = map.get(k);
            if (v == null) {
                return d;
            }
            if (v instanceof Map<?, ?>) {
                invalid(key(k) + " should be a list like " + d);
                return d;
            }
            List<String> out = new ArrayList<>();
            if (v instanceof List<?> l) {
                for (Object o : l) {
                    if (o != null && !(o instanceof Map<?, ?>) && !(o instanceof List<?>)
                            && !String.valueOf(o).isBlank()) {
                        out.add(String.valueOf(o).trim());
                    }
                }
            } else if (!String.valueOf(v).isBlank()) {
                out.add(String.valueOf(v).trim());
            }
            return List.copyOf(out);
        }

        /**
         * Three milestone thresholds (bronze, silver, gold): positive whole numbers up to
         * {@code hi}, each strictly better than the last - rising, or falling when lower is better
         * (times, flips). Anything else is junk.
         */
        public List<Integer> ladder(String k, List<Integer> d, int hi, boolean lowerIsBetter) {
            return ladder(k, d, 1, hi, lowerIsBetter);
        }

        /**
         * As {@link #ladder(String, List, int, boolean)}, with the lowest value a game can reach
         * too ({@code lo}): a milestone past what the game can reach would never pay, so it is
         * clamped with one WARN like any other.
         */
        public List<Integer> ladder(String k, List<Integer> d, int lo, int hi, boolean lowerIsBetter) {
            List<Integer> l = intList(k, d, Math.max(1, lo), hi, 3);
            if (l == d) {
                return d;
            }
            for (int i = 1; i < l.size(); i++) {
                boolean better = lowerIsBetter ? l.get(i) < l.get(i - 1) : l.get(i) > l.get(i - 1);
                if (!better) {
                    invalid(key(k) + " " + l + " should be three " + (lowerIsBetter ? "falling" : "rising")
                            + " values (bronze, silver, gold)");
                    return d;
                }
            }
            return l;
        }

        /**
         * A map of whole numbers read key by key from the sub-section {@code k}: each of
         * {@code d}'s keys, in its order, clamped to {@code [lo, hi]}; a missing key is shipped.
         */
        public Map<String, Integer> wholeMap(String k, Map<String, Integer> d, int lo, int hi) {
            Node n = child(k);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : d.entrySet()) {
                out.put(e.getKey(), n.whole(e.getKey(), e.getValue(), lo, hi));
            }
            return Collections.unmodifiableMap(out);
        }

        /** {@code v} squeezed into {@code [lo, hi]}, with one WARN naming {@code key(k)} when it moved. */
        public double clamp(String k, double v, double lo, double hi, boolean locked) {
            if (v > hi) {
                warn(key(k) + " " + fmt(v) + " " + (locked ? "goes past the limit locked in code (" + fmt(hi) + ")"
                        : "is above the highest allowed (" + fmt(hi) + ")") + " - using " + fmt(hi));
                return hi;
            }
            if (v < lo) {
                warn(key(k) + " " + fmt(v) + (lo == 0 ? " cannot be negative"
                        : " is below the lowest allowed (" + fmt(lo) + ")") + " - using " + fmt(lo));
                return lo;
            }
            return v;
        }

        /** The list's numbers, or {@code null} (after one WARN) when it isn't a usable list. */
        private List<Double> numbers(String k, int size, boolean whole) {
            Object v = map.get(k);
            if (v == null) {
                return null;
            }
            List<?> items = v instanceof List<?> l ? l : v instanceof Map<?, ?> ? null : List.of(v);
            String what = whole ? "whole numbers" : "numbers";
            if (items == null || items.isEmpty()) {
                invalid(key(k) + " should be a list of " + what);
                return null;
            }
            List<Double> out = new ArrayList<>(items.size());
            for (Object o : items) {
                Double x = number(o);
                if (x == null || (whole && x != Math.rint(x))) {
                    invalid(key(k) + " should be a list of " + what + ", not " + v);
                    return null;
                }
                out.add(x);
            }
            if (size > 0 && out.size() != size) {
                invalid(key(k) + " should have exactly " + size + " values, not " + out.size());
                return null;
            }
            return out;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

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

    /** {@code 25}, {@code 1.5}: whole numbers without the {@code .0}. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.valueOf(v);
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    private static Consumer<String> quiet(Consumer<String> sink) {
        return sink == null ? s -> { } : sink;
    }

    private static Map<String, Object> shippedSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            out.put(spec.id(), spec.defaults());
        }
        return out;
    }

    private static List<String> keys() {
        List<String> out = new ArrayList<>(COMMON);
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            for (String k : spec.keys()) {
                out.add(spec.id() + "." + k);
            }
        }
        return List.copyOf(out);
    }
}
