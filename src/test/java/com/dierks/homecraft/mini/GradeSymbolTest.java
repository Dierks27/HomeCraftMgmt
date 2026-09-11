package com.dierks.homecraft.mini;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grade never renders as "Creeper ?".
 *
 * <p>The stars are UTF-8. Save config.yml from an editor set to ANSI/Windows-1252 — which is a
 * plausible thing to do once while editing a lore line — and every character it cannot represent
 * is written out as the byte {@code '?'}. The file still parses, nothing errors, and every Mini
 * on the server loses its star with no way to see why from in-game.
 *
 * <p>So the config override is refused at the last possible moment, in {@link Grade#symbol()}
 * itself rather than only at the config reader: the migration repairs the file and the reader
 * warns, but this is the one that holds even if a future call site loads styles another way.
 *
 * <p>Registry-free: {@link Grade} touches no Bukkit class, so this needs no server.
 */
class GradeSymbolTest {

    @AfterEach
    void clearOverrides() {
        Grade.configure(null);
    }

    @Test
    void aSymbolFlattenedToQuestionMarksFallsBackToTheStar() {
        // All three in one map: configure() replaces the overrides wholesale, so a grade
        // configured by an earlier call would be absent rather than mangled — and the test
        // would pass without ever exercising the guard.
        Map<Grade, Grade.Style> styles = new EnumMap<>(Grade.class);
        styles.put(Grade.STANDARD, new Grade.Style("Standard", "?", 1.0));
        styles.put(Grade.GRADED, new Grade.Style("Graded", "??", 2.5));
        styles.put(Grade.MINT, new Grade.Style("Mint", " ??? ", 6.0));
        Grade.configure(styles);

        assertEquals("☆", Grade.STANDARD.symbol());
        assertEquals("★★", Grade.GRADED.symbol());
        assertEquals("★★★", Grade.MINT.symbol());
    }

    @Test
    void aSymbolTheAdminChoseIsStillTheirs() {
        configure(Grade.STANDARD, "•");
        assertEquals("•", Grade.STANDARD.symbol());

        // A question mark with a real character beside it is a choice, not wreckage.
        configure(Grade.GRADED, "?!");
        assertEquals("?!", Grade.GRADED.symbol());
    }

    @Test
    void blankAndMissingOverridesKeepTheBuiltIn() {
        configure(Grade.GRADED, "   ");
        assertEquals("★★", Grade.GRADED.symbol());

        Grade.configure(null);
        assertEquals("☆", Grade.STANDARD.symbol());
    }

    @Test
    void mangledIsQuestionMarksAndNothingElse() {
        assertTrue(Grade.isMangledSymbol("?"));
        assertTrue(Grade.isMangledSymbol("???"));
        assertTrue(Grade.isMangledSymbol("  ??  "));

        assertFalse(Grade.isMangledSymbol(null));
        assertFalse(Grade.isMangledSymbol(""));
        assertFalse(Grade.isMangledSymbol("   "), "blank is empty, not mangled — a separate case");
        assertFalse(Grade.isMangledSymbol("☆"));
        assertFalse(Grade.isMangledSymbol("?☆"));
    }

    /** The star a mangled symbol falls back to must itself be renderable on Bedrock (BMP only). */
    @Test
    void theBuiltInStarsStayInsideTheBasicMultilingualPlane() {
        for (Grade g : Grade.values()) {
            assertTrue(g.symbol().codePoints().allMatch(cp -> cp <= 0xFFFF),
                    g + " falls back to a symbol Bedrock cannot draw: " + g.symbol());
        }
    }

    private static void configure(Grade grade, String symbol) {
        Map<Grade, Grade.Style> styles = new EnumMap<>(Grade.class);
        styles.put(grade, new Grade.Style(grade.display(), symbol, grade.valueMultiplier()));
        Grade.configure(styles);
    }
}
