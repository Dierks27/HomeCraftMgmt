package com.dierks.homecraft;

import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.TokenBalance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 20: the whole-arcade token balance (the owner's live test, 2 Oct; BALANCE-SPEC).
 *
 * <p>"4 is nothing - that is like walking 1000 blocks and you get 3 tokens." The quests pay about a
 * token a minute of effort (walk 1,000 blocks, about four minutes, for 4) while the games paid three
 * to ten times less, and {@code skill_daily_cap: 6} held every game together to 6 tokens a day. The
 * games now pay on the quests' rate, with caps to match, and the Scratch Ticket's prizes come into the
 * house's 85-95 band; the numbers are {@link TokenBalance}'s. Games of chance, quests and achievements,
 * the prices and the login streak are unchanged.
 *
 * <p><b>The rule</b> is every revision's: a value that still holds what 0.35 and 0.36 shipped is
 * ours, and moves to the new default; a value already at the new default stays; anything else is the
 * owner's, and is kept with one {@link HomeCraftManagement#WARN} naming the key and the new default
 * ({@link HomeCraftManagement#retune}'s contract). An absent key is left to the backfill, which then
 * writes the new default. Numbers compare by value, lists element by element, and the Scratch
 * Ticket's rows field by field ({@link ArcadeConfigMigration#sameRows}).
 *
 * <p><b>The Scratch Ticket is one unit.</b> Its return is the prizes, the price and the jackpot together
 * ({@code ArcadeService.rtp}), so its prizes move only while {@code ticket_tokens} and the jackpot's
 * {@code seed}, {@code per_ticket} and {@code cap} are each absent or still what 0.35 and 0.36 shipped
 * ({@link TokenBalance#TICKET_SHIPPED}), each read as the plugin plays it, and so long as together they leave
 * the new prizes' return as shipped (a cap the pot never reaches changes nothing). An owner who brought the
 * ticket into the band through one of those (the 0.36 README asked them to) keeps their prizes too, with one
 * WARN naming the key and what the new prizes would give back with it: 0.37's prizes on a 9-token ticket give
 * back 99.4%, past the band. Prizes the owner deleted beside such a key are written back as 0.36 shipped them,
 * with the same WARN, rather than left to the backfill, which would write 0.37's.
 *
 * <p><b>The table is data.</b> {@link #STEPS} is {@link TokenBalance#ROWS}: each row's "old" (what 0.35
 * and 0.36 shipped) and its "new" (what 0.37.0 ships), both frozen as literal numbers. It is read neither
 * from the bundled config.yml nor from the constants the settings records' defaults read, so a later
 * release that retunes either can't change what revision 20 did; a test pins every "old" to 0.35.0's and
 * 0.36.0's config.yml and every "new" to this release's, so a retune fails it until it adds a revision.
 *
 * <p><b>Comments.</b> A key that moves takes the bundled file's comment with it, so the owner's file
 * stops saying "Keep each at or under 4" or "(each 0-10)" for limits that have changed. A section
 * comment that describes the shipped numbers ({@code games.fresh.rewards}, {@code games.fresh.star_goals},
 * {@code arcade.lotto}) is refreshed when everything under it moved, none of it kept. A kept key
 * keeps its comment.
 *
 * <p>No database change: the caps are re-summed from {@code game_rewards}, Cup entries store what
 * was paid, and Race Night freezes its prizes per night.
 */
final class EconomyMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 20. */
    static final int REVISION = 20;

    /**
     * One value revision 20 moves.
     *
     * @param path its full config key
     * @param old  what 0.35.0 and 0.36.0 shipped
     * @param now  the new default
     * @param with the keys it moves only with, at what 0.35.0 and 0.36.0 shipped ({@link TokenBalance.Row#with})
     */
    record Step(String path, Object old, Object now, Map<String, Object> with) {
    }

    /** The Scratch Ticket's prizes: the one step that moves with other keys ({@link TokenBalance#TICKET_SHIPPED}). */
    static final String TICKET = "arcade.lotto.payouts";

    /** Section comments that describe the shipped numbers under them. */
    static final List<String> COMMENT_SECTIONS = List.of("games.fresh.rewards", "games.fresh.star_goals",
            "arcade.lotto");

    /** Every value revision 20 moves, in config order: {@link TokenBalance#ROWS}. */
    static final List<Step> STEPS = steps();

    private EconomyMigration() {
    }

    private static List<Step> steps() {
        List<Step> s = new ArrayList<>();
        for (TokenBalance.Row r : TokenBalance.ROWS) {
            s.add(new Step(r.path(), r.old(), r.now(), r.with()));
        }
        return List.copyOf(s);
    }

    /** What one step found. */
    enum Outcome {
        /** the key isn't in the file: the backfill writes the new default */
        ABSENT,
        /** it held the old default and now holds the new one */
        MOVED,
        /** it already held the new default */
        ALREADY,
        /** it held the owner's own value, which stays */
        KEPT,
        /**
         * it was missing while the owner had retuned a key of its unit: the old value is written, so the backfill
         * doesn't add the new one on top of their key (the Scratch Ticket's prizes)
         */
        RESTORED
    }

    /** Run revision 20 against {@code c} (defaults-free), appending what it did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        YamlConfiguration bundled = ArcadeConfigMigration.bundled();
        Map<String, List<Step>> movedBySection = new LinkedHashMap<>();
        List<Step> kept = new ArrayList<>();
        List<Step> moved = new ArrayList<>();
        for (Step step : STEPS) {
            Outcome o = apply(c, step);
            if (o == Outcome.MOVED) {
                moved.add(step);
                movedBySection.computeIfAbsent(section(step.path()), k -> new ArrayList<>()).add(step);
                if (bundled != null) {
                    copyComments(bundled, c, step.path());
                }
            } else if (o == Outcome.KEPT || o == Outcome.RESTORED) {
                kept.add(step);
                List<String> with = retuned(c, step);
                String head = o == Outcome.RESTORED
                        ? "Config migration: " + step.path() + " was missing, so it is written back as 0.35 and 0.36"
                        + " shipped it, " + show(step.old()) + ", not as this version ships it,"
                        : "Config migration: kept " + step.path() + " = " + show(c.get(step.path(), null));
                log.add(WARN + head + (same(c.get(step.path(), null), step.old()) && !with.isEmpty()
                        ? " because you have changed " + String.join(" and ", with) + effect(c, step) + "."
                        : " because you have changed it (the new default is " + show(step.now()) + ")."));
            }
        }
        if (bundled != null) {
            for (String section : COMMENT_SECTIONS) {
                boolean any = moved.stream().anyMatch(s -> under(s.path(), section));
                boolean none = kept.stream().noneMatch(s -> under(s.path(), section));
                if (any && none && c.get(section, null) instanceof ConfigurationSection) {
                    copyComments(bundled, c, section);
                }
            }
        }
        for (Map.Entry<String, List<Step>> e : movedBySection.entrySet()) {
            log.add("Config migration: " + e.getKey() + " - " + describe(e.getKey(), e.getValue())
                    + " (the 2 Oct token balance: about a token a minute of play).");
        }
    }

    /** One step against {@code c}: what it found, and the new default written when it held the old one. */
    static Outcome apply(FileConfiguration c, Step step) {
        Object current = c.get(step.path(), null);
        if (current == null) {
            if (!retuned(c, step).isEmpty()) {
                // the backfill would write the new value beside the owner's retuned key (0.37's prizes on their
                // 9-token ticket give back 99.4%): write what shipped with the key they changed instead
                c.set(step.path(), copy(step.old()));
                return Outcome.RESTORED;
            }
            return Outcome.ABSENT;
        }
        if (same(current, step.now())) {
            return Outcome.ALREADY;
        }
        if (same(current, step.old())) {
            if (!retuned(c, step).isEmpty()) {
                return Outcome.KEPT; // the owner retuned it through a key beside it: the unit is theirs
            }
            c.set(step.path(), copy(step.now()));
            return Outcome.MOVED;
        }
        return Outcome.KEPT;
    }

    /**
     * The keys of {@code step}'s unit ({@link Step#with}) that hold the owner's own value, each as
     * {@code key = value (shipped N)}, in table order; empty when each is absent or still what was shipped. The
     * Scratch Ticket's are read as the plugin plays them ({@link #retunedTicket}).
     */
    static List<String> retuned(FileConfiguration c, Step step) {
        if (step.with().isEmpty()) {
            return List.of();
        }
        if (TICKET.equals(step.path())) {
            return retunedTicket(c, step);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : step.with().entrySet()) {
            Object v = c.get(e.getKey(), null);
            if (v != null && !same(v, e.getValue())) {
                out.add(e.getKey() + " = " + show(v) + " (shipped " + show(e.getValue()) + ")");
            }
        }
        return out;
    }

    /**
     * The Scratch Ticket's companions the owner changed, read as the plugin plays them ({@link PluginConfig#lotto}:
     * a number read whole, anything else as the shipped value), and only when together they change what the new
     * prizes give back. So {@code ticket_tokens: "10"} or {@code 10.0} is the shipped 10, and a
     * {@code jackpot.cap} the pot never reaches (2000, with the pot at its steady 150 under either table: the
     * jackpot's chance is the same in both) is no retune; the ticket then moves like an untouched one.
     */
    private static List<String> retunedTicket(FileConfiguration c, Step step) {
        YamlConfiguration shippedFile = new YamlConfiguration();
        step.with().forEach(shippedFile::set);
        PluginConfig.Lotto shipped = PluginConfig.lotto(shippedFile, null);
        PluginConfig.Lotto mine = PluginConfig.lotto(c, null);
        @SuppressWarnings("unchecked")
        List<PluginConfig.LottoPayout> prizes = PluginConfig.lottoPayouts((List<? extends Map<?, ?>>) step.now(), null);
        double theirs = ArcadeService.rtp(new PluginConfig.Lotto(mine.ticketTokens(), prizes, mine.jackpot()));
        double ours = ArcadeService.rtp(new PluginConfig.Lotto(shipped.ticketTokens(), prizes, shipped.jackpot()));
        List<String> out = new ArrayList<>();
        if (Math.abs(theirs - ours) < 1e-9) {
            return out;
        }
        for (String key : step.with().keySet()) {
            int read = ticketKey(mine, key);
            int was = ticketKey(shipped, key);
            if (read != was) {
                out.add(key + " = " + read + " (shipped " + was + ")");
            }
        }
        return out;
    }

    /** One of the ticket's companion keys as {@code l} holds it. */
    private static int ticketKey(PluginConfig.Lotto l, String key) {
        return switch (key.substring(key.lastIndexOf('.') + 1)) {
            case "ticket_tokens" -> l.ticketTokens();
            case "seed" -> l.jackpot().seed();
            case "per_ticket" -> l.jackpot().perTicket();
            case "cap" -> l.jackpot().cap();
            default -> throw new IllegalArgumentException("not a Scratch Ticket key: " + key);
        };
    }

    /**
     * The rest of the WARN for a step kept for a key beside it. For the Scratch Ticket: what the owner's ticket
     * gives back, and what the new prizes would give back with their price and jackpot ({@code ArcadeService.rtp},
     * read as the plugin reads it).
     */
    private static String effect(FileConfiguration c, Step step) {
        String plain = ", which goes with it";
        if (!TICKET.equals(step.path())) {
            return plain;
        }
        try {
            PluginConfig.Lotto mine = PluginConfig.lotto(c, null);
            @SuppressWarnings("unchecked")
            List<? extends Map<?, ?>> rows = (List<? extends Map<?, ?>>) step.now();
            PluginConfig.Lotto would = new PluginConfig.Lotto(mine.ticketTokens(), PluginConfig.lottoPayouts(rows, null),
                    mine.jackpot());
            return ", and the ticket's return is all of them together: yours gives back " + percent(mine)
                    + ", and the new prizes " + show(step.now()) + " would give back " + percent(would)
                    + " (the house band is 85-95)";
        } catch (RuntimeException e) {
            return plain;
        }
    }

    private static String percent(PluginConfig.Lotto l) {
        return RtpLimits.tenthPercent(ArcadeService.rtp(l)) + "%";
    }

    /**
     * Whether a value read from the file is {@code expected}: a number by value, a list of numbers
     * element by element, a list of rows field by field and in order.
     */
    static boolean same(Object current, Object expected) {
        if (expected instanceof Number e) {
            return current instanceof Number n && n.doubleValue() == e.doubleValue();
        }
        if (expected instanceof List<?> el) {
            if (!(current instanceof List<?> cl) || cl.size() != el.size()) {
                return false;
            }
            if (!el.isEmpty() && el.get(0) instanceof Map<?, ?>) {
                List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(cl);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> want = (List<Map<String, Object>>) el;
                return rows.size() == cl.size() && ArcadeConfigMigration.sameRows(rows, want);
            }
            for (int i = 0; i < el.size(); i++) {
                if (!same(cl.get(i), el.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(current, expected);
    }

    /** A value to write: lists and rows as fresh mutable copies, so the file never holds our constants. */
    private static Object copy(Object v) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(o instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : o);
            }
            return out;
        }
        return v;
    }

    /** {@code games.trials} for {@code games.trials.first_clear.easy}, {@code games} for a common key. */
    static String section(String path) {
        String[] parts = path.split("\\.");
        return parts.length >= 3 ? parts[0] + "." + parts[1] : parts[0];
    }

    /** {@code games.trials.first_clear} for {@code games.trials.first_clear.easy}. */
    private static String parent(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    private static boolean under(String path, String section) {
        return path.startsWith(section + ".");
    }

    /**
     * The moved steps of one section, for its one log line: {@code first_clear 5/10/20/40 → 10/15/25/50}
     * when every leaf of a group moved, else each leaf on its own ({@code daily_cap 4 → 40}).
     */
    static String describe(String section, List<Step> moved) {
        Map<String, List<Step>> groups = new LinkedHashMap<>();
        for (Step s : moved) {
            groups.computeIfAbsent(parent(s.path()), k -> new ArrayList<>()).add(s);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<Step>> g : groups.entrySet()) {
            List<Step> steps = g.getValue();
            long inGroup = STEPS.stream().filter(s -> parent(s.path()).equals(g.getKey())).count();
            boolean numbers = steps.stream().allMatch(s -> s.old() instanceof Number);
            if (!g.getKey().equals(section) && numbers && steps.size() > 1 && steps.size() == inGroup) {
                List<String> olds = new ArrayList<>();
                List<String> nows = new ArrayList<>();
                for (Step s : steps) {
                    olds.add(show(s.old()));
                    nows.add(show(s.now()));
                }
                parts.add(g.getKey().substring(section.length() + 1) + " " + String.join("/", olds) + " → "
                        + String.join("/", nows));
            } else {
                for (Step s : steps) {
                    parts.add(s.path().substring(section.length() + 1) + " " + show(s.old()) + " → " + show(s.now()));
                }
            }
        }
        return String.join(", ", parts);
    }

    /** A value as the log shows it: {@code 5}, {@code [5, 3, 2]}, or a ticket ({@code [0×25, …, jackpot×1]}). */
    static String show(Object v) {
        if (v instanceof List<?> l && !l.isEmpty()
                && (l.get(0) instanceof Map<?, ?> || l.get(0) instanceof ConfigurationSection)) {
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> r : ArcadeConfigMigration.mapRows(l)) {
                String prize = Boolean.TRUE.equals(r.get("jackpot")) ? "jackpot" : String.valueOf(r.get("tokens"));
                rows.add(prize + "×" + r.get("weight"));
            }
            return "[" + String.join(", ", rows) + "]";
        }
        return String.valueOf(v);
    }

    /** Carry a key's block and inline comments from the bundled file onto the owner's. */
    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String key) {
        try {
            to.setComments(key, from.getComments(key));
            to.setInlineComments(key, from.getInlineComments(key));
        } catch (Throwable ignored) {
            // Comment API unavailable on this server: the values still moved.
        }
    }
}
