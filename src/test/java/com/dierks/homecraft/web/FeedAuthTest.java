package com.dierks.homecraft.web;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feed token gate. A blank token leaves the feeds open, as they were; a set one needs
 * {@code Authorization: Bearer <token>}, exactly, with only the scheme's case forgiven. With
 * {@code lan_skips_token} on, this PC and the home network pass without it — but never a request
 * a proxy says it forwarded, and never CGNAT/Tailscale or a public address. IPv4-mapped IPv6 is
 * judged as the IPv4 inside it. The token never shows up in {@code toString()}.
 */
class FeedAuthTest {

    private static final String TOKEN = "s3cret-Token_42";
    private static final String GOOD = "Bearer " + TOKEN;

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal); // literals only: no DNS lookup
    }

    /** A real IPv4-mapped {@link Inet6Address}; {@code getByName} would quietly hand back an IPv4. */
    private static InetAddress mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, -1);
    }

    // ---- token off -------------------------------------------------------------------

    @Test
    void aBlankTokenLeavesTheFeedsOpen() throws Exception {
        for (String blank : new String[] {null, "", "   ", "\t\n"}) {
            FeedAuth auth = new FeedAuth(blank, false);
            assertFalse(auth.enabled(), "blank token '" + blank + "' turns the gate off");
            assertTrue(auth.allows(null, ip("8.8.8.8"), false), "no header, public address, still open");
            assertTrue(auth.allows(null, null, true), "no header, no address, forwarded, still open");
            assertTrue(auth.allows("Bearer anything", ip("8.8.8.8"), true), "any token is fine too");
        }
    }

    // ---- the header ------------------------------------------------------------------

    @Test
    void aMissingHeaderIsRefused() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertTrue(auth.enabled());
        assertFalse(auth.allows(null, ip("8.8.8.8"), false));
        assertFalse(auth.allows("", ip("8.8.8.8"), false));
        assertFalse(auth.allows(null, null, false), "an unknown address is not local");
    }

    @Test
    void theRightTokenIsAllowedAndAWrongOneIsNot() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertTrue(auth.allows(GOOD, ip("8.8.8.8"), false));
        assertTrue(auth.allows(GOOD, ip("8.8.8.8"), true), "a proxied request with the token is fine");
        assertFalse(auth.allows("Bearer wrong", ip("8.8.8.8"), false));
        assertFalse(auth.allows("Bearer " + TOKEN.toUpperCase(Locale.ROOT), ip("8.8.8.8"), false),
                "the token itself is case-sensitive");
    }

    @Test
    void theSchemeIsCaseInsensitive() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertTrue(auth.allows("bearer " + TOKEN, ip("8.8.8.8"), false));
        assertTrue(auth.allows("BEARER " + TOKEN, ip("8.8.8.8"), false));
        assertTrue(auth.allows("BeArEr " + TOKEN, ip("8.8.8.8"), false));
    }

    @Test
    void extraWhitespaceAroundTheHeaderAndTheTokenIsForgiven() throws Exception {
        FeedAuth auth = new FeedAuth("  " + TOKEN + "  ", false); // the configured value is trimmed too
        assertTrue(auth.allows("  Bearer    " + TOKEN + "   ", ip("8.8.8.8"), false));
        assertTrue(auth.allows("Bearer\t" + TOKEN, ip("8.8.8.8"), false), "a tab separates as well");
        assertTrue(auth.allows(GOOD, ip("8.8.8.8"), false));
    }

    @Test
    void aBasicHeaderIsRefusedEvenCarryingTheToken() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertFalse(auth.allows("Basic " + TOKEN, ip("8.8.8.8"), false));
        assertFalse(auth.allows("Basic dXNlcjpwYXNz", ip("8.8.8.8"), false));
        assertFalse(auth.allows(TOKEN, ip("8.8.8.8"), false), "the bare token is not a Bearer header");
        assertFalse(auth.allows("Bearer" + TOKEN, ip("8.8.8.8"), false), "the scheme needs a space after it");
    }

    @Test
    void bearerWithNoCredentialsIsRefused() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertFalse(auth.allows("Bearer", ip("8.8.8.8"), false));
        assertFalse(auth.allows("Bearer    ", ip("8.8.8.8"), false));
    }

    @Test
    void aPrefixOfTheTokenOrTheTokenWithMoreIsRefused() throws Exception {
        FeedAuth auth = new FeedAuth(TOKEN, false);
        assertFalse(auth.allows("Bearer " + TOKEN.substring(0, TOKEN.length() - 1), ip("8.8.8.8"), false));
        assertFalse(auth.allows("Bearer " + TOKEN.substring(0, 1), ip("8.8.8.8"), false));
        assertFalse(auth.allows("Bearer " + TOKEN + "x", ip("8.8.8.8"), false));
        assertFalse(auth.allows("Bearer " + TOKEN + " " + TOKEN, ip("8.8.8.8"), false));
    }

    @Test
    void bearerExtractsOnlyBearerCredentials() {
        assertEquals("abc", FeedAuth.bearer("Bearer abc"));
        assertEquals("abc", FeedAuth.bearer("  bearer   abc  "));
        assertEquals("a b", FeedAuth.bearer("Bearer a b"), "inner whitespace stays part of the credentials");
        assertNull(FeedAuth.bearer(null));
        assertNull(FeedAuth.bearer(""));
        assertNull(FeedAuth.bearer("   "));
        assertNull(FeedAuth.bearer("Bearer"));
        assertNull(FeedAuth.bearer("Bearer   "));
        assertNull(FeedAuth.bearer("Basic abc"));
        assertNull(FeedAuth.bearer("Bearerabc"));
        assertNull(FeedAuth.bearer("Token abc"));
    }

    // ---- the LAN rule ----------------------------------------------------------------

    @Test
    void loopbackSkipsTheTokenOnlyWhileTheSwitchIsOn() throws Exception {
        FeedAuth on = new FeedAuth(TOKEN, true);
        FeedAuth off = new FeedAuth(TOKEN, false);
        for (String loopback : new String[] {"127.0.0.1", "127.8.9.10", "::1"}) {
            assertTrue(on.allows(null, ip(loopback), false), loopback + " with the switch on");
            assertFalse(off.allows(null, ip(loopback), false), loopback + " with the switch off needs the token");
            assertFalse(off.allows("Bearer wrong", ip(loopback), false));
            assertTrue(off.allows(GOOD, ip(loopback), false), loopback + " with the switch off and the token");
        }
    }

    @Test
    void theHomeNetworkSkipsTheTokenWhileTheSwitchIsOn() throws Exception {
        FeedAuth on = new FeedAuth(TOKEN, true);
        FeedAuth off = new FeedAuth(TOKEN, false);
        String[] lan = {"10.0.0.5", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.20",
                "169.254.10.10", "fe80::1", "fe80::abcd:1234", "fd00::1", "fdab:cdef::42", "fc00::1"};
        for (String address : lan) {
            assertTrue(FeedAuth.isLocal(ip(address)), address + " is local");
            assertTrue(on.allows(null, ip(address), false), address + " passes with the switch on");
            assertFalse(off.allows(null, ip(address), false), address + " needs the token with the switch off");
        }
    }

    @Test
    void publicCgnatAndOtherAddressesNeedTheToken() throws Exception {
        FeedAuth on = new FeedAuth(TOKEN, true);
        String[] outside = {"8.8.8.8", "1.1.1.1", "100.64.1.1", "100.100.100.100", "172.32.0.1",
                "172.15.255.255", "192.169.0.1", "11.0.0.1", "0.0.0.0", "2001:4860::", "2001:4860:4860::8888",
                "::", "fe00::1", "2606:4700::1111"};
        for (String address : outside) {
            assertFalse(FeedAuth.isLocal(ip(address)), address + " is not local");
            assertFalse(on.allows(null, ip(address), false), address + " needs the token");
            assertTrue(on.allows(GOOD, ip(address), false), address + " with the token is fine");
        }
        assertFalse(FeedAuth.isLocal(null));
    }

    @Test
    void anIpv4MappedAddressIsJudgedAsItsIpv4() throws Exception {
        FeedAuth on = new FeedAuth(TOKEN, true);
        // The JDK's own conversion (getByName hands back an Inet4Address)...
        assertTrue(FeedAuth.isLocal(ip("::ffff:192.168.1.5")));
        assertFalse(FeedAuth.isLocal(ip("::ffff:8.8.8.8")));
        // ...and a mapped address that stays an Inet6Address, whose own isSiteLocalAddress() says no.
        InetAddress lan = mapped(192, 168, 1, 5);
        InetAddress loop = mapped(127, 0, 0, 1);
        InetAddress google = mapped(8, 8, 8, 8);
        InetAddress cgnat = mapped(100, 64, 1, 1);
        assertTrue(lan instanceof Inet6Address, "the test really exercises the mapped form");
        assertTrue(FeedAuth.isLocal(lan));
        assertTrue(FeedAuth.isLocal(loop));
        assertFalse(FeedAuth.isLocal(google));
        assertFalse(FeedAuth.isLocal(cgnat));
        assertTrue(on.allows(null, lan, false));
        assertFalse(on.allows(null, google, false));
    }

    @Test
    void aForwardedRequestFromALocalAddressNeedsTheToken() throws Exception {
        FeedAuth on = new FeedAuth(TOKEN, true);
        for (String address : new String[] {"127.0.0.1", "::1", "192.168.1.20", "10.0.0.5"}) {
            assertFalse(on.allows(null, ip(address), true), address + " behind a proxy is not local");
            assertFalse(on.allows("Bearer wrong", ip(address), true));
            assertTrue(on.allows(GOOD, ip(address), true), address + " behind a proxy with the token");
        }
    }

    // ---- forwarding headers ----------------------------------------------------------

    @Test
    void looksForwardedMatchesHeaderNamesInAnyCase() {
        assertFalse(FeedAuth.looksForwarded(null));
        assertFalse(FeedAuth.looksForwarded(Map.of()));
        assertFalse(FeedAuth.looksForwarded(Map.of("Accept", List.of("*/*"),
                "Authorization", List.of(GOOD), "User-Agent", List.of("curl/8"))));

        for (String name : FeedAuth.FORWARDING_HEADERS) {
            assertTrue(FeedAuth.looksForwarded(Map.of(name, List.of("203.0.113.9"))), name);
            assertTrue(FeedAuth.looksForwarded(Map.of(name.toLowerCase(Locale.ROOT), List.of("203.0.113.9"))),
                    name + " in lower case");
            assertTrue(FeedAuth.looksForwarded(Map.of(name.toUpperCase(Locale.ROOT), List.of("203.0.113.9"))),
                    name + " in upper case");
        }
        // The JDK server's Headers normalises "x-forwarded-for" to "X-forwarded-for".
        assertTrue(FeedAuth.looksForwarded(Map.of("X-forwarded-for", List.of("203.0.113.9"))));
        assertTrue(FeedAuth.looksForwarded(Map.of("Cf-connecting-ip", List.of())), "presence is enough");

        Map<String, List<String>> withNullKey = new HashMap<>();
        withNullKey.put(null, List.of("HTTP/1.1 200 OK"));
        assertFalse(FeedAuth.looksForwarded(withNullKey));
        withNullKey.put("x-real-ip", List.of("203.0.113.9"));
        assertTrue(FeedAuth.looksForwarded(withNullKey));
    }

    // ---- secrecy -----------------------------------------------------------------------

    @Test
    void toStringShowsOnlyTheSwitches() {
        FeedAuth auth = new FeedAuth(TOKEN, true);
        String s = auth.toString();
        assertFalse(s.contains(TOKEN), s);
        assertFalse(s.contains("s3cret"), s);
        assertEquals("FeedAuth[enabled=true, lanSkipsToken=true]", s);
        assertEquals("FeedAuth[enabled=false, lanSkipsToken=false]", new FeedAuth("", false).toString());
        assertTrue(auth.lanSkipsToken());
        assertFalse(new FeedAuth(TOKEN, false).lanSkipsToken());
    }

    @Test
    void theRetiredIpv6SiteLocalRangeIsNotTheHomeNetwork() throws Exception {
        assertFalse(FeedAuth.isLocal(ip("fec0::1")));
        assertFalse(new FeedAuth(TOKEN, true).allows(null, ip("fec0::1"), false));
    }

    @Test
    void aUtf8TokenOnTheWireMatchesTheConfiguredOne() {
        FeedAuth gate = new FeedAuth("clé-secrète-42", false);
        // What the JDK server hands over for a client that sent the token's UTF-8 bytes.
        String wire = new String("Bearer clé-secrète-42".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(gate.allows(wire, null, false), "read as ISO-8859-1 it does not match");
        assertTrue(gate.allows(FeedAuth.fromWire(wire), null, false), "re-read as UTF-8 it does");
        assertEquals(GOOD, FeedAuth.fromWire(GOOD), "an ASCII header comes back unchanged");
        assertNull(FeedAuth.fromWire(null));
    }

    @Test
    void onlyPlainAsciiTokensCountAsSendable() {
        assertTrue(FeedAuth.isPlainAscii("a1b2-C3_d4.e5 ~!"));
        assertTrue(FeedAuth.isPlainAscii(""));
        assertTrue(FeedAuth.isPlainAscii(null));
        assertFalse(FeedAuth.isPlainAscii("pässwörd"));
        assertFalse(FeedAuth.isPlainAscii("tab\there"));
        assertFalse(FeedAuth.isPlainAscii("euro€"));
    }
}
