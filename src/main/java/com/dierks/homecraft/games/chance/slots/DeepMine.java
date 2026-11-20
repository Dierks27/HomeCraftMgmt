package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine.Dig;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import com.dierks.homecraft.gui.games.chance.DeepMineMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deep Mine: the slot machine with three rows that all count (the owner asked for "several different 'Types' of
 * slot machine" and wanted them all). The screen shows three rows of three reels; the top, middle and bottom rows
 * are each a line, so one spin can pay more than once.
 *
 * <p>The player picks what goes on EACH row, and a spin always plays all three, so it costs three times that. Each
 * row is drawn by the one-line engine exactly as an Ore Slots spin is ({@link DeepMineEngine}), and the whole spin,
 * all three rows, is one round with one payout, decided from the round's one seed before a reel moves.
 *
 * <p>Its own game: {@code games.deep_mine}, its own plays a day, ledger source and odds. For tokens anywhere the
 * games of chance are played, and for money in the Game Parlor: there the chips build what goes on a row, and the
 * whole spin (three rows) is the amount the Parlor's rules are asked about.
 */
public final class DeepMine extends SlotMachine {

    static final String ID = "deep_mine";
    static final String NAME = "Deep Mine";

    public static final GameSpec<DeepMineSettings> SPEC = new GameSpec<>(ID, GameKind.CHANCE, DeepMineSettings.KEYS,
            DeepMineSettings.defaults(), DeepMineSettings::parse, DeepMine::new, null);

    /** The line solved for each dollar amount on a row asked for so far: solved once and kept. */
    private final Map<String, SlotsEngine> moneyLines = new ConcurrentHashMap<>();

    public DeepMine(GameContext ctx) {
        super(ctx, SPEC, NAME, TokenService.Source.ARCADE_DEEP_MINE);
    }

    /** The live settings (read on every use, never cached across a reload). */
    public DeepMineSettings settings() {
        return ctx.games().settings(SPEC);
    }

    @Override
    public List<String> rules() {
        return SlotsCopy.deepRules(settings());
    }

    /** The tile's key fact: what a whole spin costs, "3-15 tokens". */
    @Override
    protected String keyFact(SlotTable s, SlotsEngine engine) {
        List<Integer> totals = new ArrayList<>();
        for (int perLine : s.offered()) {
            totals.add(DeepMineEngine.total(perLine));
        }
        return SlotsCopy.stakeRange(totals);
    }

    @Override
    protected String giveBackWords(SlotTable s, SlotsEngine engine) {
        return RtpLimits.playerLine(lowest(settings()));
    }

    /** The lowest exact return over the offered stakes ({@code NaN} with none): a whole spin's is its line's. */
    static double lowest(DeepMineSettings s) {
        double low = Double.NaN;
        for (int perLine : s.offered()) {
            double v = s.bank().odds(perLine).rtp();
            if (Double.isNaN(low) || v < low) {
                low = v;
            }
        }
        return low;
    }

    @Override
    public void open(Player player, Runnable back) {
        DeepMineSettings s = settings();
        if (!s.bank().playable()) {
            player.sendMessage(Text.of("&c" + name() + " is closed right now."));
            return;
        }
        new DeepMineMenu(ctx.plugin(), this, player, back, s).open(player);
    }

    /**
     * The line for {@code perLine} dollars on a row: solved exactly as a token row is, under a third of what the
     * Parlor lets the whole spin pay; {@code null} when that row can't land in the band.
     */
    @Override
    public SlotsEngine moneyEngine(SlotTable s, int perLine) {
        if (perLine <= 0 || !(s instanceof DeepMineSettings d)) {
            return null;
        }
        Parlor.Rules rules = ctx.games().parlor().rules();
        String key = perLine + ":" + rules.capFor(DeepMineEngine.total(perLine)) + ":" + d.rtp() + ":"
                + d.engineReels() + ":" + d.enginePays();
        SlotsEngine line = moneyLines.computeIfAbsent(key, k -> d.moneyLine(perLine, rules));
        return line.odds(perLine) == null ? null : line;
    }

    /**
     * Why three rows of {@code perLine} dollars can't go now, for the Spin tile; {@code null} when nothing stops it.
     * The Parlor is asked about the whole spin, three rows, as an amount built from the chips.
     */
    public String rowsBlocker(Player player, int perLine) {
        int most = ctx.games().parlor().rules().maxStake();
        if (DeepMineEngine.total(perLine) > most) {
            return SlotsCopy.deepOverMost(perLine, most);
        }
        return moneyBlocker(player, DeepMineEngine.total(perLine));
    }

    /**
     * One spin, played and settled before anything is shown.
     *
     * @param dig  the three rows and what the round paid
     * @param line the one-line engine the rows were drawn by (for the reels' show)
     */
    public record Played(Dig dig, SlotsEngine line) {
    }

    /**
     * Play one spin with {@code perLine} on each row: the gate and the round ({@code ChanceRounds.play}) for the
     * whole spin, three times {@code perLine}, in tokens or (in the Game Parlor) in dollars. {@code null} when the
     * spin was refused (the player has been told) and nothing moved.
     *
     * @param s the settings the screen was opened with
     */
    public Played spin(Player player, DeepMineSettings s, int perLine, boolean dollars) {
        SlotsEngine line = perLine <= 0 ? null : dollars ? moneyEngine(s, perLine) : s.engine(perLine);
        if (line == null) {
            ctx.games().tell(player, Refusal.CLOSED);
            return null;
        }
        DeepMineEngine engine = new DeepMineEngine(line, perLine);
        Dig dig = ctx.games().rounds().play(player, this, engine.total(), engine, dollars);
        return dig == null ? null : new Played(dig, line);
    }

    /** One line for everyone, then each stake's detail for admins. */
    @Override
    public List<String> oddsLines() {
        DeepMineSettings s = settings();
        List<String> out = new ArrayList<>();
        out.add(SlotsCopy.oddsLine(name(), lowest(s), s.dailyLimit()));
        out.addAll(SlotsCopy.deepOddsDetail(s, s.bank()));
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        write(out, id(), name(), settings());
    }

    /**
     * Its {@code /api/arcade} entry (pure, for tests). The stakes are what a whole spin costs, each with its exact
     * return; the paytable is what ONE row pays at that spin's stake, in tokens; {@code lines} is 3,
     * {@code perLine} what each of those spins puts on a row, and {@code anyRowPays} the exact chance, for each
     * spin, that at least one of its rows pays.
     */
    static void write(FeedWriter out, String id, String name, DeepMineSettings s) {
        SlotBank bank = s.bank();
        if (!bank.playable()) {
            return;
        }
        List<Integer> totals = new ArrayList<>();
        Map<Integer, Double> back = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        Map<String, Object> anyRow = new LinkedHashMap<>();
        for (int perLine : bank.stakes()) {
            DeepMineEngine spin = new DeepMineEngine(bank.engine(perLine), perLine);
            StakeOdds o = spin.odds();
            totals.add(spin.total());
            back.put(spin.total(), o.rtp());
            for (LineOdds l : o.lines()) {
                rows.add(new FeedWriter.PayRow(spin.total(), s.theme().combo(l.line()), l.payout(), l.chance(), null,
                        null));
            }
            anyRow.put(Integer.toString(spin.total()), spin.anyRowPays().value());
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lines", DeepMineEngine.LINES);
        extra.put("perLine", bank.stakes());
        extra.put("anyRowPays", anyRow);
        out.chance(id, name, totals, back, s.dailyLimit(), rows, SlotsCopy.deepFeedRules(s), extra);
    }
}
