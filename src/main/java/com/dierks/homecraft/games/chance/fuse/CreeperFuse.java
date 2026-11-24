package com.dierks.homecraft.games.chance.fuse;

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
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 0.57.0: Creeper Fuse, the Game Parlor's fuse everyone watches together.
 *
 * <p><b>One fuse at a time, everyone in it together.</b> The first put-in starts a short wait ({@code gather_seconds})
 * in which anyone may put in too: one stake each, tokens or (in the Parlor, with the money permission) dollars. Then
 * the fuse is lit: a creeper hisses while the multiple climbs from 1.00x, slowly at first and faster and faster
 * ({@link FuseMath#climb}). Each player taps <i>Cash out</i> whenever they like and gets their stake times the multiple
 * shown, in whole tokens. When the fuse runs out the creeper goes off (only a bang and a puff of smoke: nothing is
 * broken and nobody is hurt) and anyone still in gets nothing. If it climbs all the way to the top it burns out
 * there, and everyone still in is paid at the top. An auto cash-out, picked on the screen, cashes a player out by
 * themselves at that multiple.
 *
 * <p><b>Where it goes off is drawn before it is lit</b> ({@link FuseMath#boomAt}): one draw for the whole fuse, from a
 * seed drawn when the wait ends, written into every player's round before the multiple moves, so nothing anyone does
 * once it is lit can change it. Its chances are made so that cashing out anywhere gives back {@code rtp} of every 100
 * on average (and a fuse goes off at once {@code 100 - rtp} times in 100).
 *
 * <p><b>Every place is its own round</b> in {@link ChanceRounds}: the stake goes in through the play gate as an OPEN
 * round ({@link FuseTicket}); a cash-out closes it and pays in one transaction, and so does the fuse going off (paying
 * nothing). A round still open when something stops the fuse (a restart, a crash, the game switched off) was never
 * cashed out: the stake comes back whole, once. A player who leaves the server while the fuse burns is cashed out at
 * the multiple it had reached; one who leaves before it is lit gets the stake back.
 *
 * <p>This class is the fuse's clock and its rounds. The screen ({@code CreeperFuseMenu}) and the room's show (a bar
 * over everyone in the Parlor, and a creeper over the machine that swells as the fuse burns: {@link FuseShow}) only
 * read it.
 */
public final class CreeperFuse implements Game {

    static final String NAME = "Creeper Fuse";

    public static final GameSpec<CreeperFuseSettings> SPEC = new GameSpec<>(CreeperFuseSettings.ID, GameKind.CHANCE,
            CreeperFuseSettings.KEYS, CreeperFuseSettings.defaults(), CreeperFuseSettings::parse, CreeperFuse::new,
            FuseTicket::settleOnExit);

    /** How long the end stands before the next fuse can start. */
    public static final long OVER_MS = 5_000;

    static final Refusal ALREADY_IN = Refusal.of("You're in this fuse already.");
    static final Refusal LIT = Refusal.of("The fuse is lit. You can put in for the next one when it is over.");

    /** Where the fuse is. */
    public enum Phase {
        /** Nobody has put in: the next fuse is ready. */
        IDLE,
        /** Taking put-ins: the first one started the wait. */
        GATHERING,
        /** Lit: the multiple is climbing. */
        LIT,
        /** It went off (or burned out at the top): the end stands for a few seconds. */
        OVER
    }

    /** One player's place in the fuse. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final boolean money;
        private final CreeperFuseSettings.Odds odds;
        private FuseTicket ticket;
        private ChanceRounds.Round round;
        /** Cashed out at (hundredths), 0 while still in; -1 once the fuse went off with them in. */
        private int out;
        private int paid;

        private Entry(UUID id, String name, boolean money, CreeperFuseSettings.Odds odds, FuseTicket ticket,
                      ChanceRounds.Round round) {
            this.id = id;
            this.name = name;
            this.money = money;
            this.odds = odds;
            this.ticket = ticket;
            this.round = round;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean money() {
            return money;
        }

        public int stake() {
            return ticket.stake();
        }

        /** The auto cash-out (hundredths), or 0 for none. */
        public int auto() {
            return ticket.auto();
        }

        /** The most this place can cash out at (hundredths): the top, or where its pay reaches the cap. */
        public int top() {
            return odds.top();
        }

        /** Still in: lit or waiting, not cashed out, not gone off. */
        public boolean in() {
            return out == 0;
        }

        /** Cashed out at (hundredths), or 0. */
        public int cashedAt() {
            return Math.max(0, out);
        }

        /** Still in when the fuse went off. */
        public boolean caught() {
            return out < 0;
        }

        /** What it was paid. */
        public int paid() {
            return paid;
        }

        /** What cashing out at {@code m} would pay it. */
        public int payAt(int m) {
            return FuseMath.payout(stake(), Math.min(m, top()), odds.cap());
        }
    }

    private final GameContext ctx;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    /** What a place pays at each dollar stake asked for so far: worked out once and kept. */
    private final Map<String, Optional<CreeperFuseSettings.Odds>> moneyOdds = new ConcurrentHashMap<>();
    private Phase phase = Phase.IDLE;
    private long gatherUntil;
    private int shownSeconds = -1;
    /** The fuse being burned: its settings (one snapshot), its seed, where it goes off, when it was lit. */
    private CreeperFuseSettings burning;
    private long fuseSeed;
    private int boomAt;
    private long litAt;
    private long overUntil;
    /** The last fuse's end, in hundredths ({@code > top}: it burned out at the top), or 0 before the first. */
    private int lastEnd;
    private boolean lastBurnedOut;
    /** A test's own seed for the next fuse, or {@code null} (a random one, as in play). */
    private Long nextSeed;
    /** The room's show, or {@code null} (the framework's tests: no server). */
    private FuseShow show;

    public CreeperFuse(GameContext ctx) {
        this.ctx = ctx;
    }

    /** The service this game runs in. */
    public GamesService games() {
        return ctx.games();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public CreeperFuseSettings settings() {
        return games().settings(SPEC);
    }

    /** For tests: the next fuse is drawn from {@code seed} (nothing in play calls this). */
    public void seedNextFuse(long seed) {
        this.nextSeed = seed;
    }

    // ---- the Game -----------------------------------------------------------------------------------------

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
        return TokenService.Source.ARCADE_CREEPER_FUSE;
    }

    @Override
    public boolean configEnabled() {
        CreeperFuseSettings s = settings();
        return s.enabled() && !s.odds().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Put in, then watch the creeper's fuse burn.",
                "The multiple climbs from 1.00×, faster and faster.",
                "Cash out any time: your stake times the multiple.",
                "When the creeper goes off, anyone still in gets nothing.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        CreeperFuseSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        lore.add("&7Cashing out anywhere " + RtpLimits.playerLine(s.rtpFraction()) + ".");
        lore.add("&e" + s.dailyLimit() + " fuses a day");
        List<Integer> open = s.open();
        String range = open.isEmpty() ? "no" : open.size() == 1 ? Integer.toString(open.get(0))
                : open.get(0) + " to " + open.get(open.size() - 1);
        return Menus.icon(Material.CREEPER_HEAD, "&aCreeper Fuse &7- " + range + " tokens in",
                lore.toArray(new String[0]));
    }

    /** The machine's tap, or {@code /hcm play creeper_fuse}: the screen (a player with a place always gets it). */
    @Override
    public void open(Player player, Runnable back) {
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.chance.CreeperFuseMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void start() {
        reset();
        if (ctx.plugin() != null) {
            show = new FuseShow(ctx.plugin(), this);
            games().every(this, 1, 1, this::tick);
        }
    }

    /**
     * Stop (a reload, a switch-off, the server stopping): every place still in, waiting or lit, gets its stake back
     * now ("the fuse was put out"), once; a place already cashed out was paid when it was.
     */
    @Override
    public void stop() {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.in() && e.round != null) {
                FuseTicket t = e.ticket.ended(FuseMath.ONE); // as a cash-out at 1.00x: exactly the stake back
                if (games().rounds().close(e.round, e.stake(), t.data())) {
                    games().notice(e.id, "&7" + NAME + ": the fuse was put out before it went. &fYour "
                            + amount(e.money, e.stake()) + " back.", games().online(e.id) == null);
                }
                e.round = null;
            }
        }
        if (show != null) {
            show.clear();
        }
        reset();
        changed();
    }

    /**
     * A player left the server: before the fuse is lit their place is dropped (the framework's exit rule gives the
     * stake back); while it burns they are cashed out at the multiple it has reached.
     */
    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        screens.remove(id);
        if (show != null) {
            show.forget(id);
        }
        Entry e = entries.get(id);
        if (e == null) {
            return;
        }
        if (phase == Phase.GATHERING) {
            entries.remove(id);
            if (entries.isEmpty()) {
                reset();
            }
            changed();
        } else if (phase == Phase.LIT) {
            burn(now());
            if (e.in()) {
                cashOut(e, Math.min(multiple(), e.top()));
            }
        }
    }

    @Override
    public List<String> oddsLines() {
        CreeperFuseSettings s = settings();
        if (s.odds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("&6" + NAME + " &7— cashing out anywhere " + RtpLimits.playerLine(s.rtpFraction()) + " · "
                + s.dailyLimit() + " fuses a day · goes off at once " + RtpLimits.tenthPercent(1 - s.rtpFraction())
                + "% of the time");
        for (CreeperFuseSettings.Odds o : s.odds()) {
            out.add("&7  " + o.stake() + " in: &f" + RtpLimits.tenthPercent(s.rtpFraction()) + "% &7(at least "
                    + RtpLimits.tenthPercent(o.lowest()) + "% with whole tokens), cashes out by itself at "
                    + FuseMath.times(o.top()));
        }
        for (int stake : s.stakes()) {
            if (s.odds(stake) == null) {
                out.add("&7  " + stake + " in: off (whole-token pay could give back under 85 of every 100)");
            }
        }
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        feed(out, settings());
    }

    /** Its {@code /api/arcade} entry: every stake's return, the top and how the fuse's chances are made. */
    static void feed(FeedWriter out, CreeperFuseSettings s) {
        if (s.odds().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        for (CreeperFuseSettings.Odds o : s.odds()) {
            rtp.put(o.stake(), s.rtpFraction());
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("maxMultiplier", s.top() / 100.0);
        extra.put("instantBoom", 1 - s.rtpFraction());
        extra.put("chanceStillBurningPastM", "rtp / m");
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), List.of(),
                "Everyone in the Game Parlor in the same fuse: put in, then cash out any time for your stake times the"
                        + " climbing multiple; when the creeper goes off, anyone still in gets nothing. Where it goes"
                        + " off is drawn before it is lit, so that cashing out anywhere gives back the same on"
                        + " average.", extra);
    }

    @Override
    public List<String> statusLines() {
        return List.of(phase.name().toLowerCase(java.util.Locale.ROOT) + ", " + entries.size() + " in"
                + (phase == Phase.LIT ? ", at " + FuseMath.times(multiple()) : ""));
    }

    // ---- what the screen and the show read -------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** Who is in the fuse, in the order they put in. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry entry(UUID id) {
        return entries.get(id);
    }

    /** Seconds until the fuse is lit, or -1 while it isn't waiting. */
    public int secondsLeft() {
        return phase != Phase.GATHERING ? -1 : (int) Math.max(0, (gatherUntil - now() + 999) / 1000);
    }

    /** The multiple now, in hundredths: climbing while lit, where it ended once over, 1.00x otherwise. */
    public int multiple() {
        if (phase == Phase.LIT) {
            return FuseMath.climb(now() - litAt, burning.doubleMs(), burning.top());
        }
        if (phase == Phase.OVER) {
            return lastBurnedOut ? burning.top() : lastEnd;
        }
        return FuseMath.ONE;
    }

    /** How far the multiple is to the top, 0 to 1 (the creeper's swell, the bar). */
    public double progress() {
        if (burning == null || phase == Phase.IDLE || phase == Phase.GATHERING) {
            return 0;
        }
        double span = Math.log(burning.top() / 100.0);
        return span <= 0 ? 1 : Math.min(1, Math.max(0, Math.log(multiple() / 100.0) / span));
    }

    /** Whether the last fuse burned out at the top (rather than going off). */
    public boolean burnedOut() {
        return lastBurnedOut;
    }

    /** The last fuse's end in hundredths (where it went off), or 0 before the first. */
    public int lastEnd() {
        return lastEnd;
    }

    /** Run {@code refresh} whenever the fuse changes (a screen); {@link #unwatch} when it closes. */
    public void watch(UUID id, Runnable refresh) {
        if (refresh != null) {
            screens.put(id, refresh);
        }
    }

    public void unwatch(UUID id) {
        screens.remove(id);
    }

    /**
     * What a place at {@code stake} pays, in tokens or (the Game Parlor's money play) dollars; {@code null} when that
     * stake isn't offered. Dollars: under the Parlor's biggest payout for that stake, kept only when every cash-out
     * gives back 85 or more of every 100, exactly as a token stake.
     */
    public CreeperFuseSettings.Odds odds(int stake, boolean money) {
        if (stake <= 0) {
            return null;
        }
        CreeperFuseSettings s = settings();
        if (!money) {
            return s.odds(stake);
        }
        int cap = games().parlor().rules().capFor(stake);
        String key = stake + ":" + cap + ":" + s.rtp() + ":" + s.top();
        return moneyOdds.computeIfAbsent(key, k -> {
            CreeperFuseSettings.Odds o = CreeperFuseSettings.odds(s.ratio(), stake, cap, s.top());
            return o.lowest() >= RtpLimits.MIN - 1e-12 ? Optional.of(o) : Optional.empty();
        }).orElse(null);
    }

    /** Fuses {@code player} may still put in for today, or -1 if they can't be counted right now. */
    public int fusesLeft(Player player) {
        try {
            int played = games().dao().playsToday(player.getUniqueId(), id(), games().clock().dayKey());
            return Math.max(0, settings().dailyLimit() - played);
        } catch (java.sql.SQLException | RuntimeException e) {
            return -1;
        }
    }

    // ---- putting in ----------------------------------------------------------------------------------------

    /**
     * Put in for the next fuse: {@code stake} in tokens or (in the Parlor, with the permission) dollars, with an auto
     * cash-out at {@code auto} (hundredths, 0 for none). The stake goes in now, through the play gate, as an open
     * round; the first put-in starts the wait for the others.
     *
     * @return false when it was refused (the player has been told) and nothing moved
     */
    public boolean enter(Player player, int stake, boolean money, int auto) {
        GamesService games = games();
        UUID id = player.getUniqueId();
        if (phase == Phase.LIT || phase == Phase.OVER) {
            games.tell(player, LIT);
            return false;
        }
        if (entries.containsKey(id)) {
            games.tell(player, ALREADY_IN);
            return false;
        }
        CreeperFuseSettings.Odds odds = odds(stake, money);
        if (odds == null) {
            games.tell(player, Refusal.CLOSED);
            return false;
        }
        FuseTicket ticket = FuseTicket.entry(stake, odds.cap(), odds.top(), validAuto(auto, odds));
        // a place of theirs left open from before (a crash) is finished first, by its own data: the stake back
        ChanceRounds.Round old = games.rounds().openRound(id, id());
        if (old != null) {
            games.rounds().finishNow(old);
        }
        ChanceRounds.Round round = games.rounds().open(player, this, stake, ticket.data(), money);
        if (round == null) {
            return false; // refused, and told why
        }
        entries.put(id, new Entry(id, player.getName(), money, odds, ticket, round));
        if (phase == Phase.IDLE) {
            phase = Phase.GATHERING;
            burning = settings();
            gatherUntil = now() + burning.gatherSeconds() * 1000L;
            shownSeconds = -1;
        }
        changed();
        return true;
    }

    /** An auto cash-out a place can have: above 1.00x and under its own top, or 0 (none). */
    private static int validAuto(int auto, CreeperFuseSettings.Odds odds) {
        return auto > FuseMath.ONE && auto < odds.top() ? auto : 0;
    }

    /**
     * Change {@code player}'s auto cash-out (hundredths, 0 for none) while they are still in, waiting or lit. It is
     * written to their round before it counts.
     *
     * @return whether it changed
     */
    public boolean setAuto(Player player, int auto) {
        Entry e = entries.get(player.getUniqueId());
        if (e == null || !e.in() || e.round == null || (phase != Phase.GATHERING && phase != Phase.LIT)) {
            return false;
        }
        if (phase == Phase.LIT) {
            burn(now()); // anything due before this click happens first
            if (!e.in()) {
                return false;
            }
        }
        int a = validAuto(auto, e.odds);
        if (a == e.ticket.auto()) {
            return false;
        }
        FuseTicket t = e.ticket.withAuto(a);
        ChanceRounds.Round stepped = games().rounds().step(e.round, t.data());
        if (stepped == null) {
            gone(e);
            return false;
        }
        e.round = stepped;
        e.ticket = t;
        changed();
        return true;
    }

    /**
     * {@code player} taps Cash out: anything already due is settled first (the fuse may just have gone off, or their
     * auto cash-out come up), then they are paid their stake times the multiple now.
     *
     * @return whether they were cashed out by this tap
     */
    public boolean cashOut(Player player) {
        if (phase != Phase.LIT) {
            return false;
        }
        burn(now());
        Entry e = entries.get(player.getUniqueId());
        if (e == null || !e.in() || phase != Phase.LIT) {
            return false;
        }
        return cashOut(e, Math.min(multiple(), e.top()));
    }

    // ---- the clock -------------------------------------------------------------------------------------------

    /** Every tick while the game is open: the wait, the fuse, the end. */
    public void tick() {
        long now = now();
        switch (phase) {
            case GATHERING -> {
                dropLeavers();
                if (entries.isEmpty()) {
                    reset();
                    changed();
                } else if (now >= gatherUntil) {
                    light();
                } else if (secondsLeft() != shownSeconds) {
                    shownSeconds = secondsLeft();
                    changed(); // the countdown
                }
            }
            case LIT -> burn(now);
            case OVER -> {
                if (now >= overUntil) {
                    reset();
                    changed();
                }
            }
            default -> {
            }
        }
        if (show != null) {
            show.tick();
        }
    }

    /** Places whose player is gone before the fuse is lit: the framework finishes their round (the stake back). */
    private void dropLeavers() {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (games().online(e.id) == null) {
                games().rounds().finishNow(e.round);
                entries.remove(e.id);
                changed();
            }
        }
    }

    /**
     * The wait is over: draw where the fuse goes off, write it into every place's round, then light it. A place whose
     * round was finished somewhere else meanwhile is out (it was paid there, once); with nobody left, no fuse.
     */
    private void light() {
        CreeperFuseSettings s = burning != null ? burning : settings();
        burning = s;
        fuseSeed = nextSeed != null ? nextSeed : ThreadLocalRandom.current().nextLong();
        nextSeed = null;
        boomAt = FuseMath.boomAt(fuseSeed, s.ratio(), s.top());
        for (Entry e : new ArrayList<>(entries.values())) {
            FuseTicket t = e.ticket.lit(fuseSeed, boomAt);
            ChanceRounds.Round stepped = games().rounds().step(e.round, t.data());
            if (stepped == null) {
                entries.remove(e.id);
                continue;
            }
            e.round = stepped;
            e.ticket = t;
        }
        if (entries.isEmpty()) {
            reset();
            changed();
            return;
        }
        phase = Phase.LIT;
        litAt = now();
        if (show != null) {
            show.lit();
        }
        changed();
        burn(litAt); // a fuse that goes off at once goes off now
    }

    /**
     * The fuse up to {@code now}: each place's auto cash-out (or its own top) that has come up, below where it goes
     * off, is paid at exactly that multiple; then, if the climb has reached where it goes off, it goes off; at the top,
     * it burns out.
     */
    private void burn(long now) {
        if (phase != Phase.LIT) {
            return;
        }
        int m = FuseMath.climb(now - litAt, burning.doubleMs(), burning.top());
        for (Entry e : new ArrayList<>(entries.values())) {
            if (!e.in()) {
                continue;
            }
            int target = e.auto() > 0 ? Math.min(e.auto(), e.top()) : e.top();
            if (target <= m && target < boomAt) {
                cashOut(e, target);
            }
        }
        // (the climb itself repaints nothing here: each screen paints the multiple on its own ticker)
        if (m >= boomAt) {
            end(false);
        } else if (m >= burning.top()) {
            end(true);
        }
    }

    /** Pay {@code e} for a cash-out at {@code m}, once. */
    private boolean cashOut(Entry e, int m) {
        FuseTicket t = e.ticket.ended(m);
        int pay = t.payout();
        if (!games().rounds().close(e.round, pay, t.data())) {
            gone(e);
            return false;
        }
        e.ticket = t;
        e.round = null;
        e.out = m;
        e.paid = pay;
        String line = "&a" + NAME + ": cashed out at " + FuseMath.times(m) + ". &6" + amount(e.money, e.stake())
                + " in → " + amount(e.money, pay) + " back&a.";
        if (pay <= e.stake()) {
            line = "&f" + NAME + ": cashed out at " + FuseMath.times(m) + ". Your " + amount(e.money, pay) + " back.";
        }
        games().notice(e.id, line, games().online(e.id) == null);
        Player p = games().online(e.id);
        if (p != null && ctx.plugin() != null) {
            if (pay > e.stake()) {
                com.dierks.homecraft.util.Sounds.won(p);
            } else {
                com.dierks.homecraft.util.Sounds.received(p);
            }
        }
        changed();
        return true;
    }

    /** A place whose round was finished somewhere else (the sweep): out of the fuse, paid there once. */
    private void gone(Entry e) {
        e.round = null;
        e.out = Math.max(e.out, 1);
        entries.remove(e.id);
        changed();
    }

    /** The fuse went off (or burned out at the top): everyone still in is settled, once. */
    private void end(boolean burnedOut) {
        lastBurnedOut = burnedOut;
        lastEnd = burnedOut ? burning.top() : boomAt;
        for (Entry e : new ArrayList<>(entries.values())) {
            if (!e.in()) {
                continue;
            }
            if (burnedOut) {
                cashOut(e, e.top()); // never reached: each place cashes out at its own top on the way
                continue;
            }
            FuseTicket t = e.ticket.ended(0);
            if (games().rounds().close(e.round, 0, t.data())) {
                games().notice(e.id, "&7" + NAME + ": it went off at " + FuseMath.times(boomAt)
                        + ". No win this time.", games().online(e.id) == null);
                Player p = games().online(e.id);
                if (p != null && ctx.plugin() != null) {
                    com.dierks.homecraft.util.Sounds.miss(p);
                }
            }
            e.ticket = t;
            e.round = null;
            e.out = -1;
        }
        phase = Phase.OVER;
        overUntil = now() + OVER_MS;
        if (show != null) {
            show.over(burnedOut);
        }
        changed();
    }

    private void reset() {
        phase = Phase.IDLE;
        entries.clear();
        shownSeconds = -1;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            quietly(r);
        }
        if (show != null) {
            show.changed();
        }
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // one screen's trouble is never the fuse's
        }
    }

    /** "5 tokens" or "$5". */
    public static String amount(boolean money, int n) {
        return money ? Parlor.dollars(n) : n + " token" + (n == 1 ? "" : "s");
    }

    /** For the show and the screens: whether {@code player} stands in the Game Parlor. */
    boolean inParlor(UUID player) {
        return games().inParlor(player);
    }

    /** A line for the bar over the room. */
    String barLine(UUID viewer) {
        Entry e = entries.get(viewer);
        return switch (phase) {
            case IDLE -> "&a" + NAME + " &7- put in at the machine";
            case GATHERING -> "&a" + NAME + " &7- lit in &f" + secondsLeft() + "s &7· " + entries.size() + " in";
            case LIT -> "&a" + NAME + " &f" + FuseMath.times(multiple())
                    + (e == null ? "" : e.in() ? " &7· cash out: &6" + amount(e.money, e.payAt(multiple()))
                    : e.cashedAt() > 0 ? " &7· you cashed out at " + FuseMath.times(e.cashedAt()) : "");
            case OVER -> lastBurnedOut ? "&a" + NAME + " &7- it fizzled out at &f" + FuseMath.times(lastEnd)
                    : "&c" + NAME + " &7- it went off at &f" + FuseMath.times(lastEnd);
        };
    }

    /** Tell a player in chat (the screen's own refusals). */
    void say(Player p, String line) {
        p.sendMessage(Text.of(line));
    }
}
