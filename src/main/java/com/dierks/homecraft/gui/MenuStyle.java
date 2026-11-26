package com.dierks.homecraft.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * The house style every HomeCraft screen shares (0.59.0, the face lift; the owner: "the menus for the Games menu when
 * you click on the console are a bit messy again from the changes. Can you simply go through all menus ... and get
 * them a face lift a bit."). Plain strings and numbers only, so the rules can be tested with no server; the icons that
 * follow them are made by {@link Menus}.
 *
 * <p>The conventions, so a hand (or a controller's cursor) learns one screen and knows them all:
 * <ul>
 *   <li><b>Title</b>: {@link #title(String)} / {@link #title(String, String)}, "&amp;2Screen &amp;8- part": dark ink,
 *       which reads on the light grey title bar (aqua, gold and yellow titles didn't), never bold, and cut to
 *       {@link #TITLE_MAX} letters so it never runs off the bar.</li>
 *   <li><b>Row 0 is the header</b>: tabs, or the screen's own picture and key fact.</li>
 *   <li><b>The bottom row is the way around</b>: on a 54-slot screen 45 ◀ and 53 ▶ ({@link #PREV}, {@link #NEXT}: page
 *       arrows and nothing else), 49 Back or Close ({@link #EXIT}) and 48 How to play ({@link #HELP}) right beside it.
 *       A smaller screen keeps the same two in its bottom row's middle ({@link #exitSlot}, {@link #helpSlot}: 22 and 21
 *       on 27 slots), so the buttons a cursor needs most are next to each other.</li>
 *   <li><b>Filler</b> is the grey pane ({@link Menus#FILLER}) everywhere but the Arcade hub's purple border.</li>
 *   <li><b>A tile's name</b> carries its key fact, "&amp;&lt;colour&gt;Game &amp;7- key fact" ({@link #named}), because Bedrock
 *       shows lore only on a long press. On the Games screen the colour says the tab: Luck &amp;a, Cabinets &amp;b,
 *       Courses &amp;e, Golf &amp;d, Together &amp;6, Little ones &amp;d.</li>
 *   <li><b>Lore</b> is wrapped at {@link #LORE_WIDTH} letters ({@link #wrap}, done by {@link Menus#icon} itself), so no
 *       line runs across the whole screen.</li>
 *   <li><b>Kids' screens</b> (a cabinet tapped in the Arcade Room, Picture Bingo, the kid race, the Floor Is Lava's
 *       picker) stay pictures: big buttons, one word names, nothing to read.</li>
 * </ul>
 */
public final class MenuStyle {

    /** The longest lore line, in letters (colour codes don't count); a longer one is wrapped. */
    public static final int LORE_WIDTH = 40;
    /** The most letters a title shows before it is cut with "…": what fits the title bar. */
    public static final int TITLE_MAX = 30;
    /** On a 54-slot screen: the previous-page arrow. */
    public static final int PREV = 45;
    /** On a 54-slot screen: the next-page arrow. */
    public static final int NEXT = 53;
    /** On a 54-slot screen: Back or Close. */
    public static final int EXIT = 49;
    /** On a 54-slot screen: How to play, right beside the way out. */
    public static final int HELP = 48;

    private MenuStyle() {
    }

    /** The slot of the way out on a screen of {@code size} slots: the bottom row's middle (49, 40, 31, 22). */
    public static int exitSlot(int size) {
        return size - 5;
    }

    /** The slot of How to play on a screen of {@code size} slots: just left of the way out (48, 21). */
    public static int helpSlot(int size) {
        return size - 6;
    }

    /** A screen's title in the house style: "&amp;2Screen", cut to fit. */
    public static String title(String screen) {
        return "&2" + cut(plain(screen), TITLE_MAX);
    }

    /**
     * A screen's title with which part of it this is, "&amp;2Screen &amp;8- part", cut to fit the title bar: the part
     * first, and the screen's own name only when the part alone can't make room.
     */
    public static String title(String screen, String part) {
        if (part == null || part.isBlank()) {
            return title(screen);
        }
        String s = plain(screen);
        String p = plain(part);
        int room = TITLE_MAX - s.length() - 3;
        if (room < 8) {
            String head = cut(s, TITLE_MAX - 11);
            return "&2" + head + " &8- " + cut(p, TITLE_MAX - head.length() - 3);
        }
        return "&2" + s + " &8- " + cut(p, room);
    }

    /** A tile's name in the house style: "&amp;&lt;colour&gt;Name &amp;7- key fact" (no fact: the name alone). */
    public static String named(String colour, String name, String fact) {
        return colour + name + (fact == null || fact.isBlank() ? "" : " &7- " + fact);
    }

    /**
     * An '&amp;'-coded line cut into lines of at most {@code width} letters, at spaces (a single word longer than that
     * stays whole). Each new line opens with the colour and formats in force where it was cut, so a grey sentence
     * stays grey on its second line. Colour codes don't count as letters.
     */
    public static List<String> wrap(String line, int width) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            out.add("");
            return out;
        }
        if (visible(line) <= width) {
            out.add(line);
            return out;
        }
        StringBuilder current = new StringBuilder();
        int currentLen = 0;
        boolean started = false;
        String active = "";
        String lineStart = "";
        for (String word : line.split(" ", -1)) {
            int len = visible(word);
            if (currentLen > 0 && currentLen + 1 + len > width) {
                out.add(lineStart + current.toString().stripTrailing());
                current.setLength(0);
                currentLen = 0;
                started = false;
                lineStart = active;
            }
            if (started) {
                current.append(' ');
                currentLen++;
            }
            current.append(word);
            currentLen += len;
            started = true;
            active = codesAfter(active, word);
        }
        if (started) {
            out.add(lineStart + current.toString().stripTrailing());
        }
        return out;
    }

    /** How many letters {@code s} shows: its length without the '&amp;' (or section-sign) codes. */
    public static int visible(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length() && isCode(s.charAt(i + 1))) {
                i++;
                continue;
            }
            n++;
        }
        return n;
    }

    /** The words without their colour codes. */
    public static String plain(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length() && isCode(s.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** {@code s} cut to {@code max} letters, the last one an ellipsis when anything was cut. */
    static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    private static boolean isCode(char c) {
        return "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c) >= 0;
    }

    /** The codes in force after {@code text}, starting from {@code active}: a colour (or &amp;r) resets the formats. */
    private static String codesAfter(String active, String text) {
        String colour = "";
        StringBuilder formats = new StringBuilder();
        String all = active + text;
        for (int i = 0; i + 1 < all.length(); i++) {
            char c = all.charAt(i);
            if ((c == '&' || c == '§') && isCode(all.charAt(i + 1))) {
                char k = Character.toLowerCase(all.charAt(i + 1));
                if ("0123456789abcdef".indexOf(k) >= 0) {
                    colour = "&" + k;
                    formats.setLength(0);
                } else if (k == 'r') {
                    colour = "";
                    formats.setLength(0);
                } else {
                    formats.append('&').append(k);
                }
                i++;
            }
        }
        return colour + formats;
    }
}
