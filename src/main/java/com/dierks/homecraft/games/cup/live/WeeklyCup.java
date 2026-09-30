package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.cup.CupMenu;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Weekly Cup (EVENTS-OWNER-DECISIONS §D2, EVENTS-RECONCILED decision 3): on a time-trial course
 * that runs one, a player pays a small entry once a week ({@code games.cup.entry}, 5 tokens), their
 * best counted time that week is their Cup time, and at the week's rollover (the quests' week start
 * at 04:00, when the Fresh Courses change) the pool, every entry plus a small server top-up, is
 * shared by Cup time: 70/30 with 2 Cup times, 50/30/20 with 3 or more, and no share without one.
 * With fewer than 2 Cup times, or when the course is deleted, re-made or closed mid-week, every
 * entry comes back. The server keeps nothing. It is a skill contest: nothing is random anywhere, so
 * Take a break's chance rules don't apply, and a player can hide it all with {@code /hcm play cup off}.
 *
 * <p><b>Why it is a game.</b> It has no tile and no screen of its own on the Games screen: it lives on
 * the course screens and tiles ({@link CupLink}). But as a game it gets the framework's guard (a bug
 * here switches off the Cup, never Time Trials), its own guarded minute task, its
 * {@code /hcm games cup} commands, its {@code games.cup} block, its line in {@code /hcm games status},
 * and its part of the website feed (a {@code cup} object on each course that runs one).
 *
 * <p><b>Why it stays open with {@code games.cup.enabled: false}.</b> That switch closes new entries and
 * hides the prompts at once, but a Cup already paid into must still end: its minute task keeps
 * settling (or refunding) the Cups that are running, at their own rollover. A {@code games.cup} block
 * that can't be read (junk where a number or a switch belongs) does the same: it reads as
 * {@code enabled: false} with the shipped numbers for anything unreadable ({@link #opensOnDefaults},
 * {@link #settings}), so no entrant is left waiting on a typo. Only {@code games.enabled: false}
 * stops it, and then every Cup it missed is settled at the next start.
 *
 * <p>All the money is in {@link CupDesk} and the SQL in {@code CupDao}; this class is the wiring.
 */
public final class WeeklyCup implements Game {

    /** Built. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<CupSettings> SPEC = new GameSpec<>("cup", GameKind.TRIAL, CupSettings.KEYS,
            CupSettings.defaults(), CupSettings::parse, WeeklyCup::new, null);

    /** The settlement check: once a minute (and once, a second after every start). */
    static final long TICK_EVERY = 20L * 60;
    static final long FIRST_TICK = 20L;

    private final GameContext ctx;
    private final CupAdmin admin;
    private CupDesk desk;
    /** The week start the last check saw, to say once when the owner moves it. */
    private DayOfWeek weekStartSeen;

    public WeeklyCup(GameContext ctx) {
        this.ctx = ctx;
        this.admin = new CupAdmin(this);
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
        return "Weekly Cup";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_CUP_ENTRY;
    }

    /** Always open while the games are: {@code games.cup.enabled} only closes new entries (see the class note). */
    @Override
    public boolean configEnabled() {
        return IMPLEMENTED;
    }

    /**
     * Junk in {@code games.cup} doesn't close the Cup: it still settles the Cups already paid into,
     * and takes no new entries ({@link #settings}).
     */
    @Override
    public boolean opensOnDefaults() {
        return true;
    }

    /** A week-long contest is never "today's pick". */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return CupWords.RULES;
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.GOLD_BLOCK, "&6" + name() + " &7- " + CupText.enterPrompt(settings().entry()),
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    /** No tile of its own: the Cup shows on each course's tile and screen. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of();
    }

    /** {@code /hcm play cup} lands in the command; a sign or an NPC opening it gets the same lines. */
    @Override
    public void open(Player player, Runnable back) {
        for (String line : summary(player.getUniqueId())) {
            player.sendMessage(Text.of(line));
        }
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /** The minute check, and once at start: a Cup whose rollover was missed is settled now. */
    @Override
    public void start() {
        weekStartSeen = null;
        if (!desk().freshEligible()) {
            Edition e = edition();
            logger().warning("Weekly Cup: Fresh Courses change every " + e.cadenceDays() + " day(s) from "
                    + e.rebuildDay() + " (games.fresh.cadence, rebuild_day), not once a week from " + e.weekStart()
                    + ", so Fresh courses run no Cup. Hand-built courses still can.");
        }
        games().every(this, FIRST_TICK, TICK_EVERY, this::tick);
    }

    @Override
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        CupSettings s = settings();
        out.add((s.enabled() ? "entries open: " + CupText.tokens(s.entry()) + ", top-up " + s.serverTopup()
                : "entries closed (" + closedWhy() + "); running Cups still finish their week")
                + " - /hcm games cup status");
        try {
            long week = desk().week();
            int running = 0;
            int waiting = 0;
            for (CupKey k : desk().dao().openKeys()) {
                if (k.week() == week) {
                    running++;
                } else {
                    waiting++;
                }
            }
            out.add(running + " running this week, paid " + whenDated(desk().endsAt())
                    + (waiting > 0 ? "; " + waiting + " waiting to be settled" : ""));
        } catch (SQLException e) {
            out.add("the Cups can't be read right now");
        }
        return out;
    }

    /**
     * A {@code cup} object on each open course that runs a Cup this week ({@code entry}, {@code pool},
     * {@code entrants}, {@code endsAt}); ArcadeFeed puts it on that course's entry.
     */
    @Override
    public void feed(FeedWriter out) {
        TimeTrials trials = trials();
        if (trials == null || !games().enabled(trials)) {
            return;
        }
        for (Course c : trials.openCourses()) {
            try {
                CupDesk.View v = desk().view(c, null);
                if (v.shown() && !v.settledEarly()) { // a Cup paid out early is over: not on the website
                    out.cup(c.id(), new FeedWriter.Cup(v.fee(), v.pool().tokens(), v.pool().in(), v.endsAt()));
                }
            } catch (SQLException e) {
                return; // the rest of the feed goes out without the Cup
            }
        }
    }

    // ---- what the hooks and screens call ------------------------------------------------------

    /**
     * The live settings (read on every use). A {@code games.cup} block that can't be read takes no
     * new entries, and the Cups already running settle on what could be read, the shipped numbers
     * for the rest (see the class note).
     */
    public CupSettings settings() {
        CupSettings s = games().settings(SPEC);
        return readable() ? s : new CupSettings(false, s.entry(), s.serverTopup());
    }

    /** Whether {@code games.cup} could be read. */
    boolean readable() {
        return games().config().readable(SPEC.id());
    }

    /** Why entries are closed, for the admins: the switch, or a block that can't be read. */
    String closedWhy() {
        return readable() ? "games.cup.enabled: false" : "games.cup can't be read - see the console";
    }

    /** The desk: entries, Cup times, settling and calling off. */
    public CupDesk desk() {
        if (desk == null) {
            desk = new CupDesk(games().dao().cup(), new LiveHost());
        }
        return desk;
    }

    /** Time Trials, whose courses run the Cups, or {@code null}. */
    public TimeTrials trials() {
        Game g = games().game(TimeTrials.SPEC.id());
        return g instanceof TimeTrials t ? t : null;
    }

    public GamesService games() {
        return ctx.games();
    }

    /** {@code c}'s Cup this week as {@code viewer} sees it, or {@code null} while it can't be read or is hidden. */
    public CupDesk.View view(Course c, UUID viewer, boolean evenIfHidden) {
        try {
            if (!evenIfHidden && viewer != null && hidden(viewer)) {
                return null;
            }
            return desk().view(c, viewer);
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: could not read the Cup on " + c.id(), e);
            return null;
        }
    }

    /**
     * {@code viewer}'s Cup tiles for one screen build (fx2-C #5): whether they hid the Cup is read
     * once, not once a course. {@code null} when they hid it or it can't be read (no Cup on any tile).
     */
    public CupDesk.Reads tileReads(UUID viewer) {
        try {
            return hidden(viewer) ? null : desk().reads(viewer);
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: could not read whether " + viewer + " hid the Cup", e);
            return null;
        }
    }

    /** {@code c}'s Cup on its tile through {@code reads}, or {@code null} when none is shown or it can't be read. */
    public CupDesk.View tileView(CupDesk.Reads reads, Course c) {
        try {
            return reads.shown(c);
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: could not read the Cup on " + c.id(), e);
            return null;
        }
    }

    /** Whether the player hid the Cup ({@code /hcm play cup off}). */
    public boolean hidden(UUID player) throws SQLException {
        return CupDesk.hidden(games().dao().pref(player, CupDesk.PREF_PROMPTS));
    }

    /** {@code /hcm play cup on|off}. */
    public void show(UUID player, boolean shown) throws SQLException {
        games().dao().setPref(player, CupDesk.PREF_PROMPTS, shown ? null : "off");
    }

    /**
     * Enter {@code player} in this week's Cup on the open course {@code courseId}: the games' basic gate
     * (in a session, permission, world: never Take a break's chance steps), then the debit and the row.
     *
     * @return whether they are in now
     */
    public boolean enter(Player player, String courseId) {
        GamesService g = games();
        Refusal gate = g.canOpen(player, this);
        if (gate != null) {
            g.tell(player, gate);
            return false;
        }
        TimeTrials trials = trials();
        Course c = trials == null || !g.enabled(trials) ? null : trials.openCourse(courseId);
        if (c == null) {
            g.tell(player, Refusal.of("That course is closed right now."));
            return false;
        }
        CupRefusal r;
        try {
            r = desk().enter(player.getUniqueId(), c);
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: an entry couldn't be written", e);
            g.tell(player, Refusal.of("The Cup can't take entries right now. Nothing was paid."));
            return false;
        }
        if (r != null) {
            g.tell(player, Refusal.of(r.message(settings().entry())));
            return false;
        }
        player.sendMessage(Text.of(CupWords.entered(c.name(), settings().entry())));
        Sounds.paid(player);
        logger().info("Weekly Cup: " + player.getName() + " entered the Cup on " + c.id() + " ("
                + CupText.tokens(settings().entry()) + ").");
        return true;
    }

    /**
     * A counted, timed run finished: it may set the player's Cup time ({@link CupLink#finished}). An
     * entrant whose run was on last week's layout of a Fresh course hears why it set none.
     */
    void counted(Player player, Course ranOn, long ms) {
        try {
            if (desk().counted(player.getUniqueId(), ranOn, ms, games().clock().nowMillis())) {
                player.sendMessage(Text.of(CupWords.newCupTime(ranOn.name(), ms)));
            } else if (desk().onLastWeeksLayout(player.getUniqueId(), ranOn)) {
                player.sendMessage(Text.of(CupWords.lastWeeksLayout()));
            }
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: a Cup time couldn't be written", e);
        }
    }

    /**
     * Forget a deleted course's Cup switch ({@code cup.course.<id>}), so a new course with its id
     * starts from the default. Said in the log when it can't be.
     */
    void forgetSwitch(String courseId) {
        try {
            desk().dao().choose(courseId, null);
        } catch (SQLException e) {
            logger().log(Level.WARNING, "Weekly Cup: the deleted course " + courseId + "'s Cup switch couldn't be"
                    + " cleared: a new course called " + courseId + " starts with it (/hcm games cup default "
                    + courseId + " once it is made)", e);
        }
    }

    /** Call this week's Cup on {@code courseId} off now (an admin deleted, edited or closed it). */
    public CupDesk.Closed voidNow(String courseId, CupPlan.VoidReason reason, String name) {
        return desk().voidNow(courseId, reason, name);
    }

    /**
     * How many are in this week's Cup on {@code courseId} while it is still running: 0 once it was
     * called off or settled (its rows stay, but nobody is waiting on it), or when it can't be read.
     */
    public int entrants(String courseId) {
        try {
            CupKey key = desk().key(courseId);
            return desk().dao().settledAs(key) != null ? 0 : desk().dao().entries(key).size();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** A moment with its date, for the admins: "Mon 5 Oct 4:00 AM". */
    public String whenDated(long millis) {
        return GenCopy.whenDated(millis, zone());
    }

    /** The player's Cups this week, for {@code /hcm play cup}. */
    public List<String> summary(UUID player) {
        List<String> out = new ArrayList<>();
        try {
            out.add(CupWords.promptsState(!hidden(player)));
            long week = desk().week();
            List<String> in = desk().dao().entered(player, week);
            if (in.isEmpty()) {
                out.add("&7You're not in a Weekly Cup this week."
                        + (settings().enabled() ? " Open a course to enter its Cup." : ""));
            }
            for (String id : in) {
                CupKey key = new CupKey(id, week);
                CupRules.LivePool pool = CupRules.livePool(desk().dao().entries(key), settings().serverTopup());
                out.add("&6" + desk().name(id) + "&7: " + CupText.poolLine(pool.tokens(), pool.in()) + " &8- "
                        + CupWords.yourTime(desk().dao().entry(key, player)).substring(2));
            }
            out.add("&7Paid " + GenCopy.when(desk().endsAt(), zone()) + ".");
        } catch (SQLException e) {
            out.add("&cThe Cup can't be read right now.");
        }
        return out;
    }

    // ---- internals ----------------------------------------------------------------------------

    void tick() {
        DayOfWeek start = edition().weekStart();
        if (weekStartSeen != null && weekStartSeen != start) {
            logger().info("Weekly Cup: the week now starts on " + start + ". Cups already running still finish their"
                    + " own seven days; the new week's Cups open beside them.");
        }
        weekStartSeen = start;
        desk().tick();
    }

    Edition edition() {
        return DailyLookup.edition(games());
    }

    /** A moment as players read it: "Mon 4:00 AM". */
    public String when(long millis) {
        return GenCopy.when(millis, zone());
    }

    java.time.ZoneId zone() {
        return games().clock().zone();
    }

    Logger logger() {
        return ctx.plugin() != null ? ctx.plugin().getLogger() : Logger.getLogger("HomeCraftManagement");
    }

    /** The server, as the desk needs it. */
    private final class LiveHost implements CupDesk.Host {

        @Override
        public long now() {
            return games().clock().nowMillis();
        }

        @Override
        public Edition edition() {
            return WeeklyCup.this.edition();
        }

        @Override
        public CupSettings settings() {
            return WeeklyCup.this.settings();
        }

        @Override
        public Course course(String id) throws SQLException {
            GamesDao.CourseRow row = games().dao().course(id);
            if (row == null || !TimeTrials.SPEC.id().equals(row.game())) {
                return null;
            }
            Course c = CourseCodec.decode(row.id(), row.data()).course();
            if (c == null) {
                throw new SQLException("the course row " + id + " can't be read");
            }
            return c.withRev(row.rev());
        }

        @Override
        public com.dierks.homecraft.games.RestartHold restartHold() {
            return games().restartHold();
        }

        @Override
        public Boolean slotWanted(String id) {
            GenService e = DailyLookup.engine(games());
            if (e == null || !e.running()) {
                return null;
            }
            for (GenService.SlotReport r : e.report()) {
                if (r.id().equalsIgnoreCase(id)) {
                    return r.wanted();
                }
            }
            return null;
        }

        @Override
        public boolean tellNow(UUID player, String line) {
            Player p;
            try {
                p = Bukkit.getPlayer(player);
            } catch (RuntimeException e) {
                return false; // no server to ask (a test): the line waits for their next join
            }
            if (p == null || !p.isOnline()) {
                return false;
            }
            p.sendMessage(Text.of(line));
            return true;
        }

        @Override
        public Logger logger() {
            return WeeklyCup.this.logger();
        }
    }

    /** Opens the Cup screen for {@code course} (the course screen's Cup item). */
    public void openScreen(Player player, Course course, Runnable back) {
        new CupMenu(ctx.plugin(), this, course, player, back).open(player);
    }
}
