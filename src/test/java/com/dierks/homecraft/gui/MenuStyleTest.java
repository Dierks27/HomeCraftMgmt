package com.dierks.homecraft.gui;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.59.0, the face lift: the house style's plain rules ({@link MenuStyle}): lore wraps at 40 letters keeping its
 * colour, titles fit the title bar in dark ink, and the way out and How to play sit side by side in the bottom row.
 */
class MenuStyleTest {

    @Test
    void aLongLoreLineWrapsAtSpacesAndKeepsItsColour() {
        List<String> lines = MenuStyle.wrap("&7Slide the ores. Two the same merge into the next one.", 40);
        assertEquals(List.of("&7Slide the ores. Two the same", "&7merge into the next one."), lines,
                "two even lines, not one with a word left over");
        for (String l : lines) {
            assertTrue(MenuStyle.visible(l) <= 40, l);
        }
    }

    @Test
    void aColourChangedMidLineCarriesOnToTheNextLine() {
        List<String> lines = MenuStyle.wrap("&7Each pays &6five whole tokens for every one of the medals &7once.", 30);
        assertTrue(lines.get(0).startsWith("&7Each pays &6five"), lines.toString());
        for (String l : lines) {
            assertTrue(MenuStyle.visible(l) <= 30, l);
        }
        assertTrue(lines.get(1).startsWith("&6"), "the gold goes on: " + lines.get(1));
        assertTrue(lines.get(lines.size() - 1).endsWith("&7once."), lines.toString());
    }

    @Test
    void formatsCarryOnUntilTheNextColour() {
        List<String> lines = MenuStyle.wrap("&a&lA very bold green heading that goes on a while", 20);
        assertTrue(lines.get(1).startsWith("&a&l"), lines.toString());
    }

    @Test
    void aShortLineIsLeftExactlyAsItWas() {
        assertEquals(List.of("&eClick to play"), MenuStyle.wrap("&eClick to play", 40));
        assertEquals(List.of(""), MenuStyle.wrap("", 40));
        assertEquals(List.of(""), MenuStyle.wrap(null, 40));
        assertEquals(List.of("&7" + "x".repeat(50)), MenuStyle.wrap("&7" + "x".repeat(50), 40),
                "one word longer than the width stays whole");
    }

    @Test
    void lettersAreCountedWithoutTheirColourCodes() {
        assertEquals(5, MenuStyle.visible("&a&lHello"));
        assertEquals(5, MenuStyle.visible("§aHello"));
        assertEquals(3, MenuStyle.visible("a&z"), "an ampersand before a letter that is no code is a letter");
        assertEquals(7, MenuStyle.visible("Q & A &"), "and so is one before a space, or at the end");
    }

    @Test
    void titlesAreDarkAndFitTheBar() {
        assertEquals("&2Games", MenuStyle.title("Games"));
        assertEquals("&2Games &8- Luck", MenuStyle.title("Games", "Luck"));
        assertEquals("&2Games", MenuStyle.title("&b&lGames", " "), "colours and bold are dropped, a blank part too");
        String long1 = MenuStyle.title("Sports Board", "tonight's games and then some more");
        assertTrue(MenuStyle.visible(long1) <= MenuStyle.TITLE_MAX, long1);
        assertTrue(long1.startsWith("&2Sports Board &8- tonight"), long1);
        assertTrue(long1.endsWith("…"), long1);
        String long2 = MenuStyle.title("A course with a very long name indeed", "Scorecard");
        assertTrue(MenuStyle.visible(long2) <= MenuStyle.TITLE_MAX, long2);
        assertTrue(long2.contains("&8- "), long2);
    }

    @Test
    void theWayOutAndHowToPlaySitSideBySideInTheBottomRow() {
        assertEquals(49, MenuStyle.exitSlot(54));
        assertEquals(48, MenuStyle.helpSlot(54));
        assertEquals(22, MenuStyle.exitSlot(27));
        assertEquals(21, MenuStyle.helpSlot(27));
        assertEquals(MenuStyle.EXIT, MenuStyle.exitSlot(54));
        assertEquals(MenuStyle.HELP, MenuStyle.helpSlot(54));
        assertEquals(45, MenuStyle.PREV);
        assertEquals(53, MenuStyle.NEXT);
    }

    @Test
    void aTileNameCarriesItsKeyFact() {
        assertEquals("&bSnake &7- best 12", MenuStyle.named("&b", "Snake", "best 12"));
        assertEquals("&bSnake", MenuStyle.named("&b", "Snake", null));
    }

    /** A literal title: {@code init(<size>, Text.of("...")} with no string joined to it. */
    private static final Pattern LITERAL_TITLE = Pattern.compile("init\\([^,]+,\\s*Text\\.of\\(\"([^\"]*)\"\\)\\)");

    @Test
    void noLiteralTitleRunsOffTheTitleBar() throws IOException {
        Path gui = Path.of("src", "main", "java", "com", "dierks", "homecraft", "gui");
        assertTrue(Files.isDirectory(gui), "expected to scan " + gui.toAbsolutePath());
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(gui)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = LITERAL_TITLE.matcher(src);
                while (m.find()) {
                    if (MenuStyle.visible(m.group(1)) > MenuStyle.TITLE_MAX) {
                        offences.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "titles longer than the title bar:\n" + String.join("\n", offences));
    }
}
