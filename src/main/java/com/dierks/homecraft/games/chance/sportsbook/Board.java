package com.dierks.homecraft.games.chance.sportsbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One reading of tonight's games (pure, tested): every game on the board's leagues that starts within
 * {@code hours_ahead}, each with its sides and their prices, as Kalshi had them at {@link #fetchedAt}.
 *
 * <p>The last good one is kept in {@code hcm_meta} ({@link #toJson}), so a restart shows the games again at once;
 * it takes no picks until a fresh reading comes in ({@link Sportsbook#linesOpen}).
 *
 * @param fetchedAt when it was read (epoch ms), 0 for never
 * @param games     the games, soonest first
 */
public record Board(long fetchedAt, List<Game> games) {

    /** No reading yet. */
    public static final Board EMPTY = new Board(0, List.of());

    public Board {
        games = List.copyOf(games);
    }

    /**
     * One game.
     *
     * @param event    Kalshi's event ticker ({@code KXNBAGAME-26NOV23SASSAC})
     * @param league   the league's config name ({@code nba})
     * @param title    "San Antonio vs Sacramento"
     * @param startsAt when it starts (epoch ms): picks close then
     * @param state    the game's state as Kalshi has it ({@code scheduled}, {@code inprogress}, ...; "" for unknown)
     * @param sides    who can be picked, each with its price
     * @param milestone 0.60.0: the game's milestone at Kalshi (its live score is read by it), "" for unknown
     * @param kind      the milestone's type ({@code basketball_game}, {@code hockey_match}, ...), "" for unknown
     * @param away      the visitors' short name ("MIA"), "" for unknown
     * @param home      the home side's short name ("NYK"), "" for unknown
     */
    public record Game(String event, String league, String title, long startsAt, String state, List<Side> sides,
                       String milestone, String kind, String away, String home) {

        public Game {
            sides = List.copyOf(sides);
            milestone = milestone == null ? "" : milestone;
            kind = kind == null ? "" : kind;
            away = away == null ? "" : away;
            home = home == null ? "" : home;
        }

        /** A game with no milestone or short names (a board kept before 0.60.0, and the tests' own). */
        public Game(String event, String league, String title, long startsAt, String state, List<Side> sides) {
            this(event, league, title, startsAt, state, sides, "", "", "", "");
        }

        /** Whether picks may still be made on it at {@code now}: before its start, and not under way at Kalshi. */
        public boolean open(long now) {
            return now < startsAt && (state.isEmpty() || "scheduled".equalsIgnoreCase(state)
                    || "pre".equalsIgnoreCase(state));
        }
    }

    /**
     * One side of a game: a market's "yes" (Minnesota wins) or, for a game with one market, its "no".
     *
     * @param ticker the market's ticker
     * @param side   {@code yes} or {@code no}
     * @param label  who it is ("Minnesota")
     * @param bid    the best bid in mills ({@link Lines#NONE} for none)
     * @param ask    the best ask in mills
     * @param last   the last trade in mills
     * @param price  the side's price in mills ({@link Lines#price}), {@link Lines#NONE} when it has none
     */
    public record Side(String ticker, String side, String label, int bid, int ask, int last, int price) {

        /** The side as a pick names it: {@code ticker:side}. */
        public String key() {
            return ticker + ":" + side;
        }
    }

    /** The games starting after {@code now}, soonest first, in one league ({@code null}: every league). */
    public List<Game> upcoming(long now, String league) {
        List<Game> out = new ArrayList<>();
        for (Game g : games) {
            if (g.startsAt() > now && (league == null || league.equals(g.league()))) {
                out.add(g);
            }
        }
        out.sort(Comparator.comparingLong(Game::startsAt).thenComparing(Game::title));
        return out;
    }

    /** The game and side a pick names, or {@code null}. */
    public Found find(String ticker, String side) {
        for (Game g : games) {
            for (Side s : g.sides()) {
                if (s.ticker().equals(ticker) && s.side().equals(side)) {
                    return new Found(g, s);
                }
            }
        }
        return null;
    }

    /** A side found on the board, with its game. */
    public record Found(Game game, Side side) {
    }

    /** How many markets (sides) the board holds. */
    public int sideCount() {
        int n = 0;
        for (Game g : games) {
            n += g.sides().size();
        }
        return n;
    }

    // ---- kept between restarts -------------------------------------------------------------------------

    /** The board as text for {@code hcm_meta}. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("at", fetchedAt);
        JsonArray gs = new JsonArray();
        for (Game g : games) {
            JsonObject o = new JsonObject();
            o.addProperty("e", g.event());
            o.addProperty("l", g.league());
            o.addProperty("t", g.title());
            o.addProperty("s", g.startsAt());
            o.addProperty("st", g.state());
            if (!g.milestone().isEmpty()) { // 0.60.0
                o.addProperty("ms", g.milestone());
                o.addProperty("mt", g.kind());
                o.addProperty("aw", g.away());
                o.addProperty("hm", g.home());
            }
            JsonArray ss = new JsonArray();
            for (Side s : g.sides()) {
                JsonObject x = new JsonObject();
                x.addProperty("m", s.ticker());
                x.addProperty("d", s.side());
                x.addProperty("n", s.label());
                x.addProperty("b", s.bid());
                x.addProperty("a", s.ask());
                x.addProperty("z", s.last());
                x.addProperty("p", s.price());
                ss.add(x);
            }
            o.add("sides", ss);
            gs.add(o);
        }
        root.add("games", gs);
        return root.toString();
    }

    private static String opt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    /** A board kept by {@link #toJson}, or {@link #EMPTY} for nothing or nonsense. */
    public static Board fromJson(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            List<Game> games = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("games")) {
                JsonObject o = e.getAsJsonObject();
                List<Side> sides = new ArrayList<>();
                for (JsonElement se : o.getAsJsonArray("sides")) {
                    JsonObject x = se.getAsJsonObject();
                    sides.add(new Side(x.get("m").getAsString(), x.get("d").getAsString(), x.get("n").getAsString(),
                            x.get("b").getAsInt(), x.get("a").getAsInt(), x.get("z").getAsInt(),
                            x.get("p").getAsInt()));
                }
                games.add(new Game(o.get("e").getAsString(), o.get("l").getAsString(), o.get("t").getAsString(),
                        o.get("s").getAsLong(), o.get("st").getAsString(), sides, opt(o, "ms"), opt(o, "mt"),
                        opt(o, "aw"), opt(o, "hm")));
            }
            return new Board(root.get("at").getAsLong(), games);
        } catch (RuntimeException e) {
            return EMPTY;
        }
    }
}
