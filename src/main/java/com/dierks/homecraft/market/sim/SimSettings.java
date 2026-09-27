package com.dierks.homecraft.market.sim;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every {@code market.sim} key (spec §12) as an immutable record tree. {@link #defaults()} is
 * exactly the shipped {@code config.yml}; {@code MarketSimConfig.parse} builds the live one.
 *
 * <p><b>Units mirror the config:</b> {@code *Percent} fields and {@link Range} percents are
 * WHOLE percents ({@code 25} = 25%), {@code *Hours}/{@code *Minutes}/{@code *Days} are what
 * they say. The {@code *Frac()} and {@code *Ms()} helpers convert, with the code limits
 * applied.
 *
 * <p><b>The code limits are structural.</b> Every constructor squeezes its values into the
 * §2.2 hard limits ({@link SimLimits}) and floors durations and counts at 0, silently. No
 * instance can describe a wilder market than the code allows, however it was built. The
 * parser warns by comparing what it read with what it got back.
 *
 * <p>A {@code null} section (or {@code samePlural}) reads as the shipped one. The shipped
 * headline lists, same-plural ids and seasons are shared with
 * {@link com.dierks.homecraft.market.sim.Headlines} and {@link SeasonCalendar#SHIPPED}, so
 * there is one copy of each. Note that the nested {@link SimSettings.Headlines} record (the
 * {@code headlines:} section) shares its simple name with that top-level class: outside this
 * file, write {@code SimSettings.Headlines}.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server.
 */
public record SimSettings(boolean enabled, int tickMinutes, int maxCatchupHours,
                          double maxUpPercent, double maxDownPercent, int keepDays,
                          Drift drift, Story hot, Story deal, int slotsPerItems, int cooldownDays,
                          double popularWeight, News news, Announce announce, Headlines headlines,
                          List<String> samePlural, Seasons seasons, Real real) {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;

    public SimSettings {
        tickMinutes = SimLimits.clampTickMinutes(tickMinutes);
        maxCatchupHours = Math.max(0, maxCatchupHours);
        maxUpPercent = pct(maxUpPercent, (SimLimits.MAX_MULTIPLIER - 1.0) * 100.0);
        maxDownPercent = pct(maxDownPercent, (1.0 - SimLimits.MIN_MULTIPLIER) * 100.0);
        keepDays = Math.max(1, keepDays);
        drift = drift == null ? Drift.defaults() : drift;
        hot = hot == null ? Story.hotDefaults() : hot;
        deal = deal == null ? Story.dealDefaults() : deal;
        slotsPerItems = Math.max(1, slotsPerItems);
        cooldownDays = Math.max(0, cooldownDays);
        popularWeight = nonNeg(popularWeight);
        news = news == null ? News.defaults() : news;
        announce = announce == null ? Announce.defaults() : announce;
        headlines = headlines == null ? Headlines.shipped() : headlines;
        samePlural = ids(samePlural == null
                ? new ArrayList<>(com.dierks.homecraft.market.sim.Headlines.SAME_PLURAL) : samePlural);
        seasons = seasons == null ? Seasons.defaults() : seasons;
        real = real == null ? Real.defaults() : real;
    }

    /** The shipped {@code market.sim} section, key for key. */
    public static SimSettings defaults() {
        return new SimSettings(true, 5, 48, 25, 25, 60,
                Drift.defaults(), Story.hotDefaults(), Story.dealDefaults(), 40, 7, 2.0,
                News.defaults(), Announce.defaults(), Headlines.shipped(),
                new ArrayList<>(com.dierks.homecraft.market.sim.Headlines.SAME_PLURAL),
                Seasons.defaults(), Real.defaults());
    }

    /** A copy with only {@code enabled} changed. */
    public SimSettings withEnabled(boolean on) {
        return new SimSettings(on, tickMinutes, maxCatchupHours, maxUpPercent, maxDownPercent, keepDays,
                drift, hot, deal, slotsPerItems, cooldownDays, popularWeight, news, announce, headlines,
                samePlural, seasons, real);
    }

    /** One tick in milliseconds. */
    public long tickMs() {
        return tickMinutes * MINUTE_MS;
    }

    /**
     * How far the whole mood may move, in whole percents, the SAME both ways: the narrower of
     * {@code max_up_percent} and {@code max_down_percent} (each already at most 25).
     *
     * <p>The band is always symmetric, {@code [1 - band, 1 + band]}. Narrowing only one side
     * would make the mood lean the other way: with {@code max_down_percent: 0} every dip is
     * clipped to 1.0 while every rise stands, no DEAL fits and DOWN flashes turn into UPs, so
     * the average multiplier sits a couple of percent above 1 — extra money on every sale, and
     * on unlimited volume for players who bypass the daily caps. Using the narrower side for
     * both keeps every part zero-mean, so narrowing either key can only make the market calmer.
     */
    public double bandPercent() {
        return Math.min(maxUpPercent, maxDownPercent);
    }

    /** The lower bound of the multiplier: {@code 1 - bandPercent/100}, never below 0.75. */
    public double multiplierLo() {
        return SimLimits.multiplierLo(bandPercent() / 100.0);
    }

    /** The upper bound of the multiplier: {@code 1 + bandPercent/100}, never above 1.25. */
    public double multiplierHi() {
        return SimLimits.multiplierHi(bandPercent() / 100.0);
    }

    /** {@link #samePlural} as a set, for {@code Headlines.plural}. */
    public Set<String> samePluralSet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(samePlural));
    }

    /** {@link #hot} for HOT, {@link #deal} for DEAL; {@code null} for any other kind. */
    public Story story(EventKind kind) {
        if (kind == EventKind.HOT) {
            return hot;
        }
        return kind == EventKind.DEAL ? deal : null;
    }

    // ---- nested sections ----------------------------------------------------------------

    /**
     * A {@code [low, high]} pair from config. NaN reads as 0 and the ends are sorted, so
     * {@code [15, 8]} is {@code [8, 15]}.
     */
    public record Range(double min, double max) {

        public Range {
            min = Double.isNaN(min) ? 0.0 : min;
            max = Double.isNaN(max) ? 0.0 : max;
            if (min > max) {
                double t = min;
                min = max;
                max = t;
            }
        }

        /** {@code min + (max - min) * u}. */
        public double lerp(double u) {
            return min + (max - min) * u;
        }

        /** Both ends squeezed into {@code [lo, hi]}. */
        public Range clamp(double lo, double hi) {
            return new Range(SimLimits.clamp(min, lo, hi), SimLimits.clamp(max, lo, hi));
        }
    }

    /**
     * The local-time window {@code news.hours} ({@code "07:00-21:00"}): from inclusive, to
     * exclusive. {@code from > to} wraps midnight; {@code from == to} is all day.
     */
    public record Hours(LocalTime from, LocalTime to) {

        /** 07:00-21:00, the shipped value and the fallback for a bad one. */
        public static final Hours DEFAULT = new Hours(LocalTime.of(7, 0), LocalTime.of(21, 0));

        private static final Pattern FORMAT = Pattern.compile("\\s*(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})\\s*");

        /** A missing end reads as the shipped one (07:00 or 21:00). */
        public Hours {
            from = from == null ? LocalTime.of(7, 0) : from;
            to = to == null ? LocalTime.of(21, 0) : to;
        }

        /** Parse {@code HH:MM-HH:MM}; {@code null} when it does not match. */
        public static Hours parse(String s) {
            if (s == null) {
                return null;
            }
            Matcher m = FORMAT.matcher(s);
            if (!m.matches()) {
                return null;
            }
            try {
                return new Hours(time(m.group(1)), time(m.group(2)));
            } catch (DateTimeException | NumberFormatException e) {
                return null;
            }
        }

        private static LocalTime time(String hhmm) {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                throw new NumberFormatException(hhmm);
            }
            return LocalTime.of(h, m);
        }

        /** True when {@code t} falls inside the window. */
        public boolean contains(LocalTime t) {
            if (t == null) {
                return false;
            }
            if (from.equals(to)) {
                return true;
            }
            if (from.isBefore(to)) {
                return !t.isBefore(from) && t.isBefore(to);
            }
            return !t.isBefore(from) || t.isBefore(to);
        }

        /** True when the window never closes. */
        public boolean allDay() {
            return from.equals(to);
        }

        /** {@code HH:MM-HH:MM}, the config spelling. */
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%02d:%02d-%02d:%02d",
                    from.getHour(), from.getMinute(), to.getHour(), to.getMinute());
        }
    }

    /**
     * {@code drift:} the quiet wander, an Ornstein–Uhlenbeck step each tick (§2.3).
     *
     * @param maxPercent    {@code |d|} never passes this (locked: at most 8)
     * @param calmPercent   σ for cheap staples (about 1% a day)
     * @param livelyPercent σ for dearer items (about 2% a day). Either one, times the item's
     *                      volatility, is held under the drift's speed lock when it becomes the
     *                      item's sigma ({@link SimLimits#driftSigmaCap}: 4.5% at a 66 h
     *                      half-life, less for a shorter one)
     * @param livelyFrom    "lively" when {@code sqrt(floor x ceiling)} is at least this many $
     * @param halfLifeHours half of any wander fades in this long (locked: at least 24)
     */
    public record Drift(double maxPercent, double calmPercent, double livelyPercent,
                        double livelyFrom, double halfLifeHours) {

        public Drift {
            maxPercent = pct(maxPercent, SimLimits.DRIFT_MAX * 100.0);
            calmPercent = nonNeg(calmPercent);
            livelyPercent = nonNeg(livelyPercent);
            livelyFrom = nonNeg(livelyFrom);
            halfLifeHours = SimLimits.clampDriftHalfLife(halfLifeHours);
        }

        public static Drift defaults() {
            return new Drift(8, 1.5, 3.0, 10.0, 66);
        }

        /** The drift bound {@code D = min(max_percent/100, 0.08)}. */
        public double maxFrac() {
            return SimLimits.clampDriftMax(maxPercent / 100.0);
        }
    }

    /**
     * {@code hot:} or {@code deal:} — the two stories share one shape. Keys only one of them
     * has are neutral in the other: {@code sellLimit} is false for DEAL, {@code minStockPercent}
     * and {@code buyLimitShare} are 0 for HOT.
     *
     * @param percent       strength at full strength (locked: at most 15)
     * @param holdHours     how long it stays at full strength
     * @param rampHours     silent rise before it is announced
     * @param fadeHours     cool-down at the end
     * @param gapHours      quiet time after one ends before the next can start
     * @param lastCallHours "Last call!" this long before it starts cooling (0 = never)
     * @param sellLimit     the key is {@code hot.sell_limit}, but it covers HOT and UP (a news
     *                      flash): while either runs on the item, its daily sell cap applies to
     *                      everyone; false for DEAL
     * @param minStockPercent DEAL only: only items holding this much of full_stock (and 16+)
     * @param buyLimitShare   DEAL only: "Limit N a day" as this share of max_daily_buy
     */
    public record Story(boolean enabled, Range percent, Range holdHours, double rampHours,
                        double fadeHours, Range gapHours, double lastCallHours, boolean sellLimit,
                        double minStockPercent, double buyLimitShare) {

        public Story {
            percent = (percent == null ? new Range(8, 15) : percent).clamp(0.0, SimLimits.STORY_MAX * 100.0);
            holdHours = (holdHours == null ? new Range(30, 54) : holdHours).clamp(0.0, Double.MAX_VALUE);
            rampHours = nonNeg(rampHours);
            fadeHours = nonNeg(fadeHours);
            gapHours = (gapHours == null ? new Range(48, 120) : gapHours).clamp(0.0, Double.MAX_VALUE);
            lastCallHours = nonNeg(lastCallHours);
            minStockPercent = SimLimits.clamp(minStockPercent, 0.0, 100.0);
            buyLimitShare = SimLimits.clamp(buyLimitShare, 0.0, 1.0);
        }

        public static Story hotDefaults() {
            return new Story(true, new Range(8, 15), new Range(30, 54), 4, 10, new Range(48, 120), 3,
                    true, 0, 0);
        }

        public static Story dealDefaults() {
            return new Story(true, new Range(8, 15), new Range(30, 54), 4, 10, new Range(48, 120), 3,
                    false, 3, 0.5);
        }

        /** The smallest strength, as a fraction (at most 0.15). */
        public double minFrac() {
            return SimLimits.clampStory(percent.min() / 100.0);
        }

        /** The largest strength, as a fraction (at most 0.15). */
        public double maxFrac() {
            return SimLimits.clampStory(percent.max() / 100.0);
        }

        /** {@code lerp(min, max, u)} as a fraction, within the code limit. */
        public double strength(double u) {
            return SimLimits.clampStory(percent.lerp(u) / 100.0);
        }

        public long rampMs() {
            return hoursMs(rampHours);
        }

        public long fadeMs() {
            return hoursMs(fadeHours);
        }

        public long lastCallMs() {
            return hoursMs(lastCallHours);
        }
    }

    /**
     * {@code news:} the NEWS FLASH schedule and shape.
     *
     * @param percent           jump size (locked: at most 25)
     * @param minPercent        a flash the limits would shrink below this is skipped
     * @param halfLifeHours     the jump fades by half every this long ...
     * @param lastsHours        ... and is gone after this long
     * @param gapHours          at least this long between flashes ...
     * @param extraHours        ... plus a random wait averaging this long ...
     * @param maxGapHours       ... but never longer than this
     * @param hours             local time when anything is announced
     * @param joinDelayMinutes  a due flash waits until someone has been online this long
     * @param maxHoldHours      then it happens anyway (silently)
     * @param minStockPercent   a DOWN flash needs at least this much stock
     * @param buyLimitShare     "Limit N a day" while DOWN
     * @param wantedShare       share of flashes that are WANTED (sold-out items)
     */
    public record News(boolean enabled, Range percent, double minPercent, double halfLifeHours,
                       double lastsHours, double gapHours, double extraHours, double maxGapHours,
                       int maxPerDay, Hours hours, boolean waitForPlayers, Range joinDelayMinutes,
                       double maxHoldHours, double itemCooldownHours, double minStockPercent,
                       double buyLimitShare, double wantedShare, int wantedEveryDays) {

        public News {
            percent = (percent == null ? new Range(15, 25) : percent).clamp(0.0, SimLimits.NEWS_MAX * 100.0);
            minPercent = pct(minPercent, SimLimits.NEWS_MAX * 100.0);
            halfLifeHours = nonNeg(halfLifeHours);
            lastsHours = nonNeg(lastsHours);
            gapHours = nonNeg(gapHours);
            extraHours = nonNeg(extraHours);
            maxGapHours = nonNeg(maxGapHours);
            maxPerDay = Math.max(0, maxPerDay);
            hours = hours == null ? Hours.DEFAULT : hours;
            joinDelayMinutes = (joinDelayMinutes == null ? new Range(2, 8) : joinDelayMinutes)
                    .clamp(0.0, Double.MAX_VALUE);
            maxHoldHours = nonNeg(maxHoldHours);
            itemCooldownHours = nonNeg(itemCooldownHours);
            minStockPercent = SimLimits.clamp(minStockPercent, 0.0, 100.0);
            buyLimitShare = SimLimits.clamp(buyLimitShare, 0.0, 1.0);
            wantedShare = SimLimits.clamp(wantedShare, 0.0, 1.0);
            wantedEveryDays = Math.max(0, wantedEveryDays);
        }

        public static News defaults() {
            return new News(true, new Range(15, 25), 10, 6, 30, 10, 14, 48, 2, Hours.DEFAULT, true,
                    new Range(2, 8), 14, 72, 5, 0.5, 0.15, 7);
        }

        /** The smallest jump, as a fraction (at most 0.25). */
        public double minFrac() {
            return SimLimits.clampNews(percent.min() / 100.0);
        }

        /** The largest jump, as a fraction (at most 0.25). */
        public double maxFrac() {
            return SimLimits.clampNews(percent.max() / 100.0);
        }

        /** {@code lerp(min, max, u)} as a fraction, within the code limit. */
        public double size(double u) {
            return SimLimits.clampNews(percent.lerp(u) / 100.0);
        }

        /** {@code min_percent} as a fraction: the smallest move worth a flash. */
        public double minMoveFrac() {
            return SimLimits.clampNews(minPercent / 100.0);
        }

        public long halfLifeMs() {
            return hoursMs(halfLifeHours);
        }

        public long lastsMs() {
            return hoursMs(lastsHours);
        }
    }

    /**
     * {@code announce:} how market news reaches players. Sound keys must be namespaced
     * ({@code minecraft:...}); a blank one falls back to the shipped key.
     */
    public record Announce(boolean chat, boolean title, boolean actionBar, boolean particles,
                           int minGapMinutes, int maxPerDay, boolean endings, boolean intro,
                           String soundUp, String soundDown, String soundStory, String soundOther,
                           double volume, double pitchUp, double pitchDown, boolean catchUp,
                           int catchUpLines, int catchUpHours) {

        public static final String BELL = "minecraft:block.note_block.bell";
        public static final String PLING = "minecraft:block.note_block.pling";
        public static final String CHIME = "minecraft:block.note_block.chime";

        public Announce {
            minGapMinutes = Math.max(0, minGapMinutes);
            maxPerDay = Math.max(0, maxPerDay);
            soundUp = sound(soundUp, BELL);
            soundDown = sound(soundDown, BELL);
            soundStory = sound(soundStory, PLING);
            soundOther = sound(soundOther, CHIME);
            volume = nonNeg(volume);
            pitchUp = nonNeg(pitchUp);
            pitchDown = nonNeg(pitchDown);
            catchUpLines = Math.max(0, catchUpLines);
            catchUpHours = Math.max(0, catchUpHours);
        }

        public static Announce defaults() {
            return new Announce(true, true, true, true, 20, 6, true, true,
                    BELL, BELL, PLING, CHIME, 0.8, 1.4, 0.8, true, 3, 48);
        }

        private static String sound(String key, String fallback) {
            return key == null || key.isBlank() ? fallback : key.trim();
        }
    }

    /**
     * {@code headlines:} the template lists as configured. The record keeps what it is given
     * (null reads as empty); falling back to the shipped list for an empty or invalid one is
     * the parser's job.
     */
    public record Headlines(List<String> up, List<String> down, List<String> hot, List<String> deal,
                            List<String> wanted, String realUp, String realDown) {

        public Headlines {
            up = strings(up);
            down = strings(down);
            hot = strings(hot);
            deal = strings(deal);
            wanted = strings(wanted);
            realUp = realUp == null ? "" : realUp;
            realDown = realDown == null ? "" : realDown;
        }

        /** The shipped lists. */
        public static Headlines shipped() {
            return new Headlines(com.dierks.homecraft.market.sim.Headlines.UP,
                    com.dierks.homecraft.market.sim.Headlines.DOWN,
                    com.dierks.homecraft.market.sim.Headlines.HOT,
                    com.dierks.homecraft.market.sim.Headlines.DEAL,
                    com.dierks.homecraft.market.sim.Headlines.WANTED,
                    com.dierks.homecraft.market.sim.Headlines.REAL_UP,
                    com.dierks.homecraft.market.sim.Headlines.REAL_DOWN);
        }

        /**
         * The list by config name: {@code up}, {@code down}, {@code hot}, {@code deal},
         * {@code wanted}, {@code real_up}, {@code real_down} (the last two as one-item lists).
         * Empty for anything else.
         */
        public List<String> list(String name) {
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "up" -> up;
                case "down" -> down;
                case "hot" -> hot;
                case "deal" -> deal;
                case "wanted" -> wanted;
                case "real_up" -> realUp.isEmpty() ? List.of() : List.of(realUp);
                case "real_down" -> realDown.isEmpty() ? List.of() : List.of(realDown);
                default -> List.of();
            };
        }
    }

    /**
     * {@code seasons:} the calendar layer.
     *
     * @param predictableMaxPercent seasons + real together never pass this (locked: at most
     *                              4.5, and at most 45% of the spread)
     * @param rampDays              each season fades in and out over this many days
     * @param list                  the seasons, in config order
     */
    public record Seasons(boolean enabled, double predictableMaxPercent, int rampDays, List<Season> list) {

        public Seasons {
            predictableMaxPercent = pct(predictableMaxPercent, SimLimits.PREDICTABLE_MAX * 100.0);
            rampDays = Math.max(0, rampDays);
            list = nonNull(list);
        }

        public static Seasons defaults() {
            return new Seasons(true, 4.5, 2, SeasonCalendar.SHIPPED);
        }

        /** {@code c_pred = min(predictable_max_percent/100, 0.45 x spread, 0.045)}. */
        public double predictableCap(double spread) {
            return SimLimits.predictableCap(predictableMaxPercent / 100.0, spread);
        }
    }

    /**
     * {@code real_world:} real commodity moves (off by default).
     *
     * @param provider             {@code stooq} or {@code yahoo} (lower-cased)
     * @param url                  blank = the provider's default; must be https
     * @param fetchTime            local, Monday to Friday
     * @param gain                 a real +1% day = +gain% here ...
     * @param maxPercent           ... never more than this (at most 4.5: seasons + real share
     *                             one cap anyway)
     * @param ignoreAbovePercent   a bigger real move is treated as bad data
     * @param announceAbovePercent real moves at least this big get a news line
     */
    public record Real(boolean enabled, String provider, String url, LocalTime fetchTime, double gain,
                       double maxPercent, double ignoreAbovePercent, double announceAbovePercent,
                       double halfLifeHours, double lastsHours, int timeoutSeconds,
                       List<RealSymbol> symbols) {

        public Real {
            provider = provider == null || provider.isBlank() ? "stooq" : provider.trim().toLowerCase(Locale.ROOT);
            url = url == null ? "" : url.trim();
            fetchTime = fetchTime == null ? LocalTime.of(17, 30) : fetchTime;
            gain = nonNeg(gain);
            maxPercent = pct(maxPercent, SimLimits.PREDICTABLE_MAX * 100.0);
            ignoreAbovePercent = nonNeg(ignoreAbovePercent);
            announceAbovePercent = nonNeg(announceAbovePercent);
            halfLifeHours = nonNeg(halfLifeHours);
            lastsHours = nonNeg(lastsHours);
            timeoutSeconds = Math.max(1, timeoutSeconds);
            symbols = nonNull(symbols);
        }

        public static Real defaults() {
            return new Real(false, "stooq", "", LocalTime.of(17, 30), 2.0, 4, 8, 2, 24, 96, 10, List.of(
                    new RealSymbol("gold_ingot", "gc.f", "GC=F", "gold"),
                    new RealSymbol("wheat", "zw.f", "ZW=F", "wheat"),
                    new RealSymbol("iron_ingot", "hg.f", "HG=F", "metal")));
        }

        /** {@code max_percent} as a fraction. */
        public double maxFrac() {
            return maxPercent / 100.0;
        }

        public double ignoreAboveFrac() {
            return ignoreAbovePercent / 100.0;
        }

        public double announceAboveFrac() {
            return announceAbovePercent / 100.0;
        }

        public long halfLifeMs() {
            return hoursMs(halfLifeHours);
        }

        public long lastsMs() {
            return hoursMs(lastsHours);
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    /** A whole percent squeezed into {@code [0, max]}; NaN reads as 0. */
    private static double pct(double v, double max) {
        return SimLimits.clamp(v, 0.0, max);
    }

    /** Negative and NaN read as 0. */
    private static double nonNeg(double v) {
        return Double.isNaN(v) || v < 0.0 ? 0.0 : v;
    }

    private static long hoursMs(double hours) {
        return Math.round(hours * HOUR_MS);
    }

    private static List<String> strings(List<String> in) {
        return nonNull(in);
    }

    /** An immutable copy with null elements dropped; {@code null} reads as empty. */
    private static <T> List<T> nonNull(List<T> in) {
        if (in == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>(in.size());
        for (T t : in) {
            if (t != null) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Lower-cased, trimmed, non-blank, duplicates dropped, order kept. */
    private static List<String> ids(List<String> in) {
        if (in == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }
}
