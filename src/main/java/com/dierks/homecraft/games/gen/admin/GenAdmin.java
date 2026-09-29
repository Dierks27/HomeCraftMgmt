package com.dierks.homecraft.games.gen.admin;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /hcm games gen …}: running Daily Courses by hand (GEN-SPEC §5.5). The framework has already
 * checked {@code hcm.games.admin}; {@code args} are the words after {@code gen}.
 *
 * <p>Why these verbs: the courses build themselves, so an admin only ever needs to look
 * ({@code status}, {@code plan}, {@code tp}), steer the next build ({@code tier}, {@code mix},
 * {@code pin}, {@code on}/{@code off}), try one out ({@code preview}, then {@code promote}), replace
 * today's ({@code reroll}), repair ({@code rebuild}), and hand an area over or take one back
 * ({@code claim}, {@code clear}). The hand-built course tools refuse a generated course and point
 * here.
 *
 * <p>The rules around them: destructive verbs need {@code confirm} ({@code reroll}, {@code clear},
 * {@code claim} to clear an area; {@code promote} when today's board has times, which the engine
 * decides). {@code reroll}, {@code preview} and {@code promote} are refused within
 * {@code avoid_before_restart_minutes} of a scheduled restart. Every change is logged with who made
 * it, and a command that goes wrong answers "That didn't work - see the console." and never
 * reaches the framework's guard, which would switch Daily Courses off.
 */
public final class GenAdmin implements GameAdmin {

    /** The verbs, in help order. */
    public static final List<String> VERBS = List.of("status", "plan", "preview", "promote", "reroll", "rebuild",
            "on", "off", "tier", "mix", "pin", "unpin", "tp", "claim", "clear");
    /** Verbs that change nothing (not logged). */
    private static final List<String> LOOKS = List.of("status", "plan", "tp", "help");

    private final Supplier<GenOps> ops;
    private final Logger log;

    /**
     * @param ops the running engine, or {@code null} while Daily Courses is off
     * @param log where changes are logged, with who made them
     */
    public GenAdmin(Supplier<GenOps> ops, Logger log) {
        this.ops = ops;
        this.log = log;
    }

