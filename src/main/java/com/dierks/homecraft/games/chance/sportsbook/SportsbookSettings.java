package com.dierks.homecraft.games.chance.sportsbook;

import com.dierks.homecraft.config.GamesConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Sports Board's settings: {@code games.sportsbook}.
 *
 * <p>Nothing here sets a price: every side's chance is a real market's price on Kalshi, read every
 * {@code refresh_minutes}. What a pick gives back for that chance is {@code rtp} of every 100 put in over time
 * ({@link Lines}), inside the 85-95 band locked in code, and never more than the Game Parlor's biggest payout. The
 * money rules (who may, the day's limit, the biggest stake, the chips) are the Parlor's own ({@code /hcm games parlor}).
 *
 * @param enabled        the game's own switch (it only shows while the Parlor's money play is on)
 * @param leagues        the leagues on the board, as their short names ({@code nba}) or Kalshi series tickers
 * @param refreshMinutes how often the lines are read, and the open picks' games checked
 * @param hoursAhead     how far ahead a game may start and still be on the board
 * @param rtp            what a pick gives back over time, a percent inside the band
 * @param settleDays     a pick whose game is still undecided this many days after it started is given back
 * @param apiBase        Kalshi's public market data, no key ({@code .../trade-api/v2})
 * @param liveScores     0.60.0: the games under way have their live scores read (the sports corner's TVs and ticker,
 *                       and a settled pick's final score)
 * @param scoreSeconds   how often the live scores are read
 * @param scoreGames     the most games whose scores are read each time
 */
public record SportsbookSettings(boolean enabled, List<String> leagues, int refreshMinutes, int hoursAhead, double rtp,
                                 int settleDays, String apiBase, boolean liveScores, int scoreSeconds,
                                 int scoreGames) {

    public static final String ID = "sportsbook";
    /** The leaves under {@code games.sportsbook}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "leagues", "refresh_minutes", "hours_ahead", "rtp",
            "settle_days", "api_base", "live_scores", "score_seconds", "score_games");
    /** Kalshi's public API, as shipped. */
    public static final String API_BASE = "https://api.elections.kalshi.com/trade-api/v2";

    public static final int REFRESH_MIN = 2;
    public static final int REFRESH_MAX = 60;
    public static final int HOURS_MIN = 1;
    public static final int HOURS_MAX = 72;
    public static final int SETTLE_MIN = 1;
    public static final int SETTLE_MAX = 30;
    public static final int SCORE_SECONDS_MIN = 30;
    public static final int SCORE_SECONDS_MAX = 600;
    public static final int SCORE_GAMES_MIN = 1;
    public static final int SCORE_GAMES_MAX = 30;
    /** The most leagues the board has tabs for. */
    public static final int MAX_LEAGUES = 8;

    /**
     * The leagues the board knows by name: their tab and the Kalshi series of their game-winner markets ("who wins").
     * Any other {@code KX...} series ticker may be listed too; its tab is its ticker.
     */
    public static final Map<String, League> KNOWN = known();

    /**
     * One league on the board.
     *
     * @param key    its config name ({@code nba}), or the series ticker it was listed as
     * @param tab    what its tab says ({@code NBA})
     * @param series the Kalshi series of its "who wins" markets ({@code KXNBAGAME})
     */
    public record League(String key, String tab, String series) {
    }

    private static Map<String, League> known() {
        Map<String, League> m = new LinkedHashMap<>();
        m.put("nfl", new League("nfl", "NFL", "KXNFLGAME"));
        m.put("nba", new League("nba", "NBA", "KXNBAGAME"));
        m.put("nhl", new League("nhl", "NHL", "KXNHLGAME"));
        m.put("mlb", new League("mlb", "MLB", "KXMLBGAME"));
        m.put("ncaaf", new League("ncaaf", "College Football", "KXNCAAFGAME"));
        m.put("ncaamb", new League("ncaamb", "College Basketball", "KXNCAAMBGAME"));
        m.put("ncaawb", new League("ncaawb", "Women's College Basketball", "KXNCAAWBGAME"));
        m.put("wnba", new League("wnba", "WNBA", "KXWNBAGAME"));
        return java.util.Collections.unmodifiableMap(m);
    }

    public SportsbookSettings {
        leagues = List.copyOf(leagues);
    }

    /** The settings with the live scores as shipped (read every minute, 16 games at a time). */
    public SportsbookSettings(boolean enabled, List<String> leagues, int refreshMinutes, int hoursAhead, double rtp,
                              int settleDays, String apiBase) {
        this(enabled, leagues, refreshMinutes, hoursAhead, rtp, settleDays, apiBase, true, 60, 16);
    }

    /**
     * The shipped settings: the big leagues' game winners (a league out of season simply has no games), read every 5
     * minutes, games starting in the next 24 hours, 90 of every 100 back, a pick still undecided a week after its game
     * given back; 0.60.0: the live scores of the games under way read every minute, 16 games at a time.
     */
    public static SportsbookSettings defaults() {
        return new SportsbookSettings(true, List.of("nfl", "nba", "nhl", "mlb", "ncaaf", "ncaamb"), 5, 24, 90, 7,
                API_BASE, true, 60, 16);
    }

    /** Read {@code games.sportsbook} over {@code d}; never throws. */
    public static SportsbookSettings parse(GamesConfig.Node n, SportsbookSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<String> leagues = new ArrayList<>();
        for (String raw : n.stringList("leagues", d.leagues())) {
            String k = raw.trim().toLowerCase(Locale.ROOT);
            if (league(k) == null) {
                n.warn(n.key("leagues") + " has \"" + raw + "\", which is not a league the Sports Board knows ("
                        + String.join(", ", KNOWN.keySet()) + ", or a Kalshi series ticker like KXNBAGAME) - left out");
                continue;
            }
            if (!leagues.contains(k)) {
                leagues.add(k);
            }
        }
        if (leagues.size() > MAX_LEAGUES) {
            n.warn(n.key("leagues") + " lists " + leagues.size() + " leagues - the board has room for "
                    + MAX_LEAGUES + "; the rest are left out");
            leagues = new ArrayList<>(leagues.subList(0, MAX_LEAGUES));
        }
        int refresh = n.whole("refresh_minutes", d.refreshMinutes(), REFRESH_MIN, REFRESH_MAX);
        int hours = n.whole("hours_ahead", d.hoursAhead(), HOURS_MIN, HOURS_MAX);
        double rtp = n.rtp("rtp", d.rtp());
        int settle = n.whole("settle_days", d.settleDays(), SETTLE_MIN, SETTLE_MAX);
        String api = n.text("api_base", d.apiBase());
        if (!api.startsWith("https://")) {
            n.warn(n.key("api_base") + " \"" + api + "\" is not an https address - using " + d.apiBase());
            api = d.apiBase();
        }
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        boolean live = n.bool("live_scores", d.liveScores());
        int every = n.whole("score_seconds", d.scoreSeconds(), SCORE_SECONDS_MIN, SCORE_SECONDS_MAX);
        int most = n.whole("score_games", d.scoreGames(), SCORE_GAMES_MIN, SCORE_GAMES_MAX);
        return new SportsbookSettings(enabled, leagues, refresh, hours, rtp, settle, api, live, every, most);
    }

    /** How often the live scores are read, in milliseconds. */
    public long scoreMs() {
        return scoreSeconds * 1_000L;
    }

    /** A league by its config name or series ticker (any case), or {@code null} for neither. */
    public static League league(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        League known = KNOWN.get(k);
        if (known != null) {
            return known;
        }
        for (League l : KNOWN.values()) {
            if (l.series().equalsIgnoreCase(k)) {
                return l;
            }
        }
        String upper = k.toUpperCase(Locale.ROOT);
        if (upper.matches("KX[A-Z0-9]{2,40}")) {
            String tab = upper.substring(2);
            if (tab.endsWith("GAME") && tab.length() > 4) {
                tab = tab.substring(0, tab.length() - 4);
            }
            return new League(k, tab, upper);
        }
        return null;
    }

    /** The leagues on the board, in config order. */
    public List<League> boardLeagues() {
        List<League> out = new ArrayList<>();
        for (String k : leagues) {
            League l = league(k);
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** {@code rtp} in tenths of a percent (90 is 900): what the pure maths takes. */
    public int rtpTenths() {
        return (int) Math.round(rtp * 10);
    }

    /** The refresh period in milliseconds. */
    public long refreshMs() {
        return refreshMinutes * 60_000L;
    }
}
