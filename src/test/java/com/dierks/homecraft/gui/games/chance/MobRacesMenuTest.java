package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob Races' screen says exactly the race card's numbers: each racer's chance, what it pays at the stake chosen and
 * what that gives back, its stats, its form and its last finishes, with the favourite and the longest shot marked;
 * and the "How the odds work" paper says it plainly, within the copy rules.
 */
class MobRacesMenuTest {

    private static final int CAP = 250;

    @Test
    void eachRacersTileShowsExactlyTheCardsNumbers() {
        for (long seed : new long[]{1, 2, 20261122L, 99}) {
            RaceCard card = RaceCard.of(seed);
            for (Racer r : Racer.values()) {
                for (int stake : new int[]{5, 10, 20}) {
                    RaceCard.Pay pay = card.pay(r, stake, 90, CAP);
                    String all = String.join("\n", MobRacesMenu.racerLines(r, card, pay, stake, false,
                            List.of(1, 4, 2, 6, 3)));
                    assertTrue(all.contains("1 race in " + card.oneIn(r) + " &8(" + card.percent(r) + ")"), all);
                    if (pay == null) {
                        assertTrue(all.contains("Not offered at " + stake + " tokens this race."), all);
                        assertFalse(all.contains(" in → "), "no pay shown for a pair that isn't offered");
                    } else {
                        assertTrue(all.contains(stake + " tokens in → &6" + pay.pays() + " tokens back"), all);
                        assertTrue(all.contains("gives back about " + RtpLimits.tenthPercent(pay.rtp())), all);
                    }
                    assertTrue(all.contains("Speed    " + MobRacesMenu.stars(r.speed())));
                    assertTrue(all.contains("Stamina " + MobRacesMenu.stars(r.stamina())));
                    assertTrue(all.contains("Start     " + MobRacesMenu.stars(r.start())));
                    assertTrue(all.contains("Today: " + MobRacesMenu.form(card.form(r))));
                    assertTrue(all.contains("Last 5: &a1st &f4th &f2nd &f6th &f3rd"), all);
                    assertEquals(card.favourite() == r, all.contains(RaceCard.FAVOURITE + " &7The favourite"));
                    assertEquals(card.longest() == r, all.contains(RaceCard.LONGEST + " &7The longest shot"));
                }
                // in dollars, the Parlor's cap
                int dollars = 25;
                RaceCard.Pay pay = card.pay(r, dollars, 90, Parlor.Rules.defaults().capFor(dollars));
                String all = String.join("\n", MobRacesMenu.racerLines(r, card, pay, dollars, true, List.of()));
                assertTrue(pay == null ? all.contains("Not offered at $25") : all.contains("$25 in → &6$" + pay.pays()
                        + " back"), all);
                assertTrue(all.contains("No races here yet"));
            }
        }
        String none = String.join("\n", MobRacesMenu.racerLines(Racer.PIG, RaceCard.of(1), null, 0, true, List.of()));
        assertTrue(none.contains("Add a chip to see what it pays."), none);
    }

    @Test
    void starsFormAndFinishesReadAtAGlance() {
        assertEquals("&e★★★★&8☆", MobRacesMenu.stars(4));
        assertEquals("&e★&8☆☆☆☆", MobRacesMenu.stars(1));
        assertEquals("&a▲▲ great form", MobRacesMenu.form(2));
        assertEquals("&7– as usual", MobRacesMenu.form(0));
        assertEquals("&c▼▼ well off form", MobRacesMenu.form(-2));
        assertEquals("&a1st &f2nd &f3rd &f4th &f5th", MobRacesMenu.finishes(List.of(1, 2, 3, 4, 5)));
        for (Racer r : Racer.values()) {
            assertNotEquals(Material.WHITE_BANNER, MobRacesMenu.spawnEgg(r), r + " has its own picture");
            assertEquals(Material.getMaterial(r.mob() + "_SPAWN_EGG"), MobRacesMenu.spawnEgg(r));
        }
    }

    /** The paper says how it works, the honest number, and nothing the copy rules ban. */
    @Test
    void howTheOddsWorkIsPlainAndHonest() {
        String how = String.join("\n", MobRacesMenu.howLines(0.884));
        assertTrue(how.contains("Speed, Stamina and Start"));
        assertTrue(how.contains("every pick gives back about the same"));
        assertTrue(how.contains("at least 88 of every 100"), how);
        assertTrue(how.contains("decided when entries close"));
        assertTrue(how.contains("No pick is a better deal than another."));
        Pattern banned = Pattern.compile("(?i)\\bbet\\b|wager|gambl|casino|\\bhouse\\b|so\\s+close|\\balmost\\b"
                + "|\\blucky\\b|\\bhot\\b|\\bdue\\b|big\\s+win|jackpot|try\\s+again|one\\s+more");
        for (long seed = 0; seed < 50; seed++) {
            RaceCard card = RaceCard.of(seed);
            for (Racer r : Racer.values()) {
                for (String line : MobRacesMenu.racerLines(r, card, card.pay(r, 5, 90, CAP), 5, false, List.of(2))) {
                    assertFalse(banned.matcher(line).find(), line);
                }
            }
        }
        for (String line : MobRacesMenu.howLines(Double.NaN)) {
            assertFalse(banned.matcher(line).find(), line);
        }
    }
}
