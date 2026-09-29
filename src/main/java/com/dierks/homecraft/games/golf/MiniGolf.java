package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.gui.games.golf.GolfCourseMenu;
import com.dierks.homecraft.gui.games.golf.GolfCoursesMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Mini Golf (spec §12, R2.16, R3.10, R3.13): "your Mini is the ball".
 *
 * <p>Courses are built in the Games world with {@code /hcm games golf} ({@link GolfAdmin}) and kept
 * in {@code game_courses}; each playable course is its own tile, its own {@code /hcm play} id and
 * its own high-score board ({@code golf:<id>}, strokes, lower is better). Playing one takes the
 * player into a world session (their things are kept safe and come back at the end) with five
 * clubs, "Go to my ball", "Reset ball", the scorecard and "Leave game"; the round itself — the
 * ball, its physics, holes, pick-ups and the finish — is {@link GolfRounds}'s.
 *
 * <p><b>The ball</b> wears the head of one of the player's own Minis, picked on the "Pick your
 * ball" screen and remembered in {@code game_prefs} ({@value #BALL_PREF}). Only its texture is
 * borrowed: the head is a fresh plain one (never tagged as a Mini), and the Mini never leaves the
 * player's collection. No Mini (or one without a texture) is a plain white ball, and a Bedrock
 * player's ball is a white block, which Bedrock draws reliably.
 *
 * <p><b>Rewards</b> are small and capped (§6.1): the first finish of a course, finishing at par or
 * better (once per course a day), each hole-in-one of a round that was finished (once per hole a
 * day) and today's featured bonus. A round played on a course that an admin changed meanwhile
 * counts for nothing — its board was cleared for the new layout.
 *
 * <p><b>Golf of the Week and Tiny Golf</b> (GEN-SPEC §4.3, §5, the weekly addendum) are ordinary
 * golf rows Fresh Courses writes with a {@code gen:} tag, played by this same engine. They are open
 * only while the Fresh Courses engine vouches for their blocks, they come first on the Golf tab,
 * and their scores go on each set's own board with stars for the Star Chart, par and holes-in-one
 * paid once per set ({@link GolfFinish}). A course recalled into Classic Golf plays on its original
 * set's board.
 */
public final class MiniGolf implements Game {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<MiniGolfSettings> SPEC = new GameSpec<>("golf", GameKind.GOLF,
            MiniGolfSettings.KEYS, MiniGolfSettings.defaults(), MiniGolfSettings::parse,
            MiniGolf::new, null);

    /** The player's chosen ball: a Mini id, or unset for the plain white ball. */
    public static final String BALL_PREF = "golf.ball";
    /**
     * The plain white ball: a snowball head (minecraft-heads.com "Snowball", texture
     * 1dfd7724c69a024dcfc60b16e00334ab5738f4a92bafb8fbc76cf15322ea0293).
     */
    static final String WHITE_BALL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1"
            + "cmUvMWRmZDc3MjRjNjlhMDI0ZGNmYzYwYjE2ZTAwMzM0YWI1NzM4ZjRhOTJiYWZiOGZiYzc2Y2YxNTMyMmVhMDI5MyJ9fX0=";

    private final GameContext ctx;
    private final GolfRounds rounds;
    private final GolfAdmin admin;
    /** Every golf course by id (sorted), read once and again after each edit. */
    private Map<String, GolfCourse> courses;
    /** Course rows whose data can't be read, with why: listed for admins, never played. */
    private final Map<String, String> unreadable = new TreeMap<>();

