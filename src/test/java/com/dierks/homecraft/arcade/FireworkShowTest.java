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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * while short ones are never refused. And the review's three (0.38.1): a long show stays out of the games, a long
 * show's tap on one of ours is that block's, and the held tap that starts a long show isn't refused aloud.
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
        long first = g.tryStart(her, true, 0);
        assertTrue(first != 0, "the first long show starts");
        assertTrue(g.longOn(her));
        assertEquals(0, g.tryStart(her, true, 0), "a second long one while it plays is refused (the item is kept)");
        assertEquals(0, g.tryStart(her, true, 0), "and again");
        long other = g.tryStart(UUID.randomUUID(), true, 0);
        assertTrue(other != 0, "someone else's long show is their own");
        g.end(her, first);
        assertFalse(g.longOn(her), "its own end frees her");
        long next = g.tryStart(her, true, 0);
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
            shows.add(g.tryStart(her, false, 0));
        }
        assertTrue(shows.stream().allMatch(n -> n != 0), "a pile of Firework Shows all play: " + shows);
        assertEquals(20, shows.stream().distinct().count(), "each its own show");
        assertFalse(g.longOn(her), "and none holds the long slot");
        long grand = g.tryStart(her, true, 0);
        assertTrue(grand != 0, "a long one starts beside them");
        assertTrue(g.tryStart(her, false, 0) != 0, "and a short one still plays while it does");
        for (long n : shows) {
            g.end(her, n);
        }
        assertTrue(g.longOn(her), "a short show's end frees nothing");
        assertEquals(0, g.tryStart(her, true, 0));
    }

    @Test
    void theRefusalIsShortAndKindAndSaidOnceEveryTwoSeconds() {
        assertEquals("Wait for your show to finish!", PrizeItemListener.SHOW_ON);
        FireworkShows.Guard g = new FireworkShows.Guard();
        UUID her = UUID.randomUUID();
        assertTrue(g.refusalDue(her, 10_000), "the first refusal is said");
        assertFalse(g.refusalDue(her, 10_200), "a held Bedrock tap's repeats aren't");
        assertFalse(g.refusalDue(her, 11_999));
        assertTrue(g.refusalDue(her, 12_000), "two seconds on, it is said again");
        assertTrue(g.refusalDue(UUID.randomUUID(), 12_100), "each player's own");
        g.tryStart(her, true, 12_050);
        g.forget(her);
        assertFalse(g.longOn(her), "a quit forgets her long show");
        assertTrue(g.refusalDue(her, 12_100), "and her last refusal (and her long show's start)");
    }
    @Test
    void aLongShowsStartQuietensTheHeldTapThatStartedIt() {
        // review SHOW-REFUSE-3: Java repeats a held use every 4 ticks and Bedrock a held tap's, so the first repeat
        // comes a moment after the start, while the show is on
        FireworkShows.Guard g = new FireworkShows.Guard();
        UUID her = UUID.randomUUID();
        assertTrue(g.tryStart(her, true, 10_000) != 0, "the Grand show starts");
        assertEquals(0, g.tryStart(her, true, 10_200), "the held tap's first repeat is refused (the item is kept)");
        assertFalse(g.refusalDue(her, 10_200), "and isn't said: the show has only just begun");
        assertFalse(g.refusalDue(her, 11_999), "nor are the repeats after it");
        assertTrue(g.refusalDue(her, 12_000), "two seconds after the start, a refusal is said");
        assertFalse(g.refusalDue(her, 12_200), "and then once every two seconds, as before");
        assertEquals(0, g.tryStart(her, true, 13_000), "a refused start stamps nothing");
        assertTrue(g.refusalDue(her, 14_000));

        UUID other = UUID.randomUUID();
        assertTrue(g.tryStart(other, false, 10_000) != 0, "a short show");
        assertTrue(g.refusalDue(other, 10_100), "stamps nothing: it is never refused, and the first refusal is said");
    }

    @Test
    void aLongShowStaysOutOfTheGames() throws IOException {
        // review SHOW-GAMES-1: as Party Time's guests (PartyService.guest), a game's course is no place for rockets
        assertTrue(FireworkShows.playsHere(false, false), "home: it plays");
        assertFalse(FireworkShows.playsHere(true, false), "in a game (on its way in or out too)");
        assertFalse(FireworkShows.playsHere(false, true), "in a Games world (the Clubhouse, a course)");
        assertFalse(FireworkShows.playsHere(true, true));
        assertEquals("Save your show for after the games!", PrizeItemListener.SHOW_NOT_HERE);

        String shows = source("FireworkShows.java");
        assertTrue(shows.contains("boolean isLong = FireworkShow.isLong(seconds); long show = guard.tryStart(id, isLong,"
                + " System.currentTimeMillis());"), "the start knows a long show");
        assertTrue(shows.contains("if (!player.isOnline() || (isLong && !playsHere(player))) { end(show); return; }"
                + " while (next[0] < bursts.size()"), "each step, before it bursts: a long show whose player went into"
                + " a game or a Games world ends there, and a short show plays as ever");
        assertTrue(shows.contains("return playsHere(Whereabouts.inGameSession(plugin, player),"
                + " Whereabouts.gamesWorld(plugin, player.getWorld()));"), "the facts are Whereabouts'");

        String use = fireworkCase();
        int here = use.indexOf("if (longShow && !shows.playsHere(player)) {");
        assertTrue(here >= 0, "a long show in a game or a Games world is refused: " + use);
        assertTrue(here < use.indexOf("shows.start(player, seconds)"), "before it starts");
        assertTrue(use.substring(here, use.indexOf("} else if (shows.start(player, seconds))"))
                .contains("SHOW_NOT_HERE"), "with its own line");
        assertFalse(use.substring(here, use.indexOf("} else if (shows.start(player, seconds))"))
                .contains("consume("), "and the item kept");
    }

    @Test
    void aLongShowsTapOnOneOfOursIsThatBlocks() throws IOException {
        // review SHOW-OURS-2: CustomBlockListener, JoinSigns and MiniHeadListener run first (the Arcade Machine's
        // screen, a join sign's game, a Mini caught); the tap is theirs, as for a hat (PrizeItemListener.ours)
        String use = fireworkCase();
        String guard = "boolean longShow = FireworkShow.isLong(seconds); Block clicked = event.getClickedBlock();"
                + " if (longShow && clicked != null && ours(plugin, clicked)) { return; } cancel(event);";
        assertTrue(use.contains(guard), "a long show leaves a tap on one of ours alone, before anything: " + use);
        assertTrue(use.indexOf(guard) < use.indexOf("consume(player, hand)"), "so it is never used up there");
        assertTrue(use.startsWith(" Prize row = plugin.config().arcade().prize(TokenPrizes.prizeId(item));"
                + " if (MegaShow.isMega(row)) { megaShow(event, player, hand, row); return; }"
                + " int seconds = FireworkShow.seconds(row); " + guard),
                "the first thing the case does, once a Mega show (0.38.1, MegaShowRulesTest) has gone its own way");
        assertTrue(use.contains("if (shows.start(player, seconds)) { consume(player, hand); }"),
                "a short show (or a long one's tap on a plain block or the air) plays and is used, as before");
    }

    /** The FIREWORK case of {@code PrizeItemListener.onUse}, comments gone and whitespace one space. */
    private static String fireworkCase() throws IOException {
        String src = source("PrizeItemListener.java");
        String head = "case FIREWORK -> {";
        int from = src.indexOf(head);
        assertTrue(from >= 0, "fixture: " + head);
        int at = from + head.length();
        int depth = 1;
        for (int i = at; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(at, i);
            }
        }
        throw new AssertionError("fixture: the case's end");
    }

    /** An arcade source file, its line comments gone and its whitespace one space (as {@code RentalWiringTest}). */
    private static String source(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dierks/homecraft/arcade", file), StandardCharsets.UTF_8)
                .replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ");
    }
}
