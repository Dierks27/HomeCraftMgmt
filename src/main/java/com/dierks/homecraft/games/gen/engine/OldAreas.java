package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure rules of RETIRE (V4-DECISIONS "One move mechanism"): what an old area a slot left behind is,
 * what of it is Fresh Courses' own, when it is safe to empty, and how its outcome is kept.
 *
 * <p><b>Why it exists.</b> A claim records where a slot's halves were built and how big they were
 * ({@link Regions#claim}). When a version changes a slot's size (Golf v4's 128 x 16 x 224 halves, the
 * Mountain Run v2's 480 x 176 x 640) or its shipped spot, the old claim no longer matches: what stands at
 * the old place is the plugin's own, nobody can play it, and nobody else may change it (it stays guarded).
 * RETIRE empties it by itself, at the sizes the old claim RECORDED, the water first, and then forgets it.
 *
 * <p><b>Only our own blocks go.</b> A Fresh Courses plan may only ever place a block of {@link Palette}'s
 * allowlist (or a sealed pool's still water), and every old half was guarded while it stood. So a block
 * that isn't on the list ({@link #ours}) can only be someone else's (a WorldEdit paste, say): it is left
 * exactly where it is and named in the console and {@code /hcm games check}. Water is always drained,
 * whatever made it: a pool left standing once its walls are gone would flow out over the sky.
 *
 * <p><b>Never into anything else.</b> Before a single block is written, the old halves, grown by
 * {@value Regions#CLEARANCE} blocks, must not meet any other area that may hold something ({@link #inTheWay}):
 * a slot's claimed halves, another slot's old area, the Clubhouse, the arena, the keep area or a kept
 * course's plot, a hand-built course, the world's spawn or the safe spot. If one does, nothing is
 * written, the old area stays guarded and listed, and one WARN names what is in the way.
 */
public final class OldAreas {

    private OldAreas() {
    }

    /**
     * Whether a block may be Fresh Courses' own: a block of {@link Palette#ALLOWED} (whatever its states),
     * or water of any level (a pond or pool, or what flowed from one).
     */
    public static boolean ours(String blockData) {
        return blockData != null && (Palette.allowed(blockData) || BuildJob.fluid(blockData));
    }

    /** Whether a block is someone else's and stays as it is: {@link #ours} is false. */
    public static boolean foreign(String blockData) {
        return !ours(blockData);
    }

    /**
     * Something an old area must keep clear of.
     *
     * @param world its world
     * @param box   its blocks
     * @param name  what an owner reads ("Golf of the Week's half A", "the Clubhouse", "kept plot 3")
     */
    public record Obstacle(String world, Box box, String name) {
    }

    /**
     * What is in the way of emptying {@code halves} in {@code world}, in the owner's words, or {@code null}
     * when nothing is: an obstacle within {@value Regions#CLEARANCE} blocks of a half, or the world's
     * spawn or the safe spot within as much.
     *
     * @param spawn the world's spawn {x, y, z}, or {@code null}
     * @param safe  {@code games.fresh.safe_spot}, or {@code null}
     */
    public static String inTheWay(List<Box> halves, String world, List<Obstacle> obstacles, int[] spawn,
                                  double[] safe) {
        if (halves == null || halves.isEmpty()) {
            return "its halves can't be read";
        }
        for (int i = 0; i < halves.size(); i++) {
            Box h = halves.get(i);
            char which = (char) ('A' + i);
            for (Obstacle o : obstacles == null ? List.<Obstacle>of() : obstacles) {
                if (o == null || o.world() == null || world == null || !o.world().equalsIgnoreCase(world)) {
                    continue;
                }
                int gap = h.gap(o.box());
                if (gap < Regions.CLEARANCE) {
                    return o.name() + " is " + (gap < 0 ? "inside" : "only " + gap + " blocks from") + " its old half "
                            + which + " (" + o.box().describe() + "; it must be " + Regions.CLEARANCE + " away)";
                }
            }
            if (spawn != null && point(spawn[0], spawn[1], spawn[2]).gap(h) < Regions.CLEARANCE) {
                return "the world's spawn is in or next to its old half " + which;
            }
            if (safe != null && point((int) Math.floor(safe[0]), (int) Math.floor(safe[1]), (int) Math.floor(safe[2]))
                    .gap(h) < Regions.CLEARANCE) {
                return "games.fresh.safe_spot is in or next to its old half " + which;
            }
        }
        return null;
    }

    private static Box point(int x, int y, int z) {
        return new Box(x, y, z, x, y, z);
    }

    /**
     * Whether any of {@code halves} comes within {@value Regions#APART} blocks of any of {@code wanted}: a
     * region waiting to be built there must wait until the old area is empty, rather than find its blocks
     * and be refused as foreign (MOUNTAIN-V2-SPEC §11.2 "Ordering"; GOLF-V4-SPEC §5.2 "tidy first").
     */
    public static boolean crowds(List<Box> halves, List<Box> wanted) {
        for (Box h : halves == null ? List.<Box>of() : halves) {
            for (Box w : wanted == null ? List.<Box>of() : wanted) {
                if (h.gap(w) < Regions.APART) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- the record of an old area emptied ----------------------------------------------------------

    /**
     * The last old area of a slot RETIRE emptied ({@link GenAdminKeys#retired}), for the check.
     *
     * @param at        when it finished (epoch ms)
     * @param claim     the old claim it emptied (with its recorded sizes)
     * @param removed   blocks taken away
     * @param left      blocks left there because they weren't Fresh Courses' ({@link #foreign})
     * @param firstLeft the first few of those, "x,y,z block"
     */
    public record Retired(long at, String claim, long removed, long left, List<String> firstLeft) {

        public Retired {
            firstLeft = firstLeft == null ? List.of() : List.copyOf(firstLeft);
        }

        /** As stored: {@code at|claim|removed|left|first;first...}. */
        public String text() {
            List<String> clean = new ArrayList<>();
            for (String f : firstLeft) {
                clean.add(f.replace('|', '/').replace(';', '/'));
            }
            return at + "|" + claim + "|" + removed + "|" + left + "|" + String.join(";", clean);
        }

        /** A stored record, or {@code null} when unset or unreadable. */
        public static Retired parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.split("\\|", 5);
            if (p.length < 4) {
                return null;
            }
            try {
                List<String> first = p.length < 5 || p[4].isBlank() ? List.of() : List.of(p[4].split(";"));
                return new Retired(Long.parseLong(p[0].trim()), p[1], Long.parseLong(p[2].trim()),
                        Long.parseLong(p[3].trim()), first);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ---- what the check and status are told -----------------------------------------------------

    /** Where an old area is. */
    public enum State {
        /** It will be emptied by itself, once nothing else is due (this version changed the slot's size). */
        WAITING,
        /** It is being emptied now. */
        RUNNING,
        /** It can't be emptied: something is in the way ({@link Area#detail}). It stays guarded. */
        HELD,
        /** An admin's move left it (an origin or world change): it stays guarded until {@code tidy}. */
        MANUAL,
        /** Its world isn't loaded: it waits for it. */
        ELSEWHERE
    }

    /**
     * One old area as {@code /hcm games check} and status read it.
     *
     * @param slot    the slot (or Classics slot) it belongs to
     * @param name    the slot's player-facing name
     * @param where   "half A x ..; half B ..", at the recorded sizes
     * @param detail  what is in the way ({@link State#HELD}), the world it waits for, or {@code null}
     * @param percent how far a running one is, 0-100
     */
    public record Area(String slot, String name, boolean classic, String claim, String where, State state,
                       String detail, int percent) {
    }

    /**
     * An old area's state line for status ("its old area (half A ...) is being emptied, 63%"), without the
     * slot id in front.
     */
    public static String line(Area a) {
        String it = "its old area (" + a.where() + ")";
        return switch (a.state()) {
            case RUNNING -> "emptying " + it + ", " + a.percent() + "%";
            case WAITING -> it + " is emptied by itself once nothing else is being built";
            case HELD -> it + " can't be emptied: " + a.detail() + " - it stays guarded";
            case MANUAL -> it + " is still guarded - empty it with /hcm games gen tidy " + a.slot() + " confirm";
            case ELSEWHERE -> it + " waits for its world " + a.detail() + " to be loaded";
        };
    }
}
