package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /hcm games golf ...}: building mini golf courses by standing in them (spec §12). The
 * framework checks {@code hcm.games.admin} before calling in.
 *
 * <pre>
 * create &lt;id&gt; [name...]            a new course where you stand (a Games world), closed
 * list                             every course
 * &lt;id&gt; info | list                 its holes and what's missing
 * &lt;id&gt; tp [hole]                   to a tee
 * &lt;id&gt; hole add &lt;par&gt;              a new last hole, its tee where you stand, facing the way you face
 * &lt;id&gt; hole &lt;n&gt; cup               the block you look at (within 6) is the cup: the floor of the hole
 * &lt;id&gt; hole &lt;n&gt; tee               move the tee to where you stand
 * &lt;id&gt; hole &lt;n&gt; par &lt;p&gt;           par 2-6
 * &lt;id&gt; hole &lt;n&gt; bounds &lt;1|2&gt;      a corner of the hole's bounds at your feet
 * &lt;id&gt; hole &lt;n&gt; remove            the holes after it move up
 * &lt;id&gt; name &lt;name...&gt;             rename it
 * &lt;id&gt; enable | disable            open it (only when nothing is missing) or close it
 * &lt;id&gt; delete confirm              delete it and its high scores
 * </pre>
 *
 * <p>A change to where a hole is (a tee, cup, bounds, or adding or removing a hole) bumps the
 * course's {@code rev} and clears its board, since old scores were set on another layout; when
 * there are scores to lose, the command asks for a trailing {@code confirm} first. A round being
 * played on the old layout ends at once (it couldn't count) and the player is sent home. Par and
 * the name aren't layout: they change nothing else. Course ids share {@code /hcm play} with every
 * game, alias and course, so a taken id is refused.
 *
 * <p>The cup is the block the ball comes to rest on: the floor of a sunken hole, a slab, or a
 * block flush with the green. A block with nothing to rest on at its centre, a sliver like a
 * carpet, or a hollow one like a cauldron is refused ({@link BallPhysics#cupShape}). Every edit that
 * is saved goes in the server log, as the trials editor's do.
 *
 * <p><b>Fresh Courses keeps its own</b> (GEN-SPEC §2.4, §5.5): Golf of the Week and Tiny Golf are
 * rebuilt every day, so here they can only be looked at and gone to ({@link #DAILY_VERBS}); and no
 * tee, cup or bound of a hand-built hole may be inside a Fresh Courses area or within {@value
 * DailyLookup#EDITOR_MARGIN} blocks of one.
 */
final class GolfAdmin implements GameAdmin {

    private static final String CONFIRM = "confirm";
    private static final List<String> VERBS = List.of("info", "tp", "hole", "name", "enable", "disable", "delete");
    private static final List<String> HOLE_VERBS = List.of("cup", "tee", "par", "bounds", "remove");
    /** What a course Fresh Courses made allows here: looking and going there. */
    static final List<String> DAILY_VERBS = List.of("info", "list", "tp");
    /**
     * This tool's own first words, and "auto" (the word /hcm games feature keeps for "pick one each
     * day", so a course called that could never be pinned): no course may be called them.
     */
    static final List<String> OWN_WORDS = List.of("create", "list", "help", "auto");

    private final MiniGolf golf;

    GolfAdmin(MiniGolf golf) {
        this.golf = golf;
    }

    @Override
    public String name() {
        return "golf";
    }

    @Override
    public List<String> help() {
        return List.of(
                "&e/hcm games golf create <id> [name] &7- a new golf course where you stand",
                "&e/hcm games golf list &7- every golf course",
                "&e/hcm games golf <id> info|tp [hole] &7- a course's holes; go to a tee",
                "&e/hcm games golf <id> hole add <par> &7- a new hole, its tee where you stand",
                "&e/hcm games golf <id> hole <n> cup|tee|remove &7- the cup you look at; the tee here",
                "&e/hcm games golf <id> hole <n> par <2-6>|bounds <1|2> &7- par; a bounds corner at your feet",
                "&e/hcm games golf <id> name <name>|enable|disable &7- rename, open or close it",
                "&e/hcm games golf <id> delete confirm &7- delete it and its high scores");
    }

    /**
     * A command. A bug in one is logged and answered here rather than thrown: this runs inside the
     * game's guard, where a throw would switch golf off and end every round.
     */
    @Override
    public void handle(CommandSender sender, String[] args) {
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                for (String line : help()) {
                    tell(sender, line);
                }
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(sender, args);
                case "list" -> list(sender);
                default -> course(sender, args);
            }
        } catch (RuntimeException e) {
            golf.log(Level.SEVERE, "Mini golf: /hcm games golf " + String.join(" ", args) + " failed", e);
            tell(sender, "&cThat didn't work - see the console.");
        }
    }

    // ---- create and list ------------------------------------------------------------------------------

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            tell(sender, "&cStand where the course will be: this is done in game.");
            return;
        }
        if (args.length < 2) {
            tell(sender, "&e/hcm games golf create <id> [name]");
            return;
        }
        String id = GolfCourse.normalise(args[1]);
        String problem = GolfCourse.idProblem(id);
        if (problem != null) {
            tell(sender, "&c" + problem);
            return;
        }
        GamesService games = golf.games();
        if (GameCatalog.taken(id) || games.game(id) != null || OWN_WORDS.contains(id)) {
            tell(sender, "&c" + id + " is a game's name or a reserved word. Pick another id.");
            return;
        }
        String world = p.getWorld().getName();
        if (golf.worlds().stream().noneMatch(w -> w.equalsIgnoreCase(world))) {
            tell(sender, "&cCourses are built in a Games world (games.worlds: " + String.join(", ", golf.worlds())
                    + "). You're in " + world + ".");
            return;
        }
        String name = args.length > 2 ? cleanName(String.join(" ", Arrays.copyOfRange(args, 2, args.length)))
                : pretty(id);
        try {
            if (games.dao().course(id) != null || games.resolve(id) != null) {
                tell(sender, "&cThere's already a course called " + id + ".");
                return;
            }
            long now = System.currentTimeMillis();
            games.dao().saveCourse(CourseCodec.toRow(GolfCourse.create(id, name, world), now, now));
        } catch (SQLException e) {
            failed(sender, e);
            return;
        }
        golf.reloadCourses();
        logged(sender, "created", id, name + " in " + world);
        tell(sender, "&aMade the golf course " + id + " (" + name + "), closed for now.");
        tell(sender, "&7Stand on hole 1's tee, face down the hole, then: &e/hcm games golf " + id + " hole add <par>");
    }

    private void list(CommandSender sender) {
        var all = golf.courses();
        var broken = golf.unreadable();
        if (all.isEmpty() && broken.isEmpty()) {
            tell(sender, "&7No golf courses yet. &e/hcm games golf create <id> [name]");
            return;
        }
        tell(sender, "&dGolf courses &7(" + (all.size() + broken.size()) + "):");
        List<String> worlds = golf.worlds();
        for (GolfCourse c : all.values()) {
            String state = c.playable(worlds) ? "&aopen" : !c.problems(worlds).isEmpty() ? "&cnot ready" : "&7closed";
            tell(sender, "&f" + c.id() + " &7- " + c.name() + ", " + MiniGolf.holes(c.holes().size()) + ", par "
                    + c.par() + " - " + state + (c.generated() ? " &d(Fresh Courses)" : ""));
        }
        broken.forEach((id, why) -> tell(sender, "&c" + id + " &7- its saved data can't be read (" + why + ")"));
    }

    // ---- one course ----------------------------------------------------------------------------------

    private void course(CommandSender sender, String[] args) {
        String id = GolfCourse.normalise(args[0]);
        String verb = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        if (golf.unreadable().containsKey(id)) {
            if (verb.equals("delete")) {
                delete(sender, id, args);
            } else {
                tell(sender, "&c" + id + "'s saved data can't be read (" + golf.unreadable().get(id)
                        + "). &7Delete it with &e/hcm games golf " + id + " delete confirm");
            }
            return;
        }
        GolfCourse c = golf.course(id);
        if (c == null) {
            tell(sender, "&cThere's no golf course called " + id + ". &7/hcm games golf list");
            return;
        }
        String daily = dailyRefusal(c, verb);
        if (daily != null) {
            tell(sender, daily);
            return;
        }
        switch (verb) {
            case "info", "list" -> info(sender, c);
            case "tp" -> tp(sender, c, args);
            case "hole" -> hole(sender, c, args);
            case "name" -> rename(sender, c, args);
            case "enable" -> enable(sender, c);
            case "disable" -> disable(sender, c);
            case "delete" -> delete(sender, id, args);
            default -> tell(sender, "&e/hcm games golf " + id + " info|tp|hole|name|enable|disable|delete");
        }
    }

    private void info(CommandSender sender, GolfCourse c) {
        List<String> worlds = golf.worlds();
        tell(sender, "&d" + c.name() + " &7(" + c.id() + ") - " + MiniGolf.holes(c.holes().size()) + ", par " + c.par()
                + ", world " + c.world() + ", rev " + c.rev());
        tell(sender, "&7Open to players: " + (c.playable(worlds) ? "&ayes" : c.enabled() ? "&cno - not ready"
                : "&cno - disabled"));
        for (int i = 0; i < c.holes().size(); i++) {
            GolfCourse.Hole h = c.holes().get(i);
            tell(sender, "&f" + (i + 1) + ". &7par " + h.par() + " · tee " + (h.tee() == null ? "&cnone&7"
                    : at(h.tee().x(), h.tee().y(), h.tee().z())) + " · cup " + spot(h.cup()) + " · bounds "
                    + spot(h.corner1()) + " to " + spot(h.corner2()));
        }
        for (String problem : c.problems(worlds)) {
            tell(sender, "&c- " + problem);
        }
        GenTag gen = c.gen();
        if (gen != null) {
            tell(sender, "&dMade by Fresh Courses: &7the layout for " + DailyText.setName(gen.cadence(), gen.day())
                    + (gen.reroll() > 0 ? " (reroll " + gen.reroll() + ")" : "") + ", half " + gen.half()
                    + (golf.games().generated().live(c.id(), gen) ? ", &aopen" : ", &cclosed right now")
                    + " &7- &e/hcm games gen status");
        } else {
            String kept = keptLine(c);
            if (kept != null) {
                tell(sender, kept);
            }
        }
        List<UUID> playing = golf.playing().getOrDefault(c.id(), List.of());
        if (!playing.isEmpty()) {
            tell(sender, "&7Playing it now: " + playing.size());
        }
    }

    /**
     * What {@code info} says about a course kept from a Fresh Courses plan that plays Adventure Golf's rules
     * ({@link GolfCourse#adventure}), named by the golf planner version it was kept from
     * ({@link GolfCourse#keptAlgo}): Golf v4 (version {@value GolfGroup#FIRST_LONG_CLOCK_ALGO} on), whose group
     * hole clock also grows with par, or Adventure Golf (version 3, and a course kept before the version was
     * recorded, which only 0.36's Adventure Golf could be). {@code null} for any other course.
     */
    static String keptLine(GolfCourse c) {
        if (c == null || c.generated() || !c.adventure()) {
            return null;
        }
        if (c.keptAlgo() >= GolfGroup.FIRST_LONG_CLOCK_ALGO) {
            return "&dKept from Golf v4: &7its smooth sandstone plays as sand, a ball that stops over water falls in"
                    + " (+1, back to its spot), and a group's hole clock grows with par, as when it was made.";
        }
        return "&dKept from Adventure Golf: &7its smooth sandstone plays as sand, and a ball that stops"
                + " over water falls in (+1, back to its spot), as when it was made.";
    }

    private void tp(CommandSender sender, GolfCourse c, String[] args) {
        if (!(sender instanceof Player p)) {
            tell(sender, "&cOnly a player can be sent to a tee.");
            return;
        }
        if (golf.games().sessions().session(p) != null) {
            tell(sender, "&cLeave your game first (/hcm leave).");
            return;
        }
        int n = args.length > 2 ? number(args[2], -1) : 1;
        GolfCourse.Hole h = c.hole(n);
        if (h == null || h.tee() == null) {
            tell(sender, "&c" + (h == null ? "There's no hole " + (args.length > 2 ? args[2] : "1")
                    : "Hole " + n + " has no tee yet") + ".");
            return;
        }
        World w = golf.plugin().getServer().getWorld(c.world());
        if (w == null) {
            tell(sender, "&cThe world " + c.world() + " isn't loaded.");
            return;
        }
        p.teleport(new Location(w, h.tee().x(), h.tee().y(), h.tee().z(), h.tee().yaw(), 0));
        tell(sender, "&7At hole " + n + "'s tee.");
    }

    // ---- holes ---------------------------------------------------------------------------------------

    private void hole(CommandSender sender, GolfCourse c, String[] args) {
        if (args.length < 3) {
            tell(sender, "&e/hcm games golf " + c.id() + " hole add <par> &7or &ehole <n> cup|tee|par|bounds|remove");
            return;
        }
        if (args[2].equalsIgnoreCase("add")) {
            addHole(sender, c, args);
            return;
        }
        int n = number(args[2], -1);
        GolfCourse.Hole h = c.hole(n);
        if (h == null) {
            tell(sender, "&c" + c.id() + " has no hole " + args[2] + " (it has " + c.holes().size() + ").");
            return;
        }
        String verb = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (verb) {
            case "cup" -> {
                Player p = here(sender, c);
                if (p == null) {
                    return;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null) {
                    tell(sender, "&cLook at the cup block, within 6 blocks.");
                    return;
                }
                if (nearDaily(sender, p.getWorld(), b.getX(), b.getY(), b.getZ())) {
                    return;
                }
                String what = b.getType().name().toLowerCase(Locale.ROOT);
                BallPhysics.CupShape shape = BallPhysics.cupShape(new LiveBlocks(p.getWorld()), b.getX(), b.getY(),
                        b.getZ());
                if (shape != BallPhysics.CupShape.FINE) {
                    String why = switch (shape) {
                        case NOTHING -> " has nothing a ball can rest on.";
                        case THIN -> " is too thin to hold a ball.";
                        default -> " is hollow: a ball would be stuck inside it.";
                    };
                    tell(sender, "&cThe " + what + why);
                    tell(sender, "&7Look at the floor of the hole instead: the block the ball ends up resting on.");
                    return;
                }
                layout(sender, c, c.withHole(n, h.withCup(new GolfCourse.Spot(b.getX(), b.getY(), b.getZ()))), args,
                        "Hole " + n + "'s cup is the " + what + " at " + b.getX() + " " + b.getY() + " " + b.getZ() + ".");
            }
            case "tee" -> {
                Player p = here(sender, c);
                if (p != null && !nearDaily(sender, p.getLocation())) {
                    layout(sender, c, c.withHole(n, h.withTee(tee(p))), args, "Hole " + n + "'s tee is where you stand.");
                }
            }
            case "par" -> {
                int par = args.length > 4 ? number(args[4], -1) : -1;
                if (par < GolfCourse.MIN_PAR || par > GolfCourse.MAX_PAR) {
                    tell(sender, "&cPar is " + GolfCourse.MIN_PAR + " to " + GolfCourse.MAX_PAR + ".");
                    return;
                }
                save(sender, c.withHole(n, h.withPar(par)), "changed", "Hole " + n + " is par " + par + ".");
            }
            case "bounds" -> {
                int which = args.length > 4 ? number(args[4], -1) : -1;
                if (which != 1 && which != 2) {
                    tell(sender, "&e/hcm games golf " + c.id() + " hole " + n + " bounds <1|2> &7- a corner at your feet");
                    return;
                }
                Player p = here(sender, c);
                if (p == null || nearDaily(sender, p.getLocation())) {
                    return;
                }
                Location l = p.getLocation();
                GolfCourse.Spot corner = new GolfCourse.Spot(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                GolfCourse.Spot other = which == 1 ? h.corner2() : h.corner1();
                String box = boundsRefusal(golf.games().generated(), p.getWorld().getName(), corner, other);
                if (box != null) {
                    tell(sender, box);
                    return;
                }
                layout(sender, c, c.withHole(n, h.withCorner(which, corner)), args,
                        "Hole " + n + "'s bounds corner " + which + " is at " + spot(corner) + "&a.");
            }
            case "remove" -> layout(sender, c, c.removeHole(n), args, "Removed hole " + n + "; the holes after it moved up.");
            default -> tell(sender, "&e/hcm games golf " + c.id() + " hole " + n + " cup|tee|par <p>|bounds <1|2>|remove");
        }
    }

    private void addHole(CommandSender sender, GolfCourse c, String[] args) {
        int par = args.length > 3 ? number(args[3], -1) : -1;
        if (par < GolfCourse.MIN_PAR || par > GolfCourse.MAX_PAR) {
            tell(sender, "&e/hcm games golf " + c.id() + " hole add <par> &7- par " + GolfCourse.MIN_PAR + " to "
                    + GolfCourse.MAX_PAR + ", the tee where you stand");
            return;
        }
        if (c.holes().size() >= GolfCourse.MAX_HOLES) {
            tell(sender, "&cA course has at most " + GolfCourse.MAX_HOLES + " holes.");
            return;
        }
        Player p = here(sender, c);
        if (p == null || nearDaily(sender, p.getLocation())) {
            return;
        }
        int n = c.holes().size() + 1;
        if (layout(sender, c, c.addHole(new GolfCourse.Hole(tee(p), null, par, null, null)), args,
                "Hole " + n + " (par " + par + ") starts where you stand.")) {
            tell(sender, "&7Next: look at its cup and &e/hcm games golf " + c.id() + " hole " + n
                    + " cup&7, then stand at two opposite corners for &ebounds 1&7 and &ebounds 2&7.");
        }
    }

    /** The admin must be a player in the course's world. */
    private Player here(CommandSender sender, GolfCourse c) {
        if (!(sender instanceof Player p)) {
            tell(sender, "&cThis is done in game, standing on the course.");
            return null;
        }
        if (!p.getWorld().getName().equals(c.world())) {
            tell(sender, "&cStand in " + c.world() + " (the course's world) to change it.");
            return null;
        }
        return p;
    }

    /** Whether where the admin stands is too near a Fresh Courses area (and they were told). */
    private boolean nearDaily(CommandSender sender, Location l) {
        return l.getWorld() != null && nearDaily(sender, l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Whether block (x, y, z) is too near a Fresh Courses area (and the admin was told). */
    private boolean nearDaily(CommandSender sender, World w, int x, int y, int z) {
        String why = areaRefusal(golf.games().generated(), w.getName(), x, y, z);
        if (why != null) {
            tell(sender, why);
            return true;
        }
        return false;
    }

    private static GolfCourse.Tee tee(Player p) {
        Location l = p.getLocation();
        return new GolfCourse.Tee(round(l.getX()), round(l.getY()), round(l.getZ()), (float) round(l.getYaw()));
    }

    // ---- the switch, the name, deleting ----------------------------------------------------------------

    private void rename(CommandSender sender, GolfCourse c, String[] args) {
        String name = args.length > 2 ? cleanName(String.join(" ", Arrays.copyOfRange(args, 2, args.length))) : "";
        if (name.isEmpty()) {
            tell(sender, "&e/hcm games golf " + c.id() + " name <name>");
            return;
        }
        save(sender, c.withName(name), "renamed", "It's called " + name + " now.");
    }

    private void enable(CommandSender sender, GolfCourse c) {
        List<String> problems = c.problems(golf.worlds());
        if (!problems.isEmpty()) {
            tell(sender, "&c" + c.id() + " isn't ready yet:");
            for (String problem : problems) {
                tell(sender, "&c- " + problem);
            }
            return;
        }
        if (save(sender, c.withEnabled(true), "opened", c.name() + " is open.")) {
            tell(sender, "&7Players find it on the Golf tab of /hcm play, or with &e/hcm play " + c.id() + "&7.");
        }
    }

    private void disable(CommandSender sender, GolfCourse c) {
        if (save(sender, c.withEnabled(false), "closed", c.name() + " is closed.")) {
            golf.rounds().closeCourse(c.id(), "&7" + c.name() + " was closed by an admin.");
        }
    }

    private void delete(CommandSender sender, String id, String[] args) {
        if (!last(args).equalsIgnoreCase(CONFIRM)) {
            tell(sender, "&eThis deletes " + id + " and its high scores for good. &7Type &e/hcm games golf " + id
                    + " delete confirm");
            return;
        }
        golf.rounds().closeCourse(id, "&7This course was closed by an admin.");
        int cleared;
        try {
            golf.games().dao().deleteCourse(id);
            cleared = golf.games().dao().resetScores(golf.id(), Scores.golf(id), null);
        } catch (SQLException e) {
            failed(sender, e);
            return;
        }
        golf.reloadCourses();
        String scores = cleared > 0 ? cleared + " high score" + (cleared == 1 ? "" : "s") : "";
        logged(sender, "deleted", id, scores.isEmpty() ? "" : scores + " cleared");
        tell(sender, "&aDeleted " + id + (scores.isEmpty() ? "" : " and " + scores) + ".");
    }

    // ---- saving ----------------------------------------------------------------------------------------

    /** Save a change that isn't layout (par, name, the switch); {@code action} is for the server log. */
    private boolean save(CommandSender sender, GolfCourse edited, String action, String done) {
        try {
            long now = System.currentTimeMillis();
            golf.games().dao().saveCourse(CourseCodec.toRow(edited, now, now), false);
        } catch (SQLException e) {
            failed(sender, e);
            return false;
        }
        golf.reloadCourses();
        logged(sender, action, edited.id(), done);
        tell(sender, "&a" + done);
        return true;
    }

    /**
     * Save a layout change: {@code rev} goes up and the board is cleared — after a trailing
     * {@code confirm} when there are scores to lose. Rounds being played on the course end at once
     * (they couldn't count on the old layout), and the players are told why. Then what's still
     * missing, if anything.
     *
     * @return whether it was saved
     */
    private boolean layout(CommandSender sender, GolfCourse old, GolfCourse edited, String[] args, String done) {
        boolean scores = golf.record(old.id()) != null;
        if (scores && !last(args).equalsIgnoreCase(CONFIRM)) {
            tell(sender, "&eThat changes " + old.id() + "'s layout, so its high scores will be cleared.");
            tell(sender, "&7To go ahead: &e/hcm games golf " + String.join(" ", args) + " " + CONFIRM);
            return false;
        }
        int rev;
        int cleared = 0;
        try {
            long now = System.currentTimeMillis();
            rev = golf.games().dao().saveCourse(CourseCodec.toRow(edited, now, now), true);
            if (scores) {
                cleared = golf.games().dao().resetScores(golf.id(), Scores.golf(old.id()), null);
            }
        } catch (SQLException e) {
            failed(sender, e);
            return false;
        }
        golf.reloadCourses();
        String note = "rev " + rev + (cleared > 0 ? ", " + cleared + " high score" + (cleared == 1 ? "" : "s")
                + " cleared" : "");
        int playing = golf.playing().getOrDefault(old.id(), List.of()).size();
        logged(sender, "changed the layout of", old.id(), done + " (" + note + (playing > 0 ? ", " + playing
                + " round" + (playing == 1 ? "" : "s") + " ended" : "") + ")");
        tell(sender, "&a" + done + " &7(" + note + ")");
        GolfCourse now = golf.course(old.id());
        if (now != null) {
            List<String> problems = now.problems(golf.worlds());
            if (!problems.isEmpty()) {
                tell(sender, "&7Still to do: " + String.join(", ", problems) + ".");
            } else if (!now.enabled()) {
                tell(sender, "&7It's ready. Open it with &e/hcm games golf " + now.id() + " enable");
            }
        }
        if (playing > 0) {
            golf.rounds().closeCourse(old.id(), GolfRounds.changedLine(old));
            tell(sender, "&7" + playing + " playing it now " + (playing == 1 ? "was" : "were")
                    + " sent home: a round on the old layout can't count.");
        }
        return true;
    }

    // ---- tab completion --------------------------------------------------------------------------------

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        int n = args.length;
        String typed = n == 0 ? "" : args[n - 1].toLowerCase(Locale.ROOT);
        if (n <= 1) {
            offer(out, typed, List.of("create", "list"));
            offer(out, typed, new ArrayList<>(golf.courses().keySet()));
            offer(out, typed, new ArrayList<>(golf.unreadable().keySet()));
            return out;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("create") || first.equals("list")) {
            return out;
        }
        GolfCourse c = golf.course(first);
        if (n == 2) {
            offer(out, typed, c != null && dailyRefusal(c, "hole") != null ? List.of("info", "tp") : VERBS);
            return out;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "tp" -> {
                if (n == 3) {
                    offer(out, typed, holeNumbers(c));
                }
            }
            case "delete" -> {
                if (n == 3) {
                    offer(out, typed, List.of(CONFIRM));
                }
            }
            case "hole" -> holeTab(out, typed, c, args);
            default -> {
                // nothing to offer
            }
        }
        return out;
    }

    private static void holeTab(List<String> out, String typed, GolfCourse c, String[] args) {
        int n = args.length;
        if (n == 3) {
            List<String> options = new ArrayList<>(List.of("add"));
            options.addAll(holeNumbers(c));
            offer(out, typed, options);
            return;
        }
        String what = args[2].toLowerCase(Locale.ROOT);
        if (what.equals("add")) {
            offer(out, typed, n == 4 ? pars() : n == 5 ? List.of(CONFIRM) : List.of());
            return;
        }
        if (n == 4) {
            offer(out, typed, HOLE_VERBS);
            return;
        }
        String verb = args[3].toLowerCase(Locale.ROOT);
        if (n == 5) {
            switch (verb) {
                case "par" -> offer(out, typed, pars());
                case "bounds" -> offer(out, typed, List.of("1", "2"));
                case "cup", "tee", "remove" -> offer(out, typed, List.of(CONFIRM));
                default -> {
                    // nothing
                }
            }
        } else if (n == 6 && verb.equals("bounds")) {
            offer(out, typed, List.of(CONFIRM));
        }
    }

    private static List<String> holeNumbers(GolfCourse c) {
        List<String> out = new ArrayList<>();
        for (int i = 1; c != null && i <= c.holes().size(); i++) {
            out.add(String.valueOf(i));
        }
        return out;
    }

    private static List<String> pars() {
        List<String> out = new ArrayList<>();
        for (int p = GolfCourse.MIN_PAR; p <= GolfCourse.MAX_PAR; p++) {
            out.add(String.valueOf(p));
        }
        return out;
    }

    private static void offer(List<String> out, String typed, List<String> options) {
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(o);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /**
     * Why {@code verb} can't be used on {@code c}, or {@code null} when it can: a course Daily
     * Courses made (or one with a slot's or a Classics slot's id) allows only {@link #DAILY_VERBS}.
     */
    static String dailyRefusal(GolfCourse c, String verb) {
        if (c == null || !(c.generated() || Slots.any(c.id()) != null)) {
            return null;
        }
        return verb != null && DAILY_VERBS.contains(verb.toLowerCase(Locale.ROOT)) ? null : GenCopy.MADE_BY_DAILY;
    }

    /**
     * Why a hand-built hole's tee, cup or bound can't be at block (x, y, z) of {@code world}, or
     * {@code null}: inside a Fresh Courses area or within {@value DailyLookup#EDITOR_MARGIN} blocks of one.
     */
    static String areaRefusal(GeneratedCourses g, String world, int x, int y, int z) {
        return DailyLookup.nearArea(g, world, x, y, z) ? GenCopy.EDITOR_REFUSED : null;
    }

    /**
     * Why a hand-built hole's bounds from corner {@code a} to corner {@code b} can't be, or
     * {@code null}: the whole box, not only each corner, is kept out of the Fresh Courses areas and
     * {@value DailyLookup#EDITOR_MARGIN} blocks around them (as the engine measures a hand-built
     * hole). With the other corner not set yet, only {@code a} is checked.
     */
    static String boundsRefusal(GeneratedCourses g, String world, GolfCourse.Spot a, GolfCourse.Spot b) {
        GolfCourse.Spot o = b == null ? a : b;
        return DailyLookup.nearBox(g, world, a.x(), a.y(), a.z(), o.x(), o.y(), o.z()) ? GenCopy.EDITOR_REFUSED : null;
    }

    /** A saved edit in the server log, as the trials editor keeps them: who, what, which course. */
    private void logged(CommandSender sender, String action, String id, String detail) {
        golf.log(Level.INFO, "Games: " + sender.getName() + " " + action + " golf course " + id
                + (detail == null || detail.isEmpty() ? "" : " - " + Text.plain(detail)), null);
    }

    private void failed(CommandSender sender, SQLException e) {
        golf.log(Level.SEVERE, "Mini golf: a course edit could not be saved", e);
        tell(sender, "&cCouldn't save that - see the console.");
    }

    private static void tell(CommandSender sender, String line) {
        sender.sendMessage(Text.of(line));
    }

    private static String last(String[] args) {
        return args.length == 0 ? "" : args[args.length - 1];
    }

    private static int number(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String at(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
    }

    private static String spot(GolfCourse.Spot s) {
        return s == null ? "&cnone&7" : s.x() + " " + s.y() + " " + s.z();
    }

    /** A name as players will read it: no colour codes, at most 32 characters. */
    static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[&§].", "").replaceAll("[&§]", "").trim().replaceAll("\\s+", " ");
        return s.length() > 32 ? s.substring(0, 32).trim() : s;
    }

    /** "meadow_links" → "Meadow Links". */
    static String pretty(String id) {
        StringBuilder b = new StringBuilder();
        for (String word : id.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return b.toString();
    }
}
