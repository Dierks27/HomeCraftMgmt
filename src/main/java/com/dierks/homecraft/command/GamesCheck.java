package com.dierks.homecraft.command;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /hcm games check} (EXTRAS E1): is the server set up for the Games? One line per check,
 * each OK, WARN or FAIL with the fix in plain words, then "All good" or "N things to fix".
 *
 * <p><b>Read-only.</b> Everything comes in through {@link Facts} — the live server's
 * ({@link GamesCheckLive}) or a test's — and this class only decides and words it, so the rules and
 * every message are tested without a server, and nothing here can change anything. A section whose
 * facts can't be read becomes one FAIL line saying so; the rest still run. It is safe with the games
 * on, off or failed to start.
 *
 * <p>The checks, in order: the {@code games.enabled} switch; the economy worlds exist; each Games
 * world is loaded, not an economy world and in adventure mode (Multiverse's setting, when it can be
 * read); Multiverse-Inventories gives the Games worlds a group of their own and keeps no per-game-mode
 * profiles; {@code games.restart_times} reads, with the next restart and hold; Fresh Courses (on,
 * the cadence, every area inside the world and clear of hand-built courses and each other, the
 * claims and foreign blocks, the next change, each course live or why not, the keep area and the
 * Classics); Falling Floors (its box and claim, {@link ArenaCheck}); the Clubhouse; what players can
 * see (each Games world void or not, something under its spawn, and every pair of the games' places
 * in sight of each other at the view distance the server really uses: WARNs only, {@link SightCheck});
 * every hand-built course is ready and its world loaded; the website feed; and how to take games of
 * chance away from one player.
 */
public final class GamesCheck {

    /** How a check came out. */
    public enum Status {
        OK, WARN, FAIL
    }

    /**
     * One line.
     *
     * @param what what was checked and what was found
     * @param fix  what to do about it, in plain words ({@code ""} for OK)
     */
    public record Line(Status status, String what, String fix) {

        static Line ok(String what) {
            return new Line(Status.OK, what, "");
        }

        static Line warn(String what, String fix) {
            return new Line(Status.WARN, what, fix);
        }

        static Line fail(String what, String fix) {
            return new Line(Status.FAIL, what, fix);
        }
    }

    /**
     * Multiverse-Inventories as far as the check can read it.
     *
     * @param groups       each group's worlds, or {@code null} when they can't be read
     * @param gamemodeShare whether it keeps an inventory per game mode, or {@code null} when unknown
     */
    public record MvInv(Map<String, List<String>> groups, Boolean gamemodeShare) {
    }

    /**
     * One Fresh Courses area.
     *
     * @param classic  a Classics slot
     * @param enabled  switched on in config (a Classics slot: always checked)
     * @param problems why its area can't be used (the world, a hand-built course, another area)
     * @param area     where it is, for admins
     */
    public record Region(String id, String name, boolean classic, boolean enabled, List<String> problems,
                         String area) {
    }

    /**
     * One Fresh Courses slot as the running engine holds it.
     *
     * @param wanted    switched on (config, or the admin's override)
     * @param problem   why it is off, or {@code null}
     * @param live      it has a live layout
     * @param current   that layout is vouched for and is the current set's (a Classics slot: holds its recall)
     * @param lastError why its last build failed, or {@code null}
     * @param holds     a Classics slot: what it holds, for admins, or {@code null}
     */
    public record SlotFact(String id, String name, boolean classic, boolean wanted, String problem, boolean claimed,
                           boolean live, boolean current, boolean building, String lastError, boolean healFailed,
                           String holds) {
    }

