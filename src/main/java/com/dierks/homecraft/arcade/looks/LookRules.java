package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.storage.LookDao.Look;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The wardrobe's rules (SHOP-V2-SPEC §3), as plain functions with no server: the dates a rented look runs to, the
 * buy-again window and its price, which heads-up is due, and what a sweep does with each key it finds. Every caller
 * ({@link LookService}, the menus, the commands) asks here, so each rule is pinned by a test in one place.
 *
 * <p>The model in five rules (§3.1): the right is a {@code cosmetics_owned} row; live means {@code expires_at > now},
 * kept means more than {@link #KEPT} left (collectors and Keepers write 36,500 days); a key works only on its owner
 * and only while the right is live, and any other key is deleted when it is seen, but never on an unknown answer;
 * nothing renews by itself; a rental is a TRAIL or HAT row that is neither a collector nor a Keeper.
 */
public final class LookRules {

    public static final long DAY = 86_400_000L;
    /** Buy again opens this long before the end... */
    public static final long WINDOW_BEFORE = 7 * DAY;
    /** ...and stays open this long after it. */
    public static final long WINDOW_AFTER = 30 * DAY;
    /** More than this left is "kept for good": a collector or a Keeper (they write 36,500 days). */
    public static final long KEPT = 3650 * DAY;

    /** "18 Jan", as the Prize Counter writes a collector's last day. */
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private LookRules() {
    }

    // ---- dates (§3.3) ----------------------------------------------------------------------

    /** The last local day of a run that ends at {@code expiresAt}: an end at 19 Jan 00:00 is "until 18 Jan". */
    public static LocalDate lastDay(long expiresAt, ZoneId zone) {
        return Instant.ofEpochMilli(expiresAt - 1).atZone(zone).toLocalDate();
    }

    /** Today, where the players live. */
    public static LocalDate today(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }

    /**
     * The last day after a buy of {@code days}: a live run adds its days to its last day (a renewal), anything else
     * starts today (20 Oct + 90 = 18 Jan).
     */
    public static LocalDate newLastDay(Look row, int days, long now, ZoneId zone) {
        if (row != null && row.expiresAt() > now) {
            return lastDay(row.expiresAt(), zone).plusDays(days);
        }
        return today(now, zone).plusDays(days);
    }

    /** When a run whose last day is {@code lastDay} ends: the local midnight after it (DST-safe, as GameClock). */
    public static long endOf(LocalDate lastDay, ZoneId zone) {
        return lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The start of the run after a buy: a live run keeps its start (when it has one), anything else starts now. */
    public static long since(Look row, long now) {
        return row != null && row.expiresAt() > now && row.since() > 0 ? row.since() : now;
    }

    /** "18 Jan". */
    public static String until(LocalDate day) {
        return UNTIL.format(day);
    }

    /** The whole days left before {@code end}, rounded up (0 once it has ended). */
    public static int daysLeft(long end, long now) {
        long left = end - now;
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (left + DAY - 1) / DAY);
    }

    /** "1 day", "6 days". */
    public static String days(int n) {
        return n + (n == 1 ? " day" : " days");
    }

    // ---- the window and the price (§3.4) ----------------------------------------------------

    /** Where a right stands for a buy: never had, kept for good, live (refused), open for buying again. */
    public enum Window { NEW, KEPT, LIVE, OPEN }

    public static Window window(Look row, long now) {
        if (row == null) {
            return Window.NEW;
        }
        if (row.expiresAt() - now > KEPT) {
            return Window.KEPT;
        }
        if (row.expiresAt() - now > WINDOW_BEFORE) {
            return Window.LIVE;
        }
        if (row.expiresAt() >= now - WINDOW_AFTER) {
            return Window.OPEN; // up to and including the 30th day after the end
        }
        return Window.NEW; // ended over 30 days ago: a first buy, at the full price
    }

    /** What a buy costs in {@code w}: the buy-again price while the window is open, else the full price. */
    public static int price(Prize p, Window w) {
        return w == Window.OPEN ? p.renewPrice() : p.costTokens();
    }

    /** The first day buy again opens: six days before the last day (7 days before the end). */
    public static LocalDate opensOn(long expiresAt, ZoneId zone) {
        return lastDay(expiresAt, zone).minusDays(6);
    }

    /** The last day buy again stays open: 30 days after the end ("until 17 Feb" for a run until 18 Jan). */
    public static LocalDate windowLastDay(long expiresAt, ZoneId zone) {
        return lastDay(expiresAt + WINDOW_AFTER, zone);
    }

    /** Why a buy in {@code w} is refused before any charge (LIVE, KEPT), or null when it may go on. */
    public static String refusal(Window w, Look row, ZoneId zone) {
        return switch (w) {
            case LIVE -> "You have it until " + until(lastDay(row.expiresAt(), zone)) + ". You can buy it again from "
                    + until(opensOn(row.expiresAt(), zone)) + ".";
            case KEPT -> "You have that for good.";
            default -> null;
        };
    }

    /** A first buy: never had, or ended. A first buy switches a trail on and closes the counter (§3.5). */
    public static boolean firstBuy(Look before, long now) {
        return before == null || before.expiresAt() <= now;
    }

    public static boolean live(Look row, long now) {
        return row != null && row.expiresAt() > now;
    }

    public static boolean kept(Look row, long now) {
        return row != null && row.expiresAt() - now > KEPT;
    }

    /** Listed in My Looks: live, kept, or ended in the last 30 days (while buy again is open). */
    public static boolean listed(Look row, long now) {
        return row != null && row.expiresAt() >= now - WINDOW_AFTER;
    }

    // ---- what comes on at an end (§3.9, red team O7) -----------------------------------------------

    /**
     * The trail to switch on when the one that was on has ended: only when a trail row is still switched on but ended
     * and no live one is on, the newest kept trail (a collector or a Keeper, by its start) whose row is still on the
     * counter ({@code hasRow}); else null (nothing to do, or nothing kept to switch on).
     */
    public static Look fallbackTrail(java.util.Collection<Look> rows, long now,
                                     java.util.function.Predicate<String> hasRow) {
        boolean liveOn = false;
        boolean endedOn = false;
        for (Look l : rows) {
            if (!"TRAIL".equals(l.kind()) || !l.enabled()) {
                continue;
            }
            if (l.expiresAt() > now) {
                liveOn = true;
            } else {
                endedOn = true;
            }
        }
        if (!endedOn || liveOn) {
            return null;
        }
        Look best = null;
        for (Look l : rows) {
            if ("TRAIL".equals(l.kind()) && kept(l, now) && hasRow.test(l.id())
                    && (best == null || l.since() > best.since())) {
                best = l;
            }
        }
        return best;
    }

    /** The ended, switched-on trail rows: what {@link #fallbackTrail} replaced, for its ENDED line. */
    public static List<Look> endedOn(java.util.Collection<Look> rows, long now) {
        List<Look> out = new ArrayList<>();
        for (Look l : rows) {
            if ("TRAIL".equals(l.kind()) && l.enabled() && l.expiresAt() <= now) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * Which bag slot's collector cap goes on when a worn key came off at its end and the head is empty: the newest
     * collector cap (the latest season start), the first slot on a tie; -1 for none. {@code seasonStarts[i]} is the
     * season start of the collector cap in bag slot i, or null for anything else.
     */
    public static int fallbackCap(LocalDate[] seasonStarts) {
        int best = -1;
        for (int i = 0; i < seasonStarts.length; i++) {
            LocalDate d = seasonStarts[i];
            if (d != null && (best < 0 || d.isAfter(seasonStarts[best]))) {
                best = i;
            }
        }
        return best;
    }

    // ---- heads-up notices (§3.10) -------------------------------------------------------------

    /** Which heads-up is due. */
    public enum Notice { NONE, SEVEN_DAYS, ONE_DAY, ENDED }

    /**
     * The heads-up due now for a run from {@code since} to {@code end}, given what was last sent ({@code stage} for
     * the end {@code noticeFor}). A new end resets what was sent. An offline player who crossed both thresholds gets
     * only the 1-day one; a row from before v37 ({@code since} 0) gets only the end; a crate's 1-day trail gets only
     * the end; ENDED is never sent twice. An end more than 30 days back is history, not news, and says nothing: the
     * first 0.38 join must not list every trail that ran out in 0.37 (and the start that brings v37 marks every end
     * already past as said, {@code LookDao.endsBeforeUpgrade}: 0.37 had its own line for those).
     */
    public static Notice due(long since, long end, long now, int stage, long noticeFor) {
        int sent = noticeFor == end ? stage : 0;
        long left = end - now;
        if (left <= 0) {
            return sent == -1 || -left > WINDOW_AFTER ? Notice.NONE : Notice.ENDED;
        }
        if (since <= 0) {
            return Notice.NONE;
        }
        long run = end - since;
        if (left <= DAY && run > DAY && sent != 1 && sent != -1) {
            return Notice.ONE_DAY;
        }
        if (left <= 7 * DAY && run > 7 * DAY && sent == 0) {
            return Notice.SEVEN_DAYS;
        }
        return Notice.NONE;
    }

    /** {@code notice_stage} once {@code n} is sent: 7, 1 or -1. */
    public static int stage(Notice n) {
        return switch (n) {
            case SEVEN_DAYS -> 7;
            case ONE_DAY -> 1;
            case ENDED -> -1;
            case NONE -> 0;
        };
    }

    /** A run of a day or less (a crate's trail): its end is one chat line, "Your 1-day ... is over.". */
    public static boolean shortRun(long since, long end) {
        return since > 0 && end - since <= DAY;
    }

    /**
     * An end said in one chat line, with no title, no price and no [Buy again] (red team O10): a run of a day or less,
     * or a run whose start isn't known ({@code since} 0, a row from before v37). 0.37's crate and shop wrote the
     * same rows, so a 0.37 crate trail can't be told from a bought one, and 0.37 ended every trail with one chat line
     * (red-team finding F1).
     */
    public static boolean quietEnd(long since, long end) {
        return since <= 0 || end - since <= DAY;
    }

    /**
     * Whether a heads-up may be shown now: never in a world game (or on the way back from one), in a Games world,
     * on the death screen, or while a Bedrock player has a chest screen of any kind open ({@code screenFree} false),
     * since on Bedrock it covers the title (red-team finding DJ-2: an Arcade cabinet game is a chest screen with no
     * game session). A held one waits: nothing is written until it is sent, so it goes once the screen closes.
     */
    public static boolean noticesAllowed(boolean inSession, boolean homeFromGames, boolean gamesWorld, boolean dead,
                                         boolean screenFree) {
        return !inSession && homeFromGames && !gamesWorld && !dead && screenFree;
    }

    /**
     * Whether nothing covers a player's title: Java draws it over any screen; on Bedrock only her own bag view (a
     * top inventory of type CRAFTING, as the "Trade?" screen's rule, {@code TradeRules.askScreenOpens}) leaves it
     * seen.
     */
    public static boolean screenFree(boolean bedrock, boolean noContainerOpen) {
        return !bedrock || noContainerOpen;
    }

    /** One heads-up to send: the row and what is due for it. */
    public record Due(Look row, Notice notice) {
    }

    /**
     * The heads-ups to send now, one per row that has one due, or none at all while they are held
     * ({@link #noticesAllowed}): a held notice writes no stage, so it is still due when it may be shown.
     */
    public static List<Due> dueNow(boolean allowed, List<Look> rows, long now) {
        List<Due> out = new ArrayList<>();
        if (!allowed || rows == null) {
            return out;
        }
        for (Look r : rows) {
            Notice n = due(r.since(), r.expiresAt(), now, r.noticeStage(), r.noticeFor());
            if (n != Notice.NONE) {
                out.add(new Due(r, n));
            }
        }
        return out;
    }

    /**
     * A heads-up's title (Bedrock reads titles), or null for none: a quiet end ({@link #quietEnd}: a crate's 1-day
     * trail, a row from before v37) is said in chat only.
     */
    public static String[] title(Notice n, String name, int daysLeft, boolean quiet) {
        return switch (n) {
            case SEVEN_DAYS -> new String[] {"&eYour " + name + ": " + days(daysLeft) + " left",
                    "&7Buy it again in My Looks"};
            case ONE_DAY -> new String[] {"&eLast day for your " + name + "!", "&7Buy it again in My Looks"};
            case ENDED -> quiet ? null : new String[] {"&6Your " + name + " went back to the shop",
                    "&7Get it again in My Looks"};
            case NONE -> null;
        };
    }

    /** The one title for several heads-ups due at once: "2 looks end soon", or "2 looks have ended". */
    public static String[] severalTitle(int count, boolean allEnded) {
        return new String[] {allEnded ? "&6" + count + " looks have ended" : "&e" + count + " looks end soon",
                "&7Look in My Looks"};
    }

    /**
     * The one title for the heads-ups sent together, or null for none. Only a notice with a title of its own counts
     * (red-team finding F2): quiet ends alone bring no title up, one titled notice shows its own, and two or more
     * show {@link #severalTitle} with their count. {@code name} turns a row id into its name.
     */
    public static String[] titleFor(List<Due> sent, long now, Function<String, String> name) {
        List<Due> titled = new ArrayList<>();
        for (Due d : sent) {
            if (title(d.notice(), "", 0, quietEnd(d.row().since(), d.row().expiresAt())) != null) {
                titled.add(d);
            }
        }
        if (titled.isEmpty()) {
            return null;
        }
        if (titled.size() == 1) {
            Look row = titled.get(0).row();
            return title(titled.get(0).notice(), name.apply(row.id()), daysLeft(row.expiresAt(), now),
                    quietEnd(row.since(), row.expiresAt()));
        }
        return severalTitle(titled.size(), titled.stream().allMatch(d -> d.notice() == Notice.ENDED));
    }

    /**
     * A heads-up's chat line. {@code price} is the buy-again price (below 0: none to offer, the row is off the
     * shelf or the end is quiet, {@link #quietEnd}); {@code fallbackOn} is the kept trail that came on when this one
     * ended, or null. The words don't scold and never say "for 30 days", which reads as the next run's length (§3.10).
     */
    public static String line(Notice n, String name, LocalDate lastDay, boolean shortRun, int price,
                              LocalDate windowLast, String fallbackOn) {
        return switch (n) {
            case SEVEN_DAYS -> "&eYour " + name + " ends on " + until(lastDay) + ".";
            case ONE_DAY -> "&eYour " + name + " ends tonight.";
            case ENDED -> {
                if (shortRun) {
                    yield "&7Your 1-day " + name + " is over.";
                }
                String head = fallbackOn != null
                        ? "&7Your " + name + " ended - your " + fallbackOn + (plural(fallbackOn) ? " are" : " is")
                        + " on now."
                        : "&7Your " + name + " has ended.";
                yield price < 0 ? head
                        : head + " Buy it again for " + price + " tokens until " + until(windowLast) + " (My Looks).";
            }
            case NONE -> null;
        };
    }

    /** The giver's one line when a present is a week from ending (red team O9): no title. */
    public static String giverLine(String name, String receiver, LocalDate lastDay) {
        return giverLine(name, receiver, lastDay, false);
    }

    /** The giver's one line; {@code ended}: the present ended before its receiver came by. */
    public static String giverLine(String name, String receiver, LocalDate lastDay, boolean ended) {
        return "&eThe " + name + " you gave " + receiver + (ended ? " ended on " : " ends on ") + until(lastDay) + ".";
    }

    /**
     * Whether a heads-up being sent brings the giver's one line (red team O9): the 7-day one, or, when the receiver
     * didn't come by in that week, whichever comes first after it (the 1-day one or the end) for a run long enough
     * to have had it, so every present's giver hears once (red-team finding F1-GIVER-SKIPPED). {@code d.row()} is the
     * row as it was before this notice.
     */
    public static boolean giverHears(Due d) {
        if (d.notice() == Notice.SEVEN_DAYS) {
            return true;
        }
        Look r = d.row();
        int sent = r.noticeFor() == r.expiresAt() ? r.noticeStage() : 0;
        return (d.notice() == Notice.ONE_DAY || d.notice() == Notice.ENDED) && sent == 0 && r.since() > 0
                && r.expiresAt() - r.since() > 7 * DAY;
    }

    /** The most giver lines one player keeps waiting for their join. */
    static final int GIVER_LINES_KEPT = 20;

    /**
     * An offline giver's waiting lines with one more (red-team finding WARD-1): kept in {@code game_prefs}, so a
     * restart before their next join loses none. One line per look and receiver (a newer one replaces it), oldest
     * first, at most {@value #GIVER_LINES_KEPT}. Each is {@code id TAB receiver TAB line}, one per text line.
     */
    public static String withGiverLine(String stored, String id, String receiver, String line) {
        List<String[]> lines = new ArrayList<>(giverLines(stored));
        lines.removeIf(l -> l[0].equals(id) && l[1].equalsIgnoreCase(receiver));
        lines.add(new String[] {id, receiver, line});
        while (lines.size() > GIVER_LINES_KEPT) {
            lines.remove(0);
        }
        StringBuilder out = new StringBuilder();
        for (String[] l : lines) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(clean(l[0])).append('\t').append(clean(l[1])).append('\t').append(clean(l[2]));
        }
        return out.toString();
    }

    /** {@link #withGiverLine}'s lines back: {@code [id, receiver, line]} each; a broken one is skipped. */
    public static List<String[]> giverLines(String stored) {
        List<String[]> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String row : stored.split("\n")) {
            String[] f = row.split("\t", 3);
            if (f.length == 3 && !f[0].isBlank() && !f[2].isBlank()) {
                out.add(f);
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** "Autumn Leaves 2026 are", "Twinkle 2026 is": the last word before a year ends in s. */
    static boolean plural(String name) {
        String[] words = name.trim().split("\\s+");
        for (int i = words.length - 1; i >= 0; i--) {
            if (!words[i].chars().allMatch(Character::isDigit)) {
                return words[i].toLowerCase(Locale.ROOT).endsWith("s");
            }
        }
        return false;
    }

    // ---- keys and sweeps (§3.8) -----------------------------------------------------------------

    /**
     * Whether an item is a key of look {@code id} for {@code holder}: bound to them ({@code hcm:bound_to}) AND of that
     * prize id. A hat sold before 0.38 has the same id and no binding, so it is never one (red team D-m6): no end,
     * take-off or sweep ever touches it.
     */
    public static boolean isKeyOf(UUID boundTo, String itemPrizeId, UUID holder, String id) {
        return boundTo != null && holder != null && boundTo.equals(holder) && itemPrizeId != null && id != null
                && itemPrizeId.equalsIgnoreCase(id);
    }

    /** What a sweep does with one key. */
    public enum Verdict { KEEP, REMOVE, REFRESH }

    /** A verdict and, for a REMOVE, why (the INFO line's reason). */
    public record Judged(Verdict verdict, String reason) {
        static final Judged KEEP = new Judged(Verdict.KEEP, null);
        static final Judged REFRESH = new Judged(Verdict.REFRESH, null);

        static Judged remove(String why) {
            return new Judged(Verdict.REMOVE, why);
        }
    }

    /**
     * One key's verdict, before the one-key-per-look rule ({@link #plan}): never delete on an unknown answer (the
     * rights didn't load); not the holder's, no right, or an ended right goes; a rental whose name was written for
     * another end is refreshed in place, and so is a Keeper of another look than the configured one (§5.2).
     *
     * @param keeper            a Keeper's key (it carries no end)
     * @param itemEnd           the end the key's name was written for ({@code look_end})
     * @param configuredVariant the Keeper look the config picks, or null when there is nothing to compare
     */
    public static Judged verdict(boolean loaded, UUID holder, UUID boundTo, Look right, long now, boolean keeper,
                                 long itemEnd, String itemVariant, String configuredVariant) {
        if (!loaded) {
            return Judged.KEEP;
        }
        if (boundTo == null || !boundTo.equals(holder)) {
            return Judged.remove("not yours");
        }
        if (right == null) {
            return Judged.remove("no right");
        }
        if (right.expiresAt() <= now) {
            return Judged.remove("ended");
        }
        if (!keeper && itemEnd != right.expiresAt()) {
            return Judged.REFRESH;
        }
        if (keeper && configuredVariant != null && !configuredVariant.equals(itemVariant)) {
            return Judged.REFRESH;
        }
        return Judged.KEEP;
    }

    /** Where a sweep reads: the helmet slot, the bag (0-35), the other armour, the off-hand, the ender chest. */
    public enum Area { HEAD, BAG, ARMOUR, OFF_HAND, ENDER_CHEST }

    /** What a key says about itself: its look, who it is bound to, the end its name was written for, its variant. */
    public record Key(String id, UUID boundTo, long itemEnd, String variant) {
    }

    /**
     * One key a sweep found: where (the area, the slot, and the slots inside the containers it sits in; an empty
     * path is the slot itself) and what it says.
     */
    public record Found(Area area, int slot, List<Integer> path, Key key) {
        public boolean nested() {
            return !path.isEmpty();
        }

        public boolean worn() {
            return area == Area.HEAD && path.isEmpty();
        }
    }

    /** One decision of a sweep. */
    public record Decision(Found found, Verdict verdict, String reason) {
    }

    /**
     * The keys in one slot's item: the item itself when it is a key, else every key inside it when it is a container
     * (a bundle or a shulker box), read with the fail-closed reader of D36 ({@code contents} says empty for one that
     * can't be read, or holds something past the depth limit). A container that can't be read is left alone: nothing
     * in it is found, so nothing in it is ever deleted. Works over any item type, so a test walks stand-ins.
     *
     * @param contents a container's slots by index (null for an empty one), or empty when unreadable
     */
    public static <T> List<Found> find(Area area, int slot, T item, Function<T, Key> keyOf,
                                       BiFunction<T, Integer, Optional<List<T>>> contents) {
        List<Found> out = new ArrayList<>();
        walk(area, slot, item, 0, new ArrayList<>(), keyOf, contents, out);
        return out;
    }

    private static <T> void walk(Area area, int slot, T item, int depth, List<Integer> path, Function<T, Key> keyOf,
                                 BiFunction<T, Integer, Optional<List<T>>> contents, List<Found> out) {
        if (item == null) {
            return;
        }
        Key key = keyOf.apply(item);
        if (key != null) {
            out.add(new Found(area, slot, List.copyOf(path), key));
            return;
        }
        Optional<List<T>> inside = contents.apply(item, depth);
        if (inside.isEmpty()) {
            return; // unreadable: never delete on an unknown answer
        }
        List<T> slots = inside.get();
        for (int i = 0; i < slots.size(); i++) {
            path.add(i);
            walk(area, slot, slots.get(i), depth + 1, path, keyOf, contents, out);
            path.remove(path.size() - 1);
        }
    }

    /** The order a holder's keys are kept in: the worn one, then the bag, the armour, the off-hand, the ender chest. */
    static final Comparator<Found> KEEP_ORDER = Comparator.comparing((Found f) -> !f.worn())
            .thenComparing(Found::nested)
            .thenComparing(Found::area)
            .thenComparingInt(Found::slot)
            .thenComparing(f -> f.path().toString());

    /**
     * A sweep over one holder's keys (§3.8): each key's {@link #verdict}, then one key per look (red team D-m7): of
     * several live keys of one look, the worn one (else the first in slot order) is kept and the rest go. Nothing at
     * all goes while the rights aren't loaded.
     *
     * @param rights            the holder's rows by id (case-insensitive ids are matched as written)
     * @param configuredVariant the Keeper look the config picks for an id, or null (may be null itself)
     */
    public static List<Decision> plan(boolean loaded, UUID holder, Map<String, Look> rights, long now,
                                      List<Found> keys, Function<String, String> configuredVariant) {
        List<Found> ordered = new ArrayList<>(keys);
        ordered.sort(KEEP_ORDER);
        Map<String, Found> kept = new HashMap<>();
        List<Decision> out = new ArrayList<>();
        for (Found f : ordered) {
            Key k = f.key();
            Look right = rights == null ? null : right(rights, k.id());
            boolean keeper = k.id() != null && k.id().trim().toLowerCase(Locale.ROOT).startsWith("keeper_");
            Judged j = verdict(loaded, holder, k.boundTo(), right, now, keeper, k.itemEnd(), k.variant(),
                    configuredVariant == null || !keeper ? null : configuredVariant.apply(k.id()));
            if (loaded && j.verdict() != Verdict.REMOVE) {
                String look = k.id().toLowerCase(Locale.ROOT);
                if (kept.containsKey(look)) {
                    j = Judged.remove("a second key");
                } else {
                    kept.put(look, f);
                }
            }
            out.add(new Decision(f, j.verdict(), j.reason()));
        }
        return out;
    }

    private static Look right(Map<String, Look> rights, String id) {
        if (id == null) {
            return null;
        }
        Look exact = rights.get(id);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Look> e : rights.entrySet()) {
            if (e.getKey().equalsIgnoreCase(id)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Where "Wear it" takes a key of one look from: none to take when one is on the head already (the head's), else
     * the first in the bag, the off-hand or the ender chest (red team D-m7: from the ender chest too, so it never makes
     * a copy while one is put away), or null to make a new one. A key inside a container is never taken.
     */
    public static Found wearFrom(List<Found> keysOfThisLook) {
        List<Found> top = new ArrayList<>();
        for (Found f : keysOfThisLook) {
            if (!f.nested()) {
                top.add(f);
            }
        }
        top.sort(KEEP_ORDER);
        return top.isEmpty() ? null : top.get(0);
    }

    /** Whether a sweep runs for a player now: never in a world game, on the way back from one, or while dead. */
    public static boolean sweeps(boolean inSession, boolean homeFromGames, boolean dead) {
        return !inSession && homeFromGames && !dead;
    }

    // ---- where a bought key goes (§3.5) -----------------------------------------------------------

    /** Where a new key goes after a buy or a present. */
    public enum Placement {
        /** An empty head. */
        HEAD,
        /** On the head, the key that was there deleted ("Wear it" makes it again). */
        HEAD_REPLACING_KEY,
        /** On the head, the old helmet moved into a free bag slot. */
        HEAD_SWAPPING,
        /** Into a free bag slot. */
        BAG,
        /** Nowhere: it is in My Looks (tap Wear it). Never the ground. */
        NOWHERE
    }

    /**
     * Where a bought key goes: an empty head; a head with a key on it (deleted); a head with a cosmetic (a collector
     * cap, a hat from before 0.38), moved into the bag, and with no room the cap stays on and the key goes nowhere.
     * Real armour is never taken off by a buy, so the key goes into the bag; a present swaps it into the bag, as a
     * collector present does. A helmet with Curse of Binding never comes off. A rented look never fails for room.
     */
    public static Placement placement(boolean headEmpty, boolean headKey, boolean headCosmetic, boolean headBinding,
                                      boolean bagRoom, boolean present) {
        if (headEmpty) {
            return Placement.HEAD;
        }
        if (headKey) {
            return Placement.HEAD_REPLACING_KEY;
        }
        if (headBinding) {
            return bagRoom ? Placement.BAG : Placement.NOWHERE;
        }
        if (headCosmetic || present) {
            return bagRoom ? Placement.HEAD_SWAPPING : Placement.NOWHERE;
        }
        return bagRoom ? Placement.BAG : Placement.NOWHERE;
    }

    /** "It's on your head!" and the rest, after a key was handed over. */
    /** {@code PlayerInventory}'s slot for the off-hand. */
    public static final int OFF_HAND_SLOT = 40;

    /**
     * Where "Wear it" takes a collector cap from (red-team finding BR-5): the first bag slot (0-35) holding it, else
     * the off-hand ({@value #OFF_HAND_SLOT}) when it is there, else -1. The same places My Looks calls "in your bag",
     * so a tile that says so never answers "It isn't with you."
     */
    public static int capSlot(String capId, List<String> storageIds, String offHandId) {
        if (capId == null) {
            return -1;
        }
        for (int i = 0; i < storageIds.size(); i++) {
            if (capId.equalsIgnoreCase(storageIds.get(i))) {
                return i;
            }
        }
        return capId.equalsIgnoreCase(offHandId) ? OFF_HAND_SLOT : -1;
    }

    /**
     * Whether {@code /hcm looks give} is a present, with its moment and the admin as the giver: only from a player to
     * someone else. From the console ({@code admin} null) it is a plain give, as {@code /hcm give prize} from the
     * console is (red-team finding CMD-6: her screen read "CONSOLE gave you the Lime Cap"); to yourself, no present.
     */
    public static boolean givenAsPresent(UUID admin, UUID to) {
        return admin != null && !admin.equals(to);
    }

    /** A plain give's chat line (from the console): "✔ Lime Cap - It's yours until 23 Oct! It's on your head!". */
    public static String plainGivenLine(String display, String line) {
        return "&a✔ " + display + " &7- " + line;
    }

    /**
     * The line of {@code /hcm looks give}'s present (red-team finding BR-4): until when, and where the key went
     * ({@link #where}; null for a trail, which has no key).
     */
    public static String givenLine(String until, String where) {
        return "It's yours until " + until + "!" + (where == null || where.isBlank() ? "" : " " + where);
    }

    public static String where(Placement p) {
        return switch (p) {
            case HEAD, HEAD_REPLACING_KEY -> "It's on your head!";
            case HEAD_SWAPPING -> "It's on your head! What you had on is in your bag.";
            case BAG -> "It's in your bag.";
            case NOWHERE -> "It's in My Looks - tap Wear it.";
        };
    }
}
