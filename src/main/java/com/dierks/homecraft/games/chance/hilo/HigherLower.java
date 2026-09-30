package com.dierks.homecraft.games.chance.hilo;

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
import com.dierks.homecraft.gui.games.chance.HigherLowerMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Higher or Lower (spec §5.6, R1.6, R1.3).
 *
 * <p>Guess whether the next card is higher or lower; each right guess grows the pot (always a
 * whole number of tokens), cash out any time after one. The per-guess return is solved per stake
 * ({@link HigherLowerSettings}) so that even the best play gives back no more than {@code rtp} of
 * every 100 tokens — and the screen says so plainly: every extra guess risks what you have.
 *
 * <p><b>No losing "wins".</b> A side is only offered when a right guess would raise the pot, so a
 * right guess always leaves you with more than you had; a first card with nothing to offer is
 * swapped for the next card, and later on such a card cashes you out.
 *
 * <p><b>A run is a crash-safe round</b> ({@link ChanceRounds}): the tokens go in when it starts,
 * each guess is written to the round before its card is shown, and cashing out settles once.
 * Closing the screen leaves the run open and opening the game resumes it; a player who leaves has
 * it finished by the exit rule ({@link HigherLowerRun#settleOnExit}), from the round's data alone.
 */
public final class HigherLower implements Game {

    /** Built: the game follows its config. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<HigherLowerSettings> SPEC = new GameSpec<>(HigherLowerSettings.ID, GameKind.CHANCE,
            HigherLowerSettings.KEYS, HigherLowerSettings.defaults(), HigherLowerSettings::parse,
            HigherLower::new, HigherLowerRun::settleOnExit);

    private final GameContext ctx;

    public HigherLower(GameContext ctx) {
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
        return "Higher or Lower";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_HILO;
    }

    @Override
    public boolean configEnabled() {
        HigherLowerSettings s = settings();
        return s.enabled() && !s.odds().isEmpty() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Guess if the next card is higher or lower.",
                "The same card again loses.",
                "Each right guess grows your pot. Cash out any time after one.",
                "Aces are high.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        HigherLowerSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        if (!s.odds().isEmpty()) {
            lore.add("&7The best play " + RtpLimits.playerLine(s.lowestRtp()) + ".");
        }
        lore.add("&e" + s.dailyLimit() + " runs a day");
        return Menus.icon(Material.MAP, "&aHigher or Lower &7- " + stakeRange(s) + " tokens in",
                lore.toArray(new String[0]));
    }

    /** Resume the player's open run, or (gate steps 0-4) open the table. */
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
        new HigherLowerMenu(ctx.plugin(), this, player, back, round).open(player);
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
     * to a whole number), then one line per stake for admins (one decimal, and its terms).
     */
    static List<String> oddsLines(HigherLowerSettings s) {
        if (s.odds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("&6Higher or Lower &7— the best play " + RtpLimits.playerLine(s.lowestRtp()) + " · "
                + s.dailyLimit() + " runs a day");
        for (HigherLowerSettings.Odds o : s.odds()) {
            HigherLowerRun.Terms t = o.terms();
            out.add("&7  " + o.stake() + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "% &7(r " + rText(t.r())
                    + ", cashes out at " + t.cap() + " or after " + t.maxGuesses() + " right guesses)");
        }
        return out;
    }

    /** Its {@code /api/arcade} entry: no paytable, the rules, the top multiple and the most guesses. */
    static void feed(FeedWriter out, HigherLowerSettings s) {
        if (s.odds().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        for (HigherLowerSettings.Odds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("maxMultiplier", s.maxMultiplier());
        extra.put("maxGuesses", s.maxGuesses());
        out.chance(SPEC.id(), "Higher or Lower", s.playable(), rtp, s.dailyLimit(), List.of(),
                "Guess whether the next card is higher or lower (Aces high; the same card loses). Each right"
                        + " guess grows the pot; cash out any time after one.", extra);
    }

    /** 915 thousandths as "0.915". */
    static String rText(int r) {
        return r / HigherLowerRun.R_SCALE + "." + String.format(java.util.Locale.ROOT, "%03d", r % HigherLowerRun.R_SCALE);
    }

    private static String stakeRange(HigherLowerSettings s) {
        List<Integer> p = s.playable();
        if (p.isEmpty()) {
            return "no";
        }
        return p.size() == 1 ? Integer.toString(p.get(0)) : p.get(0) + " to " + p.get(p.size() - 1);
    }

    /** The live settings — the engine object (read on every use, never cached across a reload). */
    public HigherLowerSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The service this game runs in. */
    public GamesService games() {
        return ctx.games();
    }
}
