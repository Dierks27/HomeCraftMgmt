package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.FireworkShow.Burst;
import com.dierks.homecraft.arcade.FireworkShow.Kind;
import com.dierks.homecraft.arcade.FireworkShow.Spot;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Firework Shows (0.38.1, the Grand Firework Show; the owner's request of 25 Oct): a short show is exactly what it
 * always was (6 bursts, 10 ticks apart, of 2 within 3 blocks); a longer one bursts every 10 ticks for its whole
 * length, 3 a burst, further out and at a mix of heights, with a finale of 9 higher up a second after its last burst;
 * the length comes from the item's row at use time (3 when it's gone); and a player has one long show at a time,
 * while short ones are never refused.
 */
class FireworkShowTest {

    /** Fireworks at each tick of a show, in tick order. */
    private static Map<Long, Integer> perTick(int seconds) {
        Map<Long, Integer> out = new TreeMap<>();
        for (Burst b : FireworkShow.schedule(seconds)) {
            out.merge(b.tick(), b.fireworks(), Integer::sum);
        }
        return out;
    }

    // ---- the schedule ---------------------------------------------------------------------------------

    @Test
    void theShortShowIsExactlyTodays() {
        List<Burst> show = FireworkShow.schedule(3);
        assertEquals(6, show.size(), "6 bursts");
        for (int i = 0; i < 6; i++) {
            assertEquals(new Burst(i * 10L, 2, Kind.SHORT), show.get(i), "10 ticks apart, 2 each, today's placement");
        }
        assertEquals(Map.of(0L, 2, 10L, 2, 20L, 2, 30L, 2, 40L, 2, 50L, 2), perTick(3));
        assertEquals(50, FireworkShow.lastTick(3), "over two and a half seconds in, no finale");
        assertEquals(show, FireworkShow.schedule(0), "a row with no length (0.38.0's constructor) is the short show");
        assertEquals(show, FireworkShow.schedule(-4));
    }

    @Test
    void theGrandShowBurstsForFifteenSecondsAndEndsInAFinale() {
        List<Burst> show = FireworkShow.schedule(15);
        assertEquals(31, show.size(), "30 bursts and the finale");
        for (int i = 0; i < 30; i++) {
            assertEquals(new Burst(i * 10L, 3, Kind.LONG), show.get(i), "the same 10-tick bursts, 3 each");
        }
        Burst finale = show.get(30);
        assertEquals(new Burst(290 + 20, 9, Kind.FINALE), finale, "9 at once, a second after the last burst");
        assertEquals(310, FireworkShow.lastTick(15));
        Map<Long, Integer> ticks = perTick(15);
        assertEquals(31, ticks.size());
        assertEquals(99, ticks.values().stream().mapToInt(Integer::intValue).sum(), "99 fireworks in all");
        assertEquals(9, ticks.values().stream().mapToInt(Integer::intValue).max().orElse(0), "never more than 9 at once");
        assertFalse(ticks.containsKey(300L), "a quiet beat before the finale");
    }

    @Test
    void everyLengthBurstsTwiceASecondForItsWholeLength() {
        for (int s = 4; s <= 30; s++) {
            List<Burst> show = FireworkShow.schedule(s);
            assertEquals(s * 2 + 1, show.size(), s + " seconds: two bursts a second and the finale");
            for (int i = 0; i < s * 2; i++) {
                assertEquals(new Burst(i * 10L, 3, Kind.LONG), show.get(i), s + "s burst " + i);
            }
            assertEquals(new Burst((s * 2 - 1) * 10L + 20, 9, Kind.FINALE), show.get(s * 2), s + "s finale");
        }
        assertEquals(FireworkShow.schedule(30), FireworkShow.schedule(31), "at most 30 seconds");
        assertEquals(FireworkShow.schedule(30), FireworkShow.schedule(600));
        assertEquals(61, FireworkShow.schedule(30).size());
        assertEquals(610, FireworkShow.lastTick(30));
    }

    @Test
    void sixSecondsOrMoreIsALongShow() {
        assertFalse(FireworkShow.isLong(3), "the Firework Show");
        assertFalse(FireworkShow.isLong(5));
        assertTrue(FireworkShow.isLong(6));
        assertTrue(FireworkShow.isLong(15), "the Grand Firework Show");
        assertTrue(FireworkShow.isLong(600), "clamped to 30, still long");
        assertFalse(FireworkShow.isLong(0), "no length is the short show");
    }

    // ---- where they go --------------------------------------------------------------------------------

