package com.dierks.homecraft.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Firework Launcher's own rules (0.38.1, the Mega Firework Show; the owner's request: "3 droppers that I have to
 * set up"): how many a player may have placed at once, and what a placement and a tap say. Pure, so the counting and
 * every line are tested without a server; {@link CustomBlockListener} asks them.
 *
 * <p><b>Placed at once</b> is {@code arcade.launcher.per_player} (3), admins too. The count is read from the
 * placed-block records at the moment of the place, never kept anywhere else: a launcher picked up is one fewer at
 * once. A record on the very spot being placed (the record is written again on the same spot) is never counted, nor a
 * stale one, whose block is gone (air, in a loaded chunk: a WorldEdit clear leaves records behind); a launcher whose
 * world or chunk isn't loaded can't be looked at, so it counts.
 *
 * <p>A held tap or place says its line once, and again every 2 s while it is held ({@link Lines}).
 */
public final class LauncherRules {

    /** What a Firework Launcher is called in every line. */
    public static final String NAME = "Firework Launcher";

    private LauncherRules() {
    }

    /** Whether placing another is refused: {@code placed} already stand, and that is as many as one player may have. */
    public static boolean placeRefused(int placed, int perPlayer) {
        return placed >= perPlayer;
    }

    /**
     * How many of {@code records} count against a player: theirs ({@code mine}), not the spot being placed
     * ({@code here}), and not gone ({@code gone}, which may forget a stale record as it answers). The predicates are
     * asked in that order, so a stranger's record is never looked at in the world.
     */
    public static <R> int counted(List<R> records, Predicate<R> mine, Predicate<R> here, Predicate<R> gone) {
        int n = 0;
        for (R r : records) {
            if (mine.test(r) && !here.test(r) && !gone.test(r)) {
                n++;
            }
        }
        return n;
    }

    /** "Firework Launcher" or "Firework Launchers". */
    static String launchers(int n) {
        return n + " " + NAME + (n == 1 ? "" : "s");
    }

    /** A place refused because {@code perPlayer} already stand: the item stays in the hand. */
    public static String fullLine(int perPlayer) {
        return "&7You already have " + launchers(perPlayer) + " set up. Pick one up to move it!";
    }

    /** Said after "Placed a Firework Launcher.": the rocket goes straight up, and how many are set up now. */
    public static String placedLine(int placed, int perPlayer) {
        return "&7Keep the sky above it clear! &8(" + placed + " of " + perPlayer + " set up)";
    }

    /**
     * A tap on a launcher (with no Mega Firework Show in the hand, whose tap is the show's): whose it is, how many of
     * yours are set up, and what it is for; {@code busy} adds that a show is using it right now.
     */
    public static String tapLine(boolean mine, String owner, int placed, int perPlayer, boolean busy) {
        String head = mine ? "&6Your " + NAME + " &7(" + placed + " of " + perPlayer + " set up)."
                : "&6" + owner + "'s " + NAME + "&7.";
        return head + " Use a &dMega Firework Show &7near it!" + (busy ? " &dIt's in a show right now!" : "");
    }

    /**
     * When a launcher's own line is said (review LAUNCHER-SPAM-3). A held use button repeats a tap or a place about
     * five times a second (Java every 4 ticks, and a held Bedrock tap too), and a launcher's tap opens no screen that
     * would stop it, so the same line (a tap on the same launcher, or a fourth place) is said at most once every
     * {@value #GAP_MS} ms a player, and its sound and the records a tap line reads only with it, as the show refusals
     * are ({@code FireworkShows.refusalDue}). Another line (a tap on another launcher) is said at once. Plain state, so
     * a test drives it; main thread.
     */
    public static final class Lines {
        /** The least time between two of the same line to one player. */
        public static final long GAP_MS = 2000;

        /** The last line said to a player: what it was about, and when. */
        private record Said(Object about, long at) {
        }

        private final Map<UUID, Said> last = new HashMap<>();

        /**
         * Whether {@code player}'s line {@code about} something (a launcher's spot, a refusal's text) is said at
         * {@code now} (ms): not when the last one said was about the same thing, under {@link #GAP_MS} ago. A line
         * said is the last one; one kept quiet changes nothing.
         */
        public boolean due(UUID player, Object about, long now) {
            Said s = last.get(player);
            if (s != null && s.about().equals(about) && now - s.at() < GAP_MS) {
                return false;
            }
            last.put(player, new Said(about, now));
            return true;
        }

        /** The player quit: nothing of theirs is kept. */
        public void forget(UUID player) {
            last.remove(player);
        }
    }
}
