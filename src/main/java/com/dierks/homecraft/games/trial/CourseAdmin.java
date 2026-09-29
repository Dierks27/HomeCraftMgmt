package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * {@code /hcm games course …}: building time-trial courses by standing in the world (spec §11, §9).
 * The framework has already checked {@code hcm.games.admin}; {@code args} are the words after
 * {@code course}.
 * <ul>
 *   <li>{@code list}; {@code create <id> <parkour|elytra|boat> [tier]};</li>
 *   <li>{@code <id> start} (here, facing the way you look), {@code <id> checkpoint add [radius] |
 *       remove <n> | list}, {@code <id> finish [radius]}, {@code <id> fall <y|off>} — the layout;</li>
 *   <li>{@code <id> tier <t>}, {@code <id> name <words…>}, {@code <id> minseconds <n|default>};</li>
 *   <li>{@code <id> enable|disable}, {@code <id> info}, {@code <id> tp}, {@code <id> test},
 *       {@code <id> feature [off]} (pin as the course of the week), {@code <id> delete confirm}.</li>
 * </ul>
 *
 * <p><b>The layout is guarded.</b> Every point must be in one world, and that world must be in
 * {@code games.worlds}. A layout change bumps the course's {@code rev} and clears its all-time and
 * this week's times — a time on the old layout isn't a time on the new one — so when there are
 * times to lose it asks for the same command again with {@code confirm} on the end (R2.15). A
 * fall height must sit under the lowest point of the course and not under the world's floor
 * (else every run goes straight back, or none ever falls). A course opens only with a start and a
 * finish. Every change is logged with who made it, and a command that goes wrong says so here and
 * in the console — it never reaches the framework's guard, which would switch the game off.
 *
 * <p><b>Fresh Courses keeps its own</b> (GEN-SPEC §2.4, §5.5). A course Fresh Courses made is
 * rebuilt every day from its seed, so here it can only be looked at, gone to, tried and featured
 * ({@link #DAILY_VERBS}); anything else points to {@code /hcm games gen}. And no point of a
 * hand-built course may be placed inside a Fresh Courses area or within {@value
 * DailyLookup#EDITOR_MARGIN} blocks of one: that ground is rebuilt every day.
 */
final class CourseAdmin implements GameAdmin {

    /** What can follow a course id. */
    static final List<String> VERBS = List.of("start", "checkpoint", "finish", "tier", "name", "fall", "minseconds",
            "enable", "disable", "info", "tp", "test", "feature", "delete");
    /** What can follow a course id when Fresh Courses made the course: looking, trying, featuring. */
    static final List<String> DAILY_VERBS = List.of("info", "tp", "test", "feature");
    /** Why {@code create <id> dropper} is refused (EVENTS-DROPPER-SPEC §B.1.2). */
    static final String HAND_MADE_DROPPER = "Droppers are made by Fresh Courses; keep one to make it permanent.";

    private final TimeTrials trials;

    CourseAdmin(TimeTrials trials) {
        this.trials = trials;
    }

    @Override
    public String name() {
        return "course";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games course list &7- every time-trial course",
                "&e/hcm games course create <id> <parkour|elytra|boat> [tier] &7- a new course",
                "&e/hcm games course <id> start &7- it starts here, facing the way you look",
                "&e/hcm games course <id> checkpoint add [radius]|remove <n>|list &7- checkpoints, in order",
                "&e/hcm games course <id> finish [radius] &7- it ends here",
                "&e/hcm games course <id> tier|name|fall|minseconds ... &7- tier, name, fall height (y|off), shortest time",
                "&e/hcm games course <id> enable|disable|info|tp|test &7- open it, check it, go there, try it",
                "&e/hcm games course <id> feature [off] &7- pin it as the course of the week",
                "&e/hcm games course <id> delete confirm &7- delete it and its times");
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                for (String line : help()) {
                    sender.sendMessage(Text.of(line));
                }
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list" -> list(sender);
                case "create" -> create(sender, args);
                default -> course(sender, args);
            }
        } catch (SQLException e) {
            trials.plugin().getLogger().log(Level.SEVERE, "Time trials: a course command failed", e);
            sender.sendMessage(Text.of("&cCouldn't reach the database - see the console."));
        } catch (RuntimeException e) {
            // Never out to the framework's guard: that would switch time trials off over a typo.
            trials.plugin().getLogger().log(Level.SEVERE, "Time trials: /hcm games course "
                    + String.join(" ", args) + " failed", e);
            sender.sendMessage(Text.of("&cThat didn't work - see the console."));
        }
    }

    // ---- list and create ----------------------------------------------------------------------

    private void list(CommandSender sender) throws SQLException {
        List<Course> all = trials.store().load();
        if (all.isEmpty()) {
            sender.sendMessage(Text.of("&7No courses yet. &e/hcm games course create <id> <parkour|elytra|boat> [tier]"));
            return;
        }
        String week = trials.courseOfWeek();
        sender.sendMessage(Text.of("&6Time-trial courses &7(" + all.size() + ")"));
        for (Course c : all) {
            sender.sendMessage(Text.of("&f" + c.id() + " &7- " + c.name() + " (" + TrialText.label(c) + "), "
                    + (c.enabled() ? "&aopen" : "&cclosed") + "&7, " + TrialText.checkpoints(c.checkpoints().size())
                    + ", layout " + c.rev() + (c.id().equals(week) ? " &6★ course of the week" : "")
                    + (c.pinned() ? " &8(pinned)" : "") + (c.generated() ? " &d(Fresh Courses)" : "")));
        }
    }

    private void create(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm games course create <id> <parkour|elytra|boat> [tier]"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String clash = trials.clash(id);
        if (clash != null) {
            sender.sendMessage(Text.of("&c" + clash));
            return;
        }
        TrialKind kind = TrialKind.of(args[2]);
        if (kind == null) {
            sender.sendMessage(Text.of("&cA course is parkour, elytra or boat."));
            return;
        }
        if (!kind.handMade()) {
            sender.sendMessage(Text.of("&c" + HAND_MADE_DROPPER));
            return;
        }
        Tier tier = args.length >= 4 ? Tier.of(args[3]) : Tier.EASY;
        if (tier == null) {
            sender.sendMessage(Text.of("&cThe tiers are " + String.join(", ", Tier.ids()) + "."));
            return;
        }
        Course c = trials.store().save(Course.create(id, kind, tier), false, trials.weekKey(), now());
        if (c == null) {
            sender.sendMessage(Text.of("&cThere's already a course called '" + id + "'."));
            return;
        }
        trials.forget();
        changed(sender, args);
        sender.sendMessage(Text.of("&aMade &f" + id + " &a(" + TrialText.label(c) + "). &7Next: stand where it starts, "
                + "face the way it goes and type &e/hcm games course " + id + " start"));
    }

    // ---- one course ---------------------------------------------------------------------------

    private void course(CommandSender sender, String[] args) throws SQLException {
        String id = args[0].toLowerCase(Locale.ROOT);
        Course c = trials.store().get(id);
        if (c == null) {
            sender.sendMessage(Text.of("&cNo course called '" + args[0] + "'. &7/hcm games course list"));
            return;
        }
        if (args.length < 2) {
            info(sender, c);
            return;
        }
        String daily = dailyRefusal(c, args[1]);
        if (daily == null) {
            daily = DropperLayout.editRefusal(c, args[1]); // a kept dropper's layout is the proven one
        }
        if (daily != null) {
            sender.sendMessage(Text.of(daily));
            return;
        }
        boolean confirm = args[args.length - 1].equalsIgnoreCase("confirm");
        List<String> rest = rest(args, confirm);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender, c, confirm, args);
            case "checkpoint", "checkpoints", "cp" -> checkpoint(sender, c, rest, confirm, args);
            case "finish" -> finish(sender, c, rest, confirm, args);
            case "fall" -> fall(sender, c, rest, confirm, args);
            case "tier" -> tier(sender, c, rest, args);
            case "name" -> name(sender, c, rest, args);
            case "minseconds" -> minSeconds(sender, c, rest, args);
            case "enable" -> enable(sender, c, args);
            case "disable" -> {
                save(sender, c.withEnabled(false), args);
                sender.sendMessage(Text.of("&a" + c.name() + " is closed. &7Runs already going finish as normal."));
                CupLink.courseChanged(trials.games(), sender, c.id(), c.name(), CupPlan.VoidReason.CLOSED); // Weekly Cup
            }
            case "info" -> info(sender, c);
            case "tp" -> tp(sender, c);
            case "test" -> test(sender, c);
            case "feature" -> feature(sender, c, rest, args);
            case "delete" -> delete(sender, c, confirm, args);
            default -> sender.sendMessage(Text.of("&cUnknown: &f" + args[1] + "&c. &7Try one of: "
                    + String.join(", ", VERBS)));
        }
    }

    private void start(CommandSender sender, Course c, boolean confirm, String[] args) throws SQLException {
        Location here = here(sender, c);
        if (here == null) {
            return;
        }
        Course after = c.withWorld(here.getWorld().getName()).withStart(new Course.Spot(here.getX(), here.getY(),
                here.getZ(), here.getYaw(), here.getPitch()));
        layout(sender, c, after, confirm, args, "Start set here, facing the way you look.");
    }

    private void checkpoint(CommandSender sender, Course c, List<String> rest, boolean confirm, String[] args)
            throws SQLException {
        String what = rest.isEmpty() ? "list" : rest.get(0).toLowerCase(Locale.ROOT);
        switch (what) {
            case "add" -> {
                if (c.checkpoints().size() >= Course.MAX_CHECKPOINTS) {
                    sender.sendMessage(Text.of("&cA course has at most " + Course.MAX_CHECKPOINTS + " checkpoints."));
                    return;
                }
                double r = radius(rest.size() > 1 ? rest.get(1) : null, c.kind().defaultRadius());
                if (Double.isNaN(r)) {
                    sender.sendMessage(Text.of("&cA radius is a number of blocks, like 3 or 2.5."));
                    return;
                }
                Location here = here(sender, c, r);
                if (here == null) {
                    return;
                }
                Course after = c.withWorld(here.getWorld().getName()).plusCheckpoint(mark(here, r));
                layout(sender, c, after, confirm, args, "Checkpoint " + after.checkpoints().size() + " added here "
                        + "(radius " + fmt(r) + ").");
            }
            case "remove" -> {
                int n = rest.size() > 1 ? whole(rest.get(1)) : -1;
                if (n < 1 || n > c.checkpoints().size()) {
                    sender.sendMessage(Text.of("&cWhich one? 1 to " + c.checkpoints().size()
                            + " &7(/hcm games course " + c.id() + " checkpoint list)"));
                    return;
                }
                layout(sender, c, c.minusCheckpoint(n), confirm, args, "Checkpoint " + n + " removed; the ones after "
                        + "it moved up one.");
            }
            case "list" -> {
                if (c.checkpoints().isEmpty()) {
                    sender.sendMessage(Text.of("&7" + c.name() + " has no checkpoints yet."));
                    return;
                }
                sender.sendMessage(Text.of("&6" + c.name() + " &7- checkpoints, in order:"));
                for (int i = 0; i < c.checkpoints().size(); i++) {
                    Course.Mark m = c.checkpoints().get(i);
                    sender.sendMessage(Text.of("&7" + (i + 1) + ". &f" + where(m) + " &7radius " + fmt(m.radius())));
                }
            }
            default -> sender.sendMessage(Text.of("&cUsage: /hcm games course " + c.id()
                    + " checkpoint add [radius]|remove <n>|list"));
        }
    }

    private void finish(CommandSender sender, Course c, List<String> rest, boolean confirm, String[] args)
            throws SQLException {
        double r = radius(rest.isEmpty() ? null : rest.get(0), c.kind().defaultRadius());
        if (Double.isNaN(r)) {
            sender.sendMessage(Text.of("&cA radius is a number of blocks, like 3 or 2.5."));
            return;
        }
        Location here = here(sender, c, r);
        if (here == null) {
            return;
        }
        layout(sender, c, c.withWorld(here.getWorld().getName()).withFinish(mark(here, r)), confirm, args,
                "Finish set here (radius " + fmt(r) + ").");
    }

    private void fall(CommandSender sender, Course c, List<String> rest, boolean confirm, String[] args)
            throws SQLException {
        if (rest.isEmpty()) {
            sender.sendMessage(Text.of("&cUsage: /hcm games course " + c.id() + " fall <y|off>"));
            return;
        }
        String v = rest.get(0).toLowerCase(Locale.ROOT);
        Double y;
        if (v.equals("off") || v.equals("default")) {
            y = null;
        } else {
            y = number(v);
            if (y == null) {
                sender.sendMessage(Text.of("&cA fall height is a y level, like 60, or off."));
                return;
            }
        }
        layout(sender, c, c.withFallY(y), confirm, args, y == null
                ? (c.kind() == TrialKind.PARKOUR ? "Falls now count at " + trials.settings().fallDepth()
                + " blocks under the checkpoints either side." : "No fall height: runs go back on landing or leaving.")
                : "Falling below y " + fmt(y) + " sends a run back to its last checkpoint.");
    }

    /** The world's floor under a course, or {@code null} while it has no world (or it isn't loaded). */
    private static Integer minHeight(Course c) {
        World w = c.world().isBlank() ? null : Bukkit.getWorld(c.world());
        return w == null ? null : w.getMinHeight();
    }

    private void tier(CommandSender sender, Course c, List<String> rest, String[] args) throws SQLException {
        Tier t = rest.isEmpty() ? null : Tier.of(rest.get(0));
        if (t == null) {
            sender.sendMessage(Text.of("&cThe tiers are " + String.join(", ", Tier.ids()) + "."));
            return;
        }
        save(sender, c.withTier(t), args);
        sender.sendMessage(Text.of("&a" + c.name() + " is now " + t.label() + "."
                + " &7(A first clear already paid isn't paid again.)"));
    }

    private void name(CommandSender sender, Course c, List<String> rest, String[] args) throws SQLException {
        String n = TrialText.cleanName(String.join(" ", rest));
        if (n == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm games course " + c.id() + " name <words...>"));
            return;
        }
        save(sender, c.withName(n), args);
        sender.sendMessage(Text.of("&aRenamed to &f" + n + "&a."));
    }

    private void minSeconds(CommandSender sender, Course c, List<String> rest, String[] args) throws SQLException {
        if (rest.isEmpty()) {
            sender.sendMessage(Text.of("&cUsage: /hcm games course " + c.id() + " minseconds <n|default>"));
            return;
        }
        Integer s;
        if (rest.get(0).equalsIgnoreCase("default") || rest.get(0).equalsIgnoreCase("off")) {
            s = null;
        } else {
            int n = whole(rest.get(0));
            if (n < 0 || n > 3600) {
                sender.sendMessage(Text.of("&cA shortest time is 0 to 3600 seconds, or default."));
                return;
            }
            s = n;
        }
        save(sender, c.withMinSeconds(s), args);
        sender.sendMessage(Text.of(s == null
                ? "&aA run quicker than the server's " + trials.settings().minSeconds() + " seconds won't count."
                : "&aA run quicker than " + s + " seconds won't count."));
    }

    private void enable(CommandSender sender, Course c, String[] args) throws SQLException {
        List<String> problems = c.problems(trials.games().config().common().worlds());
        if (!problems.isEmpty()) {
            sender.sendMessage(Text.of("&c" + c.name() + " can't open yet: &7" + String.join("; ", problems)));
            return;
        }
        save(sender, c.withEnabled(true), args);
        sender.sendMessage(Text.of("&a" + c.name() + " is open: &f/hcm play " + c.id()
                + (trials.games().enabled(trials) ? "" : " &7(once time trials are on)")));
    }

    private void info(CommandSender sender, Course c) throws SQLException {
        sender.sendMessage(Text.of("&6" + c.name() + " &7(" + c.id() + ") - " + TrialText.label(c) + ", "
                + (c.enabled() ? "&aopen" : "&cclosed") + "&7, layout " + c.rev()));
        sender.sendMessage(Text.of("&7World: &f" + (c.world().isBlank() ? "not set" : c.world())
                + " &7Start: &f" + (c.start() == null ? "not set" : where(c.start()))));
        sender.sendMessage(Text.of("&7Checkpoints: &f" + c.checkpoints().size() + " &7Finish: &f"
                + (c.finish() == null ? "not set" : where(c.finish()) + " &7radius " + fmt(c.finish().radius()))));
        String fall = c.fallY() != null ? "below y " + fmt(c.fallY())
                : c.kind() == TrialKind.PARKOUR ? trials.settings().fallDepth() + " under the checkpoints (default)"
                : "none (landing / leaving the boat)";
        sender.sendMessage(Text.of("&7Falls: &f" + fall + " &7Shortest time: &f"
                + c.minSecondsOr(trials.settings().minSeconds()) + "s" + (c.minSeconds() == null ? " (default)" : "")));
        GamesDao.ScoreRow record = trials.record(c.id());
        String week = trials.courseOfWeek();
        sender.sendMessage(Text.of((c.generated() ? "&7Today's best: &f" : "&7Record: &f") + (record == null ? "none"
                : TrialText.time(record.score()) + " by " + trials.holder(record.player()))
                + (c.id().equals(week) ? " &6★ course of the week" : "") + (c.pinned() ? " &8(pinned)" : "")));
        GenTag gen = c.gen();
        if (gen != null) {
            sender.sendMessage(Text.of("&dMade by Fresh Courses: &7the layout for "
                    + DailyText.setName(gen.cadence(), gen.day())
                    + (gen.reroll() > 0 ? " (reroll " + gen.reroll() + ")" : "") + ", half " + gen.half()
                    + (trials.generated().live(c.id(), gen) ? ", &aopen" : ", &cclosed right now")
                    + " &7- &e/hcm games gen status"));
            return;
        }
        List<String> problems = c.problems(trials.games().config().common().worlds());
        if (!problems.isEmpty()) {
            sender.sendMessage(Text.of("&eTo open it: &7" + String.join("; ", problems)));
        }
    }

    private void tp(CommandSender sender, Course c) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly a player can go there."));
            return;
        }
        if (trials.games().sessions().session(p) != null) {
            sender.sendMessage(Text.of("&cLeave your game first &7(/hcm leave)."));
            return;
        }
        World w = c.world().isBlank() ? null : Bukkit.getWorld(c.world());
        if (w == null || c.start() == null) {
            sender.sendMessage(Text.of("&c" + c.name() + " has no start yet."));
            return;
        }
        Course.Spot s = c.start();
        p.teleport(new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch()));
        sender.sendMessage(Text.of("&aAt the start of &f" + c.name() + "&a."));
    }

    private void test(CommandSender sender, Course c) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly a player can run a course."));
            return;
        }
        trials.startTest(p, c);
    }

    private void feature(CommandSender sender, Course c, List<String> rest, String[] args) throws SQLException {
        boolean off = !rest.isEmpty() && rest.get(0).equalsIgnoreCase("off");
        if (off) {
            save(sender, c.withPinned(false), args);
            sender.sendMessage(Text.of("&aUnpinned. &7The week picks its own course again."));
            return;
        }
        for (Course other : trials.store().load()) {
            if (other.pinned() && !other.id().equals(c.id())) {
                trials.store().save(other.withPinned(false), false, trials.weekKey(), now());
            }
        }
        save(sender, c.withPinned(true), args);
        sender.sendMessage(Text.of("&a" + c.name() + " is the course of the week"
                + (c.enabled() ? "." : " &7(once it's open).") + " &7Unpin: /hcm games course " + c.id() + " feature off"));
    }

    private void delete(CommandSender sender, Course c, boolean confirm, String[] args) throws SQLException {
        if (!confirm) {
            sender.sendMessage(Text.of("&eThis deletes " + c.name() + " and all its times. &7Type &f/hcm games course "
                    + c.id() + " delete confirm"));
            cupWarning(sender, c); // Weekly Cup
            return;
        }
        trials.store().delete(c.id());
        trials.forget();
        changed(sender, args);
        sender.sendMessage(Text.of("&aDeleted &f" + c.name() + " &aand its times."));
        CupLink.courseChanged(trials.games(), sender, c.id(), c.name(), CupPlan.VoidReason.DELETED); // Weekly Cup
    }

    // ---- saving -------------------------------------------------------------------------------

    /**
     * A layout change: asks for {@code confirm} when it would clear times, then bumps {@code rev}
     * and clears the course's all-time and this week's boards.
     */
    private void layout(CommandSender sender, Course before, Course after, boolean confirm, String[] args, String done)
            throws SQLException {
        String fall = fallProblem(after, minHeight(after));
        if (fall != null) {
            sender.sendMessage(Text.of("&c" + fall + " &7(/hcm games course " + before.id() + " fall <y|off>)"));
            return;
        }
        long week = trials.weekKey();
        boolean times = trials.store().hasTimes(before.id(), week);
        boolean cup = CupLink.entrants(trials.games(), before.id()) > 0; // Weekly Cup: it is called off
        if ((times || cup) && !confirm) {
            sender.sendMessage(Text.of("&eThat changes the layout of " + before.name() + ", so its times are cleared "
                    + "(all-time and this week). &7Type it again with &fconfirm &7on the end: &e/hcm games course "
                    + String.join(" ", args) + " confirm"));
            cupWarning(sender, before);
            return;
        }
        Course saved = trials.store().save(after, true, week, now());
        if (saved == null) {
            sender.sendMessage(Text.of("&cAnother game has a course called '" + before.id() + "'."));
            return;
        }
        trials.forget();
        changed(sender, args);
        sender.sendMessage(Text.of("&a" + done + " &7(layout " + saved.rev() + (times ? ", times cleared)" : ")")));
        CupLink.courseChanged(trials.games(), sender, before.id(), before.name(), CupPlan.VoidReason.CHANGED); // Weekly Cup
    }

    /** The confirm prompt's Weekly Cup line: this week's Cup on the course is called off (WP-C). */
    private void cupWarning(CommandSender sender, Course c) {
        String line = CupLink.warning(trials.games(), c.id());
        if (line != null) {
            sender.sendMessage(Text.of(line));
        }
    }

    /** Anything else: no new layout, the times stay. */
    private void save(CommandSender sender, Course after, String[] args) throws SQLException {
        trials.store().save(after, false, trials.weekKey(), now());
        trials.forget();
        changed(sender, args);
    }

    private void changed(CommandSender sender, String[] args) {
        trials.plugin().getLogger().info("Games: " + sender.getName() + " - /hcm games course " + String.join(" ", args));
    }

    /**
     * Where the sender stands, if it can hold a point of this course: a player, in a Games world,
     * and the course's own world once it has any point.
     */
    private Location here(CommandSender sender, Course c) {
        return here(sender, c, 0);
    }

    /** {@link #here(CommandSender, Course)} for a point with a {@code radius} (a checkpoint, the finish). */
    private Location here(CommandSender sender, Course c, double radius) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cStand in the world to do that."));
            return null;
        }
        Location here = p.getLocation();
        String world = here.getWorld() == null ? "" : here.getWorld().getName();
        if (!trials.gamesWorld(world)) {
            sender.sendMessage(Text.of("&cCourses are built in a Games world (games.worlds). You're in '" + world + "'."));
            return null;
        }
        boolean placed = c.start() != null || c.finish() != null || !c.checkpoints().isEmpty();
        if (placed && !c.world().isBlank() && !c.world().equalsIgnoreCase(world)) {
            sender.sendMessage(Text.of("&c" + c.name() + " is in '" + c.world() + "'. A course is all in one world."));
            return null;
        }
        String near = areaRefusal(trials.generated(), world, here.getX(), here.getY(), here.getZ(), radius);
        if (near != null) {
            sender.sendMessage(Text.of(near));
            return null;
        }
        return here;
    }

    // ---- tab completion -----------------------------------------------------------------------

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        if (n == 0) {
            return out;
        }
        String last = args[n - 1];
        if (n == 1) {
            match(out, last, "list", "create", "help");
            for (Course c : trials.courses()) {
                match(out, last, c.id());
            }
            return out;
        }
        if (args[0].equalsIgnoreCase("create")) {
            if (n == 3) {
                match(out, last, TrialKind.handMadeIds().toArray(new String[0]));
            } else if (n == 4) {
                match(out, last, Tier.ids().toArray(new String[0]));
            }
            return out;
        }
        if (args[0].equalsIgnoreCase("list")) {
            return out;
        }
        Course c = trials.course(args[0]);
        if (n == 2) {
            List<String> verbs = c != null && dailyRefusal(c, "start") != null ? DAILY_VERBS : VERBS;
            match(out, last, verbs.stream().filter(v -> DropperLayout.editRefusal(c, v) == null)
                    .toArray(String[]::new));
            return out;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        double radius = c == null ? 3 : c.kind().defaultRadius();
        switch (verb) {
            case "checkpoint" -> {
                if (n == 3) {
                    match(out, last, "add", "remove", "list");
                } else if (n == 4 && args[2].equalsIgnoreCase("add")) {
                    match(out, last, fmt(radius), "confirm");
                } else if (n == 4 && args[2].equalsIgnoreCase("remove") && c != null) {
                    for (int i = 1; i <= c.checkpoints().size(); i++) {
                        match(out, last, String.valueOf(i));
                    }
                } else if (n == 5) {
                    match(out, last, "confirm");
                }
            }
            case "finish" -> {
                if (n == 3) {
                    match(out, last, fmt(radius), "confirm");
                } else if (n == 4) {
                    match(out, last, "confirm");
                }
            }
            case "start" -> {
                if (n == 3) {
                    match(out, last, "confirm");
                }
            }
            case "tier" -> {
                if (n == 3) {
                    match(out, last, Tier.ids().toArray(new String[0]));
                }
            }
            case "fall" -> {
                if (n == 3) {
                    match(out, last, "off");
                    if (sender instanceof Player p) {
                        match(out, last, String.valueOf(p.getLocation().getBlockY() - 3));
                    }
                } else if (n == 4) {
                    match(out, last, "confirm");
                }
            }
            case "minseconds" -> {
                if (n == 3) {
                    match(out, last, "default", "5", "10", "30", "60");
                }
            }
            case "feature" -> {
                if (n == 3) {
                    match(out, last, "off");
                }
            }
            case "delete" -> {
                if (n == 3) {
                    match(out, last, "confirm");
                }
            }
            default -> {
                // nothing more to offer
            }
        }
        return out;
    }

    // ---- small pure helpers (tested) ----------------------------------------------------------

    /**
     * Why {@code verb} can't be used on {@code c}, or {@code null} when it can: a course Daily
     * Courses made (or one with a slot's or a Classics slot's id) allows only {@link #DAILY_VERBS}.
     */
    static String dailyRefusal(Course c, String verb) {
        if (c == null || !(c.generated() || Slots.any(c.id()) != null)) {
            return null;
        }
        return verb != null && DAILY_VERBS.contains(verb.toLowerCase(Locale.ROOT)) ? null : GenCopy.MADE_BY_DAILY;
    }

    /**
     * Why a hand-built course's point can't be at (x, y, z) of {@code world}, or {@code null}: it is
     * inside a Fresh Courses area or within {@value DailyLookup#EDITOR_MARGIN} blocks of one.
     */
    static String areaRefusal(GeneratedCourses g, String world, double x, double y, double z) {
        return areaRefusal(g, world, x, y, z, 0);
    }

    /**
     * {@link #areaRefusal(GeneratedCourses, String, double, double, double)} for a mark of
     * {@code radius}: every block it covers is kept that far away, as the Fresh Courses engine
     * measures a hand-built checkpoint (its point ± its radius).
     */
    static String areaRefusal(GeneratedCourses g, String world, double x, double y, double z, double radius) {
        double r = Math.max(0, radius);
        return DailyLookup.nearBox(g, world, (int) Math.floor(x - r), (int) Math.floor(y - r), (int) Math.floor(z - r),
                (int) Math.floor(x + r), (int) Math.floor(y + r), (int) Math.floor(z + r)) ? GenCopy.EDITOR_REFUSED : null;
    }

    /**
     * The words after the verb, without a {@code confirm} on the end: {@code [cliffs, fall, 60,
     * confirm]} → {@code [60]}; {@code [cliffs, confirm]} (no verb) → none, never a throw.
     */
    static List<String> rest(String[] args, boolean confirm) {
        int to = Math.max(2, args.length - (confirm ? 1 : 0));
        return args.length <= 2 ? new ArrayList<>() : new ArrayList<>(Arrays.asList(args).subList(2, to));
    }

    /**
     * What's wrong with a course's fall height, or {@code null} when nothing is: under the
     * world's floor no run ever falls that far, and at or above the lowest point of the course
     * (the start, a checkpoint or the finish) every run would go straight back, again and again.
     *
     * @param minHeight the world's floor, or {@code null} when not known
     */
    static String fallProblem(Course c, Integer minHeight) {
        Double y = c.fallY();
        if (y == null) {
            return null;
        }
        if (minHeight != null && y < minHeight) {
            return "y " + fmt(y) + " is below the bottom of the world (y " + minHeight
                    + "): no run would ever get there.";
        }
        Double low = c.lowestY();
        if (low != null && y >= low) {
            return "The fall height (y " + fmt(y) + ") must be under the lowest point of the course (y " + fmt(low)
                    + "), or every run goes straight back.";
        }
        return null;
    }

    /**
     * A radius typed by an admin ({@code null}/blank = {@code fallback}), kept inside
     * {@link Course#MIN_RADIUS}..{@link Course#MAX_RADIUS}; {@code NaN} when it isn't a number.
     */
    static double radius(String raw, double fallback) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("confirm")) {
            return Course.radius(fallback);
        }
        Double v = number(raw);
        return v == null ? Double.NaN : Course.radius(v);
    }

    /** A finite number, or {@code null}. */
    static Double number(String raw) {
        try {
            double v = Double.parseDouble(raw.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** A whole number, or -1. */
    static int whole(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    /** "2.5", "3" — no trailing ".0". */
    static String fmt(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static Course.Mark mark(Location here, double r) {
        return new Course.Mark(here.getX(), here.getY(), here.getZ(), r);
    }

    private static String where(Course.Mark m) {
        return fmt(m.x()) + " " + fmt(m.y()) + " " + fmt(m.z());
    }

    private static String where(Course.Spot s) {
        return fmt(s.x()) + " " + fmt(s.y()) + " " + fmt(s.z()) + " facing " + fmt(s.yaw());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void match(List<String> out, String prefix, String... options) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(o)) {
                out.add(o);
            }
        }
    }
}
