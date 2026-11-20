package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;

/**
 * Nether Slots: the same kind of machine as Ore Slots (pick a stake, three reels, one line), with a
 * different feel: it pays rarely and big. Two the same pays nothing, three the same pays from 6 to 125 times what
 * was put in, and a spin pays about one time in 12 where Ore Slots pays one in 3.
 *
 * <p>Its own game: {@code games.nether_slots}, its own plays a day, ledger source and odds. For tokens anywhere the
 * games of chance are played, and for money in the Game Parlor, exactly as Ore Slots is.
 */
public final class NetherSlots extends SlotMachine {

    static final String ID = "nether_slots";
    static final String NAME = "Nether Slots";

    public static final GameSpec<SlotSettings> SPEC = new GameSpec<>(ID, GameKind.CHANCE,
            SlotSettings.keys(SlotTheme.NETHER, false), SlotSettings.nether(),
            (n, d) -> SlotSettings.parse(n, d, ID, NAME), NetherSlots::new, null);

    public NetherSlots(GameContext ctx) {
        super(ctx, SPEC, NAME, TokenService.Source.ARCADE_NETHER_SLOTS);
    }

    /** The live settings (read on every use, never cached across a reload). */
    public SlotSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
