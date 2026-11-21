package com.dierks.homecraft.games.chance.slots;

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
import com.dierks.homecraft.gui.games.chance.SlotsMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A slot machine (the owner: "it would be fun to have several different 'Types' of slot machine"): what Ore
 * Slots and every themed machine share. Each machine is its own game (its own id, name, config section, ledger
 * source, plays a day and odds), and all of them are this class over the one {@link SlotsEngine} and the one screen
 * ({@link SlotsMenu}); a machine differs only in its {@link SlotTable} (its numbers) and that table's
 * {@link SlotTheme} (its look and names).
 *
 * <p>Three reels on one payline: three the same pays that symbol's line, a Wild stands in for any of them, two the
 * same pays the small line, and the blank symbol never pays. The blank symbol's reel weight is the one thing the
 * engine solves, per stake, so the machine gives back what {@code rtp} says and no more, and the number players read
 * is the one the engine computed, never the target.
 *
 * <p>A spin is one instant round ({@code ChanceRounds.play}): the gate, a seed, the engine's outcome from that seed,
 * and what goes in and comes back in one transaction, all before the reels move. The screen only shows what already
 * happened. Everything a player or admin reads about the odds, here and on the website, comes from the one engine in
 * the machine's settings.
 */
public abstract class SlotMachine implements Game {

    protected final GameContext ctx;
    private final GameSpec<? extends SlotTable> spec;
    private final String name;
    private final TokenService.Source source;
    /** The reels solved for each dollar stake asked for so far (0.46.0): solved once and kept. */
    private final Map<String, SlotsEngine> moneyEngines = new ConcurrentHashMap<>();