    /**
     * Fresh Courses.
     *
     * @param cadence     how often the courses change ("weekly", "every 3 days")
     * @param schedule    when ("New courses every Monday")
     * @param world       the world they are built in ({@code ""}: none)
     * @param slots       the running engine's slots, or {@code null} while it isn't running
     * @param nextChange  when the courses change next, for admins, or {@code null}
     * @param keepProblem why the keep area can't be used, or {@code null}
     * @param keepPlots   the kept-course plots that don't fit the world (past its border or height, or at
     *                    its spawn or the safe spot), each with its first problem: they are skipped
     */
    public record Fresh(boolean enabled, String cadence, String schedule, String world, boolean worldLoaded,
                        boolean worldListed, List<Region> regions, List<SlotFact> slots, String nextChange,
                        String keepProblem, String keepArea, Map<Integer, String> keepPlots, List<OldArea> oldAreas) {

        public Fresh {
            keepPlots = keepPlots == null ? Map.of() : Map.copyOf(keepPlots);
            oldAreas = oldAreas == null ? List.of() : List.copyOf(oldAreas);
        }

        /** Fresh Courses with no old area to tell about. */
        public Fresh(boolean enabled, String cadence, String schedule, String world, boolean worldLoaded,
                     boolean worldListed, List<Region> regions, List<SlotFact> slots, String nextChange,
                     String keepProblem, String keepArea, Map<Integer, String> keepPlots) {
            this(enabled, cadence, schedule, world, worldLoaded, worldListed, regions, slots, nextChange, keepProblem,
                    keepArea, keepPlots, List.of());
        }

        /** Fresh Courses with every plot fitting the world. */
        public Fresh(boolean enabled, String cadence, String schedule, String world, boolean worldLoaded,
                     boolean worldListed, List<Region> regions, List<SlotFact> slots, String nextChange,
                     String keepProblem, String keepArea) {
            this(enabled, cadence, schedule, world, worldLoaded, worldListed, regions, slots, nextChange, keepProblem,
                    keepArea, Map.of());
        }
    }

    /**
     * An old area a Fresh course left behind when its area moved or grew with an update (V4-DECISIONS "One
     * move mechanism"), or one emptied lately.
     *
     * @param id      the course (or Classics slot), for the fix's command
     * @param name    what an owner reads
     * @param state   where it is
     * @param where   its halves ("half A x ..; half B ..")
     * @param detail  what is in the way ({@link OldState#HELD}), the world it waits for, the first block left
     *                ({@link OldState#EMPTIED}), or {@code null}
     * @param percent how far a running one is
     * @param removed blocks taken away ({@link OldState#EMPTIED})
     * @param left    blocks left there because they weren't Fresh Courses' ({@link OldState#EMPTIED})
     */
    public record OldArea(String id, String name, OldState state, String where, String detail, int percent,
                          long removed, long left) {
    }

    /** Where an old area is. */
    public enum OldState {
        /** Emptied by itself once nothing else is being built (or being emptied now). */
        WAITING,
        /** Being emptied now. */
        RUNNING,
        /** Something is in the way: nothing there was changed, and it stays guarded. */
        HELD,
        /** An owner's move left it: guarded until {@code tidy}. */
        MANUAL,
        /** Its world isn't loaded. */
        ELSEWHERE,
        /** Emptied lately. */
        EMPTIED
    }

    /**
     * One hand-built course.
     *
     * @param golf     a golf course (else a time trial)
     * @param problems what stops it being played (its own validator's words)
     */
    public record Course(String id, boolean golf, String world, boolean enabled, boolean worldLoaded,
                         List<String> problems) {
    }

    /**
     * The website feed.
     *
     * @param buildError why {@code /api/arcade} doesn't build, or {@code null}
     */
    public record Web(boolean enabled, boolean tokenSet, String buildError, int games, int bytes) {
    }

    /**
     * Race Night as far as the check can read it (EVENTS-DROPPER-SPEC §A.11): each line is fine, or a
     * warning with its fix; {@code enabled} false when it is switched off.
     *
     * @param lines what was checked: the schedule reads and fits the restarts, the track can be raced
     *              (grid and stand), the stand is in the Games world
     */
    public record RaceNight(boolean enabled, List<RaceNightLine> lines) {

