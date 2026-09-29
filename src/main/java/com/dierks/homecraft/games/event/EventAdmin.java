package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * {@code /hcm games event ...} (EVENTS-DROPPER-SPEC §A.11): Race Night's admin commands. The
 * framework checks {@code hcm.games.admin} before calling in, and everything runs inside Race
 * Night's guard, so a bug here is answered, never thrown.
 *
 * <pre>
 * status                                   the night: state, times, racers, prize nights, the restart
 * list [days]                              the scheduled nights (7 days), each "fits" or skipped with why
 * start [course] [races N] [laps N] [in M] [fun]   a join window now (or in M minutes)
 * go                                       close the window early and start (needs min_racers)
 * cancel [confirm]                         call it off (confirm once racing: points so far count)
 * skip &lt;id|next&gt; / unskip &lt;id&gt;             skip one scheduled night
 * pause / resume                           stop or restart the schedule
 * results [id]                             a night's results (the last one)
 * grid &lt;course&gt; show|auto|add|remove &lt;n&gt;|clear   the starting grid (add: where you stand)
 * stand &lt;course&gt; set|clear                  the viewing stand (set: where you stand)
 * </pre>
 *
 * The rules each command checks are pure and tested here ({@link #parseStart}, {@link #startProblem},
 * {@link #cancelProblem}, {@link #complete}); the grid and stand rules are {@link RaceTrack}'s.
 */
final class EventAdmin implements GameAdmin {

    static final String CONFIRM = "confirm";
    /** The first words. */
    static final List<String> VERBS = List.of("status", "list", "start", "go", "cancel", "skip", "unskip", "pause",
            "resume", "results", "grid", "stand");
    static final List<String> GRID_VERBS = List.of("show", "auto", "add", "remove", "clear");
    static final List<String> STAND_VERBS = List.of("set", "clear");
    static final List<String> START_WORDS = List.of("races", "laps", "in", "fun");

    private final RaceNight game;

    EventAdmin(RaceNight game) {
        this.game = game;
    }

    @Override
    public String name() {
        return "event";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games event status|list [days] &7- Race Night now; the scheduled nights",
                "&e/hcm games event start [course] [races N] [laps N] [in M] [fun] &7- open a Race Night",
                "&e/hcm games event go|cancel [confirm] &7- start now; call it off",
                "&e/hcm games event skip <id|next>|unskip <id>|pause|resume &7- the schedule",
                "&e/hcm games event results [id] &7- a night's results",
                "&e/hcm games event grid <course> show|auto|add|remove <n>|clear &7- the starting grid",
                "&e/hcm games event stand <course> set|clear &7- the viewing stand");
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                help().forEach(l -> tell(sender, l));
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> status(sender);
                case "list" -> list(sender, args);
                case "start" -> start(sender, args);
                case "go" -> go(sender);
                case "cancel" -> cancel(sender, args);
                case "skip" -> skip(sender, args, true);
                case "unskip" -> skip(sender, args, false);
                case "pause", "resume" -> pause(sender, args[0].equalsIgnoreCase("pause"));
                case "results" -> results(sender, args);
                case "grid" -> grid(sender, args);
                case "stand" -> stand(sender, args);
                default -> tell(sender, "&cUnknown: " + args[0] + ". &7Try /hcm games event help.");
            }
        } catch (SQLException | RuntimeException e) {
            game.log(Level.SEVERE, "Race Night: /hcm games event " + String.join(" ", args) + " failed", e);
            tell(sender, "&cThat didn't work - see the console.");
        }
    }

    // ---- the pure rules (tested) ----------------------------------------------------------------

    /**
     * A parsed {@code start}: {@code [course] [races N] [laps N] [in M] [fun]}, in any order.
     *
     * @param error why it can't be read, or {@code null}
     */
    record Start(String course, Integer races, Integer laps, int inMinutes, boolean fun, String error) {

        static Start bad(String why) {
            return new Start(null, null, null, 0, false, why);
        }
    }

    /** Read the words after {@code start}. */
    static Start parseStart(List<String> words) {
        String course = null;
        Integer races = null;
        Integer laps = null;
        int in = 0;
        boolean fun = false;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            switch (w) {
                case "fun" -> fun = true;
                case "races", "laps", "in" -> {
                    Integer n = i + 1 < words.size() ? number(words.get(i + 1)) : null;
                    int lo = w.equals("laps") ? 0 : w.equals("in") ? 0 : 1;
                    int hi = w.equals("in") ? 720 : 5;
                    if (n == null || n < lo || n > hi) {
                        return Start.bad(w + " needs a number from " + lo + " to " + hi);
                    }
                    i++;
                    switch (w) {
                        case "races" -> races = n;
                        case "laps" -> laps = n;
                        default -> in = n;
                    }
                }
                default -> {
                    if (course != null || !w.matches("[a-z0-9_]+")) {
                        return Start.bad("start [course] [races N] [laps N] [in M] [fun] - I couldn't read \"" + w + "\"");
                    }
                    course = w;
                }
            }
        }
        return new Start(course, races, laps, in, fun, null);
    }

    /**
     * Why an admin night can't start, in the order an admin fixes things, or {@code null}.
     *
     * @param live       another night is pending or running
     * @param raceNight  {@code games.race_night.enabled}
     * @param trials     Time Trials is open
     * @param track      why the track can't be raced, or {@code null}
     * @param restart    why a restart would cut it off, or {@code null}
     * @param clash      why a scheduled night is too close, or {@code null}
     */
    static String startProblem(boolean live, boolean raceNight, boolean trials, String track, String restart,
                               String clash) {
        if (live) {
            return "Another Race Night is on - use cancel first.";
        }
        if (!raceNight) {
            return "Race Night is switched off (games.race_night.enabled).";
        }
        if (!trials) {
            return "Time Trials is closed, and Race Night races are Time Trials runs.";
        }
        if (track != null) {
            return "Can't race there: " + track + ".";
        }
        if (restart != null) {
            return restart;
        }
        return clash == null ? null : "Can't start now: " + clash + ".";
    }

    /** Why {@code cancel} needs more, or {@code null}: once racers are at the track it needs {@code confirm}. */
    static String cancelProblem(EventMachine.Phase phase, boolean confirm) {
        if (phase == null || phase.over()) {
            return "No Race Night is on.";
        }
        if (phase.running() && !confirm) {
            return "Racers are at the track - /hcm games event cancel confirm calls it off (points so far count).";
        }
        return null;
    }

    /**
     * Tab completions for the word being typed.
     *
     * @param courses the boat courses' ids
     * @param nights  the ids {@code skip}/{@code unskip}/{@code results} may name
     */
    static List<String> complete(String[] args, List<String> courses, List<String> nights) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        String typed = n == 0 ? "" : args[n - 1].toLowerCase(Locale.ROOT);
        if (n <= 1) {
            offer(out, typed, VERBS);
            return out;
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "start" -> {
                String before = args[n - 2].toLowerCase(Locale.ROOT);
                if (before.equals("races") || before.equals("laps")) {
                    offer(out, typed, List.of("1", "2", "3", "4", "5"));
                } else if (before.equals("in")) {
                    offer(out, typed, List.of("1", "5", "10", "30"));
                } else {
                    offer(out, typed, START_WORDS);
                    if (n == 2) {
                        offer(out, typed, courses);
                    }
                }
            }
            case "cancel" -> {
                if (n == 2) {
                    offer(out, typed, List.of(CONFIRM));
                }
            }
            case "skip", "unskip", "results" -> {
                if (n == 2) {
                    if (verb.equals("skip")) {
                        offer(out, typed, List.of("next"));
                    }
                    offer(out, typed, nights);
                }
            }
            case "list" -> {
                if (n == 2) {
                    offer(out, typed, List.of("7", "14", "30"));
                }
            }
            case "grid", "stand" -> {
                if (n == 2) {
                    offer(out, typed, courses);
                } else if (n == 3) {
                    offer(out, typed, verb.equals("grid") ? GRID_VERBS : STAND_VERBS);
                } else if (n == 4 && verb.equals("grid") && args[2].equalsIgnoreCase("remove")) {
                    offer(out, typed, List.of("1", "2", "3", "4", "5", "6", "7", "8"));
                }
            }
            default -> {
                // nothing more to offer
            }
        }
        return out;
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> courses = new ArrayList<>();
        TimeTrials t = game.trials();
        if (t != null) {
            for (Course c : t.openCourses()) {
                if (c.kind() == TrialKind.BOAT) {
                    courses.add(c.id());
                }
            }
        }
        List<String> nights = new ArrayList<>();
        for (EventSchedule.Occurrence o : game.upcoming(7)) {
            nights.add(o.id());
        }
        nights.addAll(game.skipped());
        for (EventDao.EventRow r : game.recent(5)) {
            nights.add(r.id());
        }
        return complete(args, courses, nights);
    }

    // ---- the commands -----------------------------------------------------------------------------

    private void status(CommandSender sender) {
        NightRunner n = game.night();
        RaceNightSettings s = game.settings();
        tell(sender, "&6Race Night &7- " + (game.games().enabled(game) ? "&aopen" : "&cclosed")
                + (game.paused() ? " &7(schedule paused)" : ""));
        if (n == null) {
            EventSchedule.Occurrence o = game.next();
            tell(sender, "&7No night on now. Next: " + (o == null ? "none set" : "&f" + o.id() + " &7at "
                    + EventCopy.when(o.startsAt(), game.zone()) + " on " + game.nextTrackName(o)));
        } else {
            tell(sender, "&f" + n.plan().id() + " &7on &f" + n.track().name() + " &7- " + n.phase().name().toLowerCase(
                    Locale.ROOT) + ", race " + Math.max(0, n.race()) + " of " + n.plan().races() + ", "
                    + n.laps() + (n.laps() == 1 ? " lap" : " laps") + (n.plan().rules().fun() ? " (fun)" : ""));
            tell(sender, "&7Joining " + EventCopy.clock(n.plan().joinAt(), game.zone()) + ", start "
                    + EventCopy.clock(n.startsAt(), game.zone()) + ", " + n.joined().size() + " of " + n.maxRacers()
                    + " in, " + (n.started() >= 0 ? n.started() + " started race 1, " : "")
                    + (n.prizeNight() ? "a prize night" : "just for fun"));
            for (NightStandings.Ranked r : n.standings()) {
                NightRunner.Racer racer = n.racer(r.player());
                tell(sender, "&7  " + r.place() + ". &f" + (racer == null ? r.player() : racer.name()) + " &7"
                        + EventCopy.points(r.points()) + (racer == null ? "" : " &8(" + racer.leg().name()
                        .toLowerCase(Locale.ROOT) + (racer.seated() ? ", at the track" : "") + ")"));
            }
            tell(sender, "&7Race tick: " + game.tickStats());
        }
        tell(sender, "&7Prize nights this week: &f" + game.prizedThisWeek() + "/" + s.prizeEventsPerWeek());
        RestartHold hold = game.games().restartHold();
        tell(sender, "&7" + hold.status(game.now()));
    }

    private void list(CommandSender sender, String[] args) {
        int days = 7;
        if (args.length >= 2) {
            Integer d = number(args[1]);
            if (d == null || d < 1 || d > 60) {
                tell(sender, "&cDays is a number from 1 to 60.");
                return;
            }
            days = d;
        }
        List<EventSchedule.Occurrence> list = game.upcoming(days);
        if (game.settings().schedule().isEmpty()) {
            tell(sender, "&7No schedule set (games.race_night.schedule is []): nights only when an admin starts one.");
            return;
        }
        if (list.isEmpty()) {
            tell(sender, "&7No scheduled nights in the next " + days + " days.");
        }
        for (EventSchedule.Occurrence o : list) {
            tell(sender, (o.fits() ? "&a" : "&7") + o.id() + " &7" + EventCopy.when(o.startsAt(), game.zone()) + " - "
                    + (o.fits() ? "&afits" : "&cskipped: " + o.skip()));
        }
        if (game.paused()) {
            tell(sender, "&eThe schedule is paused - /hcm games event resume.");
        }
    }

    private void start(CommandSender sender, String[] args) {
        Start st = parseStart(List.of(args).subList(1, args.length));
        if (st.error() != null) {
            tell(sender, "&c" + st.error());
            return;
        }
        String by = sender instanceof Player p ? p.getName() : "console";
        String why = game.adminStart(by, st.course(), st.races(), st.laps(), st.inMinutes(), st.fun());
        if (why != null) {
            tell(sender, "&c" + why);
            return;
        }
        NightRunner n = game.night();
        tell(sender, "&aRace Night " + n.plan().id() + " is set: &f" + n.track().name() + "&a, joining "
                + (st.inMinutes() > 0 ? "at " + EventCopy.clock(n.plan().joinAt(), game.zone()) : "now")
                + ", start at " + EventCopy.clock(n.startsAt(), game.zone()) + (st.fun() ? " (just for fun)" : "") + ".");
        game.log(Level.INFO, "Race Night " + n.plan().id() + " started by " + by, null);
    }

    private void go(CommandSender sender) {
        NightRunner n = game.night();
        if (n == null) {
            tell(sender, "&cNo Race Night is on.");
            return;
        }
        String why = n.goNow();
        tell(sender, why == null ? "&aRace Night starts in 15 seconds." : "&c" + why);
    }

    private void cancel(CommandSender sender, String[] args) {
        NightRunner n = game.night();
        boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase(CONFIRM);
        String why = cancelProblem(n == null ? null : n.phase(), confirm);
        if (why != null) {
            tell(sender, "&c" + why);
            return;
        }
        n.callOff("&7Race Night was called off by an admin" + (n.racesDone() > 0 ? " - points so far count." : "."),
                "an admin called it off");
        tell(sender, "&aRace Night " + n.plan().id() + " is called off.");
    }

    private void skip(CommandSender sender, String[] args, boolean skip) throws SQLException {
        if (args.length < 2) {
            tell(sender, skip ? "&cskip <id|next>" : "&cunskip <id>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (skip && id.equals("next")) {
            EventSchedule.Occurrence o = game.next();
            if (o == null) {
                tell(sender, "&7There's no scheduled night to skip.");
                return;
            }
            id = o.id();
        }
        if (!EventPlan.isId(id) || EventPlan.adminId(id)) {
            tell(sender, "&cThat isn't a scheduled night's id (like rn-20261002-1900). &7See /hcm games event list.");
            return;
        }
        NightRunner n = game.night();
        if (skip && n != null && n.plan().id().equals(id)) {
            tell(sender, "&cThat night is already on - use cancel.");
            return;
        }
        game.dao().setMeta(RaceNight.SKIP + id, skip ? "1" : null);
        game.changed();
        tell(sender, skip ? "&a" + id + " is skipped." : "&a" + id + " is back on the schedule (if it still fits).");
    }

    private void pause(CommandSender sender, boolean on) throws SQLException {
        game.pause(on);
        tell(sender, on ? "&aThe Race Night schedule is paused. &7A night already on carries on; admins can still start one."
                : "&aThe Race Night schedule is back on.");
    }

    private void results(CommandSender sender, String[] args) {
        String id;
        if (args.length >= 2) {
            id = args[1].toLowerCase(Locale.ROOT);
        } else {
            List<EventDao.EventRow> r = game.recent(1);
            if (r.isEmpty()) {
                tell(sender, "&7No Race Night has finished yet.");
                return;
            }
            id = r.get(0).id();
        }
        EventDao.EventRow row;
        try {
            row = game.dao().event(id);
        } catch (SQLException e) {
            row = null;
        }
        if (row == null) {
            tell(sender, "&cNo Race Night called " + id + ".");
            return;
        }
        tell(sender, "&6" + row.id() + " &7on " + game.courseName(row.course()) + " - " + row.state().toLowerCase(Locale.ROOT)
                + ", " + row.racesDone() + " race(s)" + (row.prized() ? ", a prize night" : ", just for fun")
                + (row.note() == null || row.note().isBlank() ? "" : " (" + row.note() + ")"));
        List<EventDao.EntryRow> entries = new ArrayList<>(game.entries(id));
        entries.sort((a, b) -> Integer.compare(a.place() == null ? 999 : a.place(), b.place() == null ? 999 : b.place()));
        for (EventDao.EntryRow e : entries) {
            tell(sender, "&7  " + (e.place() == null ? "-" : e.place() + ".") + " &f" + e.name() + " &7"
                    + EventCopy.points(e.points()) + (e.prize() > 0 ? ", " + EventCopy.tokens(e.prize())
                    + (e.paidAt() == null ? " (owed)" : "") : "") + (EventDao.LEFT.equals(e.status()) ? " (left)" : ""));
        }
    }

    // ---- the grid and the stand -------------------------------------------------------------------

    private Course boatCourse(CommandSender sender, String id) {
        TimeTrials t = game.trials();
        Course c = t == null ? null : t.course(id);
        if (c == null) {
            tell(sender, "&cNo course called " + id + ".");
            return null;
        }
        if (c.kind() != TrialKind.BOAT) {
            tell(sender, "&c" + c.name() + " isn't a boat course.");
            return null;
        }
        return c;
    }

    private void grid(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            tell(sender, "&cgrid <course> show|auto|add|remove <n>|clear");
            return;
        }
        Course c = boatCourse(sender, args[1].toLowerCase(Locale.ROOT));
        if (c == null) {
            return;
        }
        String verb = args[2].toLowerCase(Locale.ROOT);
        String key = Tracks.GRID + c.id();
        if (c.generated() && !verb.equals("show") && !verb.equals("auto")) {
            tell(sender, "&c" + c.name() + " is a Fresh course: it changes, so it always uses the automatic grid.");
            return;
        }
        List<Course.Spot> stored = c.generated() ? null : game.tracks().storedGrid(c);
        switch (verb) {
            case "show" -> {
                List<String> why = new ArrayList<>();
                List<Course.Spot> grid = game.tracks().grid(c, RaceTrack.MAX_GRID, why);
                tell(sender, "&6" + c.name() + " &7grid (" + (stored != null && !stored.isEmpty() ? "set by an admin"
                        : "automatic") + "): " + grid.size() + " spot(s)");
                showSpots(sender, grid);
                why.forEach(w -> tell(sender, "&7  " + w));
            }
            case "auto" -> {
                if (stored != null) {
                    game.dao().setMeta(key, null);
                }
                List<String> why = new ArrayList<>();
                List<Course.Spot> grid = game.tracks().grid(c, RaceTrack.MAX_GRID, why);
                tell(sender, (grid.size() >= game.settings().minRacers() ? "&a" : "&c") + "The automatic grid seats "
                        + grid.size() + " on " + c.name() + ".");
                showSpots(sender, grid);
                why.forEach(w -> tell(sender, "&7  " + w));
            }
            case "add" -> {
                if (!(sender instanceof Player p)) {
                    tell(sender, "&cStand where the spot goes, in game.");
                    return;
                }
                Location l = p.getLocation();
                if (l.getWorld() == null || !l.getWorld().getName().equalsIgnoreCase(c.world())) {
                    tell(sender, "&cStand on the track, in " + c.world() + ".");
                    return;
                }
                List<Course.Spot> grid = stored == null ? new ArrayList<>() : new ArrayList<>(stored);
                Course.Spot spot = new Course.Spot(l.getX(), l.getY(), l.getZ(), c.start() == null ? l.getYaw()
                        : c.start().yaw(), 0);
                String why = RaceTrack.gridProblem(c, grid, spot);
                if (why != null) {
                    tell(sender, "&c" + why + ".");
                    return;
                }
                grid.add(spot);
                game.dao().setMeta(key, RaceTrack.encodeGrid(c.layoutHash(), grid));
                tell(sender, "&aGrid spot " + grid.size() + " set on " + c.name() + ".");
                game.log(Level.INFO, "Race Night: grid spot " + grid.size() + " of " + c.id() + " set by "
                        + sender.getName(), null);
            }
            case "remove" -> {
                Integer n = args.length >= 4 ? number(args[3]) : null;
                if (stored == null || n == null || n < 1 || n > stored.size()) {
                    tell(sender, "&cremove <n>: a spot of the admin grid (it has " + (stored == null ? 0 : stored.size())
                            + ").");
                    return;
                }
                List<Course.Spot> grid = new ArrayList<>(stored);
                grid.remove(n - 1);
                game.dao().setMeta(key, grid.isEmpty() ? null : RaceTrack.encodeGrid(c.layoutHash(), grid));
                tell(sender, "&aGrid spot " + n + " removed.");
            }
            case "clear" -> {
                game.dao().setMeta(key, null);
                tell(sender, "&aThe admin grid of " + c.name() + " is cleared: the automatic grid applies.");
            }
            default -> tell(sender, "&cgrid <course> show|auto|add|remove <n>|clear");
        }
    }

    private void showSpots(CommandSender sender, List<Course.Spot> grid) {
        for (int i = 0; i < grid.size(); i++) {
            Course.Spot s = grid.get(i);
            tell(sender, "&7  " + (i + 1) + ". " + Math.round(s.x() * 10) / 10.0 + ", " + Math.round(s.y() * 10) / 10.0
                    + ", " + Math.round(s.z() * 10) / 10.0);
        }
    }

    private void stand(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            tell(sender, "&cstand <course> set|clear");
            return;
        }
        Course c = boatCourse(sender, args[1].toLowerCase(Locale.ROOT));
        if (c == null) {
            return;
        }
        if (c.generated()) {
            tell(sender, "&c" + c.name() + " is a Fresh course: its stand is built in.");
            return;
        }
        String key = Tracks.STAND + c.id();
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (!(sender instanceof Player p)) {
                    tell(sender, "&cStand where the stand goes, in game.");
                    return;
                }
                Location l = p.getLocation();
                Point at = new Point(l.getX(), l.getY(), l.getZ());
                String why = game.tracks().standProblem(c, l.getWorld() == null ? null : l.getWorld().getName(), at);
                if (why != null) {
                    tell(sender, "&c" + why + ".");
                    return;
                }
                game.dao().setMeta(key, RaceTrack.encodeStand(c.layoutHash(), at));
                tell(sender, "&aThe viewing stand of " + c.name() + " is set: nights there can have more than one race.");
                game.log(Level.INFO, "Race Night: the stand of " + c.id() + " set by " + sender.getName(), null);
            }
            case "clear" -> {
                game.dao().setMeta(key, null);
                tell(sender, "&aThe stand of " + c.name() + " is cleared: nights there have one race.");
            }
            default -> tell(sender, "&cstand <course> set|clear");
        }
    }

    // ---- words ------------------------------------------------------------------------------------

    private static Integer number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static void offer(List<String> out, String typed, List<String> options) {
        for (String o : options) {
            if (o != null && o.toLowerCase(Locale.ROOT).startsWith(typed) && !out.contains(o)) {
                out.add(o);
            }
        }
    }

    private static void tell(CommandSender sender, String line) {
        sender.sendMessage(Text.of(line));
    }
}
