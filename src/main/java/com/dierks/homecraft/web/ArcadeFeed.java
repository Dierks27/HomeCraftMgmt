package com.dierks.homecraft.web;

import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.gen.engine.FreshFeed;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackOdds;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.storage.GamesDao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code /api/arcade} JSON (games spec §10): the Arcade as the LilahCraft website shows it —
 * every open game with its published odds or its record, today's featured game, the Scratch
 * Ticket's pot, the Prize Counter, the token Card Packs and the achievements. Built on the main
 * thread alongside the other feeds and cached; the HTTP handler only serves the string.
 *
 * <pre>{"generatedAt":ms[,"games":[…]][,"featured":{"game":…,"until":ms}][,"starChart":{…}]
 * [,"freshHistory":[…]][,"events":{…}][,"jackpots":[{"game":"scratch_ticket","tokens":N}]][,"prizes":[…]]
 * [,"packs":[…]][,"achievements":[…]]}</pre>
 *
 * <p><b>How the games get in.</b> This class is the games' {@link FeedWriter}: the server hands one
 * instance to every open game's {@code Game.feed}, each game writes its entries from the SAME
 * settings/engine object it plays with (so the website's numbers are the game's numbers), and
 * {@link #json} then writes them. The writer owns every format and omission rule, so no game can
 * publish a malformed number or a key the site doesn't expect:
 * <ul>
 *   <li>{@code chance}: {@code {id,name,kind:"chance",stakes,rtp,rtpByStake,dailyLimit?,paytable?,
 *       rules?,…extra}}. A stake is published only with its RTP; each RTP is the COMPUTED return
 *       floored to one decimal ({@link RtpLimits#tenthPercent}, the same number {@code /hcm arcade
 *       odds} gives admins) and the headline {@code rtp} is the lowest. A paytable row carries
 *       {@code chance} (4 significant digits, {@link Json#sig}) with its {@code oneIn}
 *       ({@link FeedWriter#oneIn}, the number the screen shows), or the Wheel's exact
 *       {@code spaces}/{@code of}; a row with neither — a payout without its odds — is left out,
 *       and so is a game with no stake left. Extra keys never replace a standard one.</li>
 *   <li>{@code cabinet}: {@code {id,name,kind:"cabinet",board,unit,lowerIsBetter,best?,top?}}.</li>
 *   <li>a course: {@code {id,name,kind:"parkour"|"elytra"|"boat",tier?,record?:{ms,at?},daily?,fresh?,
 *       classic?,top?}}.</li>
 *   <li>{@code golf}: {@code {id,name,kind:"golf",holes,par,record?:{strokes,at?},daily?,fresh?,classic?,
 *       top?}}.</li>
 *   <li>{@code arena} (Falling Floors, EVENTS-DROPPER-SPEC §B.3.4): {@code {id,name,kind:"arena",shape?,
 *       top?}}, its {@code top} from the board the game names.</li>
 * </ul>
 * A Fresh Courses course (GEN-SPEC §5.6, the weekly addendum) is written the same way, its record
 * taken from its set's board, plus {@code daily:{day,nextAt?,goldMs?,silverMs?,cadence?,lastDay?}}:
 * the set's first day, when the next set is due, for a time trial its star times, and the set's
 * length in days and last day; and, from Fresh Courses itself (GEN-SPEC-KEEP §8), {@code fresh:
 * {code,seed,from,to?,cadenceDays}} (the course code and short seed) or, for a Classics slot,
 * {@code classic:{code,from,to?}}. A course without them is written byte for byte as before. The
 * week's Star Chart is a top-level {@code starChart:{week,best?}} (with its {@code holder} only
 * while names are on), and every past Fresh course a top-level {@code freshHistory} (the writer
 * writes each entry itself, so a record's holder follows the same rule). Never a full seed, a rev,
 * a half or a UUID.
 *
 * <p><b>Leaderboards (EXTRAS E3).</b> Every entry with a board (a cabinet, a course, a golf course,
 * a Fresh course, a Classic) carries {@code top:[{rank,value,unit,at,holder?}]}: its board's best
 * {@code games.feed_top} rows (shipped 5), ties sharing a rank (1, 1, 3); every past Fresh course
 * its best 3. The writer reads the rows itself ({@link Boards}), after every game has written, so
 * a name is only ever looked up while names may be shown. An empty board has no {@code top}.
 * {@code record} and {@code best} are kept as they were.
 * Entries keep the order they were written in (the catalog's), after the Scratch Ticket's; a
 * second entry with an id already published is dropped, so the site can key on {@code id}.
 *
 * <p><b>The Scratch Ticket.</b> Whenever {@code jackpots} is published, {@code games[]} carries a
 * {@code scratch_ticket} chance entry too — both come from one {@link Scratch}, so the site never
 * shows a pot without its odds. Its RTP is {@link ArcadeService#rtp}'s steady-state number (the
 * ticket is not clamped to 85-95 in this release), its one stake is the ticket price, its rows pay
 * tokens, and it has no {@code dailyLimit}.
 *
 * <p><b>Today's pick.</b> {@code featured.game} is the id of a {@code games[]} entry, or
 * {@code "trials"} / {@code "golf"} when the owner pinned a whole world game: then every course of
 * that kind ({@code parkour}/{@code elytra}/{@code boat}, or {@code golf}) is today's pick. It is
 * written only when something it points at is in {@code games[]}; {@code until} is the next local
 * midnight.
 *
 * <p><b>The other sections.</b> {@code prizes}: the visible Prize Counter rows except Trade In,
 * Quest Reroll and the Rare Card ({@link #prizes}). {@code packs}: packs sold for tokens with the
 * rarity odds they roll with, as percents ({@link #packs}). {@code achievements}: the enabled rows
 * minus the ones won by a game of chance ({@link #chanceWin}).
 *
 * <p><b>Race Night</b> (EVENTS-DROPPER-SPEC §A.7) is the top-level {@code events} object, written
 * by the writer from what Race Night hands {@link #events}: {@code next:{id,name,joinAt,startsAt,
 * course?:{id,name},races,laps,entry:"free",prizes,finisherPrize,prizeNight,racers,maxRacers}},
 * {@code upcoming:[ms…]}, {@code live:{id,state,race,of,racers,standings?:[{rank,points,lap,laps,
 * holder?}]}}, {@code recent:[{id,at,course?,racers,state,top?:[{rank,value,unit,holder?}]}]} (at most
 * {@value FeedWriter.Events#RECENT} nights of {@value FeedWriter.Events#ROWS} rows) and
 * {@code season:{key,name,until,top?}} (E3's {@code top}, read from its board). A state the site
 * doesn't know is not published; empty parts are left out, and the whole object while it has
 * nothing. {@code racers} is only ever a count.
 *
 * <p><b>Omission.</b> {@code generatedAt} is always first; every other key is written with a
 * leading comma only when it has something in it, so an empty or switched-off section is absent,
 * never {@code []} or {@code null}. Names, combos and rules lose their colour codes.
 *
 * <p><b>Privacy.</b> No player data: no UUIDs, balances, per-player limits, winners or names. A
 * record is a score or a time and a date. The one exception is {@code web.dashboard.arcade_show_names}
 * (shipped false): only while it is true does a record carry its {@code holder}, and a holder a game
 * passes while it is false is dropped here. Extra keys that name a person or a balance
 * ({@link #PLAYER_KEYS}), a UUID-shaped string and any value that is not plain data are never
 * written, at any depth.
 *
 * <p>Needs no server: it reads only plain config records and {@link ArcadeService#rtp}'s static
 * math, so it is unit-tested without one.
 */
public final class ArcadeFeed implements FeedWriter {

    /** The Scratch Ticket's id in {@code games[]} and {@code jackpots}. */
    public static final String SCRATCH_ID = "scratch_ticket";
    /** The pinned-whole ids of the world games (their courses publish under their own ids). */
    static final String TRIALS_ID = "trials";
    static final String GOLF_ID = "golf";
    /** Its name. */
    public static final String SCRATCH_NAME = "Scratch Ticket";

    /**
     * Keys that would name a person, a balance or a winner. Never written from a game's extra keys
     * (at any depth); a record's {@code holder} is written by the writer itself, and only while
     * names are switched on.
     */
    public static final Set<String> PLAYER_KEYS = Set.of("uuid", "uuids", "player", "players", "owner", "owners",
            "holder", "holders", "winner", "winners", "balance", "balances");

    /** A chance entry's own keys, which an extra key never replaces. */
    private static final Set<String> CHANCE_KEYS = Set.of("id", "name", "kind", "stakes", "rtp", "rtpByStake",
            "dailyLimit", "paytable", "rules");

    /** Prize Counter rows that are actions, not prizes (spec §10). */
    private static final Set<PluginConfig.PrizeType> NOT_PRIZES = EnumSet.of(PluginConfig.PrizeType.TRADE_IN,
            PluginConfig.PrizeType.QUEST_REROLL, PluginConfig.PrizeType.PITY);

    private static final Pattern UUID_TEXT =
            Pattern.compile("(?i).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");

    // ---- the rows ---------------------------------------------------------------------------

    /** One {@code games[]} entry as a game wrote it. */
    public sealed interface GameRow permits ChanceRow, CabinetRow, CourseRow, GolfRow, ArenaRow {
        String id();
    }

    /**
     * A game of chance, as {@link FeedWriter#chance} takes it. The lists and maps are copied (nulls
     * kept, to be skipped when written); {@code extra} keeps its order when it has one (a
     * {@code LinkedHashMap}) and is sorted by key otherwise, so the output is stable.
     */
    public record ChanceRow(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                            Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra)
            implements GameRow {

        public ChanceRow {
            stakes = stakes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(stakes));
            rtpByStake = rtpByStake == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rtpByStake));
            paytable = paytable == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(paytable));
            extra = extra == null ? Map.of() : Collections.unmodifiableMap(ordered(extra));
        }
    }

    /** A menu cabinet's published board ({@link FeedWriter#cabinet}). */
    public record CabinetRow(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                             String holder) implements GameRow {
    }

    /** A time-trial course ({@link FeedWriter#course}); {@code daily} for a Fresh Courses course, else null. */
    public record CourseRow(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                            String holder, Daily daily) implements GameRow {

        /** A hand-built course. */
        public CourseRow(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                         String holder) {
            this(id, name, kind, tier, recordMs, recordAt, holder, null);
        }
    }

    /** A mini golf course ({@link FeedWriter#golf}); {@code daily} for a Fresh Courses course, else null. */
    public record GolfRow(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                          String holder, Daily daily) implements GameRow {

        /** A hand-built course. */
        public GolfRow(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                       String holder) {
            this(id, name, holes, par, recordStrokes, recordAt, holder, null);
        }
    }

    /** An arena game ({@link FeedWriter#arena}): Falling Floors, with this week's floor shape. */
    public record ArenaRow(String id, String name, String shape) implements GameRow {
    }

    /**
     * The week's Star Chart ({@link FeedWriter#starChart}).
     *
     * @param week   the week's first day ({@code 2026-09-28})
     * @param best   the top total, or {@code null}
     * @param holder who holds it, or {@code null} (published only while names are on)
     */
    public record StarChart(String week, Long best, String holder) {
    }

    /**
     * Today's featured game.
     *
     * @param game  its play id (a game or a course); written only when {@code games[]} has it
     * @param until when the pick changes (the next local midnight)
     */
    public record Featured(String game, long until) {
    }

    /**
     * The Scratch Ticket: its {@code games[]} entry and, when it has a jackpot line, its
     * {@code jackpots} row. Made by {@link #scratch}.
     *
     * @param ticketTokens the one stake
     * @param rtp          the steady-state return as a fraction
     * @param paytable     its winning lines, in tokens
     * @param pot          the pot now, or {@code null} when the ticket has no jackpot line
     * @param closed       whether it is closed ({@link ArcadeService#ticketClosed}: it would give back 100% or
     *                     more), written as {@code "closed": true} on its entry, and left out otherwise
     */
    public record Scratch(int ticketTokens, double rtp, List<PayRow> paytable, Integer pot, boolean closed) {

        public Scratch {
            paytable = paytable == null ? List.of() : List.copyOf(paytable);
        }

        /** An open ticket. */
        public Scratch(int ticketTokens, double rtp, List<PayRow> paytable, Integer pot) {
            this(ticketTokens, rtp, paytable, pot, false);
        }

        ChanceRow row() {
            return new ChanceRow(SCRATCH_ID, SCRATCH_NAME, List.of(ticketTokens), Map.of(ticketTokens, rtp), null,
                    paytable, null, closed ? Map.of("closed", true) : Map.of());
        }
    }

    /**
     * One Prize Counter row.
     *
     * @param category its counter tab, lower case ({@code boosts}, {@code hunt}, {@code cosmetics},
     *                 {@code perks}, {@code trophies}, {@code minis})
     * @param cost     tokens; a row priced per purchase (the +1 Home) gives its first price
     */
    public record PrizeRow(String id, String name, String category, int cost, String description) {
    }

    /**
     * One Card Pack sold for tokens.
     *
     * @param odds each rarity's chance as a fraction (they add up to 1), in rarity order
     */
    public record PackRow(String id, String name, int cost, Map<Rarity, Double> odds) {

        public PackRow {
            Map<Rarity, Double> copy = new EnumMap<>(Rarity.class);
            if (odds != null) {
                copy.putAll(odds);
            }
            odds = Collections.unmodifiableMap(copy);
        }
    }

    /** One achievement: {@code description} is the line the Achievements screen shows. */
    public record AchievementRow(String id, String name, String description, int tokens) {
    }

    /**
     * Where the writer reads a board's best rows for the {@code top} lists (the games' scores), and
     * a player's name (asked only while names may be shown).
     */
    public interface Boards {

        /** The board's best {@code limit} rows, best first (ties: whoever got there first). */
        List<GamesDao.ScoreRow> top(String game, String board, boolean lowerIsBetter, int limit);

        /** A player's name, or {@code null}. */
        String name(UUID player);
    }

    /** The board behind an entry ({@link FeedWriter#board}), for its {@code top} list. */
    public record BoardRef(String id, String game, String board, boolean lowerIsBetter, String unit) {
    }

    /** Fresh Courses' {@code fresh} object for an entry. */
    private record FreshPart(String id, FreshFeed.Fresh fresh) {
    }

    /** Fresh Courses' {@code classic} object for an entry. */
    private record ClassicPart(String id, FreshFeed.Classic classic) {
    }

    /** Fresh Courses' {@code freshHistory}. */
    private record History(List<FreshFeed.Entry> entries) {
    }

    /** Race Night's {@code events} ({@link FeedWriter#events}); the last one written wins. */
    private record EventsPart(Events events) {
    }

    /** The Weekly Cup's {@code cup} object for a course's entry ({@link FeedWriter#cup}; WP-C). */
    private record CupPart(String id, Cup cup) {
    }

    /** The states a live night and a past night may be published with (§A.7). */
    private static final Set<String> LIVE_STATES = Set.of("open", "racing", "break", "results");
    private static final Set<String> RECENT_STATES = Set.of("done", "called_off");
    /** The most start times and live standings published. */
    private static final int UPCOMING = 8;
    private static final int STANDINGS = 12;

    // ---- the writer -------------------------------------------------------------------------

    private final boolean showNames;
    private final int topSize;
    private final Boards boards;
    /** Everything written, in order: the entries and what goes with them (a mark for {@link #truncate}). */
    private final List<Object> writes = new ArrayList<>();
    private StarChart starChart;
    /** The Weekly Cup's parts by course id, gathered by {@link #json} before the entries are written. */
    private Map<String, Cup> cups = new HashMap<>();

    /** @param showNames {@code web.dashboard.arcade_show_names}: whether records carry a holder */
    public ArcadeFeed(boolean showNames) {
        this(showNames, 0, null);
    }

    /**
     * @param showNames {@code web.dashboard.arcade_show_names}: whether records carry a holder
     * @param topSize   {@code games.feed_top}: rows per {@code top} list (0 = none)
     * @param boards    where the {@code top} rows are read, or {@code null} for none
     */
    public ArcadeFeed(boolean showNames, int topSize, Boards boards) {
        this.showNames = showNames;
        this.topSize = Math.max(0, topSize);
        this.boards = boards;
    }

    @Override
    public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                       Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
        writes.add(new ChanceRow(id, name, stakes, rtpByStake, dailyLimit, paytable, rules, extra));
    }

    @Override
    public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                        String holder) {
        writes.add(new CabinetRow(id, name, board, unit, lowerIsBetter, best, holder));
    }

    @Override
    public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                       String holder) {
        writes.add(new CourseRow(id, name, kind, tier, recordMs, recordAt, holder));
    }

    @Override
    public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                     String holder) {
        writes.add(new GolfRow(id, name, holes, par, recordStrokes, recordAt, holder));
    }

    @Override
    public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                       String holder, Daily daily) {
        writes.add(new CourseRow(id, name, kind, tier, recordMs, recordAt, holder, daily));
    }

    @Override
    public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                     String holder, Daily daily) {
        writes.add(new GolfRow(id, name, holes, par, recordStrokes, recordAt, holder, daily));
    }

    @Override
    public void board(String id, String game, String board, boolean lowerIsBetter, String unit) {
        if (!blank(id) && !blank(game) && !blank(board)) {
            writes.add(new BoardRef(id, game, board, lowerIsBetter, unit));
        }
    }

    @Override
    public void fresh(String id, FreshFeed.Fresh fresh) {
        if (!blank(id) && fresh != null) {
            writes.add(new FreshPart(id, fresh));
        }
    }

    @Override
    public void classic(String id, FreshFeed.Classic classic) {
        if (!blank(id) && classic != null) {
            writes.add(new ClassicPart(id, classic));
        }
    }

    @Override
    public void freshHistory(List<FreshFeed.Entry> entries) {
        writes.add(new History(entries == null ? List.of() : List.copyOf(entries)));
    }

    @Override
    public void events(Events events) {
        if (events != null) {
            writes.add(new EventsPart(events));
        }
    }

    @Override
    public void arena(String id, String name, String shape) {
        writes.add(new ArenaRow(id, name, shape));
    }

    @Override
    public void cup(String id, Cup cup) {
        if (!blank(id) && cup != null) {
            writes.add(new CupPart(id, cup));
        }
    }

    /** How many rows each {@code top} list has at most ({@code games.feed_top}). */
    public int topSize() {
        return topSize;
    }

    @Override
    public void starChart(String weekIso, Long best, String holder) {
        starChart = weekIso == null || weekIso.isBlank() ? null : new StarChart(weekIso, best, holder);
    }

    /** The Star Chart written so far, or {@code null}. */
    public StarChart starChart() {
        return starChart;
    }

    @Override
    public boolean showNames() {
        return showNames;
    }

    /** The website publishes the Fresh Courses history. */
    @Override
    public boolean wantsHistory() {
        return true;
    }

    /** The entries written so far, in order. */
    public List<GameRow> rows() {
        List<GameRow> out = new ArrayList<>();
        for (Object w : writes) {
            if (w instanceof GameRow r) {
                out.add(r);
            }
        }
        return out;
    }

    /** How many things have been written (entries and what goes with them) — a mark for {@link #truncate}. */
    public int size() {
        return writes.size();
    }

    /**
     * Forget everything written after the mark {@code size} ({@link #size()}): a game that threw
     * halfway through its {@code feed} leaves nothing half-written behind.
     */
    public void truncate(int size) {
        int keep = Math.max(0, size);
        while (writes.size() > keep) {
            writes.remove(writes.size() - 1);
        }
    }

    // ---- the feed ---------------------------------------------------------------------------

    /**
     * The feed as compact JSON (no whitespace). Every argument may be {@code null} (the section is
     * then absent), and {@code null} rows are skipped.
     *
     * @param featured today's featured game, or {@code null}
     * @param scratch  the Scratch Ticket ({@link #scratch}), or {@code null} while the Arcade is off
     */
    public String json(long generatedAt, Featured featured, Scratch scratch, List<PrizeRow> prizes,
                       List<PackRow> packs, List<AchievementRow> achievements) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"generatedAt\":").append(generatedAt);

        List<String> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String scratchEntry = scratch == null ? null : chance(scratch.row());
        if (scratchEntry != null) {
            entries.add(scratchEntry);
            ids.add(SCRATCH_ID);
        }
        Map<String, BoardRef> refs = new HashMap<>();
        Map<String, FreshFeed.Fresh> fresh = new HashMap<>();
        Map<String, FreshFeed.Classic> classics = new HashMap<>();
        cups = new HashMap<>();
        List<FreshFeed.Entry> history = List.of();
        Events events = null;
        for (Object w : writes) {
            switch (w) {
                case BoardRef b -> refs.put(key(b.id()), b);
                case FreshPart f -> fresh.put(key(f.id()), f.fresh());
                case ClassicPart c -> classics.put(key(c.id()), c.classic());
                case History h -> history = h.entries();
                case EventsPart e -> events = e.events();
                case CupPart p -> cups.put(key(p.id()), p.cup());
                default -> {
                    // an entry: written below, in order
                }
            }
        }
        for (GameRow row : rows()) {
            String entry = row == null || blank(row.id()) ? null : entry(row, refs, fresh, classics);
            if (entry != null && ids.add(row.id().toLowerCase(Locale.ROOT))) {
                entries.add(entry);
                // A world game pinned whole ("trials", "golf") makes every one of its courses
                // today's pick: publish it when at least one of those courses is in games[].
                if (row instanceof CourseRow) {
                    ids.add(TRIALS_ID);
                } else if (row instanceof GolfRow) {
                    ids.add(GOLF_ID);
                }
            }
        }
        array(sb, "games", entries);

        if (featured != null && !blank(featured.game()) && featured.until() > 0
                && ids.contains(featured.game().toLowerCase(Locale.ROOT))) {
            sb.append(",\"featured\":{\"game\":").append(Json.string(featured.game()))
                    .append(",\"until\":").append(featured.until()).append('}');
        }
        if (starChart != null && !blank(Json.plain(starChart.week()))) {
            sb.append(",\"starChart\":{\"week\":").append(Json.string(Json.plain(starChart.week())));
            if (starChart.best() != null) {
                sb.append(",\"best\":").append(Math.max(0, starChart.best()));
                holder(sb, starChart.holder());
            }
            sb.append('}');
        }
        List<String> past = new ArrayList<>();
        for (FreshFeed.Entry e : history) {
            if (e != null && !blank(e.code()) && !blank(e.slot())) {
                past.add(history(e));
            }
        }
        array(sb, "freshHistory", past);
        String eventsJson = events == null ? null : eventsJson(events);
        if (eventsJson != null) {
            sb.append(",\"events\":").append(eventsJson);
        }
        if (scratchEntry != null && scratch.pot() != null) {
            sb.append(",\"jackpots\":[{\"game\":").append(Json.string(SCRATCH_ID))
                    .append(",\"tokens\":").append(Math.max(0, scratch.pot())).append("}]");
        }

        List<String> out = new ArrayList<>();
        if (prizes != null) {
            for (PrizeRow p : prizes) {
                if (p != null && !blank(p.id())) {
                    out.add(prize(p));
                }
            }
        }
        array(sb, "prizes", out);

        out = new ArrayList<>();
        if (packs != null) {
            for (PackRow p : packs) {
                String pack = p == null || blank(p.id()) ? null : pack(p);
                if (pack != null) {
                    out.add(pack);
                }
            }
        }
        array(sb, "packs", out);

        out = new ArrayList<>();
        if (achievements != null) {
            for (AchievementRow a : achievements) {
                if (a != null && !blank(a.id())) {
                    out.add(achievement(a));
                }
            }
        }
        array(sb, "achievements", out);

        sb.append('}');
        return sb.toString();
    }

    private String entry(GameRow row, Map<String, BoardRef> refs, Map<String, FreshFeed.Fresh> fresh,
                         Map<String, FreshFeed.Classic> classics) {
        String k = key(row.id());
        return switch (row) {
            case ChanceRow c -> chance(c);
            case CabinetRow c -> cabinet(c);
            case CourseRow c -> course(c, fresh.get(k), classics.get(k), refs.get(k));
            case GolfRow g -> golf(g, fresh.get(k), classics.get(k), refs.get(k));
            case ArenaRow a -> arena(a, refs.get(k));
        };
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** A chance entry, or {@code null} when no stake has a publishable RTP. */
    private static String chance(ChanceRow r) {
        Map<Integer, Double> rtps = new LinkedHashMap<>();
        for (Integer stake : r.stakes()) {
            if (stake == null || stake <= 0 || rtps.containsKey(stake)) {
                continue;
            }
            Double f = r.rtpByStake().get(stake);
            if (f != null && Double.isFinite(f) && f > 0) {
                rtps.put(stake, f);
            }
        }
        if (rtps.isEmpty()) {
            return null;
        }
        double lowest = Double.MAX_VALUE;
        StringBuilder sb = new StringBuilder(512);
        head(sb, r.id(), r.name(), "chance");
        sb.append(",\"stakes\":[");
        boolean first = true;
        for (Integer stake : rtps.keySet()) {
            sb.append(first ? "" : ",").append(stake);
            first = false;
        }
        sb.append("],\"rtp\":");
        StringBuilder byStake = new StringBuilder("{");
        for (Map.Entry<Integer, Double> e : rtps.entrySet()) {
            double pct = RtpLimits.tenthPercent(e.getValue());
            lowest = Math.min(lowest, pct);
            byStake.append(byStake.length() > 1 ? "," : "").append(Json.string(Integer.toString(e.getKey())))
                    .append(':').append(tenth(pct));
        }
        sb.append(tenth(lowest)).append(",\"rtpByStake\":").append(byStake).append('}');
        if (r.dailyLimit() != null && r.dailyLimit() > 0) {
            sb.append(",\"dailyLimit\":").append(r.dailyLimit());
        }
        List<String> rows = new ArrayList<>();
        for (PayRow p : r.paytable()) {
            String row = payRow(p, rtps.keySet());
            if (row != null) {
                rows.add(row);
            }
        }
        array(sb, "paytable", rows);
        if (!blank(Json.plain(r.rules()))) {
            sb.append(",\"rules\":").append(Json.string(Json.plain(r.rules())));
        }
        for (Map.Entry<String, ?> e : r.extra().entrySet()) {
            if (e.getKey() == null || CHANCE_KEYS.contains(e.getKey()) || personal(e.getKey())) {
                continue;
            }
            String value = value(e.getValue());
            if (value != null) {
                sb.append(',').append(Json.string(e.getKey())).append(':').append(value);
            }
        }
        return sb.append('}').toString();
    }

    /**
     * One paytable row, or {@code null} for one the site must not show: a stake that isn't
     * published, a negative payout, or no odds (neither a probability in (0, 1] nor a count of
     * spaces).
     */
    private static String payRow(PayRow p, Set<Integer> stakes) {
        if (p == null || p.pays() < 0 || (p.stake() != null && !stakes.contains(p.stake()))) {
            return null;
        }
        Double chance = p.chance();
        boolean odds = chance != null && Double.isFinite(chance) && chance > 0 && chance <= 1;
        boolean spaces = p.spaces() != null && p.of() != null && p.spaces() > 0 && p.of() > 0 && p.spaces() <= p.of();
        if (!odds && !spaces) {
            return null;
        }
        StringBuilder sb = new StringBuilder(96).append('{');
        if (p.stake() != null) {
            sb.append("\"stake\":").append(p.stake()).append(',');
        }
        sb.append("\"combo\":").append(Json.string(Json.plain(p.combo())));
        sb.append(",\"pays\":").append(p.pays());
        if (odds) {
            sb.append(",\"chance\":").append(Json.sig(chance, 4));
            sb.append(",\"oneIn\":").append(FeedWriter.oneIn(chance));
        }
        if (spaces) {
            sb.append(",\"spaces\":").append(p.spaces()).append(",\"of\":").append(p.of());
        }
        return sb.append('}').toString();
    }

    private String cabinet(CabinetRow r) {
        StringBuilder sb = new StringBuilder(160);
        head(sb, r.id(), r.name(), "cabinet");
        sb.append(",\"board\":").append(Json.string(Json.plain(r.board())));
        sb.append(",\"unit\":").append(Json.string(Json.plain(r.unit())));
        sb.append(",\"lowerIsBetter\":").append(r.lowerIsBetter());
        if (r.best() != null) {
            sb.append(",\"best\":").append(r.best());
            holder(sb, r.holder());
        }
        top(sb, r.id(), r.board(), r.lowerIsBetter(), r.unit(), topSize);
        return sb.append('}').toString();
    }

    private String course(CourseRow r, FreshFeed.Fresh fresh, FreshFeed.Classic classic, BoardRef ref) {
        StringBuilder sb = new StringBuilder(160);
        head(sb, r.id(), r.name(), Json.plain(r.kind()).toLowerCase(Locale.ROOT));
        if (!blank(Json.plain(r.tier()))) {
            sb.append(",\"tier\":").append(Json.string(Json.plain(r.tier()).toLowerCase(Locale.ROOT)));
        }
        if (r.recordMs() != null) {
            sb.append(",\"record\":{\"ms\":").append(r.recordMs());
            record(sb, r.recordAt(), r.holder());
        }
        daily(sb, r.daily());
        freshParts(sb, fresh, classic);
        cup(sb, cups.get(key(r.id())));
        board(sb, ref);
        return sb.append('}').toString();
    }

    private String golf(GolfRow r, FreshFeed.Fresh fresh, FreshFeed.Classic classic, BoardRef ref) {
        StringBuilder sb = new StringBuilder(160);
        head(sb, r.id(), r.name(), "golf");
        sb.append(",\"holes\":").append(r.holes()).append(",\"par\":").append(r.par());
        if (r.recordStrokes() != null) {
            sb.append(",\"record\":{\"strokes\":").append(r.recordStrokes());
            record(sb, r.recordAt(), r.holder());
        }
        daily(sb, r.daily());
        freshParts(sb, fresh, classic);
        board(sb, ref);
        return sb.append('}').toString();
    }

    private String arena(ArenaRow r, BoardRef ref) {
        StringBuilder sb = new StringBuilder(128);
        head(sb, r.id(), r.name(), "arena");
        if (!blank(Json.plain(r.shape()))) {
            sb.append(",\"shape\":").append(Json.string(Json.plain(r.shape()).toLowerCase(Locale.ROOT)));
        }
        board(sb, ref);
        return sb.append('}').toString();
    }

    // ---- Race Night's events (§A.7) ------------------------------------------------------------

    /** The {@code events} object, or {@code null} when none of its parts has anything in it. */
    private String eventsJson(Events e) {
        StringBuilder sb = new StringBuilder(512);
        String next = e.next() == null || blank(e.next().id()) ? null : next(e.next());
        if (next != null) {
            sb.append("\"next\":").append(next);
        }
        List<String> upcoming = new ArrayList<>();
        for (Long at : e.upcoming()) {
            if (at != null && at > 0 && upcoming.size() < UPCOMING) {
                upcoming.add(Long.toString(at));
            }
        }
        if (!upcoming.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : ",").append("\"upcoming\":[").append(String.join(",", upcoming)).append(']');
        }
        String live = e.live() == null || blank(e.live().id()) ? null : live(e.live());
        if (live != null) {
            sb.append(sb.isEmpty() ? "" : ",").append("\"live\":").append(live);
        }
        List<String> recent = new ArrayList<>();
        for (Events.Recent r : e.recent()) {
            String row = r == null || blank(r.id()) || recent.size() >= Events.RECENT ? null : recent(r);
            if (row != null) {
                recent.add(row);
            }
        }
        if (!recent.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : ",").append("\"recent\":[").append(String.join(",", recent)).append(']');
        }
        String season = e.season() == null || blank(Json.plain(e.season().key())) ? null : season(e.season());
        if (season != null) {
            sb.append(sb.isEmpty() ? "" : ",").append("\"season\":").append(season);
        }
        return sb.isEmpty() ? null : "{" + sb + "}";
    }

    private static String next(Events.Next n) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(Json.string(Json.plain(n.id())));
        sb.append(",\"name\":").append(Json.string(blank(Json.plain(n.name())) ? "Race Night" : Json.plain(n.name())));
        sb.append(",\"joinAt\":").append(Math.max(0, n.joinAt()));
        sb.append(",\"startsAt\":").append(Math.max(0, n.startsAt()));
        eventCourse(sb, n.courseId(), n.courseName());
        sb.append(",\"races\":").append(Math.max(0, n.races()));
        sb.append(",\"laps\":").append(Math.max(0, n.laps()));
        sb.append(",\"entry\":\"free\"");
        List<String> prizes = new ArrayList<>();
        for (Integer p : n.prizes()) {
            prizes.add(Integer.toString(p == null ? 0 : Math.max(0, p)));
        }
        sb.append(",\"prizes\":[").append(String.join(",", prizes)).append(']');
        sb.append(",\"finisherPrize\":").append(Math.max(0, n.finisherPrize()));
        sb.append(",\"prizeNight\":").append(n.prizeNight());
        sb.append(",\"racers\":").append(Math.max(0, n.racers()));
        sb.append(",\"maxRacers\":").append(Math.max(0, n.maxRacers()));
        return sb.append('}').toString();
    }

    /** The live night, or {@code null} for a state the site doesn't know. */
    private String live(Events.Live l) {
        String state = Json.plain(l.state()).toLowerCase(Locale.ROOT);
        if (!LIVE_STATES.contains(state)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(Json.string(Json.plain(l.id())));
        sb.append(",\"state\":").append(Json.string(state));
        sb.append(",\"race\":").append(Math.max(0, l.race()));
        sb.append(",\"of\":").append(Math.max(0, l.of()));
        sb.append(",\"racers\":").append(Math.max(0, l.racers()));
        List<String> rows = new ArrayList<>();
        for (Events.Standing st : l.standings()) {
            if (st != null && rows.size() < STANDINGS) {
                StringBuilder row = new StringBuilder(96);
                row.append("{\"rank\":").append(Math.max(1, st.rank()));
                row.append(",\"points\":").append(Math.max(0, st.points()));
                row.append(",\"lap\":").append(Math.max(0, st.lap()));
                row.append(",\"laps\":").append(Math.max(0, st.laps()));
                holder(row, st.holder());
                rows.add(row.append('}').toString());
            }
        }
        array(sb, "standings", rows);
        return sb.append('}').toString();
    }

    /** A past night, or {@code null} for a state the site doesn't know. */
    private String recent(Events.Recent r) {
        String state = Json.plain(r.state()).toLowerCase(Locale.ROOT);
        if (!RECENT_STATES.contains(state)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(Json.string(Json.plain(r.id())));
        sb.append(",\"at\":").append(Math.max(0, r.at()));
        eventCourse(sb, r.courseId(), r.courseName());
        sb.append(",\"racers\":").append(Math.max(0, r.racers()));
        sb.append(",\"state\":").append(Json.string(state));
        List<String> rows = new ArrayList<>();
        for (Events.Top t : r.top()) {
            if (t != null && rows.size() < Events.ROWS) {
                StringBuilder row = new StringBuilder(96);
                row.append("{\"rank\":").append(Math.max(1, t.rank()));
                row.append(",\"value\":").append(Math.max(0, t.value()));
                row.append(",\"unit\":\"points\"");
                holder(row, t.holder());
                rows.add(row.append('}').toString());
            }
        }
        array(sb, "top", rows);
        return sb.append('}').toString();
    }

    private String season(Events.Season s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"key\":").append(Json.string(Json.plain(s.key())));
        sb.append(",\"name\":").append(Json.string(Json.plain(s.name())));
        sb.append(",\"until\":").append(Math.max(0, s.until()));
        top(sb, s.game(), s.board(), false, "points", topSize);
        return sb.append('}').toString();
    }

    /** {@code ,"course":{"id","name"}}, or nothing while no track is chosen. */
    private static void eventCourse(StringBuilder sb, String id, String name) {
        if (blank(Json.plain(id))) {
            return;
        }
        String plain = Json.plain(name);
        sb.append(",\"course\":{\"id\":").append(Json.string(Json.plain(id)));
        sb.append(",\"name\":").append(Json.string(blank(plain) ? Json.plain(id) : plain)).append('}');
    }

    /** Fresh Courses' {@code fresh} and {@code classic} objects (no player in either). */
    private static void freshParts(StringBuilder sb, FreshFeed.Fresh fresh, FreshFeed.Classic classic) {
        if (fresh != null && !blank(fresh.code())) {
            sb.append(",\"fresh\":").append(fresh.json());
        }
        if (classic != null && !blank(classic.code())) {
            sb.append(",\"classic\":").append(classic.json());
        }
    }

    /**
     * {@code ,"cup":{entry,pool,entrants,endsAt}}: this week's Weekly Cup on a course (WP-C), or nothing.
     * Only whole, sane numbers: a Cup with no entry or no end is left out.
     */
    private static void cup(StringBuilder sb, Cup c) {
        if (c == null || c.entry() <= 0 || c.endsAt() <= 0) {
            return;
        }
        sb.append(",\"cup\":{\"entry\":").append(c.entry())
                .append(",\"pool\":").append(Math.max(0, c.pool()))
                .append(",\"entrants\":").append(Math.max(0, c.entrants()))
                .append(",\"endsAt\":").append(c.endsAt()).append('}');
    }

    /** The {@code top} list of the board behind a course or golf entry, when a game named one. */
    private void board(StringBuilder sb, BoardRef ref) {
        if (ref != null) {
            top(sb, ref.game(), ref.board(), ref.lowerIsBetter(), ref.unit(), topSize);
        }
    }

    /**
     * {@code ,"top":[{rank,value,unit,at,holder?}]}: the board's best {@code limit} rows, ties sharing
     * a rank; nothing at all for no rows (or no {@link Boards}, or a limit of 0). A holder only while
     * names may be shown, looked up only then, and never a blank or a UUID.
     */
    private void top(StringBuilder sb, String game, String board, boolean lowerIsBetter, String unit, int limit) {
        if (boards == null || limit <= 0 || blank(game) || blank(board)) {
            return;
        }
        List<GamesDao.ScoreRow> rows;
        try {
            rows = boards.top(game, board, lowerIsBetter, limit);
        } catch (RuntimeException e) {
            return; // a board that can't be read has no top list; the rest of the feed goes out
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> values = new ArrayList<>();
        for (GamesDao.ScoreRow row : rows) {
            values.add(row.score());
        }
        List<Integer> ranks = FreshFeed.ranks(values);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            GamesDao.ScoreRow row = rows.get(i);
            String name = null;
            if (showNames && row.player() != null) {
                try {
                    name = boards.name(row.player());
                } catch (RuntimeException e) {
                    name = null;
                }
            }
            out.add(topRow(ranks.get(i), row.score(), unit, row.at(), name));
        }
        array(sb, "top", out);
    }

    /** One {@code top} row: {@code {"rank","value","unit","at","holder"?}}. */
    private String topRow(int rank, long value, String unit, long at, String holder) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"rank\":").append(rank).append(",\"value\":").append(value);
        sb.append(",\"unit\":").append(Json.string(blank(Json.plain(unit)) ? "points" : Json.plain(unit)));
        sb.append(",\"at\":").append(at);
        holder(sb, holder);
        return sb.append('}').toString();
    }

    /**
     * One {@code freshHistory} entry, keys in {@link FreshFeed#KEYS} order, written here so its
     * record's and its top rows' holders follow the writer's own rule (only while names may be
     * shown, never a blank or a UUID). Its {@code top} has at most 3 rows, and at most
     * {@code games.feed_top}.
     */
    private String history(FreshFeed.Entry e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"code\":").append(Json.string(Json.plain(e.code())));
        sb.append(",\"slot\":").append(Json.string(Json.plain(e.slot())));
        sb.append(",\"name\":").append(Json.string(Json.plain(e.name())));
        sb.append(",\"kind\":").append(Json.string(Json.plain(e.kind())));
        if (!blank(e.tier())) {
            sb.append(",\"tier\":").append(Json.string(Json.plain(e.tier())));
        }
        sb.append(",\"from\":").append(e.from());
        if (e.to() != null) {
            sb.append(",\"to\":").append(e.to());
        }
        sb.append(",\"seed\":").append(Json.string(Json.plain(e.seed())));
        sb.append(",\"plays\":").append(Math.max(0, e.plays()));
        FreshFeed.Record r = e.record();
        if (r != null && (r.ms() != null || r.strokes() != null)) {
            sb.append(r.ms() != null ? ",\"record\":{\"ms\":" + r.ms() : ",\"record\":{\"strokes\":" + r.strokes());
            record(sb, r.at(), r.holder());
        }
        if (!blank(e.kept())) {
            sb.append(",\"kept\":").append(Json.string(Json.plain(e.kept())));
        }
        if (e.classic()) {
            sb.append(",\"classic\":true");
        }
        int limit = Math.min(FreshFeed.TOP, topSize);
        List<FreshFeed.Record> shown = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (FreshFeed.Record t : e.top()) {
            if (shown.size() < limit && t != null && (t.ms() != null || t.strokes() != null)) {
                shown.add(t);
                values.add(t.ms() != null ? t.ms() : (long) t.strokes());
            }
        }
        List<Integer> ranks = FreshFeed.ranks(values);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            rows.add(topRow(ranks.get(i), values.get(i), e.unit(), shown.get(i).at(), shown.get(i).holder()));
        }
        array(sb, "top", rows);
        return sb.append('}').toString();
    }

    /**
     * A Fresh Courses course's {@code daily} object: its set's first day, when the next set is due
     * (when known), its star times (when set), and the set's length in days and last day (when
     * known); nothing at all for a hand-built course.
     */
    private static void daily(StringBuilder sb, Daily d) {
        if (d == null) {
            return;
        }
        sb.append(",\"daily\":{\"day\":").append(Json.string(Json.plain(d.day())));
        if (d.nextAt() > 0) {
            sb.append(",\"nextAt\":").append(d.nextAt());
        }
        if (d.goldMs() != null && d.goldMs() > 0) {
            sb.append(",\"goldMs\":").append(d.goldMs());
        }
        if (d.silverMs() != null && d.silverMs() > 0) {
            sb.append(",\"silverMs\":").append(d.silverMs());
        }
        if (d.cadence() > 0) {
            sb.append(",\"cadence\":").append(d.cadence());
        }
        if (!blank(Json.plain(d.lastDay()))) {
            sb.append(",\"lastDay\":").append(Json.string(Json.plain(d.lastDay())));
        }
        sb.append('}');
    }

    /** The rest of a {@code record} object: {@code at} when known, the holder when allowed, the brace. */
    private void record(StringBuilder sb, Long at, String holder) {
        if (at != null) {
            sb.append(",\"at\":").append(at);
        }
        holder(sb, holder);
        sb.append('}');
    }

    /** A record's holder: only while names are switched on, and never a blank or a UUID. */
    private void holder(StringBuilder sb, String holder) {
        String name = Json.plain(holder);
        if (showNames && !name.isEmpty() && !UUID_TEXT.matcher(name).matches()) {
            sb.append(",\"holder\":").append(Json.string(name));
        }
    }

    private static void head(StringBuilder sb, String id, String name, String kind) {
        sb.append("{\"id\":").append(Json.string(id));
        sb.append(",\"name\":").append(Json.string(Json.plain(name)));
        sb.append(",\"kind\":").append(Json.string(kind));
    }

    private static String prize(PrizeRow p) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"id\":").append(Json.string(p.id()));
        sb.append(",\"name\":").append(Json.string(Json.plain(p.name())));
        sb.append(",\"category\":").append(Json.string(Json.plain(p.category())));
        sb.append(",\"cost\":").append(Math.max(0, p.cost()));
        if (!blank(Json.plain(p.description()))) {
            sb.append(",\"description\":").append(Json.string(Json.plain(p.description())));
        }
        return sb.append('}').toString();
    }

    /** A pack, or {@code null} when none of its odds is a positive number. */
    private static String pack(PackRow p) {
        StringBuilder odds = new StringBuilder("{");
        for (Map.Entry<Rarity, Double> e : p.odds().entrySet()) {
            Double f = e.getValue();
            if (f != null && Double.isFinite(f) && f > 0) {
                odds.append(odds.length() > 1 ? "," : "").append(Json.string(e.getKey().name())).append(':')
                        .append(Json.sig(f * 100.0, 4));
            }
        }
        if (odds.length() == 1) {
            return null;
        }
        return "{\"id\":" + Json.string(p.id())
                + ",\"name\":" + Json.string(Json.plain(p.name()))
                + ",\"cost\":" + Math.max(0, p.cost())
                + ",\"odds\":" + odds + "}}";
    }

    private static String achievement(AchievementRow a) {
        return "{\"id\":" + Json.string(a.id())
                + ",\"name\":" + Json.string(Json.plain(a.name()))
                + ",\"description\":" + Json.string(Json.plain(a.description()))
                + ",\"tokens\":" + Math.max(0, a.tokens()) + "}";
    }

    /** {@code ,"key":[a,b,…]}, or nothing at all for an empty list. */
    private static void array(StringBuilder sb, String key, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(",\"").append(key).append("\":[").append(String.join(",", items)).append(']');
    }

    /** The one decimal an RTP percent is published with ({@code 90.0}, {@code 89.7}). */
    private static String tenth(double pct) {
        return String.format(Locale.ROOT, "%.1f", pct);
    }

    // ---- extra values -----------------------------------------------------------------------

    /**
     * An extra value as JSON, or {@code null} to leave it out: text (colour codes stripped; a
     * UUID-shaped string is left out), a whole or finite number, a boolean, an enum's name, or a
     * list or map of those. A map's keys that name a person are left out, and so is anything else
     * (a player, a location, an item) — the feed only ever publishes plain data.
     */
    private static String value(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof CharSequence s) {
            String text = Json.plain(s.toString());
            return UUID_TEXT.matcher(text).matches() ? null : Json.string(text);
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) {
                return null;
            }
            String four = Json.num4(d);
            return "0".equals(four) && d != 0.0 ? Json.sig(d, 4) : four;
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Enum<?> e) {
            return Json.string(e.name());
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> e : ordered(m).entrySet()) {
                if (e.getKey() == null || personal(String.valueOf(e.getKey()))) {
                    continue;
                }
                String item = value(e.getValue());
                if (item != null) {
                    sb.append(sb.length() > 1 ? "," : "").append(Json.string(String.valueOf(e.getKey())))
                            .append(':').append(item);
                }
            }
            return sb.append('}').toString();
        }
        if (v instanceof Iterable<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : list) {
                String item = value(o);
                if (item != null) {
                    sb.append(sb.length() > 1 ? "," : "").append(item);
                }
            }
            return sb.append(']').toString();
        }
        return null;
    }

    private static boolean personal(String key) {
        return PLAYER_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    /** A map in its own order when it has one, else sorted by key (numbers numerically, first). */
    private static <K, V> Map<K, V> ordered(Map<K, V> map) {
        Map<K, V> out = new LinkedHashMap<>();
        if (map instanceof SequencedMap<K, V>) {
            out.putAll(map);
            return out;
        }
        List<K> keys = new ArrayList<>(map.keySet());
        keys.sort(ArcadeFeed::compareKeys);
        for (K k : keys) {
            out.put(k, map.get(k));
        }
        return out;
    }

    private static int compareKeys(Object a, Object b) {
        String x = String.valueOf(a);
        String y = String.valueOf(b);
        Long nx = whole(x);
        Long ny = whole(y);
        if (nx != null && ny != null) {
            return Long.compare(nx, ny);
        }
        if (nx != null || ny != null) {
            return nx != null ? -1 : 1;
        }
        return x.compareTo(y);
    }

    private static Long whole(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---- from the Arcade's config (pure; the server passes what it read) ----------------------

    /**
     * The Scratch Ticket's feed side from its config and the pot now, or {@code null} when the
     * ticket isn't set up (no price, or no weight to draw from). One row per prize: each amount
     * of tokens with its chance (rows paying the same are one line; the "no win" row is not a
     * line) and the jackpot, which pays the pot now. The RTP is {@link ArcadeService#rtp} — the
     * number {@code /hcm arcade odds} prints — with the pot at its steady state.
     */
    public static Scratch scratch(PluginConfig.Lotto lotto, int pot) {
        if (lotto == null || lotto.ticketTokens() <= 0 || lotto.payouts() == null) {
            return null;
        }
        double total = 0;
        for (PluginConfig.LottoPayout p : lotto.payouts()) {
            if (p != null && p.weight() > 0) {
                total += p.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        // Key -1 is the jackpot; any other key is a token amount.
        Map<Integer, Double> weights = new LinkedHashMap<>();
        for (PluginConfig.LottoPayout p : lotto.payouts()) {
            if (p == null || !(p.weight() > 0) || (!p.jackpot() && p.tokens() <= 0)) {
                continue;
            }
            weights.merge(p.jackpot() ? -1 : p.tokens(), p.weight(), Double::sum);
        }
        int stake = lotto.ticketTokens();
        List<PayRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : weights.entrySet()) {
            int tokens = e.getKey();
            double chance = e.getValue() / total;
            if (tokens < 0) {
                rows.add(new PayRow(stake, "the jackpot", Math.max(0, pot), chance, null, null));
            } else {
                rows.add(new PayRow(stake, tokens + (tokens == 1 ? " token" : " tokens"), tokens, chance, null, null));
            }
        }
        return new Scratch(stake, ArcadeService.rtp(lotto), rows, weights.containsKey(-1) ? Math.max(0, pot) : null,
                ArcadeService.ticketClosed(lotto));
    }

    /**
     * The {@code prizes} rows from the Prize Counter's VISIBLE rows (the server passes
     * {@code PrizeService.visible(tab)} tab by tab): Trade In, Quest Reroll and the Rare Card are
     * actions, not prizes, and a disabled row never shows. The +1 Home publishes its first price.
     * Only the id, name, tab, price and the lines the counter shows go out — never a row's
     * commands, permissions, textures or limits.
     */
    public static List<PrizeRow> prizes(Collection<PluginConfig.Prize> visible) {
        List<PrizeRow> out = new ArrayList<>();
        if (visible == null) {
            return out;
        }
        for (PluginConfig.Prize p : visible) {
            if (p == null || !p.enabled() || p.type() == null || NOT_PRIZES.contains(p.type()) || blank(p.id())) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            if (p.description() != null) {
                for (String line : p.description()) {
                    String plain = Json.plain(line);
                    if (!plain.isEmpty()) {
                        lines.add(plain);
                    }
                }
            }
            String category = p.tab() == null ? "" : p.tab().name().toLowerCase(Locale.ROOT);
            out.add(new PrizeRow(p.id(), p.display(), category, p.priceAfter(0), String.join(" ", lines)));
        }
        return out;
    }

    /**
     * The {@code packs} rows: packs sold for tokens that hold at least one Card, with the odds they
     * roll with. An odds pack publishes its configured rarity odds normalised to 1
     * ({@code PackOdds.effective} over every rarity — the odds as configured, not today's sold-out
     * state); a hand-picked pool pack publishes its pool's weights added up by each Mini's rarity
     * ({@code rarityOf}; a Mini it doesn't know is left out). A pack with nothing to roll is left
     * out.
     */
    public static List<PackRow> packs(Collection<Pack.PackDef> defs, Function<String, Rarity> rarityOf) {
        List<PackRow> out = new ArrayList<>();
        if (defs == null) {
            return out;
        }
        Set<Rarity> every = EnumSet.allOf(Rarity.class);
        for (Pack.PackDef def : defs) {
            if (def == null || def.priceTokens() <= 0 || def.cardCount() <= 0 || blank(def.id())) {
                continue;
            }
            Map<Rarity, Double> odds;
            if (def.usesPool()) {
                Map<Rarity, Double> byRarity = new EnumMap<>(Rarity.class);
                for (Pack.PackEntry e : def.pool()) {
                    Rarity r = e == null || !(e.weight() > 0) || rarityOf == null ? null : rarityOf.apply(e.miniId());
                    if (r != null) {
                        byRarity.merge(r, e.weight(), Double::sum);
                    }
                }
                odds = PackOdds.effective(byRarity, every);
            } else {
                odds = PackOdds.effective(def.rarityOdds(), every);
            }
            if (!odds.isEmpty()) {
                out.add(new PackRow(def.id(), def.displayName(), def.priceTokens(), odds));
            }
        }
        return out;
    }

    /**
     * The {@code achievements} rows: enabled ones, in the order given (the Achievements screen's),
     * minus those won by a game of chance ({@link #chanceWin}). An achievement has one line of
     * text, which the screen shows as its name; it is both {@code name} and {@code description}.
     */
    public static List<AchievementRow> achievements(Collection<PluginConfig.AchievementDef> defs) {
        List<AchievementRow> out = new ArrayList<>();
        if (defs == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PluginConfig.AchievementDef def : defs) {
            if (def == null || !def.enabled() || blank(def.id()) || chanceWin(def)
                    || !seen.add(def.id().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new AchievementRow(def.id(), def.display(), def.display(), Math.max(0, def.reward())));
        }
        return out;
    }

    /**
     * Whether an achievement is earned by winning a game of chance, which the website never
     * advertises (spec §2): the Scratch Ticket's {@code jackpot} moment, or any count of
     * {@code jackpots}.
     */
    public static boolean chanceWin(PluginConfig.AchievementDef def) {
        if (def == null) {
            return false;
        }
        if ("jackpot".equalsIgnoreCase(def.id())) {
            return true;
        }
        return def.type() == PluginConfig.AchievementType.COUNTER && "jackpots".equalsIgnoreCase(def.key());
    }
}
