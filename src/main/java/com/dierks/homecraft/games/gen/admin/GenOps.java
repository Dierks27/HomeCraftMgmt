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

    /** A dry run: the plan for today (or {@code tomorrow}, or a seed), no blocks. */
    void plan(String slot, String seedOrTomorrow, Consumer<String> report);

    /** Build into the idle half without flipping. */
    void preview(String slot, String seed, Consumer<String> report);

    /** The preview becomes today's layout. */
    void promote(String slot, boolean confirm, Consumer<String> report);

    /** A new layout for today (a fresh board). */
    void reroll(String slot, Consumer<String> report);

    /** Verify and heal the live half, same seed. */
    void rebuild(String slot, Consumer<String> report);

    /** The admin's on/off (kept in {@code hcm_meta}). */
    void enable(String slot, boolean on, Consumer<String> report);

    /** A tier or mix override, from the next build. */
    void tier(String slot, String tierOrMix, Consumer<String> report);

    /** Pin a seed ({@code today} = the live one) for {@code days} (0 = until unpinned). */
    void pin(String slot, String seedOrToday, int days, Consumer<String> report);

    void unpin(String slot, Consumer<String> report);

    /** Where {@code tp} takes an admin: the live course's start, or the idle half. {@code null} if nowhere. */
    Spot spot(String slot, boolean idle);

    /** Count what isn't air in a region's halves; with {@code confirm}, clear it and claim the region. */
    void claim(String slot, boolean confirm, Consumer<String> report);

    /** Empty both halves and switch the slot off (decommission, or before moving it). */
    void clear(String slot, Consumer<String> report);
}
