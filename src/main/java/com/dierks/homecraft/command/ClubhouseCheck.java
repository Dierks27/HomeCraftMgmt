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
     * @param handWorld   the owner-built room's world ({@code ""} for none)
     * @param handListed  that world is one of {@code games.worlds}
     * @param roomsOn     0.41.0: the Arcade Room or the Trophy Room is switched on
     * @param annexProblems 0.41.0: why the side rooms' box can't be used, empty when it can
     * @param sideRooms   0.41.0: the running room's side rooms in a line, or {@code null}
     * @param kidRefused  0.41.0: the Arcade Room's listed games that are left out (a game of chance, not a game)
     */
    record Facts(boolean enabled, boolean handBuilt, String world, boolean worldLoaded, String box,
                 List<String> problems, ArenaCheck.Claim claim, String closed, boolean ready, List<String> handSpots,
                 boolean handComplete, String handWorld, boolean handListed, boolean roomsOn,
                 List<String> annexProblems, String sideRooms, List<String> kidRefused) {

        Facts {
            world = world == null ? "" : world;
            handWorld = handWorld == null ? "" : handWorld;
            problems = List.copyOf(problems == null ? List.of() : problems);
            handSpots = List.copyOf(handSpots == null ? List.of() : handSpots);
            annexProblems = List.copyOf(annexProblems == null ? List.of() : annexProblems);
            kidRefused = List.copyOf(kidRefused == null ? List.of() : kidRefused);
        }

        /** 0.40.0's facts: no side rooms. */
        Facts(boolean enabled, boolean handBuilt, String world, boolean worldLoaded, String box, List<String> problems,
              ArenaCheck.Claim claim, String closed, boolean ready, List<String> handSpots, boolean handComplete,
              String handWorld, boolean handListed) {
            this(enabled, handBuilt, world, worldLoaded, box, problems, claim, closed, ready, handSpots, handComplete,
                    handWorld, handListed, false, List.of(), null, List.of());
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
            if (!f.handListed()) { // the Clubhouse review, #13: a room set before `here` checked the world
                out.add(Line.warn("The Clubhouse is your own room in '" + f.handWorld() + "', which isn't a Games"
                        + " world (games.worlds)", "stand in a room in a Games world and use /hcm games clubhouse here,"
                        + " or go back to the generated room with /hcm games clubhouse generated"));
            }
            if (f.closed() != null) {
                out.add(Line.fail("The Clubhouse is closed: " + f.closed(), "see /hcm games clubhouse status"));
            } else if (!f.handComplete()) {
                out.add(Line.warn("The Clubhouse is your own room, but not every spot is set: "
                        + String.join(", ", f.handSpots()), "stand in each place and use /hcm games clubhouse"
                        + " podium <1|2|3> and board"));
            } else {
                out.add(Line.ok("The Clubhouse is your own room: arrival, podium and board all set"));
            }
            kids(f, out);
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
        sideRooms(f, out);
    }

    /**
     * 0.41.0, the generated room's side rooms: switched off; their box in another area's way (the main room opens
     * without them); left out for blocks in their box; or built.
     */
    static void sideRooms(Facts f, List<Line> out) {
        if (!f.roomsOn() && f.sideRooms() == null) {
            kids(f, out);
            return; // no running room to report on (or 0.40.0's facts): nothing to say
        }
        if (!f.roomsOn()) {
            out.add(Line.ok("The Clubhouse's Arcade Room and Trophy Room are switched off"
                    + " (games.clubhouse.arcade_room / trophy_room)"));
            return;
        }
        for (String p : f.annexProblems()) {
            out.add(Line.warn("The Clubhouse's side rooms (the 16 rows south of it): " + p + " - they are left out",
                    ORIGIN + ", or switch them off with games.clubhouse.arcade_room.enabled and trophy_room.enabled"));
        }
        if (f.annexProblems().isEmpty()) {
            String s = f.sideRooms();
            if (s != null && s.startsWith("left out")) {
                out.add(Line.warn("The Clubhouse's side rooms are " + s, s.contains("rebuild confirm")
                        ? "/hcm games clubhouse rebuild confirm" : "see /hcm games clubhouse status"));
            } else {
                out.add(Line.ok("The Clubhouse's side rooms: " + (s == null ? "built with the room" : s)));
            }
        }
        kids(f, out);
    }

    /** The Arcade Room's list: each game left out (a game of chance, or no such cabinet) is a WARN. */
    private static void kids(Facts f, List<Line> out) {
        for (String why : f.kidRefused()) {
            out.add(Line.warn(why, "list only arcade cabinet games in games.clubhouse.arcade_room.games"));
        }
    }
}
