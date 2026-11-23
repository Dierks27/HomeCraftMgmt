package com.dierks.homecraft.games.chance.mobraces;

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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob Races: the Game Parlor's model racetrack (the owner: "I hope you make a mini track and you literally see the
 * tiny mobs running on the track!"). Six tiny mobs race round a table in the middle of the room; a player picks one
 * and puts tokens (or, in the Parlor with the money permission, dollars) on it. The owner, after the first races: "gave
 * us real odds and real stats on the mobs. So you could actually win more on longshots and also have a bit of info on
 * the mob so you aren't just betting blindly."
 *
 * <p><b>Each race has its card</b> ({@link RaceCard}): made when the race is open for entries (the first time the
 * board or a screen shows it), and fixed until that race is run. It holds every racer's form today and its exact
 * chance of winning, which come from its stats by one formula; the winner is drawn with exactly those chances. A pick
 * that wins pays what its tile says for that racer and stake ({@link #pay}): a favourite little, a longshot a lot, each
 * as near the game's target return as whole amounts allow. Anything else pays nothing. A new card is made only once a
 * race has been run on the old one.
 *
 * <p><b>One track, one race at a time, everyone in the room in the same race.</b> The first entry starts a short
 * wait ({@code gather_seconds}) in which anyone in the Parlor may enter: one racer and one stake each. If every
 * entrant taps Start now the wait is skipped. Then the race runs ({@code race_seconds}) and the result stands for a
 * few seconds before the track is free again. The game is watched in the room: its screen is only for picking, and
 * closes when the race starts.
 *
 * <p><b>Every entry is its own round</b> in {@link ChanceRounds}: the stake goes in through the play gate when the
 * entry is made (the permission, Take a break, the day's limits, the balance), as an OPEN round whose data carries
 * the pick and what a win pays ({@link RaceTicket}). When entries close, ONE seed is drawn for the race, written into
 * every entrant's round and every round is settled at once: decided and paid before a mob moves, as every game of
 * chance here is. Nothing about the result exists while a pick can still be made. A round left open is finished from
 * its own data: with no race seed the stake comes back whole; with one, it pays what that race pays its pick.
 *
 * <p>This class is the race's clock and its rounds. The track itself (the table, the mobs, the board) is drawn by
 * the Clubhouse, which reads this through {@link #phase()}, {@link #script()} and {@link #raceTick()}, and says once
 * a second that the track is there ({@link #trackSeen()}): with no track, nobody can enter.
 *
 * <p><b>NPC players</b> ({@link #enterNpc}): while a race gathers with a real entrant in it, an NPC or two may enter
 * too, with a pretend stake on a racer of their own. An NPC's entry has no round: nothing is put in, nothing is paid,
 * and it touches neither the race's seed, nor its card, nor any real entry's ticket, so every real entry's chances and
 * pay are what they were (a race's card is fixed whoever else is in). A race with only NPCs in it never runs: when the last
 * real entrant is gone, the race is off and the NPCs with it.
 */
public final class MobRaces implements Game {

    static final String NAME = "Mob Races";

    public static final GameSpec<MobRacesSettings> SPEC = new GameSpec<>(MobRacesSettings.ID, GameKind.CHANCE,
            MobRacesSettings.KEYS, MobRacesSettings.defaults(), MobRacesSettings::parse, MobRaces::new,
            RaceTicket::settleOnExit);

    /** How long the result stands before the track is free again: long enough for the last racer to come home. */
    public static final long RESULT_MS = 6_000;
    /** The track counts as there while the Clubhouse said so this recently. */
    static final long TRACK_MS = 5_000;

    static final Refusal NOT_HERE = Refusal.of("Mob Races is run on the track in the Clubhouse's Game Parlor.");
    static final Refusal NO_TRACK = Refusal.of("Mob Races has no track here yet.");
    static final Refusal ALREADY_IN = Refusal.of("You're in this race already.");
    static final Refusal RACE_ON = Refusal.of("The race is on. You can enter the next one when it is over.");

    /** Where the track is. */
    public enum Phase {
        /** Nobody has entered: the track is free. */
        IDLE,
        /** Taking entries: the first one started the wait. */
        GATHERING,
        /** The race is being run: every entry was decided and paid when it started. */
        RUNNING,
        /** The winner is home: the result stands for a few seconds. */
        RESULT
    }

    /** One player's entry in the race. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final Racer pick;
        private final boolean money;
        private RaceTicket ticket;
        /** Its open round until the race is drawn, then {@code null} (settled). Always {@code null} for an NPC. */
        private ChanceRounds.Round round;
        private boolean startNow;
        /** An NPC's pretend entry: no round, nothing in, nothing paid. */
        private boolean npc;

        private Entry(UUID id, String name, Racer pick, boolean money, RaceTicket ticket, ChanceRounds.Round round) {
            this.id = id;
            this.name = name;
            this.pick = pick;
            this.money = money;
            this.ticket = ticket;
            this.round = round;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Racer pick() {
            return pick;
        }

        /** What went on the pick. */
        public int stake() {
            return ticket.in();
        }

        /** What a win pays. */
        public int pays() {
            return ticket.pays();
        }

        /** Whether the entry is in dollars. */
        public boolean money() {
            return money;
        }

        /** Whether they asked to start now. */
        public boolean startNow() {
            return startNow;
        }

        /** Whether this is an NPC's pretend entry. */
        public boolean npc() {
            return npc;
        }
    }

    /** The most NPC entries in one race. */
    public static final int MAX_NPCS = 2;

    private final GameContext ctx;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    /** The next race's card, or the card of the race being run; {@code null} until it is first shown. */
    private RaceCard card;
    /** A test's own card for every race, or {@code null} (a random one each race, as in play). */
    private RaceCard testCard;
    /** Each racer's last finishes, most recent first (1 is a win), by lane; read from the database when first asked. */
    private List<List<Integer>> recent;
    private Phase phase = Phase.IDLE;
    private long gatherUntil;
    private int shownSeconds = -1;
    private RaceScript script;
    private int laps = 1;
    private long startedAt;
    private long resultUntil;
    private Racer lastWinner;
    private long trackSeenAt = Long.MIN_VALUE / 2;
    /** A test's own seed for the next race, or {@code null} (a random one, as in play). */
    private Long nextSeed;

    public MobRaces(GameContext ctx) {
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
    public MobRacesSettings settings() {
        return games().settings(SPEC);
    }

    /** For tests: the next race is drawn from {@code seed} (nothing in play calls this). */
    public void seedNextRace(long seed) {
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
        return TokenService.Source.ARCADE_MOB_RACES;
    }

    @Override
    public boolean configEnabled() {
        MobRacesSettings s = settings();
        return s.enabled() && !s.open().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Six tiny mobs race round the track in the Game Parlor.",
                "Each one's stats and form today make its chance of winning.",
                "A favourite wins more often and pays less; a longshot pays more.",
                "If your pick wins, you get what its tile says. If not, nothing.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        MobRacesSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        double low = lowestRtp();
        if (!Double.isNaN(low)) {
            lore.add("&7Whichever you pick, it " + RtpLimits.playerLine(low) + ".");
        }
        lore.add("&e" + s.dailyLimit() + " races a day");
        lore.add("&7Tap the track in the Game Parlor to enter.");
        List<Integer> open = s.open();
        String range = open.isEmpty() ? "no" : open.size() == 1 ? Integer.toString(open.get(0))
                : open.get(0) + " to " + open.get(open.size() - 1);
        return Menus.icon(Material.SADDLE, "&aMob Races &7- " + range + " tokens in", lore.toArray(new String[0]));
    }

    /** Its tile is shown only where it can be played: to a player standing in the Game Parlor. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        if (viewer != null && !games().inParlor(viewer.getUniqueId())) {
            return List.of();
        }
        return Game.super.tiles(viewer);
    }

    /** The track's tap, or {@code /hcm play mob_races}: the picking screen, in the Parlor, while no race is on. */
    @Override
    public void open(Player player, Runnable back) {
        GamesService games = games();
        Refusal why = whyNotHere(player);
        if (why != null) {
            games.tell(player, why);
            return;
        }
        if (phase == Phase.RUNNING || phase == Phase.RESULT) {
            player.sendMessage(Text.of("&7The race is on. Watch the track! You can enter the next one when it is"
                    + " over."));
            return;
        }
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.chance.MobRacesMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void start() {
        reset();
        if (ctx.plugin() != null) {
            games().every(this, 1, 1, this::tick);
        }
    }

    /**
     * Stop (a reload that closed it, a switch-off, the server stopping): a race being run was paid when it started,
     * so its entrants are told how it came out now. Entries still waiting are OPEN rounds with nothing drawn: the
     * framework finishes them, and their stakes come back whole.
     */
    @Override
    public void stop() {
        if (phase == Phase.RUNNING) {
            remember(script);
            tellResults();
        }
        reset();
        card = null;
        changed();
    }

    /** A player left: out of a race that hasn't started (the framework finishes their round: the stake comes back). */
    @Override
    public void onQuit(Player player) {
        screens.remove(player.getUniqueId());
        if (phase == Phase.GATHERING && entries.remove(player.getUniqueId()) != null) {
            if (!anyReal()) {
                reset(); // a race of NPCs alone never runs
            }
            changed();
        }
    }

    @Override
    public List<String> oddsLines() {
        MobRacesSettings s = settings();
        if (s.open().isEmpty()) {
            return List.of();
        }
        RaceCard c = card();
        List<String> out = new ArrayList<>();
        out.add("&6" + NAME + " &7— " + RtpLimits.playerLine(lowestRtp()) + " · " + s.dailyLimit()
                + " races a day · this race's card:");
        for (Racer r : Racer.values()) {
            StringBuilder b = new StringBuilder("&7  " + r.label() + " &7wins " + c.percent(r) + " (1 race in "
                    + c.oneIn(r) + "):");
            for (int stake : s.open()) {
                RaceCard.Pay p = c.pay(r, stake, s.rtp(), s.cap());
                b.append(p == null ? " " + stake + " in: not offered;" : " " + stake + " in → " + p.pays() + " (&f"
                        + RtpLimits.tenthPercent(p.rtp()) + "%&7);");
            }
            out.add(b.substring(0, b.length() - 1));
        }
        for (int stake : s.stakes()) {
            if (!s.offers(stake)) {
                out.add("&7  " + stake + " in: off (not even a favourite can be paid inside 85-95 of every 100)");
            }
        }
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        feed(out, settings(), card());
    }

    /**
     * Its {@code /api/arcade} entry (pure, for tests): each stake's lowest exact return on this card, and what each
     * racer pays at each stake with its exact chance.
     */
    static void feed(FeedWriter out, MobRacesSettings s, RaceCard c) {
        if (s.open().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (int stake : s.open()) {
            double low = Double.NaN;
            for (Racer r : Racer.values()) {
                RaceCard.Pay p = c.pay(r, stake, s.rtp(), s.cap());
                if (p == null) {
                    continue;
                }
                low = Double.isNaN(low) ? p.rtp() : Math.min(low, p.rtp());
                rows.add(new FeedWriter.PayRow(stake, r.mobName() + " wins", p.pays(), c.chance(r), null, null));
            }
            if (!Double.isNaN(low)) {
                rtp.put(stake, low);
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("racers", Racer.COUNT);
        List<Map<String, Object>> card = new ArrayList<>();
        for (Racer r : Racer.values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("racer", r.mobName());
            one.put("speed", r.speed());
            one.put("stamina", r.stamina());
            one.put("start", r.start());
            one.put("form", c.form(r));
            one.put("chance", c.chance(r));
            card.add(one);
        }
        extra.put("card", card);
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), rows,
                "Six tiny mobs race round a track in the Clubhouse's Game Parlor. Each race has its card: every"
                        + " racer's stats and form today make its exact chance of winning, and a winning pick gets"
                        + " what that racer pays at the stake put on it (a favourite less, a longshot more); any"
                        + " other pick gets nothing.", extra);
    }

    @Override
    public List<String> statusLines() {
        return List.of((trackUp() ? "track: up" : "track: none (see /hcm games clubhouse status)") + ", "
                + phase.name().toLowerCase(java.util.Locale.ROOT) + ", " + entries.size() + " in");
    }

    // ---- what the track and the screen read ---------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** Who is in the race, in the order they entered. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry entry(UUID id) {
        return entries.get(id);
    }

    /** Seconds until entries close, or -1 when none are being taken. */
    public int secondsLeft() {
        return phase != Phase.GATHERING ? -1 : (int) Math.max(0, (gatherUntil - now() + 999) / 1000);
    }

    /** The race being run or just finished, or {@code null}. */
    public RaceScript script() {
        return phase == Phase.RUNNING || phase == Phase.RESULT ? script : null;
    }

    /** How many ticks into the race it is (0 before it starts). */
    public int raceTick() {
        return script() == null ? 0 : (int) Math.max(0, (now() - startedAt) / 50);
    }

    /** How many laps this race is. */
    public int laps() {
        return laps;
    }

    /** The winner, once it is home ({@code null} while the race is still being run). */
    public Racer winner() {
        return phase == Phase.RESULT && script != null ? Racer.of(script.winner()) : null;
    }

    /** Who won the last race run here, or {@code null} before the first. */
    public Racer lastWinner() {
        return lastWinner;
    }

    /** The Clubhouse's word, once a second, that the track is drawn and can be tapped. */
    public void trackSeen() {
        trackSeenAt = now();
    }

    /** Whether there is a track to race on now. */
    public boolean trackUp() {
        return now() - trackSeenAt <= TRACK_MS;
    }

    /** Run {@code refresh} whenever the race changes (a screen); {@link #unwatch} when it closes. */
    public void watch(UUID id, Runnable refresh) {
        if (refresh != null) {
            screens.put(id, refresh);
        }
    }

    public void unwatch(UUID id) {
        screens.remove(id);
    }

    // ---- the card --------------------------------------------------------------------------------------------

    /**
     * This race's card: the one being run, or the next race's. Made the first time it is asked for after a race was
     * run, and fixed until the next race is run on it (a race called off, or a race of NPCs alone, keeps it).
     */
    public RaceCard card() {
        if (card == null) {
            card = testCard != null ? testCard : RaceCard.of(ThreadLocalRandom.current().nextLong());
        }
        return card;
    }

    /** For tests: every race from now on is run on {@code card} (nothing in play calls this). */
    public void useCard(RaceCard card) {
        this.testCard = card;
        if (phase == Phase.IDLE && entries.isEmpty()) {
            this.card = null; // no race is open on the one shown: the test's card is the next one
        }
    }

    /**
     * What a pick on {@code racer} with {@code stake} on it pays in this race, in tokens or (the Game Parlor's money
     * play) dollars; {@code null} when that pair isn't offered. Tokens: a stake the game offers, under
     * {@code games.max_payout}. Dollars: under the Parlor's biggest payout for that stake. Either way the pay whose
     * return is closest to the target inside the 85-95 band.
     */
    public RaceCard.Pay pay(Racer racer, int stake, boolean money) {
        if (racer == null || stake <= 0) {
            return null;
        }
        MobRacesSettings s = settings();
        if (money) {
            return card().pay(racer, stake, s.rtp(), games().parlor().rules().capFor(stake));
        }
        return s.offers(stake) ? card().pay(racer, stake, s.rtp(), s.cap()) : null;
    }

    /**
     * The lowest exact return of any pick offered in tokens on this race's card ({@code NaN} with none): the game's
     * one honest number. Every pick is within a little of it.
     */
    public double lowestRtp() {
        MobRacesSettings s = settings();
        double low = Double.NaN;
        for (int stake : s.open()) {
            for (Racer r : Racer.values()) {
                RaceCard.Pay p = pay(r, stake, false);
                if (p != null && (Double.isNaN(low) || p.rtp() < low)) {
                    low = p.rtp();
                }
            }
        }
        return low;
    }

    /** How many of a racer's last finishes are kept and shown. */
    public static final int RECENT = 5;
    /** Where they are kept ({@code hcm_meta}). */
    static final String KEY_RECENT = "gen.mobraces.recent";

    /** {@code racer}'s last finishes on this server, most recent first (1 is a win), up to {@value #RECENT}. */
    public List<Integer> recent(Racer racer) {
        return List.copyOf(recent().get(racer.lane()));
    }

    private List<List<Integer>> recent() {
        if (recent == null) {
            recent = readRecent(null);
            try {
                Parlor.Store store = games().metaStore();
                if (store != null) {
                    recent = readRecent(store.get(KEY_RECENT));
                }
            } catch (SQLException | RuntimeException e) {
                // only shown: a race runs and pays the same without them
            }
        }
        return recent;
    }

    /** The kept finishes (pure): {@code "14263,2,..."}, one run of places a lane, most recent first. */
    static List<List<Integer>> readRecent(String value) {
        List<List<Integer>> out = new ArrayList<>();
        String[] lanes = value == null ? new String[0] : value.split(",", -1);
        for (int lane = 0; lane < Racer.COUNT; lane++) {
            List<Integer> places = new ArrayList<>();
            if (lanes.length == Racer.COUNT) {
                for (char ch : lanes[lane].toCharArray()) {
                    if (ch >= '1' && ch <= '0' + Racer.COUNT && places.size() < RECENT) {
                        places.add(ch - '0');
                    }
                }
            }
            out.add(places);
        }
        return out;
    }

    /** The finishes as kept (pure). */
    static String writeRecent(List<List<Integer>> recent) {
        StringBuilder b = new StringBuilder();
        for (int lane = 0; lane < Racer.COUNT; lane++) {
            b.append(lane == 0 ? "" : ",");
            for (int place : recent.get(lane)) {
                b.append(place);
            }
        }
        return b.toString();
    }

    /** The race just run: each racer's place goes first in its finishes, and is kept. */
    private void remember(RaceScript race) {
        if (race == null) {
            return;
        }
        List<List<Integer>> all = recent();
        for (int lane = 0; lane < Racer.COUNT; lane++) {
            List<Integer> places = all.get(lane);
            places.add(0, race.placeOf(lane) + 1);
            while (places.size() > RECENT) {
                places.remove(places.size() - 1);
            }
        }
        try {
            Parlor.Store store = games().metaStore();
            if (store != null) {
                store.set(KEY_RECENT, writeRecent(all));
            }
        } catch (SQLException | RuntimeException e) {
            // only shown: kept in memory until the next start
        }
    }

    /** Races {@code player} may still enter today, or -1 if they can't be counted right now. */
    public int racesLeft(Player player) {
        try {
            int played = games().dao().playsToday(player.getUniqueId(), id(), games().clock().dayKey());
            return Math.max(0, settings().dailyLimit() - played);
        } catch (SQLException | RuntimeException e) {
            return -1;
        }
    }

    // ---- entering ------------------------------------------------------------------------------------------

    /** Why {@code player} can't use the track where they are, or {@code null}: it is in the Parlor, and it is there. */
    private Refusal whyNotHere(Player player) {
        if (!games().inParlor(player.getUniqueId())) {
            return NOT_HERE;
        }
        return trackUp() ? null : NO_TRACK;
    }

    /**
     * Enter the race: {@code stake} on {@code pick}, in tokens or (in the Parlor, with the permission) dollars. The
     * stake goes in now, through the play gate, as an open round; the first entry starts the wait for the others.
     *
     * @return false when it was refused (the player has been told) and nothing moved
     */
    public boolean enter(Player player, Racer pick, int stake, boolean money) {
        GamesService games = games();
        UUID id = player.getUniqueId();
        if (phase == Phase.RUNNING || phase == Phase.RESULT) {
            games.tell(player, RACE_ON);
            return false;
        }
        Refusal why = whyNotHere(player);
        if (why == null && entries.containsKey(id)) {
            why = ALREADY_IN;
        }
        RaceCard.Pay pay = pick == null ? null : pay(pick, stake, money);
        if (why == null && pay == null) {
            why = Refusal.CLOSED;
        }
        if (why != null) {
            games.tell(player, why);
            return false;
        }
        RaceTicket ticket = RaceTicket.entry(pick.lane(), pay.stake(), pay.pays(), card().parts());
        // an entry of theirs left open from before (a crash) is finished first, by its own data
        ChanceRounds.Round old = games.rounds().openRound(id, id());
        if (old != null) {
            games.rounds().finishNow(old);
        }
        ChanceRounds.Round round = games.rounds().open(player, this, pay.stake(), ticket.data(), money);
        if (round == null) {
            return false; // refused, and told why
        }
        entries.put(id, new Entry(id, player.getName(), pick, money, ticket, round));
        if (phase == Phase.IDLE) {
            phase = Phase.GATHERING;
            gatherUntil = now() + settings().gatherSeconds() * 1000L;
            shownSeconds = -1;
        }
        changed();
        return true;
    }

    /**
     * An NPC enters the race gathering now: a pretend {@code stake} on {@code pick}. Only while a real player is in it,
     * once per NPC, at most {@link #MAX_NPCS}. No round is opened and nothing is put in: its entry only shows on the
     * board, and is told nothing. It reads the race's card and changes nothing on it.
     *
     * @param id    the NPC's UUID (never a real player's)
     * @param label its name with its gear and stars
     * @return whether it entered
     */
    public boolean enterNpc(UUID id, String label, Racer pick, int stake) {
        if (phase != Phase.GATHERING || !anyReal() || pick == null || entries.containsKey(id) || npcCount() >= MAX_NPCS) {
            return false;
        }
        RaceCard.Pay pay = pay(pick, stake, false);
        if (pay == null) {
            return false;
        }
        Entry e = new Entry(id, label, pick, false, RaceTicket.entry(pick.lane(), pay.stake(), pay.pays(),
                card().parts()), null);
        e.npc = true;
        e.startNow = true; // an NPC never holds the start up
        entries.put(id, e);
        changed();
        return true;
    }

    /** Whether a real player is in the race. */
    public boolean anyReal() {
        for (Entry e : entries.values()) {
            if (!e.npc) {
                return true;
            }
        }
        return false;
    }

    /** How many NPCs are in the race. */
    public int npcCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.npc) {
                n++;
            }
        }
        return n;
    }

    /** {@code player}, an entrant, asks to start now: when every entrant has, the wait is skipped. */
    public void startNow(Player player) {
        Entry e = entries.get(player.getUniqueId());
        if (e == null || phase != Phase.GATHERING || e.startNow) {
            return;
        }
        e.startNow = true;
        if (everyoneAsked()) {
            startRace();
        } else {
            changed();
        }
    }

    private boolean everyoneAsked() {
        if (entries.isEmpty()) {
            return false;
        }
        for (Entry e : entries.values()) {
            if (!e.startNow) {
                return false;
            }
        }
        return true;
    }

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Every tick while the game is open: the wait, the race, the result. */
    public void tick() {
        long now = now();
        switch (phase) {
            case GATHERING -> {
                dropLeavers();
                if (!anyReal()) {
                    reset(); // a race nobody real is left in does not run (NPCs alone never race)
                    changed();
                } else if (now >= gatherUntil || everyoneAsked()) {
                    startRace();
                } else if (secondsLeft() != shownSeconds) {
                    shownSeconds = secondsLeft();
                    changed(); // the countdown
                }
            }
            case RUNNING -> {
                if (raceTick() >= script.ticks()) {
                    phase = Phase.RESULT;
                    resultUntil = now + RESULT_MS;
                    lastWinner = Racer.of(script.winner());
                    remember(script);
                    tellResults();
                    changed();
                }
            }
            case RESULT -> {
                if (now >= resultUntil) {
                    reset();
                    card = null; // the race on that card has been run: the next race gets its own
                    changed();
                }
            }
            default -> {
            }
        }
    }

    /**
     * Entrants who are gone before the race is drawn: one who left the server (their round is finished by the exit
     * rule: the stake back), and one who walked out of the Parlor (the stake back, and told so).
     */
    private void dropLeavers() {
        GamesService games = games();
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.npc) {
                continue;
            }
            Player p = games.online(e.id);
            if (p == null) {
                games.rounds().finishNow(e.round); // if their quit hasn't already: paid once, there or here
                entries.remove(e.id);
                changed();
            } else if (!games.inParlor(e.id)) {
                if (games.rounds().close(e.round, e.stake(), e.ticket.data())) {
                    p.sendMessage(Text.of("&7You left the Game Parlor before the race. &fYour "
                            + amount(e.money, e.stake()) + " back."));
                }
                entries.remove(e.id);
                screens.remove(e.id);
                changed();
            }
        }
    }

    /**
     * Entries are closed: draw the race's one seed, write it into every entrant's round, and settle them all, before
     * anything is shown. The winner is drawn from the seed with exactly the card's chances, which every entry carries. An entrant whose round was finished somewhere else meanwhile is out of the race (it paid
     * there, once); with nobody left, there is no race.
     */
    private void startRace() {
        GamesService games = games();
        MobRacesSettings s = settings();
        long seed = nextSeed != null ? nextSeed : ThreadLocalRandom.current().nextLong();
        nextSeed = null;
        for (Entry e : new ArrayList<>(entries.values())) {
            RaceTicket drawn = e.ticket.in(seed);
            if (e.npc) {
                e.ticket = drawn; // pretend: only so the board can say whether its pick won
                continue;
            }
            ChanceRounds.Round stepped = games.rounds().step(e.round, drawn.data());
            if (stepped == null) {
                entries.remove(e.id);
                continue;
            }
            // a settle that can't be written leaves the round open with the seed in it: the exit rule pays the same
            games.rounds().close(stepped, drawn.payout(), drawn.data());
            e.ticket = drawn;
            e.round = null;
        }
        if (!anyReal()) {
            reset(); // nobody real is left: no race (the NPCs' entries go with it)
            changed();
            return;
        }
        script = RaceScript.of(seed, s.raceTicks(), card().parts());
        laps = s.laps();
        startedAt = now();
        phase = Phase.RUNNING;
        changed();
    }

    /** Tell every entrant how the race came out for them (now, or at their next join). */
    private void tellResults() {
        if (script == null) {
            return;
        }
        Racer won = Racer.of(script.winner());
        for (Entry e : entries.values()) {
            if (e.npc) {
                continue;
            }
            String line;
            if (e.ticket.won()) {
                line = "&6" + NAME + ": " + won.label() + " &6won, and you picked it! &f" + amount(e.money, e.stake())
                        + " in → " + amount(e.money, e.pays()) + " back.";
            } else {
                line = "&7" + NAME + ": " + won.label() + " &7won. You picked " + e.pick.label()
                        + "&7. No win this time.";
            }
            games().notice(e.id, line, games().online(e.id) == null);
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        entries.clear();
        script = null;
        shownSeconds = -1;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // one screen's trouble is never the race's
            }
        }
    }

    /** "5 tokens" or "$5". */
    static String amount(boolean money, int n) {
        return money ? Parlor.dollars(n) : n + " token" + (n == 1 ? "" : "s");
    }
}