    @Override
    public String name() {
        return "gen";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games gen status [course] &7- what is up, what is being built, and why not",
                "&e/hcm games gen plan <course> [seed|tomorrow] &7- a dry run: what a build would make, no blocks",
                "&e/hcm games gen preview <course> [seed] &7- build into the spare half to walk it (no switch)",
                "&e/hcm games gen promote <course> [confirm] &7- the preview becomes today's course",
                "&e/hcm games gen reroll <course|all> confirm &7- a new course for today, on a fresh board",
                "&e/hcm games gen rebuild <course> &7- check and repair today's course (same seed)",
                "&e/hcm games gen on|off <course> &7- open or close one (its blocks stay)",
                "&e/hcm games gen tier <course> <easy|medium|hard> &7- its difficulty from the next build",
                "&e/hcm games gen mix <golf course> <E, M and H> &7- the golf holes from the next build",
                "&e/hcm games gen pin <course> <seed|today> [days] &7- keep a good course; unpin to let it change",
                "&e/hcm games gen tp <course> [live|idle] &7- go and look",
                "&e/hcm games gen claim <course> [confirm] &7- count what is in a new area; confirm clears foreign blocks and claims it",
                "&e/hcm games gen clear <course> confirm &7- empty both halves and switch it off (before moving it)");
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            run(sender, args);
        } catch (RuntimeException e) {
            // Never out to the framework's guard: that would switch Daily Courses off over a typo.
            log.log(Level.SEVERE, "Daily Courses: /hcm games gen " + String.join(" ", args) + " failed", e);
            say(sender, "&cThat didn't work - see the console.");
        }
    }

    private void run(CommandSender sender, String[] args) {
        String verb = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (verb.equals("help") || !VERBS.contains(verb)) {
            if (!verb.equals("help")) {
                say(sender, "&cUnknown: &f" + args[0] + "&c. &7Try one of: " + String.join(", ", VERBS));
            }
            help().forEach(line -> say(sender, line));
            return;
        }
        GenOps engine = ops.get();
        if (engine == null) {
            say(sender, "&cDaily Courses isn't running. &7Set games.daily.enabled: true (and games.enabled), then"
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
            String id = rest.isEmpty() ? null : slot(sender, rest.get(0));
            if (!rest.isEmpty() && id == null) {
                return;
            }
            say(sender, "&6Daily Courses");
            engine.status(id).forEach(report);
            return;
        }
        if (verb.equals("reroll") && !rest.isEmpty() && rest.get(0).equalsIgnoreCase("all")) {
            if (refusedNearRestart(sender, engine) || !confirmed(sender, confirm, "reroll all",
                    "This makes a new course of every daily course for today. Anyone playing one finishes on"
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
        String id = slot(sender, rest.get(0));
        if (id == null) {
            return;
        }
        Slots.Def def = Slots.of(id);
        String arg = rest.size() > 1 ? rest.get(1) : null;
        switch (verb) {
            case "plan" -> {
                if (arg != null && !arg.equalsIgnoreCase("tomorrow") && GenSeed.parse(arg) == null) {
                    say(sender, "&cA seed is up to 16 hex digits (like 3f2a91c07d1e55b0), or &etomorrow&c.");
                    return;
                }
                engine.plan(id, arg, report);
            }
            case "preview" -> {
                if (arg != null && GenSeed.parse(arg) == null) {
                    say(sender, "&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0.");
                    return;
                }
                if (refusedNearRestart(sender, engine)) {
                    return;
                }
                logChange(sender, args);
                engine.preview(id, arg, report);
            }
            case "promote" -> {
                if (refusedNearRestart(sender, engine)) {
                    return;
                }
                logChange(sender, args);
                engine.promote(id, confirm, report);
            }
            case "reroll" -> {
                if (refusedNearRestart(sender, engine) || !confirmed(sender, confirm, "reroll " + id,
                        "This makes a new " + def.name() + " for today, on a fresh board. Anyone playing it"
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
                boolean wants = def.golf() ? verb.equals("mix") : verb.equals("tier");
                if (!wants) {
                    say(sender, def.golf() ? "&c" + def.name() + " takes a mix of holes: &e/hcm games gen mix " + id
                            + " " + def.tierOrMix() : "&c" + def.name() + " takes a tier: &e/hcm games gen tier " + id
                            + " <easy|medium|hard>");
                    return;
                }
                if (arg == null) {
                    say(sender, "&cUsage: /hcm games gen " + verb + " " + id + " " + (def.golf()
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
                    say(sender, "&cUsage: /hcm games gen pin " + id + " <seed|today> [days]");
                    return;
                }
                if (!arg.equalsIgnoreCase("today") && GenSeed.parse(arg) == null) {
                    say(sender, "&cA seed is up to 16 hex digits, or &etoday&c.");
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
                engine.pin(id, arg, days, report);
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
            default -> help().forEach(report);
        }
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
        say(sender, "&7" + (idle ? "The spare half" : "Today's course") + " of " + Slots.of(id).name() + ".");
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

    /** Whether {@code confirm} was typed; when not, the sender reads what it would do and how to confirm. */
    private static boolean confirmed(CommandSender sender, boolean confirm, String words, String what) {
        if (confirm) {
            return true;
        }
        say(sender, "&e" + what + " &7Type &e/hcm games gen " + words + " confirm");
        return false;
    }

    /** The slot id typed, or {@code null} after telling the sender the choices. */
    private static String slot(CommandSender sender, String typed) {
        Slots.Def def = Slots.of(typed);
        if (def == null) {
            say(sender, "&cNo daily course called '" + typed + "'. &7" + String.join(", ", Slots.ids()));
            return null;
        }
        return def.id();
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
            log.info("Daily Courses: " + sender.getName() + " ran /hcm games gen " + String.join(" ", args));
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
        String verb = args[0].toLowerCase(Locale.ROOT);
        if (!VERBS.contains(verb)) {
            return out;
        }
        if (args.length == 2) {
            List<String> ids = new ArrayList<>(Slots.ids());
            if (verb.equals("reroll")) {
                ids.add("all");
            }
            if (verb.equals("tier")) {
                ids.removeIf(id -> Slots.of(id).golf());
            } else if (verb.equals("mix")) {
                ids.removeIf(id -> !Slots.of(id).golf());
            }
            match(out, last, ids);
            return out;
        }
        Slots.Def def = Slots.of(args[1]);
        if (args.length == 3) {
            switch (verb) {
                case "plan" -> match(out, last, List.of("tomorrow"));
                case "tier" -> match(out, last, Slots.TIERS);
                case "mix" -> match(out, last, def == null ? List.of() : List.of(def.tierOrMix()));
                case "pin" -> match(out, last, List.of("today"));
                case "tp" -> match(out, last, List.of("live", "idle"));
                case "promote", "reroll", "claim", "clear" -> match(out, last, List.of("confirm"));
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

    private static void match(List<String> out, String prefix, List<String> words) {
        for (String w : words) {
            if (w.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(w);
            }
        }
    }
}
