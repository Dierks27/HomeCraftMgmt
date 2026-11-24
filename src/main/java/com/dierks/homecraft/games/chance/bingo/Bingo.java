package com.dierks.homecraft.games.chance.bingo;

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
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bingo Night (0.57.0): the Game Parlor's bingo, played together. Classic 75-ball: each card is five columns of five
 * numbers, B I N G O, with a free square in the middle ({@link BingoCard}).
 *
 * <p><b>One round at a time, everyone in it together.</b> The first card bought starts a short wait
 * ({@code gather_seconds}) in which anyone may buy cards for the same round: one to {@value BingoTicket#MAX_CARDS}
 * each, at the card's price in tokens or, with the Parlor's money play, in dollars. If every player in it taps Start
 * now, the wait is skipped. Then the numbers are called, one every {@code call_seconds}: in chat's action bar with a
 * sound, and on every card screen. Cards daub themselves (nothing to miss, nothing to tap in time): the first card to
 * make a line gets the line's part of the prize, and (as shipped) the first to make four corners the bigger part. A
 * card that makes it gets a BINGO! title and fireworks; every card that makes the same pattern on the same call
 * shares that part. The round ends when the last part is won, and the result stands a few seconds.
 *
 * <p><b>The prize</b> is worked out from the number of cards in play alone ({@link BingoPrize}): each card gives back
 * exactly {@code pool / N} on average, inside the 85-95 band, whoever holds the others. A round is never played with
 * fewer than {@code room_cards} cards: the NPC players' and the room's own make it up, so a player alone still plays a
 * real round (not a sure but smaller return).
 *
 * <p><b>Every player's cards are one round</b> in {@link ChanceRounds}: what they cost goes in through the play gate
 * when they are bought (the permission, Take a break, the day's limits, the balance), as an OPEN round whose data
 * carries each card's seed ({@link BingoTicket}). When the wait is over ONE seed is drawn for the round: the order of
 * the calls, and so who makes what, is decided, every player's prize is written into their round and every round is
 * settled at once, before the first number is called. Nothing about the calls exists while cards are still sold. A
 * round left open (a crash, a reload while cards were sold) is finished from its own data: with no call seed the
 * cards are given back, whole; with one, it pays what was written.
 *
 * <p><b>NPC players</b> ({@link #enterNpc}) take cards for fun while a round gathers with a real player in it who has
 * NPCs on, and fill the room up to {@code room_cards} at the start. An NPC's cards have no round: nothing is put in,
 * nothing is paid, they are play chips. Their wins never take a real player's prize: the prize is sized for every
 * card in play, an NPC's included, so an NPC's card adds to it exactly what it wins on average, and a player's
 * expected return is the same as with no NPC there at all (the proof is {@link BingoPrize}'s). What an NPC wins is
 * only cheered. The room's own cards (when nobody wants NPCs) are the same, without a name. A round with no real
 * player in it never runs.
 */
public final class Bingo implements Game {

    static final String NAME = "Bingo Night";

    public static final GameSpec<BingoSettings> SPEC = new GameSpec<>(BingoSettings.ID, GameKind.CHANCE,
            BingoSettings.KEYS, BingoSettings.defaults(), BingoSettings::parse, Bingo::new, BingoTicket::settleOnExit);

    /** How long the result stands after the last part is won. */
    public static final long DONE_MS = 6_000;
    /** The most NPCs in a round. */
    public static final int MAX_NPCS = NpcCrowd.MAX_IN_GAME;
    /** The room's own cards' holder (never a player's UUID: a version-0 UUID). */
    public static final UUID ROOM = new UUID(0L, 0xB1B60L);

    static final Refusal ALREADY_IN = Refusal.of("You have your cards for this round already.");
    static final Refusal ROUND_ON = Refusal.of("A round is being called. You can buy cards for the next one when it"
            + " is over.");
    static final Refusal FULL = Refusal.of("This round has all the cards it can take. Buy fewer, or wait for the"
            + " next one.");

    /** Where the round is. */
    public enum Phase {
        /** Nobody has bought a card: no round. */
        IDLE,
        /** Cards are being bought: the first one started the wait. */
        GATHERING,
        /** The numbers are being called: every round was decided and paid when the calling started. */
        CALLING,
        /** The last part is won: the result stands a few seconds. */
        DONE
    }

    /** Who holds cards in the round. */
    public enum Holder {
        PLAYER, NPC, ROOM
    }

    /** One holder's cards in the round. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final Holder holder;
        private final boolean money;
        private final List<BingoCard> cards = new ArrayList<>();
        private BingoTicket ticket;
        /** Its open round until the round is drawn, then {@code null} (settled). Always {@code null} but a player's. */
        private ChanceRounds.Round round;
        private boolean startNow;
        /** Each card's place among the cards in play, once drawn. */
        private int[] places = new int[0];
        /** What its cards made, once drawn (in its own money; play chips for an NPC or the room). */
        private int pays;

        private Entry(UUID id, String name, Holder holder, boolean money) {
            this.id = id;
            this.name = name;
            this.holder = holder;
            this.money = money;
            this.startNow = holder != Holder.PLAYER; // only a player ever holds the start up
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Holder holder() {
            return holder;
        }

        /** Whether its cards were bought for dollars. */
        public boolean money() {
            return money;
        }

        /** Its cards. */
        public List<BingoCard> cards() {
            return List.copyOf(cards);
        }

        /** What its cards cost (0 for an NPC or the room). */
        public int in() {
            return ticket == null ? 0 : ticket.in();
        }

        public boolean startNow() {
            return startNow;
        }

        /** Its card {@code k}'s place among the cards in play, or -1 before the round is drawn. */
        public int place(int k) {
            return k < places.length ? places[k] : -1;
        }

        /** What its cards made (known from the draw; told as each part is won). */
        public int pays() {
            return pays;
        }
    }

    private final GameContext ctx;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    private Phase phase = Phase.IDLE;
    private long gatherUntil;
    private int shownSeconds = -1;
    private long lastSecond;
    /** The round being called: the draw, its cards in play and each card's prize. */
    private BingoDraw draw;
    private int[] rank;
    private final List<BingoCard> inPlay = new ArrayList<>();
    private final List<Entry> heldBy = new ArrayList<>();
    private int[] lineShare = new int[0];
    private int[] cornersShare = new int[0];
    private int linePart;
    private int cornersPart;
    private int lineDollars;
    private int cornersDollars;
    private long startedAt;
    private int called;
    private long doneUntil;
    private boolean told;
    /** A test's own seed for the next round, or {@code null} (a random one, as in play). */
    private Long nextSeed;

    public Bingo(GameContext ctx) {
        this.ctx = ctx;
    }

    public GamesService games() {
        return ctx.games();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    /** The live settings (read on every use). */
    public BingoSettings settings() {
        return games().settings(SPEC);
    }

    /** For tests: the next round is drawn from {@code seed} (nothing in play calls this). */
    public void seedNextRound(long seed) {
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
        return TokenService.Source.ARCADE_BINGO;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    @Override
    public List<String> rules() {
        BingoSettings s = settings();
        return List.of("Buy 1 to 4 cards, then the numbers are called.",
                "Your cards daub themselves. Make a line" + (s.cornersShare() > 0 ? ", then four corners." : "."),
                "The first card to make it shares the prize with any that make it on the same call.",
                "The prize grows with every card in play.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        BingoSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        double low = lowestRtp();
        if (!Double.isNaN(low)) {
            lore.add("&7Each card " + RtpLimits.playerLine(low) + ".");
        }
        lore.add("&e" + s.dailyLimit() + " rounds a day");
        return Menus.icon(Material.FILLED_MAP, "&aBingo Night &7- " + s.cardPrice() + " tokens a card",
                lore.toArray(new String[0]));
    }

    /** The machine's tap or {@code /hcm play bingo}: the cards screen. */
    @Override
    public void open(Player player, Runnable back) {
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.chance.BingoMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void start() {
        reset();
        if (ctx.plugin() != null) {
            games().every(this, 1, 1, this::tick);
        }
    }

    /**
     * Stop (a reload, a switch-off, the server stopping): a round being called was paid when the calling started, so
     * its players are told how it came out now. Cards still being bought are OPEN rounds with nothing drawn: the
     * framework finishes them, and the cards are given back.
     */
    @Override
    public void stop() {
        if ((phase == Phase.CALLING || phase == Phase.DONE) && !told) {
            tellResults();
        }
        reset();
        changed();
    }

    /** A player left: out of a round still gathering (the framework finishes their round: the cards come back). */
    @Override
    public void onQuit(Player player) {
        screens.remove(player.getUniqueId());
        if (phase == Phase.GATHERING && entries.remove(player.getUniqueId()) != null) {
            if (!anyReal()) {
                reset(); // a round of NPCs alone never runs
            }
            changed();
        }
    }

    @Override
    public List<String> statusLines() {
        return List.of(phase.name().toLowerCase(java.util.Locale.ROOT) + ", " + entries.size() + " holding cards, "
                + cardsNow() + " cards" + (phase == Phase.CALLING ? ", call " + called : ""));
    }

    @Override
    public List<String> oddsLines() {
        BingoSettings s = settings();
        List<String> out = new ArrayList<>();
        out.add("&6" + NAME + " &7— " + RtpLimits.playerLine(lowestRtp()) + " · " + s.dailyLimit()
                + " rounds a day · " + s.cardPrice() + " tokens ($" + s.cardDollars() + ") a card · at least "
                + s.roomCards() + ", at most " + s.maxCards() + " cards in play:");
        for (int n : tableSizes(s)) {
            int pool = BingoPrize.pool(s.cardPrice(), n, s.rtp());
            out.add("&7  " + n + " cards → a prize of " + pool + " (line " + BingoPrize.line(pool, s.cornersShare())
                    + (s.cornersShare() > 0 ? ", four corners " + BingoPrize.corners(pool, s.cornersShare()) : "")
                    + "): each card &f" + RtpLimits.tenthPercent(BingoPrize.rtp(s.cardPrice(), n, s.rtp())) + "%");
        }
        return out;
    }

    /** The card counts the odds lines and the feed show: the fewest, a few between, the most. */
    static List<Integer> tableSizes(BingoSettings s) {
        Set<Integer> out = new java.util.TreeSet<>();
        for (int n = s.roomCards(); n <= s.maxCards(); n += 4) {
            out.add(n);
        }
        out.add(s.maxCards());
        return List.copyOf(out);
    }

    @Override
    public void feed(FeedWriter out) {
        BingoSettings s = settings();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (int n : tableSizes(s)) {
            rows.add(new FeedWriter.PayRow(s.cardPrice(), n + " cards in play: the prize",
                    BingoPrize.pool(s.cardPrice(), n, s.rtp()), null, null, null));
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        rtp.put(s.cardPrice(), lowestRtp());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("roomCards", s.roomCards());
        extra.put("maxCards", s.maxCards());
        extra.put("cornersShare", s.cornersShare());
        out.chance(SPEC.id(), NAME, List.of(s.cardPrice()), rtp, s.dailyLimit(), rows,
                "75-ball bingo played together in the Clubhouse's Game Parlor. The prize is sized by the cards in"
                        + " play and shared by the cards that make each pattern first, on the same call.", extra);
    }

    /** The lowest exact return of a card in any round this game may run, in tokens. */
    public double lowestRtp() {
        BingoSettings s = settings();
        double low = Double.NaN;
        for (int n = s.roomCards(); n <= s.maxCards(); n++) {
            double r = BingoPrize.rtp(s.cardPrice(), n, s.rtp());
            if (!Double.isNaN(r) && (Double.isNaN(low) || r < low)) {
                low = r;
            }
        }
        return low;
    }

    // ---- what the screens read ----------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** Who holds cards, in the order they came. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry entry(UUID id) {
        return entries.get(id);
    }

    /** Seconds until the calling starts, or -1 when no cards are being sold. */
    public int secondsLeft() {
        return phase != Phase.GATHERING ? -1 : (int) Math.max(0, (gatherUntil - now() + 999) / 1000);
    }

    /** The cards held now (players' and NPCs'; the room's own once drawn). */
    public int cardsNow() {
        int n = 0;
        for (Entry e : entries.values()) {
            n += e.cards.size();
        }
        return n;
    }

    /** The cards the round would be played with if it started now: at least {@code room_cards}. */
    public int cardsInPlay() {
        return phase == Phase.CALLING || phase == Phase.DONE ? inPlay.size()
                : Math.max(settings().roomCards(), cardsNow());
    }

    /** The prize for a round of {@code cards} cards, in tokens or dollars. */
    public int prize(boolean money, int cards) {
        BingoSettings s = settings();
        return Math.max(0, BingoPrize.pool(s.price(money), cards, s.rtp()));
    }

    /** How many numbers have been called. */
    public int called() {
        return phase == Phase.CALLING || phase == Phase.DONE ? called : 0;
    }

    /** The numbers called so far, in order. */
    public int[] callsSoFar() {
        if (draw == null) {
            return new int[0];
        }
        return java.util.Arrays.copyOf(draw.calls(), called());
    }

    /** The round's ranks (on which call each number comes), for daubing what is called so far; {@code null} before. */
    public int[] rank() {
        return rank == null ? null : rank.clone();
    }

    /** Whether the line has been called out yet, and four corners. */
    public boolean lineCalled() {
        return draw != null && called() > draw.lineCall();
    }

    public boolean cornersCalled() {
        return draw != null && draw.corners() && called() > draw.cornersCall();
    }

    /** Whether the round plays for four corners. */
    public boolean playsCorners() {
        return draw != null ? draw.corners() : settings().cornersShare() > 0;
    }

    /** Whether the card at {@code place} made the line (once called out). */
    public boolean madeLine(int place) {
        return lineCalled() && draw.madeLine(place);
    }

    /** Whether the card at {@code place} made four corners (once called out). */
    public boolean madeCorners(int place) {
        return cornersCalled() && draw.madeCorners(place);
    }

    /** What the card at {@code place} was given for the line, and for four corners (in its holder's money). */
    public int lineShare(int place) {
        return place >= 0 && place < lineShare.length ? lineShare[place] : 0;
    }

    public int cornersShare(int place) {
        return place >= 0 && place < cornersShare.length ? cornersShare[place] : 0;
    }

    /** Who holds the card at {@code place}. */
    public Entry holderOf(int place) {
        return place >= 0 && place < heldBy.size() ? heldBy.get(place) : null;
    }

    public void watch(UUID id, Runnable refresh) {
        if (refresh != null) {
            screens.put(id, refresh);
        }
    }

    public void unwatch(UUID id) {
        screens.remove(id);
    }

    /** Rounds {@code player} may still play today, or -1 if they can't be counted right now. */
    public int roundsLeft(Player player) {
        try {
            int played = games().dao().playsToday(player.getUniqueId(), id(), games().clock().dayKey());
            return Math.max(0, settings().dailyLimit() - played);
        } catch (java.sql.SQLException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * The most cards a round may have now: {@code max_cards}, and for a round with dollar cards in it, as many as keep
     * a lone winner's prize inside the Parlor's biggest payout.
     */
    public int mostCards(boolean money) {
        BingoSettings s = settings();
        int most = s.maxCards();
        if (money || anyMoney()) {
            most = Math.min(most, BingoPrize.mostCards(s.cardDollars(), s.rtp(),
                    games().parlor().rules().maxPayout(), s.maxCards()));
        }
        return most;
    }

    private boolean anyMoney() {
        for (Entry e : entries.values()) {
            if (e.money) {
                return true;
            }
        }
        return false;
    }

    // ---- buying cards ---------------------------------------------------------------------------------------

    /**
     * Buy {@code count} cards for the round gathering now (or start one), in tokens or (in the Parlor, with the
     * permission) dollars. What they cost goes in now, through the play gate, as an open round.
     *
     * @return false when it was refused (the player has been told) and nothing moved
     */
    public boolean buy(Player player, int count, boolean money) {
        GamesService games = games();
        UUID id = player.getUniqueId();
        if (phase == Phase.CALLING || phase == Phase.DONE) {
            games.tell(player, ROUND_ON);
            return false;
        }
        if (entries.containsKey(id)) {
            games.tell(player, ALREADY_IN);
            return false;
        }
        if (count < 1 || count > BingoTicket.MAX_CARDS) {
            games.tell(player, Refusal.CLOSED);
            return false;
        }
        if (realCards() + count > mostCards(money)) {
            games.tell(player, FULL);
            return false;
        }
        BingoSettings s = settings();
        long[] seeds = new long[count];
        for (int i = 0; i < count; i++) {
            seeds[i] = ThreadLocalRandom.current().nextLong();
        }
        BingoTicket ticket = BingoTicket.bought(s.price(money), seeds);
        // cards of theirs left open from before (a crash) are finished first, by their own data
        ChanceRounds.Round old = games.rounds().openRound(id, id());
        if (old != null) {
            games.rounds().finishNow(old);
        }
        ChanceRounds.Round round = games.rounds().open(player, this, ticket.in(), ticket.data(), money);
        if (round == null) {
            return false; // refused, and told why
        }
        Entry e = new Entry(id, player.getName(), Holder.PLAYER, money);
        e.ticket = ticket;
        e.round = round;
        e.cards.addAll(List.of(ticket.dealt()));
        entries.put(id, e);
        makeRoom();
        if (phase == Phase.IDLE) {
            phase = Phase.GATHERING;
            gatherUntil = now() + s.gatherSeconds() * 1000L;
            shownSeconds = -1;
        }
        changed();
        return true;
    }

    /** Cards the players hold. */
    private int realCards() {
        int n = 0;
        for (Entry e : entries.values()) {
            n += e.holder == Holder.PLAYER ? e.cards.size() : 0;
        }
        return n;
    }

    /** NPCs give way: while the round has more cards than it may, the last NPC's cards go (then the NPC). */
    private void makeRoom() {
        int most = mostCards(false);
        List<Entry> npcs = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.holder != Holder.PLAYER) {
                npcs.add(e);
            }
        }
        for (int i = npcs.size() - 1; i >= 0 && cardsNow() > most; i--) {
            Entry n = npcs.get(i);
            while (!n.cards.isEmpty() && cardsNow() > most) {
                n.cards.remove(n.cards.size() - 1);
            }
            if (n.cards.isEmpty()) {
                entries.remove(n.id);
            }
        }
    }

    /**
     * An NPC takes {@code count} cards in the round gathering now: only while a real player is in it, once per NPC, at
     * most {@link #MAX_NPCS}, and only into cards the round has room for. Nothing is put in and nothing is paid: its
     * cards are play chips.
     *
     * @param id    the NPC's UUID (never a real player's)
     * @param label its name with its gear and stars
     * @return whether it took any
     */
    public boolean enterNpc(UUID id, String label, int count) {
        if (phase != Phase.GATHERING || !anyReal() || id == null || entries.containsKey(id) || npcCount() >= MAX_NPCS) {
            return false;
        }
        int room = Math.min(Math.min(BingoTicket.MAX_CARDS, count), mostCards(false) - cardsNow());
        if (room <= 0) {
            return false;
        }
        Entry e = new Entry(id, label, Holder.NPC, false);
        for (int i = 0; i < room; i++) {
            e.cards.add(BingoCard.deal(ThreadLocalRandom.current().nextLong()));
        }
        entries.put(id, e);
        changed();
        return true;
    }

    /** Whether a real player is in the round. */
    public boolean anyReal() {
        for (Entry e : entries.values()) {
            if (e.holder == Holder.PLAYER) {
                return true;
            }
        }
        return false;
    }

    public int npcCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            n += e.holder == Holder.NPC ? 1 : 0;
        }
        return n;
    }

    /** {@code player} asks to start now: when every player in it has, the wait is skipped. */
    public void startNow(Player player) {
        Entry e = entries.get(player.getUniqueId());
        if (e == null || phase != Phase.GATHERING || e.startNow) {
            return;
        }
        e.startNow = true;
        if (everyoneAsked()) {
            startRound();
        } else {
            changed();
        }
    }

    private boolean everyoneAsked() {
        for (Entry e : entries.values()) {
            if (!e.startNow) {
                return false;
            }
        }
        return !entries.isEmpty();
    }

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Every tick while the game is open: the wait, the calls, the result. */
    public void tick() {
        long now = now();
        switch (phase) {
            case GATHERING -> {
                dropLeavers();
                if (!anyReal()) {
                    reset(); // nobody real is left: no round
                    changed();
                } else if (now >= gatherUntil || everyoneAsked()) {
                    startRound();
                } else {
                    if (now - lastSecond >= 1000) {
                        lastSecond = now;
                        npcSecond();
                    }
                    if (secondsLeft() != shownSeconds) {
                        shownSeconds = secondsLeft();
                        changed(); // the countdown
                    }
                }
            }
            case CALLING -> {
                long due = Math.min(BingoCard.BALLS, (now - startedAt) / settings().callMs() + 1);
                boolean moved = false;
                while (called < due && called <= draw.lastCall()) {
                    call(called);
                    called++;
                    moved = true;
                }
                if (called > draw.lastCall() && now - startedAt >= (long) called * settings().callMs()) {
                    phase = Phase.DONE;
                    doneUntil = now + DONE_MS;
                    tellResults();
                    moved = true;
                }
                if (moved) {
                    changed();
                }
            }
            case DONE -> {
                if (now >= doneUntil) {
                    reset();
                    changed();
                }
            }
            default -> {
            }
        }
    }

    /** Players gone before the round is drawn (off the server): their round is finished by its exit rule. */
    private void dropLeavers() {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.holder == Holder.PLAYER && games().online(e.id) == null) {
                games().rounds().finishNow(e.round); // if their quit hasn't already: paid once, there or here
                entries.remove(e.id);
                screens.remove(e.id);
                changed();
            }
        }
    }

    /**
     * Once a second while a round gathers with real players in it who all have NPCs on: now and then an NPC takes a
     * few cards (how many by its personality).
     */
    private void npcSecond() {
        CpuPlayers cpu = CpuPlayers.of(games());
        if (cpu == null || secondsLeft() < 4 || npcCount() >= MAX_NPCS || !welcome(cpu)
                || cpu.rng().nextInt(100) >= 25) {
            return;
        }
        List<CpuPlayer> free = NpcCrowd.pick(cpu.roster(), entries.keySet(), 1, cpu.rng());
        if (!free.isEmpty()) {
            CpuPlayer c = free.get(0);
            enterNpc(c.id(), cpu.label(c), npcCards(c));
        }
    }

    /** How many cards an NPC takes, by its personality: a careful one 1, a bold one 4, the others 2. */
    static int npcCards(CpuPlayer c) {
        return switch (c.personality()) {
            case CAREFUL -> 1;
            case BOLD -> 4;
            default -> 2;
        };
    }

    /** Whether every real player in the round has NPCs on. */
    private boolean welcome(CpuPlayers cpu) {
        List<UUID> reals = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.holder == Holder.PLAYER) {
                reals.add(e.id);
            }
        }
        return NpcCrowd.welcome(reals, cpu::wants);
    }

    /**
     * The wait is over: fill the room up to {@code room_cards} (NPCs, when every player wants them, else the room's
     * own cards), draw the round's one seed, work out every card's prize, write it into every player's round and
     * settle them all, before the first number is called.
     */
    private void startRound() {
        GamesService games = games();
        BingoSettings s = settings();
        dropLeavers();
        if (!anyReal()) {
            reset();
            changed();
            return;
        }
        fillRoom(s);
        int most = mostCards(false);
        if (realCards() > most) {
            callOff(); // the Parlor's biggest payout went down under a gathering round: every card back
            return;
        }
        makeRoom();
        trimRoom(most);
        inPlay.clear();
        heldBy.clear();
        for (Entry e : entries.values()) {
            e.places = new int[e.cards.size()];
            for (int k = 0; k < e.cards.size(); k++) {
                e.places[k] = inPlay.size();
                inPlay.add(e.cards.get(k));
                heldBy.add(e);
            }
        }
        int n = inPlay.size();
        long seed = nextSeed != null ? nextSeed : ThreadLocalRandom.current().nextLong();
        nextSeed = null;
        draw = BingoDraw.of(inPlay, seed, s.cornersShare() > 0);
        rank = draw.rank();
        int poolTokens = Math.max(0, BingoPrize.pool(s.cardPrice(), n, s.rtp()));
        int poolDollars = Math.max(0, BingoPrize.pool(s.cardDollars(), n, s.rtp()));
        int share = draw.corners() ? s.cornersShare() : 0;
        linePart = BingoPrize.line(poolTokens, share);
        cornersPart = BingoPrize.corners(poolTokens, share);
        lineDollars = BingoPrize.line(poolDollars, share);
        cornersDollars = BingoPrize.corners(poolDollars, share);
        // each card's share in its holder's money: dollars for cards bought for dollars, else tokens (play chips for
        // an NPC's or the room's)
        int[][] tokens = BingoPrize.cardShares(draw, n, linePart, cornersPart);
        int[][] dollars = BingoPrize.cardShares(draw, n, lineDollars, cornersDollars);
        lineShare = new int[n];
        cornersShare = new int[n];
        for (int place = 0; place < n; place++) {
            int[][] in = heldBy.get(place).money ? dollars : tokens;
            lineShare[place] = in[0][place];
            cornersShare[place] = in[1][place];
        }
        for (Entry e : new ArrayList<>(entries.values())) {
            e.pays = 0;
            for (int place : e.places) {
                e.pays += lineShare[place] + cornersShare[place];
            }
            if (e.holder != Holder.PLAYER) {
                continue;
            }
            BingoTicket drawn = e.ticket.drawn(seed, n, e.pays);
            ChanceRounds.Round stepped = games.rounds().step(e.round, drawn.data());
            if (stepped != null) {
                // a settle that can't be written leaves the round open with its prize in it: the exit rule pays it
                games.rounds().close(stepped, drawn.payout(), drawn.data());
            }
            e.ticket = drawn;
            e.round = null;
        }
        startedAt = now();
        called = 0;
        told = false;
        phase = Phase.CALLING;
        changed();
    }

    /** Up to {@code room_cards}: NPCs take what's missing when every player wants them, the room the rest. */
    private void fillRoom(BingoSettings s) {
        int missing = s.roomCards() - cardsNow();
        CpuPlayers cpu = CpuPlayers.of(games());
        if (missing > 0 && cpu != null && welcome(cpu)) {
            for (CpuPlayer c : NpcCrowd.pick(cpu.roster(), entries.keySet(), MAX_NPCS - npcCount(), cpu.rng())) {
                if (missing <= 0) {
                    break;
                }
                int k = Math.min(missing, Math.max(npcCards(c), (missing + 1) / 2));
                if (enterNpc(c.id(), cpu.label(c), k)) {
                    missing = s.roomCards() - cardsNow();
                }
            }
        }
        if (missing > 0) {
            Entry room = new Entry(ROOM, "the room's cards", Holder.ROOM, false);
            for (int i = 0; i < missing; i++) {
                room.cards.add(BingoCard.deal(ThreadLocalRandom.current().nextLong()));
            }
            entries.put(ROOM, room);
        }
    }

    /** The room's own cards give way too, after the NPCs'. */
    private void trimRoom(int most) {
        Entry room = entries.get(ROOM);
        while (room != null && !room.cards.isEmpty() && cardsNow() > most) {
            room.cards.remove(room.cards.size() - 1);
        }
        if (room != null && room.cards.isEmpty()) {
            entries.remove(ROOM);
        }
    }

    /** No round can be played: every player's cards are given back, and they are told. */
    private void callOff() {
        for (Entry e : entries.values()) {
            if (e.holder == Holder.PLAYER && e.round != null
                    && games().rounds().close(e.round, e.ticket.in(), e.ticket.data())) {
                games().notice(e.id, "&7" + NAME + " couldn't be played this time. &fYour " + amount(e.money,
                        e.ticket.in()) + " back.", games().online(e.id) == null);
            }
        }
        reset();
        changed();
    }

    // ---- the calls --------------------------------------------------------------------------------------------

    /** Call number {@code i} (from 0): to every player in the round, and the moment a part is won. */
    private void call(int i) {
        int number = draw.calls()[i];
        for (Entry e : entries.values()) {
            if (e.holder != Holder.PLAYER) {
                continue;
            }
            Player p = games().online(e.id);
            if (p == null) {
                continue;
            }
            boolean mine = false;
            for (BingoCard c : e.cards) {
                mine |= c.has(number);
            }
            p.sendActionBar(Text.of("&e&l" + BingoCard.ball(number) + (mine ? "  &a✔ on your card" : "")));
            ding(p, mine);
        }
        if (i == draw.lineCall()) {
            moment(draw.lineWinners(), lineShare, "a line");
        }
        if (draw.corners() && i == draw.cornersCall()) {
            moment(draw.cornersWinners(), cornersShare, "four corners");
        }
    }

    /** A part is won: BINGO! for each player who made it, a cheer for each NPC, and every player is told who. */
    private void moment(int[] winners, int[] shares, String what) {
        Map<Entry, Integer> won = new LinkedHashMap<>();
        for (int place : winners) {
            won.merge(heldBy.get(place), shares[place], Integer::sum);
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<Entry, Integer> w : won.entrySet()) {
            Entry e = w.getKey();
            switch (e.holder) {
                case PLAYER -> {
                    names.add("&f" + e.name + " &7(" + amount(e.money, w.getValue()) + ")");
                    Player p = games().online(e.id);
                    if (p != null) {
                        bingo(p, what, amount(e.money, w.getValue()));
                    }
                }
                case NPC -> names.add(e.name + " &7(play chips)");
                default -> names.add("&7one of the room's cards");
            }
        }
        String line = "&6&lBINGO! &e" + Character.toUpperCase(what.charAt(0)) + what.substring(1) + ": "
                + String.join("&7, ", names);
        boolean npcWon = won.keySet().stream().anyMatch(e -> e.holder == Holder.NPC);
        for (Entry e : entries.values()) {
            if (e.holder == Holder.PLAYER) {
                Player p = games().online(e.id);
                if (p != null) {
                    p.sendMessage(Text.of(line));
                    if (npcWon && !won.containsKey(e)) {
                        cheer(p);
                    }
                }
            }
        }
    }

    /** Tell every player how the round came out for them (now, or at their next join). */
    private void tellResults() {
        if (told || draw == null) {
            return;
        }
        told = true;
        for (Entry e : entries.values()) {
            if (e.holder != Holder.PLAYER || e.ticket == null) {
                continue;
            }
            int n = e.cards.size();
            String cards = n + " card" + (n == 1 ? "" : "s");
            String line = e.pays > 0
                    ? "&6" + NAME + ": &fyour " + cards + ": " + amount(e.money, e.ticket.in()) + " in → "
                    + amount(e.money, e.pays) + " back."
                    : "&7" + NAME + ": your " + cards + " made nothing this time (" + amount(e.money, e.ticket.in())
                    + " in).";
            games().notice(e.id, line, games().online(e.id) == null);
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        entries.clear();
        draw = null;
        rank = null;
        inPlay.clear();
        heldBy.clear();
        lineShare = new int[0];
        cornersShare = new int[0];
        called = 0;
        shownSeconds = -1;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // one screen's trouble is never the round's
            }
        }
    }

    /** "5 tokens" or "$5". */
    public static String amount(boolean money, int n) {
        return money ? Parlor.dollars(n) : n + " token" + (n == 1 ? "" : "s");
    }

    /** What the line and four corners pay now for a round of {@code cards} cards, in tokens or dollars. */
    public int[] parts(boolean money, int cards) {
        BingoSettings s = settings();
        int pool = prize(money, cards);
        int share = s.cornersShare();
        return new int[] {BingoPrize.line(pool, share), BingoPrize.corners(pool, share)};
    }

    /** The parts of the round being called (tokens, then dollars). */
    public int[] partsNow(boolean money) {
        return money ? new int[] {lineDollars, cornersDollars} : new int[] {linePart, cornersPart};
    }

    /** The NPCs' and the room's cards are never a player's: who holds this id, for a screen. */
    public static boolean real(UUID id) {
        return id != null && !ROOM.equals(id) && !CpuRoster.isCpu(id);
    }

    // ---- sounds and sparkles (a live server only) -----------------------------------------------------------

    /** A number is called: a bell, brighter when it is on one of your cards. */
    private void ding(Player p, boolean mine) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, mine ? 1.6f : 1.0f);
            if (mine) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the round
        }
    }

    /** Their card made it: BINGO! on the screen, fireworks over them and a fanfare. */
    private void bingo(Player p, String what, String amount) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            p.showTitle(Title.title(Text.of("&6&lBINGO!"), Text.of("&e" + Character.toUpperCase(what.charAt(0))
                    + what.substring(1) + " &7- &6" + amount), Title.Times.times(Duration.ofMillis(200),
                    Duration.ofMillis(2200), Duration.ofMillis(600))));
            Location at = p.getLocation().add(0, 1.2, 0);
            p.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.1f);
            p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f);
            p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.2f);
            p.getWorld().spawnParticle(Particle.FIREWORK, at.clone().add(0, 1.0, 0), 60, 0.6, 0.6, 0.6, 0.12);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.5, 0.8, 0.5, 0.35);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the round
        }
    }

    /** An NPC made it: a little cheer from the room. */
    private void cheer(Player p) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 0.7f, 1.2f);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the round
        }
    }

    /** The UUIDs of everyone holding cards (for tests). */
    Set<UUID> holders() {
        return new HashSet<>(entries.keySet());
    }
}
