package com.dierks.homecraft.mini;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The rules for holiday Minis (the Holiday Collection, 0.38.6), kept free of Bukkit and of the
 * clock so every rule has a test.
 *
 * <p>A Mini with {@code event_only: true} is earned at its own event and nowhere else. So every
 * place that picks a Mini at random (Card Packs, the Arcade Crate's Card rewards, the Rare Card,
 * wild drops and the wild hunt) leaves it out, it never has Cards (a Card would be printed into
 * one), and the mint refuses it unless event code asks. Event code may mint it only from
 * {@code earn_from} to {@code earn_to}, both local days and both included. After {@code earn_to}
 * it is closed for good; copies already made keep working and can still be traded. The cap
 * applies as it does to every Mini.
 */
public final class EventMinis {

    /** Where a holiday Mini's earn window stands on a given day. */
    public enum State {
        /** The window has not started yet. */
        NOT_YET,
        /** Event code can give it today. */
        OPEN,
        /** Over for good, or never set up properly. */
        CLOSED
    }

    /** What a catalog row says about being a holiday Mini. */
    public record Window(boolean eventOnly, LocalDate from, LocalDate to) {
        /** An ordinary Mini. */
        public static final Window NONE = new Window(false, null, null);
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private EventMinis() {
    }

    // ---- random draws ------------------------------------------------------------------

    /** True if a random draw may give this Mini: anything but a holiday Mini. */
    public static boolean inDraws(MiniDef def) {
        return def != null && !def.eventOnly();
    }

    /**
     * True if Cards of this Mini may exist. A holiday Mini has none: a Card is printed into a
     * Mini, and only event code may make one. Every Card issuer asks this through
     * {@code CardService.canIssue}, so a pack pool or a crate naming one by id gives nothing.
     */
    public static boolean hasCards(MiniDef def) {
        return inDraws(def);
    }

    /**
     * The Minis the Card Binder's album shows and counts, in order (a fresh list): those that can have
     * Cards. A holiday Mini has none, so counting it would leave every binder one short for good.
     */
    public static List<MiniDef> album(Collection<MiniDef> catalog) {
        List<MiniDef> out = new ArrayList<>();
        if (catalog != null) {
            for (MiniDef def : catalog) {
                if (hasCards(def)) {
                    out.add(def);
                }
            }
        }
        return out;
    }

    /** The Minis of {@code pool} a random draw may give, in order (a fresh list). */
    public static List<MiniDef> drawable(Collection<MiniDef> pool) {
        List<MiniDef> out = new ArrayList<>();
        if (pool != null) {
            for (MiniDef def : pool) {
                if (inDraws(def)) {
                    out.add(def);
                }
            }
        }
        return out;
    }

    /**
     * The Minis carrying {@code tag}, for a tag pool (wild drops and crate rewards with a tag).
     * A blank tag is no pool at all; a holiday Mini is never in one, even if it is tagged.
     */
    public static List<MiniDef> tagPool(String tag, Collection<MiniDef> catalog) {
        List<MiniDef> out = new ArrayList<>();
        if (tag == null || tag.isBlank()) {
            return out;
        }
        for (MiniDef def : drawable(catalog)) {
            if (def.hasTag(tag)) {
                out.add(def);
            }
        }
        return out;
    }

    /** A crate Card reward's pool: {@code "*"} is every Mini a draw may give, anything else a tag pool. */
    public static List<MiniDef> crateTagPool(String tag, Collection<MiniDef> catalog) {
        return "*".equals(tag) ? drawable(catalog) : tagPool(tag, catalog);
    }

    /** The Minis a rarity-odds Card Pack draws from: those with its tag, or every one for {@code tag: ''}. */
    public static List<MiniDef> packCandidates(Pack.PackDef pack, Collection<MiniDef> catalog) {
        List<MiniDef> out = new ArrayList<>();
        for (MiniDef def : drawable(catalog)) {
            if (pack == null || !pack.hasTag() || def.hasTag(pack.tag())) {
                out.add(def);
            }
        }
        return out;
    }

    /** The Rare Card's pool: every Mini at or above {@code floor} a draw may give. */
    public static List<MiniDef> pityPool(Collection<MiniDef> catalog, Rarity floor) {
        List<MiniDef> out = new ArrayList<>();
        for (MiniDef def : drawable(catalog)) {
            if (floor == null || (def.rarity() != null && def.rarity().ordinal() >= floor.ordinal())) {
                out.add(def);
            }
        }
        return out;
    }

