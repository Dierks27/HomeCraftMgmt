package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pot machine (the owner: "can we make a slot machine ... where you can have a building pot"): a slot
 * machine with one set price, played for money only, whose every spin feeds a pot everyone can see. Three Wilds
 * takes the whole pot, and it starts again at its seed. The Motherlode is the big one, Farm Slots the small one.
 *
 * <p><b>Money only.</b> A spin is the machine's price in dollars through {@code ChanceRounds.play}, so every step
 * of the play gate runs (the games-of-chance permission, Take a break, the cooldown, the machine's plays a day, and
 * the Parlor's money steps: money play on, {@code hcm.games.money}, standing in the Parlor, the day's money limit,
 * the balance). It never takes tokens ({@link #setPrice()}), and outside the Parlor it only shows itself.
 *
 * <p><b>The return.</b> {@code feed_percent} of every spin's price goes into the pot, and the lines are solved for
 * {@code rtp} less that share, so the lines and the pot together give back {@code rtp}. The three-Wilds line's own
 * pay IS the pot's seed: whoever lands it gets that through the round like any line, and what the spins added on
 * top ({@code Pots}). The number players read is the lines' exact return plus the pot's share.
 */
public abstract class PotSlots extends SlotMachine {

    private final GameSpec<SlotSettings> spec;

    protected PotSlots(GameContext ctx, GameSpec<SlotSettings> spec, String name, TokenService.Source source) {
        super(ctx, spec, name, source);
        this.spec = spec;
    }

    /** The live settings (read on every use, never cached across a reload). */
    public SlotSettings settings() {
        return ctx.games().settings(spec);
    }

    /**
     * The one price of a spin, in dollars: this machine is played for money only. Never 0, whatever its settings
     * hold (with no price left it is closed, and reads as its shipped price), so the gate never takes it for a game
     * that takes tokens.
     */
    @Override
    public int setPrice() {
        int price = settings().price();
        return price > 0 ? price : Math.max(1, spec.defaults().price());
    }

    /**
     * The engine a spin is decided by: the lines solved for the price under the Parlor's biggest payout;
     * {@code null} when the price can't land in the band there.
     */
    public SlotsEngine engine(SlotSettings s) {
        return moneyEngine(s, s.price());
    }

    /** The engine everything published reads: the one a spin is decided by, or the settings' own without it. */
    private SlotsEngine published(SlotSettings s) {
        SlotsEngine live = engine(s);
        return live != null ? live : s.engine();
    }

    /** What the pot starts at, in cents: what the three-Wilds line pays on its own at the price. */
    public long seedCents(SlotSettings s) {
        return seedCents(published(s), s.price());
    }

    private static long seedCents(SlotsEngine engine, int price) {
        return engine.payout(price, Symbol.WILD, Symbol.WILD, Symbol.WILD) * 100L;
    }

    /** What one spin adds to the pot, in cents: {@code feed_percent} of the price. */
    public long feedCents(SlotSettings s) {
        return (long) s.price() * s.feedPercent();
    }

    /** What the pot holds now, in cents: its seed and what the spins have added. */
    public long potCents() {
        return potCents(settings());
    }

    private long potCents(SlotSettings s) {
        return seedCents(s) + ctx.games().parlor().pots().added(id());
    }

    /**
     * One spin for {@code player}, played and settled before anything is shown.
     *
     * @param spin     the reels, the line and what the round paid (in dollars)
     * @param engine   the engine it was decided by (for the reels' show)
     * @param potCents the whole pot the player took, in cents (the line's own pay and what the spins had added);
     *                 0 for any spin that is not three Wilds
     */
    public record PotSpin(Spin spin, SlotsEngine engine, long potCents) {

        /** Whether this spin took the pot. */
        public boolean tookPot() {
            return potCents > 0;
        }
    }

    /**
     * Play one spin: the gate and the round ({@code ChanceRounds.play}, for the price, in dollars), then the pot:
     * the spin's share goes in, and three Wilds takes it all (the seed came back through the round; the added part
     * is paid on top, once). {@code null} when the spin was refused (the player has been told) and nothing moved.
     */
    public PotSpin spin(Player player) {
        SlotSettings s = settings();
        int price = s.price();
        SlotsEngine engine = engine(s);
        if (price <= 0 || engine == null) {
            ctx.games().tell(player, Refusal.CLOSED);
            return null;
        }
        Spin spin = ctx.games().rounds().play(player, this, price, engine, true);
        if (spin == null) {
            return null;
        }
        long seed = seedCents(engine, price);
        ctx.games().parlor().pots().feed(id(), feedCents(s), seed);
        if (!threeWilds(spin)) {
            return new PotSpin(spin, engine, 0);
        }
        long added = ctx.games().parlor().pots().take(player, this, seed);
        return new PotSpin(spin, engine, seed + added);
    }

    private static boolean threeWilds(Spin spin) {
        for (Symbol s : spin.reels()) {
            if (s != Symbol.WILD) {
                return false;
            }
        }
        return spin.payout() > 0;
    }

    /**
     * Why {@code player} can't spin now, for the Spin tile (a plain reason, never a wall of rules); {@code null}
     * when nothing stops them but the play gate's own last word.
     */
    public String blocker(Player player) {
        SlotSettings s = settings();
        Refusal r = ctx.games().parlor().allowed(player);
        if (r == Parlor.MONEY_NOT_HERE) {
            return "Only in the Game Parlor";
        }
        if (r == Parlor.MONEY_NOT_YOURS) {
            return "Playing for money isn't open to you";
        }
        if (r != null) {
            return "Playing for money is closed right now";
        }
        if (engine(s) == null) {
            return name() + " can't be played for " + Parlor.dollars(s.price());
        }
        return moneyBlocker(player, s.price());
    }

    @Override
    public String machineLabel() {
        return SlotsCopy.potLabel(name(), potCents());
    }

    @Override
    protected String potOdds(SlotTable s, SlotsEngine engine) {
        LineOdds line = potLine(engine, settings().price());
        return line == null ? null : "&7Takes the pot about 1 in " + SlotsCopy.grouped(line.oneIn()) + " spins.";
    }

    @Override
    protected String keyFact(SlotTable s, SlotsEngine engine) {
        return SlotsCopy.potFact(settings().price(), potCents());
    }

    @Override
    protected String giveBackWords(SlotTable s, SlotsEngine engine) {
        return RtpLimits.playerLine(lowest(s, engine)).replace("tokens", "dollars");
    }

    /** The lowest "gives back" over the engine's stakes: the lines and the pot's share. */
    private static double lowest(SlotTable s, SlotsEngine engine) {
        double low = Double.NaN;
        for (StakeOdds o : engine.odds().values()) {
            double v = s.givesBack(o);
            if (Double.isNaN(low) || v < low) {
                low = v;
            }
        }
        return low;
    }

    /** One line for everyone, then the detail for admins: the lines and the pot's share apart, and the pot now. */
    @Override
    public List<String> oddsLines() {
        SlotSettings s = settings();
        SlotsEngine engine = published(s);
        List<String> out = new ArrayList<>();
        out.add(SlotsCopy.potOddsLine(name(), s, engine));
        out.addAll(SlotsCopy.potOddsDetail(s, engine, potCents(s), seedCents(engine, s.price())));
        return out;
    }

    /**
     * Its {@code /api/arcade} entry: the price as its one stake, what it gives back with the pot's share counted,
     * the lines by their theme's names, and the pot ({@code money}, {@code feedPercent}, {@code potSeed} and
     * {@code pot}, in dollars).
     */
    @Override
    public void feed(FeedWriter out) {
        SlotSettings s = settings();
        SlotsEngine engine = published(s);
        if (!engine.playable()) {
            return;
        }
        Map<Integer, Double> back = new LinkedHashMap<>();
        for (StakeOdds o : engine.odds().values()) {
            back.put(o.stake(), s.givesBack(o));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("money", true);
        extra.put("feedPercent", s.feedPercent());
        extra.put("potSeed", seedCents(engine, s.price()) / 100.0);
        extra.put("pot", potCents(s) / 100.0);
        out.chance(id(), name(), engine.stakes(), back, s.dailyLimit(), engine.feedRows(s.theme()),
                SlotsCopy.feedRules(s), extra);
    }

    /** The three-Wilds line at the price, for the paytable; {@code null} when it can't come up. */
    public static LineOdds potLine(SlotsEngine engine, int price) {
        StakeOdds odds = engine == null ? null : engine.odds(price);
        return odds == null ? null : odds.line(Line.THREE_WILDS);
    }
}
