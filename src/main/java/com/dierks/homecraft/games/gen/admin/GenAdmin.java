package com.dierks.homecraft.games.gen.admin;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /hcm games gen …}: running Fresh Courses by hand (GEN-SPEC §5.5). The framework has already
 * checked {@code hcm.games.admin}; {@code args} are the words after {@code gen}.
 *
 * <p>Why these verbs: the courses build themselves, so an admin only ever needs to look
 * ({@code status}, {@code plan}, {@code tp}), steer the next build ({@code tier}, {@code mix},
 * {@code pin}, {@code on}/{@code off}), try one out ({@code preview}, then {@code promote}), replace
 * the current set's ({@code reroll}), repair ({@code rebuild}), and hand an area over or take one back
 * ({@code claim}, {@code clear}). The hand-built course tools refuse a generated course and point
 * here.
 *
 * <p>The rules around them: destructive verbs need {@code confirm} ({@code reroll}, {@code clear},
 * {@code claim} to clear an area; {@code promote} when the course's board has times, which the engine
 * decides). {@code reroll}, {@code preview} and {@code promote} are refused within
 * {@code avoid_before_restart_minutes} of a scheduled restart. Every change is logged with who made
 * it, and a command that goes wrong answers "That didn't work - see the console." and never
 * reaches the framework's guard, which would switch Fresh Courses off.
 *
 * <p><b>The archive (GEN-SPEC-KEEP).</b> {@code history} lists every past course with its course
 * code; {@code recall} brings one back into a Classics slot for a while ({@code unrecall} closes
 * it); {@code keep} makes one a normal course for good in a plot of the keep area ({@code plots}
 * lists them, {@code clear-plot} takes one down). Any of them takes a course code where an edition
 * is asked for ({@link GenArgs}). {@code recall} and {@code keep} are refused near a restart, like
 * {@code reroll}; {@code keep}, {@code clear-plot}, and a recall or unrecall that would disturb
 * someone playing, ask for {@code confirm} first.
 *
 * <p><b>Picking a good course (WP-ADM, the owner's words).</b> "If it comes out really bad, I can
 * skip that course": {@code retry} and {@code regenerate} are {@code reroll} by other names, with its
 * confirm and refusals. "Will I be able to play the course previews?": {@code test} starts an admin
 * test run on the preview in the spare half, its real start, checkpoints, finish, clock and kit,
 * recording nothing (golf is walked: {@code tp <course> idle}). "Find a good one before posting it
 * for the following week": {@code preview <course> next [seed]} builds a candidate with the next
 * set's settings, and {@code choose} makes its seed the next set's course (a one-set pin),
 * {@code unchoose} cancels. The course screens carry the same as admin-only buttons.
 *
 * <p>Round 2, G2 #1: {@code promote <course> <seed>} and {@code choose <course> <seed> [<pick>|none]}
 * act only while the preview is still that seed (and, for {@code choose confirm}, the pick still to
 * come is the one named): what the buttons' "Sure?" screens send, so a Yes pressed after a new
 * preview came along acts on nothing. Typed without a seed, they act on whatever stands, as before.
 */
public final class GenAdmin implements GameAdmin {

    /** The verbs, in help order. */
    public static final List<String> VERBS = List.of("status", "plan", "preview", "test", "promote", "choose", "unchoose",
            "reroll", "retry", "regenerate", "rebuild", "on", "off", "tier", "mix", "pin", "unpin", "tp", "claim", "clear",
            "tidy", "retire", "history", "recall", "unrecall", "keep", "plots", "clear-plot");
    /** Other names for a verb (the owner's words): each does exactly what its verb does. */
    public static final Map<String, String> ALIASES = Map.of("retry", "reroll", "regenerate", "reroll", "retire", "tidy");
    /** Verbs that change nothing (not logged). */
    private static final List<String> LOOKS = List.of("status", "plan", "tp", "help", "history", "plots", "test");
    /** Verbs that also take a Classics slot's id. */
    private static final List<String> CLASSIC_VERBS = List.of("status", "rebuild", "tp", "claim", "tidy");
    /** How a preview asks for a Mountain Run v2 style: {@code style:road} or {@code style:slalom}. */
    static final String STYLE = "style:";

    /**
     * Starts an admin's test run on a course that isn't the live one (a preview): Time Trials' test
     * run, which records nothing. {@code again} is what its "Play again" does: this command again.
     */
    public interface Tester {
        void test(Player player, Course course, Runnable again);
    }

    private final Supplier<GenOps> ops;
    private final Logger log;
    private final Tester tester;

    /**
     * @param ops the running engine, or {@code null} while Fresh Courses is off
     * @param log where changes are logged, with who made them
     */
    public GenAdmin(Supplier<GenOps> ops, Logger log) {
        this(ops, log, (player, course, again) -> player.sendMessage(Text.of("&cTest runs aren't available right now.")));
    }

    /** @param tester how {@code test} starts a test run on a preview (Time Trials') */
    public GenAdmin(Supplier<GenOps> ops, Logger log, Tester tester) {
        this.ops = ops;
        this.log = log;
        this.tester = tester;
    }

    /** A verb as typed, as the verb it names ({@code regenerate} is {@code reroll}). */
    public static String verb(String typed) {
        String v = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(v, v);
    }

    @Override
    public String name() {
        return "gen";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games gen status [course] &7- how often they change, when next, what is up, and why not",
                "&e/hcm games gen plan <course> [seed|next] &7- a dry run: what a build would make, no blocks",
                "&e/hcm games gen preview <course> [next] [seed] &7- build into the spare half to try it (no switch,"
                        + " even while it's off); next: a candidate for the next set; Ice Boat: style:road|slalom",
                "&e/hcm games gen test <course> &7- a test run on the preview (nothing is recorded; golf: tp idle)",
                "&e/hcm games gen promote <course> [seed] [confirm] &7- the preview becomes the current course (with"
                        + " a seed: only while the preview is that seed)",
                "&e/hcm games gen choose <course> [seed] [confirm] &7- the preview's seed is the next set's course;"
                        + " unchoose to cancel",
                "&e/hcm games gen reroll <course|all> confirm &7- a new course for this set, on a fresh board",
                "&e/hcm games gen retry|regenerate <course|all> confirm &7- the same as reroll",
                "&e/hcm games gen rebuild <course> &7- check and repair the current course (same seed)",
                "&e/hcm games gen on|off <course> &7- open or close one (its blocks stay)",
                "&e/hcm games gen tier <course> <easy|medium|hard> &7- its difficulty from the next build",
                "&e/hcm games gen mix <golf course|dropper> <E, M and H> &7- the holes or levels from the next build",
                "&e/hcm games gen pin <course> <seed|live> [days] &7- keep a good course; unpin to let it change",
                "&e/hcm games gen tp <course> [live|idle] &7- go and look",
                "&e/hcm games gen claim <course|plot n> [confirm] &7- count what is in a new area; confirm clears"
                        + " foreign blocks and claims it",
                "&e/hcm games gen clear <course> confirm &7- empty both halves and switch it off (before moving it)",
                "&e/hcm games gen tidy|retire <course> [confirm] &7- the old area a course left when it moved or grew:"
                        + " confirm empties it now (only Fresh Courses' own blocks, water first)",
                "&e/hcm games gen history <course|all> [page] &7- every past course with its code; history <code> for one",
                "&e/hcm games gen recall <code> [days|forever] [confirm] &7- bring a past course back into a Classics"
                        + " slot (or: recall <classic|kind> <course> <last|number|date d|seed:hex>)",
                "&e/hcm games gen unrecall <classic> [confirm] &7- close a Classics slot",
                "&e/hcm games gen keep <code|course [which]> <new-id> [name] [--fresh-board] confirm &7- keep a"
                        + " course for good as a normal course",
                "&e/hcm games gen plots &7- the kept courses and their plots",
                "&e/hcm games gen clear-plot <n> confirm &7- delete a kept course and its board, and clear its plot");
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            run(sender, args);
        } catch (RuntimeException e) {
            // Never out to the framework's guard: that would switch Fresh Courses off over a typo.
            log.log(Level.SEVERE, "Fresh Courses: /hcm games gen " + String.join(" ", args) + " failed", e);
            say(sender, "&cThat didn't work - see the console.");
        }
    }

    private void run(CommandSender sender, String[] args) {
        String typed = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String verb = verb(typed);
        if (verb.equals("help") || !VERBS.contains(verb)) {
            if (!verb.equals("help")) {
                say(sender, "&cUnknown: &f" + args[0] + "&c. &7Try one of: " + String.join(", ", VERBS));
            }
            help().forEach(line -> say(sender, line));
            return;
        }
        GenOps engine = ops.get();
        if (engine == null) {
            say(sender, "&cFresh Courses isn't running. &7Set games.fresh.enabled: true (and games.enabled), then"
                    + " /hcm reload.");
            return;
        }
        boolean confirm = args.length > 1 && args[args.length - 1].equalsIgnoreCase("confirm");
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length - (confirm ? 1 : 0); i++) {
            rest.add(args[i]);
        }
        Consumer<String> report = line -> say(sender, line);
        if (verb.equals("status")) {
            String id = rest.isEmpty() ? null : slot(sender, rest.get(0), true);
            if (!rest.isEmpty() && id == null) {
                return;
            }
            say(sender, "&6Fresh Courses");
            engine.status(id).forEach(report);
            return;
        }
        if (archiveVerb(sender, engine, verb, rest, confirm, args, report)) {
            return;
        }
        if (verb.equals("reroll") && !rest.isEmpty() && rest.get(0).equalsIgnoreCase("all")) {
            if (refusedNearRestart(sender, engine) || !confirmed(sender, confirm, typed + " all",
                    "This makes a new course of every Fresh Course, for this set. Anyone playing one finishes on"
                            + " its old board.")) {
                return;
            }
            logChange(sender, args);
            for (String id : Slots.ids()) {
                engine.reroll(id, report);
            }
            return;
        }
        if (rest.isEmpty()) {
            say(sender, "&cWhich course? &7" + String.join(", ", Slots.ids()));
            return;
        }
        if ((verb.equals("claim") || verb.equals("tp")) && rest.get(0).equalsIgnoreCase("plot")) {
            int n = rest.size() > 1 ? plot(rest.get(1)) : 0;
            if (n < 1) {
                say(sender, "&cWhich plot? A number, like: /hcm games gen " + verb + " plot 3");
                return;
            }
            if (verb.equals("tp")) {
                tp(sender, engine, "plot:" + n, null);
                return;
            }
            if (confirm) {
                logChange(sender, args);
            }
            engine.claimPlot(n, confirm, report);
            return;
        }
        if ((verb.equals("choose") || verb.equals("unchoose")) && Slots.of(rest.get(0)) == null
                && Slots.any(rest.get(0)) != null) {
            say(sender, "&c" + Slots.any(rest.get(0)).name() + " is a Classics slot: it holds a course brought back with"
                    + " recall. &7Choose is for Fresh Courses.");
            return;
        }
        String id = slot(sender, rest.get(0), CLASSIC_VERBS.contains(verb));
        if (id == null) {
            return;
        }
        Slots.Def def = Slots.any(id);
        String arg = rest.size() > 1 ? rest.get(1) : null;
        switch (verb) {
            case "plan" -> {
                if (arg != null && !arg.equalsIgnoreCase("next") && !arg.equalsIgnoreCase("tomorrow")
                        && GenSeed.parse(arg) == null) {
                    say(sender, "&cA seed is up to 16 hex digits (like 3f2a91c07d1e55b0), or &enext&c.");
                    return;
                }
                engine.plan(id, arg, report);
            }
            case "preview" -> {
                // style:road|slalom anywhere after the course (Ice Boat, MOUNTAIN-V2-SPEC §5.1): the seed search
                List<String> words = new ArrayList<>(rest.subList(1, rest.size()));
                String styleWord = styleWord(words);
                BoatStyle style = styleWord == null ? null : BoatStyle.byWord(styleWord);
                if (styleWord != null && style == null) {
                    say(sender, "&cA style is style:road (the Winding Road) or style:slalom.");
                    return;
                }
                if (style != null && !Slots.BOAT.equals(def.generator())) {
                    say(sender, "&cOnly Ice Boat has styles (style:road or style:slalom).");
                    return;
                }
                String first = words.isEmpty() ? null : words.get(0);
                boolean next = first != null && first.equalsIgnoreCase("next");
                String seed = next ? (words.size() > 1 ? words.get(1) : null) : first;
                if (seed != null && GenSeed.parse(seed) == null) {
                    say(sender, "&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0.");
                    return;
                }
                if (refusedNearRestart(sender, engine)) {
                    return;
                }
                logChange(sender, args);
                if (next) {
                    engine.previewNext(id, seed, style, report);
                } else {
                    engine.preview(id, seed, style, report);
                }
            }
            case "test" -> test(sender, engine, id);
            case "choose" -> {
                GenOps.Shown shown = shown(sender, arg, rest.size() > 2 ? rest.get(2) : null);
                if (arg != null && shown == null) {
                    return;
                }
                logChange(sender, args);
                engine.choose(id, shown, confirm, report);
            }
            case "unchoose" -> {
                logChange(sender, args);
                engine.unchoose(id, report);
            }
            case "promote" -> {
                GenOps.Shown shown = shown(sender, arg, null);
                if ((arg != null && shown == null) || refusedNearRestart(sender, engine)) {
                    return;
                }
                logChange(sender, args);
                engine.promote(id, shown, confirm, report);
            }
            case "reroll" -> {
                if (refusedNearRestart(sender, engine) || !confirmed(sender, confirm, typed + " " + id,
                        "This makes a new " + def.name() + " for this set, on a fresh board. Anyone playing it"
                                + " finishes on the old one.")) {
                    return;
                }
                logChange(sender, args);
                engine.reroll(id, report);
            }
            case "rebuild" -> {
                logChange(sender, args);
                engine.rebuild(id, report);
            }
            case "on", "off" -> {
                logChange(sender, args);
                engine.enable(id, verb.equals("on"), report);
            }
            case "tier", "mix" -> {
                boolean wants = def.mixed() ? verb.equals("mix") : verb.equals("tier");
                if (!wants) {
                    say(sender, def.mixed() ? "&c" + def.name() + " takes a mix of " + (def.golf() ? "holes" : "levels")
                            + ": &e/hcm games gen mix " + id + " " + def.tierOrMix() : "&c" + def.name()
                            + " takes a tier: &e/hcm games gen tier " + id + " <easy|medium|hard>");
                    return;
                }
                if (arg == null) {
                    say(sender, "&cUsage: /hcm games gen " + verb + " " + id + " " + (def.mixed()
                            ? "<E, M and H, like " + def.tierOrMix() + ">" : "<easy|medium|hard>"));
                    return;
                }
                String problem = def.tierProblem(arg);
                if (problem != null) {
                    say(sender, "&c" + problem + ".");
                    return;
                }
                logChange(sender, args);
                engine.tier(id, arg, report);
            }
            case "pin" -> {
                if (arg == null) {
                    say(sender, "&cUsage: /hcm games gen pin " + id + " <seed|live> [days]");
                    return;
                }
                if (!live(arg) && GenSeed.parse(arg) == null) {
                    say(sender, "&cA seed is up to 16 hex digits, or &elive&c.");
                    return;
                }
                int days = 0;
                if (rest.size() > 2) {
                    days = days(rest.get(2));
                    if (days < 1) {
                        say(sender, "&cDays is a whole number from 1 to 365.");
                        return;
                    }
                }
                logChange(sender, args);
                engine.pin(id, live(arg) ? "live" : arg, days, report);
            }
            case "unpin" -> {
                logChange(sender, args);
                engine.unpin(id, report);
            }
            case "tp" -> tp(sender, engine, id, arg);
            case "claim" -> {
                if (confirm) {
                    logChange(sender, args);
                }
                engine.claim(id, confirm, report);
            }
            case "clear" -> {
                if (!confirmed(sender, confirm, "clear " + id, "This empties both halves of " + def.name()
                        + " and switches it off. Its board and stars stay.")) {
                    return;
                }
                logChange(sender, args);
                engine.clear(id, report);
            }
            case "tidy" -> {
                if (confirm) {
                    if (refusedNearRestart(sender, engine)) {
                        return;
                    }
                    logChange(sender, args);
                }
                engine.tidy(id, confirm, report);
            }
            default -> help().forEach(report);
        }
    }

    /** The {@code style:} word's value taken out of {@code words} ({@code style:road} gives "road"), or {@code null}. */
    static String styleWord(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (w.toLowerCase(Locale.ROOT).startsWith(STYLE)) {
                words.remove(i);
                return w.substring(STYLE.length());
            }
        }
        return null;
    }

    /**
     * {@code test <course>}: an admin's test run on the preview in the spare half (WP-ADM), refused
     * near a restart, with no preview, on golf (walked instead) and while the course is off for a problem
     * (switched off, its preview can be tried: CV final gate, a preview before switching it on).
     */
    private void test(CommandSender sender, GenOps engine, String id) {
        if (!(sender instanceof Player player)) {
            say(sender, "&cOnly a player can run a course.");
            return;
        }
        if (refusedNearRestart(sender, engine)) {
            return;
        }
        GenOps.PreviewRun run = engine.previewRun(id);
        if (run == null || run.course() == null) {
            say(sender, run == null ? "&cThere's no preview to try." : run.refusal());
            return;
        }
        tester.test(player, run.course(), () -> handle(player, new String[]{"test", id}));
    }

    private void tp(CommandSender sender, GenOps engine, String id, String which) {
        if (!(sender instanceof Player player)) {
            say(sender, "&cOnly players can go there.");
            return;
        }
        boolean idle = which != null && which.equalsIgnoreCase("idle");
        if (which != null && !idle && !which.equalsIgnoreCase("live")) {
            say(sender, "&cUsage: /hcm games gen tp " + id + " [live|idle]");
            return;
        }
        GenOps.Spot spot = engine.spot(id, idle);
        World world = spot == null ? null : Bukkit.getWorld(spot.world());
        if (world == null) {
            say(sender, "&cThere's nowhere to go for that yet.");
            return;
        }
        player.teleport(new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), 0f));
        Slots.Def def = Slots.any(id);
        say(sender, def == null ? "&7" + id.replace("plot:", "Plot ") + "." : "&7" + (idle ? "The spare half"
                : "The current course") + " of " + def.name() + ".");
    }

    /** Whether a restart is too close for this (the sender is told). */
    private static boolean refusedNearRestart(CommandSender sender, GenOps engine) {
        String soon = engine.restartSoon();
        if (soon != null) {
            say(sender, soon);
            return true;
        }
        return false;
    }

    /**
     * Round 2, G2 #1: what a {@code promote} or {@code choose} names, so it acts only on that: the
     * preview's seed ({@code seed}) and, for {@code choose}, the pick still to come ({@code pick}: a seed,
     * or {@code none}). {@code null} when no seed was typed (whatever stands), or after telling the
     * sender that a word isn't a seed.
     */
    private static GenOps.Shown shown(CommandSender sender, String seed, String pick) {
        if (seed == null) {
            return null;
        }
        Long preview = GenSeed.parse(seed);
        Long picked = pick == null || pick.equalsIgnoreCase("none") ? null : GenSeed.parse(pick);
        if (preview == null || (pick != null && !pick.equalsIgnoreCase("none") && picked == null)) {
            say(sender, "&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0" + (pick == null ? "." : "; the pick"
                    + " it replaces is one too, or &enone&c."));
            return null;
        }
        return new GenOps.Shown(preview, pick != null, picked);
    }

    /** Whether {@code confirm} was typed; when not, the sender reads what it would do and how to confirm. */
    private static boolean confirmed(CommandSender sender, boolean confirm, String words, String what) {
        if (confirm) {
            return true;
        }
        say(sender, "&e" + what + " &7Type &e/hcm games gen " + words + " confirm");
        return false;
    }

    /** The slot (or, when {@code classics}, Classics slot) id typed, or {@code null} after saying the choices. */
    private static String slot(CommandSender sender, String typed, boolean classics) {
        Slots.Def def = classics ? Slots.any(typed) : Slots.of(typed);
        if (def == null) {
            say(sender, "&cNo Fresh Course called '" + typed + "'. &7" + String.join(", ", Slots.ids())
                    + (classics ? ", " + String.join(", ", Slots.classicIds()) : ""));
            return null;
        }
        return def.id();
    }

    /**
     * {@code history}, {@code plots}, {@code recall}, {@code unrecall}, {@code keep} and
     * {@code clear-plot} (GEN-SPEC-KEEP); {@code false} for any other verb.
     */
    private boolean archiveVerb(CommandSender sender, GenOps engine, String verb, List<String> rest, boolean confirm,
                                String[] args, Consumer<String> report) {
        switch (verb) {
            case "history" -> {
                GenArgs.History h = GenArgs.history(rest);
                if (h.error() != null) {
                    say(sender, "&c" + h.error());
                } else if (h.detail() != null) {
                    engine.historyOf(h.slot(), h.detail(), report);
                } else {
                    engine.history(h.slot(), h.page()).forEach(report);
                }
            }
            case "plots" -> engine.plots().forEach(report);
            case "recall" -> {
                GenArgs.Recall r = GenArgs.recall(rest);
                if (r.error() != null) {
                    say(sender, "&c" + r.error());
                    return true;
                }
                if (refusedNearRestart(sender, engine)) {
                    return true;
                }
                logChange(sender, args);
                engine.recall(r.classic(), r.slot(), r.which(), r.days(), confirm, report);
            }
            case "unrecall" -> {
                Slots.Def c = rest.isEmpty() ? null : Slots.classicByWord(rest.get(0));
                if (c == null) {
                    say(sender, "&cWhich Classics slot? &7" + String.join(", ", Slots.classicIds()));
                    return true;
                }
                logChange(sender, args);
                engine.unrecall(c.id(), confirm, report);
            }
            case "keep" -> {
                GenArgs.Keep k = GenArgs.keep(rest);
                if (k.error() != null) {
                    say(sender, "&c" + k.error());
                    return true;
                }
                if (refusedNearRestart(sender, engine)) {
                    return true;
                }
                if (confirm) {
                    logChange(sender, args);
                }
                engine.keep(k.slot(), k.which(), k.id(), k.name(), k.freshBoard(), confirm, report);
            }
            case "clear-plot" -> {
                int n = rest.isEmpty() ? 0 : plot(rest.get(0));
                if (n < 1) {
                    say(sender, "&cWhich plot? A number, like: /hcm games gen clear-plot 3 confirm &7(/hcm games gen"
                            + " plots)");
                    return true;
                }
                if (confirm) {
                    logChange(sender, args);
                }
                engine.clearPlot(n, confirm, report);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A plot number as typed (1 to 100), or 0. */
    private static int plot(String typed) {
        try {
            int n = Integer.parseInt(typed.trim());
            return n >= 1 && n <= 100 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The live layout's seed: {@code live}, or {@code today} as it was first called. */
    private static boolean live(String typed) {
        return typed.equalsIgnoreCase("live") || typed.equalsIgnoreCase("today");
    }

    private static int days(String typed) {
        try {
            int d = Integer.parseInt(typed.trim());
            return d >= 1 && d <= 365 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void logChange(CommandSender sender, String[] args) {
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!LOOKS.contains(verb)) {
            log.info("Fresh Courses: " + sender.getName() + " ran /hcm games gen " + String.join(" ", args));
        }
    }

    private static void say(CommandSender sender, String line) {
        sender.sendMessage(Text.of(line));
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 0) {
            return out;
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            match(out, last, VERBS);
            return out;
        }
        String verb = verb(args[0]);
        if (!VERBS.contains(verb)) {
            return out;
        }
        List<String> archive = archiveTab(verb, args, last);
        if (archive != null) {
            return archive;
        }
        if (args.length == 2) {
            List<String> ids = new ArrayList<>(Slots.ids());
            if (verb.equals("reroll")) {
                ids.add("all");
            }
            if (CLASSIC_VERBS.contains(verb)) {
                ids.addAll(Slots.classicIds());
            }
            if (verb.equals("claim") || verb.equals("tp")) {
                ids.add("plot");
            }
            if (verb.equals("tier")) {
                ids.removeIf(id -> Slots.of(id).mixed());
            } else if (verb.equals("mix")) {
                ids.removeIf(id -> !Slots.of(id).mixed());
            }
            match(out, last, ids);
            return out;
        }
        Slots.Def def = Slots.of(args[1]);
        if (verb.equals("preview") && def != null && Slots.BOAT.equals(def.generator()) && args.length >= 3
                && args.length <= 5) { // Ice Boat's style:road|slalom, anywhere after the course
            List<String> offer = new ArrayList<>(args.length == 3 ? List.of("next") : List.of());
            offer.addAll(List.of(STYLE + BoatStyle.ROAD.id(), STYLE + BoatStyle.SLALOM.id()));
            match(out, last, offer);
            return out;
        }
        if (args.length == 3) {
            switch (verb) {
                case "plan", "preview" -> match(out, last, List.of("next"));
                case "tier" -> match(out, last, Slots.TIERS);
                case "mix" -> match(out, last, def == null ? List.of() : List.of(def.tierOrMix()));
                case "pin" -> match(out, last, List.of("live"));
                case "tp" -> match(out, last, List.of("live", "idle"));
                case "promote", "choose", "reroll", "claim", "clear", "tidy" -> match(out, last, List.of("confirm"));
                default -> {
                    // nothing more to offer
                }
            }
            return out;
        }
        if (args.length == 4 && verb.equals("pin")) {
            match(out, last, List.of("1", "7", "30"));
        }
        return out;
    }

    /** Completion for the archive's verbs, or {@code null} for any other verb. */
    private List<String> archiveTab(String verb, String[] args, String last) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        switch (verb) {
            case "history" -> {
                if (n == 2) {
                    List<String> ids = new ArrayList<>(Slots.ids());
                    ids.add("all");
                    match(out, last, ids);
                } else if (n == 3 && Slots.of(args[1]) != null) {
                    match(out, last, List.of("last", "date", "2"));
                }
            }
            case "recall" -> {
                if (n == 2) {
                    List<String> words = new ArrayList<>(Slots.classicIds());
                    words.addAll(List.of("parkour", "rings", "golf", "dropper"));
                    match(out, last, words);
                } else if (n == 3 && Slots.classicByWord(args[1]) != null) {
                    Slots.Def c = Slots.classicByWord(args[1]);
                    List<String> ids = new ArrayList<>();
                    for (Slots.Def d : Slots.ALL) {
                        if (Slots.classicFor(d) == c) {
                            ids.add(d.id());
                        }
                    }
                    match(out, last, ids);
                } else if (n == 4 && Slots.of(args[2]) != null) {
                    match(out, last, List.of("last", "date", "seed:"));
                } else if (n >= 3) {
                    match(out, last, List.of("7", "forever", "confirm"));
                }
            }
            case "unrecall" -> {
                if (n == 2) {
                    match(out, last, Slots.classicIds());
                } else if (n == 3) {
                    match(out, last, List.of("confirm"));
                }
            }
            case "keep" -> {
                if (n == 2) {
                    match(out, last, Slots.ids());
                } else if (n == 3 && Slots.of(args[1]) != null) {
                    match(out, last, List.of("current", "last"));
                } else if (n >= 4) {
                    match(out, last, List.of("--fresh-board", "confirm"));
                }
            }
            case "clear-plot" -> {
                if (n == 2) {
                    GenOps engine = ops.get();
                    List<String> used = new ArrayList<>();
                    for (int p : engine == null ? List.<Integer>of() : engine.usedPlots()) {
                        used.add(Integer.toString(p));
                    }
                    match(out, last, used);
                } else if (n == 3) {
                    match(out, last, List.of("confirm"));
                }
            }
            case "plots" -> {
                // nothing more to offer
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private static void match(List<String> out, String prefix, List<String> words) {
        for (String w : words) {
            if (w.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(w);
            }
        }
    }
}