        public RaceNight {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One Race Night line: fine ({@code fix} is {@code null}), or a warning with its fix. */
    public record RaceNightLine(String what, String fix) {
    }

    /** Everything the check reads. Each is asked once; one that throws fails only its own section. */
    public interface Facts {

        /** The Games service started. */
        boolean serviceUp();

        /** {@code games.enabled}. */
        boolean gamesEnabled();

        /** {@code worlds.economy_enabled}. */
        List<String> economyWorlds();

        /** {@code games.worlds}. */
        List<String> gamesWorlds();

        boolean worldLoaded(String world);

        /** A world's Multiverse game mode ({@code ADVENTURE}), or {@code null} when it can't be read. */
        String gameMode(String world);

        /** Multiverse-Inventories, or {@code null} when it isn't installed. */
        MvInv mvInventories();

        /** {@code games.restart_times} as written. */
        List<Object> restartTimes();

        /** "Next restart: 4:00 PM (new runs held from 3:55 PM)". */
        String restartStatus();

        Fresh fresh();

        /** Every hand-built course. */
        List<Course> courses();

        Web web();

        /** Race Night's lines, or {@code null} when it isn't there to check. */
        default RaceNight raceNight() {
            return null;
        }

        // ---- Falling Floors (EVENTS-DROPPER-SPEC §C.2 WP-F) ----
        /** Falling Floors' box and claim ({@link ArenaCheck}); {@code null} reads as switched off. */
        default ArenaCheck.Facts arena() {
            return null;
        }
        // ---- end Falling Floors ----

        // ---- the Clubhouse (WP-CH) ----
        /** The Clubhouse's box, claim and room ({@link ClubhouseCheck}); {@code null} reads as switched off. */
        default ClubhouseCheck.Facts clubhouse() {
            return null;
        }
        // ---- end the Clubhouse ----

        /**
         * What players can see (LAYOUT-SPEC §5.1, {@link SightCheck}): the Games worlds' ground, the view
         * distance and every place; {@code null} skips the section.
         */
        default SightCheck.Facts sight() {
            return null;
        }
    }

    /** The LuckPerms line that takes games of chance away from one player. */
    static final String LUCKPERMS = "/lp user <player> permission set hcm.games.chance false";

    private static final Pattern FOREIGN = Pattern.compile("Region has ([0-9,]+) block");

    private GamesCheck() {
    }

    // ---- the checks ---------------------------------------------------------------------------------

    /** Every check, in order. Never throws. */
    public static List<Line> run(Facts f) {
        List<Line> out = new ArrayList<>();
        section(out, "the games switch", () -> games(f, out));
        List<String> economy = new ArrayList<>();
        section(out, "the economy worlds", () -> economy.addAll(economy(f, out)));
        List<String> worlds = new ArrayList<>();
        section(out, "the Games worlds", () -> worlds.addAll(gamesWorlds(f, economy, out)));
        section(out, "Multiverse-Inventories", () -> inventories(f.mvInventories(), worlds, economy, out));
        section(out, "games.restart_times", () -> restarts(f, out));
        section(out, "Fresh Courses", () -> fresh(f.fresh(), out));
        section(out, "Falling Floors", () -> ArenaCheck.rows(f.arena(), out)); // WP-F
        section(out, "the Clubhouse", () -> ClubhouseCheck.rows(f.clubhouse(), out)); // WP-CH
        section(out, "what players can see", () -> SightCheck.rows(f.sight(), out)); // WARN only, never FAIL
        section(out, "the hand-built courses", () -> courses(f.courses(), out));
        section(out, "Race Night", () -> raceNight(f.raceNight(), out));
        section(out, "the website feed", () -> web(f.web(), out));
        out.add(Line.ok("Games of chance need hcm.games.chance. To take them away from one player: " + LUCKPERMS));
        return out;
    }

    /** Run one section; one that throws becomes a FAIL line naming it. */
    private static void section(List<Line> out, String name, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException | LinkageError e) {
            out.add(Line.fail("Couldn't check " + name + " (" + e + ")", "see the console, then run the check again"));
        }
    }

    private static void games(Facts f, List<Line> out) {
        if (!f.serviceUp()) {
            out.add(Line.fail("The Games didn't start", "look for the error in the console, fix it, then /hcm reload"));
        } else if (!f.gamesEnabled()) {
            out.add(Line.warn("games.enabled is false, so every game is closed",
                    "set games.enabled: true, then /hcm reload"));
        } else {
            out.add(Line.ok("games.enabled is true"));
        }
    }

