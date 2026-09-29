package com.dierks.homecraft.web;

import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackOdds;
import com.dierks.homecraft.mini.Rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code /api/arcade} JSON (games spec §10): the Arcade as the LilahCraft website shows it —
 * every open game with its published odds or its record, today's featured game, the Scratch
 * Ticket's pot, the Prize Counter, the token Card Packs and the achievements. Built on the main
 * thread alongside the other feeds and cached; the HTTP handler only serves the string.
 *
 * <pre>{"generatedAt":ms[,"games":[…]][,"featured":{"game":…,"until":ms}]
 * [,"jackpots":[{"game":"scratch_ticket","tokens":N}]][,"prizes":[…]][,"packs":[…]][,"achievements":[…]]}</pre>
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
 *   <li>{@code cabinet}: {@code {id,name,kind:"cabinet",board,unit,lowerIsBetter,best?}}.</li>
 *   <li>a course: {@code {id,name,kind:"parkour"|"elytra"|"boat",tier?,record?:{ms,at?}}}.</li>
 *   <li>{@code golf}: {@code {id,name,kind:"golf",holes,par,record?:{strokes,at?}}}.</li>
 * </ul>
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
 * minus the ones won by a game of chance ({@link #chanceWin}). Nothing about events yet.
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
    public sealed interface GameRow permits ChanceRow, CabinetRow, CourseRow, GolfRow {
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

    /** A time-trial course ({@link FeedWriter#course}). */
    public record CourseRow(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                            String holder) implements GameRow {
    }

    /** A mini golf course ({@link FeedWriter#golf}). */
    public record GolfRow(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                          String holder) implements GameRow {
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
     */
    public record Scratch(int ticketTokens, double rtp, List<PayRow> paytable, Integer pot) {

        public Scratch {
            paytable = paytable == null ? List.of() : List.copyOf(paytable);
        }

        ChanceRow row() {
            return new ChanceRow(SCRATCH_ID, SCRATCH_NAME, List.of(ticketTokens), Map.of(ticketTokens, rtp), null,
                    paytable, null, Map.of());
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

    // ---- the writer -------------------------------------------------------------------------

    private final boolean showNames;
    private final List<GameRow> games = new ArrayList<>();

    /** @param showNames {@code web.dashboard.arcade_show_names}: whether records carry a holder */
    public ArcadeFeed(boolean showNames) {
        this.showNames = showNames;
    }

    @Override
    public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                       Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
        games.add(new ChanceRow(id, name, stakes, rtpByStake, dailyLimit, paytable, rules, extra));
    }

    @Override
    public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                        String holder) {
        games.add(new CabinetRow(id, name, board, unit, lowerIsBetter, best, holder));
    }

    @Override
    public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                       String holder) {
        games.add(new CourseRow(id, name, kind, tier, recordMs, recordAt, holder));
    }

    @Override
    public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                     String holder) {
        games.add(new GolfRow(id, name, holes, par, recordStrokes, recordAt, holder));
    }

    @Override
    public boolean showNames() {
        return showNames;
    }

    /** The entries written so far, in order. */
    public List<GameRow> rows() {
        return List.copyOf(games);
    }

    /** How many entries have been written — a mark for {@link #truncate}. */
    public int size() {
        return games.size();
    }

    /**
     * Forget every entry written after {@code size} entries: a game that threw halfway through
     * its {@code feed} leaves nothing half-written behind.
     */
    public void truncate(int size) {
        int keep = Math.max(0, size);
        while (games.size() > keep) {
            games.remove(games.size() - 1);
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
        for (GameRow row : games) {
            String entry = row == null || blank(row.id()) ? null : entry(row);
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

    private String entry(GameRow row) {
        return switch (row) {
            case ChanceRow c -> chance(c);
            case CabinetRow c -> cabinet(c);
            case CourseRow c -> course(c);
            case GolfRow g -> golf(g);
        };
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
        return sb.append('}').toString();
    }

    private String course(CourseRow r) {
        StringBuilder sb = new StringBuilder(160);
        head(sb, r.id(), r.name(), Json.plain(r.kind()).toLowerCase(Locale.ROOT));
        if (!blank(Json.plain(r.tier()))) {
            sb.append(",\"tier\":").append(Json.string(Json.plain(r.tier()).toLowerCase(Locale.ROOT)));
        }
        if (r.recordMs() != null) {
            sb.append(",\"record\":{\"ms\":").append(r.recordMs());
            record(sb, r.recordAt(), r.holder());
        }
        return sb.append('}').toString();
    }

    private String golf(GolfRow r) {
        StringBuilder sb = new StringBuilder(160);
        head(sb, r.id(), r.name(), "golf");
        sb.append(",\"holes\":").append(r.holes()).append(",\"par\":").append(r.par());
        if (r.recordStrokes() != null) {
            sb.append(",\"record\":{\"strokes\":").append(r.recordStrokes());
            record(sb, r.recordAt(), r.holder());
        }
        return sb.append('}').toString();
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
        return new Scratch(stake, ArcadeService.rtp(lotto), rows, weights.containsKey(-1) ? Math.max(0, pot) : null);
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
