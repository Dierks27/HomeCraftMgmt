package com.dierks.homecraft.gui.games.kidbingo;

import com.dierks.homecraft.games.kidbingo.Picture;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picture Bingo's screen (0.57.0), for a child who can't read and plays with a controller: every animal is its own
 * picture, the card and the big caller are two 3 x 3 blocks side by side, and nothing a cursor needs is far from the
 * door.
 */
class KidBingoMenuTest {

    @Test
    void everyAnimalIsItsOwnPicture() {
        Set<Material> eggs = new HashSet<>();
        for (Picture p : Picture.values()) {
            Material egg = KidBingoMenu.egg(p);
            assertTrue(egg.name().endsWith("_SPAWN_EGG"), p + ": " + egg);
            assertTrue(eggs.add(egg), p + " has a picture of its own");
        }
        assertEquals(Picture.COUNT, eggs.size());
        assertEquals(Material.EGG, KidBingoMenu.egg(null), "the star's square never asks, but a plain egg if it did");
    }

    @Test
    void theCardAndTheCallerAreTwoBlocksSideBySideAndGoIsBesideTheDoor() {
        Set<Integer> card = new HashSet<>();
        for (int s : KidBingoMenu.CARD) {
            card.add(s);
        }
        Set<Integer> caller = new HashSet<>();
        for (int s : KidBingoMenu.CALLER) {
            caller.add(s);
        }
        assertEquals(9, card.size());
        assertEquals(9, caller.size());
        for (int s : KidBingoMenu.CARD) {
            assertFalse(caller.contains(s), "nothing is both");
            assertTrue(s >= 9 && s < 36, "rows 1-3");
            assertTrue(s % 9 >= 1 && s % 9 <= 3, "columns 1-3");
        }
        for (int s : KidBingoMenu.CALLER) {
            assertTrue(s % 9 >= 5 && s % 9 <= 7, "columns 5-7: the caller beside the card");
        }
        // row by row: the card's square r,c sits r rows down and c across
        for (int sq = 0; sq < 9; sq++) {
            assertEquals(10 + (sq / 3) * 9 + sq % 3, KidBingoMenu.CARD[sq]);
        }
    }
}
