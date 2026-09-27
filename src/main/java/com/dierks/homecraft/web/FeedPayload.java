package com.dierks.homecraft.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * One built feed, ready to serve: the JSON as UTF-8 bytes, plus its gzip, made the first time a
 * client asks for it and then kept. The main-thread refresh builds a new payload each cycle and
 * swaps it in; the HTTP threads only read it, so the JSON is encoded once per refresh rather than
 * once per request, and compressed at most once.
 *
 * <p>Gzip is worth it here: a market item carries up to 96 + 168 + 120 history points, which is
 * mostly repeated keys and digits.
 *
 * <p>Thread-safe. The arrays handed out are the payload's own (no copy per request), so callers
 * must not modify them.
 */
public final class FeedPayload {

    private final byte[] raw;
    private volatile byte[] gzipped;

    /** @param json the feed's JSON text; never {@code null} */
    public FeedPayload(String json) {
        this.raw = Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8);
    }

    /** The JSON as UTF-8 bytes. */
    public byte[] raw() {
        return raw;
    }

    /** The JSON gzipped; compressed on the first call, the same array on every call after. */
    public byte[] gzipped() {
        byte[] g = gzipped;
        if (g == null) {
            synchronized (this) {
                g = gzipped;
                if (g == null) {
                    g = gzip(raw);
                    gzipped = g;
                }
            }
        }
        return g;
    }

    /**
     * Whether a request's {@code Accept-Encoding} header values allow a gzip response. Each value
     * is a comma-separated list of {@code name[;q=x]} codings; names are case-insensitive and
     * spaces are tolerated. {@code gzip} (or its old alias {@code x-gzip}) decides when it is
     * listed: acceptable when its q is above zero (no q means 1, an unreadable q is read as 1, and
     * if it is listed more than once the lowest q counts). Otherwise a {@code *} decides the same
     * way. Otherwise — including no header at all — the answer is no: plain JSON is always safe.
     */
    public static boolean acceptsGzip(List<String> acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        double gzipQ = Double.NaN;
        double anyQ = Double.NaN;
        for (String value : acceptEncoding) {
            if (value == null) {
                continue;
            }
            for (String coding : value.split(",")) {
                String[] parts = coding.split(";");
                String name = parts[0].trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                double q = quality(parts);
                if (name.equals("gzip") || name.equals("x-gzip")) {
                    gzipQ = Double.isNaN(gzipQ) ? q : Math.min(gzipQ, q);
                } else if (name.equals("*")) {
                    anyQ = Double.isNaN(anyQ) ? q : Math.min(anyQ, q);
                }
            }
        }
        if (!Double.isNaN(gzipQ)) {
            return gzipQ > 0;
        }
        if (!Double.isNaN(anyQ)) {
            return anyQ > 0;
        }
        return false;
    }

    // ---- helpers ------------------------------------------------------------------

    /** The {@code q} parameter among a coding's parameters (parts[1..]); 1 when absent or unreadable. */
    private static double quality(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i];
            int eq = param.indexOf('=');
            if (eq < 0 || !param.substring(0, eq).trim().equalsIgnoreCase("q")) {
                continue;
            }
            try {
                double q = Double.parseDouble(param.substring(eq + 1).trim());
                return Double.isNaN(q) ? 1.0 : q;
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }
        return 1.0;
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            // A ByteArrayOutputStream never throws; this only satisfies the signature.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