    private static List<String> economy(Facts f, List<Line> out) {
        List<String> eco = f.economyWorlds() == null ? List.of() : f.economyWorlds();
        if (eco.isEmpty()) {
            out.add(Line.warn("worlds.economy_enabled is empty, so the economy runs in every world, the Games world too",
                    "list your main and hub worlds in worlds.economy_enabled"));
        }
        for (String w : eco) {
            out.add(f.worldLoaded(w) ? Line.ok("Economy world '" + w + "' is loaded")
                    : Line.fail("Economy world '" + w + "' doesn't exist (or isn't loaded)",
                    "fix its name in worlds.economy_enabled - it must match the world's folder name"));
        }
        return eco;
    }

    private static List<String> gamesWorlds(Facts f, List<String> economy, List<Line> out) {
        List<String> worlds = f.gamesWorlds() == null ? List.of() : f.gamesWorlds();
        if (worlds.isEmpty()) {
            out.add(Line.warn("games.worlds is empty, so courses and golf have nowhere to be",
                    "make a world with Multiverse and list it in games.worlds"));
        }
        for (String w : worlds) {
            List<Line> problems = new ArrayList<>();
            boolean loaded = f.worldLoaded(w);
            if (!loaded) {
                problems.add(Line.fail("Games world '" + w + "' isn't loaded",
                        "make it with Multiverse (/mv create " + w + " normal), or fix its name in games.worlds"));
            }
            if (contains(economy, w)) {
                problems.add(Line.fail("Games world '" + w + "' is also an economy world",
                        "take it out of worlds.economy_enabled (world games still pay tokens there)"));
            }
            if (loaded) {
                String mode = f.gameMode(w);
                if (mode == null) {
                    problems.add(Line.warn("Games world '" + w + "': can't tell its game mode",
                            "check /mv info " + w + " - it should say adventure"));
                } else if (!mode.equalsIgnoreCase("ADVENTURE")) {
                    problems.add(Line.fail("Games world '" + w + "' is set to " + mode.toLowerCase(Locale.ROOT)
                            + " mode", "/mv modify " + w + " set gamemode adventure"));
                }
            }
            if (problems.isEmpty()) {
                out.add(Line.ok("Games world '" + w + "' is loaded, not an economy world, in adventure mode"));
            } else {
                out.addAll(problems);
            }
        }
        return worlds;
    }

    static void inventories(MvInv inv, List<String> gamesWorlds, List<String> economy, List<Line> out) {
        if (inv == null) {
            out.add(Line.ok("Multiverse-Inventories isn't installed - nothing to check"));
            return;
        }
        if (inv.groups() == null) {
            out.add(Line.warn("Couldn't read Multiverse-Inventories' groups",
                    "check by hand: each Games world needs a group of its own (plugins/Multiverse-Inventories/groups.yml)"));
        } else {
            for (String w : gamesWorlds) {
                List<String> own = new ArrayList<>();
                List<Line> problems = new ArrayList<>();
                for (Map.Entry<String, List<String>> g : inv.groups().entrySet()) {
                    if (!contains(g.getValue(), w)) {
                        continue;
                    }
                    List<String> others = new ArrayList<>();
                    List<String> eco = new ArrayList<>();
                    for (String x : g.getValue()) {
                        if (!contains(gamesWorlds, x)) {
                            others.add(x);
                            if (contains(economy, x)) {
                                eco.add(x);
                            }
                        }
                    }
                    if (!eco.isEmpty()) {
                        problems.add(Line.fail("Games world '" + w + "' shares things with the economy world '"
                                + eco.get(0) + "' (group '" + g.getKey() + "')",
                                "give the Games worlds a group of their own in plugins/Multiverse-Inventories/groups.yml"));
                    } else if (!others.isEmpty()) {
                        problems.add(Line.warn("Games world '" + w + "' shares group '" + g.getKey() + "' with "
                                + String.join(", ", others), "give the Games worlds a group of their own"));
                    } else {
                        own.add(g.getKey());
                    }
                }
                if (!problems.isEmpty()) {
                    out.addAll(problems);
                } else if (own.isEmpty()) {
                    out.add(Line.warn("Games world '" + w + "' isn't in a Multiverse-Inventories group",
                            "make a group with only the Games worlds in it"));
                } else {
                    out.add(Line.ok("Games world '" + w + "' has a Multiverse-Inventories group of its own ('"
                            + own.get(0) + "')"));
                }
            }
        }
        Boolean share = inv.gamemodeShare();
        if (share == null) {
            out.add(Line.warn("Couldn't read Multiverse-Inventories' game-mode setting",
                    "check by hand: share-handling.enable-gamemode-share-handling (use_game_mode_profiles on 4.x) "
                            + "must be false in its config.yml"));
        } else if (share) {
            out.add(Line.fail("Multiverse-Inventories keeps a separate inventory for each game mode",
                    "set share-handling.enable-gamemode-share-handling: false (use_game_mode_profiles: false on 4.x) "
                            + "in its config.yml, then restart"));
        } else {
            out.add(Line.ok("Multiverse-Inventories' game-mode share handling is off"));
        }
    }

