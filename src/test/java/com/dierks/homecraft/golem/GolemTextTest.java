package com.dierks.homecraft.golem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Copper Golem Brush's copy (0.38.4): every string literal in the golem package is kid-safe (the Clubhouse's
 * scan: none of bet, wager, gamble, casino, lucky, almost, so close or sink; no emoji; nothing above U+FFFF), and
 * the key fact is in each line, since a Bedrock player reads plain chat and a name tag only.
 */
class GolemTextTest {

    private static final Path DIR = Path.of("src", "main", "java", "com", "dierks", "homecraft", "golem");
    private static final Pattern[] BANNED = {
            Pattern.compile("(?i)\\bbet\\b"), Pattern.compile("(?i)wager"), Pattern.compile("(?i)gambl"),
            Pattern.compile("(?i)casino"), Pattern.compile("(?i)\\blucky\\b"), Pattern.compile("(?i)\\balmost\\b"),
            Pattern.compile("(?i)\\bso\\s+close\\b"), Pattern.compile("(?i)\\bsink"),
    };
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyStringIsKidSafeWithNoEmojiAndNothingAboveUffff() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(DIR)) {
            files = s.filter(f -> f.toString().endsWith(".java")).toList();
        }
        assertEquals(6, files.size(), "the golem package's six files: " + files);
        List<String> offences = new ArrayList<>();
        for (Path f : files) {
            String src = Files.readString(f);
            src.codePoints().filter(cp -> cp > 0xFFFF || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0xFE0F)
                    .forEach(cp -> offences.add(f.getFileName() + ": U+" + Integer.toHexString(cp)));
            for (String line : src.split("\n")) {
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue; // comments
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    for (Pattern p : BANNED) {
                        if (p.matcher(m.group(1)).find()) {
                            offences.add(f.getFileName() + ": " + m.group(1));
                        }
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "kid-safe copy only:\n" + String.join("\n", offences));
    }

    @Test
    void theKeyFactIsInEachLine() {
        assertTrue(GolemText.pick("Rusty").contains("Rusty") && GolemText.pick("Rusty").contains("60 s"),
                "who and how long: " + GolemText.pick("Rusty"));
        assertTrue(GolemText.pick("Rusty").contains("tap again to unlink"), "and how to undo");
        assertEquals("&aChest #1 linked.", GolemText.linked(1, 1));
        assertTrue(GolemText.linked(3, 4).contains("#3") && GolemText.linked(3, 4).contains("#4")
                && GolemText.linked(3, 4).contains("both halves"), "a double chest: both numbers");
        assertTrue(GolemText.unlinked(2).contains("2 chests left"));
        assertTrue(GolemText.unlinked(0).contains("no chests left"));
        assertTrue(GolemText.keeps("Rusty", 3).contains("Rusty") && GolemText.keeps("Rusty", 3).contains("3 chests"));
        assertTrue(GolemText.keeps("Rusty", 1).contains("1 chest."));
        assertTrue(GolemText.cleared("Bolt").contains("Bolt") && GolemText.cleared("Bolt").contains("any chest"));
        assertTrue(GolemText.armClear("Bolt").contains("Shift-tap Bolt again"), "the second tap, named");
        assertTrue(GolemText.farHint("Bolt").contains("32 blocks"), "the reach, in numbers");
        assertTrue(GolemText.gone("Bolt").contains("Bolt"));
        assertTrue(GolemText.otherWorld("Bolt").contains("another world"));
        assertTrue(GolemText.FULL.contains("32"), "the cap, in numbers");
        assertTrue(GolemText.OFF.contains("golem.enabled"), "the owner knows which key");
        assertTrue(GolemText.BRUSH_NAME.contains("Copper Golem Brush"));
        assertEquals(5, GolemText.LORE.length);
        assertTrue(GolemText.LORE[4].contains("twice"), "the clear gesture is on the item");
    }
}
