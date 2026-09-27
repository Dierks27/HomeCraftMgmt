package com.dierks.homecraft.web;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * The gate in front of the website feeds ({@code /api/*}): the optional shared secret
 * {@code web.dashboard.feed_token}, sent by the website as {@code Authorization: Bearer <token>}.
 *
 * <p><b>Blank token = open</b>, exactly as the feeds were before there was a token. Once it is set,
 * a request needs the right token — unless {@code web.dashboard.lan_skips_token} is on and it
 * comes straight from this PC or the home network. That switch ships <b>off</b>: the dashboard's
 * own page, which fetches {@code /api/market} from the browser, asks for the token once instead,
 * because the shipped way to reach the port from outside (a Playit agent on this PC) and most
 * tunnels deliver internet traffic from 127.0.0.1 or a LAN address.
 *
 * <p><b>A proxied request is never local.</b> A reverse proxy on the same machine or the LAN
 * connects from 127.0.0.1 or a LAN address on behalf of somebody on the internet; if it says so
 * with any {@link #FORWARDING_HEADERS forwarding header}, the request needs the token like any
 * other. A tunnel that adds no such header (ssh -R, WireGuard, frp, cloudflared…) cannot be told
 * apart from a local browser, which is why the config warns to turn the switch off behind one.
 *
 * <p><b>The token itself is never kept.</b> Only its SHA-256 digest is, and a request's token is
 * digested before the compare, so {@link MessageDigest#isEqual} always compares two 32-byte arrays:
 * constant time, and nothing leaks the real token's length either. Nothing here logs, prints or
 * returns the token; {@link #toString()} shows only the two switches.
 *
 * <p>Immutable and thread-safe: built on the main thread when the server starts, read by the HTTP
 * threads on every request.
 */
public final class FeedAuth {

    /**
     * Headers a proxy adds when it relays somebody else's request. Any one of them present means
     * the connection's own address says nothing about who is asking, so the LAN rule is off for it.
     */
    public static final List<String> FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-For",
            "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP",
            "X-Client-IP", "Fastly-Client-IP", "X-Cluster-Client-IP");

    private static final String SCHEME = "Bearer";

    /** SHA-256 of the configured token's UTF-8 bytes; {@code null} when the token is blank (open). */
    private final byte[] expectedDigest;
    private final boolean lanSkipsToken;

    /**
     * @param token         the configured {@code web.dashboard.feed_token}; trimmed, and
     *                      {@code null} or blank turns the gate off
     * @param lanSkipsToken {@code web.dashboard.lan_skips_token}: requests straight from this PC
     *                      or the LAN pass without the token
     */
    public FeedAuth(String token, boolean lanSkipsToken) {
        String t = token == null ? "" : token.trim();
        this.expectedDigest = t.isEmpty() ? null : sha256(t);
        this.lanSkipsToken = lanSkipsToken;
    }

    /** Whether a token is configured, i.e. whether the feeds are gated at all. */
    public boolean enabled() {
        return expectedDigest != null;
    }

    /** Whether requests straight from this PC or the LAN skip the token. */
    public boolean lanSkipsToken() {
        return lanSkipsToken;
    }

    /**
     * Whether a feed request may be served.
     *
     * @param authorization the request's {@code Authorization} header, or {@code null}
     * @param remote        the connection's remote address, or {@code null} if unknown
     * @param forwarded     whether the request carries a forwarding header
     *                      ({@link #looksForwarded}); a forwarded request is never treated as local
     */
    public boolean allows(String authorization, InetAddress remote, boolean forwarded) {
        if (!enabled()) {
            return true;
        }
        if (lanSkipsToken && !forwarded && isLocal(remote)) {
            return true;
        }
        String offered = bearer(authorization);
        if (offered == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(offered));
    }

    /**
     * The credentials of a {@code Bearer} {@code Authorization} header, trimmed, or {@code null} when
     * the header is missing, uses another scheme (e.g. {@code Basic}), or carries no credentials.
     * The scheme is matched case-insensitively, as HTTP requires.
     */
    public static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String h = authorization.trim();
        int split = 0;
        while (split < h.length() && !Character.isWhitespace(h.charAt(split))) {
            split++;
        }
        if (!h.substring(0, split).equalsIgnoreCase(SCHEME)) {
            return null;
        }
        String credentials = h.substring(split).trim();
        return credentials.isEmpty() ? null : credentials;
    }

    /**
     * Whether an address is this PC or the home network: loopback (127/8, ::1), private IPv4
     * (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) and IPv6 unique-local
     * (fc00::/7). An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is judged as the IPv4
     * address inside it. Everything else is not local — including CGNAT / Tailscale (100.64/10),
     * which is shared with strangers, and the wildcard 0.0.0.0.
     */
    public static boolean isLocal(InetAddress a) {
        if (a == null) {
            return false;
        }
        InetAddress addr = unmapped(a);
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        if (addr instanceof Inet6Address) {
            byte first = addr.getAddress()[0];
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }

    /**
     * Whether a request carries any {@link #FORWARDING_HEADERS forwarding header}. Header names
     * are compared case-insensitively by walking the keys, because a plain map keeps whatever case
     * it was given (the JDK server's {@code Headers} normalises it, a plain map does not).
     */
    public static boolean looksForwarded(Map<String, List<String>> headers) {
        if (headers == null) {
            return false;
        }
        for (String key : headers.keySet()) {
            if (key == null) {
                continue;
            }
            for (String forwarding : FORWARDING_HEADERS) {
                if (key.equalsIgnoreCase(forwarding)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only the switches: the token (and its digest) never reach a log line. */
    @Override
    public String toString() {
        return "FeedAuth[enabled=" + enabled() + ", lanSkipsToken=" + lanSkipsToken + "]";
    }

    // ---- helpers ------------------------------------------------------------------

    /**
     * An IPv4-mapped IPv6 address as its IPv4 address. The JDK already converts most of them, but
     * {@link Inet6Address#getByAddress(String, byte[], int)} keeps the mapped form, whose
     * {@code isSiteLocalAddress()} says {@code false} even for ::ffff:192.168.1.5.
     */
    private static InetAddress unmapped(InetAddress a) {
        if (!(a instanceof Inet6Address)) {
            return a;
        }
        byte[] b = a.getAddress();
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return a;
            }
        }
        if (b[10] != (byte) 0xff || b[11] != (byte) 0xff) {
            return a;
        }
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            return a; // unreachable: four bytes is always a valid IPv4 address
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
