package com.dierks.homecraft.games.gen.admin;

import com.dierks.homecraft.games.gen.api.CourseCode;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of {@code history}, {@code recall} and {@code keep} (GEN-SPEC-KEEP §2-§4, §8), read
 * without a server so every form is tested.
 *
 * <p>An archived edition can be named many ways, because admins meet it in many places: its course
 * code from a tile or the website ({@code HARD-40}), its number alone ({@code 40}), its edition key
 * from status ({@code 7:40}), {@code last} (the one before the current), {@code current}, the date
 * it was up ({@code date 2026-10-05} or just {@code 2026-10-05}), or {@code seed:<hex>} to make it
 * again from its seed with today's generator (when its stored plan can't be read) — at least 8 hex
 * digits, like the 12 the website shows.
 */
public final class GenArgs {

    /** A recalled course stays this long unless the recall says otherwise. */
    public static final int DAYS_DEFAULT = 0;
    /** "forever": until replaced or unrecalled. */
    public static final int FOREVER = -1;
    /** The shortest seed prefix accepted. */
    public static final int MIN_SEED_DIGITS = 8;

    /** How an edition is named. */
    public enum How {
        CODE, KEY, NUMBER, LAST, CURRENT, DATE, SEED
    }

    /**
     * An edition as typed.
     *
     * @param how  which way
     * @param text the code ({@code HARD-40}), the key ({@code 7:40}) or the seed's hex digits
     * @param n    the number ({@link How#NUMBER}; also a code's)
     * @param date the date ({@link How#DATE})
     */
    public record Which(How how, String text, int n, LocalDate date) {

        /** Its slot, when the way it was named says (a code); else {@code null}. */
        public String slot() {
            if (how != How.CODE) {
                return null;
            }
            CourseCode.Parsed p = CourseCode.parse(text);
            return p == null ? null : p.slot();
        }

        /** Whether it asks for the course to be made again from its seed. */
        public boolean remade() {
            return how == How.SEED;
        }

        /** As an admin would type it again. */
        public String typed() {
            return switch (how) {
                case CODE, KEY -> text;
                case NUMBER -> Integer.toString(n);
                case LAST -> "last";
                case CURRENT -> "current";
                case DATE -> "date " + date;
                case SEED -> "seed:" + text;
            };
        }
    }

    /**
     * {@code recall} read.
     *
     * @param classic the Classics slot or kind typed, or {@code null} (then it follows the course's kind)
     * @param slot    the slot the edition was made for, or {@code null} when a code says
     * @param which   the edition
     * @param days    how long it stays: 1-365 days, {@link #DAYS_DEFAULT} for {@code classics.days}, or
     *                {@link #FOREVER}
     * @param error   what is wrong with the words, or {@code null}
     */
    public record Recall(String classic, String slot, Which which, int days, String error) {

        static Recall bad(String why) {
            return new Recall(null, null, null, 0, why);
        }
    }

    /**
     * {@code keep} read.
     *
     * @param slot       the slot the edition was made for
     * @param which      the edition ({@link How#CURRENT} when none was typed)
     * @param id         the new course's id
     * @param name       its name as typed, or {@code null} for the id's own
     * @param freshBoard start its board empty instead of copying the edition's records
     * @param error      what is wrong with the words, or {@code null}
     */
    public record Keep(String slot, Which which, String id, String name, boolean freshBoard, String error) {

        static Keep bad(String why) {
            return new Keep(null, null, null, null, false, why);
        }
    }

    /**
     * {@code history} read.
     *
     * @param slot   the slot, or {@code null} for every slot
     * @param page   the page (1 up)
     * @param detail one edition's details, or {@code null} for the list
     * @param error  what is wrong with the words, or {@code null}
     */
    public record History(String slot, int page, Which detail, String error) {
    }

    private GenArgs() {
    }

