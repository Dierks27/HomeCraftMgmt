package com.dierks.homecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Words the Games never say to a player (spec §2, R3.17).
 *
 * <p>The Games are played by children for tokens, and the copy is part of keeping games of chance
 * from feeling like gambling: no betting words, no "the house", no near-miss wording ("so close",
 * "almost"), no streak or "due" folklore, no big-win hype, and no jackpot (the Scratch Ticket's
 * existing jackpot lives outside these packages and is left to Jeff). Inside the games of chance
 * themselves a loss is also never followed by "try again" or "one more" — skill games may say
 * "Try again" and "Play again", since there a second go is about getting better, not getting even.
 *
 * <p>This is {@link PlayerCopyTest}'s scan — '&amp;'-coloured string literals, comments skipped —
 * run over {@code games/**} and {@code gui/games/**}, case-insensitive with word boundaries.
 */
class GamesCopyTest {

    /** Banned in every games package. */
    private static final Pattern[] EVERYWHERE = {
            word("bet"),
            Pattern.compile("(?i)wager"),
            Pattern.compile("(?i)gambl"),
            Pattern.compile("(?i)casino"),
            word("house"),
            Pattern.compile("(?i)\\bso\\s+close\\b"),
            word("almost"),
            word("lucky"),
            word("hot"),
            word("due"),
            Pattern.compile("(?i)\\bbig\\s+win"),
            Pattern.compile("(?i)jackpot"),
    };

    /** Banned only in the games of chance: after a loss, never nudge another go. */
    private static final Pattern[] CHANCE_ONLY = {
            Pattern.compile("(?i)\\btry\\s+again\\b"),
            Pattern.compile("(?i)\\bone\\s+more\\b"),
    };

    private static Pattern word(String w) {
        return Pattern.compile("(?i)\\b" + w + "\\b");
    }

    @Test
    void noGamesScreenUsesGamblingOrPressureWords() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path root : List.of(PlayerCopyTest.BASE.resolve("games"), PlayerCopyTest.BASE.resolve("gui/games"))) {
            assertTrue(Files.isDirectory(root), "expected to scan " + root.toAbsolutePath()
                    + " — if the working directory moved, this test is silently checking nothing");
            offences.addAll(PlayerCopyTest.offences(root, EVERYWHERE));
        }
        assertTrue(offences.isEmpty(), "The Games never say bet, wager, gamble, casino, house, so close, almost, "
                + "lucky, hot, due, big win or jackpot to a player:\n" + String.join("\n", offences));
    }

    @Test
    void noGameOfChanceNudgesAnotherGo() throws IOException {
        Path games = PlayerCopyTest.BASE.resolve("games/chance");
        assertTrue(Files.isDirectory(games), "expected to scan " + games.toAbsolutePath());
        List<String> offences = new ArrayList<>(PlayerCopyTest.offences(games, CHANCE_ONLY));
        Path screens = PlayerCopyTest.BASE.resolve("gui/games/chance");
        if (Files.isDirectory(screens)) {
            offences.addAll(PlayerCopyTest.offences(screens, CHANCE_ONLY));
        }
        assertTrue(offences.isEmpty(), "A game of chance never says \"try again\" or \"one more\":\n"
                + String.join("\n", offences));
    }

    @Test
    void theWordRulesCatchWhatTheyMeanAndNothingElse() {
        for (String bad : List.of("Place your bet", "The House wins", "So  close!", "almost had it",
                "Lucky you", "Hot streak", "You're due", "BIG WIN", "Jackpot!", "gambling", "wagers")) {
            assertTrue(matches(EVERYWHERE, bad), "\"" + bad + "\" should be caught");
        }
        for (String fine : List.of("Better than your best", "Houses and shops", "A photo shoot",
                "Duel a friend", "Today's pick", "gives back about 89 of every 100 tokens",
                "No win this time.", "Your 10 back")) {
            assertFalse(matches(EVERYWHERE, fine), "\"" + fine + "\" is fine and must not be caught: "
                    + "the rules match whole words, not letters inside other words");
        }
        assertTrue(matches(CHANCE_ONLY, "Try again!"), "a chance screen may not say try again");
        assertTrue(matches(CHANCE_ONLY, "One more?"), "a chance screen may not say one more");
        assertFalse(matches(CHANCE_ONLY, "Once more the reels stop"), "only the phrase is banned");
    }

    private static boolean matches(Pattern[] rules, String text) {
        for (Pattern p : rules) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
