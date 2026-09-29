package com.dierks.homecraft.games.gen.admin;

import com.dierks.homecraft.games.trial.Course;

import java.util.List;
import java.util.function.Consumer;

/**
 * What {@code /hcm games gen} asks of the running engine (GEN-SPEC §5.5). {@link GenAdmin} parses
 * the words, checks the slot and asks for {@code confirm} where a verb is destructive; the engine
 * does the rest and answers through {@code report}, now or when its job finishes (a preview, a
 * claim). Every line is {@code &}-coded. All on the main thread.
 */
public interface GenOps {

    /** Where {@code tp} goes: a world and a spot. */
    record Spot(String world, double x, double y, double z, float yaw) {
    }

    /** One line per slot ({@code slot} null) or the detail of one (§8.6). */
    List<String> status(String slot);

    /** "A restart is coming at 4:00 PM - try after it." while one is within {@code avoid_before_restart_minutes}, else {@code null}. */
    String restartSoon();

    /** A dry run: the plan for the current edition (or the {@code next} one, or a seed), no blocks. */
    void plan(String slot, String seedOrTomorrow, Consumer<String> report);

    /** Build into the idle half without flipping. */
    void preview(String slot, String seed, Consumer<String> report);

    /** The preview becomes the current edition's layout. */
    void promote(String slot, boolean confirm, Consumer<String> report);

    /** A new layout for the current edition (a fresh board). */
    void reroll(String slot, Consumer<String> report);

    /** Verify and heal the live half, same seed. */
    void rebuild(String slot, Consumer<String> report);

    /** The admin's on/off (kept in {@code hcm_meta}). */
    void enable(String slot, boolean on, Consumer<String> report);

    /** A tier or mix override, from the next build. */
    void tier(String slot, String tierOrMix, Consumer<String> report);

    /** Pin a seed ({@code live} = the live one) for {@code days} (0 = until unpinned). */
    void pin(String slot, String seedOrToday, int days, Consumer<String> report);

    void unpin(String slot, Consumer<String> report);

    /** Where {@code tp} takes an admin: the live course's start, or the idle half. {@code null} if nowhere. */
    Spot spot(String slot, boolean idle);

    /** Count what isn't air in a region's halves; with {@code confirm}, clear it and claim the region. */
    void claim(String slot, boolean confirm, Consumer<String> report);

    /** Empty both halves and switch the slot off (decommission, or before moving it). */
    void clear(String slot, Consumer<String> report);

    // ---- picking a good course (WP-ADM) --------------------------------------------------------------

    /**
     * A preview an admin can test-run: the course as its flip would make it, in the idle half
     * ({@code course}), or the line saying why there is none ({@code refusal}).
     */
    record PreviewRun(Course course, String refusal) {

        public static PreviewRun refused(String line) {
            return new PreviewRun(null, line);
        }
    }

    /**
     * What the course screen's admin tools show of a slot.
     *
     * @param on          switched on and nothing in the way
     * @param golf        a golf course (its preview is walked, not test-played)
     * @param cadence     the set's length in days now (7: "next week")
     * @param previewSeed the preview's seed, or {@code null} for no preview
     * @param previewNext the preview was made for the next set ({@code preview <course> next})
     * @param chosenSeed  the seed chosen for the next set, or {@code null}
     * @param chosenFor   that set as admins read it ("Mon 5 Oct-Sun 11 Oct"), or {@code null}
     * @param busy        a job for it is queued or running
     */
    record Tools(boolean on, boolean golf, int cadence, Long previewSeed, boolean previewNext, Long chosenSeed,
                 String chosenFor, boolean busy) {

        /** Whether a preview stands in the spare half. */
        public boolean preview() {
            return previewSeed != null;
        }
    }

    /** Build a candidate for the NEXT set into the idle half: its tier or mix, and {@code seed} (random when null). */
    default void previewNext(String slot, String seed, Consumer<String> report) {
        report.accept("&cThat isn't available.");
    }

    /** The preview's seed becomes the course of exactly the next set (a one-set pin). */
    default void choose(String slot, boolean confirm, Consumer<String> report) {
        report.accept("&cThat isn't available.");
    }

    /** Forget the next set's chosen seed: it gets its own new course. */
    default void unchoose(String slot, Consumer<String> report) {
        report.accept("&cThat isn't available.");
    }

    /** The preview as a course to test-run, or why not. */
    default PreviewRun previewRun(String slot) {
        return PreviewRun.refused("&cThat isn't available.");
    }

    /** The admin tools' view of a slot, or {@code null} for none (a Classics slot, or not running). */
    default Tools tools(String slot) {
        return null;
    }

    // ---- the archive: history, recall and keep (GEN-SPEC-KEEP) ------------------------------------------

    /** Past courses, newest first, 8 a page ({@code slot} null: every slot's). */
    default List<String> history(String slot, int page) {
        return List.of("&cThe archive isn't available.");
    }

    /** One past course's details, with its top 5. */
    default List<String> historyOf(String slot, GenArgs.Which which) {
        return List.of("&cThe archive isn't available.");
    }

    /**
     * Bring an archived course back into a Classics slot.
     *
     * @param classic the Classics slot or kind typed, or {@code null} (it follows the course's kind)
     * @param slot    the slot the course was made for, or {@code null} when {@code which} is a code
     * @param days    1-365, {@link GenArgs#DAYS_DEFAULT} ({@code classics.days}) or {@link GenArgs#FOREVER}
     */
    default void recall(String classic, String slot, GenArgs.Which which, int days, boolean confirm,
                        Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /** Close a Classics slot; its halves are cleared once nobody is on them. */
    default void unrecall(String classic, boolean confirm, Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /** Keep an archived course for good as a normal course in the next free plot. */
    default void keep(String slot, GenArgs.Which which, String id, String name, boolean freshBoard, boolean confirm,
                      Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /** The keep area's plots and the courses in them. */
    default List<String> plots() {
        return List.of("&cThe archive isn't available.");
    }

    /** Delete plot {@code n}'s course (and its boards), move anyone out, and clear the plot to air. */
    default void clearPlot(int n, boolean confirm, Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /**
     * Count what is in free plot {@code n}; with {@code confirm}, clear it for a kept course. Refused
     * while anything is in the way (keeping is off, a course is in or near it).
     */
    default void claimPlot(int n, boolean confirm, Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /** The plot numbers that hold a course (tab completion). */
    default List<Integer> usedPlots() {
        return List.of();
    }
}
