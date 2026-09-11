package com.dierks.homecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No text this plugin shows a player may use a character above U+FFFF.
 *
 * <p>Most of this server's players are on Bedrock, through Geyser. Bedrock Edition's font is a set
 * of UTF-16 glyph pages and it ships nothing above the Basic Multilingual Plane — so an emoji like
 * 🔒 (U+1F512) is not "a slightly wrong icon" there, it is a missing-character box. Java players see
 * a padlock and Bedrock players see a blank, with nothing in the code to say why.
 *
 * <p>Symbols BELOW U+FFFF are fine and used freely: ★ ✦ ✓ ✗ » « → ▲ ▼ all come from GNU Unifont,
 * which Bedrock does include. The line is the plane, not "is it a symbol".
 *
 * <p>This walks the source rather than the built jar because the strings are what matter, and they
 * are spread across menus, items, chat messages and the bundled config alike.
 */
class BedrockGlyphTest {

    private static final Path[] ROOTS = {
            Path.of("src", "main", "java"),
            Path.of("src", "main", "resources"),
    };

    @Test
    void noSourceStringUsesACharacterBedrockCannotRender() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root),
                    "expected to scan " + root.toAbsolutePath() + " — if the working directory moved, "
                            + "this test is silently checking nothing");
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java") && !name.endsWith(".yml")) {
                        continue;
                    }
                    scan(file, offences);
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Bedrock renders nothing above U+FFFF, so these show as an empty box through Geyser:\n"
                        + String.join("\n", offences)
                        + "\nUse a symbol below U+FFFF (GNU Unifont's range) instead — e.g. ✖ ❒ ★ ✦ ✓.");
    }

    private void scan(Path file, List<String> offences) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // codePoints(), not chars(): a supplementary character is two chars, and iterating
            // chars would see two lone surrogates instead of the one codepoint that is the problem.
            int lineNo = i + 1;
            line.codePoints().filter(cp -> cp > 0xFFFF).forEach(cp ->
                    offences.add(String.format("  %s:%d  U+%04X  %s",
                            file, lineNo, cp, new String(Character.toChars(cp)))));
        }
    }
}
