package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Line;

import java.util.List;

/**
 * The Floor Is Lava's rows in {@code /hcm games check}: whether it is on, whether its box may stand where it is
 * (Fresh Courses' areas, the kept courses, the Clubhouse, the arena, hand-built courses, the world), whether the box
 * is claimed, whether the playroom closed itself, and whether the Clubhouse's Arcade Room has a place for its
 * cabinet.
 *
 * <p>Pure, like {@link GamesCheck} and {@link ArenaCheck}: the facts come in ({@link GamesCheckLive} reads them from
 * the config, the world, the database and the running game), and only the rules and the words are here, so every
 * line is tested without a server. Read-only.
 */
final class LavaCheck {

    /**
     * The Floor Is Lava as the check reads it.
     *
     * @param enabled      {@code games.enabled} and {@code games.floor_is_lava.enabled}
     * @param world        its world ({@code games.fresh.world}, or the first Games world); {@code ""} for none
     * @param worldLoaded  that world is loaded
     * @param box          where the box is, for admins
     * @param problems     why the box can't be used there, empty when it can
     * @param claim        the claim's state ({@code gen.lava.claim})
     * @param closed       why the running playroom closed itself, or {@code null}
     * @param ready        the playroom is built and checked (players may come in)
     * @param arcadeRoom   the Clubhouse and its Arcade Room are on (its cabinet can stand there)
     * @param cabinetPlace the Arcade Room has a cabinet place after its listed games (the lava cabinet takes it)
     */
    record Facts(boolean enabled, String world, boolean worldLoaded, String box, List<String> problems,
                 ArenaCheck.Claim claim, String closed, boolean ready, boolean arcadeRoom, boolean cabinetPlace) {

        Facts {
            world = world == null ? "" : world;
            problems = List.copyOf(problems == null ? List.of() : problems);
        }
    }

    private static final String ORIGIN = "move it with games.floor_is_lava.origin";

    private LavaCheck() {
    }

    /** The Floor Is Lava's lines, in order. */
    static void rows(Facts f, List<Line> out) {
        if (f == null || !f.enabled()) {
            out.add(Line.ok("The Floor Is Lava is off - nothing to check (it needs games.enabled and "
                    + "games.floor_is_lava.enabled)"));
            return;
        }
        if (f.world().isBlank()) {
            out.add(Line.fail("The Floor Is Lava has no world", "list a Games world in games.worlds, or set"
                    + " games.fresh.world"));
            return;
        }
        if (!f.worldLoaded()) {
            out.add(Line.fail("The Floor Is Lava's world '" + f.world() + "' isn't loaded",
                    "make it with Multiverse, or fix games.fresh.world"));
            return;
        }
        int before = out.size();
        for (String p : f.problems()) {
            out.add(Line.fail("The Floor Is Lava's box (" + f.box() + "): " + p, ORIGIN));
        }
        if (f.claim() == ArenaCheck.Claim.MOVED) {
            out.add(Line.warn("The Floor Is Lava's box was claimed at another place",
                    "those old blocks are left as they are: clear them by hand. The new box is checked before it is used"));
        } else if (f.claim() == ArenaCheck.Claim.UNKNOWN) {
            out.add(Line.warn("Couldn't read The Floor Is Lava's claim", "see the console, then run the check again"));
        }
        if (f.closed() != null && f.problems().isEmpty()) {
            boolean foreign = f.closed().contains("claim confirm");
            out.add(Line.fail("The Floor Is Lava is closed: " + f.closed(), foreign
                    ? "/hcm games lava claim confirm clears them (or " + ORIGIN + ")"
                    : "see /hcm games lava status; /hcm games lava rebuild opens it again"));
        }
        if (out.size() == before) {
            out.add(Line.ok("The Floor Is Lava: box fits (" + f.box() + "), "
                    + (f.claim() == ArenaCheck.Claim.CLAIMED ? "claimed" : "empty (claimed at its first build)")
                    + (f.ready() ? ", playroom ready" : ", playroom being built")));
        }
        if (!f.arcadeRoom()) {
            out.add(Line.warn("The Floor Is Lava has no cabinet in the Clubhouse (the Clubhouse or its Arcade Room"
                    + " is off)", "it is still played from the Games screen and /hcm play floor_is_lava; for the"
                    + " one-tap cabinet turn on games.clubhouse.enabled and games.clubhouse.arcade_room.enabled"));
        } else if (!f.cabinetPlace()) {
            out.add(Line.warn("The Arcade Room has no place for The Floor Is Lava's cabinet (every cabinet place has"
                    + " a game of its own)", "list fewer games in games.clubhouse.arcade_room.games; in a room you"
                    + " built yourself, /hcm games clubhouse cabinet floor_is_lava puts it where you stand"));
        }
    }
}
