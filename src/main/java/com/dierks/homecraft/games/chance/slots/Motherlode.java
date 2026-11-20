package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;

/**
 * The Motherlode: the big building pot. One set price ($5 a spin as shipped), money only; every spin feeds
 * a pot everyone can see, three Wilds takes the whole pot and the whole server is told, and it starts again at its
 * seed ($50 as shipped: the Wild line's 10 times the price).
 *
 * <p>Its own game: {@code games.motherlode}, its own plays a day, ledger source and odds. See {@link PotSlots}.
 */
public final class Motherlode extends PotSlots {

    static final String ID = "motherlode";
    static final String NAME = "The Motherlode";

    public static final GameSpec<SlotSettings> SPEC = new GameSpec<>(ID, GameKind.CHANCE,
            SlotSettings.keys(SlotTheme.MOTHERLODE, true), SlotSettings.motherlode(),
            (n, d) -> SlotSettings.parse(n, d, ID, NAME), Motherlode::new, null);

    public Motherlode(GameContext ctx) {
        super(ctx, SPEC, NAME, TokenService.Source.ARCADE_MOTHERLODE);
    }
}
