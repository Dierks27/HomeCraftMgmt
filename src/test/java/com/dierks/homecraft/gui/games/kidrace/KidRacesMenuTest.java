package com.dierks.homecraft.gui.games.kidrace;

import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade Room's Mob Races screen, for a child who can't read: each racer is a picture (its own mob's spawn egg)
 * in its own colour, its number the stack's count, and its stats are stars, never numbers or odds.
 */
class KidRacesMenuTest {

    @Test
    void everyRacerIsItsOwnPictureAndColour() {
        Set<Material> eggs = new HashSet<>();
        Set<Material> colours = new HashSet<>();
        for (Racer r : Racer.values()) {
            Material egg = KidRacesMenu.egg(r);
            assertTrue(egg.name().endsWith("_SPAWN_EGG"), r + ": its mob's picture: " + egg);
            eggs.add(egg);
            Material c = KidRacesMenu.colour(r);
            assertNotEquals(Material.WHITE_CONCRETE, c, r + " has a colour of its own");
            colours.add(c);
        }
        assertEquals(Racer.COUNT, eggs.size(), "six mobs");
        assertEquals(Racer.COUNT, colours.size(), "six colours");
    }

    @Test
    void statsAreStarsOnly() {
        assertEquals("&e★★★&8☆☆", KidRacesMenu.stars(3));
        assertEquals("&e★★★★★&8", KidRacesMenu.stars(9), "never more than five");
        RaceCard card = RaceCard.withForm(new int[Racer.COUNT]);
        for (Racer r : Racer.values()) {
            List<String> lines = KidRacesMenu.racerLines(r, card);
            assertEquals(4, lines.size());
            for (String line : lines) {
                assertTrue(line.contains("★") || line.contains("☆"), line);
                assertFalse(line.contains("%") || line.contains(" in "), "no odds to read: " + line);
                String l = line.toLowerCase(Locale.ROOT);
                for (String banned : List.of("bet", "wager", "lucky", "jackpot", "odds", "pays")) {
                    assertFalse(l.contains(banned), line);
                }
            }
        }
    }
}