    protected SlotMachine(GameContext ctx, GameSpec<? extends SlotTable> spec, String name,
                          TokenService.Source source) {
        this.ctx = ctx;
        this.spec = spec;
        this.name = name;
        this.source = source;
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public GameKind kind() {
        return spec.kind();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TokenService.Source source() {
        return source;
    }

    /** The live settings (read on every use, never cached across a reload). */
    public SlotTable table() {
        return ctx.games().settings(spec);
    }

    /** Its own switch, and at least one stake the solve could fit inside the band. */
    @Override
    public boolean configEnabled() {
        SlotTable s = table();
        return s.enabled() && s.engine().playable();
    }

    @Override
    public List<String> rules() {
        return SlotsCopy.rules(table());
    }

    /** "&amp;aOre Slots &amp;7- 1-5 tokens", with the rules, the give-back line and plays left. */
    @Override
    public ItemStack tile(Player viewer) {
        SlotTable s = table();
        SlotsEngine engine = s.engine();
        List<String> lore = new ArrayList<>();
        List<String> rules = rules();
        for (String line : rules.subList(0, Math.max(0, rules.size() - 1))) {
            lore.add("&7" + line);
        }
        if (engine.playable()) {
            String line = giveBackWords(s, engine);
            lore.add("&7" + Character.toUpperCase(line.charAt(0)) + line.substring(1) + ".");
        }
        String potOdds = potOdds(s, engine);
        if (potOdds != null) {
            lore.add(potOdds); // 0.50.2: a pot is never shown without its odds (the voice rule)
        }
        int left = playsLeft(viewer.getUniqueId(), s);
        if (left >= 0) {
            lore.add("&7Plays left today: &f" + left);
        }
        lore.add("&eClick to play");
        return Menus.icon(s.theme().tileIcon(), "&a" + name() + " &7- " + keyFact(s, engine),
                lore.toArray(new String[0]));
    }

    /** A pot machine's odds of taking its pot, for its tile, or {@code null} (no pot). */
    protected String potOdds(SlotTable s, SlotsEngine engine) {
        return null;
    }

    /** The tile's key fact: "1-5 tokens". */
    protected String keyFact(SlotTable s, SlotsEngine engine) {
        return SlotsCopy.stakeRange(engine.stakes());
    }

    /** "gives back about 89 of every 100 tokens", with the lowest value over the stakes. */
    protected String giveBackWords(SlotTable s, SlotsEngine engine) {
        return RtpLimits.playerLine(engine.lowestRtp());
    }

    @Override
    public void open(Player player, Runnable back) {
        SlotTable s = table();
        if (!s.engine().playable()) {
            player.sendMessage(Text.of("&c" + name() + " is closed right now."));
            return;
        }
        new SlotsMenu(ctx.plugin(), this, player, back, s).open(player);
    }

    /** One line for everyone, then the per-stake detail for admins (spec R1.21). */
    @Override
    public List<String> oddsLines() {
        SlotTable s = table();
        List<String> out = new ArrayList<>();
        out.add(SlotsCopy.oddsLine(name(), s.engine(), s.dailyLimit()));
        out.addAll(SlotsCopy.oddsDetail(s.theme(), s.engine()));
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        write(out, id(), name(), table());
    }

    /** A machine's {@code /api/arcade} entry, from the engine in {@code s} (pure, for tests). */
    static void write(FeedWriter out, String id, String name, SlotTable s) {
        SlotsEngine engine = s.engine();
        if (!engine.playable()) {
            return;
        }
        out.chance(id, name, engine.stakes(), engine.rtpByStake(), s.dailyLimit(), engine.feedRows(s.theme()),
                SlotsCopy.feedRules(s), Map.of());
    }

    /**
     * 0.46.0, the Game Parlor's money play: the engine for {@code dollars} in, its reels solved exactly as for a token
     * stake (the same lines, target and 85-95 band) under the Parlor's biggest payout; {@code null} when that stake
     * can't land in the band.
     */
    public SlotsEngine moneyEngine(SlotTable s, int dollars) {
        if (dollars <= 0) {
            return null;
        }
        int cap = ctx.games().parlor().rules().capFor(dollars);
        String key = dollars + ":" + cap + ":" + s.linesRtp() + ":" + s.engineReels() + ":" + s.enginePays();
        SlotsEngine engine = moneyEngines.computeIfAbsent(key,
                k -> SlotsEngine.solve(s.engineReels(), s.enginePays(), List.of(dollars), cap, s.linesRtp()));
        return engine.odds(dollars) == null ? null : engine;
    }

    /** Why a spin for {@code dollars} can't go now, for the Spin tile; {@code null} when nothing stops it. */
    public String moneyBlocker(Player player, int dollars) {
        // a machine's own set price is not the chips' to make; a stake built from chips is
        Refusal r = ctx.games().parlor().check(player, dollars, setPrice() != dollars);
        if (r == null) {
            return null;
        }
        return switch (r.reason()) {
            case PERSONAL_LIMIT -> "Over your limit today";
            case BALANCE -> "Not enough money";
            default -> "Closed right now";
        };
    }

    /**
     * Why a spin for {@code stake} tokens can't go now, for the Spin tile ("Over your limit today"); {@code null} when
     * nothing on the screen stops it. 0.50.2 (the casino play-test): the token Spin checked only the balance and stayed
     * lit past the day's limit, where the click was then refused. The gate still decides on the click.
     */
    public String tokenBlocker(Player player, SlotTable s, int stake) {
        UUID id = player.getUniqueId();
        GamesService games = ctx.games();
        Breaks b = games.breaks();
        int in = b == null ? 0 : b.tokensInToday(id);
        int limit = b == null ? Breaks.NO_LIMIT : b.limit(id);
        int balance = ctx.plugin() == null || ctx.plugin().tokens() == null ? 0 : ctx.plugin().tokens().balance(id);
        return tokenBlocker(stake, balance, playsLeft(id, s), in, limit);
    }

    /** The first thing that stops a token spin: no spins left, the day's limit, the balance. */
    static String tokenBlocker(int stake, int balance, int playsLeft, int tokensIn, int limit) {
        if (playsLeft == 0) {
            return "No spins left today";
        }
        if (limit >= 0 && tokensIn + stake > limit) {
            return "Over your limit today";
        }
        if (balance < stake) {
            return "Need " + (stake - balance) + " more tokens";
        }
        return null;
    }

    /** Spins the player has left today, or -1 if that can't be read right now. */
    public int playsLeft(UUID player, SlotTable s) {
        GamesService games = ctx.games();
        try {
            int played = games.dao().playsToday(player, id(), games.clock().dayKey());
            return Math.max(0, s.dailyLimit() - played);
        } catch (SQLException | RuntimeException e) {
            log().log(Level.WARNING, "Could not read today's " + name() + " plays", e);
            return -1;
        }
    }

    /** "Today: 35 of 100 tokens" — tokens into games of chance today against the effective limit. */
    public String todayLine(UUID player) {
        Breaks breaks = ctx.games().breaks();
        if (breaks == null) {
            return "&7Today: &f?";
        }
        int in = breaks.tokensInToday(player);
        int limit = breaks.limit(player);
        return limit == Breaks.NO_LIMIT
                ? "&eToday: &6" + in + " &etoken" + (in == 1 ? "" : "s") + " in"
                : "&eToday: &6" + in + " of " + limit + " &etokens";
    }

    /** The plugin's log (a test has no plugin). */
    protected Logger log() {
        return ctx.plugin() != null ? ctx.plugin().getLogger() : Logger.getLogger(SlotMachine.class.getName());
    }
}
