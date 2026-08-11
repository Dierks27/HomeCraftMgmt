package com.dierks.homecraft.integration;

import com.dierks.homecraft.HomeCraftManagement;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fetches head textures from minecraft-heads.com's category JSON API — the most
 * reliable published path (the site exports {@code scripts/api.php?cat=<slug>}).
 * The one field we lift is each head's Base64 {@code value}, which we render
 * natively; there is no runtime dependency on the site once imported.
 *
 * <p>All network I/O runs off the main thread; callbacks hop back onto it. A
 * whole category is fetched once and cached, then paged/filtered in-memory — so
 * we never sweep the whole site and flipping pages is instant. Fetches are
 * politely rate-limited and every failure degrades to a clear error (the admin
 * can still add Minis by hand).
 */
public final class HeadLibraryService {

    /** A browseable category: a friendly label and the API slug it maps to. */
    public record Category(String label, String slug) {
    }

    /**
     * The site's main head categories. Slugs match the api.php {@code cat}
     * parameter; if the site renames one, that button simply reports an empty
     * result and the others keep working.
     */
    public static final List<Category> CATEGORIES = List.of(
            new Category("Animals", "animals"),
            new Category("Food & Drinks", "food-drinks"),
            new Category("Alphabet", "alphabet"),
            new Category("Blocks", "blocks"),
            new Category("Decoration", "decoration"),
            new Category("Humans", "humans"),
            new Category("Humanoid", "humanoid"),
            new Category("Monsters", "monsters"),
            new Category("Plants", "plants"),
            new Category("Miscellaneous", "miscellaneous"));

    private static final long MIN_INTERVAL_MS = 1500L;

    private final HomeCraftManagement plugin;
    private final HttpClient http;
    private final Map<String, List<HeadEntry>> cache = new ConcurrentHashMap<>();
    private volatile long lastFetch = 0L;

    public HeadLibraryService(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isCached(String slug) {
        return cache.containsKey(slug);
    }

    /**
     * Fetch a category (cached after the first hit). {@code onOk}/{@code onError}
     * are invoked on the main thread, so they may touch GUIs safely.
     */
    public void fetchCategory(String slug, Consumer<List<HeadEntry>> onOk, Consumer<String> onError) {
        List<HeadEntry> cached = cache.get(slug);
        if (cached != null) {
            onOk.accept(cached);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                rateLimit();
                List<HeadEntry> entries = request(slug);
                cache.put(slug, entries);
                onMain(() -> onOk.accept(entries));
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                plugin.getLogger().warning("Head library fetch failed for '" + slug + "': " + message);
                onMain(() -> onError.accept(message));
            }
        });
    }

    private synchronized void rateLimit() {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastFetch);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        lastFetch = System.currentTimeMillis();
    }

    private List<HeadEntry> request(String slug) throws IOException, InterruptedException {
        String url = "https://minecraft-heads.com/scripts/api.php?cat="
                + URLEncoder.encode(slug, StandardCharsets.UTF_8) + "&tags=true";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "HomeCraftMgmt-Collectibles-Import")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return parse(resp.body(), slug);
    }

    /** Parse the JSON array, tolerating field-name and shape variations defensively. */
    private List<HeadEntry> parse(String body, String slug) throws IOException {
        List<HeadEntry> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException ex) {
            throw new IOException("unexpected response (not JSON)");
        }
        if (!root.isJsonArray()) {
            return out;
        }
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String value = firstString(o, "value", "texture", "Value");
            if (value == null || value.isBlank()) {
                continue;
            }
            String name = firstString(o, "name", "title", "Name");
            String tags = firstString(o, "tags", "tag", "Tags");
            out.add(new HeadEntry(name == null || name.isBlank() ? "Head" : name, value, slug,
                    tags == null ? "" : tags));
        }
        return out;
    }

    private String firstString(JsonObject o, String... keys) {
        for (String key : keys) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsString();
            }
            if (o.has(key) && o.get(key).isJsonArray()) {
                // e.g. tags as an array — join it.
                StringBuilder sb = new StringBuilder();
                for (JsonElement e : o.get(key).getAsJsonArray()) {
                    if (e.isJsonPrimitive()) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(e.getAsString());
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        }
        return null;
    }

    private void onMain(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }
}
