package com.dierks.homecraft.games.chance.sportsbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Kalshi's public market data says, read into the Sports Board's own records (pure: text in, records out, never
 * the network; tested on real replies kept under {@code src/test/resources/kalshi/}).
 *
 * <p><b>What is asked</b> (no key, no player data, just these paths under {@code api_base}):
 * <ul>
 *   <li>{@code GET /events?series_ticker=<league's series>&status=open&with_nested_markets=true&with_milestones=true
 *       &limit=200[&cursor=...]}: the league's open games, each with its markets (one per team: "Minnesota wins") and
 *       the game's milestone, which carries its real start ({@code start_date}) and state ({@code details.status}:
 *       {@code scheduled}, {@code inprogress}, ...). A market's own {@code close_time} is a day or two after the game
 *       (it trades while the game is played), so it is never taken as the start: a game with no milestone has no
 *       known start and isn't offered.</li>
 *   <li>{@code GET /markets?tickers=a,b,c}: the markets of open picks, to settle them ({@code status} and
 *       {@code result}).</li>
 * </ul>
 *
 * <p><b>Fields read</b> from a market: {@code ticker}, {@code status} ({@code active} takes picks),
 * {@code market_type} ({@code binary}), {@code yes_sub_title}/{@code no_sub_title} (who), {@code yes_bid_dollars},
 * {@code yes_ask_dollars}, {@code no_bid_dollars}, {@code no_ask_dollars}, {@code last_price_dollars} (prices as
 * dollar strings; the older whole-cent fields {@code yes_bid}, {@code yes_ask}, {@code last_price} are read when the
 * dollar ones are missing) and {@code result} ({@code yes}, {@code no}, {@code void}, or empty until decided).
 */
public final class Kalshi {

    /** The page size asked for. */
    public static final int PAGE = 100;

    private Kalshi() {
    }

    /**
     * One page of a league's games: the games read from it, the cursor for the next page ("" for the last), how many
     * events it held, and the earliest start among them (epoch ms; {@link Long#MAX_VALUE} for none known): Kalshi
     * lists a league's games soonest first, so a page that starts after the board's window ends the reading.
     */
    public record Page(List<Board.Game> games, String cursor, int events, long earliest) {
    }

    /** What a market says about how it ended. */
    public enum Outcome {
        /** Not decided yet. */
        OPEN,
        /** "Yes" won. */
        YES,
        /** "No" won. */
        NO,
        /** Called off (voided, or settled at a fair price rather than a winner): every pick on it is given back. */
        VOID
    }

    /**
     * A market as the settle check reads it.
     *
     * @param ticker its ticker
     * @param status Kalshi's status ({@code active}, {@code closed}, {@code determined}, {@code finalized}, ...)
     * @param result Kalshi's result ({@code yes}, {@code no}, {@code void}, ... or "" while undecided)
     */
    public record Market(String ticker, String status, String result) {

        /** How it ended, or {@link Outcome#OPEN} while it hasn't. */
        public Outcome outcome() {
            String r = result == null ? "" : result.trim().toLowerCase(Locale.ROOT);
            String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
            boolean done = s.equals("determined") || s.equals("settled") || s.equals("finalized");
            if (r.equals("yes") && (done || s.equals("closed"))) {
                return Outcome.YES;
            }
            if (r.equals("no") && (done || s.equals("closed"))) {
                return Outcome.NO;
            }
            if (r.equals("void")) {
                return Outcome.VOID;
            }
            // settled with no winner (a game called off settles "at a fair price"): given back
            return (s.equals("settled") || s.equals("finalized")) && !r.equals("yes") && !r.equals("no")
                    ? Outcome.VOID : Outcome.OPEN;
        }
    }

    // ---- what to ask ---------------------------------------------------------------------------------------

    /** The path and query for one page of a league's open games. */
    public static String eventsPath(String series, String cursor) {
        String q = "/events?series_ticker=" + enc(series) + "&status=open&with_nested_markets=true"
                + "&with_milestones=true&limit=" + PAGE;
        return cursor == null || cursor.isBlank() ? q : q + "&cursor=" + enc(cursor);
    }

