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
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.games.world.WorldSessions;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
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
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
 *
 * <p><b>Fresh courses</b> (GEN-SPEC §5, R6, the weekly addendum). Fresh Courses writes its parkour,
 * Sky Rings and ice boat courses as ordinary rows with a {@code gen:} tag, and this engine runs them
 * unchanged; only a few things differ. A generated course is open only while the Fresh Courses
 * engine vouches for its blocks ({@code GamesService#generated()}, the gate), and it comes first on
 * the Courses tab. A finish on the layout before the one now live still counts while that layout
 * stands (the "still standing" rule,
 * {@link FairPlay#stale(Course, int, Course, java.util.function.Predicate)}). Its time goes on that
 * set's own board, it earns stars for the Star Chart, and it pays the first finish of its set
 * instead of the week's best ({@link TrialFinish}). A course recalled into a Classics slot plays on
 * its original set's board. The words follow the cadence ("this week", "today"). And a Sky Rings
 * run's first fall-reset tells the player how to open their wings.
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
    /** The Dropper's hooks (EVENTS-DROPPER-SPEC §B.1.7): every dropper rule is in DropperRun. */
    private final DropperHooks drops = new DropperHooks(this);
    // ---- WP-R1: race mode, warm-ups and party races live in their own classes; these are hooks ----
    private final RaceMode race = new RaceMode(this);
    private final Warmups warmups = new Warmups(this);
    private final PartyRaces party = new PartyRaces(this);
    // ---- WP-CH: ride along (one passenger in the back of a boat); its logic is in Riders ----
    private final Riders riders = new Riders(RideAlong.live(this));

    /** WP-ADM: an admin's test runs of a Fresh Courses preview ("Play again" plays the preview again). */
    private final PreviewTests previews = new PreviewTests();

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
     * @param daily        what a Fresh course's run showed besides, or {@code null} (hand-built)
     * @param bonks        a dropper run's bonks (EVENTS-DROPPER-SPEC §B.1.1), or -1 for any other course
     */
    public record Result(String courseId, String courseName, long ms, boolean counted, boolean test, String reason,
                         boolean personalBest, boolean record, Long best, int earned, Daily daily, int bonks) {

        /** A result with no Dropper bonk count (any course but a dropper: {@code bonks} -1). */
        public Result(String courseId, String courseName, long ms, boolean counted, boolean test, String reason,
                      boolean personalBest, boolean record, Long best, int earned, Daily daily) {
            this(courseId, courseName, ms, counted, test, reason, personalBest, record, best, earned, daily, -1);
        }

        /** Whether it was a dropper run (its bonks are counted). */
        public boolean dropper() {
            return bonks >= 0;
        }

        /** A hand-built course's result. */
        public Result(String courseId, String courseName, long ms, boolean counted, boolean test, String reason,
                      boolean personalBest, boolean record, Long best, int earned) {
            this(courseId, courseName, ms, counted, test, reason, personalBest, record, best, earned, null);
        }
    }

    /**
     * What a Fresh course's run showed (GEN-SPEC §5.4).
     *
     * @param board     the set's own board ("this week's best"), which the result screen reads
     * @param stars     the stars it earned (0 when it didn't count)
     * @param weekStars the Star Chart total after it, or -1 when not known
     * @param goldMs    the layout's 3-star time
     * @param silverMs  its 2-star time
     * @param cadence   the words the screen uses ({@link GenCopy#words}): the set's length in days
     *                  ("this week", "today"), or {@link GenCopy#CLASSIC} for a recalled course
     * @param code      the course's code ("HARD-40"), or {@code null}
     */
    public record Daily(String board, int stars, long weekStars, long goldMs, long silverMs, int cadence,
                        String code) {

        /** A weekly set with no code (the shape before cadences). */
        public Daily(String board, int stars, long weekStars, long goldMs, long silverMs) {
            this(board, stars, weekStars, goldMs, silverMs, 7, null);
        }
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
        if (warmups.offer(player, c)) { // WP-R1 (D3): "Warm up (3:00)" or "Go straight to the timed run"
            return true;
        }
        return begin(player, c, false);
    }

    /**
     * One {@code /api/arcade} entry per open course: its kind, tier and record (no names unless
     * allowed), and the board behind it for its {@code top} list. A Fresh course's record is its
     * set's board's, with the set's first and last day, cadence, when the next set is due and its
     * star times (never its seed, rev or half; the course code and short seed come from Fresh
     * Courses' own {@code fresh}). A course recalled into a Classics slot has no next set: its
     * {@code classic} window says when it goes.
     */
    @Override
    public void feed(FeedWriter out) {
        List<Course> open = new ArrayList<>(openCourses());
        open.sort(Comparator.comparing(Course::id));
        for (Course c : open) {
            String board = board(c);
            GamesDao.ScoreRow r = recordOn(board);
            Long ms = r == null ? null : r.score();
            Long at = r == null ? null : r.at();
            String who = r != null && out.showNames() ? holder(r.player()) : null;
            if (c.generated()) {
                GenTag t = c.gen();
                out.course(c.id(), c.name(), c.kind().id(), c.tier().id(), ms, at, who, new FeedWriter.Daily(
                        t.date().toString(), t.recalled() ? 0 : games().generated().nextChangeAt(),
                        t.goldMs() > 0 ? t.goldMs() : null, t.silverMs() > 0 ? t.silverMs() : null, t.cadence(),
                        t.date().plusDays(t.cadence() - 1L).toString()));
            } else {
                out.course(c.id(), c.name(), c.kind().id(), c.tier().id(), ms, at, who);
            }
            out.board(c.id(), id(), board, true, "ms");
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
        g.on(this, EntityDamageEvent.class, EventPriority.MONITOR, false, drops::hurt); // a dropper's bonk
        g.on(this, BlockFromToEvent.class, EventPriority.LOW, true, drops::flow); // a dropper's pools never flow out
        g.every(this, 1, 1, this::tick);
        party.start(); // WP-R1 (D4)
        riders.boarded(this::hadRider); // WP-CH: a run with a rider at any point (#12)
    }

    /** The framework ends the sessions; here the boats go and the runs are forgotten. */
    @Override
    public void stop() {
        race.stop(); // WP-R1
        party.stop();
        riders.stop(); // WP-CH
        for (TrialRun run : new ArrayList<>(runs.values())) {
            removeBoat(run, Bukkit.getPlayer(run.player));
            drops.end(run, Bukkit.getPlayer(run.player));
        }
        runs.clear();
        boats.clear();
        cache = null;
        lastTick = 0;
    }

    @Override
    public void onQuit(Player player) {
        party.quit(player); // WP-R1: a disconnect is a DNF, and the party passes on
        race.left(player, EndReason.DISCONNECT);
        warmups.forget(player); // WP-R1 (D3): a warm-up chosen before the quit never carries over
        end(player);
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        race.left(player, reason); // WP-R1: RaceLink.left
        end(player);
        riders.sessionEnded(player.getUniqueId()); // WP-CH: a driver's rider goes too; a rider's driver carries on
    }

    /** Fell out of the world, or someone else moved the player a little way: back to the last checkpoint. */
    @Override
    public void onVoid(Player player) {
        TrialRun run = runs.get(player.getUniqueId());
        if (run != null && race.onVoid(player, run)) { // WP-R1: a parked racer goes back to the stand
            return;
        }
        if (run != null && run.drop != null && drops.bonk(player, run, DropperRules.Why.VOID)) {
            return; // a dropper's bonk: back to the top of this level
        }
        if (run != null && run.running() && sendBack(player, run, 0)) {
            wingsTip(player, run);
        }
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        TrialRun run = runs.get(player.getUniqueId());
        if (run == null || drops.kit(player, run, action)) {
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
            case Warmup.TIMED -> warmups.timed(player, run); // WP-R1 (D3)
            case Warmup.READY -> warmups.ready(player, run);
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
    public Course course(String id) {
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

    /**
     * The courses players can play: open, complete, in a Games world and — for a daily course —
     * vouched for by the Fresh Courses engine (the gate, GEN-SPEC R5). Daily courses first, in
     * slot order; then easiest first, then by name.
     */
    public List<Course> openCourses() {
        return open(courses(), this::gamesWorld, games().generated());
    }

    /**
     * {@link #openCourses()} as a pure filter, so a test can hand it the real rows and the real
     * Fresh Courses gate: every course of {@code courses} that is open, complete, in a Games world
     * and, when generated, {@code gate}-vouched, in {@link #sorted} order.
     */
    public static List<Course> open(Collection<Course> courses, Predicate<String> gamesWorld, GeneratedCourses gate) {
        List<Course> out = new ArrayList<>();
        for (Course c : courses) {
            if (c.enabled() && c.ready() && gamesWorld.test(c.world()) && gate.live(c.id(), c.gen())
                    && DropperLayout.problems(c).isEmpty()) { // a malformed dropper never opens
                out.add(c);
            }
        }
        return sorted(out);
    }

    /** Daily courses first, in slot order ({@link Slots#ALL}); then easiest first, then by name, then id. */
    static List<Course> sorted(List<Course> courses) {
        List<Course> out = new ArrayList<>(courses);
        out.sort(Comparator.comparingInt(TimeTrials::dailyOrder).thenComparing((Course c) -> c.tier().ordinal())
                .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)).thenComparing(Course::id));
        return out;
    }

    /** Where a course sorts among the daily ones: its slot's place, after them all when hand-built. */
    private static int dailyOrder(Course c) {
        if (!c.generated()) {
            return Integer.MAX_VALUE;
        }
        int i = Slots.ids().indexOf(c.id());
        return i < 0 ? Slots.ALL.size() : i;
    }

    /**
     * The board a course's times go on: a Fresh course's set board ({@code gfresh:<slot>:<edition>},
     * "this week's best"; a recalled course's is its original set's, with its old records), else its
     * all-time board.
     */
    public static String board(Course c) {
        return c.generated() ? GenBoards.day(c.gen()) : Scores.course(c.id());
    }

    /**
     * What a course's first clear is kept under: its id, or a Fresh course's slot (so a recalled
     * course never adds a first clear beyond its slot's once-ever one).
     */
    public static String firstClearId(Course c) {
        return c.generated() ? c.gen().slot() : c.id();
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

    @Override
    public void coursesChanged() {
        forget();
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
        return games().clock().weekKey(start); // the framework's clock: the plugin's, live
    }

    private long today() {
        return games().clock().dayKey();
    }

    // ---- what the screens show --------------------------------------------------------------

    /** A course's tile: "River Run (Boat · Medium) - best 1:02.3", its rules, the record, what it pays. */
    public ItemStack courseTile(Player viewer, Course c, String courseOfWeek) {
        if (c.generated()) {
            return dailyTile(viewer, c, courseOfWeek);
        }
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
        CupLink.Tile cup = CupLink.tile(games(), viewer, c); // Weekly Cup: read once for the lore and the NAME
        lore.addAll(cup.lines());
        lore.add("&eClick to play");
        return Menus.icon(icon(c.kind()), "&e" + c.name() + " &7(" + TrialText.label(c) + ") &7- "
                + (c.kind() == TrialKind.DROPPER ? TrialText.levels(DropperLayout.levels(c)) + " · " : "") // a kept dropper
                + (best == null ? "no time yet" : "best " + TrialText.time(best)) + cup.suffix(),
                lore.toArray(new String[0]));
    }

    /**
     * A Fresh course's tile (GEN-SPEC §5.4): "&amp;aEasy Parkour &amp;7- ★★☆ &amp;a(new this week)
     * &amp;8· &amp;7Course code EASY-4", its star times, your best and the set's best, and what its
     * first finish in the set pays. A course recalled into a Classics slot says it is a classic:
     * "&amp;6Classic: Hard Parkour (week of 5 Oct)", its code and that its old records are the ones
     * to beat.
     */
    private ItemStack dailyTile(Player viewer, Course c, String courseOfWeek) {
        GenTag t = c.gen();
        GamesService g = games();
        int cadence = GenCopy.words(t); // a Classic's board holds its original set's times: no "this week"
        int stars = DailyLookup.stars(g, viewer.getUniqueId(), t);
        String code = DailyLookup.code(g, t);
        List<String> lore = new ArrayList<>();
        if (t.recalled()) {
            lore.add("&7Its old records are the ones to beat.");
        } else {
            lore.add("&7" + GenCopy.schedule(cadence, DailyLookup.edition(g).rebuildDay(), null) + ".");
        }
        for (String line : c.kind().rules()) {
            lore.add("&7" + line);
        }
        lore.add(DailyText.starTimes(t.goldMs(), t.silverMs()));
        String board = board(c);
        Long best = bestOn(viewer, board);
        lore.add(DailyText.yourBest(cadence, best == null ? null : TrialText.time(best)));
        lore.add(setBestLine(recordOn(board), viewer, cadence));
        String first = DailyText.firstFinish(cadence, DailyLookup.freshClear(g, t),
                DailyLookup.freshClearPaid(g, viewer.getUniqueId(), id(), t));
        if (first != null) {
            lore.add(first);
        }
        if (c.id().equals(courseOfWeek)) {
            lore.add("&6★ Course of the week");
        }
        CupLink.Tile cup = CupLink.tile(g, viewer, c); // Weekly Cup: read once for the lore and the NAME
        lore.addAll(cup.lines());
        lore.add("&eClick to play");
        String fact = (c.kind() == TrialKind.DROPPER ? DailyText.levels(DropperLayout.levels(c)) + " · " : "")
                + DailyText.trialFact(cadence, stars);
        String name = t.recalled() ? "&6" + classicName(t, c.name()) + " &7- " + fact
                : DailyText.tabName(Slots.of(t.slot()), c.name(), fact, DailyLookup.current(g, t.slot()), cadence);
        return Menus.glint(Menus.icon(icon(c.kind()), name + DailyLookup.codeSuffix(code)
                + cup.suffix(), lore.toArray(new String[0])), stars >= 3);
    }

    /**
     * A recalled course's full name from its tag ("Classic: Hard Parkour (week of 5 Oct)", with
     * "(re-made)" when its row says so): the row's name may be cut to fit.
     */
    public static String classicName(GenTag t, String rowName) {
        Slots.Def d = Slots.of(t.slot());
        return GenCopy.classicName(d == null ? rowName : GenCopy.slotName(d, t.cadence()), t.cadence(), t.day(),
                rowName != null && rowName.contains("(re-made)"));
    }

    /**
     * "&amp;7This week's best: 0:58.1 by Alex" on a Fresh course, or that nobody has finished it yet
     * in this set (the words follow the set's cadence).
     */
    public String setBestLine(GamesDao.ScoreRow record, Player viewer, int cadence) {
        if (record == null) {
            return DailyText.setBest(cadence, null, null, false);
        }
        boolean yours = viewer != null && viewer.getUniqueId().equals(record.player());
        return DailyText.setBest(cadence, TrialText.time(record.score()), yours ? null : holder(record.player()),
                yours);
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
            case DROPPER -> Material.WATER_BUCKET;
        };
    }

    /** The player's best time on a course (a Fresh course: on its set's board), or {@code null}. */
    public Long best(Player player, String courseId) {
        Course c = course(courseId);
        return bestOn(player, c == null ? Scores.course(courseId) : board(c));
    }

    /** The course record (a Fresh course: its set's best), or {@code null}. */
    public GamesDao.ScoreRow record(String courseId) {
        Course c = course(courseId);
        return recordOn(c == null ? Scores.course(courseId) : board(c));
    }

    /** The player's best time on one board, or {@code null}. */
    public Long bestOn(Player player, String board) {
        return games().scores().best(player.getUniqueId(), id(), board);
    }

    /** The best time on one board, or {@code null}. */
    public GamesDao.ScoreRow recordOn(String board) {
        return games().scores().record(id(), board, true);
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

    /** Whether the player has had a course's first-clear reward (a Fresh course's: its slot's). */
    public boolean firstClearDone(Player player, Course c) {
        try {
            return games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.FIRST_CLEAR,
                    SkillRewards.firstClearRef(firstClearId(c)));
        } catch (SQLException e) {
            log().warning("Could not read time-trial rewards: " + e.getMessage());
            return false;
        }
    }

    /** The server-wide featured bonus. */
    public int featuredBonus() {
        return games().config().common().featuredBonus();
    }

    /** A course's all-time board (a Fresh course: its set's board). */
    public void showScores(Player player, String courseId, Runnable back) {
        Course c = course(courseId);
        games().screens().scores(player, this, c == null ? Scores.course(courseId) : board(c), true, back);
    }

    /** The live Fresh Courses gate and figures ({@code GamesService#generated()}). */
    public GeneratedCourses generated() {
        return games().generated();
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
        if (warmups.offer(player, c)) { // WP-R1 (D3)
            return;
        }
        begin(player, c, false);
    }

    /** "Play again" on the result screen: the same course (a test again, for a test). */
    public void again(Player player, Result result) {
        if (result.test()) {
            Runnable preview = previews.again(player.getUniqueId()); // WP-ADM: a preview's test, again
            if (preview != null && player.hasPermission("hcm.games.admin")) {
                preview.run();
                return;
            }
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
        previews.forget(player.getUniqueId()); // WP-ADM: "Play again" is this course's now
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

    /**
     * WP-ADM ({@code /hcm games gen test}): an admin's test run on a Fresh Courses preview, a course
     * with no row that stands in its slot's spare half. The same test run as any other (its start,
     * checkpoints, finish, clock and kit, the Dropper's practice drop offered), recording nothing;
     * "Play again" runs {@code again}.
     */
    public void testPreview(Player player, Course preview, Runnable again) {
        startTest(player, preview);
        previews.started(player.getUniqueId(), again);
    }

    /** Take the player to the course's start in a world session; the run begins when they're in. */
    boolean begin(Player player, Course course, boolean test) {
        World world = course.ready() ? Bukkit.getWorld(course.world()) : null;
        if (world == null) {
            games().tell(player, Refusal.of("That course isn't ready right now."));
            warmups.forget(player);
            return false;
        }
        if (race.refuseSolo(player, course.id())) { // WP-R1: a course held for a race
            warmups.forget(player);
            return false;
        }
        Course.Spot s = course.start();
        Location start = new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        boolean in = sessions().enter(player, this, course.id(), start, p -> ready(p, course, test));
        if (!in) {
            warmups.forget(player);
        }
        return in;
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
        p.sendMessage(Text.of(course.kind() == TrialKind.DROPPER ? DropperText.ready(DropperLayout.levels(course))
                : n == 0 ? "&7Get to the finish. Ready..."
                : "&7Reach " + TrialText.checkpoints(n) + " in order, then the finish. Ready..."));
        if (course.kind() == TrialKind.DROPPER) {
            run.drop = drops.start(p, run); // not collidable, and the practice drop's offer
        }
        warmups.begin(p, run); // WP-R1 (D3): the warm-up chosen, if any (never on a Dropper)
    }

    void giveKit(Player p, TrialKind kind) {
        if (kind == TrialKind.DROPPER) {
            drops.giveKit(p, DropperRun.Kit.DROP);
            return;
        }
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
        race.sweepArrivals(); // WP-R1: a racer whose entry was dropped on the way in never holds a race up
        if (++riderTicks % 20 == 0) {
            riders.second(); // WP-CH: a rider out of the boat is put back
        }
        if (runs.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (TrialRun run : new ArrayList<>(runs.values())) {
            Player p = Bukkit.getPlayer(run.player);
            Session s = p == null ? null : sessions().session(p);
            if (s == null || !id().equals(s.gameId())) {
                runs.remove(run.player);
                race.gone(run, p); // WP-R1
                removeBoat(run, p);
                drops.end(run, p);
                continue;
            }
            if (stall != null && run.running()) {
                run.stalls.add(stall); // the moves handled in its wake were timed late: no leg check across it
            }
            if (s.phase() != Session.Phase.ACTIVE) {
                continue;
            }
            run.ticks++;
            if (run.race != null && race.tick(p, run, now)) { // WP-R1: the grid, the stand, called off
                continue;
            }
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
        if (drops.countdown(p, run)) {
            return; // a dropper's practice drop is offered, or on: no 3-2-1 yet
        }
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
        if (run.warmup && warmups.tick(p, run, now)) { // WP-R1 (D3): its time ran out
            return;
        }
        watch(p, run);
        drops.running(p, run);
        if (run.suspended) {
            long waited = now - run.lastReset;
            if (waited >= (run.expect == null ? SUSPEND_TICKS : WAIT_TICKS)) {
                sendBack(p, run, 0); // moved by someone else and not sent back yet, or our teleport never came
            }
        } else if (run.backDue) {
            if (sendBack(p, run, RESET_GAP)) { // clears backDue only once it really sends them back
                wingsTip(p, run);
            }
        } else if (run.course.kind() == TrialKind.BOAT && now >= run.reseatUntil && !seated(p, run)) {
            sendBack(p, run, RESET_GAP);
        }
        if (run.phase == TrialRun.Phase.RUNNING && run.ticks % CLOCK_EVERY == 0) {
            p.sendActionBar(Text.of(run.warmup ? warmups.bar(run, now) // WP-R1 (D3)
                    : run.drop != null ? run.drop.clockLine(System.nanoTime()) : clockLine(run)));
        }
    }

    /**
     * The fair-play watch, at Go and on every tick: flying, a changed game mode, a potion effect,
     * a changed walk speed or movement attribute — the run won't count, and the player hears it
     * once.
     */
    void watch(Player p, TrialRun run) {
        if (run.voided != null || run.warmup) { // WP-R1 (D3): nothing in a warm-up counts anyway
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
                || !e.hasExplicitlyChangedPosition() || (run.drop != null && run.drop.letsGo())) {
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
        if (run != null && run.drop != null) {
            drops.moved(p, run, e.getTo()); // splashes, hops and the floor: DropperRun
            return;
        }
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
     * @return whether the player was sent back
     */
    private boolean sendBack(Player p, TrialRun run, long gap) {
        long now = Bukkit.getCurrentTick();
        if (gap > 0 && now - run.lastReset < gap) {
            return false;
        }
        if (run.drop != null) {
            run.backDue = false;
            return drops.back(p, run); // the top of the level it is on
        }
        run.backDue = false;
        run.lastReset = now;
        int last = run.progress == null ? -1 : run.progress.lastCheckpoint();
        Location at = race.reseat(run, backTo(run, last)); // WP-R1: clear of the other race boats
        if (at == null || !move(p, run, at)) {
            return false;
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
        return true;
    }

    /**
     * Sky Rings (GEN-SPEC §4.2): the first time a run falls back, how to open the wings — the one
     * thing a young flyer can't guess. Once per run.
     */
    private static void wingsTip(Player p, TrialRun run) {
        if (!run.wingsTip && wingsTipFor(run.course)) {
            run.wingsTip = true;
            p.sendMessage(Text.of(GenCopy.WINGS_TIP));
        }
    }

    /** Whether a course gets the wings tip: a generated elytra course (Sky Rings). */
    static boolean wingsTipFor(Course c) {
        return c != null && c.generated() && c.kind() == TrialKind.ELYTRA;
    }

    /** Back to the start for another countdown. */
    void toStart(Player p, TrialRun run) {
        Location at = backTo(run, -1);
        if (at != null) {
            run.lastReset = Bukkit.getCurrentTick();
            move(p, run, at);
        }
    }

    /** The run's own teleport (a re-seat for a boat), marked so it isn't taken for anyone else's. */
    boolean move(Player p, TrialRun run, Location at) {
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
    Location backTo(TrialRun run, int last) {
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
    void seat(Player p, TrialRun run, Location at) {
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
        if (seated(p, run)) {
            riders.seated(p, boat, run.course.id()); // WP-CH: the rider behind the driver, on every seat
        }
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

    static boolean seated(Player p, TrialRun run) {
        return run.boat != null && run.boat.isValid() && run.boat.getPassengers().contains(p);
    }

    /** Remove the run's boat (never dropped as an item), letting its rider out as our own dismount. */
    void removeBoat(TrialRun run, Player p) {
        Entity b = run.boat;
        if (b == null) {
            return;
        }
        run.boat = null;
        boats.remove(b.getUniqueId());
        Runnable boatGone = () -> {
            b.eject();
            b.remove();
        };
        Player rider = p == null ? null : riders.riderIn(b, p.getUniqueId()); // WP-CH: their getting out is ours too
        Runnable gone = rider == null ? boatGone : () -> sessions().ownDismount(rider, boatGone);
        if (p != null && p.isOnline()) {
            sessions().ownDismount(p, gone);
        } else {
            gone.run();
        }
    }

    // ---- the finish -------------------------------------------------------------------------

    void finish(Player p, TrialRun run, long nanos) {
        switch (RaceRun.route(run)) { // WP-R1: a warm-up lap (D3) and a race's line never reach the normal finish
            case WARMUP_LAP -> {
                warmups.lap(p, run, nanos);
                return;
            }
            case RACE -> {
                race.finish(p, run, nanos);
                return;
            }
            default -> {
                // a solo run: as it always was
            }
        }
        run.phase = TrialRun.Phase.DONE;
        long ms = run.elapsedMs(nanos);
        boolean stale = FairPlay.stale(run.course, run.layout, course(run.course.id()), games().generated()::standing);
        TimeTrialsSettings s = settings();
        int tooFast = FairPlay.tooFast(run.course, run.progress.startNanos(), run.progress.times(),
                run.progress.reachedTargets(), run.stalls);
        FairPlay.Verdict verdict = FairPlay.judge(run.test, run.voided, stale, ms,
                run.course.minSecondsOr(s.minSeconds()), tooFast);
        verdict = withRider(p, run, verdict); // WP-CH: rider_runs_count false makes a ride just for fun
        String name = run.course.name();
        GenTag tag = run.course.gen();
        String code = tag == null ? null : DailyLookup.code(games(), tag);
        p.sendMessage(Text.of("&b" + name + ": &f" + TrialText.time(ms)));
        if (code != null) {
            p.sendMessage(Text.of("&7" + GenCopy.courseCode(code))); // so players can ask for it back
        }
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
            case COUNTED -> summary = settleCounted(p, run, ms, verdict);
        }
        drops.finished(p, run, ms, verdict.counts(), summary.stars()); // bonks, the splash, a clean drop
        Long best = verdict.counts() ? bestOn(p, board(run.course)) : null;
        Daily daily = tag == null ? null : new Daily(board(run.course), summary.stars(), summary.weekStars(),
                tag.goldMs(), tag.silverMs(), GenCopy.words(tag), code);
        Result result = new Result(run.course.id(), name, ms, verdict.counts(), run.test, verdict.reason(),
                summary.course().personalBest(), summary.course().record(), best, summary.earned(), daily,
                run.drop == null ? -1 : run.drop.bonks());
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

    /**
     * A counted run recorded and paid: the boards, the rewards and what they tell the quests
     * ({@link TrialFinish}), then its Cup time. A solo run's finish and a party race's finish (WP-R1,
     * D4: each racer's run is also a normal counted run, once) both come through here, so anything a
     * counted run does belongs here. A Race Night heat never does.
     */
    TrialFinish.Summary settleCounted(Player p, TrialRun run, long ms, FairPlay.Verdict verdict) {
        TimeTrialsSettings s = settings();
        TrialFinish.Summary summary = TrialFinish.settle(verdict, finishedRun(run, ms, s), ledger(p, run, s, ms));
        // The Weekly Cup (WP-C): a counted, timed run, solo or a party race's. A Race Night heat never
        // comes here (RaceMode.finish: its start is a grid spot), so it never sets a Cup time.
        CupLink.finished(games(), p, run.course, ms, verdict, run.warmup);
        return summary;
    }

    /**
     * What a counted run tells the quests and achievements (EXTRAS E4, through the guarded
     * {@code GamesService#tellProgress}; {@link TrialFinish#settle} calls it once, last, for a counted
     * run only): the course finished, and on a Fresh course the stars it added, the week's top goal
     * and a whole set finished ({@link DailyLookup#freshProgress}).
     */
    private void progress(Player p, Course c, TrialFinish.Run run, TrialFinish.Summary summary) {
        boolean record = summary.course().record();
        games().tellProgress(g -> g.courseFinished(p, c.id(), c.generated(), record));
        TrialFinish.Daily d = run.daily();
        if (d != null && d.tag() != null) {
            DailyLookup.freshProgress(games(), p, d.tag(), summary.added(), d.weekKey(), d.goals());
        }
    }

    TrialFinish.Run finishedRun(TrialRun run, long ms, TimeTrialsSettings s) {
        Course c = run.course;
        TrialFinish.Daily daily = null;
        if (c.generated()) { // the set of the layout the run started on (GEN-SPEC §3.1), the week of today
            GenTag t = c.gen();
            GeneratedCourses g = games().generated();
            long week = DailyLookup.weekKey(games());
            daily = new TrialFinish.Daily(t, week, g.dailyClear(t.slot(), t.cadence()), g.goals(week));
        }
        return new TrialFinish.Run(c.id(), c.name(), ms, today(), weekKey(), c.id().equals(courseOfWeek()),
                featured(c.id()), s.firstClearFor(c.tier().id()), s.weeklyBestBonus(), s.courseOfWeekBonus(),
                featuredBonus(), daily);
    }

    /** Where a counted run is recorded and paid: the scores, the finish lines, the capped rewards. */
    TrialFinish.Ledger ledger(Player p, TrialRun run, TimeTrialsSettings s, long ms) {
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

            @Override
            public int payWhole(RewardKind kind, String ref, int tokens, String detail) {
                GenTag t = run.course.gen();
                return games().rewards().payWhole(p, TimeTrials.this, run.course.kind().source(), kind, ref, tokens,
                        s.dailyCap(), detail, GenCopy.clearLimit(GenCopy.words(t)));
            }

            @Override
            public GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
                return DailyLookup.addStars(games(), p.getUniqueId(), dayBoard, weekBoard, stars);
            }

            @Override
            public void finished(TrialFinish.Run counted, TrialFinish.Summary summary) {
                progress(p, run.course, counted, summary);
            }

            @Override
            public void stars(int stars, GamesDao.StarsAdded added) {
                GenTag t = run.course.gen();
                p.sendMessage(Text.of(DailyText.trialFinish(stars, t == null ? 0 : t.goldMs(),
                        t == null ? 0 : t.silverMs(), added == null ? -1 : added.weekTotal())));
            }

            @Override
            public int payGoal(String ref, int tokens, String detail) {
                return DailyLookup.payGoal(games(), p, ref, tokens, detail);
            }
        };
    }

    /** The finish lines: your best, the record (and who holds it), this week's best. */
    private void announceTo(Player p, Course c, long ms, ScoreResult course, ScoreResult week, boolean firstFinish) {
        if (c.generated()) {
            announceDaily(p, c, ms, course, firstFinish);
            return;
        }
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
        try {
            if (course.personalBest() || week.record()) {
                Sounds.won(p);
            } else {
                Sounds.received(p);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration (and has no registry off a server): the finish is recorded all the same
        }
    }

    /**
     * A Fresh course's finish lines: your best in this set, the set's best (and who holds it) on
     * its own board, in the set's words ("this week", "today"; a Classic's "on this course", its
     * board being its original set's). The stars line follows
     * ({@link TrialFinish}), then the course code.
     */
    private void announceDaily(Player p, Course c, long ms, ScoreResult set, boolean firstFinish) {
        int cadence = GenCopy.words(c.gen());
        String sub;
        if (set.personalBest()) {
            p.sendMessage(Text.of(set.previous() == null && firstFinish ? TrialText.bestLine(c.name(), null, true)
                    : DailyText.newBest(cadence, set.previous() == null ? null : TrialText.time(set.previous()))));
            sub = "&e" + GenCopy.yourBest(cadence) + "!";
        } else {
            String yours = set.previous() == null ? null : TrialText.time(set.previous());
            p.sendMessage(Text.of(DailyText.yourBest(cadence, yours)));
            sub = yours == null ? "" : "&7" + GenCopy.yourBest(cadence) + ": " + yours;
        }
        if (set.record()) {
            p.sendMessage(Text.of("&6★ " + GenCopy.bestOf(cadence) + " time!"));
            sub = "&6" + GenCopy.bestOf(cadence) + "!";
        } else {
            p.sendMessage(Text.of(setBestLine(recordOn(board(c)), p, cadence)));
        }
        title(p, "&a" + TrialText.time(ms), sub, 40);
        if (set.personalBest()) {
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
    void end(Player player) {
        TrialRun run = runs.remove(player.getUniqueId());
        if (run != null) {
            removeBoat(run, player);
            drops.end(run, player);
        }
    }

    /** Race mode's server side (WP-R1). */
    RaceMode raceMode() {
        return race;
    }

    /** WP-CH: ride along (one passenger in the back of a boat). */
    Riders riders() {
        return riders;
    }

    private long riderTicks;

    /** WP-CH: a run with a rider aboard is just for fun while {@code rider_runs_count} is false. */
    FairPlay.Verdict withRider(Player p, FairPlay.Verdict verdict) {
        return withRider(p, null, verdict);
    }

    /**
     * {@link #withRider(Player, FairPlay.Verdict)} for {@code run}: a rider aboard at any point of it
     * counts (latched on the run), not only one aboard at the line.
     */
    FairPlay.Verdict withRider(Player p, TrialRun run, FairPlay.Verdict verdict) {
        if (verdict.counts() && justForFun(p, run)) {
            return new FairPlay.Verdict(FairPlay.Kind.VOID, Riders.FUN_ONLY);
        }
        return verdict;
    }

    /**
     * WP-CH: whether the run is just for fun: {@code rider_runs_count} is false and a rider rode in
     * it (at any point: {@link TrialRun#hadRider}, or aboard now).
     */
    boolean justForFun(Player p, TrialRun run) {
        return !settings().riderRunsCount() && ((run != null && run.hadRider) || riders.funOnly(p.getUniqueId(), false));
    }

    /** A rider sat down behind {@code driver}: their run had a rider (latched). */
    private void hadRider(UUID driver) {
        TrialRun r = driver == null ? null : runs.get(driver);
        if (r != null) {
            r.hadRider = true;
        }
    }

    /** The warm-ups (WP-R1, D3). */
    Warmups warmups() {
        return warmups;
    }

    /** Whether the player is on a run now (racing, warming up, or parked on a race's stand). WP-CH. */
    public boolean onRun(UUID player) {
        return player != null && run(player) != null;
    }

    /**
     * The player's live run, or {@code null} (the Dropper's fall-damage hook finds it here; race mode
     * and warm-ups work on it).
     */
    TrialRun run(UUID player) {
        return runs.get(player);
    }

    /** Replace the player's run (race mode's re-grid: a new course from a new spot, the same race). */
    void replaceRun(TrialRun run) {
        runs.put(run.player, run);
    }

    /** Every live run. */
    Collection<TrialRun> liveRuns() {
        return runs.values();
    }

    /** Where the run is: the boat on a boat course, the player's feet otherwise. */
    static Point position(Player p, TrialRun run) {
        Entity where = run.boat != null && run.boat.isValid() ? run.boat : p;
        return point(where.getLocation());
    }

    // ---- race mode (EVENTS-DROPPER-SPEC §A.4.11, EVENTS-OWNER-DECISIONS D3-D4) ------------------
    //
    // The C1 contract Race Night (WP-R2) and party races (WP-R1) code against. WP-R1 built the
    // bodies in RaceMode (the race runs), Warmups (D3) and PartyRaces (D4); a run without a race and
    // without a warm-up is byte for byte the run it always was.

    /** What the race-mode entry points threw until WP-R1 built them (kept for the contract). */
    public static final String NOT_BUILT = "not built yet";

    /**
     * Start {@code courseId} after the warm-up choice (D3): "Warm up (3:00)" first, or "Go straight to
     * the timed run". The gate runs again, as for Start.
     */
    public void startRun(Player player, String courseId, boolean warmUp) {
        warmups.choose(player, courseId, warmUp);
    }

    /** "Race with friends" on a course screen, or {@code /hcm play race <course>} (D4): the party's lobby. */
    public void raceWithFriends(Player player, String courseId, Runnable back) {
        party.open(player, courseId, back);
    }

    /** The party races (D4), for their screens. */
    public PartyRaces party() {
        return party;
    }

    /**
     * Seat a racer: a {@code trials} session with ref = {@code base}'s id at the {@code grid} spot (a
     * fresh tagged oak boat on a boat course), held until {@code link.goTick()}. The run is judged
     * against {@code raced} (the base course from the grid spot, with its laps) but its staleness
     * against {@code base} and the still-standing rule; at the line it calls {@code link.finished},
     * and only when {@link RaceLink#normalRun()} also the course's normal finish. With a shared
     * warm-up ({@link RaceLink#warmupUntil()}) the racer starts on free laps instead.
     *
     * @param stand where finishers wait (a viewing stand), or {@code null}: finishers go home
     * @return why the racer can't be seated (not standing still and safe, in another game, a restart
     *         due), or {@code null} when they are on the grid
     */
    public Refusal race(Player p, Course base, Course raced, Course.Spot grid, Location stand, RaceLink link) {
        return race.race(p, base, raced, grid, stand, link);
    }

    /** The next race: a new boat on the racer's new {@code grid} spot, held to the link's next {@code goTick}. */
    public void regrid(Player p, Course raced, Course.Spot grid) {
        race.regrid(p, raced, grid);
    }

    /** A racer is done for this race: the boat goes, the run's own teleport takes them to the stand, the run idles. */
    public void park(Player p) {
        race.park(p);
    }

    /** End a racer's race run: home with their things, reading {@code line} (colour codes allowed). */
    public void endRace(UUID racer, EndReason why, String line) {
        race.endRace(racer, why, line);
    }

    /**
     * WP-CH: end a racer's race run into the Clubhouse (their session handed there in place), reading
     * {@code line}; home as {@link #endRace} when the Clubhouse can't take them.
     */
    public void endRaceToClubhouse(UUID racer, EndReason why, String line) {
        race.endRace(racer, why, line, true);
    }

    /**
     * Hold a course for a race: new solo runs and party races on it are refused with {@code line}
     * until {@link #release}, and a party race already on it is called off (its racers go home with a
     * clear line; nothing unfinished counts). {@code holder} is the race (a course is held by at most
     * one). Holds are memory only and a Time Trials stop drops them: the holder reserves again on its
     * next tick (Race Night does, every step while it needs the track), which is idempotent.
     *
     * @return false when another holder has it
     */
    public boolean reserve(String courseId, Object holder, String line) {
        boolean held = race.holds().reserve(courseId, holder, line);
        if (held) {
            party.callOff(courseId, null); // WP-R1: a party race on a held track is called off, its racers home
        }
        return held;
    }

    /** Let the course go again (only its own holder can). */
    public void release(String courseId, Object holder) {
        race.holds().release(courseId, holder);
    }

    /**
     * Who is on a solo run on {@code courseId} now (not racing): Race Night warms them that it needs
     * the track, then ends their runs (EVENTS-DROPPER-SPEC §A.1).
     */
    public List<UUID> soloRunners(String courseId) {
        List<UUID> out = new ArrayList<>();
        for (TrialRun run : runs.values()) {
            if (run.race == null && courseId != null && run.course.id().equalsIgnoreCase(courseId.trim())) {
                out.add(run.player);
            }
        }
        return out;
    }

    static Point point(Location l) {
        return new Point(l.getX(), l.getY(), l.getZ());
    }

    static void title(Player p, String big, String small, int stayTicks) {
        try {
            p.showTitle(Title.title(Text.of(big), Text.of(small), Title.Times.times(Duration.ZERO,
                    Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(200))));
        } catch (RuntimeException | LinkageError ignored) {
            // a title is decoration
        }
    }

    static void ping(Player p, float pitch) {
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

    WorldSessions sessions() {
        return ctx.games().sessions();
    }

    Logger log() {
        HomeCraftManagement plugin = ctx.plugin();
        return plugin != null ? plugin.getLogger() : Logger.getLogger("HomeCraftManagement");
    }
}
