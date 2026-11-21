package com.dierks.homecraft.games.cpu;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The NPC players: a small fixed roster of six, two at each level, with friendly invented names (nobody real, nobody
 * famous) and a personality each. Pure.
 *
 * <p><b>The marker.</b> Wherever an NPC's name is shown it is followed by a grey gear ({@value #MARKER}) and its stars,
 * so nobody, a child included, mistakes one for a real person (the owner: "The gear would be good -- Otherwise NPC would
 * prob be better"). A place that can only take plain words says {@value #WORD} instead ({@link #plain}). The marker and
 * the stars are added here, never typed into a name, so renaming one can never take them away ({@link #cleanName}).
 */
public final class CpuRoster {

    /** The gear after every NPC's name. U+2699, in GNU Unifont: Java and Bedrock both draw it. */
    public static final String MARKER = "⚙";
    /** The word where a gear can't be shown. */
    public static final String WORD = "NPC";
    /** The longest name the owner can give one. */
    public static final int MAX_NAME = 16;

    /** The roster, in the order screens list it. */
    public static final List<CpuPlayer> ALL = List.of(
            new CpuPlayer("pip", "Pip", new NpcSkin.BuiltIn("sunny", false), CpuPersonality.CHATTY, CpuLevel.EASY, 1),
            new CpuPlayer("biscuit", "Biscuit", new NpcSkin.BuiltIn("kai", false), CpuPersonality.HUNCH, CpuLevel.EASY,
                    3),
            new CpuPlayer("wren", "Wren", new NpcSkin.BuiltIn("noor", true), CpuPersonality.CAREFUL, CpuLevel.MEDIUM,
                    2),
            new CpuPlayer("mabel", "Mabel", new NpcSkin.BuiltIn("makena", true), CpuPersonality.HUNCH,
                    CpuLevel.MEDIUM, 2),
            new CpuPlayer("ozzie", "Ozzie", new NpcSkin.BuiltIn("efe", false), CpuPersonality.BOLD, CpuLevel.HARD, 4),
            new CpuPlayer("tansy", "Tansy", new NpcSkin.BuiltIn("zuri", true), CpuPersonality.CAREFUL, CpuLevel.HARD,
                    0));

    private CpuRoster() {
    }

    /** The NPC with this key ({@code pip}), any case, or {@code null}. */
    public static CpuPlayer byKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (CpuPlayer c : ALL) {
            if (c.key().equals(k)) {
                return c;
            }
        }
        return null;
    }

    /** The NPC whose UUID this is, or {@code null} for anyone else (a real player). */
    public static CpuPlayer byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (CpuPlayer c : ALL) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Whether {@code id} is an NPC's, never a real player's. */
    public static boolean isCpu(UUID id) {
        return byId(id) != null;
    }

    /** {@code "&fPip &7⚙ &e★"}: the name, the grey gear and the level's stars, for a screen or a board. */
    public static String label(String name, CpuLevel level) {
        return "&f" + name + " &7" + MARKER + " &e" + level.starText();
    }

    /**
     * {@code "Pip ⚙ NPC"}: an NPC's name where a world game lists it among real players (a race's results, a round's
     * lines, a scorecard): the gear and the word, so a child who can't read yet sees the gear and a grown-up reads
     * "NPC". Never a colour code, so it sits inside any line.
     */
    public static String tagged(String name) {
        return name + " " + MARKER + " " + WORD;
    }

    /** {@code "Pip (NPC)"}: for a place that only takes plain words (the console, a log line). */
    public static String plain(String name) {
        return name + " (" + WORD + ")";
    }

    /**
     * A name the owner typed, made fit to show, or {@code null} when nothing is left: colour codes, the gear, stars
     * and anything but letters, digits, spaces, {@code -}, {@code '} and {@code _} are taken out, and it is cut to
     * {@value #MAX_NAME}. The marker and the stars are never part of a name: {@link #label} adds them.
     */
    public static String cleanName(String typed) {
        if (typed == null) {
            return null;
        }
        String s = typed.replaceAll("(?i)[&§][0-9a-fk-orx]", "");
        s = s.replaceAll("[^\\p{L}\\p{N} _'\\-]", "").replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME).trim();
        }
        return s.isEmpty() ? null : s;
    }
}