    /**
     * One word naming an edition, or {@code null} when it isn't one ({@code date} alone is
     * {@link #date}'s job: it takes the next word).
     */
    public static Which which(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String w = word.trim();
        String lower = w.toLowerCase(Locale.ROOT);
        if (lower.equals("last")) {
            return new Which(How.LAST, "last", 0, null);
        }
        if (lower.equals("current") || lower.equals("live")) {
            return new Which(How.CURRENT, "current", 0, null);
        }
        if (lower.startsWith("seed:")) {
            String hex = lower.substring(5);
            if (hex.length() < MIN_SEED_DIGITS || GenSeed.parse(hex) == null) {
                return null;
            }
            return new Which(How.SEED, hex, 0, null);
        }
        CourseCode.Parsed code = CourseCode.parse(w);
        if (code != null) {
            return new Which(How.CODE, code.code(), code.n(), null);
        }
        Edition.Key key = Edition.Key.parse(lower);
        if (key != null && key.toString().equals(lower)) {
            return new Which(How.KEY, lower, 0, null);
        }
        if (lower.matches("\\d{1,9}")) {
            int n = Integer.parseInt(lower);
            return n >= 1 ? new Which(How.NUMBER, lower, n, null) : null;
        }
        LocalDate d = date(lower);
        return d == null ? null : new Which(How.DATE, lower, 0, d);
    }

