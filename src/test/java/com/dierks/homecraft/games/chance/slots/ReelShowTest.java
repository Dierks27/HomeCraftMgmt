package com.dierks.homecraft.games.chance.slots;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reels' show (spec §2, §5.3): fixed stop ticks that never depend on the result, fewer frames
 * on Bedrock, and cosmetic frames that show exactly the drawn symbols once a reel stops and never
 * a paying line before the last one does.
 */
class ReelShowTest {

    private static SlotsEngine shipped() {
        return SlotsEngine.solve(map("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 2),
                map("two", 2, "coal", 4, "copper", 6, "iron", 10, "gold", 20, "diamond", 40, "wild", 50),
                List.of(1, 2, 5), 250, 90);
    }

    @Test
    void theReelsStopOnFixedTicks() {
        assertArrayEquals(new int[]{8, 14, 20}, ReelShow.stops(false), "Java: ticks 8, 14 and 20");
        assertArrayEquals(new int[]{10, 20, 30}, ReelShow.stops(true), "Bedrock: three frames, a longer period");
        assertEquals(2, ReelShow.period(false));
        assertEquals(10, ReelShow.period(true));
        assertEquals(0, ReelShow.stopped(false, 6));
        assertEquals(1, ReelShow.stopped(false, 8));
        assertEquals(2, ReelShow.stopped(false, 14));
        assertEquals(3, ReelShow.stopped(false, 20), "a Java spin lasts one second");
        assertEquals(3, ReelShow.stopped(true, 30), "a Bedrock spin lasts a second and a half");
        for (boolean bedrock : new boolean[]{false, true}) {
            int frames = 0;
            for (int tick = ReelShow.period(bedrock); ReelShow.stopped(bedrock, tick) < 3; tick += ReelShow.period(bedrock)) {
                frames++;
            }
            assertEquals(bedrock ? 2 : 9, frames, "frames before the last stop (bedrock " + bedrock + ")");
            for (int stop : ReelShow.stops(bedrock)) {
                assertEquals(0, stop % ReelShow.period(bedrock), "every stop lands on a frame");
            }
        }
    }

    @Test
    void aFrameNeverShowsAPayingLineBeforeTheLastReelStops() {
        SlotsEngine e = shipped();
        SplittableRandom cosmetic = new SplittableRandom(7);
        for (Symbol a : Symbol.values()) {
            for (Symbol b : Symbol.values()) {
                for (Symbol c : Symbol.values()) {
                    List<Symbol> result = List.of(a, b, c);
                    for (int stopped = 0; stopped < 3; stopped++) {
                        for (int k = 0; k < 20; k++) {
                            List<Symbol> shown = ReelShow.frame(e, 5, result, stopped, cosmetic);
                            for (int i = 0; i < stopped; i++) {
                                assertEquals(result.get(i), shown.get(i), "a stopped reel shows what was drawn");
                            }
                            if (!shown.contains(null)) {
                                assertNull(e.line(5, shown.get(0), shown.get(1), shown.get(2)),
                                        result + " with " + stopped + " stopped showed a paying " + shown);
                            }
                        }
                    }
                    assertEquals(result, ReelShow.frame(e, 5, result, 3, cosmetic), "the last frame is the result");
                }
            }
        }
    }

    @Test
    void aSpinningReelBesideAStoppedPairIsBlank() {
        SlotsEngine e = shipped();
        List<Symbol> shown = ReelShow.frame(e, 1, List.of(Symbol.COAL, Symbol.COAL, Symbol.DIAMOND), 2,
                new SplittableRandom(1));
        assertEquals(Symbol.COAL, shown.get(0));
        assertEquals(Symbol.COAL, shown.get(1));
        assertNull(shown.get(2), "anything next to a stopped pair would pay: the reel shows a blank instead");
        List<Symbol> wild = ReelShow.frame(e, 1, List.of(Symbol.WILD, Symbol.COAL, Symbol.COAL), 1,
                new SplittableRandom(2));
        assertTrue(wild.get(1) != null && wild.get(2) != null, "a lone Wild can still spin beside Stone");
        assertNull(e.line(1, wild.get(0), wild.get(1), wild.get(2)), "and shows nothing that pays: " + wild);
    }

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return out;
    }
}
