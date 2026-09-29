package com.dierks.homecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The events batch's copy (EV-BUILD house rules): Race Night, the Weekly Cup, Falling Floors, golf
 * together, party races, warm-ups and the Dropper never say bet, wager, gamble, casino, lucky,
 * almost, so close or sink, and never use an emoji or a character above U+FFFF.
 *
 * <p>Why a scan of its own. {@link GamesCopyTest} and {@link PlayerCopyTest} read only the
 * '&amp;'-coloured literals, but much of this batch's copy is built from plain pieces (the Cup's
 * result lines, Race Night's standings, the floors' round lines) that get their colour somewhere
 * else, and "sink" is only in {@link PlayerCopyTest}'s list. So this reads EVERY string literal
 * (comments skipped) in the batch's files, coloured or not. None of them is a place where those
 * words belong, whether a player or an admin reads them. {@link BedrockGlyphTest} already refuses
 * anything above U+FFFF everywhere; this adds the emoji below it (a character whose default look is
 * an emoji, {@link Character#isEmojiPresentation}, or the emoji variation selector U+FE0F), which a
 * Bedrock player sees as a box too. The plain symbols the house style uses (★ ✦ ✓ · » →) are fine.
 */
class EventsCopyTest {

    private static final Path BASE = PlayerCopyTest.BASE;

    /** Whole packages of the batch. */
    private static final List<String> PACKAGES = List.of(
            "games/event", "games/cup", "games/arena", "gui/games/event", "gui/games/cup");

    /** Files of the batch that live beside older code: globs relative to {@link #BASE}. */
    private static final List<String> FILES = List.of(
            "games/golf/GolfGroup*.java", "games/golf/GolfTogether.java", "games/golf/GolfRounds.java",
            "gui/games/golf/GolfGroupCardMenu.java", "gui/games/golf/GolfPartyMenu.java",
            "games/trial/Part*.java", "games/trial/Race*.java", "games/trial/Warmup*.java",
            "games/trial/Dropper*.java", "games/trial/Laps.java",
            "gui/games/trial/Party*.java", "gui/games/trial/WarmupChoiceMenu.java",
            "games/Invites.java", "games/NoPush.java", "display/EventDisplay.java");

    /** A Java string literal. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** A legacy '&amp;' colour or format code, taken out before the words are matched. */
    private static final Pattern COLOURED = Pattern.compile("&[0-9a-fk-orA-FK-OR]");

    private static final Pattern[] BANNED = {
            Pattern.compile("(?i)\\bbet\\b"),
            Pattern.compile("(?i)wager"),
            Pattern.compile("(?i)gambl"),
            Pattern.compile("(?i)casino"),
            Pattern.compile("(?i)\\blucky\\b"),
            Pattern.compile("(?i)\\balmost\\b"),
            Pattern.compile("(?i)\\bso\\s+close\\b"),
            Pattern.compile("(?i)\\bsink"),
    };

    /** Every {@code .java} file the batch's copy lives in. */
    static List<Path> files() throws IOException {
        TreeSet<Path> out = new TreeSet<>();
        for (String pkg : PACKAGES) {
            Path root = BASE.resolve(pkg);
            assertTrue(Files.isDirectory(root), "expected to scan " + root.toAbsolutePath()
                    + " - if the working directory moved, this test is silently checking nothing");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        for (String glob : FILES) {
            int slash = glob.lastIndexOf('/');
            Path dir = BASE.resolve(glob.substring(0, slash));
            assertTrue(Files.isDirectory(dir), "expected " + dir.toAbsolutePath());
            int found = 0;
            try (var stream = Files.newDirectoryStream(dir, glob.substring(slash + 1))) {
                for (Path p : stream) {
                    out.add(p);
                    found++;
                }
            }
            assertTrue(found > 0, glob + " names at least one file: a rename would leave it silently unchecked");
        }
        return List.copyOf(out);
    }

    @Test
    void theBatchsCopyNeverUsesTheBannedWords() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : files()) {
            for (Literal l : literals(file)) {
                String words = COLOURED.matcher(l.text()).replaceAll(" ");
                for (Pattern b : BANNED) {
                    if (b.matcher(words).find()) {
                        offences.add(l.where() + "  \"" + l.text() + "\"");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "The events batch never says bet, wager, gamble, casino, lucky, almost,"
                + " so close or sink:\n" + String.join("\n", offences));
    }

    @Test
    void theBatchsCopyHasNoEmojiAndNothingAboveUFFFF() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : files()) {
            for (Literal l : literals(file)) {
                l.text().codePoints().filter(EventsCopyTest::emojiOrAstral).forEach(cp ->
                        offences.add(l.where() + String.format("  U+%04X  \"", cp) + l.text() + "\""));
            }
        }
        assertTrue(offences.isEmpty(), "No emoji and nothing above U+FFFF (Bedrock shows a box):\n"
                + String.join("\n", offences));
    }

    @Test
    void theRulesCatchWhatTheyMeanAndNothingElse() {
        for (String bad : List.of("Place a bet", "wagered", "Gambling", "CASINO night", "Lucky you",
                "almost there", "So  close!", "the boat sinks")) {
            assertTrue(banned(bad), "\"" + bad + "\" should be caught");
        }
        for (String fine : List.of("Better than your best", "between races", "Alma's time", "closer to the stand",
                "a single lap")) {
            assertFalse(banned(fine), "\"" + fine + "\" is fine: whole words only");
        }
        assertTrue(emojiOrAstral(0x1F3C1), "the chequered flag emoji (above U+FFFF)");
        assertTrue(emojiOrAstral(0x26BD), "a football: an emoji below U+FFFF");
        assertTrue(emojiOrAstral(0x2B50), "the emoji star");
        assertTrue(emojiOrAstral(0xFE0F), "the emoji variation selector");
        for (int ok : new int[]{0x2605, 0x2726, 0x2713, 0x00B7, 0x00BB, 0x2192, 0x25B2}) {
            assertFalse(emojiOrAstral(ok), String.format("U+%04X is a plain symbol the house style uses", ok));
        }
    }

    private static boolean banned(String text) {
        for (Pattern b : BANNED) {
            if (b.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean emojiOrAstral(int cp) {
        return cp > 0xFFFF || cp == 0xFE0F || Character.isEmojiPresentation(cp);
    }

    private record Literal(String where, String text) {
    }

    /** Every string literal in {@code file} outside comments. */
    private static List<Literal> literals(Path file) throws IOException {
        List<Literal> out = new ArrayList<>();
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
                out.add(new Literal(BASE.relativize(file) + ":" + (i + 1), m.group(1)));
            }
        }
        return out;
    }
}
