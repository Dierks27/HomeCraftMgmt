package com.dierks.homecraft.games.gen.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What makes a course row a generated one (GEN-SPEC §5.1): the {@code gen:} block of its
 * {@code game_courses} data, carried as the last part of {@code trial.Course} and
 * {@code golf.GolfCourse}. A course with no tag is hand-built and behaves exactly as it always did.
 *
 * <p>The tag says which layout the blocks are: the slot, the generator and its {@code algo}, the
 * edition (its first day and cadence) and reroll, the seed, the half it stands in and the plan's
 * hash — enough to derive the whole plan again at every boot without storing a block of it. It
 * also carries what the boards and rewards need from that layout (the reference and star times)
 * and, for golf, what the boot check replays on the real blocks (each hole's chosen attempt and
 * witness line).
 *
 * <p>Because a run keeps its course as it was when it started, the run keeps the tag too: its
 * finish goes on its own layout's board, and the "still standing" rule (§3.4) asks about the
 * layout it started on.
 *
 * <p>The cadence is part of the tag, not only of the settings, so a layout keeps its own edition
 * key when the owner changes {@code games.fresh.cadence}: its boards, stars and rewards stay its
 * own, and the engine knows when it naturally ends.
 *
 * @param slot      the slot id ({@link Slots})
 * @param generator the planner's id ({@code parkour}, {@code rings}, {@code golf}, {@code boat})
 * @param algo      the planner's version when this was made
 * @param day       the first day (local epoch day) of the edition this layout is for
 * @param reroll    0, or the admin's reroll number in that edition
 * @param seed      the plan's seed (admins only; never published)
 * @param half      'A' or 'B': where the blocks stand
 * @param planHash  the plan's hash (12 hex)
 * @param refMs     the reference (expert) time; 0 for golf
 * @param goldMs    the 3-star time, fixed when the layout was made; 0 for golf
 * @param silverMs  the 2-star time; 0 for golf
 * @param attempts  golf: each hole's winning attempt number, to rebuild it without the solver
 * @param witness   golf: each hole's expert line, replayed at every build and boot
 * @param builtAt   when the blocks were verified (epoch ms)
 * @param cadence   the edition's length in days (1 to 28); a tag written before editions reads as 1
 * @param recall    for an archived course recalled into a Classics slot (GEN-SPEC-KEEP §3), which slot and
 *                  since when; {@code null} for a slot's own layout. Everything else is the ORIGINAL
 *                  edition's (slot, edition, seed, star times), so its board ({@code gfresh:}) and its
 *                  first-finish reward are the original's; {@code half} and {@code planHash} name the
 *                  blocks in the Classics slot's half.
 */