    public MiniGolf(GameContext ctx) {
        this.ctx = ctx;
        this.rounds = new GolfRounds(this);
        this.admin = new GolfAdmin(this);
    }

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
        return "Mini Golf";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_GOLF;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Putt your ball into the cup in as few strokes as you can.",
                "Your Mini is the ball! Pick it before you start.",
                "Any click with a club putts the way you look.",
                "Slime bounces, ice slides, water puts you back (+1 stroke).");
    }

    @Override
    public ItemStack tile(Player viewer) {
        int n = playable().size();
        return Menus.icon(Material.SNOWBALL, "&d" + name() + " &7- " + n + " course" + (n == 1 ? "" : "s"),
                "&7" + rules().get(0), "&7" + rules().get(1), "&eClick to play");
    }

    /** One tile per playable course, on the Golf tab. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        List<GameTile> out = new ArrayList<>();
        int order = 0;
        for (GolfCourse c : playable()) {
            out.add(new GameTile(Tab.GOLF, courseTile(viewer, c), c.id(), order++));
        }
        return out;
    }

    /** A course's tile: "&amp;dMeadow Links &amp;7- 9 holes, par 27". */
    public ItemStack courseTile(Player viewer, GolfCourse c) {
        if (c.generated()) {
            return dailyTile(viewer, c);
        }
        List<String> lore = new ArrayList<>();
        lore.add("&7" + rules().get(0));
        lore.add("&7" + rules().get(2));
        Long best = best(viewer.getUniqueId(), c.id());
        lore.add(best == null ? "&7You haven't finished it yet."
                : "&7Your best: &f" + GolfRun.strokesText(best.intValue()) + " &7(" + GolfRun.vsParText(best.intValue()
                - c.par()) + ")");
        GamesDao.ScoreRow record = record(c.id());
        if (record != null) {
            lore.add("&7Course record: &f" + GolfRun.strokesText((int) record.score()));
        }
        lore.add("&eClick to play");
        return Menus.icon(Material.SNOWBALL, "&d" + c.name() + " &7- " + holes(c.holes().size()) + ", par " + c.par(),
                lore.toArray(new String[0]));
    }

    /**
     * A Fresh course's tile (GEN-SPEC §5.4): "&amp;dGolf of the Week &amp;7- 9 holes, par 29 ★☆☆
     * &amp;a(new this week) &amp;8· &amp;7Course code GOLF-3", its star lines, your best and the set's
     * best, and what its first finish in the set pays. A course recalled into Classic Golf says it
     * is a classic, with its old records to beat.
     */
    private ItemStack dailyTile(Player viewer, GolfCourse c) {
        GenTag t = c.gen();
        GamesService g = games();
        UUID id = viewer.getUniqueId();
        int cadence = t.cadence();
        int stars = DailyLookup.stars(g, id, t);
        List<String> lore = new ArrayList<>();
        if (t.recalled()) {
            lore.add("&7Its old records are the ones to beat.");
        } else {
            lore.add("&7" + GenCopy.schedule(cadence, DailyLookup.edition(g).rebuildDay(), null) + ".");
        }
        lore.add("&7" + rules().get(0));
        lore.add(DailyText.starStrokes(c.par(), c.holes().size()));
        Long best = best(id, c.id());
        lore.add(DailyText.yourBest(cadence, best == null ? null : GolfRun.strokesText(best.intValue())));
        lore.add(setBestLine(c, viewer));
        String first = DailyText.firstFinish(cadence, DailyLookup.freshClear(g, t),
                DailyLookup.freshClearPaid(g, id, id(), t));
        if (first != null) {
            lore.add(first);
        }
        lore.add("&eClick to play");
        String fact = holes(c.holes().size()) + ", par " + c.par() + (stars > 0 ? " " + Stars.text(stars) : "");
        String name = t.recalled()
                ? "&6" + com.dierks.homecraft.games.trial.TimeTrials.classicName(t, c.name()) + " &7- " + fact
                : DailyText.tabName(Slots.of(t.slot()), c.name(), fact, DailyLookup.current(g, t.slot()), cadence);
        return Menus.glint(Menus.icon(Material.SNOWBALL, name + DailyLookup.codeSuffix(DailyLookup.code(g, t)),
                lore.toArray(new String[0])), stars >= Stars.MAX);
    }

    /**
     * "&amp;7This week's best: 27 strokes by Alex" on a Fresh course, or that nobody has finished it
     * yet in this set (the words follow the set's cadence).
     */
    public String setBestLine(GolfCourse c, Player viewer) {
        int cadence = c.gen() == null ? 7 : c.gen().cadence();
        GamesDao.ScoreRow r = record(c.id());
        if (r == null) {
            return DailyText.setBest(cadence, null, null, false);
        }
        boolean yours = viewer != null && viewer.getUniqueId().equals(r.player());
        String who = yours ? null : Bukkit.getOfflinePlayer(r.player()).getName();
        return DailyText.setBest(cadence, GolfRun.strokesText((int) r.score()), who, yours);
    }

    /** The course list. */
    @Override
    public void open(Player player, Runnable back) {
        new GolfCoursesMenu(ctx.plugin(), this, player, 0, back).open(player);
    }

    /** Every playable course, by id. */
    @Override
    public Collection<Playable> playables() {
        List<Playable> out = new ArrayList<>();
        for (GolfCourse c : playable()) {
            out.add(new Playable(c.id(), c.name(), this, source(), ""));
        }
        return out;
    }

    /** {@code /hcm play <course>} and its tile: the course's screen (rules, best, record, Start). */
    @Override
    public boolean play(Player player, String playableId, Runnable back) {
        GolfCourse c = playableCourse(playableId);
        if (c == null) {
            return false;
        }
        new GolfCourseMenu(ctx.plugin(), this, player, c.id(), back).open(player);
        return true;
    }

    /**
     * One {@code /api/arcade} entry per playable course, and the board behind it for its
     * {@code top} list. A Fresh course's record is its set's board's, with the set's first and last
     * day, cadence and when the next set is due (never its full seed, rev or half); a recalled one
     * has no next set.
     */
    @Override
    public void feed(FeedWriter out) {
        for (GolfCourse c : playable()) {
            GamesDao.ScoreRow record = record(c.id());
            Integer strokes = record == null ? null : (int) record.score();
            Long at = record == null ? null : record.at();
            String who = record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null;
            if (c.generated()) {
                GenTag t = c.gen();
                out.golf(c.id(), c.name(), c.holes().size(), c.par(), strokes, at, who, new FeedWriter.Daily(
                        t.date().toString(), t.recalled() ? 0 : games().generated().nextChangeAt(), null, null,
                        t.cadence(), t.date().plusDays(t.cadence() - 1L).toString()));
            } else {
                out.golf(c.id(), c.name(), c.holes().size(), c.par(), strokes, at, who);
            }
            out.board(c.id(), id(), board(c), true, "strokes");
        }
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    /** Sweep leftover balls, read the courses afresh and start the round clock. */
    @Override
    public void start() {
        courses = null;
        if (ctx.plugin() == null) {
            return;
        }
        WorldEntities.sweep(this);
        games().every(this, 1, 1, rounds::tick);
    }

    @Override
    public void stop() {
        rounds.stopAll();
    }

    @Override
    public void onQuit(Player player) {
        rounds.quit(player);
    }

    @Override
    public void onSessionEnd(Player player, EndReason reason) {
        rounds.ended(player, reason);
    }

    @Override
    public void onVoid(Player player) {
        rounds.voided(player);
    }

    @Override
    public void onKitUse(Player player, String action, boolean leftClick) {
        rounds.kit(player, action);
    }

    // ---- for the rounds, the screens and the admin tool --------------------------------------------

    public GamesService games() {
        return ctx.games();
    }

    public HomeCraftManagement plugin() {
        return ctx.plugin();
    }

    /** The live settings (read on every use, never cached across a reload). */
    MiniGolfSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** The rounds being played. */
    public GolfRounds rounds() {
        return rounds;
    }

    /** Start {@code courseId} for the player (the course screen's Start, "Play again"). */
    public boolean start(Player player, String courseId) {
        return rounds.start(player, courseId);
    }

    /** {@code games.worlds}: where courses may be built. */
    List<String> worlds() {
        return games().config().common().worlds();
    }

    /** Every golf course by id, read from the database on first use. */
    Map<String, GolfCourse> courses() {
        if (courses == null) {
            Map<String, GolfCourse> read = new TreeMap<>();
            unreadable.clear();
            try {
                for (GamesDao.CourseRow row : games().dao().courses(CourseCodec.GAME)) {
                    try {
                        read.put(row.id(), CourseCodec.fromRow(row));
                    } catch (IllegalArgumentException e) {
                        unreadable.put(row.id(), e.getMessage());
                    }
                }
                if (!unreadable.isEmpty()) {
                    log(Level.WARNING, "Mini golf: can't read course(s) " + unreadable
                            + " - they stay closed until fixed or deleted (/hcm games golf <id> delete confirm)", null);
                }
            } catch (SQLException e) {
                log(Level.SEVERE, "Mini golf: could not read the courses", e);
            }
            courses = read;
        }
        return courses;
    }

    /** Course ids whose saved data can't be read, with why. */
    Map<String, String> unreadable() {
        courses();
        return unreadable;
    }

    /** Read the courses again (after an edit). */
    void reloadCourses() {
        courses = null;
    }

    @Override
    public void coursesChanged() {
        reloadCourses();
    }

    /** The course, if it exists (enabled or not). */
    public GolfCourse course(String id) {
        return id == null ? null : courses().get(GolfCourse.normalise(id));
    }

    /**
     * The course if it can be played right now (and, for a Fresh course, the Fresh Courses engine
     * vouches for its blocks), else {@code null}.
     */
    public GolfCourse playableCourse(String id) {
        GolfCourse c = course(id);
        return c != null && c.playable(worlds()) && games().generated().live(c.id(), c.gen()) ? c : null;
    }

    /** Every course that can be played right now: the Fresh ones first (slot order), then by id. */
    public List<GolfCourse> playable() {
        return playable(courses().values(), worlds(), games().generated());
    }

    /**
     * {@link #playable()} as a pure filter, so a test can hand it the real rows and the real Fresh
     * Courses gate: every course of {@code courses} that can be played in {@code worlds} and, when
     * generated, is {@code gate}-vouched, in {@link #sorted} order.
     */
    public static List<GolfCourse> playable(Collection<GolfCourse> courses, Collection<String> worlds,
                                            GeneratedCourses gate) {
        List<GolfCourse> out = new ArrayList<>();
        for (GolfCourse c : courses) {
            if (c.playable(worlds) && gate.live(c.id(), c.gen())) {
                out.add(c);
            }
        }
        return sorted(out);
    }

    /** Fresh courses first, in slot order ({@link Slots#ALL}); then the rest by id. */
    static List<GolfCourse> sorted(List<GolfCourse> courses) {
        List<GolfCourse> out = new ArrayList<>(courses);
        out.sort(Comparator.comparingInt(MiniGolf::dailyOrder).thenComparing(GolfCourse::id));
        return out;
    }

    private static int dailyOrder(GolfCourse c) {
        if (!c.generated()) {
            return Integer.MAX_VALUE;
        }
        int i = Slots.ids().indexOf(c.id());
        return i < 0 ? Slots.ALL.size() : i;
    }

    /** The board a course's scores go on: a Fresh course's set board ("this week's best"), else its own. */
    public static String board(GolfCourse c) {
        return GolfFinish.board(c.id(), c.gen());
    }

    /** The board of course {@code courseId} as it is now. */
    private String board(String courseId) {
        GolfCourse c = course(courseId);
        return c == null ? Scores.golf(GolfCourse.normalise(courseId)) : board(c);
    }

    /** The player's best on a course (strokes; a Fresh course: on its set's board), or {@code null}. */
    public Long best(UUID player, String courseId) {
        return games().scores().best(player, id(), board(courseId));
    }

    /** A course's record (a Fresh course: its set's best), or {@code null}. */
    public GamesDao.ScoreRow record(String courseId) {
        return games().scores().record(id(), board(courseId), true);
    }

    /** Open a course's high scores (a Fresh course: its set's board). */
    public void openScores(Player player, String courseId, Runnable back) {
        games().screens().scores(player, this, board(courseId), true, back);
    }

    /** Players on each course right now, for the admin tool. */
    Map<String, List<UUID>> playing() {
        Map<String, List<UUID>> out = new LinkedHashMap<>();
        for (LiveRound r : rounds.all()) {
            out.computeIfAbsent(r.course.id(), k -> new ArrayList<>()).add(r.player);
        }
        return out;
    }

    // ---- the ball -------------------------------------------------------------------------------

    /**
     * The Minis the player could use as a ball: ones they own with a head texture (a posed stand
     * has none), by name. One database read.
     */
    public List<MiniDef> ballChoices(UUID player) {
        MiniService minis = ctx.plugin() == null ? null : ctx.plugin().miniService();
        if (minis == null) {
            return List.of();
        }
        List<MiniDef> out = new ArrayList<>();
        for (String id : minis.ownedIds(player)) {
            MiniDef def = minis.def(id);
            if (usable(def)) {
                out.add(def);
            }
        }
        out.sort(Comparator.comparing(MiniDef::name, String.CASE_INSENSITIVE_ORDER).thenComparing(MiniDef::id));
        return out;
    }

    /** The chosen Mini id, or {@code null} for the plain white ball. */
    public String chosenBall(UUID player) {
        try {
            String v = games().dao().pref(player, BALL_PREF);
            return v == null || v.isBlank() ? null : v;
        } catch (SQLException e) {
            log(Level.WARNING, "Mini golf: could not read a player's ball", e);
            return null;
        }
    }

    /** Choose a Mini ({@code null} = the plain white ball). */
    public boolean chooseBall(UUID player, String miniId) {
        try {
            games().dao().setPref(player, BALL_PREF, miniId == null || miniId.isBlank() ? null : miniId);
            return true;
        } catch (SQLException e) {
            log(Level.WARNING, "Mini golf: could not save a player's ball", e);
            return false;
        }
    }

    /**
     * The ball this player plays with: a white block on Bedrock; else the head of their chosen Mini
     * if they still own it and it has a texture; else the plain white ball. Reads what they own
     * once (call it once per round).
     */
    ItemStack ballItem(Player player) {
        if (Bedrock.is(player)) {
            return new ItemStack(Material.WHITE_CONCRETE);
        }
        String texture = WHITE_BALL;
        String chosen = chosenBall(player.getUniqueId());
        MiniService minis = ctx.plugin() == null ? null : ctx.plugin().miniService();
        if (chosen != null && minis != null && minis.ownedIds(player.getUniqueId()).contains(chosen)) {
            MiniDef def = minis.def(chosen);
            if (usable(def)) {
                texture = def.texture();
            }
        }
        return Heads.base(texture);
    }

    /** The plain white ball, for screens. */
    public static ItemStack whiteBall() {
        return Heads.base(WHITE_BALL);
    }

    /** Whether a Mini can be a ball: it exists, it's a head, and it has a texture. */
    static boolean usable(MiniDef def) {
        return def != null && def.type() != MiniType.ARMOR_STAND && def.texture() != null && !def.texture().isBlank();
    }

    // ---- words ----------------------------------------------------------------------------------

    /** "1 hole", "9 holes". */
    public static String holes(int n) {
        return n + (n == 1 ? " hole" : " holes");
    }

    void log(Level level, String line, Throwable cause) {
        if (ctx.plugin() != null) {
            ctx.plugin().getLogger().log(level, line, cause);
        }
    }
}
