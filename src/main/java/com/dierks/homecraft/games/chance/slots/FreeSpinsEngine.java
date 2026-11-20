package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One Mob Slots round: a spin on the one line, and, when its three reels all land the Creeper, the free spins
 * that follow. The whole round is decided from the round's one seed before anything is shown.
 *
 * <p><b>The Creeper is the engine's Wild.</b> It stands in for any drop on a line, and three of them on the line pay
 * the Wild line and give {@code freeSpins} free spins at the same stake. Each free spin is exactly a spin of the same
 * one-line {@link SlotsEngine} (the round's seed gives every spin a seed of its own), its line is paid like any
 * spin's, and a free spin that lands the three Creepers gives NO more free spins.
 *
 * <p><b>The return.</b> With {@code L} the exact return of one spin and {@code p} the exact chance of three Creepers
 * (the Creeper's weight over the reel's total, cubed), a round gives back {@code L × (1 + p × N)}
 * ({@link SlotOdds#withFreeSpins}); the settings find the engine whose WHOLE round lands at the target.
 * <b>The cap.</b> The engine is solved with a cap of the round's cap divided by {@code N + 1} ({@link #spinCap}), so
 * the first spin and every free spin together can never pay more than the round is held to: by construction, never
 * by clamping the sum.
 */
public final class FreeSpinsEngine implements ChanceRounds.InstantEngine<FreeSpinsEngine.Round> {

    /** The most free spins three Creepers may give ({@code free_spins}: 0-10). */
    public static final int MAX_FREE_SPINS = 10;
    /** The engine version stored with every round. */
    public static final int VERSION = 1;

    private final SlotsEngine spin;
    private final int stake;
    private final int freeSpins;

    /**
     * @param spin      the one-line engine every spin of the round is drawn by, solved for {@code stake} under
     *                  {@link #spinCap}
     * @param stake     what the round puts in
     * @param freeSpins the free spins three Creepers give (0: a plain machine)
     * @throws IllegalArgumentException when the engine does not take that stake
     */
    public FreeSpinsEngine(SlotsEngine spin, int stake, int freeSpins) {
        if (spin == null || spin.odds(stake) == null) {
            throw new IllegalArgumentException("Mob Slots does not take " + stake);
        }
        this.spin = spin;
        this.stake = stake;
        this.freeSpins = Math.max(0, Math.min(MAX_FREE_SPINS, freeSpins));
    }

    /**
     * The most one spin may pay when the whole round may pay {@code roundCap}: its share when the first spin and
     * all {@code freeSpins} free spins pay their most, rounded down.
     */
    public static int spinCap(int roundCap, int freeSpins) {
        return roundCap / (Math.max(0, freeSpins) + 1);
    }

    /** Whether a spin's three reels all landed the Creeper: what gives the free spins. */
    public static boolean threeCreepers(Spin spin) {
        for (Symbol s : spin.reels()) {
            if (s != Symbol.WILD) {
                return false;
            }
        }
        return true;
    }

    /** The one-line engine every spin of the round is drawn by. */
    public SlotsEngine spin() {
        return spin;
    }

    /** The free spins three Creepers give. */
    public int freeSpins() {
        return freeSpins;
    }

    /** One spin's solved odds. */
    public StakeOdds odds() {
        return spin.odds(stake);
    }

    /** What a whole round gives back per token in, exactly, the free spins counted: {@code L × (1 + p × N)}. */
    public Frac givesBack() {
        return SlotOdds.withFreeSpins(spin, stake, freeSpins);
    }

    /** The exact chance a round's first spin lands three Creepers. */
    public Frac creepers() {
        return SlotOdds.threeWilds(spin, stake);
    }

    /** The most a whole round can pay: the first spin and every free spin paying the top line. */
    public int most() {
        int top = 0;
        for (SlotsEngine.LineOdds l : odds().lines()) {
            top = Math.max(top, l.payout());
        }
        // free spins only ever follow three Creepers, so without that line there is one spin to pay
        return creepers().num().signum() > 0 ? top * (freeSpins + 1) : top;
    }

    /**
     * The round for this seed: the seed gives the first spin its own seed, and, only when that spin lands three
     * Creepers, one more for each free spin; each spin is the one-line engine's spin for its seed. Same seed and
     * stake, same round.
     *
     * @throws IllegalArgumentException for any stake but this engine's
     */
    @Override
    public Round decide(long seed, int stake) {
        if (stake != this.stake) {
            throw new IllegalArgumentException("This Mob Slots round is for " + this.stake + ", not " + stake);
        }
        SplittableRandom seeds = new SplittableRandom(seed);
        Spin base = spin.decide(seeds.nextLong(), stake);
        List<Spin> free = new ArrayList<>();
        int payout = base.payout();
        if (freeSpins > 0 && threeCreepers(base)) {
            for (int i = 0; i < freeSpins; i++) {
                Spin next = spin.decide(seeds.nextLong(), stake);
                free.add(next);
                payout += next.payout();
            }
        }
        return new Round(stake, base, free, payout);
    }

    @Override
    public int payout(Round round) {
        return round.payout();
    }

    /**
     * What the round stores: {@code v=1;stake=2;free=5;spin=wild,wild,wild:wild:40;free1=coal,wild,stone:two:2;...;
     * payout=57;stone=1668;total=5668} ({@code free}: how many free spins it had; the symbols are the engine's own
     * keys, so the Creeper reads {@code wild}).
     */
    @Override
    public String data(Round round) {
        StringBuilder sb = new StringBuilder("v=").append(VERSION).append(";stake=").append(round.stake())
                .append(";free=").append(round.free().size())
                .append(";spin=").append(DeepMineEngine.row(round.base()));
        for (int i = 0; i < round.free().size(); i++) {
            sb.append(";free").append(i + 1).append('=').append(DeepMineEngine.row(round.free().get(i)));
        }
        sb.append(";payout=").append(round.payout());
        StakeOdds o = spin.odds(round.stake());
        if (o != null) {
            sb.append(";stone=").append(o.stone()).append(";total=").append(o.total());
        }
        return sb.toString();
    }

    /**
     * A stored round read back: every spin exactly as it was decided (to show it again, or to check it).
     *
     * @throws IllegalArgumentException when {@code data} is not a Mob Slots round
     */
    public static Round parse(String data) {
        Map<String, String> f = DeepMineEngine.fields(data);
        try {
            int stake = Integer.parseInt(f.get("stake"));
            int n = Integer.parseInt(f.get("free"));
            Spin base = DeepMineEngine.row(f.get("spin"), stake);
            List<Spin> free = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                free.add(DeepMineEngine.row(f.get("free" + i), stake));
            }
            return new Round(stake, base, free, Integer.parseInt(f.get("payout")));
        } catch (NullPointerException | NumberFormatException e) {
            throw new IllegalArgumentException("not a Mob Slots round: " + data, e);
        }
    }

    /**
     * One round.
     *
     * @param stake  what went in (the free spins cost nothing more)
     * @param base   the spin the stake bought
     * @param free   the free spins, in order: empty unless {@code base} landed three Creepers
     * @param payout what the whole round gives back: the first spin and every free spin added up
     */
    public record Round(int stake, Spin base, List<Spin> free, int payout) {

        public Round {
            free = List.copyOf(free);
        }

        /** Whether the round had free spins. */
        public boolean bonus() {
            return !free.isEmpty();
        }

        /** More came back than went in: the only result ever called a win. */
        public boolean win() {
            return payout > stake;
        }

        /** What the round had given back once {@code spins} of its free spins had landed (0: the first spin only). */
        public int backAfter(int spins) {
            int back = base.payout();
            for (int i = 0; i < spins && i < free.size(); i++) {
                back += free.get(i).payout();
            }
            return back;
        }
    }
}
