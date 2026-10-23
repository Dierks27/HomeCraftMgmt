package com.dierks.homecraft.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Whereabouts} (SHOP-V2-SPEC §4.13, §8 C0): home from games, a Games world, in a game session, worked out
 * in one place; and the copies the Prize Counter, the candy shower and the admin give-backs used to keep are gone.
 */
class WhereaboutsTest {

    @Test
    void homeFromGames() {
        assertTrue(Whereabouts.homeFromGames(false, true), "no session and home");
        assertFalse(Whereabouts.homeFromGames(true, true), "in a game");
        assertFalse(Whereabouts.homeFromGames(false, false), "on the way back: a saved state still to restore");
        assertFalse(Whereabouts.homeFromGames(true, false));
    }

    @Test
    void aGamesWorldIsNamedCaseInsensitively() {
        List<String> games = List.of("games", "Games_Nether");
        assertTrue(Whereabouts.gamesWorld("games", games));
        assertTrue(Whereabouts.gamesWorld("GAMES", games));
        assertTrue(Whereabouts.gamesWorld("games_nether", games));
        assertFalse(Whereabouts.gamesWorld("world", games));
        assertFalse(Whereabouts.gamesWorld("world", List.of()), "no Games worlds: none is one (an empty economy "
                + "list means everywhere, so this is checked on its own)");
        assertFalse(Whereabouts.gamesWorld(null, games));
        assertFalse(Whereabouts.gamesWorld("games", null));
        assertTrue(Whereabouts.gamesWorld("games", Arrays.asList(null, "games")), "a blank entry is skipped");
    }

    /** The old copies: anything that still works these out by itself instead of asking {@link Whereabouts}. */
    @Test
    void theCopiesAreGone() throws IOException {
        Path main = Path.of("src", "main", "java", "com", "dierks", "homecraft");
        List<String> copies = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("Whereabouts.java")) {
                    continue;
                }
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (String shape : List.of("sessions().session(player) == null && games.sessions().home(player)",
                        "sessions().session(p) == null && games.sessions().home(p)")) {
                    if (src.contains(shape)) {
                        copies.add(main.relativize(f) + ": " + shape);
                    }
                }
            }
        }
        assertEquals(List.of(), copies, "homeFromGames lives in util/Whereabouts");
        for (String caller : List.of("arcade/PrizeService.java", "arcade/candy/CandyService.java",
                "command/HcmCommand.java")) {
            assertTrue(Files.readString(main.resolve(caller), StandardCharsets.UTF_8).contains("Whereabouts."),
                    caller + " asks Whereabouts");
        }
    }
}