    /** A date as typed ({@code 2026-10-05}), or {@code null}. */
    public static LocalDate date(String word) {
        if (word == null || !word.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(word.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * {@code recall <classic-slot|kind> <slot> <edition|date <d>|last|seed:<hex>> [days|forever]},
     * or {@code recall [classic-slot|kind] <CODE> [days|forever]} ({@code confirm} already taken off).
     */
    public static Recall recall(List<String> words) {
        List<String> w = new ArrayList<>(words == null ? List.of() : words);
        if (w.isEmpty()) {
            return Recall.bad("Which course? Like: /hcm games gen recall HARD-40, or recall parkour fresh_parkour_hard"
                    + " last");
        }
        String classic = null;
        if (!CourseCode.is(w.get(0))) {
            if (Slots.classicByWord(w.get(0)) == null) {
                return Recall.bad("'" + w.get(0) + "' isn't a course code or a Classics slot (" + String.join(", ",
                        Slots.classicIds()) + ", or parkour, rings, golf).");
            }
            classic = Slots.classicByWord(w.remove(0)).id();
        }
        if (w.isEmpty()) {
            return Recall.bad("Which course? A course code like HARD-40, or a course and which one (like"
                    + " fresh_parkour_hard last).");
        }
        String slot = null;
        Which which;
        if (CourseCode.is(w.get(0))) {
            which = which(w.remove(0));
        } else {
            Slots.Def def = Slots.of(w.get(0));
            if (def == null) {
                return Recall.bad("No Fresh Course called '" + w.get(0) + "'. " + String.join(", ", Slots.ids()));
            }
            slot = def.id();
            w.remove(0);
            if (w.isEmpty()) {
                return Recall.bad("Which one of " + def.name() + "? A number or code, last, date 2026-10-05 or"
                        + " seed:<hex>.");
            }
            which = editionWords(w);
            if (which == null) {
                return Recall.bad("'" + String.join(" ", w) + "' doesn't name one of " + def.name() + "'s courses. Try"
                        + " a number or code, last, date 2026-10-05 or seed:<hex>.");
            }
        }
        int days = DAYS_DEFAULT;
        if (!w.isEmpty()) {
            days = days(w.remove(0));
            if (days == 0) {
                return Recall.bad("How long: a number of days from 1 to 365, or forever.");
            }
        }
        if (!w.isEmpty()) {
            return Recall.bad("Too many words: '" + String.join(" ", w) + "'.");
        }
        if (which.how() == How.CURRENT) {
            return Recall.bad("The current course is already up; recall an old one (last, a number or a code).");
        }
        return new Recall(classic, slot, which, days, null);
    }

    /**
     * {@code keep <slot> [edition|current] <new-id> [name...] [--fresh-board]}, or
     * {@code keep <CODE> <new-id> [name...] [--fresh-board]} ({@code confirm} already taken off).
     * A name may be in quotes.
     */
    public static Keep keep(List<String> words) {
        List<String> w = new ArrayList<>();
        boolean fresh = false;
        for (String s : words == null ? List.<String>of() : words) {
            if (s.equalsIgnoreCase("--fresh-board") || s.equalsIgnoreCase("--fresh")) {
                fresh = true;
            } else {
                w.add(s);
            }
        }
        if (w.isEmpty()) {
            return Keep.bad("Which course? Like: /hcm games gen keep HARD-40 dragon_run \"Dragon Run\"");
        }
        String slot;
        Which which;
        if (CourseCode.is(w.get(0))) {
            which = which(w.remove(0));
            slot = which.slot();
        } else {
            Slots.Def def = Slots.of(w.get(0));
            if (def == null) {
                return Keep.bad("'" + w.get(0) + "' isn't a course code or a Fresh Course. " + String.join(", ",
                        Slots.ids()));
            }
            slot = def.id();
            w.remove(0);
            if (w.size() == 1 && (which(w.get(0)) != null || w.get(0).equalsIgnoreCase("date"))) {
                // "keep fresh_parkour_hard last confirm": an edition and no id, never a course called "last"
                return Keep.bad("Give the new course an id too, like: keep " + def.id() + " "
                        + w.get(0).toLowerCase(Locale.ROOT) + " dragon_run");
            }
            which = w.size() >= 2 ? editionWords(w) : null;
            if (which == null) {
                which = new Which(How.CURRENT, "current", 0, null);
            }
        }
        if (w.isEmpty()) {
            return Keep.bad("What should the new course be called? Give it an id, like dragon_run.");
        }
        String id = w.remove(0).toLowerCase(Locale.ROOT);
        String name = w.isEmpty() ? null : String.join(" ", w);
        if (name != null) {
            name = name.replace("\"", "").trim();
            if (name.isEmpty()) {
                name = null;
            }
        }
        return new Keep(slot, which, id, name, fresh, null);
    }

    /** {@code history <slot|all> [page]} or {@code history <slot> <edition|code>}; a code alone works too. */
    public static History history(List<String> words) {
        List<String> w = new ArrayList<>(words == null ? List.of() : words);
        if (w.isEmpty()) {
            return new History(null, 1, null, null);
        }
        String first = w.remove(0);
        if (CourseCode.is(first) && w.isEmpty()) {
            Which which = which(first);
            return new History(which.slot(), 1, which, null);
        }
        String slot = null;
        if (!first.equalsIgnoreCase("all")) {
            Slots.Def def = Slots.of(first);
            if (def == null) {
                return new History(null, 1, null, "No Fresh Course called '" + first + "'. " + String.join(", ",
                        Slots.ids()) + ", or all.");
            }
            slot = def.id();
        }
        if (w.isEmpty()) {
            return new History(slot, 1, null, null);
        }
        if (slot != null && !(w.size() == 1 && w.get(0).matches("(?i)p(age)?\\d+"))) {
            List<String> rest = new ArrayList<>(w);
            Which which = w.size() == 1 && w.get(0).matches("\\d{1,4}") ? null : editionWords(rest);
            if (which != null && rest.isEmpty()) {
                return new History(slot, 1, which, null);
            }
        }
        String p = w.get(0).toLowerCase(Locale.ROOT).replaceFirst("^p(age)?", "");
        if (w.size() > 1 || !p.matches("\\d{1,4}") || Integer.parseInt(p) < 1) {
            return new History(slot, 1, null, "A page is a number, like 2" + (slot == null ? "" : "; one course is"
                    + " a code (HARD-40), last or date 2026-10-05") + ".");
        }
        return new History(slot, Integer.parseInt(p), null, null);
    }

    /** The edition named by the first word(s) of {@code w} (removed), or {@code null} (nothing removed). */
    static Which editionWords(List<String> w) {
        if (w.isEmpty()) {
            return null;
        }
        if (w.get(0).equalsIgnoreCase("date")) {
            if (w.size() < 2) {
                return null;
            }
            LocalDate d = date(w.get(1));
            if (d == null) {
                return null;
            }
            w.remove(0);
            w.remove(0);
            return new Which(How.DATE, d.toString(), 0, d);
        }
        Which which = which(w.get(0));
        if (which != null) {
            w.remove(0);
        }
        return which;
    }

    /** "7" → 7, "forever" → {@link #FOREVER}; 0 for anything else. */
    static int days(String word) {
        if (word == null) {
            return 0;
        }
        String t = word.trim().toLowerCase(Locale.ROOT);
        if (t.equals("forever")) {
            return FOREVER;
        }
        t = t.endsWith("d") ? t.substring(0, t.length() - 1) : t;
        try {
            int d = Integer.parseInt(t);
            return d >= 1 && d <= 365 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
