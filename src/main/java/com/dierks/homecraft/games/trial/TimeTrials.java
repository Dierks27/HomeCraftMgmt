package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.games.world.WorldSessions;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.trial.CourseListMenu;
import com.dierks.homecraft.gui.games.trial.CourseMenu;
import com.dierks.homecraft.gui.games.trial.ResultMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Time Trials (spec §11, R2.15, R3.3, R3.7, R3.10, R3.13): parkour, elytra and boat courses in the
 * Games world, built by admins with {@code /hcm games course} and kept in the database.
 *
 * <p><b>Each course is its own game.</b> It has its own tile on the Courses tab, its own
 * {@code /hcm play} id (a {@link Playable} that pays under its kind's ledger source), its own
 * all-time and weekly boards and its own first-clear reward. This class is the one engine that
 * runs them all; the maths — checkpoints in order, the finish time between two ticks, the fall
 * rule, the speed checks — is in plain classes ({@link Progress}, {@link FairPlay}) that are
 * tested without a server.
 *
 * <p><b>A run.</b> {@code /hcm play <course>} (or Start on the course screen) runs the gate, then
 * a world session takes the player to the start with only the course kit — "Back to checkpoint",
 * rockets and an elytra on an elytra course, a boat of their own on a boat course, and "Leave
 * game". A 3-2-1 countdown holds them still without teleporting them (a player on foot keeps
 * looking around; a boat is held at zero speed), and the clock starts on the tick it ends. Every
 * move is checked as the segment it really is, so a fast one clears each checkpoint it passes
 * through and can't skip one. Going wrong — a fall on a parkour course, a landing or water on an
 * elytra course, getting out of the boat, someone else moving the player, the void — sends the
 * run back to its last checkpoint; the clock keeps running.
 *
 * <p><b>Fair play.</b> The session already keeps the player's things out and their game mode on
 * adventure. On top of it, flying, a changed game mode, any potion effect, or a changed walk speed
 * or movement attribute voids the run the moment it is seen, and at the finish a run quicker than
 * the course's shortest time, or a leg faster than the kind allows (legs a server stall touched
 * aside), doesn't count either. A run whose course changed layout while it ran records nothing
 * (its time belongs to a course that's gone). Test runs record nothing ever.
 *
 * <p><b>The finish.</b> The time goes onto the course's all-time board and this week's board;
 * the player reads the time, their best and the record (with who holds it); the rewards are paid
 * through {@link SkillRewards} — first clear, best this week, course of the week, today's pick —
 * and the session sends them home. Once they're there, a small result screen offers "Play again".
 *
 * <p>Everything live here — runs, boats — is in memory and cleaned up with the session: boats are
 * never saved with the world, and any left by a crash are swept when the game starts.
 */
public final class TimeTrials implements Game {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<TimeTrialsSettings> SPEC = new GameSpec<>("trials", GameKind.TRIAL,
            TimeTrialsSettings.KEYS, TimeTrialsSettings.defaults(), TimeTrialsSettings::parse,
            TimeTrials::new, null);

    /** The countdown: "3", "2", "1" a second apart, then "Go!". */
    static final int COUNTDOWN_TICKS = 60;
    /** The clock on the action bar is redrawn this often. */
    static final int CLOCK_EVERY = 4;
    /** Two send-backs closer than this are one (a double click, one fall seen twice). */
    static final long RESET_GAP = 10;
    /** A held sneak in a boat sends the run back at most this often. */
    static final long DISMOUNT_GAP = 40;
    /** How long the run waits for its own teleport before trying again. */
    static final long WAIT_TICKS = 60;
    /** Moved by someone else and not sent back within this long: send it back now. */
    static final long SUSPEND_TICKS = 20;
    /** Rockets in the elytra kit, topped up at every checkpoint. */
    static final int ROCKETS = 3;
    /** The result screen waits up to this many checks, this far apart, for the player to be home. */
    static final int RESULT_TRIES = 30;
    static final long RESULT_EVERY = 10;
    /** A teleport this close to the one the run asked for is the run's own. */
    private static final double MATCH = 0.05;

    /** The general rules, shown on the course list. */
    private static final List<String> RULES = List.of(
            "Race from the start to the finish.",
            "Reach every checkpoint in order.",
            "Go wrong and you're back at your last checkpoint.",
            "The clock keeps running, so beat your best time!");

    private final GameContext ctx;
    private final CourseStore store;
    private final CourseAdmin admin;
    /** Player → their run (at most one each: a player is in at most one session). */
    private final Map<UUID, TrialRun> runs = new HashMap<>();
    /** Boat → its rider, so a boat's move finds its run at once. */
    private final Map<UUID, UUID> boats = new HashMap<>();
    /** Every course, read once and again after each edit; {@code null} = read on next use. */
    private List<Course> cache;
    private long lastReadError;
    /** When the last trial tick ran ({@code System.nanoTime()}; 0 = none yet), to see server stalls. */
    private long lastTick;

    public TimeTrials(GameContext ctx) {
        this.ctx = ctx;
        this.store = new CourseStore(() -> ctx.games().dao(), line -> log().warning(line));
        this.admin = new CourseAdmin(this);
    }

    /**
     * What a finished run showed, for the result screen.
     *
     * @param courseId     the course
     * @param courseName   its name
     * @param ms           the time
     * @param counted      recorded and rewarded
     * @param test         an admin's test run
     * @param reason       why it didn't count (or, for a test, wouldn't have), or {@code null}
     * @param personalBest a new best
     * @param record       a new course record
     * @param best         the player's best on the course after this run, or {@code null}
     * @param earned       tokens paid
     */
    public record Result(String courseId, String courseName, long ms, boolean counted, boolean test, String reason,
                         boolean personalBest, boolean record, Long best, int earned) {
    }

    // ---- the Game ---------------------------------------------------------------------------

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return "Time Trials";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_PARKOUR;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return RULES;
    }

    @Override
    public ItemStack tile(Player viewer) {
        int n = openCourses().size();
        List<String> lore = new ArrayList<>();
        for (String line : RULES.subList(0, 3)) {
            lore.add("&7" + line);
        }
        lore.add("&eClick to see the courses");
        return Menus.icon(Material.FEATHER, "&e" + name() + " &7- " + n + " course" + (n == 1 ? "" : "s"),
                lore.toArray(new String[0]));
    }

    /** The course list. */
    @Override
    public void open(Player player, Runnable back) {
        new CourseListMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /** One tile per open course on the Courses tab, easiest first. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        List<Course> open = openCourses();
        String week = courseOfWeek(open);
        List<GameTile> out = new ArrayList<>(open.size());
        for (int i = 0; i < open.size(); i++) {
            Course c = open.get(i);
            out.add(new GameTile(Tab.COURSES, courseTile(viewer, c, week), c.id(), i));
        }
        return out;
    }

    /** Every open course, each paying under its kind's own ledger source. */
    @Override
    public Collection<Playable> playables() {
        List<Playable> out = new ArrayList<>();
        for (Course c : openCourses()) {
            out.add(new Playable(c.id(), c.name(), this, c.kind().source(), c.tier().id()));
        }
        return out;
    }

    /**
     * Start a course. From a screen (there is somewhere to go back to) it opens the course's own
     * screen first — rules, your best, the record, Start; from a command, a sign or an NPC it
     * starts at once.
     */
    @Override
    public boolean play(Player player, String playableId, Runnable back) {
        Course c = openCourse(playableId);
        if (c == null) {
            return false;
        }
        if (back != null) {
            new CourseMenu(ctx.plugin(), this, c, player, back).open(player);
            return true;
        }
        return begin(player, c, false);
    }

    /** One {@code /api/arcade} entry per open course: its kind, tier and record (no names unless allowed). */
    @Override
    public void feed(FeedWriter out) {
        List<Course> open = new ArrayList<>(openCourses());
        open.sort(Comparator.comparing(Course::id));
        for (Course c : open) {
            GamesDao.ScoreRow r = record(c.id());
            out.course(c.id(), c.name(), c.kind().id(), c.tier().id(), r == null ? null : r.score(),
                    r == null ? null : r.at(), r != null && out.showNames() ? holder(r.player()) : null);
        }
    }

    /** Sweep leftover boats, read the courses afresh, and hook the moves, glides and boats. */
    @Override
    public void start() {
        cache = null;
        lastTick = 0;
        WorldEntities.sweep(this);
        GamesService g = games();
        g.on(this, PlayerMoveEvent.class, EventPriority.HIGH, true, this::hold);
        g.on(this, PlayerMoveEvent.class, EventPriority.MONITOR, true, this::moved);
        g.on(this, VehicleMoveEvent.class, EventPriority.MONITOR, false, this::boatMoved);
        g.on(this, PlayerTeleportEvent.class, EventPriority.MONITOR, true, this::teleported);
        g.on(this, EntityToggleGlideEvent.class, EventPriority.MONITOR, true, this::glide);
        g.on(this, VehicleExitEvent.class, EventPriority.MONITOR, false,
                e -> triedToLeave(e.isCancelled(), e.getExited(), e.getVehicle()));
        g.on(this, EntityDismountEvent.class, EventPriority.MONITOR, false,
                e -> triedToLeave(e.isCancelled(), e.getEntity(), e.getDismounted()));
        g.every(this, 1, 1, this::tick);
    }

    /** The framework ends the sessions; here the boats go and the runs are forgotten. */
    @Override
    public void stop() {
        for (TrialRun run : new ArrayList<>(runs.values())) {
            removeBoat(run, Bukkit.getPlayer(run.player));
        }
        runs.clear();
        boats.clear();
        cache = null;
        lastTick = 0;
    }

    @Override
    public void onQuit(Player player) {
        end(player);
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        end(player);
    }

    /** Fell out of the world, or someone else moved the player a little way: back to the last checkpoint. */
    @Override
    public void onVoid(Player player) {
        TrialRun run = runs.get(player.getUniqueId());
        if (run != null && run.running()) {
            sendBack(player, run, 0);
        }
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        TrialRun run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        switch (action) {
            case "checkpoint" -> {
                if (run.running()) {
                    sendBack(player, run, RESET_GAP);
                } else if (run.phase == TrialRun.Phase.COUNTDOWN) {
                    player.sendActionBar(Text.of("&7Wait for the countdown."));
                }
            }
            case "firework" -> player.sendActionBar(Text.of("&7Rockets work while you glide."));
            default -> {
                // the elytra and anything else: nothing to do
            }
        }
    }

    // ---- courses ----------------------------------------------------------------------------

    /** The live settings (read on every use, never cached across a reload). */
    public TimeTrialsSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** Every course, open or not, by id. */
    List<Course> courses() {
        if (cache == null) {
            try {
                cache = List.copyOf(store.load());
            } catch (SQLException e) {
                long now = System.currentTimeMillis();
                if (now - lastReadError > 60_000) {
                    lastReadError = now;
                    log().log(Level.SEVERE, "Time trials: could not read the courses", e);
                }
                return List.of();
            }
        }
        return cache;
    }

    /** A course by id (any case), open or not, or {@code null}. */
    Course course(String id) {
        if (id == null) {
            return null;
        }
        for (Course c : courses()) {
            if (c.id().equalsIgnoreCase(id.trim())) {
                return c;
            }
        }
        return null;
    }

    /** The courses players can play: open, complete, and in a Games world — easiest first, then by name. */
    public List<Course> openCourses() {
        List<Course> out = new ArrayList<>();
        for (Course c : courses()) {
            if (c.enabled() && c.ready() && gamesWorld(c.world())) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing((Course c) -> c.tier().ordinal())
                .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)).thenComparing(Course::id));
        return out;
    }

    /** An open course by id, or {@code null}. */
    public Course openCourse(String id) {
        for (Course c : openCourses()) {
            if (id != null && c.id().equalsIgnoreCase(id.trim())) {
                return c;
            }
        }
        return null;
    }

    /** Read the courses again (after an edit). */
    void forget() {
        cache = null;
    }

    CourseStore store() {
        return store;
    }

    /** Whether {@code world} is listed in {@code games.worlds}. */
    boolean gamesWorld(String world) {
        for (String w : games().config().common().worlds()) {
            if (w != null && w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why {@code id} can't name a new course, or {@code null} when it can: course ids share
     * {@code /hcm play} with the games, their other names, the words it keeps for itself and
     * every world game's courses.
     */
    String clash(String id) throws SQLException {
        if (!TrialText.validId(id)) {
            return "A course id is 2-32 lower-case letters, digits or _, starting with a letter.";
        }
        if (GameCatalog.taken(id)) {
            return "'" + id + "' is a game's id, or a word /hcm play keeps for itself.";
        }
        if (TrialText.keptWord(id)) {
            return "'" + id + "' is a word /hcm games feature keeps for itself.";
        }
        if (games().game(id) != null) {
            return "'" + id + "' is another name for a game.";
        }
        if (store.taken(id)) {
            return "There's already a course called '" + id + "'.";
        }
        if (games().resolve(id) != null) {
            return "'" + id + "' already opens something with /hcm play.";
        }
        return null;
    }

    /** This week's course among the open ones: the pinned one, or the week's pick. */
    public String courseOfWeek() {
        return courseOfWeek(openCourses());
    }

    private String courseOfWeek(List<Course> open) {
        List<String> ids = new ArrayList<>();
        String pinned = null;
        for (Course c : open) {
            ids.add(c.id());
            if (c.pinned()) {
                pinned = c.id();
            }
        }
        return CourseOfWeek.pick(ids, weekKey(), pinned);
    }

    /** Whether {@code courseId} is today's featured pick: by its own id, or the whole game pinned. */
    public boolean featured(String courseId) {
        return featured(games().featured()::isFeatured, courseId);
    }

    /**
     * Whether {@code courseId} is today's pick, asking {@code isFeatured} about a play id: the
     * course by its own id, or every course when the game itself ({@code trials}) is the pick.
     */
    static boolean featured(Predicate<String> isFeatured, String courseId) {
        return isFeatured.test(courseId) || isFeatured.test(SPEC.id());
    }

    /** This week's key (the same week as the weekly quests). */
    long weekKey() {
        DayOfWeek start = DayOfWeek.MONDAY;
        try {
            var quests = ctx.plugin().config().quests();
            if (quests != null && quests.weekStartsOn() != null) {
                start = quests.weekStartsOn();
            }
        } catch (RuntimeException e) {
            // the default week
        }
        return ctx.plugin().clock().weekKey(start);
    }

    private long today() {
        return ctx.plugin().clock().dayKey();
    }

    // ---- what the screens show --------------------------------------------------------------

    /** A course's tile: "River Run (Boat · Medium) - best 1:02.3", its rules, the record, what it pays. */
    public ItemStack courseTile(Player viewer, Course c, String courseOfWeek) {
        Long best = best(viewer, c.id());
        GamesDao.ScoreRow record = record(c.id());
        List<String> lore = new ArrayList<>();
        for (String line : c.kind().rules()) {
            lore.add("&7" + line);
        }
        lore.add(recordLine(record, viewer));
        int first = firstClear(c);
        if (first > 0 && !firstClearDone(viewer, c)) {
            lore.add("&7First finish: &6" + TrialText.tokens(first));
        }
        if (c.id().equals(courseOfWeek)) {
            lore.add("&6★ Course of the week");
        }
        lore.add("&eClick to play");
        return Menus.icon(icon(c.kind()), "&e" + c.name() + " &7(" + TrialText.label(c) + ") &7- "
                + (best == null ? "no time yet" : "best " + TrialText.time(best)), lore.toArray(new String[0]));
    }

    /** "&amp;7Record: 0:58.1 by Alex", or that there isn't one yet. */
    public String recordLine(GamesDao.ScoreRow record, Player viewer) {
        if (record == null) {
            return "&7No record yet - set one!";
        }
        boolean yours = viewer != null && viewer.getUniqueId().equals(record.player());
        return "&7Record: &f" + TrialText.time(record.score())
                + (yours ? " &7(yours)" : " &7by &f" + holder(record.player()));
    }

    /** The item a kind of course shows. */
    public static Material icon(TrialKind kind) {
        return switch (kind) {
            case PARKOUR -> Material.FEATHER;
            case ELYTRA -> Material.ELYTRA;
            case BOAT -> Material.OAK_BOAT;
        };
    }

    /** The player's best time on a course, or {@code null}. */
    public Long best(Player player, String courseId) {
        return games().scores().best(player.getUniqueId(), id(), Scores.course(courseId));
    }

    /** The course record, or {@code null}. */
    public GamesDao.ScoreRow record(String courseId) {
        return games().scores().record(id(), Scores.course(courseId), true);
    }

    /** This week's best on a course, or {@code null}. */
    public GamesDao.ScoreRow weekRecord(String courseId) {
        return games().scores().record(id(), Scores.week(courseId, weekKey()), true);
    }

    /** A record holder's name (names are fine in game), or "someone". */
    public String holder(UUID player) {
        try {
            OfflinePlayer p = Bukkit.getOfflinePlayer(player);
            String name = p.getName();
            return name == null ? "someone" : name;
        } catch (RuntimeException e) {
            return "someone";
        }
    }

    /** The first-clear reward for a course's tier. */
    public int firstClear(Course c) {
        return settings().firstClearFor(c.tier().id());
    }

    /** Whether the player has had a course's first-clear reward. */
    public boolean firstClearDone(Player player, Course c) {
        try {
            return games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.FIRST_CLEAR,
                    SkillRewards.firstClearRef(c.id()));
        } catch (SQLException e) {
            log().warning("Could not read time-trial rewards: " + e.getMessage());
            return false;
        }
    }

    /** The server-wide featured bonus. */
    public int featuredBonus() {
        return games().config().common().featuredBonus();
    }

    /** A course's all-time board. */
    public void showScores(Player player, String courseId, Runnable back) {
        games().screens().scores(player, this, Scores.course(courseId), true, back);
    }

    /** Whether the player is back from their last world game (a "Play again" waits for this). */
    public boolean home(Player player) {
        return sessions().home(player);
    }

    /** Start from the course screen: the gate, then the run. */
    public void startFromScreen(Player player, String courseId) {
        Refusal r = games().canOpen(player, this);
        if (r != null) {
            games().tell(player, r);
            return;
        }
        Course c = openCourse(courseId);
        if (c == null) {
            games().tell(player, Refusal.of("That course is closed right now."));
            return;
        }
        begin(player, c, false);
    }

    /** "Play again" on the result screen: the same course (a test again, for a test). */
    public void again(Player player, Result result) {
        if (result.test()) {
            Course c = course(result.courseId());
            if (c != null && player.hasPermission("hcm.games.admin")) {
                startTest(player, c);
            }
            return;
        }
        games().open(player, result.courseId(), null);
    }

    // ---- starting a run -----------------------------------------------------------------------

    /** An admin's test run: any complete course, open or not; records nothing. */
    void startTest(Player player, Course c) {
        if (!games().enabled(this)) {
            player.sendMessage(Text.of("&cTime trials are closed &7- games.enabled and games.trials.enabled must be on."));
            return;
        }
        Refusal r = games().canOpen(player, this);
        if (r != null) {
            games().tell(player, r);
            return;
        }
        List<String> problems = c.problems(games().config().common().worlds());
        if (!problems.isEmpty()) {
            player.sendMessage(Text.of("&cCan't test " + c.id() + " yet: &7" + String.join("; ", problems)));
            return;
        }
        begin(player, c, true);
    }

    /** Take the player to the course's start in a world session; the run begins when they're in. */
    private boolean begin(Player player, Course course, boolean test) {
        World world = course.ready() ? Bukkit.getWorld(course.world()) : null;
        if (world == null) {
            games().tell(player, Refusal.of("That course isn't ready right now."));
            return false;
        }
        Course.Spot s = course.start();
        Location start = new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        return sessions().enter(player, this, course.id(), start, p -> ready(p, course, test));
    }

    /** In, saved, cleared: the kit, the boat, and the countdown. */
    private void ready(Player p, Course course, boolean test) {
        end(p);
        TrialRun run = new TrialRun(p.getUniqueId(), course, test, COUNTDOWN_TICKS + 1);
        runs.put(p.getUniqueId(), run);
        giveKit(p, course.kind());
        p.setFallDistance(0f);
        if (course.kind() == TrialKind.BOAT) {
            seat(p, run, p.getLocation());
        }
        p.sendMessage(Text.of("&b" + course.name() + " &7(" + TrialText.label(course) + ")"
                + (test ? " &d- test run: nothing is recorded" : "")));
        int n = course.checkpoints().size();
        p.sendMessage(Text.of(n == 0 ? "&7Get to the finish. Ready..."
                : "&7Reach " + TrialText.checkpoints(n) + " in order, then the finish. Ready..."));
    }

    private void giveKit(Player p, TrialKind kind) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(0, KitItems.item(this, "checkpoint", Material.RECOVERY_COMPASS, "&eBack to checkpoint",
                "&7Takes you back to your last checkpoint.", "&7The clock keeps running."));
        if (kind == TrialKind.ELYTRA) {
            inv.setItem(1, rockets());
            ItemStack wings = KitItems.item(this, "elytra", Material.ELYTRA, "&bElytra", "&7Glide through the rings.");
            ItemMeta meta = wings.getItemMeta();
            if (meta != null) {
                meta.setUnbreakable(true);
                wings.setItemMeta(meta);
            }
            inv.setChestplate(wings);
        }
        inv.setItem(8, KitItems.item(this, "leave", Material.OAK_DOOR, "&cLeave game", "&7Click twice to leave.",
                "&7Your things come back."));
        inv.setHeldItemSlot(0);
    }

    private ItemStack rockets() {
        ItemStack r = KitItems.item(this, "firework", Material.FIREWORK_ROCKET, "&6Rockets &7- click while gliding",
                "&7A boost while you glide.", "&7You get " + ROCKETS + " more at every ring.");
        r.setAmount(ROCKETS);
        return r;
    }

    /** Top the rockets back up to {@value #ROCKETS}. */
    private void refillRockets(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if ("firework".equals(KitItems.action(it)) && id().equals(KitItems.gameId(it))) {
                it.setAmount(ROCKETS);
                inv.setItem(i, it);
                return;
            }
        }
        inv.setItem(1, rockets());
    }

    // ---- the tick: countdowns, clocks and the fair-play watch ---------------------------------

    private void tick() {
        long nanos = System.nanoTime();
        FairPlay.Stall stall = FairPlay.stall(lastTick, nanos);
        lastTick = nanos;
        if (runs.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (TrialRun run : new ArrayList<>(runs.values())) {
            Player p = Bukkit.getPlayer(run.player);
            Session s = p == null ? null : sessions().session(p);
            if (s == null || !id().equals(s.gameId())) {
                runs.remove(run.player);
                removeBoat(run, p);
                continue;
            }
            if (stall != null && run.running()) {
                run.stalls.add(stall); // the moves handled in its wake were timed late: no leg check across it
            }
            if (s.phase() != Session.Phase.ACTIVE) {
                continue;
            }
            run.ticks++;
            switch (run.phase) {
                case COUNTDOWN -> countdown(p, run, now);
                case RUNNING -> running(p, run, now);
                case DONE -> {
                    // waiting for the session to end
                }
            }
        }
    }

    private void countdown(Player p, TrialRun run, long now) {
        boolean boat = run.course.kind() == TrialKind.BOAT;
        if (boat) {
            if (seated(p, run)) {
                run.boat.setVelocity(new Vector());
            } else if (now >= run.reseatUntil) {
                seat(p, run, p.getLocation());
            }
        }
        int left = --run.countdown;
        if (left > 0 && left % 20 == 0) {
            title(p, "&e" + (left / 20), "&7Get ready", 20);
            ping(p, 1.0f);
        }
        if (left > 0) {
            return;
        }
        Point here = position(p, run);
        Point start = run.course.start().point();
        double off = boat ? here.flatDistance(start) : here.distance(start);
        if (off > FairPlay.START_RADIUS) {
            run.countdown = COUNTDOWN_TICKS + 1;
            p.sendMessage(Text.of("&eStay at the start until it says Go! &7Counting again."));
            toStart(p, run);
            return;
        }
        run.progress = new Progress(run.course, here, System.nanoTime());
        run.phase = TrialRun.Phase.RUNNING;
        title(p, "&aGo!", "", 15);
        ping(p, 2.0f);
        watch(p, run);
    }

    private void running(Player p, TrialRun run, long now) {
        watch(p, run);
        if (run.suspended) {
            long waited = now - run.lastReset;
            if (waited >= (run.expect == null ? SUSPEND_TICKS : WAIT_TICKS)) {
                sendBack(p, run, 0); // moved by someone else and not sent back yet, or our teleport never came
            }
        } else if (run.backDue) {
            sendBack(p, run, RESET_GAP); // clears backDue only once it really sends them back
        } else if (run.course.kind() == TrialKind.BOAT && now >= run.reseatUntil && !seated(p, run)) {
            sendBack(p, run, RESET_GAP);
        }
        if (run.phase == TrialRun.Phase.RUNNING && run.ticks % CLOCK_EVERY == 0) {
            p.sendActionBar(Text.of(clockLine(run)));
        }
    }

    /**
     * The fair-play watch, at Go and on every tick: flying, a changed game mode, a potion effect,
     * a changed walk speed or movement attribute — the run won't count, and the player hears it
     * once.
     */
    private void watch(Player p, TrialRun run) {
        if (run.voided != null) {
            return;
        }
        String why = p.getGameMode() != GameMode.ADVENTURE ? FairPlay.GAME_MODE
                : p.getAllowFlight() || p.isFlying() ? FairPlay.FLYING
                : !p.getActivePotionEffects().isEmpty() ? FairPlay.EFFECT
                : FairPlay.movement(p.getWalkSpeed(), movementStats(p));
        if (why != null) {
            run.voided = why;
            p.sendMessage(Text.of("&cThis run won't count &7- " + why + ". Finish it for fun, or use Leave game."));
            Sounds.miss(p);
        }
    }

    /** The movement attributes the watch reads: base values and modifier keys. */
    private static List<FairPlay.Stat> movementStats(Player p) {
        List<FairPlay.Stat> out = new ArrayList<>(5);
        for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.STEP_HEIGHT,
                Attribute.GRAVITY, Attribute.SAFE_FALL_DISTANCE)) {
            AttributeInstance in = p.getAttribute(a);
            if (in == null) {
                continue;
            }
            List<String> modifiers = new ArrayList<>();
            for (AttributeModifier m : in.getModifiers()) {
                modifiers.add(m.getKey().toString());
            }
            out.add(new FairPlay.Stat(a.getKey().toString(), in.getBaseValue(), modifiers));
        }
        return out;
    }

    /** "0:21.4 · 2/5 checkpoints", or "on to the finish!". */
    private static String clockLine(TrialRun run) {
        String time = TrialText.time(run.elapsedMs(System.nanoTime()));
        int got = run.progress.reachedCheckpoints();
        int of = run.progress.checkpoints();
        String where = got >= of ? "on to the finish!" : got + "/" + of + " checkpoints";
        String head = run.test ? "&dTest &e" : run.voided != null ? "&c" : "&e";
        return head + time + " &7· " + where + (run.voided != null ? " &8(won't count)" : "");
    }

    // ---- moves ------------------------------------------------------------------------------

    /** The countdown holds a player on foot still, but lets them look around (no real teleport). */
    private void hold(PlayerMoveEvent e) {
        if (runs.isEmpty()) {
            return;
        }
        TrialRun run = runs.get(e.getPlayer().getUniqueId());
        if (run == null || run.phase != TrialRun.Phase.COUNTDOWN || run.course.kind() == TrialKind.BOAT
                || !e.hasExplicitlyChangedPosition()) {
            return;
        }
        Location held = e.getFrom().clone();
        held.setYaw(e.getTo().getYaw());
        held.setPitch(e.getTo().getPitch());
        e.setTo(held);
    }

    private void moved(PlayerMoveEvent e) {
        if (runs.isEmpty()) {
            return;
        }
        Player p = e.getPlayer();
        TrialRun run = runs.get(p.getUniqueId());
        if (run == null || !run.running() || run.suspended || run.course.kind() == TrialKind.BOAT) {
            return;
        }
        Location to = e.getTo();
        if (!advance(p, run, to)) {
            return;
        }
        Point at = point(to);
        double floor = FairPlay.fallY(run.course, run.progress.lastCheckpoint(), settings().fallDepth());
        if (!Double.isNaN(floor) && at.y() < floor) {
            run.backDue = true;
        } else if (run.course.kind() == TrialKind.ELYTRA && p.isInWater() && !FairPlay.safeLanding(run.course, at)) {
            run.backDue = true;
        }
    }

    private void boatMoved(VehicleMoveEvent e) {
        if (boats.isEmpty()) {
            return;
        }
        UUID rider = boats.get(e.getVehicle().getUniqueId());
        TrialRun run = rider == null ? null : runs.get(rider);
        Player p = rider == null ? null : Bukkit.getPlayer(rider);
        if (run == null || p == null || !run.running() || run.suspended || !seated(p, run)) {
            return;
        }
        Location to = e.getTo();
        if (advance(p, run, to) && run.course.fallY() != null && to.getY() < run.course.fallY()) {
            run.backDue = true;
        }
    }

    /**
     * Feed one move to the run's progress: checkpoints reached are announced, the finish ends the
     * run.
     *
     * @return whether the run is still going
     */
    private boolean advance(Player p, TrialRun run, Location to) {
        if (to.getWorld() == null || !to.getWorld().getName().equals(run.course.world())) {
            return true;
        }
        for (Progress.Reached r : run.progress.move(point(to), System.nanoTime())) {
            if (r.finish()) {
                finish(p, run, r.nanos());
                return false;
            }
            reached(p, run, r.index());
        }
        return true;
    }

    private void reached(Player p, TrialRun run, int index) {
        int of = run.course.checkpoints().size();
        String time = TrialText.time(run.elapsedMs(run.progress.times()[index]));
        title(p, "", "&aCheckpoint " + (index + 1) + " of " + of + " &7- " + time, 25);
        ping(p, 1.4f + 0.4f * (index + 1) / Math.max(1, of));
        if (run.course.kind() == TrialKind.ELYTRA) {
            refillRockets(p);
        }
    }

    /** The run's own teleport arrived (moves count again from there); anyone else's suspends it. */
    private void teleported(PlayerTeleportEvent e) {
        if (runs.isEmpty()) {
            return;
        }
        TrialRun run = runs.get(e.getPlayer().getUniqueId());
        if (run == null) {
            return;
        }
        Location to = e.getTo();
        if (run.expect != null && to.getWorld() != null && to.getWorld().equals(run.expect.getWorld())
                && to.distance(run.expect) <= MATCH) {
            run.expect = null;
            run.suspended = false;
            if (run.progress != null) {
                run.progress.jump(point(to), System.nanoTime());
            }
            return;
        }
        if (run.running() && e.getCause() != PlayerTeleportEvent.TeleportCause.DISMOUNT) {
            run.suspended = true; // nothing counts from where they were put; the run sends them back
            run.lastReset = Bukkit.getCurrentTick();
        }
    }

    /** An elytra run that lands (or hits water) outside a checkpoint goes back to its last one. */
    private void glide(EntityToggleGlideEvent e) {
        if (runs.isEmpty() || e.isGliding() || !(e.getEntity() instanceof Player p)) {
            return;
        }
        TrialRun run = runs.get(p.getUniqueId());
        if (run == null || !run.running() || run.suspended || run.course.kind() != TrialKind.ELYTRA) {
            return;
        }
        if (!FairPlay.safeLanding(run.course, point(p.getLocation()))) {
            run.backDue = true;
        }
    }

    /** Getting out of the race boat (the guard keeps them in): back to the last checkpoint. */
    private void triedToLeave(boolean cancelled, Entity who, Entity vehicle) {
        if (boats.isEmpty() || !cancelled || !(who instanceof Player p)) {
            return;
        }
        TrialRun run = runs.get(p.getUniqueId());
        if (run == null || run.boat == null || !run.boat.getUniqueId().equals(vehicle.getUniqueId())) {
            return;
        }
        if (run.running()) {
            if (Bukkit.getCurrentTick() - run.lastReset >= DISMOUNT_GAP) {
                run.backDue = true;
            }
        } else if (run.phase == TrialRun.Phase.COUNTDOWN) {
            p.sendActionBar(Text.of("&7Wait for the countdown."));
        }
    }

    // ---- going back ---------------------------------------------------------------------------

    /**
     * Send the run back to its last checkpoint (or the start), facing the next one; the clock
     * keeps running. Nothing between here and there counts: moves are held until the teleport
     * lands.
     *
     * @param gap ignore this if the run went back fewer ticks ago than this (0 = always)
     */
    private void sendBack(Player p, TrialRun run, long gap) {
        long now = Bukkit.getCurrentTick();
        if (gap > 0 && now - run.lastReset < gap) {
            return;
        }
        run.backDue = false;
        run.lastReset = now;
        int last = run.progress == null ? -1 : run.progress.lastCheckpoint();
        Location at = backTo(run, last);
        if (at == null || !move(p, run, at)) {
            return;
        }
        if (run.course.kind() == TrialKind.ELYTRA) {
            refillRockets(p);
            Location here = p.getLocation();
            if (last >= 0 && here.getWorld() != null && here.getWorld().equals(at.getWorld())
                    && here.distance(at) < 1) {
                p.setGliding(true); // a ring hangs in the air: carry on gliding from it
            }
        }
        p.sendActionBar(Text.of(last >= 0 ? "&eBack to checkpoint " + (last + 1) : "&eBack to the start"));
        ping(p, 0.8f);
    }

    /** Back to the start for another countdown. */
    private void toStart(Player p, TrialRun run) {
        Location at = backTo(run, -1);
        if (at != null) {
            run.lastReset = Bukkit.getCurrentTick();
            move(p, run, at);
        }
    }

    /** The run's own teleport (a re-seat for a boat), marked so it isn't taken for anyone else's. */
    private boolean move(Player p, TrialRun run, Location at) {
        run.expect = at;
        run.suspended = true;
        boolean ok = run.course.kind() == TrialKind.BOAT ? reseat(p, run, at) : sessions().teleport(p, at);
        if (!ok) {
            run.expect = null;
            run.suspended = false;
            return false;
        }
        if (run.course.kind() != TrialKind.BOAT) {
            p.setVelocity(new Vector());
            p.setFallDistance(0f);
        }
        return true;
    }

    /** Where going back to checkpoint {@code last} (-1 = the start) puts the player, facing onward. */
    private Location backTo(TrialRun run, int last) {
        World w = Bukkit.getWorld(run.course.world());
        Course c = run.course;
        if (w == null || c.start() == null) {
            return null;
        }
        if (last < 0 || last >= c.checkpoints().size()) {
            Course.Spot s = c.start();
            return new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        }
        Course.Mark m = c.checkpoints().get(last);
        List<Course.Mark> targets = c.targets();
        Point next = last + 1 < targets.size() ? targets.get(last + 1).center() : m.center();
        return new Location(w, m.x(), m.y(), m.z(), Geometry.yawToward(m.center(), next), 0f);
    }

    // ---- boats --------------------------------------------------------------------------------

    /** Seat the player in a fresh boat of their own at {@code at}. */
    private void seat(Player p, TrialRun run, Location at) {
        removeBoat(run, p);
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        OakBoat boat = w.spawn(at, OakBoat.class, b -> WorldEntities.tag(b, this, p.getUniqueId()));
        run.boat = boat;
        boats.put(boat.getUniqueId(), p.getUniqueId());
        boat.addPassenger(p);
        // Seated: done. Not seated (another plugin stopped the spawn or the ride): try again in a second, not every tick.
        run.reseatUntil = seated(p, run) ? 0 : Bukkit.getCurrentTick() + 20;
    }

    /** Re-seat (R3.13): our own dismount, the old boat gone, our teleport, a new boat, seated. */
    private boolean reseat(Player p, TrialRun run, Location at) {
        run.reseatUntil = Bukkit.getCurrentTick() + WAIT_TICKS;
        sessions().ownDismount(p, () -> removeBoat(run, p));
        if (!sessions().teleport(p, at)) {
            run.reseatUntil = 0;
            return false;
        }
        seatWhenThere(p, run, at, 10);
        return true;
    }

    /** Seat the player once their teleport has landed (at once when the chunk was loaded). */
    private void seatWhenThere(Player p, TrialRun run, Location at, int tries) {
        if (runs.get(p.getUniqueId()) != run || !p.isOnline()) {
            return;
        }
        Location here = p.getLocation();
        if (here.getWorld() != null && here.getWorld().equals(at.getWorld()) && here.distance(at) < 1) {
            seat(p, run, at);
            return;
        }
        if (tries > 0) {
            games().later(this, 3, () -> seatWhenThere(p, run, at, tries - 1));
        }
    }

    private static boolean seated(Player p, TrialRun run) {
        return run.boat != null && run.boat.isValid() && run.boat.getPassengers().contains(p);
    }

    /** Remove the run's boat (never dropped as an item), letting its rider out as our own dismount. */
    private void removeBoat(TrialRun run, Player p) {
        Entity b = run.boat;
        if (b == null) {
            return;
        }
        run.boat = null;
        boats.remove(b.getUniqueId());
        Runnable gone = () -> {
            b.eject();
            b.remove();
        };
        if (p != null && p.isOnline()) {
            sessions().ownDismount(p, gone);
        } else {
            gone.run();
        }
    }

    // ---- the finish -------------------------------------------------------------------------

    private void finish(Player p, TrialRun run, long nanos) {
        run.phase = TrialRun.Phase.DONE;
        long ms = run.elapsedMs(nanos);
        boolean stale = FairPlay.stale(run.course.rev(), run.layout, course(run.course.id()));
        TimeTrialsSettings s = settings();
        int tooFast = FairPlay.tooFast(run.course, run.progress.startNanos(), run.progress.times(),
                run.progress.reachedTargets(), run.stalls);
        FairPlay.Verdict verdict = FairPlay.judge(run.test, run.voided, stale, ms,
                run.course.minSecondsOr(s.minSeconds()), tooFast);
        String name = run.course.name();
        p.sendMessage(Text.of("&b" + name + ": &f" + TrialText.time(ms)));
        TrialFinish.Summary summary = TrialFinish.Summary.NONE;
        switch (verdict.kind()) {
            case TEST -> {
                p.sendMessage(Text.of("&dTest run &7- nothing was recorded. " + (verdict.reason() == null
                        ? "It would have counted." : "It wouldn't have counted: " + verdict.reason() + ".")));
                title(p, "&a" + TrialText.time(ms), "&dTest run", 40);
                Sounds.received(p);
            }
            case VOID, STALE -> {
                p.sendMessage(Text.of("&cThat run didn't count. &7(" + verdict.reason() + ")"));
                title(p, "&f" + TrialText.time(ms), "&cThat run didn't count", 40);
                Sounds.miss(p);
            }
            case COUNTED -> summary = TrialFinish.settle(verdict, finishedRun(run, ms, s), ledger(p, run, s, ms));
        }
        Long best = verdict.counts() ? best(p, run.course.id()) : null;
        Result result = new Result(run.course.id(), name, ms, verdict.counts(), run.test, verdict.reason(),
                summary.course().personalBest(), summary.course().record(), best, summary.earned());
        UUID id = p.getUniqueId();
        games().later(this, 1, () -> {
            Player q = Bukkit.getPlayer(id);
            Session session = q == null ? null : sessions().session(q);
            if (session != null && id().equals(session.gameId())) {
                sessions().leave(q, EndReason.FINISH);
            }
        });
        showResult(id, result, RESULT_TRIES);
    }

    private TrialFinish.Run finishedRun(TrialRun run, long ms, TimeTrialsSettings s) {
        Course c = run.course;
        return new TrialFinish.Run(c.id(), c.name(), ms, today(), weekKey(), c.id().equals(courseOfWeek()),
                featured(c.id()), s.firstClearFor(c.tier().id()), s.weeklyBestBonus(), s.courseOfWeekBonus(),
                featuredBonus());
    }

    /** Where a counted run is recorded and paid: the scores, the finish lines, the capped rewards. */
    private TrialFinish.Ledger ledger(Player p, TrialRun run, TimeTrialsSettings s, long ms) {
        return new TrialFinish.Ledger() {
            @Override
            public ScoreResult submit(String board, long time) {
                return games().scores().submit(p.getUniqueId(), id(), board, time, true);
            }

            @Override
            public void announce(ScoreResult course, ScoreResult week, boolean firstFinish) {
                announceTo(p, run.course, ms, course, week, firstFinish);
            }

            @Override
            public boolean firstClearPaid() {
                return firstClearDone(p, run.course);
            }

            @Override
            public int pay(RewardKind kind, String ref, int tokens, String detail) {
                return games().rewards().pay(p, TimeTrials.this, run.course.kind().source(), kind, ref, tokens,
                        s.dailyCap(), detail);
            }
        };
    }

    /** The finish lines: your best, the record (and who holds it), this week's best. */
    private void announceTo(Player p, Course c, long ms, ScoreResult course, ScoreResult week, boolean firstFinish) {
        String sub;
        if (course.personalBest()) {
            p.sendMessage(Text.of(TrialText.bestLine(c.name(), course.previous(), firstFinish)));
            sub = "&eNew best!";
        } else {
            String yours = course.previous() == null ? "" : TrialText.time(course.previous());
            p.sendMessage(Text.of("&7Your best: &f" + yours));
            sub = "&7Your best: " + yours;
        }
        if (course.record()) {
            p.sendMessage(Text.of("&6★ New course record!"));
            sub = "&6Course record!";
        } else {
            p.sendMessage(Text.of(recordLine(record(c.id()), p)));
        }
        if (week.record() && !course.record()) {
            p.sendMessage(Text.of("&e★ Best time this week!"));
        }
        title(p, "&a" + TrialText.time(ms), sub, 40);
        if (course.personalBest() || week.record()) {
            Sounds.won(p);
        } else {
            Sounds.received(p);
        }
    }

    /** Once the player is home (the return teleport done), the result screen with "Play again". */
    private void showResult(UUID id, Result result, int tries) {
        games().later(this, RESULT_EVERY, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            if (!home(p)) {
                if (tries > 0) {
                    showResult(id, result, tries - 1);
                }
                return;
            }
            InventoryType open = p.getOpenInventory().getType();
            if (open == InventoryType.CRAFTING || open == InventoryType.CREATIVE) {
                new ResultMenu(ctx.plugin(), this, result, p).open(p);
            }
        });
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Forget the player's run (their session ended, or they left). */
    private void end(Player player) {
        TrialRun run = runs.remove(player.getUniqueId());
        if (run != null) {
            removeBoat(run, player);
        }
    }

    /** Where the run is: the boat on a boat course, the player's feet otherwise. */
    private static Point position(Player p, TrialRun run) {
        Entity where = run.boat != null && run.boat.isValid() ? run.boat : p;
        return point(where.getLocation());
    }

    private static Point point(Location l) {
        return new Point(l.getX(), l.getY(), l.getZ());
    }

    private static void title(Player p, String big, String small, int stayTicks) {
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small), Title.Times.times(Duration.ZERO,
                    Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(200))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    private static void ping(Player p, float pitch) {
        try {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }

    GamesService games() {
        return ctx.games();
    }

    HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    private WorldSessions sessions() {
        return ctx.games().sessions();
    }

    private Logger log() {
        HomeCraftManagement plugin = ctx.plugin();
        return plugin != null ? plugin.getLogger() : Logger.getLogger("HomeCraftManagement");
    }
}