    /**
     * {@code games.restart_times} as the owner wrote it, read the way the games read it: one value
     * is a list of one (an unquoted {@code 16:00} reads as the number 960), and only a key the
     * owner left out falls back to the shipped times. Bukkit's {@code getList} hands back the
     * shipped list for a single value, so the mistake this check is for would never be seen.
     */
    public static List<Object> restartTimes(ConfigurationSection config) {
        String path = "games.restart_times";
        if (config == null) {
            return List.of();
        }
        Object v = config.get(path, null);
        if (v == null) {
            List<?> shipped = config.getList(path);
            return shipped == null ? List.of() : new ArrayList<>(shipped);
        }
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        List<Object> one = new ArrayList<>();
        one.add(v instanceof ConfigurationSection section ? String.valueOf(section.getValues(false)) : v);
        return one;
    }

    private static void restarts(Facts f, List<Line> out) {
        List<Object> raw = f.restartTimes() == null ? List.of() : f.restartTimes();
        boolean bad = false;
        for (Object r : raw) {
            if (RestartHold.parseTime(r) == null) {
                bad = true;
                out.add(Line.fail("games.restart_times has " + (r instanceof String ? "\"" + r + "\"" : String.valueOf(r))
                                + ", which isn't a time",
                        "write each time in 24-hour form and in quotes, like \"04:00\" (04:00 without quotes reads as a number)"));
            }
        }
        if (bad) {
            return;
        }
        out.add(Line.ok(raw.isEmpty() ? "No restart times set, so nothing is held" : f.restartStatus()));
    }

    static void fresh(Fresh fr, List<Line> out) {
        if (fr == null || !fr.enabled()) {
            out.add(Line.ok("Fresh Courses is off - nothing to check (it needs games.enabled and games.fresh.enabled)"));
            return;
        }
        out.add(Line.ok("Fresh Courses is on: new courses " + fr.cadence() + " (" + fr.schedule() + ")"));
        if (fr.world() == null || fr.world().isBlank()) {
            out.add(Line.fail("Fresh Courses has no world", "list a Games world in games.worlds, or set games.fresh.world"));
        } else if (!fr.worldListed()) {
            out.add(Line.fail("Fresh Courses' world '" + fr.world() + "' isn't in games.worlds",
                    "add it to games.worlds, or set games.fresh.world to one that is"));
        } else if (!fr.worldLoaded()) {
            out.add(Line.fail("Fresh Courses' world '" + fr.world() + "' isn't loaded",
                    "make it with Multiverse, or fix games.fresh.world"));
        }
        Map<String, SlotFact> engine = new LinkedHashMap<>();
        if (fr.slots() != null) {
            for (SlotFact s : fr.slots()) {
                engine.put(s.id(), s);
            }
        } else {
            out.add(Line.warn("Fresh Courses isn't running, so its courses can't be checked",
                    "see /hcm games status (it says why)"));
        }
        for (Region r : fr.regions() == null ? List.<Region>of() : fr.regions()) {
            SlotFact s = engine.get(r.id());
            if (!r.classic() && !r.enabled() && (s == null || !s.wanted())) {
                continue; // switched off: nothing to check
            }
            slot(r, s, fr.slots() != null, out);
        }
        for (OldArea a : fr.oldAreas()) {
            out.add(oldArea(a));
        }
        if (fr.nextChange() != null) {
            out.add(Line.ok("Next change: " + fr.nextChange()));
        }
        out.add(fr.keepProblem() == null ? Line.ok("Keep area: " + fr.keepArea())
                : Line.fail("The keep area can't be used: " + fr.keepProblem(), "move it with games.fresh.keep.area"));
        if (fr.keepProblem() == null && !fr.keepPlots().isEmpty()) {
            // one WARN per kind of problem, lowest plot first, each with the fix that fits it
            Map<Regions.PlotIssue, List<Integer>> byKind = new LinkedHashMap<>();
            for (int n : new java.util.TreeSet<>(fr.keepPlots().keySet())) {
                byKind.computeIfAbsent(Regions.PlotIssue.of(fr.keepPlots().get(n)), k -> new ArrayList<>()).add(n);
            }
            for (Map.Entry<Regions.PlotIssue, List<Integer>> e : byKind.entrySet()) {
                List<Integer> plots = e.getValue();
                int n = plots.size();
                out.add(Line.warn(n + " kept-course plot" + (n == 1 ? "" : "s") + " (" + plotList(plots)
                        + ") can't be used: " + fr.keepPlots().get(plots.get(0)), plotFix(e.getKey(), fr.world())));
            }
        }
    }

