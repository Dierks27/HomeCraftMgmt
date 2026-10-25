package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.HolidayShows.Theme;
import com.dierks.homecraft.arcade.MegaShow.Look;
import com.dierks.homecraft.arcade.MegaShow.Shot;
import com.dierks.homecraft.arcade.MegaShow.Style;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a holiday changes a show (0.38.2): a themed Mega show of any tier takes only the theme's colours and fades, each
 * lane starting on its own, the theme's shape on about one rocket in four, every other finale rocket, and a gold in the
 * theme's first two colours with its shape, flickering with a trail; with no theme it is 0.38.1's look exactly. The
 * Grand show's long bursts and finale take the theme too; a short burst never does, so the 3-second Firework Show is
 * as always; and which rows are themed.
 */
class HolidayLookTest {

    private static final Theme HALLOWEEN = HolidayShows.SHIPPED.themeOn(LocalDate.of(2026, 10, 31));

    private static Prize firework(String id, int seconds, int launchers) {
        return new Prize(id, PrizeTab.COSMETICS, "&dShow", List.of(), 75, new Icon("", Material.FIREWORK_ROCKET),
                PrizeType.FIREWORK, true, null, 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(),
                null, null, List.of(), null, new PrizeExtra(null, 0, null, OnlyIf.ANY, seconds, null, launchers));
    }

    @Test
    void aThemedMegaShowIsInTheHolidaysColoursAndShape() {
        Set<Color> palette = new HashSet<>(HALLOWEEN.colours());
        Set<Color> fades = new HashSet<>(HALLOWEEN.fades());
        for (int lanes : new int[]{3, 4, 5, 10}) {
            List<Shot> show = MegaShow.plan(lanes, lanes == 10 ? 90 : 60);
            int shaped = 0;
            int other = 0;
            for (Shot s : show) {
                Look look = MegaShow.look(s, lanes, HALLOWEEN);
                assertTrue(palette.containsAll(look.colours()), lanes + ": only the theme's colours: " + s);
                assertTrue(fades.contains(look.fade()), lanes + ": and its fades");
                if (s.style() == Style.GOLD) {
                    assertEquals(HALLOWEEN.colours().subList(0, 2), look.colours(), "the gold: its first two");
                    assertEquals(FireworkEffect.Type.CREEPER, look.type(), "in its shape");
                    assertTrue(look.flicker() && look.trail());
                    continue;
                }
                if (s.style() == Style.FINALE) {
                    assertEquals((s.lane() + s.nth()) % 2 == 0 ? FireworkEffect.Type.CREEPER
                            : FireworkEffect.Type.BALL_LARGE, look.type(), "every other finale rocket");
                    assertTrue(look.flicker() && look.trail(), "flickering with a trail, as always");
                    continue;
                }
                Look plain = MegaShow.look(s, lanes, null);
                assertEquals(plain.flicker(), look.flicker(), "the style's own flicker");
                assertEquals(plain.trail(), look.trail(), "and trail");
                if (look.type() == FireworkEffect.Type.CREEPER) {
                    shaped++;
                    assertEquals(0, (s.lane() + s.nth()) % 4, "one in four: " + s);
                } else {
                    other++;
                    assertEquals(plain.type(), look.type(), "the rest keep the style's own shape");
                }
            }
            double share = shaped / (double) (shaped + other);
            assertTrue(share > 0.15 && share < 0.35, lanes + ": about a quarter in the holiday's shape: " + share);
        }
        Set<Color> firsts = new HashSet<>();
        for (int lane = 0; lane < 3; lane++) {
            firsts.add(MegaShow.look(new Shot(0, lane, lane, Style.SPARK, MegaShow.Height.LOW, 0), 3, HALLOWEEN)
                    .colours().get(0));
        }
        assertEquals(3, firsts.size(), "the lanes start on different colours, so they still differ");
    }

