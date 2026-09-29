package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Line;

import java.util.List;

/**
 * Falling Floors' rows in {@code /hcm games check} (EXTRAS E1, EVENTS-DROPPER-SPEC §C.2 WP-F): whether
 * it is on, whether its box may stand where it is (Fresh Courses' areas, the kept courses,
 * hand-built courses, the world), whether the box is claimed, and whether the arena closed itself.
 *
 * <p>Pure, like {@link GamesCheck}: the facts come in ({@link GamesCheckLive} reads them from the
 * config, the world, the database and the running game), and only the rules and the words are
 * here, so every line is tested without a server. Read-only.
 */
final class ArenaCheck {

    /** What the claim ({@code gen.floors.claim}) says about the box where it is now. */
    enum Claim {
        /** Claimed here: the arena's own. */
        CLAIMED,
        /** Never claimed: it is claimed at its first build, when it is empty. */
        UNCLAIMED,
        /** Claimed at another place (the origin moved): those blocks were left as they were. */
        MOVED,
        /** The claim can't be read now. */
        UNKNOWN
    }

    /**
     * Falling Floors as the check reads it.
     *
     * @param enabled     {@code games.enabled} and {@code games.falling_floors.enabled}
     * @param world       its world ({@code games.fresh.world}, or the first Games world); {@code ""} for none
     * @param worldLoaded that world is loaded
     * @param box         where the box is, for admins
     * @param problems    why the box can't be used there, empty when it can
     * @param claim       the claim's state
     * @param closed      why the running arena closed itself, or {@code null}
     * @param ready       its floors are built and verified (players may come in)
     */
    record Facts(boolean enabled, String world, boolean worldLoaded, String box, List<String> problems, Claim claim,
                 String closed, boolean ready) {

        Facts {
            world = world == null ? "" : world;
            problems = List.copyOf(problems == null ? List.of() : problems);
        }
    }

    private static final String ORIGIN = "move it with games.falling_floors.origin";

    private ArenaCheck() {
    }

    /** Falling Floors' lines, in order. */
    static void rows(Facts f, List<Line> out) {
        if (f == null || !f.enabled()) {
            out.add(Line.ok("Falling Floors is off - nothing to check (it needs games.enabled and "
                    + "games.falling_floors.enabled)"));
            return;
        }
        if (f.world().isBlank()) {
            out.add(Line.fail("Falling Floors has no world", "list a Games world in games.worlds, or set games.fresh.world"));
            return;
        }
        if (!f.worldLoaded()) {
            out.add(Line.fail("Falling Floors' world '" + f.world() + "' isn't loaded",
                    "make it with Multiverse, or fix games.fresh.world"));
            return;
        }
        int before = out.size();
        for (String p : f.problems()) {
            out.add(Line.fail("Falling Floors' box (" + f.box() + "): " + p, ORIGIN));
        }
        if (f.claim() == Claim.MOVED) {
            out.add(Line.warn("Falling Floors' box was claimed at another place",
                    "those old blocks are left as they are: clear them by hand. The new box is checked before it is used"));
        } else if (f.claim() == Claim.UNKNOWN) {
            out.add(Line.warn("Couldn't read Falling Floors' claim", "see the console, then run the check again"));
        }
        if (f.closed() != null && f.problems().isEmpty()) {
            boolean foreign = f.closed().contains("claim confirm");
            out.add(Line.fail("Falling Floors is closed: " + f.closed(), foreign
                    ? "/hcm games floors claim confirm clears them (or " + ORIGIN + ")"
                    : "see /hcm games floors status; /hcm games floors reset opens it again"));
        }
        if (out.size() > before) {
            return;
        }
        out.add(Line.ok("Falling Floors: box fits (" + f.box() + "), "
                + (f.claim() == Claim.CLAIMED ? "claimed" : "empty (claimed at its first build)")
                + (f.ready() ? ", floors ready" : ", floors being built")));
    }
}
