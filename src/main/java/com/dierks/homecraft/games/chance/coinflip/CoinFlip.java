package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.chance.CoinFlipConfirmMenu;
import com.dierks.homecraft.gui.games.chance.CoinFlipMenu;
import com.dierks.homecraft.gui.games.chance.CoinFlipShowMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Coin Flip (spec §5.7, R1.8).
 *
 * <p>Two players both online put in the same tokens and one coin decides; the winner gets a share
 * of both set by {@code rtp} and the rest is gone. Ships off: it is the one game where tokens pass
 * between players, so the owner turns it on if he wants it — and even then a player can only be
 * asked once they have turned Coin Flip invites on themselves (in Take a break).
 *
 * <p>The flow: A picks a stake and a nearby player from the shared player picker, which lists only
 * players who could take the flip right now (the inviter is never told why someone is missing). B
 * gets the invite ({@link com.dierks.homecraft.games.Invites}); accepting opens a screen with the
 * stake, what the winner gets and the 1 in 2 chance; only B's Confirm on that screen can move
 * tokens, and at that moment everything is checked again for BOTH players
 * ({@link CoinFlipRules#confirmable}). If anything fails, nothing is taken and both read "The flip
 * was called off." Otherwise one transaction takes both stakes, writes one round row per player and
 * pays the winner ({@code GamesDao.pairRound}); the flip is logged, never announced, and each
 * player sees a short coin show that was decided before it started.
 *
 * <p>The daily limit counts each player's flips; the pair limit counts the flips between the same
 * two players today whoever asked — together they keep Coin Flip from moving tokens between
 * accounts at any scale.
 */
public final class CoinFlip implements Game {

    /** Built: the game follows its config (which ships it off). */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<CoinFlipSettings> SPEC = new GameSpec<>("coin_flip", GameKind.CHANCE,
            CoinFlipSettings.KEYS, CoinFlipSettings.defaults(), CoinFlipSettings::parse,
            CoinFlip::new, null);

    /** The player-facing name. */
    static final String NAME = "Coin Flip";
    /** How the website and admins read the rules in one line. */
    static final String RULES_LINE = "Two players close to each other put in the same tokens; one flip picks the "
            + "winner, who gets most of both (the rest is gone). Each has a 1 in 2 chance.";

    /**
     * An invite this game sent and hasn't settled.
     *
     * @param id        the invite's id
     * @param from      who asked
     * @param fromName  their name, for lines sent after they left
     * @param to        who was asked
     * @param toName    their name
     * @param stake     the tokens each would put in
     * @param expiresAt when the invite runs out (epoch ms); the confirm must come before it
     * @param accepted  whether the invited player accepted (and so has the confirm screen)
     */
    public record Offer(long id, UUID from, String fromName, UUID to, String toName, int stake, long expiresAt,
                        boolean accepted) {

        Offer accept() {
            return new Offer(id, from, fromName, to, toName, stake, expiresAt, true);
        }

        boolean involves(UUID player) {
            return from.equals(player) || to.equals(player);
        }
    }

    private final GameContext ctx;
    /** Invites sent and not yet settled, by invite id. */
    private final Map<Long, Offer> offers = new LinkedHashMap<>();
    /** When each pair last had an invite (either way round), for {@link CoinFlipRules#PAIR_COOLDOWN_MS}. */
    private final Map<String, Long> lastInvite = new HashMap<>();

    public CoinFlip(GameContext ctx) {
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
        return TokenService.Source.ARCADE_COIN_FLIP;
    }

    /** Its own switch (shipped off), built, and at least one stake left after the solve. */
    @Override
    public boolean configEnabled() {
        CoinFlipSettings s = settings();
        return s.enabled() && IMPLEMENTED && !s.odds().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Invite a player near you. You both put in the same tokens.",
                "One flip: each of you has a 1 in 2 chance.",
                "The winner gets most of the tokens put in. The rest is gone.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        CoinFlipSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        CoinFlipOdds low = s.lowest();
        if (low != null) {
            lore.add("&7" + low.giveBack() + ".");
        }
        int left = playsLeft(viewer, s);
        if (left >= 0) {
            lore.add("&7Flips left today: &f" + left);
        }
        lore.add(takesInvites(viewer.getUniqueId()) ? "&7Your Coin Flip invites: &aon" : "&7Your Coin Flip invites: &7off");
        lore.add("&eClick to play");
        return Menus.icon(Material.GOLD_NUGGET, "&aCoin Flip &7- " + range(s.open()) + " tokens each",
                lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        CoinFlipSettings s = settings();
        if (s.odds().isEmpty()) {
            ctx.games().tell(player, Refusal.CLOSED);
            return;
        }
        new CoinFlipMenu(ctx.plugin(), this, player, back, s, s.open().get(0)).open(player);
    }

    /** A player who leaves takes their invites with them (sent or received). */
    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        offers.values().removeIf(o -> o.involves(id));
    }

    @Override
    public void stop() {
        offers.clear();
        lastInvite.clear();
    }

    /** The player's line first, then one detail line per stake for admins. Nothing while closed. */
    @Override
    public List<String> oddsLines() {
        return ctx.games().enabled(this) ? oddsLines(settings()) : List.of();
    }

    /** Its {@code /api/arcade} entry: each stake's exact return and "win the flip" row. Nothing while closed. */
    @Override
    public void feed(FeedWriter out) {
        if (ctx.games().enabled(this)) {
            feed(settings(), out);
        }
    }

    // ---- what the screens read ----------------------------------------------------------------

    /** The live settings (read on every use, never cached across a reload). */
    public CoinFlipSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** Whether the player has turned Coin Flip invites on (off until they do, in Take a break). */
    public boolean takesInvites(UUID player) {
        return ctx.games().invites().accepts(player, id());
    }

    /** Take a break: the only place a player turns their Coin Flip invites on or off (spec §4.3). */
    public void takeABreak(Player player, Runnable back) {
        ctx.games().screens().takeABreak(player, back);
    }

    /** The invite the player sent that hasn't been settled, or {@code null}. */
    public Offer outgoing(UUID player) {
        prune();
        for (Offer o : offers.values()) {
            if (o.from().equals(player)) {
                return o;
            }
        }
        return null;
    }

    /** An open invite by id, or {@code null} once it was used, answered no, or ran out. */
    public Offer offer(long id) {
        return offers.get(id);
    }

    /** Flips left today, or -1 if they can't be counted right now. */
    public int playsLeft(Player player, CoinFlipSettings s) {
        try {
            return Math.max(0, s.dailyLimit() - playsToday(player.getUniqueId()));
        } catch (SQLException | RuntimeException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Coin Flip could not count today's flips", e);
            return -1;
        }
    }

    /** "Today: 35 of 100 tokens" — tokens put into games of chance today against the player's limit. */
    public String today(Player player) {
        Breaks b = ctx.games().breaks();
        if (b == null) {
            return "Today: -";
        }
        int limit = b.limit(player.getUniqueId());
        int in = b.tokensInToday(player.getUniqueId());
        return limit < 0 ? "Today: " + in + " tokens" : "Today: " + in + " of " + limit + " tokens";
    }

    /** Why inviting can't go right now, for the button ("need 3 more tokens"), or {@code null}. */
    public String blocker(Player player, CoinFlipSettings s, int stake) {
        int balance = ctx.plugin().tokens() == null ? 0 : ctx.plugin().tokens().balance(player.getUniqueId());
        Breaks b = ctx.games().breaks();
        int in = b == null ? 0 : b.tokensInToday(player.getUniqueId());
        int limit = b == null ? Breaks.NO_LIMIT : b.limit(player.getUniqueId());
        int left = playsLeft(player, s);
        if (left == 0) {
            return "no flips left today";
        }
        if (limit >= 0 && in + stake > limit) {
            return "over your limit today";
        }
        if (balance < stake) {
            return "need " + (stake - balance) + " more tokens";
        }
        return null;
    }

    // ---- inviting ---------------------------------------------------------------------------

    /**
     * A asked to invite someone for {@code stake}: check A can put it in, then open the player
     * picker listing only those who could take the flip right now.
     */
    public void invite(Player a, CoinFlipSettings s, int stake, Runnable back) {
        GamesService games = ctx.games();
        if (s.odds(stake) == null) {
            games.tell(a, Refusal.CLOSED);
            return;
        }
        if (outgoing(a.getUniqueId()) != null) {
            games.tell(a, Refusal.of("You already asked someone. Wait for their answer."));
            return;
        }
        Refusal r = ready(a, s, stake);
        if (r != null) {
            games.tell(a, r);
            return;
        }
        games.screens().pickPlayer(a, this,
                p -> games.guard(this, () -> why(a, p, s, stake) == null, false),
                b -> games.guard(this, () -> send(a, b, s, stake, back)),
                back);
    }

    /**
     * Whether a player could put {@code stake} into Coin Flip right now, without counting as a
     * click: the gate's opening steps, its limit and balance steps, and the day's flips.
     */
    private Refusal ready(Player p, CoinFlipSettings s, int stake) {
        Refusal r = gate(p, stake);
        if (r != null) {
            return r;
        }
        int left = playsLeft(p, s);
        if (left < 0) {
            return Refusal.CLOSED;
        }
        return left == 0 ? Refusal.dailyLimit(NAME) : null;
    }

    /** Gate steps 0-4 and 2, 4, 7, 8 for the stake; {@code null} = go ahead. */
    private Refusal gate(Player p, int stake) {
        GamesService games = ctx.games();
        Refusal r = games.canOpen(p, this);
        return r != null ? r : games.gate().extra(p, this, stake);
    }

    /** Why {@code a} can't ask {@code b} right now (never shown to {@code a}), or {@code null}. */
    private String why(Player a, Player b, CoinFlipSettings s, int stake) {
        prune();
        UUID bid = b.getUniqueId();
        boolean waiting = ctx.games().invites().pending(bid) != null || involved(bid);
        boolean cooling = cooling(a.getUniqueId(), bid, System.currentTimeMillis());
        CoinFlipRules.Side sa = side(a, null, 0);
        // The cheap facts first, with passing values for the rest, so nobody's limits or the
        // database are read for players who are simply too far away.
        String cheap = CoinFlipRules.invitable(s, stake, sa, side(b, null, 0), true, waiting, cooling, 0);
        if (cheap != null) {
            return cheap;
        }
        try {
            CoinFlipRules.Side sb = side(b, gate(b, stake), playsToday(bid));
            int pair = pairPlaysToday(a.getUniqueId(), bid);
            return CoinFlipRules.invitable(s, stake, sa, sb, takesInvites(bid), false, false, pair);
        } catch (SQLException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Coin Flip could not check an invite", e);
            return "the database could not be read";
        }
    }

    /** Send the invite, after checking both again (things change while a picker is open). */
    private void send(Player a, Player b, CoinFlipSettings s, int stake, Runnable back) {
        GamesService games = ctx.games();
        String why = outgoing(a.getUniqueId()) != null ? "an invite is already out" : why(a, b, s, stake);
        Invite inv = null;
        if (why == null) {
            inv = games.invites().send(a, b, this, "Coin Flip for " + stake + " tokens each", s.inviteSeconds(),
                    (invite, yes) -> games.guard(this, () -> answered(invite, yes)));
        }
        if (inv == null) {
            games.tell(a, Refusal.of("That player can't take an invite right now."));
            return;
        }
        lastInvite.put(CoinFlipRules.pairKey(a.getUniqueId(), b.getUniqueId()), System.currentTimeMillis());
        offers.put(inv.id(), new Offer(inv.id(), a.getUniqueId(), a.getName(), b.getUniqueId(), b.getName(), stake,
                inv.expiresAt(), false));
        a.sendMessage(Text.of("&7Invite sent to &f" + b.getName() + "&7. It lasts " + s.inviteSeconds() + " seconds."));
        if (back != null) {
            back.run();
        }
    }

    /** The invite was answered: yes opens B's confirm screen; no (or it ran out) tells A plainly. */
    private void answered(Invite inv, boolean yes) {
        Offer o = offers.get(inv.id());
        if (o == null) {
            return;
        }
        Player b = Bukkit.getPlayer(o.to());
        if (!yes || b == null) {
            offers.remove(inv.id());
            tellInviter(o);
            return;
        }
        offers.put(o.id(), o.accept());
        new CoinFlipConfirmMenu(ctx.plugin(), this, b, o.id()).open(b);
    }

    /** B said no on the confirm screen, closed it, or let it run out. */
    public void decline(Player b, long inviteId, boolean ranOut) {
        Offer o = offers.remove(inviteId);
        if (o == null) {
            return;
        }
        if (ranOut) {
            b.sendMessage(Text.of("&7That Coin Flip invite ran out."));
        }
        tellInviter(o);
    }

    private void tellInviter(Offer o) {
        Player a = Bukkit.getPlayer(o.from());
        if (a != null) {
            a.sendMessage(Text.of("&7" + o.toName() + " didn't take your Coin Flip invite."));
        }
    }

    // ---- the flip ---------------------------------------------------------------------------

    /**
     * B pressed Confirm. Everything is checked again for both players at this moment; then one
     * transaction takes both stakes, writes both rows and pays the winner.
     *
     * @param shownPays what B's screen said the winner gets: a reload that changed it calls the flip off
     */
    public void confirm(Player b, long inviteId, int shownPays) {
        Offer o = offers.get(inviteId);
        if (o == null || !o.to().equals(b.getUniqueId())) {
            calledOff(null, b, null, "the invite was already used or answered");
            return;
        }
        offers.remove(inviteId); // used: a second click can never flip twice
        Player a = Bukkit.getPlayer(o.from());
        CoinFlipSettings s = settings();
        CoinFlipOdds odds = s.odds(o.stake());
        String why;
        try {
            if (odds == null || odds.pays() != shownPays || !ctx.games().enabled(this)) {
                why = "Coin Flip or that stake changed since the invite";
            } else {
                // The full gate for both, the inviter first; the invited player's is only asked
                // (and their click only counted) when the inviter could go ahead.
                CoinFlipRules.Side sa = a == null ? CoinFlipRules.Side.offline(o.from())
                        : side(a, ctx.games().canStake(a, this, o.stake()), playsToday(a.getUniqueId()));
                CoinFlipRules.Side sb = !sa.online() || sa.gate() != null ? side(b, null, 0)
                        : side(b, ctx.games().canStake(b, this, o.stake()), playsToday(b.getUniqueId()));
                int pair = pairPlaysToday(o.from(), o.to());
                why = CoinFlipRules.confirmable(s, o.stake(), true, System.currentTimeMillis() >= o.expiresAt(),
                        sa, sb, pair);
            }
        } catch (SQLException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Coin Flip could not check a flip", e);
            why = "the database could not be read";
        }
        if (why != null) {
            calledOff(a, b, o, why);
            return;
        }
        flip(a, b, o, odds);
    }

    private void flip(Player a, Player b, Offer o, CoinFlipOdds odds) {
        long seed = ThreadLocalRandom.current().nextLong();
        boolean inviterWins = CoinFlipRules.inviterWins(seed);
        UUID winner = inviterWins ? o.from() : o.to();
        String pair = UUID.randomUUID().toString().substring(0, 8);
        String data = CoinFlipRules.data(pair, o.stake(), odds.pays(), inviterWins);
        var clock = ctx.plugin().clock();
        List<ChanceRounds.Round> rows;
        try {
            rows = ctx.games().dao().pairRound(o.from(), o.to(), id(), source(), clock.dayKey(), o.stake(), seed,
                    winner, odds.pays(), data, ChanceRounds.stakeDetail(NAME, o.stake()),
                    ChanceRounds.payoutDetail(NAME, o.stake(), odds.pays()), clock.nowMillis());
        } catch (SQLException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Coin Flip could not be written", e);
            rows = null;
        }
        if (rows == null) {
            calledOff(a, b, o, "a player's tokens were refused");
            return;
        }
        ctx.plugin().getLogger().info("Coin Flip: " + o.fromName() + " and " + o.toName() + " put in " + o.stake()
                + " each; " + (inviterWins ? o.fromName() : o.toName()) + " got " + odds.pays()
                + " (pair " + pair + ", seed " + seed + ")");
        show(a, o, inviterWins, odds);
        show(b, o, inviterWins, odds);
    }

    private void show(Player viewer, Offer o, boolean inviterWins, CoinFlipOdds odds) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        boolean asked = viewer.getUniqueId().equals(o.from());
        new CoinFlipShowMenu(ctx.plugin(), this, viewer, o.fromName(), o.toName(), inviterWins, asked, odds)
                .open(viewer);
    }

    /** Nothing was taken: both read the same plain line, and the log says why. */
    private void calledOff(Player a, Player b, Offer o, String why) {
        ctx.plugin().getLogger().info("Coin Flip " + (o == null ? "" : "between " + o.fromName() + " and "
                + o.toName() + " ") + "called off: " + why);
        Refusal off = Refusal.of("The flip was called off.");
        for (Player p : new Player[]{a, b}) {
            if (p != null && p.isOnline()) {
                ctx.games().tell(p, off);
            }
        }
    }

    // ---- facts ------------------------------------------------------------------------------

    private CoinFlipRules.Side side(Player p, Refusal gate, int playsToday) {
        if (p == null || !p.isOnline()) {
            return CoinFlipRules.Side.offline(p == null ? new UUID(0, 0) : p.getUniqueId());
        }
        Location l = p.getLocation();
        String world = l.getWorld() == null ? null : l.getWorld().getName();
        return new CoinFlipRules.Side(p.getUniqueId(), true, world, l.getX(), l.getY(), l.getZ(), gate, playsToday);
    }

    private int playsToday(UUID player) throws SQLException {
        return ctx.games().dao().playsToday(player, id(), ctx.plugin().clock().dayKey());
    }

    private int pairPlaysToday(UUID a, UUID b) throws SQLException {
        return ctx.games().dao().pairPlaysToday(a, b, id(), ctx.plugin().clock().dayKey());
    }

    private boolean involved(UUID player) {
        for (Offer o : offers.values()) {
            if (o.involves(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean cooling(UUID a, UUID b, long now) {
        Long last = lastInvite.get(CoinFlipRules.pairKey(a, b));
        return last != null && now - last < CoinFlipRules.PAIR_COOLDOWN_MS;
    }

    /** Forget cooldowns that are over and invites long past running out (a leak guard, nothing more). */
    private void prune() {
        long now = System.currentTimeMillis();
        lastInvite.values().removeIf(t -> now - t >= CoinFlipRules.PAIR_COOLDOWN_MS);
        offers.values().removeIf(o -> now >= o.expiresAt() + 60_000L);
    }

    // ---- pure helpers (tested) ---------------------------------------------------------------

    /** "5-25", or "5" for one stake. */
    static String range(List<Integer> stakes) {
        if (stakes.isEmpty()) {
            return "0";
        }
        int lo = stakes.get(0);
        int hi = stakes.get(stakes.size() - 1);
        return lo == hi ? Integer.toString(lo) : lo + "-" + hi;
    }

    /** The odds lines for {@code s}: the player line, then per stake for admins (and any stake that is off). */
    static List<String> oddsLines(CoinFlipSettings s) {
        CoinFlipOdds low = s.lowest();
        if (low == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(NAME + " — " + RtpLimits.playerLine(low.rtp()) + " · " + s.dailyLimit() + " plays a day");
        for (CoinFlipOdds o : s.odds()) {
            out.add(NAME + " at " + o.stake() + " tokens each: " + RtpLimits.tenthPercent(o.rtp())
                    + "% - the winner gets " + o.pays() + " of " + o.pot() + ", a 1 in 2 chance");
        }
        for (int stake : s.stakes()) {
            if (s.odds(stake) == null) {
                out.add(NAME + " at " + stake + " tokens each: off (it can't give back 85-95 of every 100)");
            }
        }
        return out;
    }

    /** The feed entry for {@code s}: one "win the flip" row per stake. */
    static void feed(CoinFlipSettings s, FeedWriter out) {
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (CoinFlipOdds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
            rows.add(new FeedWriter.PayRow(o.stake(), "win the flip", o.pays(), 0.5, null, null));
        }
        if (rows.isEmpty()) {
            return;
        }
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), rows, RULES_LINE, Map.of());
    }
}