    /** One old area's line: news while it is emptied, a WARN with the one fix when it can't be. */
    public static Line oldArea(OldArea a) {
        String tidy = "/hcm games gen tidy " + a.id() + " confirm";
        return switch (a.state()) {
            case WAITING -> Line.ok(a.name() + " moved to its new area; its old area (" + a.where() + ") is emptied by"
                    + " itself once nothing else is being built");
            case RUNNING -> Line.ok(a.name() + " moved to its new area; its old area (" + a.where() + ") is being"
                    + " emptied, " + a.percent() + "%");
            case HELD -> Line.warn(a.name() + "'s old area (" + a.where() + ") can't be emptied: " + a.detail()
                    + ". Nothing there was changed and it stays guarded", "move what is in the way, then " + tidy);
            case MANUAL -> Line.warn(a.name() + "'s old area (" + a.where() + ") still stands, guarded",
                    tidy + " empties it (only Fresh Courses' own blocks, water first)");
            case ELSEWHERE -> Line.warn(a.name() + "'s old area (" + a.where() + ") is in " + a.detail() + ", which"
                    + " isn't loaded, so it waits", "load " + a.detail() + " (Multiverse) and it is emptied by itself, or "
                    + tidy + " once it is");
            case EMPTIED -> a.left() > 0
                    ? Line.warn(a.name() + "'s old area (" + a.where() + ") is empty of Fresh Courses' blocks ("
                    + a.removed() + " taken away), but " + a.left() + " block" + (a.left() == 1 ? " that isn't" : "s that"
                    + " aren't") + " Fresh Courses' " + (a.left() == 1 ? "was" : "were") + " left there (first at "
                    + a.detail() + ")", "they are yours: remove them by hand if you like; nothing guards that area now")
                    : Line.ok(a.name() + " moved to its new area; its old area is empty (" + a.removed() + " blocks"
                    + " taken away)");
        };
    }

    /** The fix for kept plots with problem {@code kind} in {@code world}. */
    static String plotFix(Regions.PlotIssue kind, String world) {
        String fits = "; a course is only ever kept in a plot that fits";
        return switch (kind) {
            case BORDER -> "make the world border bigger (stand in " + world + " and use /worldborder set), or move"
                    + " games.fresh.keep.area" + fits;
            case SPAWN -> "move the world's spawn out of the keep area (stand elsewhere in " + world + " and use"
                    + " /setworldspawn), or move games.fresh.keep.area" + fits;
            case SAFE_SPOT -> "move games.fresh.safe_spot out of the keep area, or move games.fresh.keep.area" + fits;
            case HEIGHT -> "move games.fresh.keep.area to a height " + world + " has room for" + fits;
            case OTHER -> "move games.fresh.keep.area" + fits;
        };
    }

