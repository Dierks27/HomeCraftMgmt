package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Slots;

import java.util.List;

/**
 * The Dropper's slots (EVENTS-DROPPER-SPEC §B.1.2), as {@link Slots.Def}s, until the wiring adds
 * them to {@link Slots} itself (WIRING.md): generator {@code dropper}, row game {@code trials}, kind
 * {@code dropper}, a 64 x 64 x 16 half, and the mix as the slot's difficulty ({@code plots} = 5
 * levels).
 *
 * <p>They live here only so the planner and its tests have real halves to plan into; nothing
 * registers them, so no slot, config key or play id exists for them yet.
 */
public final class DropperSlots {

    /** The generator id ({@link DropperPlanner#id()}). */
    public static final String DROPPER = "dropper";

    /** Easy Dropper: 3 easy levels, the path holes lit ("follow the light"). */
    public static final Slots.Def EASY = new Slots.Def("fresh_dropper_easy", DROPPER, Slots.GAME_TRIALS, "dropper",
            "Easy Dropper", "&a", DropperGeometry.SIZE_X, DropperGeometry.SIZE_Y, DropperGeometry.SIZE_Z,
            DropRules.MAX_LEVELS, false, "EEE", 5376, 160, 4096, 1, 2);
    /** Dropper: 5 levels that get harder. */
    public static final Slots.Def DROPPER_SLOT = new Slots.Def("fresh_dropper", DROPPER, Slots.GAME_TRIALS,
            "dropper", "Dropper", "&9", DropperGeometry.SIZE_X, DropperGeometry.SIZE_Y, DropperGeometry.SIZE_Z,
            DropRules.MAX_LEVELS, false, "EEMMH", 5376, 160, 4160, 2, 3);
    /** Classic Dropper: holds a recalled dropper of any mix. */
    public static final Slots.Def CLASSIC = new Slots.Def("fresh_classic_dropper", DROPPER, Slots.GAME_TRIALS,
            "dropper", "Classic Dropper", "&6", DropperGeometry.SIZE_X, DropperGeometry.SIZE_Y, DropperGeometry.SIZE_Z,
            DropRules.MAX_LEVELS, true, "EEMMH", 5376, 160, 4224, 0, 0);

    /** Every dropper slot, in display order. */
    public static final List<Slots.Def> ALL = List.of(EASY, DROPPER_SLOT, CLASSIC);

    private DropperSlots() {
    }
}
