package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.chance.TwentyOneMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Twenty-One (spec §5.4, R1.5, R1.10), shown to players as "Twenty-One" and reachable as
 * {@code /hcm play blackjack} too.
 *
 * <p>Beat the Arcade's hand without going over 21: Hit, Stand or Double, the Arcade draws to 17.
 * What a win adds is solved per stake ({@link TwentyOneSettings}) so that even the best possible
 * play gives back no more than {@code rtp} of every 100 tokens put in — and that computed number,
 * never the config target, is what the screen, {@code /hcm arcade odds} and the website show.
 *
 * <p><b>A hand is a crash-safe round</b> ({@link ChanceRounds}): Deal puts the tokens in and opens
 * the round with every payout parameter in its data; each Hit is recorded before its card is shown;
 * a Double re-runs the limits for the extra tokens at the moment they go in; the end settles once.
 * Closing the screen leaves the hand open and opening the game again resumes it; a player who
 * leaves (or a server that stops) has it finished by the exit rule — stand
 * ({@link TwentyOneHand#settleOnExit}), from the round's data alone.
 */
public final class TwentyOne implements Game {

    /** Built: the game follows its config. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<TwentyOneSettings> SPEC = new GameSpec<>(TwentyOneSettings.ID, GameKind.CHANCE,
            TwentyOneSettings.KEYS, TwentyOneSettings.defaults(), TwentyOneSettings::parse,
            TwentyOne::new, TwentyOneHand::settleOnExit);

    private final GameContext ctx;

    /** A new session's shoe: a player away from Twenty-One this long gets a freshly shuffled one (0.45.0). */
    static final long SESSION_GAP_MS = 30L * 60_000L;

    /**
     * Each player's shoe this session: its seed, where its next card is, when it was last dealt from, and whether a hand
     * dealt from it is still out ({@link #dealt} hasn't said where it stopped).
     */
    private record ShoeState(long seed, int next, long at, boolean inPlay) {
    }

    private final Map<UUID, ShoeState> shoes = new java.util.concurrent.ConcurrentHashMap<>();

    public TwentyOne(GameContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The shoe {@code player}'s next hand is dealt from (0.45.0, the owner: "4 Deck Shoe - But reshuffle for a new
     * session"): the one they are playing through, carrying on where their last hand stopped; a freshly shuffled one at
     * the first hand of a session (none yet, a restart, or {@link #SESSION_GAP_MS} since their last) or at the cut card.
     *
     * <p><b>Never the same cards twice.</b> A hand that ends anywhere but on its own screen (the player left and the
     * exit rule stood for them, the ten-minute sweep, a hand finished elsewhere) never tells the shoe where it
     * stopped. Dealing the next hand from the same place would show the same cards again, to a player who has seen
     * them: so a shoe with a hand still out is thrown away and a fresh one shuffled.
     */
    public TwentyOneHand.Shoe shoeFor(UUID player) {
        return shoeFor(player, System.currentTimeMillis(), java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    /** {@link #shoeFor(UUID)} at {@code now}, with {@code fresh} as the seed of a new shoe (a test's own). */
    TwentyOneHand.Shoe shoeFor(UUID player, long now, long fresh) {
        ShoeState s = shoes.get(player);
        if (s == null || s.inPlay() || now - s.at() > SESSION_GAP_MS || s.next() >= TwentyOneHand.CUT_CARD) {
            s = new ShoeState(fresh, 0, now, false);
        }
        shoes.put(player, new ShoeState(s.seed(), s.next(), now, true));
        return new TwentyOneHand.Shoe(s.seed(), s.next());
    }

    /** A hand from {@code player}'s shoe ended on its own screen: the shoe's next card is where it stopped. */
    public void dealt(UUID player, TwentyOneHand hand) {
        if (hand == null || hand.shoe() == null) {
            return;
        }
        shoes.put(player, new ShoeState(hand.shoe().seed(), Math.min(hand.nextAt(), TwentyOneHand.SHOE_CARDS),
                System.currentTimeMillis(), false));
    }

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return "Twenty-One";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_TWENTY_ONE;
    }

    @Override
    public List<String> aliases() {
        return List.of("blackjack");
    }

    @Override
    public boolean configEnabled() {
        TwentyOneSettings s = settings();
        return s.enabled() && !s.odds().isEmpty() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Beat the Arcade's hand without going over 21.",
                "Hit for a card, Stand to stop.",
                "Double: put the same in again for one last card.",
                "The Arcade draws to 17 and stops on any 17.",
                "Your first two cards making 21 is Twenty-One! and pays more.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        TwentyOneSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        if (!s.odds().isEmpty()) {
            lore.add("&7With the best play it " + RtpLimits.playerLine(s.lowestRtp()) + ".");
        }
        lore.add("&e" + s.dailyLimit() + " hands a day");
        return Menus.icon(Material.PAPER, "&aTwenty-One &7- " + stakeRange(s) + " tokens in",
                lore.toArray(new String[0]));
    }

    /** 0.47.0: the Game Parlor's table, where up to four play one shoe together. */
    private final TwentyOneTable table = new TwentyOneTable(this);

    /** The Game Parlor's Twenty-One table. */
    public TwentyOneTable table() {
        return table;
    }

    /**
     * Resume the player's open hand, or (gate steps 0-4) open the table. In the Game Parlor (0.47.0) that is the
     * table everyone there shares; anywhere else it is a hand alone, as before.
     */
    @Override
    public void open(Player player, Runnable back) {
        GamesService games = ctx.games();
        if (games.inParlor(player.getUniqueId())) {
            Refusal refusal = games.canOpen(player, this);
            if (refusal != null) {
                games.tell(player, refusal);
                return;
            }
            new com.dierks.homecraft.gui.games.chance.TwentyOneTableMenu(ctx.plugin(), this, player, back).open(player);
            return;
        }
        ChanceRounds.Round round = games.rounds().openRound(player.getUniqueId(), id());
        if (round != null && TableRound.isTable(round.data())) {
            games.rounds().finishNow(round); // a table hand met away from the table: it stands, and is paid
            round = null;
        }
        if (round == null) {
            Refusal refusal = games.canOpen(player, this);
            if (refusal != null) {
                games.tell(player, refusal);
                return;
            }
        }
        new TwentyOneMenu(ctx.plugin(), this, player, back, round).open(player);
    }

    @Override
    public List<String> oddsLines() {
        return oddsLines(settings());
    }

    @Override
    public void feed(FeedWriter out) {
        feed(out, settings());
    }

    /**
     * {@code /hcm arcade odds}: the first line is the player's (the lowest stake's value, floored
     * to a whole number), then one line per stake for admins (one decimal, and what it pays).
     */
    static List<String> oddsLines(TwentyOneSettings s) {
        if (s.odds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("&6Twenty-One &7— the best play " + RtpLimits.playerLine(s.lowestRtp()) + " · " + s.dailyLimit()
                + " hands a day");
        for (TwentyOneSettings.Odds o : s.odds()) {
            TwentyOneMath.Payouts p = o.payouts();
            out.add("&7  " + o.stake() + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "% &7(win " + p.win()
                    + ", Twenty-One! " + p.twentyOne() + (p.canDouble() ? ", double win " + p.doubleWin() : "")
                    + ")");
        }
        return out;
    }

    /** Its {@code /api/arcade} entry: no paytable, the rules and what each stake pays. */
    static void feed(FeedWriter out, TwentyOneSettings s) {
        if (s.odds().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> payouts = new LinkedHashMap<>();
        for (TwentyOneSettings.Odds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
            TwentyOneMath.Payouts p = o.payouts();
            Map<String, Integer> row = new LinkedHashMap<>();
            row.put("win", p.win());
            row.put("twentyOne", p.twentyOne());
            if (p.canDouble()) {
                row.put("doubleWin", p.doubleWin());
            }
            payouts.put(Integer.toString(o.stake()), row);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("payouts", payouts);
        out.chance(SPEC.id(), "Twenty-One", s.playable(), rtp, s.dailyLimit(), List.of(),
                "Beat the Arcade's hand without going over 21. The Arcade draws to 17 and stops on any 17;"
                        + " it checks for Twenty-One when it shows an Ace or a ten. Double on your first two cards."
                        + " No split.", extra);
    }

    private static String stakeRange(TwentyOneSettings s) {
        List<Integer> p = s.playable();
        if (p.isEmpty()) {
            return "no";
        }
        return p.size() == 1 ? Integer.toString(p.get(0)) : p.get(0) + " to " + p.get(p.size() - 1);
    }

    /** What a hand pays at each dollar stake asked for so far (0.46.0), by stake, biggest payout and the settings' own numbers. */
    private final Map<String, java.util.Optional<TwentyOneSettings.Odds>> moneyOdds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 0.46.0, the Game Parlor's money play: what a hand pays with {@code dollars} in, solved exactly as a token stake
     * is (the same target, the same 85-95 band) under the Parlor's biggest payout; {@code null} when no whole-dollar
     * payouts fit the band at that stake. Solved once per stake and kept.
     */
    public TwentyOneSettings.Odds moneyOdds(int dollars) {
        if (dollars <= 0) {
            return null;
        }
        TwentyOneSettings s = settings();
        int cap = ctx.games().parlor().rules().capFor(dollars);
        String key = dollars + ":" + cap + ":" + s.rtp() + ":" + s.naturalBonus();
        return moneyOdds.computeIfAbsent(key, k -> TwentyOneSettings.solve(List.of(dollars), s.rtp(),
                s.naturalBonus(), cap, null, null, null).stream().findFirst()).orElse(null);
    }

    /** The live settings — the engine object (read on every use, never cached across a reload). */
    public TwentyOneSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The service this game runs in. */
    public GamesService games() {
        return ctx.games();
    }
}
