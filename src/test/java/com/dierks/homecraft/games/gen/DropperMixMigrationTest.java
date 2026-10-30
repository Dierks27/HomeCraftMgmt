package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 25 ({@link DropperMixMigration}): the Long Drop. The Dropper's shipped mix EEMMH becomes
 * EEMHX (trimmed, any case) with one INFO line and nothing else of the file touched; an owner's own mix stays
 * with one WARN naming the command; a value already EEMHX, a missing key, a bare switch and the Easy Dropper's
 * row are left alone without a word; and a second run changes nothing.
 */
class DropperMixMigrationTest {

    private static final String MIX = "games.fresh.slots.fresh_dropper.mix";
    private static final String EASY_MIX = "games.fresh.slots.fresh_dropper_easy.mix";

    /** The bundled file as every version before this one shipped the Dropper's mix. */
    private static YamlConfiguration before() {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set(MIX, "EEMMH");
        return c;
    }

    @Test
    void itIsRevision25AndTheShippedMixIsTheDroppersNewOne() {
        assertEquals(25, DropperMixMigration.REVISION, "config revision 25");
        assertEquals("EEMMH", DropperMixMigration.OLD_MIX, "what every version before shipped");
        assertEquals("EEMHX", Slots.FRESH_DROPPER.tierOrMix(), "what this one ships: level 5 is the Long Drop");
        assertEquals("games.fresh.slots.fresh_dropper.mix", DropperMixMigration.KEY, "the one key it may write");
    }

    @Test
    void theShippedMixMovesAndNothingElseDoes() {
        YamlConfiguration c = before();
        List<String> log = new ArrayList<>();
        assertTrue(DropperMixMigration.apply(c, log), "the file changes");
        assertEquals("EEMHX", c.getString(MIX), "EEMMH becomes EEMHX");
        assertEquals(LayoutFixtures.bundled().saveToString(), c.saveToString(), "now exactly the bundled file: every"
                + " other key as it was (enabled, origin, the Easy Dropper, the Classic)");
        assertEquals(1, log.size(), "one line: " + log);
        assertFalse(log.get(0).startsWith(LayoutGuard.WARN), "an INFO: nothing for the owner to do");
        assertTrue(log.get(0).startsWith("Config migration: the Dropper's shipped mix EEMMH becomes EEMHX (level 5 is"
                + " the Long Drop)"), "saying what moved: " + log);
        assertTrue(log.get(0).contains("64 x 144 x 16") && log.get(0).contains("emptied by itself"),
                "and what the restart does with the taller halves: " + log);
        assertFalse(DropperMixMigration.apply(c, new ArrayList<>()), "a second run changes nothing");
    }

    @Test
    void anyCaseAndSpacesOfTheShippedMixCountAsIt() {
        YamlConfiguration c = before();
        c.set(MIX, " eemmh ");
        assertTrue(DropperMixMigration.apply(c, new ArrayList<>()), "trimmed, any case");
        assertEquals("EEMHX", c.getString(MIX), "moved");
    }

    @Test
    void anOwnersOwnMixStaysWithOneWarnNamingTheCommand() {
        for (String theirs : List.of("EEMHH", "HHHHH", "E", "eeemh")) {
            YamlConfiguration c = before();
            c.set(MIX, theirs);
            List<String> log = new ArrayList<>();
            assertFalse(DropperMixMigration.apply(c, log), theirs + ": the file doesn't change");
            assertEquals(theirs, c.getString(MIX), theirs + ": the owner's own mix stays, as written");
            assertEquals(1, log.size(), theirs + ": one line: " + log);
            assertTrue(log.get(0).startsWith(LayoutGuard.WARN) && log.get(0).contains("kept your own " + MIX + " "
                    + theirs) && log.get(0).contains("/hcm games gen mix fresh_dropper EEMHX"),
                    theirs + ": a WARN naming the key, their value and the command: " + log);
        }
    }

    @Test
    void aMixAlreadyThereAMissingKeyAndABareSwitchAreLeftAloneWithoutAWord() {
        YamlConfiguration already = LayoutFixtures.bundled();
        List<String> log = new ArrayList<>();
        assertFalse(DropperMixMigration.apply(already, log), "the shipped EEMHX needs nothing");
        assertEquals(List.of(), log, "and no word");

        YamlConfiguration missing = before();
        missing.set(MIX, null);
        assertFalse(DropperMixMigration.apply(missing, log), "a missing mix is left to the backfill");
        assertNull(missing.get(MIX), "which writes the shipped EEMHX");
        assertEquals(List.of(), log, "no word");

        YamlConfiguration bare = before();
        bare.set("games.fresh.slots.fresh_dropper", false); // the owner's bare switch: off, and no section
        assertFalse(DropperMixMigration.apply(bare, log), "nothing to read");
        assertEquals(Boolean.FALSE, bare.get("games.fresh.slots.fresh_dropper"), "the switch is kept");
        assertEquals(List.of(), log, "no word");

        YamlConfiguration number = before();
        number.set(MIX, 5);
        assertFalse(DropperMixMigration.apply(number, log), "a value that isn't text is the owner's");
        assertEquals(5, number.getInt(MIX), "and stays (the slot is switched off with its own WARN when read)");
        assertEquals(1, log.size(), "with the WARN: " + log);
    }

    @Test
    void theEasyDropperIsNeverRead() {
        YamlConfiguration c = before();
        c.set(EASY_MIX, "EEMMH"); // the owner's own Easy Dropper mix, the same letters: not the Dropper's
        List<String> log = new ArrayList<>();
        assertTrue(DropperMixMigration.apply(c, log), "the Dropper's moves");
        assertEquals("EEMMH", c.getString(EASY_MIX), "Easy Dropper's stays as it was");
        assertEquals(1, log.size(), "and nothing is said about it: " + log);
        assertFalse(log.get(0).contains("fresh_dropper_easy"), log.toString());
    }
}
