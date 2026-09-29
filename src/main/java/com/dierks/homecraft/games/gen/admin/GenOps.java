package com.dierks.homecraft.games.gen.admin;

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

    /** Count what is in plot {@code n}; with {@code confirm}, clear it and make it Fresh Courses' to build in. */
    default void claimPlot(int n, boolean confirm, Consumer<String> report) {
        report.accept("&cThe archive isn't available.");
    }

    /** The plot numbers that hold a course (tab completion). */
    default List<Integer> usedPlots() {
        return List.of();
    }
}
