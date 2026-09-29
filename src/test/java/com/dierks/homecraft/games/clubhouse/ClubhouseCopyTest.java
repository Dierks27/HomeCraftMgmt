package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse's copy (CLUBHOUSE-SPEC §7): every string literal in its files (the Clubhouse package,
 * its screens, and the bridges in the race, Race Night and golf packages) is kid-safe: none of bet,
 * wager, gamble, casino, lucky, almost, so close or sink; no emoji; nothing above U+FFFF.
 */
class ClubhouseCopyTest {

    private static final Path BASE = Path.of("src", "main", "java", "com", "dierks", "homecraft");
    private static final Pattern[] BANNED = {
            Pattern.compile("(?i)\\bbet\\b"), Pattern.compile("(?i)wager"), Pattern.compile("(?i)gambl"),
            Pattern.compile("(?i)casino"), Pattern.compile("(?i)\\blucky\\b"), Pattern.compile("(?i)\\balmost\\b"),
            Pattern.compile("(?i)\\bso\\s+close\\b"), Pattern.compile("(?i)\\bsink"),
    };
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The Clubhouse's own files, and the new bridge files in the other packages. */
    private static List<Path> files() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(BASE.resolve("games/clubhouse"), BASE.resolve("gui/games/clubhouse"))) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.walk(dir)) {
                    s.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        for (String f : List.of("games/trial/ClubRaces.java", "games/event/ClubNight.java", "games/golf/ClubGolf.java",
                "command/ClubhouseCheck.java", "games/trial/Riders.java", "games/trial/RideAlong.java")) {
            out.add(BASE.resolve(f));
        }
        return out;
    }

    @Test
    void everyStringIsKidSafeWithNoEmojiAndNothingAboveUffff() throws IOException {
        List<Path> files = files();
        assertTrue(files.size() >= 14, "scanning the Clubhouse's files: " + files.size());
        List<String> offences = new ArrayList<>();
        for (Path f : files) {
            assertTrue(Files.isRegularFile(f), "expected to scan " + f.toAbsolutePath());
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
    void theKitsKeyFactsAreInItsNames() {
        for (String name : List.of(ClubhouseText.KIT_PARTY, ClubhouseText.KIT_RESULTS, ClubhouseText.KIT_LEAVE,
                ClubhouseText.GO_BUTTON, ClubhouseText.WATCH_BUTTON, ClubhouseText.WAIT_BUTTON,
                com.dierks.homecraft.games.trial.RideAlong.BUTTON)) {
            assertTrue(name.contains("- ") && name.length() > 20, "Bedrock reads the NAME: the key fact is in it: " + name);
        }
    }
}
