package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;

/**
 * Ore Slots (spec §5.3, R1.4): the reference game of chance.
 *
 * <p>Three reels of ores on one payline: three the same pays that ore's line, a Wild stands in
 * for any ore, two the same pays the small line, and Stone never pays. Stone's reel weight is the
 * one thing the engine solves, per stake, so the game gives back what {@code rtp} says and no
 * more — and the number players read is the one the engine computed, never the target.
 *
 * <p>A spin is one instant round ({@code ChanceRounds.play}): the gate, a seed, the engine's
 * outcome from that seed, and the tokens in and back in one transaction — all before the reels
 * move. The screen only shows what already happened. Everything a player
 * or admin reads about the odds, here and on the website, comes from the one engine in
 * {@link OreSlotsSettings#engine()}.
 *
 * <p>The machine itself is {@link SlotMachine}, shared with the themed machines (Nether Slots, The
 * Motherlode, Farm Slots). Ore Slots is that machine with the ores' theme ({@link SlotTheme#ORE}) and its own
 * settings; nothing about how it plays, reads or is configured changed.
 */
public final class OreSlots extends SlotMachine {

    /** Built: the game follows its config. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<OreSlotsSettings> SPEC = new GameSpec<>("ore_slots", GameKind.CHANCE,
            OreSlotsSettings.KEYS, OreSlotsSettings.defaults(), OreSlotsSettings::parse,
            OreSlots::new, null);

    public OreSlots(GameContext ctx) {
        super(ctx, SPEC, "Ore Slots", TokenService.Source.ARCADE_SLOTS);
    }

    @Override
    public boolean configEnabled() {
        return IMPLEMENTED && super.configEnabled();
    }

    /** This game's {@code /api/arcade} entry, from the engine in {@code s} (pure, for tests). */
    static void write(FeedWriter out, String id, String name, OreSlotsSettings s) {
        SlotMachine.write(out, id, name, s);
    }

    /** The live settings (read on every use, never cached across a reload). */
    public OreSlotsSettings settings() {
        return ctx.games().settings(SPEC);
    }
}