    /** "3" / "19-24" / "3, 19-24": plot numbers as ranges. */
    static String plotList(List<Integer> sorted) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            int from = sorted.get(i);
            int to = from;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == to + 1) {
                to = sorted.get(++i);
            }
            out.add(from == to ? Integer.toString(from) : from + "-" + to);
        }
        return String.join(", ", out);
    }

    /** One Fresh Courses slot: its area, then (engine running) its claim and course. */
    private static void slot(Region r, SlotFact s, boolean running, List<Line> out) {
        List<Line> problems = new ArrayList<>();
        String origin = r.classic() ? "games.fresh.classics.slots." + r.id() + ".origin" : "games.fresh.slots." + r.id() + ".origin";
        if (!r.problems().isEmpty()) {
            // a spot config can't read isn't one to move away from: fixing the value is the whole fix
            boolean typo = com.dierks.homecraft.games.gen.engine.GenService.UNPLACED.equals(r.problems().get(0));
            problems.add(Line.fail(r.name() + ": " + r.problems().get(0), typo ? "write " + origin + " (or its"
                    + " half_gap) as the console's WARN says, then /hcm reload; nothing moves meanwhile"
                    : "move it with " + origin));
        }
        List<String> state = new ArrayList<>();
        if (running && s != null) {
            Matcher m = s.problem() == null ? null : FOREIGN.matcher(s.problem());
            if (m != null && m.find()) {
                problems.add(Line.fail(r.name() + ": " + m.group(1) + " blocks that aren't Fresh Courses' are in its area",
                        "/hcm games gen claim " + r.id() + " confirm clears them (or move it with " + origin + ")"));
            } else if (s.problem() != null && r.problems().isEmpty()) {
                problems.add(Line.fail(r.name() + " is off: " + s.problem(), "see /hcm games gen status " + r.id()));
            } else if (s.claimed()) {
                state.add("claimed");
            } else {
                state.add("empty (claimed at its first build)");
            }
            if (r.classic()) {
                if (s.healFailed() && s.live()) {
                    problems.add(Line.fail(r.name() + " is closed: " + orWhy(s.lastError(), "its course couldn't be checked"),
                            "see /hcm games gen status " + r.id()));
                } else if (s.holds() != null) {
                    state.add("holds " + s.holds());
                } else if (s.building()) {
                    state.add("being built");
                } else {
                    state.add("empty - /hcm games gen recall brings an old course back");
                }
            } else if (s.problem() == null) {
                if (s.healFailed()) {
                    problems.add(Line.fail(r.name() + " is closed: " + orWhy(s.lastError(), "its course couldn't be checked"),
                            "a new one is built for this set; see /hcm games gen status " + r.id()));
                } else if (s.building()) {
                    state.add("being built");
                } else if (s.current()) {
                    state.add("live");
                } else if (s.lastError() != null) {
                    problems.add(Line.fail(r.name() + (s.live() ? " still shows the last set" : " has no course yet")
                                    + ": " + s.lastError(),
                            "it is tried again by itself; /hcm games gen status " + r.id() + " says when"));
                } else {
                    state.add(s.live() ? "the last set's course, the new one is on its way" : "no course yet, on its way");
                }
            }
        }
        if (problems.isEmpty()) {
            out.add(Line.ok(r.name() + ": area fits" + (state.isEmpty() ? "" : ", " + String.join(", ", state))));
        } else {
            out.addAll(problems);
        }
    }

    private static String orWhy(String why, String otherwise) {
        return why == null || why.isBlank() ? otherwise : why;
    }

    static void courses(List<Course> courses, List<Line> out) {
        if (courses == null || courses.isEmpty()) {
            out.add(Line.ok("No hand-built courses yet"));
            return;
        }
        for (Course c : courses) {
            String kind = c.golf() ? "Golf course" : "Course";
            List<String> problems = new ArrayList<>(c.problems() == null ? List.of() : c.problems());
            if (c.world() != null && !c.world().isBlank() && !c.worldLoaded()) {
                problems.add("its world '" + c.world() + "' isn't loaded");
            }
            if (problems.isEmpty()) {
                out.add(Line.ok(kind + " '" + c.id() + "' is ready" + (c.enabled() ? "" : " (switched off)")));
                continue;
            }
            String fix = "finish it with /hcm games " + (c.golf() ? "golf " : "course ") + c.id()
                    + (c.enabled() ? ", or switch it off" : "");
            out.add(c.enabled() ? Line.fail(kind + " '" + c.id() + "': " + String.join("; ", problems), fix)
                    : Line.warn(kind + " '" + c.id() + "' (switched off): " + String.join("; ", problems), fix));
        }
    }

    /** Race Night's rows (EVENTS-DROPPER-SPEC §A.11): nothing when it isn't there. */
    static void raceNight(RaceNight r, List<Line> out) {
        if (r == null) {
            return;
        }
        if (!r.enabled()) {
            out.add(Line.ok("Race Night is switched off (games.race_night.enabled) - nothing to check"));
            return;
        }
        for (RaceNightLine l : r.lines()) {
            out.add(l.fix() == null ? Line.ok(l.what()) : Line.warn(l.what(), l.fix()));
        }
    }

    static void web(Web w, List<Line> out) {
        if (w == null || !w.enabled()) {
            out.add(Line.ok("The website feed is off (web.dashboard.enabled: false) - nothing to check"));
            return;
        }
        out.add(w.tokenSet() ? Line.ok("A feed token is set")
                : Line.warn("web.dashboard.feed_token is empty, so anyone who can reach the port can read the feeds",
                "set a long random feed_token and give it to the website"));
        out.add(w.buildError() == null
                ? Line.ok("/api/arcade builds (" + w.games() + " game entr" + (w.games() == 1 ? "y" : "ies") + ", "
                + w.bytes() + " bytes)")
                : Line.fail("/api/arcade doesn't build: " + w.buildError(), "see the console, then /hcm reload"));
    }

    // ---- words --------------------------------------------------------------------------------------

    /** The lines to send: a header, every check, then "All good." or "N things to fix.". */
    public static List<String> render(List<Line> lines) {
        List<String> out = new ArrayList<>();
        out.add("&6Games check &7(it only looks, it changes nothing)");
        for (Line l : lines) {
            String tag = switch (l.status()) {
                case OK -> "&a[OK] &f";
                case WARN -> "&e[WARN] &f";
                case FAIL -> "&c[FAIL] &f";
            };
            out.add(tag + l.what() + (l.fix() == null || l.fix().isBlank() ? "" : " &7- " + l.fix()));
        }
        out.add(summary(lines));
        return out;
    }

    /** "&amp;aAll good." or "&amp;e3 things to fix." (every WARN and FAIL). */
    public static String summary(List<Line> lines) {
        long n = lines.stream().filter(l -> l.status() != Status.OK).count();
        return n == 0 ? "&aAll good." : "&e" + n + " thing" + (n == 1 ? "" : "s") + " to fix.";
    }

    // ---- Multiverse-Inventories' files (pure, tested) ----------------------------------------------

    /**
     * Multiverse-Inventories from its files: the groups from {@code groups.yml} (5.x) or its
     * {@code config.yml} (4.x), and the game-mode setting from {@code config.yml}
     * ({@code share-handling.enable-gamemode-share-handling} on 5.x, {@code use_game_mode_profiles}
     * on 4.x). Either may be {@code null} (unreadable).
     */
    public static MvInv readMvInv(ConfigurationSection groupsFile, ConfigurationSection configFile) {
        Map<String, List<String>> groups = groups(groupsFile);
        if (groups == null) {
            groups = groups(configFile);
        }
        Boolean share = null;
        if (configFile != null) {
            for (String key : List.of("share-handling.enable-gamemode-share-handling", "enable-gamemode-share-handling",
                    "settings.use_game_mode_profiles", "use_game_mode_profiles", "settings.use-game-mode-profiles")) {
                if (configFile.isBoolean(key)) {
                    share = configFile.getBoolean(key);
                    break;
                }
            }
        }
        return new MvInv(groups, share);
    }

    private static Map<String, List<String>> groups(ConfigurationSection file) {
        ConfigurationSection groups = file == null ? null : file.getConfigurationSection("groups");
        if (groups == null) {
            return null;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : groups.getKeys(false)) {
            ConfigurationSection g = groups.getConfigurationSection(name);
            out.put(name, g == null ? List.of() : g.getStringList("worlds"));
        }
        return out;
    }

    private static boolean contains(List<String> names, String name) {
        if (names == null || name == null) {
            return false;
        }
        for (String n : names) {
            if (n != null && n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