public record GenTag(String slot, String generator, int algo, long day, int reroll, long seed, char half,
                     String planHash, long refMs, long goldMs, long silverMs, List<Integer> attempts,
                     List<List<Putt>> witness, long builtAt, int cadence, Recall recall) {

    /**
     * Where and since when an archived edition stands in a Classics slot.
     *
     * @param slot the Classics slot holding it ({@code fresh_classic_parkour})
     * @param from when it was recalled (epoch ms)
     * @param day  the local epoch day of {@code from}: its own stars board is kept by it
     *             ({@link GenBoards#stars(GenTag)})
     */
    public record Recall(String slot, long from, long day) {

        public Recall {
            if (slot == null || slot.isBlank()) {
                throw new IllegalArgumentException("a recall needs its Classics slot");
            }
            slot = slot.trim();
        }
    }

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
        cadence = Edition.clampCadence(cadence);
    }

    /** A slot's own layout (not recalled): the shape before the Classics slots. */
    public GenTag(String slot, String generator, int algo, long day, int reroll, long seed, char half,
                  String planHash, long refMs, long goldMs, long silverMs, List<Integer> attempts,
                  List<List<Putt>> witness, long builtAt, int cadence) {
        this(slot, generator, algo, day, reroll, seed, half, planHash, refMs, goldMs, silverMs, attempts, witness,
                builtAt, cadence, null);
    }

    /** A tag of a daily edition (the shape before cadences): {@code day} is the course day. */
    public GenTag(String slot, String generator, int algo, long day, int reroll, long seed, char half,
                  String planHash, long refMs, long goldMs, long silverMs, List<Integer> attempts,
                  List<List<Putt>> witness, long builtAt) {
        this(slot, generator, algo, day, reroll, seed, half, planHash, refMs, goldMs, silverMs, attempts, witness,
                builtAt, Edition.DAILY);
    }

    /**
     * The name of this layout: {@code N:<index>}, or {@code N:<index>r<reroll>} after a reroll
     * ({@link Edition#editionKey(int, long, int)}); its board is {@code gfresh:<slot>:<this>}.
     */
    public String editionKey() {
        return Edition.editionKey(cadence, day, reroll);
    }

    /**
     * The edition without the reroll ({@code 7:38}): what a player's stars and the first-finish
     * reward are kept per, so a reroll gives a fresh board but no second reward.
     */
    public String edition() {
        return Edition.editionKey(cadence, day, 0);
    }

    /** The edition's first day's date. */
    public LocalDate date() {
        return LocalDate.ofEpochDay(day);
    }

    /** The day the next edition after this one starts on (this edition's natural end, at {@code rebuild_at}). */
    public long endDay() {
        return day + cadence;
    }

    /** Whether {@code other} is the same edition and reroll (maybe another layout of it, after a promote). */
    public boolean sameEdition(GenTag other) {
        return other != null && cadence == other.cadence && day == other.day && reroll == other.reroll;
    }

    /** The other half: where the next layout is built. */
    public char otherHalf() {
        return half == 'A' ? 'B' : 'A';
    }

    /**
     * Whether {@code other} names the same blocks: the same slot, generator, algo, half and plan,
     * in the same place (a slot's own half, or the same Classics slot). A pinned layout restamped
     * for a new edition (§3.2) is the same layout under a new edition; an archived course recalled
     * into a Classics slot is never the same layout as its slot's own, even with the same plan.
     */
    public boolean sameLayout(GenTag other) {
        return other != null && slot.equals(other.slot) && generator.equals(other.generator) && algo == other.algo
                && half == other.half && planHash.equals(other.planHash)
                && Objects.equals(recallSlot(), other.recallSlot());
    }

    /** Whether it is an archived course recalled into a Classics slot. */
    public boolean recalled() {
        return recall != null;
    }

    /** The Classics slot holding it, or {@code null} for a slot's own layout. */
    public String recallSlot() {
        return recall == null ? null : recall.slot();
    }

    /** The slot whose blocks these are: the Classics slot for a recalled course, else {@link #slot}. */
    public String holder() {
        return recall == null ? slot : recall.slot();
    }

    /** The same layout for another first day and reroll of the same cadence (a restamp: new boards, no blocks). */
    public GenTag withEdition(long newDay, int newReroll) {
        return withEdition(cadence, newDay, newReroll);
    }

    /** The same layout for another edition (a restamp: new boards, no blocks). */
    public GenTag withEdition(int newCadence, long newDay, int newReroll) {
        return new GenTag(slot, generator, algo, newDay, newReroll, seed, half, planHash, refMs, goldMs, silverMs,
                attempts, witness, builtAt, newCadence, recall);
    }

    /** The same tag with a new verified time. */
    public GenTag withBuiltAt(long at) {
        return new GenTag(slot, generator, algo, day, reroll, seed, half, planHash, refMs, goldMs, silverMs,
                attempts, witness, at, cadence, recall);
    }

    /** The same edition standing in a Classics slot ({@code null}: a slot's own layout). */
    public GenTag withRecall(Recall r) {
        return new GenTag(slot, generator, algo, day, reroll, seed, half, planHash, refMs, goldMs, silverMs,
                attempts, witness, builtAt, cadence, r);
    }
}
