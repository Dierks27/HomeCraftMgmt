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

    public TwentyOne(GameContext ctx) {
        this.ctx = ctx;
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

    /** Resume the player's open hand, or (gate steps 0-4) open the table. */
    @Override
    public void open(Player player, Runnable back) {
        GamesService games = ctx.games();
        ChanceRounds.Round round = games.rounds().openRound(player.getUniqueId(), id());
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

    /** The live settings — the engine object (read on every use, never cached across a reload). */
    public TwentyOneSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The service this game runs in. */
    public GamesService games() {
        return ctx.games();
    }
}
