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
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings.Odds;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob Races: the Game Parlor's model racetrack (the owner: "I hope you make a mini track and you literally see the
 * tiny mobs running on the track!"). Six tiny mobs race round a table in the middle of the room; a player picks one
 * and puts tokens (or, in the Parlor with the money permission, dollars) on it. Every racer is as likely to win as
 * any other. A pick that wins pays one whole amount solved per stake ({@link MobRacesSettings}); anything else pays
 * nothing.
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
 * and it touches neither the race's seed nor any real entry's ticket, so every real entry's odds and pay are what they
 * were (a race pays a fixed amount per stake whoever else is in). A race with only NPCs in it never runs: when the last
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
    /** What a win pays at each dollar stake asked for so far: solved once and kept. */
    private final Map<String, Optional<Odds>> moneyOdds = new ConcurrentHashMap<>();
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
        return s.enabled() && !s.odds().isEmpty();
    }

    @Override
    public List<String> rules() {
        return List.of("Six tiny mobs race round the track in the Game Parlor.",
                "Pick one and put something on it before they start.",
                "Every racer is just as likely to win.",
                "If your pick wins, you get what its tile says. If not, nothing.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        MobRacesSettings s = settings();
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        if (!s.odds().isEmpty()) {
            lore.add("&7It " + RtpLimits.playerLine(s.lowestRtp()) + ".");
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
            tellResults();
        }
        reset();
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
        if (s.odds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("&6" + NAME + " &7— " + RtpLimits.playerLine(s.lowestRtp()) + " · " + s.dailyLimit()
                + " races a day");
        for (Odds o : s.odds()) {
            out.add("&7  " + o.stake() + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "% &7(a winning pick gets "
                    + o.pays() + ", 1 race in " + Racer.COUNT + ")");
        }
        for (int stake : s.stakes()) {
            if (s.odds(stake) == null) {
                out.add("&7  " + stake + " in: off (no whole amount gives back 85-95 of every 100)");
            }
        }
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        feed(out, settings());
    }

    /** Its {@code /api/arcade} entry (pure, for tests): each stake's exact return, and what a winning pick gets. */
    static void feed(FeedWriter out, MobRacesSettings s) {
        if (s.odds().isEmpty()) {
            return;
        }
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        List<FeedWriter.PayRow> rows = new ArrayList<>();
        for (Odds o : s.odds()) {
            rtp.put(o.stake(), o.rtp());
            rows.add(new FeedWriter.PayRow(o.stake(), "your pick wins", o.pays(), 1.0 / Racer.COUNT, null, null));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("racers", Racer.COUNT);
        out.chance(SPEC.id(), NAME, s.open(), rtp, s.dailyLimit(), rows,
                "Six tiny mobs race round a track in the Clubhouse's Game Parlor. Pick one before they start; every"
                        + " racer is just as likely to win. A winning pick gets the amount shown, any other nothing.",
                extra);
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

    /** What a win pays with {@code stake} on a racer, in tokens or dollars; {@code null} when that can't be played. */
    public Odds odds(int stake, boolean money) {
        return money ? moneyOdds(stake) : settings().odds(stake);
    }

    /**
     * The Game Parlor's money play: what a win pays with {@code dollars} on a racer, solved exactly as a token stake
     * is (the same target, the same 85-95 band) under the Parlor's biggest payout; {@code null} when no whole amount
     * fits the band at that stake.
     */
    public Odds moneyOdds(int dollars) {
        if (dollars <= 0) {
            return null;
        }
        double rtp = settings().rtp();
        int cap = games().parlor().rules().capFor(dollars);
        return moneyOdds.computeIfAbsent(dollars + ":" + cap + ":" + rtp,
                k -> Optional.ofNullable(MobRacesSettings.solve(dollars, rtp, cap))).orElse(null);
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
        Odds odds = pick == null ? null : odds(stake, money);
        if (why == null && odds == null) {
            why = Refusal.CLOSED;
        }
        if (why != null) {
            games.tell(player, why);
            return false;
        }
        RaceTicket ticket = RaceTicket.entry(pick.lane(), odds.stake(), odds.pays());
        // an entry of theirs left open from before (a crash) is finished first, by its own data
        ChanceRounds.Round old = games.rounds().openRound(id, id());
        if (old != null) {
            games.rounds().finishNow(old);
        }
        ChanceRounds.Round round = games.rounds().open(player, this, odds.stake(), ticket.data(), money);
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
     * board, and is told nothing.
     *
     * @param id    the NPC's UUID (never a real player's)
     * @param label its name with its gear and stars
     * @return whether it entered
     */
    public boolean enterNpc(UUID id, String label, Racer pick, int stake) {
        if (phase != Phase.GATHERING || !anyReal() || pick == null || entries.containsKey(id) || npcCount() >= MAX_NPCS) {
            return false;
        }
        Odds odds = settings().odds(stake);
        if (odds == null) {
            return false;
        }
        Entry e = new Entry(id, label, pick, false, RaceTicket.entry(pick.lane(), odds.stake(), odds.pays()), null);
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
                    tellResults();
                    changed();
                }
            }
            case RESULT -> {
                if (now >= resultUntil) {
                    reset();
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
     * anything is shown. An entrant whose round was finished somewhere else meanwhile is out of the race (it paid
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
        script = RaceScript.of(seed, s.raceTicks());
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
