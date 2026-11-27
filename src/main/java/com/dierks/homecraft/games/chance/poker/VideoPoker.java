package com.dierks.homecraft.games.chance.poker;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.57.0: Video Poker, the Game Parlor's Jacks or Better machine.
 *
 * <p>Put in your tokens (or, in the Parlor with the money permission, dollars from the chips) and five cards are dealt
 * from a fresh 52-card deck. Tap the cards to hold them (a held card glows and says HELD), then Draw once: the others
 * are replaced from the 47 left, and the hand is paid by the pay table on the screen ({@link VideoPokerSettings}). A
 * pair of Jacks or better is the least that pays, and it gives your tokens back, never called a win.
 *
 * <p><b>Its one number is exact.</b> Each stake's return is the best play's, counted over every deal, hold and draw
 * ({@link PokerMath}) for exactly the prizes that stake pays, and the screen, {@code /hcm arcade odds} and the website
 * all show that count. Any other way of holding gives back less.
 *
 * <p><b>A hand is a crash-safe round</b> ({@link ChanceRounds}): the stake goes in when the cards are dealt, as an
 * OPEN round whose data carries every hand's prize and whose seed decides every card ({@link PokerDeal}); the draw
 * settles it once, in one transaction, before the new cards are shown. Closing the screen leaves the hand open and
 * opening the game resumes it; a player who leaves has it drawn for them with the best hold, from the round's data
 * alone ({@link PokerDeal#settleOnExit}).
 */
public final class VideoPoker implements Game {

    static final String NAME = "Video Poker";

    public static final GameSpec<VideoPokerSettings> SPEC = new GameSpec<>(VideoPokerSettings.ID, GameKind.CHANCE,
            VideoPokerSettings.KEYS, VideoPokerSettings.defaults(), VideoPokerSettings::parse, VideoPoker::new,
            PokerDeal::settleOnExit);

    /** How the website and admins read the rules in one line. */
    static final String RULES_LINE = "Jacks or Better: five cards from a fresh deck, hold any, draw once; the hand is"
            + " paid by the pay table (a pair of Jacks or better gives the tokens back).";

    private final GameContext ctx;
    /** What a hand pays at each dollar stake asked for so far: counted once and kept. */
    private final Map<String, Optional<VideoPokerSettings.Odds>> moneyOdds = new ConcurrentHashMap<>();

    public VideoPoker(GameContext ctx) {
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
        return TokenService.Source.ARCADE_VIDEO_POKER;
    }

    @Override
    public boolean configEnabled() {
        VideoPokerSettings s = settings();
        return s.enabled() && !s.odds().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Put in your tokens: five cards are dealt.",
                "Tap the cards you want to keep. They say HELD.",
                "Draw once: the others are replaced.",
                "The pay table shows what each hand pays.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        VideoPokerSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        if (!s.odds().isEmpty()) {
            lore.add("&7The best play " + RtpLimits.playerLine(s.lowestRtp()) + ".");
        }
        lore.add("&e" + s.dailyLimit() + " hands a day");
        List<Integer> open = s.open();
        String range = open.isEmpty() ? "no" : open.size() == 1 ? Integer.toString(open.get(0))
                : open.get(0) + " to " + open.get(open.size() - 1);
        // 0.59.0: a machine of its own (it was the same paper as Twenty-One and Texas Hold'em on the Games screen)
        return Menus.icon(Material.OBSERVER, "&aVideo Poker &7- " + range + " tokens in", lore.toArray(new String[0]));
    }

    /** Resume the player's open hand, or (gate steps 0-4) open the machine. */
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
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.chance.VideoPokerMenu(ctx.plugin(), this, player, back, round)
                .open(player);
    }

    /**
     * The exact count's tallies are built off the main thread as the game starts (about a second, once), so a money
     * stake the Parlor's payout cap holds down is counted at once when a player first puts its chips in.
     */
    @Override
    public void start() {
        if (ctx.plugin() == null) {
            return;
        }
        ctx.plugin().getServer().getScheduler().runTaskAsynchronously(ctx.plugin(), () -> {
            try {
                PokerMath.warm();
            } catch (RuntimeException | OutOfMemoryError e) {
                ctx.plugin().getLogger().warning("Video Poker could not count its odds ahead: " + e);
            }
        });
    }

    @Override
    public List<String> oddsLines() {
        return oddsLines(settings());
    }

    @Override
    public void feed(FeedWriter out) {
        feed(out, settings());
    }

    /** {@code /hcm arcade odds}: the player's line (the lowest stake's return), then each stake for admins. */
    static List<String> oddsLines(VideoPokerSettings s) {
        if (s.odds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("&6" + NAME + " &7— the best play " + RtpLimits.playerLine(s.lowestRtp()) + " · " + s.dailyLimit()
                + " hands a day");
        for (VideoPokerSettings.Odds o : s.odds()) {
            List<String> rows = new ArrayList<>();
            for (PokerHand h : VideoPokerSettings.ROWS) {
                rows.add(h.label() + " " + o.prize(h));
            }
            out.add("&7  " + o.stake() + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "% &7(exact, best play: "
                    + o.exact().num() + "/" + o.exact().den() + ") " + String.join(", ", rows));
        }
        for (int stake : s.stakes()) {
            if (s.odds(stake) == null) {
                out.add("&7  " + stake + " in: off (its pay table can't give back 85-95 of every 100 there)");
            }
        }
        return out;
    }

    /** Its {@code /api/arcade} entry: each stake's exact return and every hand's prize at it. */
    static void feed(FeedWriter out, VideoPokerSettings s) {
        if (s.odds().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (VideoPokerSettings.Odds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
            for (PokerHand h : VideoPokerSettings.ROWS) {
                rows.add(new FeedWriter.PayRow(o.stake(), h.label(), o.prize(h), null, null, null));
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> pays = new LinkedHashMap<>();
        for (PokerHand h : VideoPokerSettings.ROWS) {
            pays.put(h.key(), s.multiple(h));
        }
        extra.put("payTable", pays);
        extra.put("rtpIs", "the best play's exact return, counted over every deal, hold and draw");
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), rows, RULES_LINE, extra);
    }

    /**
     * The Game Parlor's money play: what a hand pays with {@code dollars} in (each hand's multiple, held under the
     * Parlor's biggest payout), counted exactly as a token stake is; {@code null} when that lands outside 85-95.
     */
    public VideoPokerSettings.Odds moneyOdds(int dollars) {
        if (dollars <= 0) {
            return null;
        }
        VideoPokerSettings s = settings();
        int cap = ctx.games().parlor().rules().capFor(dollars);
        String key = dollars + ":" + cap + ":" + s.pays();
        return moneyOdds.computeIfAbsent(key, k -> {
            VideoPokerSettings.Odds o = VideoPokerSettings.odds(s.pays(), dollars, cap);
            return RtpLimits.inBand(o.exact()) ? Optional.of(o) : Optional.empty();
        }).orElse(null);
    }

    /** The live settings (read on every use, never cached across a reload). */
    public VideoPokerSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The service this game runs in. */
    public GamesService games() {
        return ctx.games();
    }

    /** "5 tokens" or "$5". */
    public static String amount(boolean money, int n) {
        return money ? Parlor.dollars(n) : n + " token" + (n == 1 ? "" : "s");
    }
}
