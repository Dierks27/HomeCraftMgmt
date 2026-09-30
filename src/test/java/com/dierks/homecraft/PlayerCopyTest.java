package com.dierks.homecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Words a player should never read.
 *
 * <p>Admin vocabulary leaked onto player screens: a crate tile said "Money sink — burned.", a
 * reward said "Random Card (tag: starter)", an empty board told a child to edit
 * {@code arcade.quests}. Those words mean something to whoever tunes the economy and nothing to
 * the people playing it — and the youngest of them is only starting to read.
 *
 * <p>The scan is the text a player actually sees: string literals that carry an '&amp;' colour
 * code, in the packages that build player screens and items ({@code gui/}, {@code arcade/},
 * {@code mini/}, {@code games/}). Log lines and admin messages are uncoloured and are left alone.
 * Comments are skipped. Like {@link BedrockGlyphTest} it reads source, so it needs no server.
 * {@link GamesCopyTest} runs the same scan with the games' own banned words.
 */
class PlayerCopyTest {

    static final Path BASE = Path.of("src", "main", "java", "com", "dierks", "homecraft");
    private static final String[] PACKAGES = {"gui", "arcade", "mini", "games"};

    /** A Java string literal. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** A legacy '&amp;' colour or format code — what makes a literal player-facing. */
    private static final Pattern COLOURED = Pattern.compile("&[0-9a-fk-orA-FK-OR]");

    /**
     * The banned words, each with why. {@code arcade.} is matched only where a config key would
     * follow it ("arcade.quests"), so a sentence ending "…at the Arcade." is fine.
     */
    private static final Pattern[] BANNED = {
            Pattern.compile("(?i)\\bsink"),
            Pattern.compile("(?i)\\bburned\\b"),
            Pattern.compile("(?i)\\(tag:"),
            Pattern.compile("\\barcade\\.[a-z_]"),
    };

    @Test
    void noPlayerFacingStringUsesAdminVocabulary() throws IOException {
        List<String> offences = new ArrayList<>();
        for (String pkg : PACKAGES) {
            Path root = BASE.resolve(pkg);
            assertTrue(Files.isDirectory(root),
                    "expected to scan " + root.toAbsolutePath() + " — if the working directory moved, "
                            + "this test is silently checking nothing");
            offences.addAll(offences(root, BANNED));
        }
        assertTrue(offences.isEmpty(),
                "Player-facing text must not use admin words (sink, burned, (tag:, config keys):\n"
                        + String.join("\n", offences));
    }

    /**
     * Every player-facing literal ('&amp;'-coloured, outside comments) in the {@code .java} files
     * under {@code root} that one of {@code banned} matches, one report line each.
     */
    static List<String> offences(Path root, Pattern[] banned) throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                scan(file, banned, offences);
            }
        }
        return offences;
    }

    private static void scan(Path file, Pattern[] banned, List<String> offences) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (inBlock) {
                if (line.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                inBlock = !line.contains("*/");
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            Matcher m = LITERAL.matcher(line);
            while (m.find()) {
                String literal = m.group(1);
                if (!COLOURED.matcher(literal).find()) {
                    continue;
                }
                // Match the words, not the codes: "&farcade.quests" has no word boundary before
                // "arcade" until the "&f" is gone.
                String words = COLOURED.matcher(literal).replaceAll(" ");
                for (Pattern b : banned) {
                    if (b.matcher(words).find()) {
                        offences.add(String.format("  %s:%d  \"%s\"", BASE.relativize(file), i + 1, literal));
                    }
                }
            }
        }
    }
}
