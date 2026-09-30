package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.chance.WheelMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The Wheel (spec §5.5, R1.7).
 *
 * <p>One spin of a ring of 24 equally likely spaces: what you see is the odds. Each space is a
 * base multiple of the tokens put in (0, 1 or more); the engine scales the prizes per stake so the
 * wheel gives back what {@code rtp} says, no more, and every tile shows its exact prize for the
 * stake the player picked. A space worth exactly the tokens put in reads "Your 10 back" — never a
 * win — and every prize above that is at least one token more than was put in.
 *
 * <p>A spin is an instant round ({@link com.dierks.homecraft.games.ChanceRounds#play}): the gate,
 * then the seed decides the space, then the tokens in, the round and the tokens back are written in
 * one transaction — all before the highlight starts to move. {@link WheelSettings} is the engine,
 * and the screen, {@code /hcm arcade odds} and the website all read that same object.
 */
public final class Wheel implements Game {

    /** Built: the game follows its config. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<WheelSettings> SPEC = new GameSpec<>("wheel", GameKind.CHANCE,
            WheelSettings.KEYS, WheelSettings.defaults(), WheelSettings::parse,
            Wheel::new, null);

    /** The player-facing name. */
    static final String NAME = "The Wheel";
    /** How the website and admins read the rules in one line. */
    static final String RULES_LINE = "24 equally likely spaces around the screen; each shows the tokens it gives "
            + "for the tokens put in (0 = nothing, your tokens back, or a win).";

    private final GameContext ctx;

    public Wheel(GameContext ctx) {
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
        return NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_WHEEL;
    }

    /** Its own switch, built, and at least one stake left after the solve. */
    @Override
    public boolean configEnabled() {
        WheelSettings s = settings();
        return s.enabled() && IMPLEMENTED && !s.odds().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Pick how many tokens to put in, then spin.",
                "All 24 spaces are just as likely.",
                "Each space shows what it gives you.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        WheelSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        WheelOdds low = s.lowest();
        if (low != null) {
            lore.add("&7" + low.giveBack() + ".");
        }
        int left = playsLeft(viewer, s);
        if (left >= 0) {
            lore.add("&7Plays left today: &f" + left);
        }
        lore.add("&eClick to play");
        return Menus.icon(Material.COMPASS, "&aThe Wheel &7- " + range(s.open()) + " tokens",
                lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        WheelSettings s = settings();
        if (s.odds().isEmpty()) {
            ctx.games().tell(player, Refusal.CLOSED);
            return;
        }
        new WheelMenu(ctx.plugin(), this, player, back, s).open(player);
    }

    /**
     * The player's line first ("The Wheel — gives back about 87 of every 100 tokens · 30 plays a
     * day", the lowest stake's number), then one detail line per stake for admins. Nothing while
     * the game is closed.
     */
    @Override
    public List<String> oddsLines() {
        return ctx.games().enabled(this) ? oddsLines(settings()) : List.of();
    }

    /** Its {@code /api/arcade} entry: every stake's exact return and prize rows. Nothing while closed. */
    @Override
    public void feed(FeedWriter out) {
        if (ctx.games().enabled(this)) {
            feed(settings(), out);
        }
    }

    // ---- play (the screen calls these) ------------------------------------------------------

    /**
     * One spin with the settings the screen was opened with: the gate (every step), then the
     * instant round. {@code null} when refused; the player has been told.
     */
    public WheelSpin play(Player player, WheelSettings s, int stake) {
        GamesService games = ctx.games();
        if (s.odds(stake) == null) {
            games.tell(player, Refusal.CLOSED);
            return null;
        }
        // The gate counts plays too; asking first keeps a player at the limit from being told
        // anything past it (steps 0-4, then the day's plays, then the rest).
        Refusal r = games.canOpen(player, this);
        if (r == null) {
            int left = playsLeft(player, s);
            if (left == 0) {
                r = Refusal.dailyLimit("Wheel");
            } else if (left < 0) {
                r = Refusal.CLOSED;
            }
        }
        if (r != null) {
            games.tell(player, r);
            return null;
        }
        return games.rounds().play(player, this, stake, s);
    }

    /** Spins left today, or -1 if they can't be counted right now. */
    public int playsLeft(Player player, WheelSettings s) {
        try {
            long day = ctx.plugin().clock().dayKey();
            int played = ctx.games().dao().playsToday(player.getUniqueId(), id(), day);
            return Math.max(0, s.dailyLimit() - played);
        } catch (SQLException | RuntimeException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "The Wheel could not count today's spins", e);
            return -1;
        }
    }

    /** "Today: 35 of 100 tokens" — tokens put into games of chance today against the player's limit. */
    public String today(Player player) {
        Breaks b = ctx.games().breaks();
        if (b == null) {
            return "Today: -";
        }
        return todayLine(b.tokensInToday(player.getUniqueId()), b.limit(player.getUniqueId()));
    }

    /**
     * Why Spin can't go right now, for its tile ("need 3 more tokens"), or {@code null} when
     * nothing on the screen says it can't. The gate still decides on the click.
     */
    public String blocker(Player player, WheelSettings s, int stake) {
        int balance = ctx.plugin().tokens() == null ? 0 : ctx.plugin().tokens().balance(player.getUniqueId());
        Breaks b = ctx.games().breaks();
        int in = b == null ? 0 : b.tokensInToday(player.getUniqueId());
        int limit = b == null ? Breaks.NO_LIMIT : b.limit(player.getUniqueId());
        return blocker(stake, balance, playsLeft(player, s), in, limit);
    }

    /** The live settings (read on every use, never cached across a reload). */
    WheelSettings settings() {
        return ctx.games().settings(SPEC);
    }

    // ---- pure helpers (tested) ---------------------------------------------------------------

    /** "Today: 35 of 100 tokens", or "Today: 35 tokens" with no limit at all. */
    static String todayLine(int tokensIn, int limit) {
        return limit < 0 ? "Today: " + tokensIn + " tokens" : "Today: " + tokensIn + " of " + limit + " tokens";
    }

    /** The first thing on screen that stops a spin: no plays left, the day's limit, the balance. */
    static String blocker(int stake, int balance, int playsLeft, int tokensIn, int limit) {
        if (playsLeft == 0) {
            return "no spins left today";
        }
        if (limit >= 0 && tokensIn + stake > limit) {
            return "over your limit today";
        }
        if (balance < stake) {
            return "need " + (stake - balance) + " more tokens";
        }
        return null;
    }

    /** "5-20", or "5" for one stake. */
    static String range(List<Integer> stakes) {
        if (stakes.isEmpty()) {
            return "0";
        }
        int lo = stakes.get(0);
        int hi = stakes.get(stakes.size() - 1);
        return lo == hi ? Integer.toString(lo) : lo + "-" + hi;
    }

    /** The odds lines for {@code s}: the player line, then per stake for admins (and any stake that is off). */
    static List<String> oddsLines(WheelSettings s) {
        WheelOdds low = s.lowest();
        if (low == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(NAME + " — " + RtpLimits.playerLine(low.rtp()) + " · " + s.dailyLimit() + " plays a day");
        for (WheelOdds o : s.odds()) {
            List<String> prizes = new ArrayList<>();
            for (int p : o.distinct()) {
                prizes.add(o.plain(p) + " on " + o.spaces(p));
            }
            out.add(NAME + " at " + o.stake() + " tokens: " + RtpLimits.tenthPercent(o.rtp()) + "% - "
                    + String.join(", ", prizes) + " (of 24 spaces)");
        }
        for (int stake : s.stakes()) {
            if (s.odds(stake) == null) {
                out.add(NAME + " at " + stake + " tokens: off (it can't give back 85-95 of every 100)");
            }
        }
        return out;
    }

    /** The feed entry for {@code s}: one row per stake per different prize, counted in spaces of 24. */
    static void feed(WheelSettings s, FeedWriter out) {
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (WheelOdds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
            for (int p : o.distinct()) {
                rows.add(new FeedWriter.PayRow(o.stake(), o.plain(p), p, null, o.spaces(p), WheelSettings.SPACES));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), rows, RULES_LINE, Map.of());
    }
}
