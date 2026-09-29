package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Line;

import java.util.List;

/**
 * The Clubhouse's rows in {@code /hcm games check} (CLUBHOUSE-SPEC §5; WP-CH): whether it is on,
 * whether its box may stand where it is ({@code Regions.extraProblems}: Fresh Courses' areas, the
 * kept courses, the arena, hand-built courses, the world), whether the box is claimed, whether it is
 * built and checked, and for an owner-built room whether its spots are set.
 *
 * <p>Pure, like {@link GamesCheck} and {@link ArenaCheck}: the facts come in ({@link GamesCheckLive}
 * reads them), only the rules and the words are here. Read-only.
 */
final class ClubhouseCheck {

    /**
     * The Clubhouse as the check reads it.
     *
     * @param enabled     {@code games.enabled} and {@code games.clubhouse.enabled}
     * @param handBuilt   the owner-built room is the Clubhouse ({@code /hcm games clubhouse here})
     * @param world       its world; {@code ""} for none
     * @param worldLoaded that world is loaded
     * @param box         where the generated room's box is, for admins
     * @param problems    why the box can't be used there, empty when it can
     * @param claim       the claim's state
     * @param closed      why the running Clubhouse closed itself, or {@code null}
     * @param ready       open: built and checked (or the owner-built room's arrival spot set)
     * @param handSpots   the owner-built room's spots ("arrival not set", ...)
     * @param handComplete every owner-built spot is set
     */
    record Facts(boolean enabled, boolean handBuilt, String world, boolean worldLoaded, String box,
                 List<String> problems, ArenaCheck.Claim claim, String closed, boolean ready, List<String> handSpots,
                 boolean handComplete) {

        Facts {
            world = world == null ? "" : world;
            problems = List.copyOf(problems == null ? List.of() : problems);
            handSpots = List.copyOf(handSpots == null ? List.of() : handSpots);
        }
    }

    private static final String ORIGIN = "move it with games.clubhouse.origin";

    private ClubhouseCheck() {
    }

    /** The Clubhouse's lines, in order. */
    static void rows(Facts f, List<Line> out) {
        if (f == null || !f.enabled()) {
            out.add(Line.ok("The Clubhouse is off - every race and round works as before (it needs games.enabled"
                    + " and games.clubhouse.enabled)"));
            return;
        }
        if (f.handBuilt()) {
            if (f.closed() != null) {
                out.add(Line.fail("The Clubhouse is closed: " + f.closed(), "see /hcm games clubhouse status"));
            } else if (!f.handComplete()) {
                out.add(Line.warn("The Clubhouse is your own room, but not every spot is set: "
                        + String.join(", ", f.handSpots()), "stand in each place and use /hcm games clubhouse"
                        + " podium <1|2|3> and board"));
            } else {
                out.add(Line.ok("The Clubhouse is your own room: arrival, podium and board all set"));
            }
            return;
        }
        if (f.world().isBlank()) {
            out.add(Line.fail("The Clubhouse has no world", "list a Games world in games.worlds, or set games.fresh.world"));
            return;
        }
        if (!f.worldLoaded()) {
            out.add(Line.fail("The Clubhouse's world '" + f.world() + "' isn't loaded",
                    "make it with Multiverse, or fix games.fresh.world"));
            return;
        }
        int before = out.size();
        for (String p : f.problems()) {
            out.add(Line.fail("The Clubhouse's box (" + f.box() + "): " + p, ORIGIN));
        }
        if (f.claim() == ArenaCheck.Claim.MOVED) {
            out.add(Line.warn("The Clubhouse's box was claimed at another place",
                    "those old blocks are left as they are: clear them by hand. The new box is checked before it is used"));
        } else if (f.claim() == ArenaCheck.Claim.UNKNOWN) {
            out.add(Line.warn("Couldn't read the Clubhouse's claim", "see the console, then run the check again"));
        }
        if (f.closed() != null && f.problems().isEmpty()) {
            out.add(Line.fail("The Clubhouse is closed: " + f.closed(), f.closed().contains("rebuild confirm")
                    ? "/hcm games clubhouse rebuild confirm (or " + ORIGIN + ")"
                    : "see /hcm games clubhouse status"));
        }
        if (out.size() > before) {
            return;
        }
        out.add(Line.ok("The Clubhouse: box fits (" + f.box() + "), "
                + (f.claim() == ArenaCheck.Claim.CLAIMED ? "claimed" : "empty (claimed at its first build)")
                + (f.ready() ? ", built and checked" : ", being built")));
    }
}
