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
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.Menus;
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

    @Override
    public void feed(FeedWriter out) {
        for (GolfCourse c : playable()) {
            GamesDao.ScoreRow record = record(c.id());
            out.golf(c.id(), c.name(), c.holes().size(), c.par(),
                    record == null ? null : (int) record.score(), record == null ? null : record.at(),
                    record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
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

    /** The course if it can be played right now, else {@code null}. */
    public GolfCourse playableCourse(String id) {
        GolfCourse c = course(id);
        return c != null && c.playable(worlds()) ? c : null;
    }

    /** Every course that can be played right now, by id. */
    public List<GolfCourse> playable() {
        List<GolfCourse> out = new ArrayList<>();
        List<String> worlds = worlds();
        for (GolfCourse c : courses().values()) {
            if (c.playable(worlds)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The player's best on a course (strokes), or {@code null}. */
    public Long best(UUID player, String courseId) {
        return games().scores().best(player, id(), Scores.golf(courseId));
    }

    /** A course's record, or {@code null}. */
    public GamesDao.ScoreRow record(String courseId) {
        return games().scores().record(id(), Scores.golf(courseId), true);
    }

    /** Open a course's high scores. */
    public void openScores(Player player, String courseId, Runnable back) {
        games().screens().scores(player, this, Scores.golf(courseId), true, back);
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