    @Test
    void noThemeIs0381sLookExactly() {
        for (Shot s : MegaShow.plan(60)) {
            assertEquals(MegaShow.look(s), MegaShow.look(s, 3, null), "the three-lane show: " + s);
            assertFalse(MegaShow.look(s).type() == FireworkEffect.Type.CREEPER, "never a creeper face");
        }
        Theme one = new Theme("x", "X", List.of(Color.RED), List.of(Color.WHITE), FireworkEffect.Type.STAR);
        Shot gold = new Shot(0, 0, 0, Style.GOLD, MegaShow.Height.HIGH, 0);
        assertEquals(List.of(Color.RED), MegaShow.look(gold, 3, one).colours(), "a one-colour theme's gold");
        assertEquals(List.of(Color.RED), HolidayShows.two(one, 4, 7), "and its every rocket");
    }

    @Test
    void theGrandShowsLongBurstsAndFinaleAreThemedAndAShortBurstNever() {
        for (int burst = 0; burst < 30; burst++) {
            for (int i = 0; i < FireworkShow.LONG_PER_BURST; i++) {
                Look look = FireworkShow.look(HALLOWEEN, FireworkShow.Kind.LONG, burst, i);
                assertTrue(HALLOWEEN.colours().containsAll(look.colours()), "the theme's colours");
                assertTrue(HALLOWEEN.fades().contains(look.fade()));
                assertEquals(HolidayShows.signature(i, burst) ? FireworkEffect.Type.CREEPER : FireworkEffect.Type.BALL,
                        look.type(), "its shape on one in four, else a ball");
                assertTrue(look.trail());
                assertFalse(look.flicker());
            }
            assertNull(FireworkShow.look(HALLOWEEN, FireworkShow.Kind.SHORT, burst, 0),
                    "the 3-second show is never themed");
            assertNull(FireworkShow.look(null, FireworkShow.Kind.LONG, burst, 0), "no theme: 0.38.1's random look");
        }
        for (int i = 0; i < FireworkShow.FINALE_FIREWORKS; i++) {
            Look look = FireworkShow.look(HALLOWEEN, FireworkShow.Kind.FINALE, 30, i);
            assertEquals(i % 2 == 0 ? FireworkEffect.Type.CREEPER : FireworkEffect.Type.BALL_LARGE, look.type());
            assertTrue(look.flicker() && look.trail(), "the finale flickers");
            assertTrue(HALLOWEEN.colours().containsAll(look.colours()));
        }
    }

    @Test
    void whichRowsAreThemed() {
        assertTrue(FireworkShow.themed(firework("firework_grand", 15, 0)), "the Grand show");
        assertTrue(FireworkShow.themed(firework("firework_mega", 60, 3)), "the Mega show");
        assertTrue(FireworkShow.themed(firework("firework_super", 60, 4)));
        assertTrue(FireworkShow.themed(firework("firework_ultra", 60, 5)));
        assertTrue(FireworkShow.themed(firework("firework_ultimate", 90, 10)));
        assertFalse(FireworkShow.themed(firework("firework_show", 3, 0)), "never the 3-second Firework Show");
        assertFalse(FireworkShow.themed(null));
        Prize party = new Prize("party_time", PrizeTab.COSMETICS, "&dParty", List.of(), 300,
                new Icon("", Material.CAKE), PrizeType.PARTY, true, null, 1, null, null, null, null, 0, 0, null, 0, "",
                List.of(), List.of(), null, null, List.of(), null, new PrizeExtra(null, 0, null, OnlyIf.ANY, 10, null));
        assertFalse(FireworkShow.themed(party), "nor Party Time");
    }

    @Test
    void theLooksMixesAreStable() {
        assertTrue(HolidayShows.signature(0, 0));
        assertTrue(HolidayShows.signature(1, 3));
        assertFalse(HolidayShows.signature(1, 1));
        assertEquals(List.of(HALLOWEEN.colours().get(1), HALLOWEEN.colours().get(2)), HolidayShows.two(HALLOWEEN, 1, 0));
        assertEquals(List.of(HALLOWEEN.colours().get(2), HALLOWEEN.colours().get(0)), HolidayShows.two(HALLOWEEN, 0, 2),
                "round the theme's colours");
        assertEquals(HALLOWEEN.fades().get(1), HolidayShows.fade(HALLOWEEN, 1, 0));
    }
}
