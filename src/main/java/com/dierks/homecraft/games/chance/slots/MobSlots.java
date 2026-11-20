package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.FreeSpinsEngine.Round;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mob Slots: the slot machine with a bonus round (the owner asked for "several different 'Types' of slot
 * machine" and wanted them all). One line, like Ore Slots; three Creepers on the line pay their own line and give
 * free spins at the same stake.
 *
 * <p>The free spins are played at once, from the same seed, each exactly a spin of the same engine, and their lines
 * are added to the round: the spin and its free spins are ONE round with one payout, decided before a reel moves
 * ({@link FreeSpinsEngine}). The screen then shows the free spins land one after another; they were already paid.
 *
 * <p>Its own game: {@code games.mob_slots}, its own plays a day, ledger source and odds. For tokens anywhere the
 * games of chance are played, and for money in the Game Parlor, exactly as Ore Slots is. What players read it gives
 * back is the whole round's exact return, the free spins counted, never one spin's on its own.
 */
public final class MobSlots extends SlotMachine {

    static final String ID = "mob_slots";
    static final String NAME = "Mob Slots";

    public static final GameSpec<MobSlotsSettings> SPEC = new GameSpec<>(ID, GameKind.CHANCE, MobSlotsSettings.KEYS,
            MobSlotsSettings.defaults(), MobSlotsSettings::parse, MobSlots::new, null);

    public MobSlots(GameContext ctx) {
        super(ctx, SPEC, NAME, TokenService.Source.ARCADE_MOB_SLOTS);
    }

    /** The live settings (read on every use, never cached across a reload). */
    public MobSlotsSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The tile's key fact: "1-2 tokens", over the stakes that are offered. */
    @Override
    protected String keyFact(SlotTable s, SlotsEngine engine) {
        return SlotsCopy.stakeRange(s.offered());
    }

    @Override
    protected String giveBackWords(SlotTable s, SlotsEngine engine) {
        return RtpLimits.playerLine(lowest(settings()));
    }

    /** The lowest exact return of a whole round over the offered stakes ({@code NaN} with none). */
    static double lowest(MobSlotsSettings s) {
        double low = Double.NaN;
        for (int stake : s.offered()) {
            double v = s.round(stake).givesBack().value();
            if (Double.isNaN(low) || v < low) {
                low = v;
            }
        }
        return low;
    }

    /**
     * The spin for {@code dollars} in: found exactly as a token stake's is (the whole round at the target, inside
     * the band), under the Parlor's biggest payout shared between the spin and its free spins; {@code null} when
     * that stake's round can't land in the band.
     */
    @Override
    public SlotsEngine moneyEngine(SlotTable s, int dollars) {
        if (dollars <= 0 || !(s instanceof MobSlotsSettings m)) {
            return null;
        }
        MobSlotsSettings.Solved solved = m.moneySpin(dollars, ctx.games().parlor().rules());
        return solved.offered() ? solved.engine() : null;
    }

    /**
     * One round, played and settled before anything is shown.
     *
     * @param round the spin, its free spins and what the round paid
     * @param spin  the one-line engine every spin of it was drawn by (for the reels' show)
     */
    public record Played(Round round, SlotsEngine spin) {
    }

    /**
     * Play one round for {@code stake}: the gate and the round ({@code ChanceRounds.play}), in tokens or (in the Game
     * Parlor) in dollars. The free spins, if the Creepers land, are in the same round and the same one payout.
     * {@code null} when it was refused (the player has been told) and nothing moved.
     *
     * @param s the settings the screen was opened with
     */
    public Played spin(Player player, MobSlotsSettings s, int stake, boolean dollars) {
        SlotsEngine spin = stake <= 0 ? null : dollars ? moneyEngine(s, stake) : s.engine(stake);
        if (spin == null) {
            ctx.games().tell(player, Refusal.CLOSED);
            return null;
        }
        Round round = ctx.games().rounds().play(player, this, stake, new FreeSpinsEngine(spin, stake, s.freeSpins()),
                dollars);
        return round == null ? null : new Played(round, spin);
    }

    /** One line for everyone, then each stake's detail for admins: the whole round, and one spin apart. */
    @Override
    public List<String> oddsLines() {
        MobSlotsSettings s = settings();
        List<String> out = new ArrayList<>();
        out.add(SlotsCopy.oddsLine(name(), lowest(s), s.dailyLimit()));
        out.addAll(SlotsCopy.bonusOddsDetail(s, s.bank()));
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        write(out, id(), name(), settings());
    }

    /**
     * Its {@code /api/arcade} entry (pure, for tests): each stake's exact return of a whole round (the free spins
     * counted), the lines by the machine's own names (one set in multiples when every stake pays alike, else a set
     * for each stake in tokens), {@code freeSpins}, and {@code freeSpinsChance}: the exact chance, for each stake,
     * that a spin lands the three Creepers.
     */
    static void write(FeedWriter out, String id, String name, MobSlotsSettings s) {
        SlotBank bank = s.bank();
        if (!bank.playable()) {
            return;
        }
        Map<Integer, Double> back = new LinkedHashMap<>();
        Map<String, Object> chance = new LinkedHashMap<>();
        boolean alike = true;
        StakeOdds first = null;
        for (int stake : bank.stakes()) {
            FreeSpinsEngine round = s.round(stake);
            StakeOdds o = round.odds();
            back.put(stake, round.givesBack().value());
            chance.put(Integer.toString(stake), round.creepers().value());
            alike &= !o.capped() && (first == null || first.stone() == o.stone());
            first = first == null ? o : first;
        }
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (int stake : bank.stakes()) {
            for (LineOdds l : bank.odds(stake).lines()) {
                rows.add(alike ? new FeedWriter.PayRow(null, s.theme().combo(l.line()), l.multiple(), l.chance(),
                        null, null) : new FeedWriter.PayRow(stake, s.theme().combo(l.line()), l.payout(), l.chance(),
                        null, null));
            }
            if (alike) {
                break; // one set stands for every stake
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("freeSpins", s.freeSpins());
        if (s.freeSpins() > 0) {
            extra.put("freeSpinsChance", chance);
        }
        out.chance(id, name, bank.stakes(), back, s.dailyLimit(), rows, SlotsCopy.feedRules(s), extra);
    }
}
