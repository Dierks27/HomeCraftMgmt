package com.dierks.homecraft.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A served feed: the JSON's UTF-8 bytes, and a gzip of them made once and reused. And when a
 * request may have the gzip — only when {@code Accept-Encoding} says so, with an explicit
 * {@code gzip;q=0} beating a {@code *}, and plain JSON whenever in doubt.
 */
class FeedPayloadTest {

    private static final String JSON = "{\"title\":\"Café Market — ÷ 日本 ⛏\",\"items\":[{\"id\":\"iron_ingot\"}]}";

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static boolean accepts(String... values) {
        return FeedPayload.acceptsGzip(Arrays.asList(values));
    }

    // ---- the payload -------------------------------------------------------------------

    @Test
    void rawIsTheJsonInUtf8() {
        FeedPayload p = new FeedPayload(JSON);
        assertArrayEquals(JSON.getBytes(StandardCharsets.UTF_8), p.raw());
        assertEquals(JSON, new String(p.raw(), StandardCharsets.UTF_8), "round-trips non-ASCII text");
        assertSame(p.raw(), p.raw(), "no copy per request");
    }

    @Test
    void gzippedDecompressesBackToRawAndIsMadeOnce() throws Exception {
        FeedPayload p = new FeedPayload(JSON);
        byte[] gz = p.gzipped();
        assertEquals(0x1f, gz[0] & 0xff, "gzip magic");
        assertEquals(0x8b, gz[1] & 0xff, "gzip magic");
        assertArrayEquals(p.raw(), gunzip(gz));
        assertSame(gz, p.gzipped(), "the second call reuses the first gzip");
    }

    @Test
    void aBigRepetitiveFeedShrinks() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 2_000; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"t\":").append(1_790_000_000_000L + i * 3_600_000L)
                    .append(",\"p\":2.31,\"s\":4020}");
        }
        FeedPayload p = new FeedPayload(sb.append("]}").toString());
        assertTrue(p.gzipped().length * 4 < p.raw().length, "history compresses well");
        assertArrayEquals(p.raw(), gunzip(p.gzipped()));
    }

    @Test
    void anEmptyFeedStillGzips() throws Exception {
        FeedPayload p = new FeedPayload("");
        assertEquals(0, p.raw().length);
        assertEquals(0, gunzip(p.gzipped()).length);
    }

    @Test
    void aNullJsonIsRefusedUpFront() {
        assertThrows(NullPointerException.class, () -> new FeedPayload(null));
    }

    @Test
    void concurrentFirstCallsAllGetTheSameGzip() throws Exception {
        FeedPayload p = new FeedPayload(JSON);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                start.await();
                return p.gzipped();
            }));
        }
        start.countDown();
        byte[] first = results.get(0).get(30, TimeUnit.SECONDS);
        for (Future<byte[]> f : results) {
            assertSame(first, f.get(30, TimeUnit.SECONDS), "compressed once, shared by every thread");
        }
        pool.shutdown();
    }

    // ---- Accept-Encoding -------------------------------------------------------------------

    @Test
    void noHeaderMeansNoGzip() {
        assertFalse(FeedPayload.acceptsGzip(null));
        assertFalse(FeedPayload.acceptsGzip(List.of()));
        assertFalse(accepts(""));
        assertFalse(accepts("   "));
        assertFalse(accepts(" , ,"));
        assertFalse(FeedPayload.acceptsGzip(Arrays.asList((String) null)));
    }

    @Test
    void gzipListedIsAccepted() {
        assertTrue(accepts("gzip"));
        assertTrue(accepts("GZIP"));
        assertTrue(accepts("Gzip"));
        assertTrue(accepts("x-gzip"));
        assertTrue(accepts("X-GZIP"));
        assertTrue(accepts("gzip, deflate, br"));
        assertTrue(accepts("deflate, gzip;q=0.5"));
        assertTrue(accepts("  deflate ,  gzip ; q = 0.5  "), "spaces anywhere are fine");
        assertTrue(accepts("gzip;q=1.0"));
        assertTrue(accepts("gzip;Q=0.001"), "the q parameter's name is case-insensitive too");
    }

    @Test
    void gzipWithQZeroIsRefused() {
        assertFalse(accepts("gzip;q=0"));
        assertFalse(accepts("gzip; q=0.000"));
        assertFalse(accepts("x-gzip;q=0"));
        assertFalse(accepts("deflate, gzip;q=0"));
    }

    @Test
    void anUnreadableQCountsAsOne() {
        assertTrue(accepts("gzip;q=abc"));
        assertTrue(accepts("gzip;q="));
        assertTrue(accepts("gzip;level=9"), "other parameters are ignored");
    }

    @Test
    void theWildcardDecidesOnlyWhenGzipIsNotListed() {
        assertTrue(accepts("*"));
        assertFalse(accepts("*;q=0"));
        assertTrue(accepts("br, *;q=0.1"));
        assertFalse(accepts("gzip;q=0, *"), "an explicit gzip;q=0 beats the wildcard");
        assertFalse(accepts("*, gzip;q=0"), "whatever the order");
        assertTrue(accepts("gzip, *;q=0"), "and an explicit gzip beats a refusing wildcard");
    }

    @Test
    void otherCodingsAloneMeanNoGzip() {
        assertFalse(accepts("identity"));
        assertFalse(accepts("deflate"));
        assertFalse(accepts("br, deflate, identity;q=0.5"));
        assertFalse(accepts("gzipped"), "only the exact coding name counts");
    }

    @Test
    void severalHeaderValuesAreReadTogether() {
        assertTrue(accepts("deflate", "gzip"));
        assertFalse(accepts("gzip;q=0", "*"));
        assertTrue(accepts("br", "*;q=0.2"));
        assertFalse(accepts("gzip", "gzip;q=0"), "listed twice, the refusal wins");
    }
}
