package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.Slots;

import java.util.List;

/**
 * The Dropper's slots (EVENTS-DROPPER-SPEC §B.1.2) under the names this package used before the
 * wiring (WP-D) moved them into {@link Slots}: generator {@code dropper}, row game {@code trials},
 * kind {@code dropper}, a 64 x 64 x 16 half, and the mix as the slot's difficulty ({@code plots} = 5
 * levels). Every constant here IS the {@link Slots} one, so there is one definition.
 */
public final class DropperSlots {

    /** The generator id ({@link DropperPlanner#id()}): {@link Slots#DROPPER}. */
    public static final String DROPPER = Slots.DROPPER;

    /** Easy Dropper: 3 easy levels, the path holes lit ("follow the light"). */
    public static final Slots.Def EASY = Slots.EASY_DROPPER;
    /** Dropper: 5 levels that get harder. */
    public static final Slots.Def DROPPER_SLOT = Slots.FRESH_DROPPER;
    /** Classic Dropper: holds a recalled dropper of any mix. */
    public static final Slots.Def CLASSIC = Slots.CLASSIC_DROPPER;

    /** Every dropper slot, in display order. */
    public static final List<Slots.Def> ALL = List.of(EASY, DROPPER_SLOT, CLASSIC);

    private DropperSlots() {
    }
}
