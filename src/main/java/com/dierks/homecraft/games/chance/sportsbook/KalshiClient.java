package com.dierks.homecraft.games.chance.sportsbook;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Sports Board's reads from Kalshi, made OFF the main thread (the caller hands the result back to it).
 *
 * <p>Plain {@code GET}s of public market data: no key, no cookie, nothing about any player. One {@link HttpClient}
 * (connect 10 s, each request 20 s, the JVM's own proxy settings), {@code User-Agent: HomeCraftManagement}, https
 * only, replies cut off at {@link #MAX_BODY_BYTES}. Requests go one after another, {@link #SPACING_MS} apart (Kalshi
 * limits how fast a reader may ask); a "too many requests" is asked again once, a little later.
 */
public final class KalshiClient {

    /** The largest reply read. */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    /** Requests are this far apart. */
    static final long SPACING_MS = 1_100L;
    /** A "too many requests" is asked again after this long, once. */
    static final long RETRY_MS = 4_000L;
    /** The most pages of one league's games read in one refresh. */
    static final int MAX_PAGES = 5;
    /** The most markets asked about in one request. */
    static final int TICKERS_A_REQUEST = 20;
    static final String USER_AGENT = "HomeCraftManagement";

    /** One GET of {@code api_base + path}: the reply's text, or an exception saying why not. */
    @FunctionalInterface
    public interface Fetcher {
        String get(String path) throws IOException, InterruptedException;
    }

    /** Waiting between requests (a test's waits nothing). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * What one refresh read.
     *
     * @param games   the board's games, or {@code null} when the lines weren't asked for or couldn't be read
     * @param markets the open picks' markets that were read, by ticker
     * @param error   why something couldn't be read, or {@code null} when everything was
     */
    public record Result(List<Board.Game> games, Map<String, Kalshi.Market> markets, String error) {
    }

    private KalshiClient() {
    }

    /**
     * One refresh: every league's games starting after {@code now} and by {@code until} (when {@code lines}), then the
     * markets of {@code tickers}. Any league that can't be read fails the lines as a whole (the board keeps its last
     * reading); markets that were read are kept even when a later batch fails.
     */
    public static Result fetch(Fetcher fetcher, Sleeper sleeper, List<SportsbookSettings.League> leagues,
                               List<String> tickers, long now, long until, boolean lines) {
        List<Board.Game> games = lines ? new ArrayList<>() : null;
        Map<String, Kalshi.Market> markets = new LinkedHashMap<>();
        String error = null;
        boolean first = true;
        try {
            if (lines) {
                for (SportsbookSettings.League l : leagues) {
                    String cursor = "";
                    for (int page = 0; page < MAX_PAGES; page++) {
                        if (!first) {
                            sleeper.sleep(SPACING_MS);
                        }
                        first = false;
                        Kalshi.Page p = Kalshi.events(get(fetcher, sleeper, Kalshi.eventsPath(l.series(), cursor)),
                                l.key(), now, until);
                        games.addAll(p.games());
                        cursor = p.cursor();
                        if (cursor == null || cursor.isBlank() || p.events() < Kalshi.PAGE) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            games = null;
            error = describe(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, markets, "interrupted");
        }
        try {
            for (int i = 0; i < tickers.size(); i += TICKERS_A_REQUEST) {
                if (!first) {
                    sleeper.sleep(SPACING_MS);
                }
                first = false;
                List<String> batch = tickers.subList(i, Math.min(tickers.size(), i + TICKERS_A_REQUEST));
                markets.putAll(Kalshi.markets(get(fetcher, sleeper, Kalshi.marketsPath(batch))));
            }
        } catch (IOException | IllegalArgumentException e) {
            error = error != null ? error : describe(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        return new Result(games, markets, error);
    }

    /** One GET, asked again once after a "too many requests". */
    private static String get(Fetcher fetcher, Sleeper sleeper, String path) throws IOException, InterruptedException {
        try {
            return check(fetcher.get(path));
        } catch (TooMany e) {
            sleeper.sleep(RETRY_MS);
            return check(fetcher.get(path));
        }
    }

    /** Kalshi's "too many requests", as a reply or a status. */
    static final class TooMany extends IOException {
        TooMany() {
            super("too many requests");
        }
    }

    private static String check(String body) throws IOException {
        if (body != null && body.length() < 400 && body.contains("too_many_requests")) {
            throw new TooMany();
        }
        return body;
    }

    static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ---- the live fetcher ------------------------------------------------------------------------------------

    /** The real GET over {@code apiBase} (https only). */
    public static Fetcher http(String apiBase) {
        if (apiBase == null || !apiBase.startsWith("https://")) {
            throw new IllegalArgumentException("api_base must be https: " + apiBase);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault()) // the JVM's own proxy settings (https.proxyHost ...)
                .followRedirects(HttpClient.Redirect.NORMAL) // never https -> http
                .build();
        return path -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() == 429) {
                    throw new TooMany();
                }
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode());
                }
                byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new IOException("reply over " + (MAX_BODY_BYTES / 1024 / 1024) + " MB");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        };
    }
}
