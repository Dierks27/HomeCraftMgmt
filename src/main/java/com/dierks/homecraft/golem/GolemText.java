package com.dierks.homecraft.golem;

/**
 * Everything the Copper Golem Brush says (0.38.4): plain chat lines a Bedrock player reads too (no hover, no click),
 * kid-safe, no emoji, nothing above U+FFFF ({@code GolemTextTest} scans the package as the Clubhouse's scan does).
 * The name is the golem's own ({@link GolemRules#base}).
 */
public final class GolemText {

    private GolemText() {
    }

    /** The brush's name, as it reads in the bag. */
    public static final String BRUSH_NAME = "&6Copper Golem Brush";

    /** The session opened: what to do next. */
    public static String pick(String name) {
        return "&6" + name + " &7- pick its chests (60 s). Tap a chest to link it, tap again to unlink.";
    }

    /** A chest linked as #{@code from} (a double chest: #{@code from} and #{@code to}). */
    public static String linked(int from, int to) {
        return from == to ? "&aChest #" + from + " linked." : "&aChests #" + from + " and #" + to
                + " linked (both halves).";
    }

    /** A chest unlinked; {@code left} stay. */
    public static String unlinked(int left) {
        return "&7Chest unlinked - " + GolemRules.chests(left) + " left.";
    }

    /** The session ended, however it ended. */
    public static String keeps(String name, int chests) {
        return "&6" + name + " &7keeps " + GolemRules.chests(chests) + ".";
    }

    /** Two shift-taps: its chests are gone. */
    public static String cleared(String name) {
        return "&6" + name + " &7has no chests of its own now - it uses any chest again.";
    }

    /** The first shift-tap on the session's golem. */
    public static String armClear(String name) {
        return "&eShift-tap " + name + " again &7(within 2 s) to clear its chests.";
    }

    /** A chest linked beyond where the golem walks: still linked, but said. */
    public static String farHint(String name) {
        return "&7That's a long way for " + name + " - it only uses chests within about 32 blocks of where it walks"
                + " (one block up, two down).";
    }

    /** The golem died, or isn't there any more. */
    public static String gone(String name) {
        return "&7" + name + " is gone.";
    }

    /** The session's golem is unloaded: not gone, just far. */
    public static String away(String name) {
        return "&7" + name + " is too far away - walk back to it and tap it again.";
    }

    /** A chest in another world than the golem's. */
    public static String otherWorld(String name) {
        return "&c" + name + " can't reach a chest in another world.";
    }

    public static final String FULL = "&cThat golem has all the chests it can keep (" + GolemRules.MAX_CHESTS + ").";
    public static final String OFF = "&7The Copper Golem Brush is switched off (golem.enabled).";

    /** The brush's lore. */
    public static final String[] LORE = {
            "&7Tap a Copper Golem to pick its chests,",
            "&7then tap each chest it may use.",
            "&7Tap the golem again when you're done.",
            "",
            "&eShift-tap the golem twice &7to clear its chests.",
    };
}
