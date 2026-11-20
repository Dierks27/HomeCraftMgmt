package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;

/**
 * Farm Slots (the owner: "Lets make it the small-pot machine"): one set price ($1 a spin as shipped), money
 * only, with its own small pot that is taken fairly often (about one spin in 146 as shipped) and starts again at
 * $10.
 *
 * <p>Its own game: {@code games.farm_slots}, its own plays a day, ledger source and odds. See {@link PotSlots}.
 */
public final class FarmSlots extends PotSlots {

    static final String ID = "farm_slots";
    static final String NAME = "Farm Slots";

    public static final GameSpec<SlotSettings> SPEC = new GameSpec<>(ID, GameKind.CHANCE,
            SlotSettings.keys(SlotTheme.FARM, true), SlotSettings.farm(),
            (n, d) -> SlotSettings.parse(n, d, ID, NAME), FarmSlots::new, null);

    public FarmSlots(GameContext ctx) {
        super(ctx, SPEC, NAME, TokenService.Source.ARCADE_FARM_SLOTS);
    }
}