    /** The entries of a wild-drop loot list that name a Mini a draw may give ({@code lookup} finds it by id). */
    public static List<Loot.LootEntry> lootEntries(List<Loot.LootEntry> entries, Function<String, MiniDef> lookup) {
        List<Loot.LootEntry> out = new ArrayList<>();
        if (entries != null) {
            for (Loot.LootEntry e : entries) {
                if (inDraws(lookup.apply(e.miniId()))) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The wild hunt's pool when an admin forces a spawn: head Minis of {@code rarity} (null = any). */
    public static List<MiniDef> huntPool(Collection<MiniDef> catalog, Rarity rarity) {
        List<MiniDef> out = new ArrayList<>();
        for (MiniDef def : drawable(catalog)) {
            if (def.type() == MiniType.HEAD && (rarity == null || def.rarity() == rarity)) {
                out.add(def);
            }
        }
        return out;
    }

    // ---- the mint ----------------------------------------------------------------------

    /**
     * True if a mint may make this Mini: a holiday Mini only when event code asks, or an admin gives back one an
     * event owed (through {@code MiniService.giveAdmin}, inside its window and under its cap).
     */
    public static boolean mintAllowed(MiniDef def, boolean fromEvent) {
        return def != null && (!def.eventOnly() || fromEvent);
    }

    /** Where {@code from..to} stands on {@code today}; a missing or back-to-front window is closed. */
    public static State state(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null || to.isBefore(from) || today == null) {
            return State.CLOSED;
        }
        if (today.isBefore(from)) {
            return State.NOT_YET;
        }
        return today.isAfter(to) ? State.CLOSED : State.OPEN;
    }

    /** Where a Mini's earn window stands on {@code today}; an ordinary Mini is always open to events. */
    public static State state(MiniDef def, LocalDate today) {
        if (def == null) {
            return State.CLOSED;
        }
        return def.eventOnly() ? state(def.earnFrom(), def.earnTo(), today) : State.OPEN;
    }

    /**
     * Why event code may not mint this Mini today, or null when it may. A holiday Mini needs its
     * window open; every Mini needs room under its cap.
     *
     * @param mintedOut whether its copies (and wild spawns holding a slot) already reach the cap
     */
    public static String eventRefusal(MiniDef def, LocalDate today, boolean mintedOut) {
        if (def == null) {
            return "No such Mini.";
        }
        State s = state(def, today);
        if (s == State.NOT_YET) {
            return def.name() + " can be earned from " + day(def.earnFrom(), true) + ".";
        }
        if (s == State.CLOSED) {
            return def.name() + " is closed: its event is over.";
        }
        if (mintedOut) {
            return def.name() + " is minted out.";
        }
        return null;
    }

    /**
     * As {@link #eventRefusal(MiniDef, LocalDate, boolean)}, and a full bag refuses too. A holiday Mini is never
     * dropped at the player's feet: on the ground nothing protects it, a child can't pick it up with a full bag, and
     * it would despawn with its mint number spent. The event keeps it owed and tries again once a slot is free.
     *
     * @param hasRoom whether the player has an empty slot (a Mini never stacks, each copy has its own uid)
     */
    public static String eventRefusal(MiniDef def, LocalDate today, boolean mintedOut, boolean hasRoom) {
        String no = eventRefusal(def, today, mintedOut);
        if (no != null || hasRoom) {
            return no;
        }
        return def.name() + " needs an empty slot: make room first.";
    }

    // ---- the Museum --------------------------------------------------------------------

    /**
     * The Museum's line for a holiday Mini: {@code "Earn: 22 Nov - 29 Nov 2026"} until its last
     * day, then {@code "Closed"}. Null for an ordinary Mini. Plain ASCII, so Bedrock shows it.
     */
    public static String museumLine(MiniDef def, LocalDate today) {
        if (def == null || !def.eventOnly()) {
            return null;
        }
        if (state(def, today) == State.CLOSED) {
            return "Closed";
        }
        return "Earn: " + span(def.earnFrom(), def.earnTo());
    }

    /** {@code "22 Nov - 29 Nov 2026"}, or {@code "31 Dec 2026 - 3 Jan 2027"} across a new year. */
    public static String span(LocalDate from, LocalDate to) {
        if (from.getYear() == to.getYear()) {
            return day(from, false) + " - " + day(to, true);
        }
        return day(from, true) + " - " + day(to, true);
    }

    private static String day(LocalDate d, boolean withYear) {
        String s = d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
        return withYear ? s + " " + d.getYear() : s;
    }

    // ---- config ------------------------------------------------------------------------

    /**
     * Read {@code event_only}, {@code earn_from} and {@code earn_to} from one {@code minis.series}
     * entry. Never throws; at most one WARN per Mini, naming what to fix. A bad or missing date
     * leaves a holiday Mini closed (nothing can give it) rather than open forever.
     */
    public static Window readWindow(Map<?, ?> entry, String id, Consumer<String> warn) {
        if (entry == null) {
            return Window.NONE;
        }
        Consumer<String> w = warn != null ? warn : line -> { };
        Object rawOnly = entry.get("event_only");
        boolean eventOnly = rawOnly instanceof Boolean b ? b
                : rawOnly != null && Boolean.parseBoolean(String.valueOf(rawOnly).trim());
        String[] problem = new String[1];
        LocalDate from = date(entry.get("earn_from"), "earn_from", problem);
        LocalDate to = date(entry.get("earn_to"), "earn_to", problem);
        String tail = eventOnly ? "; no event can give it until that is fixed." : "; it is ignored.";
        if (problem[0] != null) {
            w.accept("minis.series: Mini '" + id + "': " + problem[0] + tail);
        } else if (from != null && to != null && to.isBefore(from)) {
            w.accept("minis.series: Mini '" + id + "': earn_to " + to + " is before earn_from " + from + tail);
        } else if (eventOnly && (from == null || to == null)) {
            w.accept("minis.series: Mini '" + id + "' has event_only: true but no "
                    + (from == null ? "earn_from" : "earn_to") + "; no event can give it until both are set.");
        }
        return new Window(eventOnly, from, to);
    }

    /** One date: text {@code YYYY-MM-DD}, or the date YAML makes of it when it is written without quotes. */
    private static LocalDate date(Object raw, String key, String[] problem) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw instanceof java.util.Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            if (problem[0] == null) {
                problem[0] = key + " '" + s + "' is not a date like \"2026-11-22\" (YYYY-MM-DD)";
            }
            return null;
        }
    }

    /**
     * Write a Mini's holiday keys into its catalog entry, as {@link #readWindow} reads them back.
     * An ordinary Mini gets none of them, so its row is written exactly as before.
     */
    public static void writeWindow(MiniDef def, Map<String, Object> entry) {
        if (def.eventOnly()) {
            entry.put("event_only", true);
        }
        if (def.earnFrom() != null) {
            entry.put("earn_from", def.earnFrom().toString());
        }
        if (def.earnTo() != null) {
            entry.put("earn_to", def.earnTo().toString());
        }
    }
}