    @Test
    void theShortShowsSpotsAreTodaysAndALongShowsAreFurtherOutAtAMixOfHeights() {
        Random r = new Random(7);
        for (int n = 0; n < 500; n++) {
            Spot s = FireworkShow.spot(Kind.SHORT, n % 2, 2, r);
            assertTrue(Math.abs(s.dx()) <= 3 && Math.abs(s.dz()) <= 3, "within 3 blocks either way: " + s);
            assertEquals(0.5, s.dy(), 1e-9, "half a block up");
            assertEquals(1, s.power(), "the rocket's usual height");
        }
        List<Integer> powers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Spot s = FireworkShow.spot(Kind.LONG, i, 3, r);
            double d = Math.hypot(s.dx(), s.dz());
            assertTrue(d >= 2.5 && d <= 5.5, "2.5 to 5.5 blocks out: " + d);
            assertEquals(0.5, s.dy(), 1e-9);
            powers.add(s.power());
        }
        assertEquals(List.of(0, 1, 0), powers, "alternately low and at the short show's height");
        for (int n = 0; n < 500; n++) {
            Spot s = FireworkShow.spot(Kind.LONG, n, 3, r);
            double d = Math.hypot(s.dx(), s.dz());
            assertTrue(d >= 2.5 - 1e-9 && d <= 5.5 + 1e-9, "2.5 to 5.5 blocks out: " + d);
        }
    }

    @Test
    void theFinaleGoesEvenlyRoundAndHigherUpThanEveryBurst() {
        Random r = new Random(11);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 9; i++) {
                Spot s = FireworkShow.spot(Kind.FINALE, i, 9, r);
                double d = Math.hypot(s.dx(), s.dz());
                assertTrue(d >= 3 - 1e-9 && d <= 5 + 1e-9, "3 to 5 blocks out: " + d);
                double angle = Math.atan2(s.dz(), s.dx());
                double slice = Math.PI * 2 / 9;
                double from = i * slice;
                double a = angle < from - 1e-9 ? angle + Math.PI * 2 : angle;
                assertTrue(a >= from - 1e-9 && a <= from + slice + 1e-9, "firework " + i + " in its own slice: " + a);
                assertEquals(1.0, s.dy(), 1e-9, "a block up");
                assertEquals(2, s.power(), "higher up");
                assertTrue(s.power() > FireworkShow.spot(Kind.LONG, i, 3, r).power(), "than every long burst");
            }
        }
    }

    // ---- the length comes from the item's row ----------------------------------------------------------

    private static Prize row(String id, PrizeType type, int seconds) {
        return new Prize(id, PrizeTab.COSMETICS, "&6" + id, List.of(), 40, new Icon("", Material.FIREWORK_ROCKET),
                type, true, null, 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(), null, null,
                List.of(), null, new PrizeExtra(null, 0, null, OnlyIf.ANY, seconds, null));
    }

    @Test
    void aUsedItemsLengthIsItsRowsAtUseTime() {
        assertEquals(15, FireworkShow.seconds(row("firework_grand", PrizeType.FIREWORK, 15)));
        assertEquals(3, FireworkShow.seconds(row("firework_show", PrizeType.FIREWORK, 3)));
        assertEquals(3, FireworkShow.seconds((Prize) null), "a row that's gone: the short show");
        assertEquals(3, FireworkShow.seconds(row("firework_grand", PrizeType.BOOST, 15)),
                "a row that isn't a firework row any more: the short show");
        assertEquals(3, FireworkShow.seconds(row("firework_show", PrizeType.FIREWORK, 0)),
                "a row from the constructor every row had before 0.38.1");
        assertEquals(30, FireworkShow.seconds(row("firework_big", PrizeType.FIREWORK, 90)), "at most 30");
        Prize old = new Prize("firework_show", PrizeTab.COSMETICS, "&cFirework Show", List.of(), 15,
                new Icon("", Material.FIREWORK_ROCKET), PrizeType.FIREWORK, true, null, 1, null, null, null, null, 0,
                0, null, 0, "", List.of(), List.of(), null, null, List.of(), null);
        assertEquals(3, FireworkShow.seconds(old), "the 25-argument constructor's row");
    }

    // ---- one long show a player at a time ---------------------------------------------------------------

    @Test
    void aPlayerHasOneLongShowAtATime() {
        FireworkShows.Guard g = new FireworkShows.Guard();
        UUID her = UUID.randomUUID();
        long first = g.tryStart(her, true);
        assertTrue(first != 0, "the first long show starts");
        assertTrue(g.longOn(her));
        assertEquals(0, g.tryStart(her, true), "a second long one while it plays is refused (the item is kept)");
        assertEquals(0, g.tryStart(her, true), "and again");
        long other = g.tryStart(UUID.randomUUID(), true);
        assertTrue(other != 0, "someone else's long show is their own");
        g.end(her, first);
        assertFalse(g.longOn(her), "its own end frees her");
        long next = g.tryStart(her, true);
        assertTrue(next != 0 && next != first, "then the next one starts, with its own number");
        g.end(her, first);
        assertTrue(g.longOn(her), "a late end from the old show changes nothing");
        g.endAll();
        assertFalse(g.longOn(her), "a stop ends whatever is on");
    }

    @Test
    void shortShowsAreNeverRefusedStackedOrNot() {
        FireworkShows.Guard g = new FireworkShows.Guard();
        UUID her = UUID.randomUUID();
        List<Long> shows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shows.add(g.tryStart(her, false));
        }
        assertTrue(shows.stream().allMatch(n -> n != 0), "a pile of Firework Shows all play: " + shows);
        assertEquals(20, shows.stream().distinct().count(), "each its own show");
        assertFalse(g.longOn(her), "and none holds the long slot");
        long grand = g.tryStart(her, true);
        assertTrue(grand != 0, "a long one starts beside them");
        assertTrue(g.tryStart(her, false) != 0, "and a short one still plays while it does");
        for (long n : shows) {
            g.end(her, n);
        }
        assertTrue(g.longOn(her), "a short show's end frees nothing");
        assertEquals(0, g.tryStart(her, true));
    }

    @Test
    void theRefusalIsShortAndKind() {
        assertEquals("Wait for your show to finish!", PrizeItemListener.SHOW_ON);
    }
}
