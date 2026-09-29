package com.dierks.homecraft.games.gen.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What makes a course row a generated one (GEN-SPEC §5.1): the {@code gen:} block of its
 * {@code game_courses} data, carried as the last part of {@code trial.Course} and
 * {@code golf.GolfCourse}. A course with no tag is hand-built and behaves exactly as it always did.
 *
 * <p>The tag says which layout the blocks are: the slot, the generator and its {@code algo}, the
 * course day and reroll, the seed, the half it stands in and the plan's hash — enough to derive
 * the whole plan again at every boot without storing a block of it. It also carries what the
 * boards and rewards need from that layout (the reference and star times) and, for golf, what the
 * boot check replays on the real blocks (each hole's chosen attempt and witness line).
 *
 * <p>Because a run keeps its course as it was when it started, the run keeps the tag too: its
 * finish goes on its own layout's day board, and the "still standing" rule (§3.4) asks about the
 * layout it started on.
 *
 * @param slot      the slot id ({@link Slots})
 * @param generator the planner's id ({@code parkour}, {@code rings}, {@code golf}, {@code boat})
 * @param algo      the planner's version when this was made
 * @param day       the course day (local epoch day) this layout is for
 * @param reroll    0, or the admin's reroll number that day
 * @param seed      the plan's seed (admins only; never published)
 * @param half      'A' or 'B': where the blocks stand
 * @param planHash  the plan's hash (12 hex)
 * @param refMs     the reference (expert) time; 0 for golf
 * @param goldMs    the 3-star time, fixed when the layout was made; 0 for golf
 * @param silverMs  the 2-star time; 0 for golf
 * @param attempts  golf: each hole's winning attempt number, to rebuild it without the solver
 * @param witness   golf: each hole's expert line, replayed at every build and boot
 * @param builtAt   when the blocks were verified (epoch ms)
 */
public record GenTag(String slot, String generator, int algo, long day, int reroll, long seed, char half,
                     String planHash, long refMs, long goldMs, long silverMs, List<Integer> attempts,
                     List<List<Putt>> witness, long builtAt) {

    public GenTag {
        slot = slot == null ? "" : slot;
        generator = generator == null ? "" : generator;
        planHash = planHash == null ? "" : planHash;
        half = Character.toUpperCase(half);
        if (half != 'A' && half != 'B') {
            throw new IllegalArgumentException("a half is A or B: " + half);
        }
        reroll = Math.max(0, reroll);
        attempts = List.copyOf(attempts == null ? List.of() : attempts);
        List<List<Putt>> lines = new ArrayList<>();
        if (witness != null) {
            for (List<Putt> line : witness) {
                lines.add(List.copyOf(line == null ? List.of() : line));
            }
        }
        witness = List.copyOf(lines);
    }

    /** The name of this layout: the day, or {@code <day>r<reroll>} (its day board is {@code gday:<slot>:<this>}). */
    public String editionKey() {
        return Edition.editionKey(day, reroll);
    }

    /** The course day's date. */
    public LocalDate date() {
        return LocalDate.ofEpochDay(day);
    }

    /** The other half: where the next layout is built. */
    public char otherHalf() {
        return half == 'A' ? 'B' : 'A';
    }

    /**
     * Whether {@code other} names the same blocks: the same slot, generator, algo, half and plan.
     * A pinned layout restamped for a new day (§3.2) is the same layout under a new day.
     */
    public boolean sameLayout(GenTag other) {
        return other != null && slot.equals(other.slot) && generator.equals(other.generator) && algo == other.algo
                && half == other.half && planHash.equals(other.planHash);
    }

    /** The same layout for another course day and reroll (a restamp: new boards, no blocks). */
    public GenTag withEdition(long newDay, int newReroll) {
        return new GenTag(slot, generator, algo, newDay, newReroll, seed, half, planHash, refMs, goldMs, silverMs,
                attempts, witness, builtAt);
    }

    /** The same tag with a new verified time. */
    public GenTag withBuiltAt(long at) {
        return new GenTag(slot, generator, algo, day, reroll, seed, half, planHash, refMs, goldMs, silverMs,
                attempts, witness, at);
    }
}
