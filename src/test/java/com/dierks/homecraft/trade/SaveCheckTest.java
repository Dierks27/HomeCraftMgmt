package com.dierks.homecraft.trade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team finding TCR-FA-3: {@code Player.saveData} never throws (vanilla catches every failure of the write and only
 * logs "Failed to save player data"), so a trade can only tell a lost save by the player's file: a save that works
 * writes a new file and moves it over the old one; one that fails leaves the file as it was.
 */
class SaveCheckTest {

    @TempDir
    Path dir;

    /** What vanilla's save does when it works: a temp file, then moved over the player's file. */
    private static void write(Path file, String data) {
        try {
            Path tmp = Files.createTempFile(file.getParent(), "player", ".dat");
            Files.writeString(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aSaveThatWorksChangesTheFile() {
        Path file = dir.resolve("p.dat");
        write(file, "bag before");
        assertTrue(SaveCheck.reached(file, () -> write(file, "bag after")));
        assertTrue(SaveCheck.reached(file, () -> write(file, "bag after")), "[X] the same bag again is a new file");
    }

    @Test
    void aSaveThatFailedLeavesTheFileAsItWas() {
        Path file = dir.resolve("p.dat");
        write(file, "bag before");
        assertFalse(SaveCheck.reached(file, () -> {
            // vanilla: "Failed to save player data for <name>", and nothing else
        }), "[X] the file wasn't replaced: the save didn't reach the disk");
        Path none = dir.resolve("new.dat");
        assertFalse(SaveCheck.reached(none, () -> { }), "a first save that wrote nothing");
        assertTrue(SaveCheck.reached(none, () -> write(none, "first")), "a first save that wrote the file");
    }

    @Test
    void anotherLayoutHasNoAnswer() {
        int[] saves = {0};
        assertNull(SaveCheck.reached(dir.resolve("players").resolve("data").resolve("p.dat"), () -> saves[0]++),
                "no such folder: the server keeps its players elsewhere");
        assertEquals(1, saves[0], "the save still ran");
    }
}
