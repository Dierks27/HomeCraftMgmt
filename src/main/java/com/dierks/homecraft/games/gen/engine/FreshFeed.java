package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the website's Arcade feed says about Fresh Courses' past and present courses
 * (GEN-SPEC-KEEP §8), as pure data and the exact JSON, so the feed builder only has to place it.
 *
 * <ul>
 *   <li>Each generated course in {@code games[]} gains
 *       {@code "fresh":{"code":"HARD-40","seed":"3f2a9c01b7de","from":ms,"to":ms,"cadenceDays":7}}
 *       ({@link Fresh}); {@code to} is the scheduled next change, absent while pinned forever.</li>
 *   <li>A Classics slot's entry gains {@code "classic":{"code":…,"from":ms,"to":ms}} ({@link Classic}):
 *       the recall's window, {@code to} absent for "forever".</li>
 *   <li>A top-level {@code freshHistory} array, newest first, at most {@code feed_history} per
 *       slot ({@link #history}, {@link #json(List)}): {@code {"code","slot","name","kind","tier"?,
 *       "from","to"?,"seed","plays","record"?:{"ms"|"strokes","at","holder"?},"kept"?,"classic"?,
 *       "top"?:[{"rank","value","unit","at","holder"?}]}} (EXTRAS E3: the board's best {@value #TOP}
 *       rows, ties sharing a rank). The array is omitted when empty.</li>
 * </ul>
 *
 * <p><b>What is never published.</b> Only editions that have been live: an archive row is written
 * by the flip that made it live, and a row whose start is after "now" is left out anyway, so
 * nothing about the NEXT edition — not its seed, not its code — is ever in the feed. No plan, no
 * block, no half, no UUID. The seed is the short one (12 hex digits): seeds are HMAC-SHA256 over
 * the server's secret, one-way, so a published seed tells nobody the secret, a future seed or a
 * cabinet's board. A record's holder is named only when names may be shown
 * ({@code arcade_show_names}), like every other record.
 */
public final class FreshFeed {

    /**
     * One past (or current) course.
     *
     * @param code    its course code
     * @param slot    the slot it was made for
     * @param name    its name then
     * @param kind    parkour, elytra, golf or boat
     * @param tier    easy, medium or hard (not for golf)
     * @param from    when it went live (epoch ms)
     * @param to      when it was replaced, or its scheduled end while live; {@code null} when unknown
     * @param seed    the short seed (12 hex)
     * @param plays   counted runs on its board
     * @param record  its record, or {@code null}
     * @param kept    the course it was kept as, or {@code null}
     * @param classic whether it is recalled into a Classics slot now
     * @param top     its board's best rows, best first (at most {@value #TOP}; holders only when names may be shown)
     */
    public record Entry(String code, String slot, String name, String kind, String tier, long from, Long to,
                        String seed, long plays, Record record, String kept, boolean classic, List<Record> top) {

        public Entry {
            top = top == null ? List.of() : List.copyOf(top);
        }

        /** An entry without a top list. */
        public Entry(String code, String slot, String name, String kind, String tier, long from, Long to, String seed,
                     long plays, Record record, String kept, boolean classic) {
            this(code, slot, name, kind, tier, from, to, seed, plays, record, kept, classic, List.of());
        }

        /** Its unit: {@code strokes} for golf, else {@code ms}. */
        public String unit() {
            return "golf".equals(kind) ? "strokes" : "ms";
        }
    }

    /**
     * A board's record: a time ({@code ms}) or golf strokes, when it was set, and who holds it
     * ({@code null} unless names may be shown).
     */
    public record Record(Long ms, Integer strokes, long at, String holder) {
    }

    /** A generated course's {@code fresh} object. */
    public record Fresh(String code, String seed, long from, Long to, int cadenceDays) {

        /** {@code {"code":…,"seed":…,"from":…,"to":…,"cadenceDays":…}} ({@code to} left out when {@code null}). */
        public String json() {
            StringBuilder sb = new StringBuilder("{");
            field(sb, "code", code);
            field(sb, "seed", seed);
            field(sb, "from", from);
            if (to != null) {
                field(sb, "to", to);
            }
            field(sb, "cadenceDays", (long) cadenceDays);
            return sb.append('}').toString();
        }
    }

    /** A Classics slot's {@code classic} object: the recalled course's code and the recall's window. */
    public record Classic(String code, long from, Long to) {

