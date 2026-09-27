package com.dierks.homecraft.market.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The live market's headlines: the shipped templates, the words they fill in, how one is picked,
 * and the checks an admin's own templates must pass (spec §6.5).
 *
 * <p><b>Kid-safe and honest.</b> The players are children, so a template may not use a word from
 * {@link #BANNED} (war, fire, crash, broke…). And the live market never moves Crate's stock, so a
 * headline talks about people out in the world wanting or having things — never about Crate's
 * shelf. Every list except {@code wanted} is checked against {@link #SHELF_WORDS}; {@code wanted}
 * is exempt because a WANTED flash is only ever sent while Crate really has none.
 *
 * <p><b>Placeholders.</b> {@code {item}} is the plural news name ("Iron Ingots"),
 * {@code {Name}} the singular plain label ("Iron Ingot"), {@code {real}} the real-world thing
 * ("gold"). {@link #render} fills them in one pass, so a value that itself contains braces is
 * never expanded again.
 *
 * <p>Pure Java — no Bukkit — so it is unit-tested without a server. The shipped lists live here
 * as constants so the config defaults, the validator's fallback and the tests share one copy.
 */
public final class Headlines {

    /** Longest a rendered headline may be, in characters (colour codes not counted). */
    public static final int MAX_RENDERED = 72;
    /** The name length {@link #MAX_RENDERED} is checked with: a {@code news_name} is at most 24. */
    public static final int CHECK_NAME_LENGTH = 24;
    /** A template is not picked again until this many others from its list have been used. */
    public static final int MEMORY = 4;

    public static final String LIST_UP = "up";
    public static final String LIST_DOWN = "down";
    public static final String LIST_HOT = "hot";
    public static final String LIST_DEAL = "deal";
    public static final String LIST_WANTED = "wanted";
    public static final String LIST_REAL_UP = "real_up";
    public static final String LIST_REAL_DOWN = "real_down";

    /** Every list name, in config order. */
    public static final List<String> LISTS = List.of(LIST_UP, LIST_DOWN, LIST_HOT, LIST_DEAL,
            LIST_WANTED, LIST_REAL_UP, LIST_REAL_DOWN);

    /** NEWS FLASH, price going up: people in the world want the item. */
    public static final List<String> UP = List.of(
            "A giant castle is being built! Everyone wants {item}!",
            "The villagers are having a party and need {item}!",
            "A famous builder just ordered lots of {item}!",
            "Wandering traders are paying big for {item} today!",
            "Everybody on the server is talking about {item}!",
            "The big building contest needs lots of {item}!",
            "A mystery shopper wants to buy {item}!",
            "A brand new town needs {item} for its houses!",
            "Kids all over the land are collecting {item}!",
            "The mayor wants {item} for the new town hall!",
            "Llamas are packing {item} for a long trip!",
            "Hooray! Everyone is looking for {item} today!");

    /** NEWS FLASH, price going down: there is plenty of the item out in the world. */
    public static final List<String> DOWN = List.of(
            "A giant cart full of {item} just rolled into town!",
            "Miners found a huge pile of {item}!",
            "The villagers brought lots of {item} to town!",
            "A boat full of {item} just landed at the dock!",
            "Hardly anyone needs {item} this week!",
            "A wandering trader is selling {item} cheap!",
            "A lucky miner found a secret stash of {item}!",
            "Everyone already has enough {item} today.",
            "Llamas carried in a mountain of {item}!",
            "The farms and mines made extra {item} this week!",
            "Lucky day! You can find {item} everywhere!",
            "Shopping time! Get {item} for less right now!");

    /** ★ HOT ITEM. */
    public static final List<String> HOT = List.of(
            "Everybody wants {item} this week!",
            "Hot item alert! Everyone is looking for {item}!",
            "Everyone is talking about {item}!",
            "Builders all over the land need {item}!",
            "{Name} is the cool thing to have right now!",
            "Hot, hot, hot! Crate wants your {item}!");

    /** ✦ DEAL. */
    public static final List<String> DEAL = List.of(
            "{Name} is on sale at Crate!",
            "Sale time! Get {item} for less at Crate!",
            "Deal alert! Save on {item} for a couple of days!",
            "Grab some {item} while the sale is on!",
            "Crate's special this week: {item} for less!",
            "Psst! There is a great deal on {item} right now!");

    /** » MARKET NEWS for a sold-out item. */
    public static final List<String> WANTED = List.of(
            "Crate is looking for {item}!",
            "Wanted: {item}! Crate would love some.",
            "Who has {item}? Crate wants to buy some!");

    /** REAL-WORLD NEWS, the real price went up. */
    public static final String REAL_UP = "In the real world, {real} prices went up today!";
    /** REAL-WORLD NEWS, the real price went down. */
    public static final String REAL_DOWN = "In the real world, {real} prices went down today!";

    /**
     * Words a headline may never use, matched as whole words in any case, plus their plain
     * plural ({@code wars}, {@code crashes}).
     */
    public static final List<String> BANNED = List.of(
            "war", "fight", "dead", "die", "dies", "died", "kill", "hurt", "blood", "bomb", "gun",
            "fire", "burn", "crash", "panic", "scary", "scare", "fear", "hate", "sick", "virus",
            "storm", "flood", "disaster", "attack", "bankrupt", "debt", "collapse", "broke", "poor",
            "steal", "thief");

    /**
     * Words about Crate's own shelf, which the live market never changes. Matched like
     * {@link #BANNED}; {@code stock} also catches {@code stocked}/{@code stocking}.
     */
    public static final List<String> SHELF_WORDS = List.of(
            "shelf", "shelves", "stock", "warehouse", "overflowing", "sold out", "out of");

    /**
     * Item ids whose name stays the same for one or many (the shipped
     * {@code market.sim.same_plural}), in config order.
     */
    public static final Set<String> SAME_PLURAL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "cobblestone", "stone", "dirt", "sand", "red_sand", "gravel", "glass", "wheat", "coal",
            "charcoal", "redstone", "lapis_lazuli", "quartz", "obsidian", "netherrack", "sugar",
            "gunpowder", "string", "bone_meal", "leather", "kelp", "bamboo", "sugar_cane", "cactus",
            "glowstone_dust", "snow", "ice", "clay", "white_wool", "cocoa_beans", "andesite", "diorite",
            "granite", "deepslate", "cobbled_deepslate", "tuff", "calcite", "basalt", "blackstone",
            "sandstone", "mud")));

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final Pattern RAW_LABEL = Pattern.compile("[A-Z0-9_]+");
    /** Words a title-cased label keeps lower-case after the first ({@code Heart of the Sea}). */
    private static final Set<String> SMALL_WORDS = Set.of("of", "the", "and", "a", "an", "on", "in", "o");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern BANNED_WORD = wordPattern(BANNED);
    private static final Pattern SHELF_WORD = wordPattern(SHELF_WORDS);

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private Headlines() {
    }

    // ---- the shipped lists --------------------------------------------------------------

    /** The shipped templates for {@code list} ({@code up}, …, {@code real_down}); empty if unknown. */
    public static List<String> shipped(String list) {
        String key = list == null ? "" : list.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case LIST_UP -> UP;
            case LIST_DOWN -> DOWN;
            case LIST_HOT -> HOT;
            case LIST_DEAL -> DEAL;
            case LIST_WANTED -> WANTED;
            case LIST_REAL_UP -> List.of(REAL_UP);
            case LIST_REAL_DOWN -> List.of(REAL_DOWN);
            default -> List.of();
        };
    }

    /** Every shipped list by name, in config order. */
    public static Map<String, List<String>> shippedLists() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String list : LISTS) {
            out.put(list, shipped(list));
        }
        return Collections.unmodifiableMap(out);
    }

    // ---- names --------------------------------------------------------------------------

    /**
     * The singular plain label headlines use for {@code {Name}}: colour codes stripped and
     * trimmed, and a raw material-style label ({@code IRON_INGOT}) title-cased to
     * {@code Iron Ingot} ({@code HEART_OF_THE_SEA} → {@code Heart of the Sea}). {@code null}
     * gives {@code ""}.
     */
    public static String name(String label) {
        String plain = plain(label);
        if (!RAW_LABEL.matcher(plain).matches()) {
            return plain;
        }
        StringBuilder sb = new StringBuilder(plain.length());
        for (String part : plain.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (sb.length() > 0) {
                sb.append(' ');
                if (SMALL_WORDS.contains(lower)) {
                    sb.append(lower);
                    continue;
                }
            }
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.length() == 0 ? plain : sb.toString();
    }


    /**
     * The plural news name headlines use for {@code {item}}.
     *
     * <ol>
     *   <li>A non-blank {@code override} (the catalog row's {@code news_name}) is used as given.</li>
     *   <li>An item in {@code same} — matched by id, or by its name written as an id
     *       ({@code "Red Sand"} → {@code red_sand}) — keeps its singular name.</li>
     *   <li>Otherwise the head word is made plural: the last word, or the word before
     *       {@code of}/{@code o'} ({@code Heart of the Sea} → {@code Hearts of the Sea}). A word
     *       ending in {@code s} is left alone (Glass, Oak Planks, Wheat Seeds); {@code x/z/ch/sh}
     *       add {@code es}; a consonant before {@code y} becomes {@code ies} (Berry → Berries);
     *       anything else adds {@code s}.</li>
     * </ol>
     *
     * @param id       the catalog id
     * @param name     the item's label (passed through {@link #name} first)
     * @param override the {@code news_name}, or {@code null}
     * @param same     ids that stay the same for one or many; {@code null} = none
     */
    public static String plural(String id, String name, String override, Set<String> same) {
        String over = plain(override);
        if (!over.isEmpty()) {
            return over;
        }
        String singular = name(name);
        if (singular.isEmpty()) {
            return singular;
        }
        if (same != null && !same.isEmpty()) {
            String byId = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            String byName = singular.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (containsIgnoreCase(same, byId) || containsIgnoreCase(same, byName)) {
                return singular;
            }
        }
        int head = headWordEnd(singular);
        int start = singular.lastIndexOf(' ', head - 1) + 1;
        String word = singular.substring(start, head);
        return singular.substring(0, start) + pluralWord(word) + singular.substring(head);
    }

    private static boolean containsIgnoreCase(Set<String> same, String key) {
        if (key.isEmpty()) {
            return false;
        }
        if (same.contains(key)) {
            return true;
        }
        for (String s : same) {
            if (s != null && s.trim().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** The end index of the word that takes the plural: before " of "/" o' ", else the end. */
    private static int headWordEnd(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String joint : new String[]{" of ", " o' "}) {
            int at = lower.indexOf(joint);
            if (at > 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best > 0 ? best : s.length();
    }

    private static String pluralWord(String word) {
        if (word.isEmpty()) {
            return word;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s")) {
            return word;
        }
        if (lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh")) {
            return word + "es";
        }
        if (lower.length() >= 2 && lower.endsWith("y") && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        return word + "s";
    }

    // ---- picking and rendering ----------------------------------------------------------

    /**
     * Which template to use: uniformly among the {@code size} templates, leaving out the ones
     * used for the last {@link #MEMORY} headlines of this list — or, for a list of
     * {@code MEMORY} or fewer, the last {@code size - 1}, so there is always a choice.
     *
     * @param size   how many templates the list has
     * @param recent indices used before, oldest first (see {@link #remember}); {@code null} = none
     * @param u      a uniform draw in {@code [0, 1)}
     * @return the index, or {@code -1} when {@code size <= 0}
     */
    public static int pick(int size, List<Integer> recent, double u) {
        if (size <= 0) {
            return -1;
        }
        Set<Integer> excluded = new LinkedHashSet<>();
        if (recent != null) {
            int keep = Math.min(MEMORY, size - 1);
            for (int i = recent.size() - 1; i >= 0 && excluded.size() < keep; i--) {
                Integer r = recent.get(i);
                if (r != null && r >= 0 && r < size) {
                    excluded.add(r);
                }
            }
        }
        List<Integer> allowed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (!excluded.contains(i)) {
                allowed.add(i);
            }
        }
        if (allowed.isEmpty()) {
            return 0;
        }
        double x = Double.isFinite(u) ? Math.min(Math.max(u, 0.0), Math.nextDown(1.0)) : 0.0;
        int at = (int) Math.floor(x * allowed.size());
        return allowed.get(Math.min(Math.max(at, 0), allowed.size() - 1));
    }

    /** The new memory after using {@code picked}: oldest first, at most {@link #MEMORY} long. */
    public static List<Integer> remember(List<Integer> recent, int picked) {
        List<Integer> out = new ArrayList<>(MEMORY);
        if (recent != null) {
            for (Integer r : recent) {
                if (r != null && r != picked) {
                    out.add(r);
                }
            }
        }
        out.add(picked);
        while (out.size() > MEMORY) {
            out.remove(0);
        }
        return List.copyOf(out);
    }

    /** The memory as stored in meta ({@code recent_<list>}): {@code "3,7,1"}. */
    public static String formatRecent(List<Integer> recent) {
        if (recent == null || recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer r : recent) {
            if (r == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r);
        }
        return sb.toString();
    }

    /** {@link #formatRecent} read back; anything that is not a non-negative number is skipped. */
    public static List<Integer> parseRecent(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : stored.split(",")) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v >= 0) {
                    out.add(v);
                }
            } catch (NumberFormatException ignored) {
                // a hand-edited meta row: drop the piece, keep the rest
            }
        }
        int from = Math.max(0, out.size() - MEMORY);
        return List.copyOf(out.subList(from, out.size()));
    }

    /**
     * {@code tpl} with each {@code {key}} found in {@code vars} replaced by its value (a
     * {@code null} value as {@code ""}), in a single pass. Unknown placeholders are left as
     * written. {@code null} gives {@code ""}.
     */
    public static String render(String tpl, Map<String, String> vars) {
        if (tpl == null) {
            return "";
        }
        if (vars == null || vars.isEmpty() || tpl.indexOf('{') < 0) {
            return tpl;
        }
        StringBuilder sb = new StringBuilder(tpl.length() + 32);
        var m = PLACEHOLDER.matcher(tpl);
        int last = 0;
        while (m.find()) {
            String key = m.group(1);
            if (!vars.containsKey(key)) {
                continue;
            }
            sb.append(tpl, last, m.start());
            String v = vars.get(key);
            sb.append(v == null ? "" : v);
            last = m.end();
        }
        sb.append(tpl, last, tpl.length());
        return sb.toString();
    }

    /** The placeholders a template in {@code list} may use. */
    public static Set<String> placeholders(String list) {
        return isReal(list) ? Set.of("item", "Name", "real") : Set.of("item", "Name");
    }

    // ---- validation ---------------------------------------------------------------------

    /**
     * Why {@code tpl} may not be used in {@code list}, or {@code null} when it is fine. A
     * template is dropped when it is blank; lacks {@code {item}} or {@code {Name}}
     * ({@code {real}} for {@code real_up}/{@code real_down}); uses any other placeholder;
     * renders over {@link #MAX_RENDERED} characters with {@link #CHECK_NAME_LENGTH}-character
     * names; uses a character above U+FFFF (Bedrock shows a box); contains a {@link #BANNED}
     * word; or — every list except {@code wanted} — a {@link #SHELF_WORDS shelf word}.
     */
    public static String problem(String list, String tpl) {
        if (tpl == null || tpl.isBlank()) {
            return "is blank";
        }
        if (isReal(list)) {
            if (!tpl.contains("{real}")) {
                return "lacks {real}";
            }
        } else if (!tpl.contains("{item}") && !tpl.contains("{Name}")) {
            return "lacks {item} or {Name}";
        }
        Set<String> allowed = placeholders(list);
        var m = PLACEHOLDER.matcher(tpl);
        while (m.find()) {
            if (!allowed.contains(m.group(1))) {
                return "uses an unknown placeholder {" + m.group(1) + "}";
            }
        }
        String text = textProblem(tpl, !LIST_WANTED.equalsIgnoreCase(list == null ? "" : list.trim()));
        if (text != null) {
            return text;
        }
        String longName = "W".repeat(CHECK_NAME_LENGTH);
        Map<String, String> vars = new LinkedHashMap<>();
        for (String key : allowed) {
            vars.put(key, longName);
        }
        int len = visibleLength(render(tpl, vars));
        if (len > MAX_RENDERED) {
            return "is " + len + " characters with a " + CHECK_NAME_LENGTH + "-character name (at most "
                    + MAX_RENDERED + ")";
        }
        return null;
    }

    /**
     * The safety checks on any player-facing news text (also a season's name and headline):
     * no character above U+FFFF, no {@link #BANNED} word and, when {@code shelfCheck}, no
     * {@link #SHELF_WORDS shelf word}. {@code null} when fine.
     */
    public static String textProblem(String text, boolean shelfCheck) {
        if (text == null) {
            return null;
        }
        int wide = text.codePoints().filter(cp -> cp > 0xFFFF).findFirst().orElse(-1);
        if (wide >= 0) {
            return String.format(Locale.ROOT, "uses U+%04X, which Bedrock cannot show", wide);
        }
        var banned = BANNED_WORD.matcher(text);
        if (banned.find()) {
            return "uses the word \"" + banned.group() + "\"";
        }
        if (shelfCheck) {
            var shelf = SHELF_WORD.matcher(text);
            if (shelf.find()) {
                return "talks about Crate's shelf (\"" + shelf.group()
                        + "\") - the live market never changes stock";
            }
        }
        return null;
    }

    /**
     * One line per template in {@code tpls} that {@link #problem} rejects, ready for a WARN:
     * {@code headlines.up #3 "…" lacks {item} or {Name} - skipped}. Empty when all are fine.
     */
    public static List<String> problems(String list, List<String> tpls) {
        if (tpls == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tpls.size(); i++) {
            String tpl = tpls.get(i);
            String why = problem(list, tpl);
            if (why != null) {
                out.add("market.sim.headlines." + list + " #" + (i + 1) + " \"" + (tpl == null ? "" : tpl)
                        + "\" " + why + " - skipped");
            }
        }
        return out;
    }

    /** The templates of {@code tpls} that pass {@link #problem}, in order. */
    public static List<String> valid(String list, List<String> tpls) {
        if (tpls == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(tpls.size());
        for (String tpl : tpls) {
            if (problem(list, tpl) == null) {
                out.add(tpl);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The load-time rule: the valid templates of {@code tpls}, each rejected one reported to
     * {@code warn}; when none survive (or none were given), the shipped list for {@code list}
     * with one more WARN.
     */
    public static List<String> validOrShipped(String list, List<String> tpls, Consumer<String> warn) {
        if (warn != null) {
            problems(list, tpls).forEach(warn);
        }
        List<String> ok = valid(list, tpls);
        if (!ok.isEmpty()) {
            return ok;
        }
        if (warn != null && tpls != null && !tpls.isEmpty()) {
            warn.accept("market.sim.headlines." + list + " has no usable headline - using the shipped ones");
        }
        return shipped(list);
    }

    // ---- words for time -----------------------------------------------------------------

    /**
     * How long something has left, in words: 36 hours or more "about N days" (N rounded),
     * 18-36 hours "about a day", 6-18 hours "less than a day", under 6 hours "a few hours".
     */
    public static String left(long ms) {
        double hours = ms / (double) HOUR_MS;
        if (hours >= 36) {
            return "about " + Math.round(hours / 24) + " days";
        }
        if (hours >= 18) {
            return "about a day";
        }
        if (hours >= 6) {
            return "less than a day";
        }
        return "a few hours";
    }

    /**
     * How long ago, in words: "just now" (under 2 minutes), "{m}m ago", "{h}h ago",
     * "yesterday" (24-48 hours), "{d} days ago".
     */
    public static String ago(long ms) {
        if (ms < 2 * MINUTE_MS) {
            return "just now";
        }
        if (ms < HOUR_MS) {
            return (ms / MINUTE_MS) + "m ago";
        }
        if (ms < DAY_MS) {
            return (ms / HOUR_MS) + "h ago";
        }
        if (ms < 2 * DAY_MS) {
            return "yesterday";
        }
        return (ms / DAY_MS) + " days ago";
    }

    // ---- helpers ------------------------------------------------------------------------

    private static boolean isReal(String list) {
        return list != null && list.trim().toLowerCase(Locale.ROOT).startsWith("real");
    }

    private static String plain(String s) {
        return s == null ? "" : LEGACY_CODE.matcher(s).replaceAll("").trim();
    }

    private static int visibleLength(String s) {
        String p = LEGACY_CODE.matcher(s).replaceAll("");
        return p.codePointCount(0, p.length());
    }

    /**
     * Whole words (and phrases, any run of spaces between their words) in any case, each
     * allowing its plain plural; {@code stock} also allows {@code ed}/{@code ing}.
     */
    private static Pattern wordPattern(List<String> words) {
        StringBuilder sb = new StringBuilder("(?iu)(?<![\\p{L}\\p{N}])(?:");
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (i > 0) {
                sb.append('|');
            }
            String[] parts = w.split(" ");
            for (int p = 0; p < parts.length; p++) {
                if (p > 0) {
                    sb.append("\\s+");
                }
                sb.append(Pattern.quote(parts[p]));
            }
            if (parts.length == 1) {
                if (w.equals("stock")) {
                    sb.append("(?:s|ed|ing)?");
                } else if (w.endsWith("s") || w.endsWith("sh")) {
                    sb.append("(?:es)?");
                } else {
                    sb.append("s?");
                }
            }
        }
        return Pattern.compile(sb.append(")(?![\\p{L}\\p{N}])").toString());
    }
}