    /** The path and query for these markets (at most a few dozen tickers at a time). */
    public static String marketsPath(List<String> tickers) {
        return "/markets?tickers=" + enc(String.join(",", tickers)) + "&limit=" + PAGE;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- reading ------------------------------------------------------------------------------------------

    /**
     * One page of {@code /events}: the games of {@code league} that start after {@code now} and no later than
     * {@code until}, each with the sides it offers (prices may be {@link Lines#NONE}; the board decides what is
     * offered).
     *
     * @throws IllegalArgumentException when the reply is Kalshi's error, or isn't a page of events at all
     */
    public static Page events(String json, String league, long now, long until) {
        JsonObject root = object(json);
        JsonArray events = array(root, "events");
        if (events == null) {
            throw new IllegalArgumentException("no events in the reply");
        }
        Map<String, Milestone> starts = milestones(array(root, "milestones"));
        List<Board.Game> out = new ArrayList<>();
        long earliest = Long.MAX_VALUE;
        for (JsonElement e : events) {
            if (!e.isJsonObject()) {
                continue;
            }
            Milestone ms = starts.get(text(e.getAsJsonObject(), "event_ticker"));
            if (ms != null) {
                earliest = Math.min(earliest, ms.startsAt());
            }
            Board.Game g = game(e.getAsJsonObject(), league, starts);
            if (g != null && g.startsAt() > now && g.startsAt() <= until && !g.sides().isEmpty()) {
                out.add(g);
            }
        }
        return new Page(out, text(root, "cursor"), events.size(), earliest);
    }

    /**
     * The markets in a {@code /markets} reply (a list) or a {@code /markets/{ticker}} reply (one), by ticker.
     *
     * @throws IllegalArgumentException when the reply is Kalshi's error, or holds no markets at all
     */
    public static Map<String, Market> markets(String json) {
        JsonObject root = object(json);
        Map<String, Market> out = new LinkedHashMap<>();
        JsonArray list = array(root, "markets");
        if (list != null) {
            for (JsonElement e : list) {
                if (e.isJsonObject()) {
                    Market m = market(e.getAsJsonObject());
                    out.put(m.ticker(), m);
                }
            }
            return out;
        }
        if (root.has("market") && root.get("market").isJsonObject()) {
            Market m = market(root.getAsJsonObject("market"));
            out.put(m.ticker(), m);
            return out;
        }
        throw new IllegalArgumentException("no markets in the reply");
    }

    private static Market market(JsonObject m) {
        return new Market(text(m, "ticker"), text(m, "status"), text(m, "result"));
    }

    /** A game's start and state, from its milestone. */
    private record Milestone(long startsAt, String state) {
    }

    private static Map<String, Milestone> milestones(JsonArray list) {
        Map<String, Milestone> out = new HashMap<>();
        if (list == null) {
            return out;
        }
        for (JsonElement e : list) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            long start = time(text(m, "start_date"));
            if (start <= 0) {
                continue;
            }
            JsonObject details = m.has("details") && m.get("details").isJsonObject() ? m.getAsJsonObject("details")
                    : new JsonObject();
            Milestone ms = new Milestone(start, text(details, "status"));
            String main = text(details, "main_game_event_ticker");
            if (!main.isEmpty()) {
                out.put(main, ms);
            }
            JsonArray primary = array(m, "primary_event_tickers");
            if (primary != null) {
                for (JsonElement t : primary) {
                    if (t.isJsonPrimitive()) {
                        out.putIfAbsent(t.getAsString(), ms);
                    }
                }
            }
        }
        return out;
    }

    /** One event as a game, or {@code null} when it has no known start or nothing to pick. */
    private static Board.Game game(JsonObject e, String league, Map<String, Milestone> starts) {
        String event = text(e, "event_ticker");
        Milestone ms = starts.get(event);
        if (event.isEmpty() || ms == null) {
            return null; // no known start: a market's close is days after the game, never its start
        }
        JsonArray markets = array(e, "markets");
        if (markets == null) {
            return null;
        }
        List<JsonObject> live = new ArrayList<>();
        for (JsonElement me : markets) {
            if (me.isJsonObject()) {
                JsonObject m = me.getAsJsonObject();
                String type = text(m, "market_type");
                if ("active".equalsIgnoreCase(text(m, "status")) && (type.isEmpty() || "binary".equalsIgnoreCase(type))) {
                    live.add(m);
                }
            }
        }
        List<Board.Side> sides = new ArrayList<>();
        if (live.size() == 1) { // one market: its yes and its no are the two sides
            JsonObject m = live.get(0);
            sides.add(side(m, "yes"));
            sides.add(side(m, "no"));
        } else {
            for (JsonObject m : live) { // one market a team: each team is its market's yes
                sides.add(side(m, "yes"));
            }
        }
        String title = text(e, "title");
        if (title.isEmpty()) {
            title = text(e, "sub_title");
        }
        return new Board.Game(event, league, title, ms.startsAt(), ms.state(), sides);
    }

    /** One side of a market. */
    private static Board.Side side(JsonObject m, String side) {
        boolean yes = side.equals("yes");
        int yesBid = price(m, "yes_bid_dollars", "yes_bid");
        int yesAsk = price(m, "yes_ask_dollars", "yes_ask");
        int last = price(m, "last_price_dollars", "last_price");
        int bid;
        int ask;
        int lastSide;
        if (yes) {
            bid = yesBid;
            ask = yesAsk;
            lastSide = last;
        } else {
            int noBid = price(m, "no_bid_dollars", "no_bid");
            int noAsk = price(m, "no_ask_dollars", "no_ask");
            bid = noBid != Lines.NONE ? noBid : Lines.other(yesAsk);
            ask = noAsk != Lines.NONE ? noAsk : Lines.other(yesBid);
            lastSide = Lines.other(last);
        }
        String label = text(m, yes ? "yes_sub_title" : "no_sub_title");
        if (label.isEmpty()) {
            String title = text(m, "title");
            label = yes ? title.replaceFirst("(?i)\\s+wins\\??$", "") : "Not " + title.replaceFirst("(?i)\\s+wins\\??$", "");
        }
        return new Board.Side(text(m, "ticker"), side, label, bid, ask, lastSide, Lines.price(bid, ask, lastSide));
    }

    /** A price in mills from its dollar field, else its whole-cent field, else {@link Lines#NONE}. */
    private static int price(JsonObject m, String dollars, String cents) {
        String d = text(m, dollars);
        if (!d.isEmpty()) {
            return Lines.mills(d);
        }
        JsonElement c = m.get(cents);
        if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber()) {
            return Lines.millsOfCents(c.getAsLong());
        }
        return Lines.NONE;
    }

    // ---- JSON helpers --------------------------------------------------------------------------------------

    private static JsonObject object(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the reply isn't JSON");
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the reply isn't a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        if (o.has("error")) {
            JsonElement err = o.get("error");
            String code = err.isJsonObject() ? text(err.getAsJsonObject(), "code") : err.toString();
            throw new IllegalArgumentException("Kalshi said: " + (code.isEmpty() ? "error" : code));
        }
        return o;
    }

    private static JsonArray array(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString().trim();
    }

    /** An ISO time as epoch ms, or 0. */
    static long time(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                return 0;
            }
        }
    }
}