        /** {@code {"code":…,"from":…,"to":…}} ({@code to} left out for "forever"). */
        public String json() {
            StringBuilder sb = new StringBuilder("{");
            field(sb, "code", code);
            field(sb, "from", from);
            if (to != null) {
                field(sb, "to", to);
            }
            return sb.append('}').toString();
        }
    }

    /** What a board holds, as the history needs it: its plays, its record and its best rows (best first). */
    public record Board(long plays, GamesDao.ScoreRow best, List<GamesDao.ScoreRow> top) {

        public Board {
            top = top == null ? (best == null ? List.of() : List.of(best)) : List.copyOf(top);
        }

        /** A board whose top list is its record alone. */
        public Board(long plays, GamesDao.ScoreRow best) {
            this(plays, best, null);
        }
    }

    /** How many of a past course's best rows the website gets ({@code freshHistory[].top}). */
    public static final int TOP = 3;

    private FreshFeed() {
    }

    /** A seed as the website shows it: its first 12 hex digits. */
    public static String shortSeed(long seed) {
        return GenSeed.hex(seed).substring(0, 12);
    }

    /**
     * The {@code freshHistory} entries: every archived edition in {@code rows} that went live at or
     * before {@code now}, at most {@code perSlot} per slot (the newest), newest first.
     *
     * @param boards    each row's board ({@link GenArchiveDao.Row#board()}) → what it holds
     * @param liveEnd   a live row's slot → its scheduled end, or {@code null} (pinned forever)
     * @param recalled  {@code slot|edition} of every edition recalled into a Classics slot now
     * @param showNames whether a record's holder may be named
     * @param nameOf    a player's name
     */
    public static List<Entry> history(List<GenArchiveDao.Row> rows, Function<String, Board> boards,
                                      Function<String, Long> liveEnd, Set<String> recalled, int perSlot,
                                      long now, boolean showNames, Function<java.util.UUID, String> nameOf) {
        List<GenArchiveDao.Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(GenArchiveDao.Row::startsAt).reversed()
                .thenComparing(GenArchiveDao.Row::slot).thenComparing(Comparator.comparingInt(GenArchiveDao.Row::seq)
                        .reversed()));
        java.util.Map<String, Integer> per = new java.util.HashMap<>();
        List<Entry> out = new ArrayList<>();
        for (GenArchiveDao.Row r : sorted) {
            if (r.startsAt() > now || perSlot <= 0) {
                continue; // never an edition that isn't live yet
            }
            int n = per.merge(r.slot(), 1, Integer::sum);
            if (n > perSlot) {
                continue;
            }
            Board b = boards.apply(r.board());
            Record record = null;
            boolean golf = Slots.GOLF.equals(r.generator()) || "golf".equals(r.kind());
            if (b != null && b.best() != null) {
                record = record(b.best(), golf, showNames, nameOf);
            }
            List<Record> top = new ArrayList<>();
            for (GamesDao.ScoreRow row : b == null ? List.<GamesDao.ScoreRow>of() : b.top()) {
                if (row != null && top.size() < TOP) {
                    top.add(record(row, golf, showNames, nameOf));
                }
            }
            Long to = r.endsAt() != null ? r.endsAt() : liveEnd == null ? null : liveEnd.apply(r.slot());
            String tier = golf || r.tierOrMix() == null || r.tierOrMix().isBlank() ? null
                    : r.tierOrMix().toLowerCase(Locale.ROOT);
            out.add(new Entry(r.code(), r.slot(), r.name(), r.kind(), tier, r.startsAt(), to, shortSeed(r.seed()),
                    b == null ? 0 : b.plays(), record, r.keptAs(), recalled != null
                    && recalled.contains(r.slot() + "|" + r.edition()), top));
        }
        return out;
    }

    /** A board row as a record: a time or strokes, when, and its holder only when names may be shown. */
    private static Record record(GamesDao.ScoreRow row, boolean golf, boolean showNames,
                                 Function<java.util.UUID, String> nameOf) {
        String holder = showNames && nameOf != null ? nameOf.apply(row.player()) : null;
        return golf ? new Record(null, (int) row.score(), row.at(), holder) : new Record(row.score(), null, row.at(), holder);
    }

    /**
     * Each row's rank, best first, ties sharing one (1, 1, 3): {@code values} in board order. The
     * one rule for every {@code top} list on the website.
     */
    public static List<Integer> ranks(List<Long> values) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            out.add(i > 0 && values.get(i).equals(values.get(i - 1)) ? out.get(i - 1) : i + 1);
        }
        return out;
    }

    /** The {@code freshHistory} array as JSON; {@code null} when it is empty (the feed leaves it out). */
    public static String json(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (Entry e : entries) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(json(e));
        }
        return sb.append(']').toString();
    }

    /** One entry as JSON, keys in the order above; optional ones left out when empty. */
    public static String json(Entry e) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "code", e.code());
        field(sb, "slot", e.slot());
        field(sb, "name", e.name());
        field(sb, "kind", e.kind());
        if (e.tier() != null) {
            field(sb, "tier", e.tier());
        }
        field(sb, "from", e.from());
        if (e.to() != null) {
            field(sb, "to", e.to());
        }
        field(sb, "seed", e.seed());
        field(sb, "plays", e.plays());
        if (e.record() != null) {
            Record r = e.record();
            StringBuilder rec = new StringBuilder("{");
            if (r.ms() != null) {
                field(rec, "ms", r.ms());
            } else {
                field(rec, "strokes", (long) r.strokes());
            }
            field(rec, "at", r.at());
            if (r.holder() != null) {
                field(rec, "holder", r.holder());
            }
            raw(sb, "record", rec.append('}').toString());
        }
        if (e.kept() != null) {
            field(sb, "kept", e.kept());
        }
        if (e.classic()) {
            raw(sb, "classic", "true");
        }
        if (!e.top().isEmpty()) {
            List<Long> values = new ArrayList<>();
            for (Record r : e.top()) {
                values.add(r.ms() != null ? r.ms() : (long) r.strokes());
            }
            List<Integer> ranks = ranks(values);
            StringBuilder top = new StringBuilder("[");
            for (int i = 0; i < e.top().size(); i++) {
                Record r = e.top().get(i);
                StringBuilder row = new StringBuilder("{");
                field(row, "rank", ranks.get(i));
                field(row, "value", values.get(i));
                field(row, "unit", e.unit());
                field(row, "at", r.at());
                if (r.holder() != null) {
                    field(row, "holder", r.holder());
                }
                top.append(i > 0 ? "," : "").append(row.append('}'));
            }
            raw(sb, "top", top.append(']').toString());
        }
        return sb.append('}').toString();
    }

    /** A map-free check for tests and the feed: which keys an entry's JSON may have. */
    public static final List<String> KEYS = List.of("code", "slot", "name", "kind", "tier", "from", "to", "seed",
            "plays", "record", "kept", "classic", "top");

    private static void field(StringBuilder sb, String key, String value) {
        raw(sb, key, value == null ? "null" : quote(value));
    }

    private static void field(StringBuilder sb, String key, long value) {
        raw(sb, key, Long.toString(value));
    }

    private static void raw(StringBuilder sb, String key, String json) {
        if (sb.length() > 1) {
            sb.append(',');
        }
        sb.append(quote(key)).append(':').append(json);
    }

    /** A JSON string: quotes, backslashes and control characters escaped. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Unused keys of an entry never appear: for callers that build a map instead of JSON. */
    public static Map<String, Object> map(Entry e) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", e.code());
        m.put("slot", e.slot());
        m.put("name", e.name());
        m.put("kind", e.kind());
        if (e.tier() != null) {
            m.put("tier", e.tier());
        }
        m.put("from", e.from());
        if (e.to() != null) {
            m.put("to", e.to());
        }
        m.put("seed", e.seed());
        m.put("plays", e.plays());
        if (e.record() != null) {
            java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
            if (e.record().ms() != null) {
                r.put("ms", e.record().ms());
            } else {
                r.put("strokes", e.record().strokes());
            }
            r.put("at", e.record().at());
            if (e.record().holder() != null) {
                r.put("holder", e.record().holder());
            }
            m.put("record", r);
        }
        if (e.kept() != null) {
            m.put("kept", e.kept());
        }
        if (e.classic()) {
            m.put("classic", true);
        }
        if (!e.top().isEmpty()) {
            List<Map<String, Object>> top = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            for (Record r : e.top()) {
                values.add(r.ms() != null ? r.ms() : (long) r.strokes());
            }
            List<Integer> ranks = ranks(values);
            for (int i = 0; i < e.top().size(); i++) {
                java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("rank", ranks.get(i));
                row.put("value", values.get(i));
                row.put("unit", e.unit());
                row.put("at", e.top().get(i).at());
                if (e.top().get(i).holder() != null) {
                    row.put("holder", e.top().get(i).holder());
                }
                top.add(row);
            }
            m.put("top", top);
        }
        return m;
    }
}
