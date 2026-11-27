package com.dierks.homecraft.games.chance.sportsbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.60.0: the games whose live scores the Sports Board keeps (pure, tested; the main thread's own): every game on the
 * board that has started, from its start until {@link #KEEP_MS} after it, with the last score read for it.
 *
 * <p>A game is read while it is being played, and once more when it is over (its final score); after that it is never
 * read again, but stays shown until it is {@link #KEEP_MS} past its start. A finished game leaves Kalshi's list of
 * open games, so it is kept here (and in {@code hcm_meta}, {@link #toJson}) rather than looked up on the board.
 */
public final class LiveScores {

    /** How long after its start a game is kept (and its final score shown). */
    public static final long KEEP_MS = 12 * 3_600_000L;

    /**
     * A game being followed.
     *
     * @param event     its event ticker (a pick's market ticker starts with it)
     * @param league    its league's config name
     * @param title     "Miami vs New York"
     * @param away      the visitors' short name
     * @param home      the home side's short name
     * @param startsAt  its start
     * @param milestone its milestone at Kalshi
     * @param kind      the milestone's type
     */
    public record Game(String event, String league, String title, String away, String home, long startsAt,
                       String milestone, String kind) {

        static Game of(Board.Game g) {
            String[] t = g.away().isEmpty() || g.home().isEmpty() ? Scores.teams("", g.title())
                    : new String[]{g.away(), g.home()};
            return new Game(g.event(), g.league(), g.title(), t[0], t[1], g.startsAt(), g.milestone(), g.kind());
        }
    }

    /**
     * A game and what is known of its score.
     *
     * @param game   the game
     * @param live   its last score read, or {@code null} for none yet
     * @param readAt when it was read (0: never)
     */
    public record Entry(Game game, Scores.Live live, long readAt) {

        /** Where it is: {@link Scores.Phase#UPCOMING} until a score is read. */
        public Scores.Phase phase() {
            return live == null ? Scores.Phase.UPCOMING : live.phase();
        }

        /** Its score as one line ({@link Scores#line}). */
        public String line() {
            return Scores.line(game.away(), game.home(), live);
        }

        /** Its score for the ticker ({@link Scores#ticker}). */
        public String ticker() {
            return Scores.ticker(game.away(), game.home(), live);
        }
    }

    private final Map<String, Entry> games = new LinkedHashMap<>();

    /** Follow every game of {@code seen} that has started (by {@code now}) and isn't {@link #KEEP_MS} old. */
    public void seen(Collection<Board.Game> seen, long now) {
        for (Board.Game g : seen) {
            if (g.milestone().isEmpty() || g.kind().isEmpty() || g.startsAt() > now || g.startsAt() <= now - KEEP_MS) {
                continue;
            }
            Game game = Game.of(g);
            Entry e = games.get(g.event());
            games.put(g.event(), e == null ? new Entry(game, null, 0) : new Entry(game, e.live(), e.readAt()));
        }
    }

    /** Stop following the games {@link #KEEP_MS} past their start. @return whether any went */
    public boolean prune(long now) {
        return games.values().removeIf(e -> e.game().startsAt() <= now - KEEP_MS);
    }

    /**
     * The games to read now, at most {@code max}: every game not yet read as over, those with open picks first
     * ({@code wanted}: event tickers), then the ones read longest ago, then the earliest started.
     */
    public List<Game> due(long now, int max, Set<String> wanted) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : games.values()) {
            if (e.game().startsAt() <= now && e.phase() != Scores.Phase.FINAL) {
                out.add(e);
            }
        }
        out.sort(Comparator.<Entry>comparingInt(e -> wanted != null && wanted.contains(e.game().event()) ? 0 : 1)
                .thenComparingLong(Entry::readAt)
                .thenComparingLong(e -> e.game().startsAt())
                .thenComparing(e -> e.game().event()));
        List<Game> games = new ArrayList<>();
        for (int i = 0; i < out.size() && i < Math.max(0, max); i++) {
            games.add(out.get(i).game());
        }
        return games;
    }

    /**
     * A score was read for {@code event}. @return true when it is the first reading of the game as over (its final
     * score is known now)
     */
    public boolean read(String event, Scores.Live live, long now) {
        Entry e = games.get(event);
        if (e == null || live == null) {
            return false;
        }
        boolean wasFinal = e.phase() == Scores.Phase.FINAL;
        games.put(event, new Entry(e.game(), live, now));
        return !wasFinal && live.phase() == Scores.Phase.FINAL;
    }

    /** The game {@code event}, or {@code null}. */
    public Entry get(String event) {
        return games.get(event);
    }

    /** The game a pick's market ({@code KXNBAGAME-26NOV27MIANYK-NYK}) is on, or {@code null}. */
    public Entry forMarket(String ticker) {
        if (ticker == null) {
            return null;
        }
        for (Entry e : games.values()) {
            if (ticker.startsWith(e.game().event() + "-") || ticker.equals(e.game().event())) {
                return e;
            }
        }
        return null;
    }

    /** A market's game's final score ("Final: CGY 6  PIT 3"), or {@code null} while it isn't known. */
    public String finalFor(String ticker) {
        Entry e = forMarket(ticker);
        return e != null && e.phase() == Scores.Phase.FINAL ? e.line() : null;
    }

    /**
     * What the screens show: the games being played, earliest started first, then the finished ones, latest
     * started first. Games with no score read yet aren't shown.
     */
    public List<Entry> shown() {
        List<Entry> live = new ArrayList<>();
        List<Entry> done = new ArrayList<>();
        for (Entry e : games.values()) {
            if (e.phase() == Scores.Phase.LIVE) {
                live.add(e);
            } else if (e.phase() == Scores.Phase.FINAL) {
                done.add(e);
            }
        }
        live.sort(Comparator.comparingLong((Entry e) -> e.game().startsAt()).thenComparing(e -> e.game().event()));
        done.sort(Comparator.comparingLong((Entry e) -> -e.game().startsAt()).thenComparing(e -> e.game().event()));
        live.addAll(done);
        return live;
    }

    /** How many games are followed. */
    public int size() {
        return games.size();
    }

    // ---- kept between restarts -------------------------------------------------------------------------

    /** The games and their scores as text for {@code hcm_meta}. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonArray list = new JsonArray();
        for (Entry e : games.values()) {
            Game g = e.game();
            JsonObject o = new JsonObject();
            o.addProperty("e", g.event());
            o.addProperty("l", g.league());
            o.addProperty("t", g.title());
            o.addProperty("a", g.away());
            o.addProperty("h", g.home());
            o.addProperty("s", g.startsAt());
            o.addProperty("m", g.milestone());
            o.addProperty("k", g.kind());
            o.addProperty("r", e.readAt());
            Scores.Live l = e.live();
            if (l != null) {
                JsonObject x = new JsonObject();
                x.addProperty("ap", l.away());
                x.addProperty("hp", l.home());
                x.addProperty("p", l.period());
                x.addProperty("pt", l.periodType());
                x.addProperty("c", l.clock());
                x.addProperty("st", l.status());
                x.addProperty("w", l.widget());
                x.addProperty("k", l.kind());
                x.addProperty("tp", l.top());
                o.add("live", x);
            }
            list.add(o);
        }
        root.add("games", list);
        return root.toString();
    }

    /** Games kept by {@link #toJson} (anything unreadable is left out). */
    public static LiveScores fromJson(String text) {
        LiveScores out = new LiveScores();
        if (text == null || text.isBlank()) {
            return out;
        }
        try {
            for (JsonElement el : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("games")) {
                JsonObject o = el.getAsJsonObject();
                Game g = new Game(s(o, "e"), s(o, "l"), s(o, "t"), s(o, "a"), s(o, "h"), o.get("s").getAsLong(),
                        s(o, "m"), s(o, "k"));
                Scores.Live live = null;
                if (o.has("live") && o.get("live").isJsonObject()) {
                    JsonObject x = o.getAsJsonObject("live");
                    live = new Scores.Live(x.get("ap").getAsInt(), x.get("hp").getAsInt(), x.get("p").getAsInt(),
                            s(x, "pt"), s(x, "c"), s(x, "st"), s(x, "w"), s(x, "k"), s(x, "tp"));
                }
                if (!g.event().isEmpty()) {
                    out.games.put(g.event(), new Entry(g, live, o.has("r") ? o.get("r").getAsLong() : 0));
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    private static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
