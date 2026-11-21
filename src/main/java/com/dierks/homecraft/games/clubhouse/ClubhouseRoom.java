package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.BuildBudget;
import com.dierks.homecraft.games.gen.engine.BuildJob;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The Clubhouse room itself (CLUBHOUSE-SPEC §1, §6): where it is, whether it is built and checked,
 * and the spots the flows use. Copies the Falling Floors arena's pattern ({@code ArenaService}):
 *
 * <ul>
 *   <li><b>The claim.</b> The first time the box is used it must be empty. An unclaimed box is only
 *       counted ({@link BuildJob.Mode#SCAN}); empty, it is claimed ({@value #CLAIM_KEY}) and built;
 *       anything in it closes the Clubhouse, touching nothing, until an admin's
 *       {@code /hcm games clubhouse rebuild confirm}. Before anything is written the box must also be
 *       clear of every Fresh Courses area, the kept courses, the arena and hand-built courses.</li>
 *   <li><b>The gate.</b> Nobody comes in until a verify has passed in this run: a build is Fresh
 *       Courses' own converge-and-verify ({@link BuildJob}), so after a crash it simply puts the room
 *       back. A failed verify closes the Clubhouse, and every flow goes back to today's behaviour.</li>
 *   <li><b>Owner-built.</b> {@code /hcm games clubhouse here} makes a room the owner built by hand the
 *       Clubhouse: its arrival spot is where they stand, the podium and board where they set them (and,
 *       0.38.3, the walls' boards: {@code board fame|playing|stats}, each facing the way the admin faced).
 *       Nothing is built, checked or guarded then; {@code generated} goes back.</li>
 *   <li><b>Off.</b> {@code /hcm games clubhouse off} closes it (it stays closed across restarts)
 *       until {@code generated}, {@code here} or {@code rebuild confirm}.</li>
 * </ul>
 * Everything runs on the main thread, inside the game's guard. Pure apart from the {@link RoomHost}.
 */
public final class ClubhouseRoom {

    /**
     * Every key the room keeps in {@code hcm_meta} starts with this. The live host stores them through
     * {@code GenMetaDao}, which refuses any key outside {@code gen.} (so a typo can never overwrite the
     * schema version or the games secret): a key outside it could never be read or saved on a live
     * server (fx2-C #8).
     */
    public static final String META_PREFIX = "gen.clubhouse.";
    /** Where the claim is kept in {@code hcm_meta}: the main room's box (32 x 16 x 32, as before 0.41.0). */
    public static final String CLAIM_KEY = META_PREFIX + "claim";
    /**
     * 0.41.0: the side rooms' box (the 16 rows south of the main room) is claimed on its own, the same way: scanned
     * first, claimed only when empty. A server that already has its main room keeps that claim untouched.
     */
    public static final String ANNEX_CLAIM_KEY = META_PREFIX + "annex.claim";
    /**
     * 0.41.0: the owner-built room's Arcade Room cabinets (one key: "game=world;x;y;z;yaw|game=..."), its trophy shelf
     * and its two door signs ({@code DOOR_KEY + "arcade"|"trophy"}).
     */
    public static final String CABINETS_KEY = META_PREFIX + "hand.cabinets";
    public static final String TROPHY_ROOM_KEY = META_PREFIX + "hand.trophyroom";
    public static final String DOOR_KEY = META_PREFIX + "hand.door.";
    /**
     * 0.42.0, edit mode: "1" while the owner is decorating the generated room ({@code edit}), and the save in use
     * ({@code save confirm}, {@code restore}), its id in {@link RoomHost#saves()}.
     */
    public static final String EDIT_KEY = META_PREFIX + "edit";
    /**
     * 0.43.0: the owner-built room's secret star and secret room (its arrival spot), the parlor's door and room, and
     * its machines (one key: "game=place|...").
     */
    public static final String SECRET_KEY = META_PREFIX + "hand.secret";
    public static final String SECRET_ROOM_KEY = META_PREFIX + "hand.secret.room";
    public static final String PARLOR_DOOR_KEY = META_PREFIX + "hand.parlor.door";
    public static final String PARLOR_ROOM_KEY = META_PREFIX + "hand.parlor.room";
    public static final String PARLOR_KEY = META_PREFIX + "hand.parlor.machines";
    public static final String SAVED_KEY = META_PREFIX + "saved";
    /** How many saves are kept (the newest). */
    public static final int SAVES_KEPT = 5;
    /** At most this many dark spots are listed when a save is checked. */
    static final int DARK_LISTED = 8;
    /** "hand" while an owner-built room is the Clubhouse. */
    public static final String MODE_KEY = META_PREFIX + "mode";
    /** "1" while an admin has switched the Clubhouse off. */
    public static final String OFF_KEY = META_PREFIX + "off";
    /** The owner-built room's spots: "world;x;y;z;yaw". */
    public static final String SPAWN_KEY = META_PREFIX + "hand.spawn";
    public static final String BOARD_KEY = META_PREFIX + "hand.board";
    /** 0.38.3: the owner-built room's wall boards, {@code BOARD_KEY + ".fame"} and so on (also under {@code gen.}). */
    public static final String WALL_KEY = BOARD_KEY + ".";
    public static final String PODIUM_KEY = META_PREFIX + "hand.podium.";
    /**
     * 0.38.4: the owner-built room's trophy case (the first frame's block, the row running to the admin's right)
     * and its jukebox (the block under the admin's feet), and the family week's ceremony marker (the week key it
     * last ran, or was skipped, for: {@link FamilyWeek}).
     */
    public static final String TROPHIES_KEY = META_PREFIX + "hand.trophies";
    public static final String JUKEBOX_KEY = META_PREFIX + "hand.jukebox";
    public static final String WEEK_DONE_KEY = META_PREFIX + "week.done";
    /**
     * 0.38.6: the owner-built room's feast table (the first plate's block, where the admin stands on their table; ten
     * plates to their right, a second row of ten one block ahead).
     */
    public static final String FEAST_KEY = META_PREFIX + "hand.feast";
    /** The build's time budget a tick, and the snapshots and chunk loads it may use. */
    static final int BUILD_MS = 3;
    static final int BUILD_SNAPSHOTS = 3;
    static final int BUILD_LOADS = 4;
    static final int BLOCKS_PER_TICK = 400;
    static final int PAUSE_MSPT = 40;

    /** Where the room is in its life. */
    public enum Phase {
        /** Waiting for its world or the database. */
        WAITING,
        /** Counting what is in an unclaimed box. */
        SCANNING,
        /** Building and verifying. */
        BUILDING,
        /** Built and verified (or an owner-built room with its arrival spot set): open. */
        READY,
        /** Closed: see {@link #closedWhy()}. */
        CLOSED
    }

    /** A place in a named world: an owner-built room's spots. */
    public record Place(String world, double x, double y, double z, float yaw) {

        /** "world;x;y;z;yaw", as stored. */
        public String text() {
            return world + ";" + fmt(x) + ";" + fmt(y) + ";" + fmt(z) + ";" + fmt(yaw);
        }

        /** Read "world;x;y;z;yaw", or {@code null} for anything else. */
        public static Place of(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] p = text.split(";");
            if (p.length != 5 || p[0].isBlank()) {
                return null;
            }
            try {
                return new Place(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        ClubhouseSite.Spot spot() {
            return new ClubhouseSite.Spot(x, y, z, yaw);
        }

        private static String fmt(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    private final RoomHost host;
    /** The main room's box (0.41.0: the claim's), the side rooms' box, and the side rooms wanted. */
    private final Box box;
    private final Box annex;
    private final Box full;
    private final ClubhousePlanner.Rooms rooms;
    private final String world;
    private final BuildBudget budget;
    /** The main room alone, and with the annex (the rooms wanted, or none to clear it); {@code null} if not made. */
    private final ClubhouseSite mainSite;
    private final ClubhouseSite fullSite;
    /** The room being built or standing: one of the two, or (0.42.0) the owner's saved room. */
    private ClubhouseSite site;
    /** 0.42.0: edit mode, the save in use (its id and site), and whether HCM's plan changed since it was taken. */
    private boolean editing;
    private String savedId;
    private ClubhouseSite savedSite;
    private String savedProblem;
    private boolean planChanged;
    /** A save being taken (its chunks loading). */
    private boolean saving;
    /** 0.41.0: whether the annex is part of the build (claimed), and why not when it is wanted but left out. */
    private boolean annexIn;
    private String annexOut;
    /** What the running job is: a claim scan of the main box, of the annex, or the build. */
    private enum JobKind { SCAN_MAIN, SCAN_ANNEX, BUILD }
    private JobKind jobKind;

    private Phase phase = Phase.WAITING;
    private String waitingFor;
    private String closedWhy;
    private String planProblem;
    private boolean hand;
    private boolean off;
    private Place handSpawn;
    private Place handBoard;
    private final Place[] handPodium = new Place[3];
    /** 0.38.3: the owner-built room's wall boards ({@link WallBoards#WALLS}), where set. */
    private final Map<String, Place> handWalls = new HashMap<>();
    /** 0.38.4: the owner-built room's trophy case and jukebox, where set. */
    private Place handTrophies;
    private Place handJukebox;
    /** 0.38.6: the owner-built room's feast table, where set. */
    private Place handFeast;
    /** 0.41.0: the owner-built room's cabinets (game id to place), trophy shelf and door signs, where set. */
    private final Map<String, Place> handCabinets = new java.util.LinkedHashMap<>();
    private Place handTrophyRoom;
    /** 0.43.0: the owner-built room's secret star and room, and its parlor's door, room and machines. */
    private Place handSecret;
    private Place handSecretRoom;
    private Place handParlorDoor;
    private Place handParlorRoom;
    private final Map<String, Place> handParlor = new java.util.LinkedHashMap<>();
    private final Map<String, Place> handDoors = new HashMap<>();
    private BuildJob job;
    private boolean jobScan;
    private long jobStarted;
    private long ticks;
    private boolean confirmClaim;
    /** 0.41.0: {@code rebuild confirm} claims the side rooms' box too, even with blocks in it. */
    private boolean confirmAnnex;
    private boolean verified;
    // for status
    private int builds;
    private long lastWrites;
    private String lastError;
    /** Who may stay in the box while it is built (visitors: the build waits for them). */
    private Predicate<UUID> visitor = id -> false;

    /** The room with the settings as they are now: the box and world are fixed until it stops. */
    public ClubhouseRoom(RoomHost host, ClubhouseSettings settings) {
        this.host = host;
        this.box = settings.mainBox();
        this.annex = settings.annexBox();
        this.full = settings.box();
        this.rooms = settings.rooms();
        String w = host.worldName();
        this.world = w == null ? "" : w.trim();
        this.budget = new BuildBudget(host::nanoTime);
        ClubhouseSite m;
        ClubhouseSite f;
        try {
            m = ClubhousePlanner.plan(box, ClubhousePlanner.Rooms.NONE);
            f = ClubhousePlanner.plan(full, rooms);
        } catch (RuntimeException e) {
            m = null;
            f = null;
            planProblem = e.getMessage();
        }
        this.mainSite = m;
        this.fullSite = f;
        this.site = m;
    }

    /** Who may stay in the box during a build (their writes wait); anyone else is moved out first. */
    public void visitors(Predicate<UUID> isVisitor) {
        this.visitor = isVisitor == null ? id -> false : isVisitor;
    }

    // ---- life -----------------------------------------------------------------------------------

    /** Read the stored mode, the owner's spots and the off switch, then start the boot build (or not). */
    public void start() {
        try {
            off = "1".equals(host.meta(OFF_KEY));
            hand = "hand".equals(host.meta(MODE_KEY));
            handSpawn = Place.of(host.meta(SPAWN_KEY));
            handBoard = Place.of(host.meta(BOARD_KEY));
            for (int n = 0; n < 3; n++) {
                handPodium[n] = Place.of(host.meta(PODIUM_KEY + (n + 1)));
            }
            handWalls.clear();
            for (String which : WallBoards.WALLS) {
                Place at = Place.of(host.meta(WALL_KEY + which));
                if (at != null) {
                    handWalls.put(which, at);
                }
            }
            handTrophies = Place.of(host.meta(TROPHIES_KEY));
            handJukebox = Place.of(host.meta(JUKEBOX_KEY));
            handFeast = Place.of(host.meta(FEAST_KEY));
            editing = "1".equals(host.meta(EDIT_KEY));
            handSecret = Place.of(host.meta(SECRET_KEY));
            handSecretRoom = Place.of(host.meta(SECRET_ROOM_KEY));
            handParlorDoor = Place.of(host.meta(PARLOR_DOOR_KEY));
            handParlorRoom = Place.of(host.meta(PARLOR_ROOM_KEY));
            handParlor.clear();
            handParlor.putAll(cabinetsOf(host.meta(PARLOR_KEY)));
            savedId = host.meta(SAVED_KEY);
            handCabinets.clear();
            handCabinets.putAll(cabinetsOf(host.meta(CABINETS_KEY)));
            handTrophyRoom = Place.of(host.meta(TROPHY_ROOM_KEY));
            handDoors.clear();
            for (String which : DOORS) {
                Place at = Place.of(host.meta(DOOR_KEY + which));
                if (at != null) {
                    handDoors.put(which, at);
                }
            }
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not read its settings from the database", e);
            waitingFor = "its settings can't be read from the database";
            phase = Phase.WAITING;
            return;
        }
        settle();
    }

    /** Stop: drop the build. */
    public void stop() {
        cancelJob();
    }

    /** Where the room should be now, from the mode and the switches. */
    private void settle() {
        cancelJob();
        verified = false;
        closedWhy = null;
        waitingFor = null;
        if (off) {
            close("an admin switched it off (/hcm games clubhouse off)");
            return;
        }
        if (hand) {
            if (handSpawn == null) {
                close("the owner-built room has no arrival spot - stand in it and use /hcm games clubhouse here");
            } else {
                phase = Phase.READY;
            }
            return;
        }
        if (mainSite == null) {
            close("its room could not be made: " + planProblem);
            return;
        }
        if (editing) {
            // 0.42.0: the owner is decorating: open as it stands, nothing built, checked or put back
            try {
                annexIn = claimText(world, annex).equals(host.meta(ANNEX_CLAIM_KEY));
            } catch (Exception e) {
                annexIn = false;
            }
            site = chosenSite();
            phase = Phase.READY;
            return;
        }
        phase = Phase.WAITING;
        tryBuild();
    }

    /**
     * The room to build (0.42.0): the owner's save when one is in use and fits the box as it is now (the main room,
     * with the side rooms when they are in), else HCM's generated room. A save that can't be read or doesn't fit is
     * left aside with a WARN (the generated room is built; {@code restore} picks it again).
     */
    private ClubhouseSite chosenSite() {
        ClubhouseSite gen = annexIn ? fullSite : mainSite;
        savedSite = null;
        savedProblem = null;
        planChanged = false;
        if (savedId == null || gen == null) {
            return gen;
        }
        try {
            ClubSave.Saved s = ClubSave.read(host.readSave(savedId), gen.box());
            // a room HCM added since the save (a region it doesn't cover) comes from HCM's plan, the rest is the save's
            com.dierks.homecraft.games.gen.api.Plan plan = ClubSave.merged(s, gen.plan(),
                    ClubhousePlanner.regions(gen.box(), annexIn ? rooms : ClubhousePlanner.Rooms.NONE));
            savedSite = new ClubhouseSite(plan, gen.spawns(), gen.podium(), gen.board(), gen.inside(), gen.more());
            planChanged = s.generatedHash() != null && !s.generatedHash().equals(gen.plan().hash());
            if (planChanged) {
                host.logger().info("Clubhouse: the Clubhouse plan changed in this update - your saved room is kept."
                        + " /hcm games clubhouse generated to see the new one.");
            }
            return savedSite;
        } catch (Exception e) {
            savedProblem = "your save " + savedId + " can't be used: " + e.getMessage();
            host.logger().warning("Clubhouse: " + savedProblem + " - building HCM's room instead");
            return gen;
        }
    }

    private void close(String why) {
        cancelJob();
        verified = false;
        phase = Phase.CLOSED;
        if (closedWhy == null || !closedWhy.equals(why)) {
            host.logger().warning("Clubhouse closed: " + why);
        }
        closedWhy = why;
    }

    /** One tick: the build's share of work. */
    public void tick() {
        ticks++;
        if (job == null) {
            return;
        }
        if (host.world(world) == null) {
            host.logger().warning("Clubhouse: the world " + world + " went away during a build");
            cancelJob();
            phase = Phase.WAITING;
            waitingFor = "the world " + world + " isn't loaded";
            return;
        }
        budget.begin(budgetNow(), host.anyoneOnline(), host.mspt());
        try {
            job.tick(budget, BUILD_LOADS, peopleHere(), host.now());
        } finally {
            budget.end();
        }
        for (UUID stuck : job.stuckPeople()) {
            if (!visitor.test(stuck)) {
                host.toSafety(stuck, world);
            }
        }
        if (job.done()) {
            BuildJob done = job;
            job = null;
            done.release();
            if (jobKind == JobKind.SCAN_MAIN) {
                scanned(done);
                return;
            }
            if (jobKind == JobKind.SCAN_ANNEX) {
                annexScanned(done);
                return;
            }
            builds++;
            lastWrites = done.writes();
            verified = true;
            phase = Phase.READY;
            lastError = null;
            host.logger().info("Clubhouse: built and checked (" + site.box().describe() + ", " + done.writes()
                    + " blocks written in " + (ticks - jobStarted) + " ticks)");
        } else if (job.failed()) {
            BuildJob failed = job;
            job = null;
            failed.release();
            lastError = failed.error();
            String first = failed.firstFound().isEmpty() ? "" : " (first at " + String.join(" ", failed.firstFound())
                    + ")";
            close("its check failed: " + lastError + first + " - /hcm games clubhouse rebuild confirm tries again");
        }
    }

    /** Once a second: a build waiting for its world or database starts when it can. */
    public void second() {
        if (phase == Phase.WAITING && job == null) {
            tryBuild();
        }
    }

    private void tryBuild() {
        if (hand || off || mainSite == null) {
            return;
        }
        if (world.isEmpty()) {
            waitingFor = "there is no Games world (games.worlds, or games.fresh.world)";
            return;
        }
        WorldPort port = host.world(world);
        if (port == null) {
            waitingFor = "the world " + world + " isn't loaded";
            return;
        }
        List<String> problems = host.regionProblems(box, world);
        if (problems != null && !problems.isEmpty()) {
            close("its box can't be used: " + problems.get(0) + " - move it with games.clubhouse.origin");
            return;
        }
        String claim;
        try {
            claim = host.meta(CLAIM_KEY);
        } catch (Exception e) {
            waitingFor = "its claim can't be read from the database";
            return;
        }
        waitingFor = null;
        boolean claimed = claimText().equals(claim);
        if (!claimed && confirmClaim) {
            try {
                host.meta(CLAIM_KEY, claimText());
            } catch (Exception e) {
                waitingFor = "its claim can't be written to the database";
                return;
            }
            host.logger().warning("Clubhouse: an admin claimed its box (" + box.describe() + "); anything in it is"
                    + " cleared");
            claimed = true;
        }
        confirmClaim = false;
        if (!claimed && claim != null) {
            host.logger().info("Clubhouse: the box moved from " + claim + " - the old one's blocks are left where"
                    + " they are");
        }
        moveStrays();
        if (!claimed) {
            startJob(port, JobKind.SCAN_MAIN);
        } else {
            afterMain(port);
        }
    }

    /**
     * The main box is claimed: the side rooms' box next (0.41.0). Wanted (a room is on) or claimed before (a room
     * switched off is cleared), it must be clear of every other games area; claimed, or scanned empty now, it is
     * built with the main room. Anything in its way leaves the side rooms out (the main room opens as before, and the
     * status says why); {@code rebuild confirm} claims it anyway.
     */
    private void afterMain(WorldPort port) {
        annexIn = false;
        annexOut = null;
        String claim;
        try {
            claim = host.meta(ANNEX_CLAIM_KEY);
        } catch (Exception e) {
            annexOut = "its claim can't be read from the database";
            startJob(port, JobKind.BUILD);
            return;
        }
        boolean claimed = claimText(world, annex).equals(claim);
        if (!rooms.any() && !claimed) {
            startJob(port, JobKind.BUILD); // no side room wanted, and none ever built here
            return;
        }
        List<String> problems = host.regionProblems(annex, world);
        if (problems != null && !problems.isEmpty()) {
            annexOut = "the side rooms' box can't be used: " + problems.get(0);
            host.logger().warning("Clubhouse: the side rooms are left out - " + annexOut);
            startJob(port, JobKind.BUILD);
            return;
        }
        if (!claimed && confirmAnnex) {
            try {
                host.meta(ANNEX_CLAIM_KEY, claimText(world, annex));
                claimed = true;
            } catch (Exception e) {
                annexOut = "its claim can't be written to the database";
            }
        }
        confirmAnnex = false;
        if (claimed) {
            annexIn = true;
            startJob(port, JobKind.BUILD);
            return;
        }
        if (annexOut != null) {
            startJob(port, JobKind.BUILD);
            return;
        }
        startJob(port, JobKind.SCAN_ANNEX);
    }

    /** The annex's count is in: empty, it is claimed and built with the room; anything in it leaves it out. */
    private void annexScanned(BuildJob scan) {
        WorldPort port = host.world(world);
        if (port == null) {
            phase = Phase.WAITING;
            return;
        }
        if (scan.found() > 0) {
            String first = scan.firstFound().isEmpty() ? "" : " (first at " + String.join(" ", scan.firstFound()) + ")";
            annexOut = "the side rooms' box has " + scan.found() + " block" + (scan.found() == 1 ? "" : "s")
                    + " that aren't the Clubhouse's" + first + " - /hcm games clubhouse rebuild confirm clears them";
            host.logger().warning("Clubhouse: the side rooms are left out - " + annexOut);
            startJob(port, JobKind.BUILD);
            return;
        }
        try {
            host.meta(ANNEX_CLAIM_KEY, claimText(world, annex));
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not record the side rooms' claim", e);
            annexOut = "its claim can't be written to the database";
            startJob(port, JobKind.BUILD);
            return;
        }
        host.logger().info("Clubhouse: claimed the side rooms' empty box (" + annex.describe() + ")");
        annexIn = true;
        startJob(port, JobKind.BUILD);
    }

    private void startJob(WorldPort port, JobKind kind) {
        jobKind = kind;
        jobScan = kind != JobKind.BUILD;
        if (kind == JobKind.BUILD) {
            site = chosenSite();
        }
        Box where = kind == JobKind.SCAN_ANNEX ? annex : kind == JobKind.SCAN_MAIN ? box : site.box();
        try {
            job = new BuildJob(port, where, jobScan ? null : site.plan(), jobScan ? BuildJob.Mode.SCAN
                    : BuildJob.Mode.CONVERGE);
        } catch (IllegalArgumentException e) {
            // 0.45.0: a save this server can't build (a block a Minecraft update renamed, a hand-edited file). It threw
            // out of here, the Clubhouse was switched off and stayed off at every reload, with no command left to
            // fix it. Now the room closes with the reason and the saves' commands still answer; nothing is built over
            // the owner's blocks.
            job = null;
            if (savedSite != null && site == savedSite) {
                savedProblem = "your save " + savedId + " can't be built here: " + e.getMessage();
                close(savedProblem + " - /hcm games clubhouse restore <n> for another save, or generated confirm for"
                        + " HCM's room");
                return;
            }
            throw e;
        }
        jobStarted = ticks;
        phase = jobScan ? Phase.SCANNING : Phase.BUILDING;
    }

    /** The claim's count is in: an empty box is claimed and built; anything in it closes the Clubhouse. */
    private void scanned(BuildJob scan) {
        if (scan.found() > 0) {
            String first = scan.firstFound().isEmpty() ? "" : " (first at " + String.join(" ", scan.firstFound()) + ")";
            close("its box has " + scan.found() + " block" + (scan.found() == 1 ? "" : "s") + " that aren't the"
                    + " Clubhouse's" + first + " - /hcm games clubhouse rebuild confirm clears them, or move it with"
                    + " games.clubhouse.origin");
            return;
        }
        try {
            host.meta(CLAIM_KEY, claimText());
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not record its claim", e);
            phase = Phase.WAITING;
            waitingFor = "its claim can't be written to the database";
            return;
        }
        host.logger().info("Clubhouse: claimed its empty box (" + box.describe() + ")");
        WorldPort port = host.world(world);
        if (port == null) {
            phase = Phase.WAITING;
            return;
        }
        afterMain(port);
    }

    private void cancelJob() {
        if (job != null) {
            job.release();
            job = null;
        }
    }

    private DailySettings.Budget budgetNow() {
        return new DailySettings.Budget(BLOCKS_PER_TICK, BLOCKS_PER_TICK, BUILD_MS, BUILD_SNAPSHOTS, BUILD_LOADS,
                PAUSE_MSPT);
    }

    /** Everyone in the room's world (a write waits for anyone next to it). */
    private List<Person> peopleHere() {
        List<Person> out = new ArrayList<>();
        for (Person p : host.people()) {
            if (p.world() != null && p.world().equalsIgnoreCase(world)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Before a build: anyone in the box (the side rooms' too) who isn't a visitor is moved out of its way. */
    private void moveStrays() {
        for (Person p : host.people()) {
            if ((p.in(world, box) || p.in(world, annex)) && !visitor.test(p.id())) {
                host.toSafety(p.id(), world);
            }
        }
    }

    /** What {@value #CLAIM_KEY} holds for the box: the world, its corner and its size. */
    public String claimText() {
        return claimText(world, box);
    }

    /** What {@value #CLAIM_KEY} holds for a box in a world: the world, the box's corner and its size. */
    public static String claimText(String world, Box box) {
        return (world == null ? "" : world.trim().toLowerCase(Locale.ROOT)) + "," + box.minX() + "," + box.minY() + ","
                + box.minZ() + "," + box.sizeX() + "," + box.sizeY() + "," + box.sizeZ();
    }

    // ---- admin ----------------------------------------------------------------------------------

    /**
     * {@code /hcm games clubhouse rebuild [confirm]}: with {@code confirm}, go back to the generated
     * room, switch it on again, claim the box even with blocks in it (the build clears them) and build
     * and check it now. Without, say what it would do.
     */
    public List<String> rebuild(boolean confirm) {
        List<String> out = new ArrayList<>();
        if (!confirm) {
            out.add("&e/hcm games clubhouse rebuild confirm &7builds the generated Clubhouse again and checks it"
                    + " (" + box.describe() + "). Anything else in its box is cleared.");
            return out;
        }
        List<String> problems = world.isEmpty() ? List.of("there is no Games world") : host.regionProblems(box, world);
        if (problems != null && !problems.isEmpty()) {
            out.add("&cThe box can't be used: " + problems.get(0) + " &7- move it with games.clubhouse.origin");
            return out;
        }
        if (!setMeta(OFF_KEY, null) || !setMeta(MODE_KEY, null)) {
            out.add("&cThat can't be saved to the database right now.");
            return out;
        }
        off = false;
        hand = false;
        confirmClaim = true;
        confirmAnnex = rooms.any();
        settle();
        out.add("&aThe Clubhouse is being built and checked. &7/hcm games clubhouse status shows how it goes.");
        return out;
    }

    /** {@code /hcm games clubhouse off}: closed until {@code generated}, {@code here} or {@code rebuild confirm}. */
    public String switchOff() {
        if (!setMeta(OFF_KEY, "1")) {
            return "&cThat can't be saved to the database right now.";
        }
        off = true;
        settle();
        return "&aThe Clubhouse is off. &7Everyone in it goes home; every race and round works as before. "
                + "&e/hcm games clubhouse generated &7(or &ehere&7) opens it again.";
    }

    /** {@code /hcm games clubhouse here}: the owner-built room, arriving at {@code spawn}. */
    public String here(Place spawn) {
        if (spawn == null) {
            return "&cStand in the room first.";
        }
        if (!host.gamesWorld(spawn.world())) { // the Clubhouse review, #13: sessions only take players there
            return "&cThe Clubhouse must be in a Games world, and " + spawn.world() + " isn't one &7(games.worlds)."
                    + " Stand in a room in a Games world and try again.";
        }
        if (!setMeta(SPAWN_KEY, spawn.text()) || !setMeta(MODE_KEY, "hand") || !setMeta(OFF_KEY, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        handSpawn = spawn;
        hand = true;
        off = false;
        settle();
        return "&aYour room is the Clubhouse now. &7Visitors arrive where you stand, facing your way. Set the podium with "
                + "&e/hcm games clubhouse podium <1|2|3> &7and the board with &e/hcm games clubhouse board&7. Nothing"
                + " there is built or guarded.";
    }

    /** {@code /hcm games clubhouse podium <n>}: pedestal {@code n} (1-3) of the owner-built room. */
    public String podium(int n, Place at) {
        if (n < 1 || n > 3) {
            return "&cThe podium has places 1, 2 and 3.";
        }
        if (!setMeta(PODIUM_KEY + n, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handPodium[n - 1] = at;
        return "&aPodium place " + n + " is where you stand." + (hand ? "" : " &7(It's used once the owner-built"
                + " room is on: /hcm games clubhouse here.)");
    }

    /**
     * {@code /hcm games clubhouse board}: where the owner-built room's results board hangs, facing the way the
     * admin faces (0.38.3: locked in place, squared to north, east, south or west).
     */
    public String board(Place at) {
        if (!setMeta(BOARD_KEY, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handBoard = at;
        return "&aThe results board hangs where you stand, facing the way you face." + notOnYet();
    }

    /**
     * {@code /hcm games clubhouse board [fame|playing|stats]} (0.38.3): the results board (no word, or
     * {@code results}), or one of the walls' boards: this week's best, now playing, my stats. Each hangs where the
     * admin stands, facing the way they face. They are optional: the check still asks only for the results board.
     */
    public String board(String which, Place at) {
        String w = which == null ? "" : which.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty() || w.equals(WallBoards.RESULTS)) {
            return board(at);
        }
        if (!WallBoards.WALLS.contains(w)) {
            return "&cThe boards are fame, playing and stats (or just board for the results).";
        }
        if (!setMeta(WALL_KEY + w, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handWalls.put(w, at);
        return "&aThe " + wallName(w) + " board hangs where you stand, facing the way you face." + notOnYet();
    }

    /**
     * {@code /hcm games clubhouse trophies} (0.38.4): where the owner-built room's trophy case starts: the first
     * frame hangs on the block the admin stands in, their back to the wall, and the row runs to their right, a frame
     * every other block (0.38.7: 15 blocks for eight frames).
     */
    public String trophies(Place at) {
        if (!setMeta(TROPHIES_KEY, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handTrophies = at;
        return "&aThe trophy case starts where you stand and runs to your right, a frame every other block." + notOnYet();
    }

    /**
     * {@code /hcm games clubhouse jukebox} (0.38.4): the owner-built room's jukebox is the block under the admin's
     * feet (they place a real jukebox and stand on it). Nothing is placed: without a jukebox there, there is no
     * music, and nothing else changes.
     */
    public String jukebox(Place at) {
        if (!setMeta(JUKEBOX_KEY, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handJukebox = at;
        return "&aThe jukebox is the block under your feet. &7(It plays only while a real jukebox is there.)"
                + notOnYet();
    }

    /**
     * {@code /hcm games clubhouse feast} (0.38.6): the owner-built room's feast table. The admin stands on their
     * table's near corner facing across it: the first plate is the block they stand in, ten run to their right, and
     * ten more one block ahead. Nothing is placed: the plates are item frames, drawn only during the Family Feast.
     */
    public String feast(Place at) {
        if (!setMeta(FEAST_KEY, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handFeast = at;
        return "&aThe feast table starts where you stand: ten plates to your right, ten more in front of them."
                + notOnYet();
    }

    // ---- 0.41.0: the owner-built room's side-room spots ----------------------------------------------

    /** The two door signs: over the Arcade Room's doorway and over the Trophy Room's. */
    public static final List<String> DOORS = List.of("arcade", "trophy");
    /** How far over the admin's feet an owner-built door sign's middle hangs. */
    static final double HAND_DOOR_UP = 3.5;
    /** The owner-built trophy shelf: this many pairs (the podium's three, then the records), two blocks each. */
    static final int SHELF_PAIRS = 3 + ClubhousePlanner.RECORD_PEDESTALS;

    /** "game=place|game=place" as stored, read back in order; anything unreadable is skipped. */
    static Map<String, Place> cabinetsOf(String text) {
        Map<String, Place> out = new java.util.LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            Place at = Place.of(part.substring(eq + 1));
            if (at != null) {
                out.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), at);
            }
        }
        return out;
    }

    /** The owner-built cabinets as stored. */
    static String cabinetsText(Map<String, Place> cabinets) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Place> e : cabinets.entrySet()) {
            out.append(out.length() == 0 ? "" : "|").append(e.getKey()).append('=').append(e.getValue().text());
        }
        return out.toString();
    }

    /**
     * {@code /hcm games clubhouse cabinet <game>} (0.41.0): the owner-built room's cabinet for {@code gameId} stands on
     * the block the admin stands on, facing the way they face (squared). The caller has checked it is a cabinet game.
     */
    public String cabinet(String gameId, Place at) {
        Map<String, Place> next = new java.util.LinkedHashMap<>(handCabinets);
        next.put(gameId, at);
        if (!setMeta(CABINETS_KEY, cabinetsText(next))) {
            return "&cThat can't be saved to the database right now.";
        }
        handCabinets.clear();
        handCabinets.putAll(next);
        return "&aThe " + gameId + " cabinet stands where you stand, facing the way you face." + notOnYet();
    }

    /** {@code /hcm games clubhouse cabinet <game> clear} (or {@code all}): the cabinet comes down. */
    public String clearCabinet(String gameId) {
        Map<String, Place> next = new java.util.LinkedHashMap<>(handCabinets);
        boolean all = "all".equals(gameId);
        if (all) {
            next.clear();
        } else if (next.remove(gameId) == null) {
            return "&7There is no " + gameId + " cabinet to clear.";
        }
        if (!setMeta(CABINETS_KEY, next.isEmpty() ? null : cabinetsText(next))) {
            return "&cThat can't be saved to the database right now.";
        }
        handCabinets.clear();
        handCabinets.putAll(next);
        return all ? "&aEvery cabinet is cleared." : "&aThe " + gameId + " cabinet is cleared.";
    }

    /**
     * {@code /hcm games clubhouse trophyroom} (0.41.0): the owner-built room's trophy shelf starts on the block the
     * admin stands on (on their shelf, their back to the wall): {@value #SHELF_PAIRS} pairs run to their right, a
     * trophy then its holder's head, the podium's three first. {@code clear} takes it down.
     */
    public String trophyRoom(Place at) {
        if (!setMeta(TROPHY_ROOM_KEY, at == null ? null : at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handTrophyRoom = at;
        return at == null ? "&aThe trophy shelf is cleared."
                : "&aThe trophy shelf starts where you stand and runs " + 2 * SHELF_PAIRS + " blocks to your right: a"
                + " trophy, then its holder's head." + notOnYet();
    }

    /**
     * {@code /hcm games clubhouse door arcade|trophy} (0.41.0): the owner-built room's sign over that doorway hangs
     * {@value #HAND_DOOR_UP} blocks over the admin's feet, facing the way they face. {@code null}: cleared.
     */
    public String door(String which, Place at) {
        if (!DOORS.contains(which)) {
            return "&cThe door signs are arcade and trophy.";
        }
        if (!setMeta(DOOR_KEY + which, at == null ? null : at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        if (at == null) {
            handDoors.remove(which);
            return "&aThe " + which + " door sign is cleared.";
        }
        handDoors.put(which, at);
        return "&aThe " + which + " door sign hangs over where you stand, facing the way you face." + notOnYet();
    }

    /**
     * The Arcade Room's cabinet places (0.41.0) for the kid set {@code games} (already checked): the generated room's
     * places in order, while the room is built; the owner-built room's own cabinet for each game that has one (only in
     * its world). Empty while there is none.
     */
    public List<Cabinet> cabinetPlaces(List<String> games) {
        List<Cabinet> out = new ArrayList<>();
        if (games == null || games.isEmpty()) {
            return out;
        }
        if (!hand) {
            if (!arcadeBuilt()) {
                return out;
            }
            // 0.42.0: every place has a cabinet, the kid set taken round again to fill the room ("repeats are fine")
            List<ClubhousePlanner.Place3> at = ClubhousePlanner.cabinets(site.box());
            for (int n = 0; n < at.size(); n++) {
                out.add(new Cabinet(games.get(n % games.size()), at.get(n)));
            }
            // 0.45.0: in an edited or saved room a cabinet the owner moved takes its game's first place; the others
            // stay (moving one used to take all the generated ones away)
            for (String g : games) {
                Place moved = handCabinets.get(g);
                if (overrides() && mine(moved)) {
                    moveFirst(out, g, new ClubhousePlanner.Place3(Math.floor(moved.x()) + 0.5, Math.floor(moved.y()),
                            Math.floor(moved.z()) + 0.5, WallBoards.snap(moved.yaw())));
                }
            }
            return out;
        }
        for (String g : games) {
            Place at = handCabinets.get(g);
            if (at != null && at.world().equalsIgnoreCase(world())) {
                out.add(new Cabinet(g, new ClubhousePlanner.Place3(Math.floor(at.x()) + 0.5, Math.floor(at.y()),
                        Math.floor(at.z()) + 0.5, WallBoards.snap(at.yaw()))));
            }
        }
        return out;
    }

    /** The generated room's cabinet places, in order, while its Arcade Room is built; none in an owner-built room. */
    public List<ClubhousePlanner.Place3> generatedCabinetPlaces() {
        return hand || !arcadeBuilt() ? List.of() : ClubhousePlanner.cabinets(site.box());
    }

    /** The first of {@code list} that plays {@code game} stands at {@code at} now (none: one more, there). */
    static void moveFirst(List<Cabinet> list, String game, ClubhousePlanner.Place3 at) {
        for (int n = 0; n < list.size(); n++) {
            if (list.get(n).game().equals(game)) {
                list.set(n, new Cabinet(game, at));
                return;
            }
        }
        list.add(new Cabinet(game, at));
    }

    /** A cabinet in the Arcade Room: the game it plays and where it stands (0.42.0: a game may have two). */
    public record Cabinet(String game, ClubhousePlanner.Place3 at) {
    }

    /**
     * 0.42.0: the generated room's decoration that isn't blocks ({@link ClubhousePlanner#decor}), while it is open;
     * none in an owner-built room (its decoration is the owner's).
     */
    public List<ClubhousePlanner.Prop> decor() {
        if (site == null || !open()) {
            return List.of();
        }
        List<ClubhousePlanner.Prop> out = new ArrayList<>();
        if (!hand) {
            out.addAll(ClubhousePlanner.decor(site.box(), annexIn ? rooms : ClubhousePlanner.Rooms.NONE));
        }
        if (hand || mine(handFeast)) {
            // the owner's own feast table: the Feast Pot (the real one, 0.42.0) just past the end of the plates' first
            // row; the generated table's pot, bread and pie don't stand on the old cloth
            out.removeIf(p -> p.key().startsWith("feast."));
            ClubhousePlanner.Prop pot = handPot();
            if (pot != null) {
                out.add(pot);
            }
        }
        return out;
    }

    /** The back bar's shelves to stock (0.44.2): the generated or saved room's, none in an owner-built one. */
    public List<int[]> barShelves() {
        if (hand || site == null || !open()) {
            return List.of();
        }
        return ClubhousePlanner.barShelves(site.box());
    }

    /** The Feast Pot by an owner-built feast table: one cell on from the first row's last plate, or {@code null}. */
    ClubhousePlanner.Prop handPot() {
        List<int[]> cells = feastCells();
        Place at = handFeast;
        if (cells == null || cells.isEmpty() || at == null) {
            return null;
        }
        int[] right = right(at.yaw());
        int[] last = cells.get(com.dierks.homecraft.arcade.feast.FeastRules.CELLS / 2 - 1);
        return ClubhousePlanner.Prop.item("feast.pot", "CAULDRON", last[0] + right[0] + 0.5,
                last[1] + ClubhousePlanner.POT_SIZE / 2, last[2] + right[1] + 0.5, 0f, ClubhousePlanner.POT_SIZE,
                "fixed");
    }

    /** Whether feet at (x, y, z) are in the Arcade Room (0.42.0, its sound): the generated room's, or near a cabinet. */
    public boolean inArcadeRoom(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equalsIgnoreCase(world())) {
            return false;
        }
        if (!hand) {
            return arcadeBuilt() && ClubhousePlanner.arcadeInside(site.box()).contains(x, y, z);
        }
        for (Place at : handCabinets.values()) {
            if (at.world().equalsIgnoreCase(world()) && Math.abs(at.x() - x) <= 8 && Math.abs(at.z() - z) <= 8
                    && Math.abs(at.y() - y) <= 4) {
                return true;
            }
        }
        return false;
    }

    /** The games with an owner-built cabinet, in the order they were set (for the kid set in that room). */
    public List<String> handCabinetGames() {
        return List.copyOf(handCabinets.keySet());
    }

    /**
     * The Trophy Room's pedestals (0.41.0): the generated room's ({@link ClubhousePlanner#pedestals}) while it is
     * built, or the owner-built shelf's pairs along the admin's right (only in its world); empty for none.
     */
    public List<ClubhousePlanner.Pedestal> pedestals() {
        if (!mine(handTrophyRoom)) {
            return trophyBuilt() ? ClubhousePlanner.pedestals(site.box()) : List.of();
        }
        Place at = handTrophyRoom;
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return List.of();
        }
        int[] r = right(at.yaw());
        float yaw = WallBoards.snap(at.yaw());
        double x0 = Math.floor(at.x()) + 0.5;
        double z0 = Math.floor(at.z()) + 0.5;
        double y = Math.floor(at.y());
        List<ClubhousePlanner.Pedestal> out = new ArrayList<>();
        for (int n = 0; n < SHELF_PAIRS; n++) {
            int t = 2 * n;
            out.add(new ClubhousePlanner.Pedestal(new ClubhousePlanner.Place3(x0 + t * r[0], y, z0 + t * r[1], yaw),
                    new ClubhousePlanner.Place3(x0 + (t + 1) * r[0], y, z0 + (t + 1) * r[1], yaw)));
        }
        return out;
    }

    /** Where door sign {@code which} hangs (its middle and facing), or {@code null} for none (0.41.0). */
    public ClubhousePlanner.Place3 doorSign(String which) {
        if (!mine(handDoors.get(which))) {
            boolean arcade = "arcade".equals(which);
            return (arcade ? arcadeBuilt() : trophyBuilt()) ? ClubhousePlanner.door(site.box(), arcade) : null;
        }
        Place at = handDoors.get(which);
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return null;
        }
        return new ClubhousePlanner.Place3(Math.floor(at.x()) + 0.5, Math.floor(at.y()) + HAND_DOOR_UP,
                Math.floor(at.z()) + 0.5, WallBoards.snap(at.yaw()));
    }

    /**
     * Whether feet at (x, y, z) are in the Trophy Room (0.41.0, for the shimmer): the generated room's, or within
     * {@value #SHELF_REACH} blocks of the owner-built shelf's start.
     */
    public boolean inTrophyRoom(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equalsIgnoreCase(world())) {
            return false;
        }
        if (!mine(handTrophyRoom)) {
            return trophyBuilt() && ClubhousePlanner.trophyInside(site.box()).contains(x, y, z);
        }
        Place at = handTrophyRoom;
        return at != null && Math.abs(x - at.x()) <= SHELF_REACH && Math.abs(z - at.z()) <= SHELF_REACH
                && Math.abs(y - at.y()) <= 4;
    }

    /** How near the owner-built shelf's start counts as being at it. */
    static final double SHELF_REACH = 2 * SHELF_PAIRS;

    /** Whether the generated room's Arcade Room is built in (open, the side rooms' box in, and switched on). */
    private boolean arcadeBuilt() {
        return site != null && annexIn && rooms.arcade() && open();
    }

    private boolean trophyBuilt() {
        return site != null && annexIn && rooms.trophy() && open();
    }

    // ---- 0.43.0: upstairs: the secret trophy room and the Game Parlor -----------------------------------

    /** How near an owner-built upstairs room's arrival spot still counts as in it. */
    static final double UP_REACH = 10;

    private boolean secretBuilt() {
        return site != null && annexIn && rooms.secret() && open();
    }

    private boolean parlorBuilt() {
        return site != null && annexIn && rooms.parlor() && open();
    }

    /** A recorded place as a spot (its block's middle, squared), or {@code null}. */
    private ClubhousePlanner.Place3 place3(Place p, double up) {
        return p == null || !p.world().equalsIgnoreCase(world()) ? null : new ClubhousePlanner.Place3(
                Math.floor(p.x()) + 0.5, Math.floor(p.y()) + up, Math.floor(p.z()) + 0.5, WallBoards.snap(p.yaw()));
    }

    private ClubhouseSite.Spot spot(Place p, int ahead) {
        if (p == null || !p.world().equalsIgnoreCase(world())) {
            return null;
        }
        int[] a = ahead(p.yaw());
        return new ClubhouseSite.Spot(Math.floor(p.x()) + 0.5 + a[0] * ahead, Math.floor(p.y()),
                Math.floor(p.z()) + 0.5 + a[1] * ahead, WallBoards.snap(p.yaw()));
    }

    /** The secret room's way in: the little gold star (0.43.0), or {@code null}. */
    public ClubhousePlanner.Place3 secretDoor() {
        if (!mine(handSecret)) {
            return secretBuilt() ? ClubhousePlanner.secretDoor(site.box()) : null;
        }
        return place3(handSecret, 0); // 0.45.0: a door, standing where the admin stood, facing the way they faced
    }

    /** Where a visitor stands after coming out of the secret room: in front of the star. */
    public ClubhouseSite.Spot secretReturn() {
        if (!mine(handSecret)) {
            return secretBuilt() ? ClubhousePlanner.secretReturn(site.box()) : null;
        }
        return spot(handSecret, 1);
    }

    /** Where the secret room's visitor arrives. */
    public ClubhouseSite.Spot secretArrival() {
        if (!mine(handSecretRoom)) {
            return secretBuilt() ? ClubhousePlanner.secretArrival(site.box()) : null;
        }
        return spot(handSecretRoom, 0);
    }

    /** The secret room's door back out (behind its arrival spot). */
    public ClubhousePlanner.Place3 secretExit() {
        if (!mine(handSecretRoom)) {
            return secretBuilt() ? ClubhousePlanner.secretExit(site.box()) : null;
        }
        ClubhouseSite.Spot s = spot(handSecretRoom, -1);
        return s == null ? null : new ClubhousePlanner.Place3(s.x(), s.y(), s.z(), s.yaw());
    }

    /** The secret room's centre pedestal top (the visitor's head), two ahead of the arrival in an owner-built room. */
    public ClubhousePlanner.Place3 secretCentre() {
        if (!mine(handSecretRoom)) {
            return secretBuilt() ? ClubhousePlanner.secretCentre(site.box()) : null;
        }
        ClubhouseSite.Spot s = spot(handSecretRoom, 2);
        return s == null ? null : new ClubhousePlanner.Place3(s.x(), s.y() + 1, s.z(), WallBoards.snap(s.yaw() + 180f));
    }

    /**
     * The secret room's trophy places: the generated room's ledges, or (owner-built) a row of {@value #SHELF_ROW} to
     * the admin's right, three ahead of the arrival, facing back to it.
     */
    public List<ClubhousePlanner.Place3> secretShelf() {
        if (!mine(handSecretRoom)) {
            return secretBuilt() ? ClubhousePlanner.secretShelf(site.box()) : List.of();
        }
        ClubhouseSite.Spot s = spot(handSecretRoom, 3);
        if (s == null) {
            return List.of();
        }
        int[] r = right(handSecretRoom.yaw());
        float back = WallBoards.snap(s.yaw() + 180f);
        List<ClubhousePlanner.Place3> out = new ArrayList<>();
        for (int n = 0; n < SHELF_ROW; n++) {
            int d = n - SHELF_ROW / 2;
            out.add(new ClubhousePlanner.Place3(s.x() + d * r[0], s.y(), s.z() + d * r[1], back));
        }
        return out;
    }

    /** An owner-built secret room's row of trophy places. */
    static final int SHELF_ROW = 16;

    /** Whether the secret room's trophies stand on the generated room's ledges (a plaque on each ledge's front). */
    public boolean secretOnLedges() {
        return !mine(handSecretRoom) && secretBuilt();
    }

    /** Whether feet are in the secret room (its air; owner-built: near its arrival spot). */
    public boolean inSecretRoom(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equalsIgnoreCase(world())) {
            return false;
        }
        if (!mine(handSecretRoom)) {
            return secretBuilt() && ClubhousePlanner.secretInside(site.box()).contains(x, y, z);
        }
        return near(handSecretRoom, x, y, z, UP_REACH);
    }

    /** The Game Parlor's door (in the 19th Hole), and where its visitor comes back out. */
    public ClubhousePlanner.Place3 parlorStar() {
        if (!mine(handParlorDoor)) {
            return parlorBuilt() ? ClubhousePlanner.parlorStar(site.box()) : null;
        }
        return place3(handParlorDoor, 1.5); // 0.45.0: a star, at eye height where the admin stood
    }

    public ClubhouseSite.Spot parlorReturn() {
        if (!mine(handParlorDoor)) {
            return parlorBuilt() ? ClubhousePlanner.parlorReturn(site.box()) : null;
        }
        return spot(handParlorDoor, 1);
    }

    /** Where the parlor's visitor arrives, and its door back out (behind it). */
    public ClubhouseSite.Spot parlorArrival() {
        if (!mine(handParlorRoom)) {
            return parlorBuilt() ? ClubhousePlanner.parlorArrival(site.box()) : null;
        }
        return spot(handParlorRoom, 0);
    }

    public ClubhousePlanner.Place3 parlorExit() {
        if (!mine(handParlorRoom)) {
            return parlorBuilt() ? ClubhousePlanner.parlorExit(site.box()) : null;
        }
        ClubhouseSite.Spot s = spot(handParlorRoom, -1);
        return s == null ? null : new ClubhousePlanner.Place3(s.x(), s.y(), s.z(), s.yaw());
    }

    /**
     * The parlor's machines: the generated room's (two Ore Slots, the Wheel, Coin Flip, the two tables, and the
     * five slot machines along the back row), or the owner's.
     */
    public List<Cabinet> parlorMachines() {
        List<Cabinet> out = new ArrayList<>();
        if (!hand) {
            if (!parlorBuilt()) {
                return out;
            }
            List<ClubhousePlanner.Place3> at = ClubhousePlanner.parlorMachines(site.box());
            for (int n = 0; n < at.size(); n++) {
                out.add(new Cabinet(ClubhousePlanner.PARLOR_GAMES.get(n), at.get(n)));
            }
            List<ClubhousePlanner.Place3> row = ClubhousePlanner.parlorRow(site.box()); // After the first six
            for (int n = 0; n < row.size(); n++) {
                out.add(new Cabinet(ClubhousePlanner.PARLOR_ROW_GAMES.get(n), row.get(n)));
            }
            for (Map.Entry<String, Place> e : handParlor.entrySet()) { // 0.45.0: a moved machine, the rest stay
                if (overrides() && mine(e.getValue()) && !notAMachine(e.getKey())) {
                    moveFirst(out, e.getKey(), place3(e.getValue(), 0));
                }
            }
            return out;
        }
        for (Map.Entry<String, Place> e : handParlor.entrySet()) {
            ClubhousePlanner.Place3 p = place3(e.getValue(), 0);
            if (p != null && !notAMachine(e.getKey())) {
                out.add(new Cabinet(e.getKey(), p));
            }
        }
        return out;
    }

    /** Mob Races' id: its place in the Parlor is a whole table's, never a machine's. */
    static final String MOB_RACES = com.dierks.homecraft.games.chance.mobraces.MobRacesSettings.ID;

    /** The Parlor's places that are not a machine standing on one block: the ticket booth, and Mob Races' track. */
    private static boolean notAMachine(String key) {
        return ClubhousePlanner.TICKET.equals(key) || MOB_RACES.equals(key);
    }

    /**
     * Where Mob Races' track is.
     *
     * @param frame the table it is drawn on
     * @param own   the owner placed it ({@code parlor mob_races}); false: it is the generated room's own table
     * @param built the table is known to be there: the generated room, built and checked. Otherwise (the owner's
     *              place, or a room being edited or kept from a save) the blocks are the owner's, and whoever draws
     *              the track looks for a table under it first
     */
    record RaceSpot(RaceTable.Frame frame, boolean own, boolean built) {
    }

    /**
     * Mob Races' track, or {@code null} for none: in the generated room the table of the plan, in the middle of the
     * Parlor; where the owner placed one ({@code /hcm games clubhouse parlor mob_races}, standing on a corner of a
     * table they built, facing along it) in an owner-built room, or in a room they edit or saved. A saved or edited
     * room keeps the blocks it has, so HCM's table is only there if the owner's room has it: {@link RaceSpot#built}
     * says whether that still has to be looked for.
     */
    RaceSpot raceTrack() {
        Place own = handParlor.get(MOB_RACES);
        if (hand) {
            return own == null || !own.world().equalsIgnoreCase(world()) ? null
                    : new RaceSpot(RaceTable.Frame.standingOn(own.x(), own.y(), own.z(), own.yaw()), true, false);
        }
        if (!parlorBuilt()) {
            return null;
        }
        if (own != null && overrides() && mine(own)) {
            return new RaceSpot(RaceTable.Frame.standingOn(own.x(), own.y(), own.z(), own.yaw()), true, false);
        }
        return new RaceSpot(ClubhousePlanner.raceTable(site.box()), false, !overrides());
    }

    /**
     * 0.46.1: where the Scratch Ticket's booth stands in the parlor, or {@code null}: the generated room's place (or
     * where the owner moved it), or the owner-built room's own ({@code parlor ticket}); none until the owner sets one
     * there.
     */
    public ClubhousePlanner.Place3 parlorTicket() {
        Place own = handParlor.get(ClubhousePlanner.TICKET);
        if (!hand) {
            if (!parlorBuilt()) {
                return null;
            }
            return own != null && overrides() && mine(own) ? place3(own, 0)
                    : ClubhousePlanner.parlorTicket(site.box());
        }
        return own == null ? null : place3(own, 0);
    }

    /** Whether feet are in the Game Parlor (its air; owner-built: near its arrival spot). */
    public boolean inParlor(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equalsIgnoreCase(world())) {
            return false;
        }
        if (!mine(handParlorRoom)) {
            return parlorBuilt() && ClubhousePlanner.parlorInside(site.box()).contains(x, y, z);
        }
        return near(handParlorRoom, x, y, z, UP_REACH);
    }

    private static boolean near(Place p, double x, double y, double z, double reach) {
        return p != null && Math.abs(x - p.x()) <= reach && Math.abs(z - p.z()) <= reach && Math.abs(y - p.y()) <= 4;
    }

    /**
     * {@code /hcm games clubhouse secret [room] [clear]} and {@code parlor door|room|<game> [clear]} (0.43.0), where
     * the admin stands: the star (on the block they stand in, facing their way), the secret room's arrival (the door
     * out behind them, the trophies along a row ahead), the parlor's door and arrival, and a machine per game of chance.
     */
    public String upstairs(String what, String sub, Place at, boolean chanceGame) {
        String key;
        String said;
        switch (what) {
            case "secret" -> {
                boolean room = "room".equals(sub);
                key = room ? SECRET_ROOM_KEY : SECRET_KEY;
                said = room ? "The secret room's visitors arrive where you stand; its door out is behind you and its"
                        + " trophies along a row three ahead." : "The secret room's door is where you stand, facing"
                        + " the way you face.";
            }
            case "parlor" -> {
                if ("door".equals(sub) || "star".equals(sub) || "room".equals(sub)) {
                    boolean room = "room".equals(sub);
                    key = room ? PARLOR_ROOM_KEY : PARLOR_DOOR_KEY;
                    said = room ? "The Game Parlor's visitors arrive where you stand; its door out is behind you."
                            : "The Game Parlor's gold star is where you stand.";
                } else {
                    if (sub == null || sub.isBlank()) {
                        return "&cWhich? &e/hcm games clubhouse parlor star|room|<game>|ticket|mob_races [clear]";
                    }
                    if (at != null && !chanceGame && !ClubhousePlanner.TICKET.equals(sub)) {
                        return "&cThe Game Parlor's machines are the games of chance (ore_slots, wheel, coin_flip,"
                                + " twenty_one, higher_lower, nether_slots, motherlode, farm_slots, deep_mine,"
                                + " mob_slots), the Scratch Ticket's booth (ticket) and Mob Races' track (mob_races).";
                    }
                    Map<String, Place> next = new java.util.LinkedHashMap<>(handParlor);
                    if (at == null) {
                        next.remove(sub);
                    } else {
                        next.put(sub, at);
                    }
                    if (!setMeta(PARLOR_KEY, next.isEmpty() ? null : cabinetsText(next))) {
                        return "&cThat can't be saved to the database right now.";
                    }
                    handParlor.clear();
                    handParlor.putAll(next);
                    if (MOB_RACES.equals(sub)) {
                        return at == null ? "&aMob Races' track is cleared here."
                                : "&aMob Races' track is drawn on the table you stand on: "
                                + com.dierks.homecraft.games.chance.mobraces.RaceTrack.LENGTH + " blocks the way you"
                                + " face, " + com.dierks.homecraft.games.chance.mobraces.RaceTrack.DEPTH
                                + " to your right, one block high. &7Stand on a corner of it. Nothing is built for"
                                + " you: with no table there it isn't drawn. To keep players off the track, leave one"
                                + " block of air over the table and put barrier blocks over that." + notOnYet();
                    }
                    return at == null ? "&aThe " + sub + " machine is cleared."
                            : "&aThe " + sub + " machine stands where you stand." + notOnYet();
                }
            }
            default -> {
                return "&cThat's secret or parlor.";
            }
        }
        if (!setMeta(key, at == null ? null : at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        switch (key) {
            case SECRET_ROOM_KEY -> handSecretRoom = at;
            case SECRET_KEY -> handSecret = at;
            case PARLOR_ROOM_KEY -> handParlorRoom = at;
            default -> handParlorDoor = at;
        }
        return at == null ? "&aCleared." : "&a" + said + notOnYet();
    }

    // ---- 0.42.0: edit mode and saves -----------------------------------------------------------------

    /**
     * {@code /hcm games clubhouse edit}: edit mode on. Admins ({@code hcm.games.admin}) may place and break blocks in
     * the whole box, side rooms too; nothing is built, checked or put back meanwhile, and visitors still come in. It
     * stays on across a restart until {@code save confirm} or {@code discard}.
     */
    public String edit() {
        if (hand) {
            return "&cYour owner-built room is yours already: change it as you like. &7(Edit mode is for HCM's room.)";
        }
        if (off) {
            return "&cThe Clubhouse is switched off. &e/hcm games clubhouse generated &7first.";
        }
        if (editing) {
            return "&7Edit mode is on already. &e/hcm games clubhouse save &7when you're done, or &ediscard&7.";
        }
        if (!open()) {
            return "&cThe Clubhouse isn't built and checked yet. &7See /hcm games clubhouse status.";
        }
        if (!setMeta(EDIT_KEY, "1")) {
            return "&cThat can't be saved to the database right now.";
        }
        cancelJob();
        editing = true;
        verified = false;
        phase = Phase.READY;
        return "&aEdit mode is on. &7Admins can place and break blocks anywhere in the Clubhouse, side rooms too;"
                + " nothing is put back while you work, and visitors still come in. &e/hcm games clubhouse save &7checks"
                + " it, &esave confirm &7keeps it, &ediscard &7puts the room back.";
    }

    /** Whether edit mode is on. */
    public boolean editing() {
        return editing;
    }

    /** The save in use, or {@code null} for HCM's generated room. */
    public String savedId() {
        return savedId;
    }

    /** Whether HCM's plan changed in an update since the save in use was taken (the admins are told). */
    public boolean planChanged() {
        return planChanged && savedSite != null;
    }

    /**
     * {@code /hcm games clubhouse save [confirm]}: every block of the box as it stands now (its chunks are loaded first,
     * so the answer comes a moment later through {@code reply}). Without {@code confirm} it only says what it found:
     * how many blocks, any spot the room uses that a block covers or that lost its block (with where; {@code spot}
     * moves one), and any place a mob could stand in the dark. With it, the blocks become the room: kept as a save (the
     * newest {@value #SAVES_KEPT}), edit mode off, the guard back on, and from then on built, checked and put back after
     * a crash from the save.
     */
    public void save(boolean confirm, java.util.function.Consumer<List<String>> reply) {
        if (hand) {
            reply.accept(List.of("&cYour owner-built room isn't saved: it's yours as it stands."));
            return;
        }
        if (site == null || !open()) {
            reply.accept(List.of("&cThe Clubhouse isn't open. &7See /hcm games clubhouse status."));
            return;
        }
        if (saving) {
            reply.accept(List.of("&7A save is being taken already - a moment."));
            return;
        }
        WorldPort port = host.world(world);
        if (port == null) {
            reply.accept(List.of("&cThe Clubhouse's world isn't loaded."));
            return;
        }
        Box b = site.box();
        List<int[]> chunks = new ArrayList<>();
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }
        saving = true;
        int[] left = {chunks.size()};
        boolean[] failed = {false};
        for (int[] c : chunks) {
            port.load(c[0], c[1], ok -> {
                failed[0] |= !ok;
                if (--left[0] == 0) {
                    try {
                        reply.accept(failed[0] ? List.of("&cThe Clubhouse's chunks couldn't be loaded - try again.")
                                : saveNow(port, b, confirm));
                    } finally {
                        for (int[] r : chunks) {
                            port.release(r[0], r[1]);
                        }
                        saving = false;
                    }
                }
            });
        }
    }

    /** The save itself, once the box's chunks are loaded. */
    private List<String> saveNow(WorldPort port, Box b, boolean confirm) {
        Map<Long, WorldPort.ChunkView> views = new HashMap<>();
        com.dierks.homecraft.games.gen.api.Plan plan = ClubSave.snapshot(b, at -> {
            long k = ((long) (at[0] >> 4) << 32) ^ (at[2] >> 4);
            WorldPort.ChunkView v = views.computeIfAbsent(k, kk -> port.snapshot(at[0] >> 4, at[2] >> 4));
            String block = v == null ? null : v.block(at[0], at[1], at[2]);
            // 0.45.0: a jukebox mid-song is saved empty (the disc is the Clubhouse's own, never part of the room)
            return block != null && block.startsWith("minecraft:jukebox[")
                    ? block.replace("has_record=true", "has_record=false") : block;
        });
        ClubhouseSite check = new ClubhouseSite(plan, site.spawns(), site.podium(), site.board(), site.inside(),
                site.more());
        List<String> spots = ClubSave.spotProblems(plan, spots());
        List<int[]> dark = ClubSave.dark(plan, check, DARK_LISTED);
        List<String> out = new ArrayList<>();
        out.add("&6The Clubhouse as it stands: &f" + plan.ops().size() + " blocks &7(" + b.describe() + ")");
        for (String why : spots) {
            out.add("&e! &7" + why + " &8- move it with /hcm games clubhouse spot ...");
        }
        if (!dark.isEmpty()) {
            StringBuilder d = new StringBuilder();
            for (int[] p : dark) {
                d.append(d.length() == 0 ? "" : ", ").append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]);
            }
            out.add("&e! &7Mobs could spawn in the dark at " + d + (dark.size() >= DARK_LISTED ? " and more" : "")
                    + " &8- a light nearby fixes it (your call)");
        }
        if (spots.isEmpty() && dark.isEmpty()) {
            out.add("&a\u2714 &7Every spot is clear and every place is lit.");
        }
        if (!confirm) {
            out.add("&e/hcm games clubhouse save confirm &7to keep it as the Clubhouse.");
            return out;
        }
        String id = "save-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date(host.now()));
        String genHash = (annexIn ? fullSite : mainSite).plan().hash();
        try {
            host.writeSave(id, ClubSave.text(plan, genHash, ClubhousePlanner.regions(b,
                    annexIn ? rooms : ClubhousePlanner.Rooms.NONE).keySet()));
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not write the save", e);
            out.add("&cThe save couldn't be written: " + e.getMessage());
            return out;
        }
        if (!setMeta(SAVED_KEY, id) || !setMeta(EDIT_KEY, null)) {
            out.add("&cThe save is written but couldn't be made the room's - see the console.");
            return out;
        }
        try { // 0.45.0: the oldest go only once the new one is the room's (never the save still in use)
            List<String> all = new ArrayList<>(host.saves());
            all.sort(null);
            while (all.size() > SAVES_KEPT) {
                String gone = all.remove(0);
                if (!gone.equals(id)) {
                    host.deleteSave(gone);
                }
            }
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not tidy the old saves", e);
        }
        savedId = id;
        editing = false;
        settle(); // built (nothing to write) and checked from the save, guarded again
        out.add("&aSaved as &f" + id + "&a. &7The Clubhouse is your room now: built, checked and put back from it."
                + " Edit mode is off.");
        return out;
    }

    /**
     * {@code /hcm games clubhouse discard}: edit mode off, and the room put back to the save in use (or HCM's
     * generated room): every block changed since is built back.
     */
    public String discard() {
        if (!editing) {
            return "&7Edit mode isn't on.";
        }
        if (!setMeta(EDIT_KEY, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        editing = false;
        settle();
        return "&aEdit mode is off. &7The room goes back to " + (savedId == null ? "HCM's room" : "your save " + savedId)
                + ": changed blocks are put back.";
    }

    /** {@code /hcm games clubhouse saves}: the saves kept, newest first, the one in use marked. */
    public List<String> savesList() {
        List<String> ids = new ArrayList<>(host.saves());
        ids.sort(java.util.Comparator.reverseOrder());
        List<String> out = new ArrayList<>();
        out.add("&6Clubhouse saves &7(newest first; &erestore <n>&7)");
        if (ids.isEmpty()) {
            out.add("&7None yet. &e/hcm games clubhouse edit&7, decorate, then &esave confirm&7.");
        }
        for (int n = 0; n < ids.size(); n++) {
            out.add("&e" + (n + 1) + ". &f" + ids.get(n) + (ids.get(n).equals(savedId) ? " &a(in use)" : ""));
        }
        out.add("&7In use: " + (savedId == null ? "HCM's generated room" : savedId) + (editing ? " &e(edit mode on)" : ""));
        return out;
    }

    /** {@code /hcm games clubhouse restore <n>}: the {@code n}th newest save becomes the room (built and checked). */
    public String restore(int n) {
        List<String> ids = new ArrayList<>(host.saves());
        ids.sort(java.util.Comparator.reverseOrder());
        if (n < 1 || n > ids.size()) {
            return "&cThere's no save " + n + ". &7/hcm games clubhouse saves lists them.";
        }
        if (hand) {
            return "&cThe owner-built room is on. &e/hcm games clubhouse generated &7first.";
        }
        if (editing) { // 0.45.0: never over unsaved edits without a word
            return "&cYou're in edit mode. &e/hcm games clubhouse save confirm &7keeps your edits, &e/hcm games"
                    + " clubhouse discard &7drops them; then restore.";
        }
        String id = ids.get(n - 1);
        if (!setMeta(SAVED_KEY, id) || !setMeta(EDIT_KEY, null) || !setMeta(OFF_KEY, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        savedId = id;
        editing = false;
        off = false;
        settle();
        if (savedProblem != null) {
            return "&c" + savedProblem + ".";
        }
        return "&aThe Clubhouse is your save &f" + id + " &aagain. &7It is built and checked before anyone comes in.";
    }

    /**
     * {@code /hcm games clubhouse spot arrival [clear]}: in the edited or saved room, visitors arrive where the admin
     * stands (instead of the Foyer's sixteen spots); {@code clear} gives the sixteen back. Every other spot moves with
     * its own command ({@code podium}, {@code board}, {@code trophies}, {@code jukebox}, {@code feast}, {@code cabinet},
     * {@code trophyroom}, {@code door}).
     */
    public String spotArrival(Place at) {
        if (hand) {
            return here(at);
        }
        if (!setMeta(SPAWN_KEY, at == null ? null : at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handSpawn = at;
        return at == null ? "&aVisitors arrive at the Foyer's spots again."
                : "&aVisitors arrive where you stand." + notOnYet();
    }

    /**
     * {@code spot <podium <n>|board [which]|trophies|jukebox|feast> clear} (0.42.0): the spot goes back to the
     * generated room's place.
     */
    public String clearSpot(String which, String arg) {
        String key;
        switch (which) {
            case "podium" -> {
                int n;
                try {
                    n = arg == null ? 0 : Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    n = 0;
                }
                if (n < 1 || n > 3) {
                    return "&cThe podium has places 1, 2 and 3.";
                }
                key = PODIUM_KEY + n;
                handPodium[n - 1] = null;
            }
            case "board" -> {
                String w = arg == null ? WallBoards.RESULTS : arg.toLowerCase(Locale.ROOT);
                key = WallBoards.RESULTS.equals(w) ? BOARD_KEY : WALL_KEY + w;
                if (WallBoards.RESULTS.equals(w)) {
                    handBoard = null;
                } else {
                    handWalls.remove(w);
                }
            }
            case "trophies" -> {
                key = TROPHIES_KEY;
                handTrophies = null;
            }
            case "jukebox" -> {
                key = JUKEBOX_KEY;
                handJukebox = null;
            }
            case "feast" -> {
                key = FEAST_KEY;
                handFeast = null;
            }
            default -> {
                return "&cThe spots are arrival, podium <n>, board [which], trophies, jukebox, feast, cabinet <game>,"
                        + " trophyroom and door <arcade|trophy>.";
            }
        }
        if (!setMeta(key, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        return "&aThe " + which + (arg == null ? "" : " " + arg) + " spot is the generated room's again.";
    }

    /** Every spot the room uses, for a save's check: where each stands and what it needs of the blocks. */
    List<ClubSave.Spot> spots() {
        List<ClubSave.Spot> out = new ArrayList<>();
        if (site == null) {
            return out;
        }
        int n = 1;
        for (ClubhouseSite.Spot s : mine(handSpawn) ? List.of(handSpawn.spot()) : site.spawns()) {
            out.add(stand("arrival " + n++, s));
        }
        for (int p = 1; p <= 3; p++) {
            ClubhouseSite.Spot s = podium(p);
            if (s != null) {
                out.add(stand("podium " + p, s));
            }
        }
        int[] j = jukeboxBlock();
        if (j != null) {
            out.add(new ClubSave.Spot("the jukebox", j[0], j[1], j[2], ClubSave.Need.JUKEBOX));
        }
        int f = 1;
        List<int[]> frames = trophyFrames();
        for (int[] c : frames == null ? List.<int[]>of() : frames) {
            out.add(new ClubSave.Spot("trophy case frame " + f++, c[0], c[1], c[2], ClubSave.Need.AIR));
        }
        int pl = 1;
        List<int[]> cells = feastCells();
        for (int[] c : cells == null ? List.<int[]>of() : cells) {
            out.add(new ClubSave.Spot("feast plate " + pl++, c[0], c[1], c[2], ClubSave.Need.AIR));
        }
        int c = 1;
        for (Cabinet cab : cabinetPlaces(List.of("a", "b", "c", "d", "e", "f"))) {
            out.add(stand("cabinet " + c++, new ClubhouseSite.Spot(cab.at().x(), cab.at().y(), cab.at().z(), 0f)));
        }
        int t = 1;
        for (ClubhousePlanner.Pedestal p : pedestals()) {
            out.add(new ClubSave.Spot("trophy " + t, (int) Math.floor(p.trophy().x()), (int) Math.floor(p.trophy().y()),
                    (int) Math.floor(p.trophy().z()), ClubSave.Need.AIR));
            out.add(new ClubSave.Spot("trophy " + t++ + "'s head", (int) Math.floor(p.head().x()),
                    (int) Math.floor(p.head().y()), (int) Math.floor(p.head().z()), ClubSave.Need.AIR));
        }
        int seat = 1;
        for (ClubhousePlanner.Seat s : seats()) {
            out.add(new ClubSave.Spot("cushion " + seat++, (int) Math.floor(s.x()), (int) Math.floor(s.y()),
                    (int) Math.floor(s.z()), ClubSave.Need.SEAT));
        }
        return out;
    }

    private static ClubSave.Spot stand(String name, ClubhouseSite.Spot s) {
        return new ClubSave.Spot(name, (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()),
                ClubSave.Need.STAND);
    }

    /** What a wall board is called in the admin's lines. */
    static String wallName(String which) {
        return switch (which) {
            case WallBoards.FAME -> "this week's best";
            case WallBoards.PLAYING -> "now playing";
            case WallBoards.STATS -> "my stats";
            default -> "results";
        };
    }

    private String notOnYet() {
        return hand ? "" : overrides() ? " &7(Your edited room uses it now; &e... clear &7puts the generated spot back.)"
                : " &7(It's used once the owner-built room is on: /hcm games clubhouse here, or once you edit the"
                + " room: /hcm games clubhouse edit.)";
    }

    /** {@code /hcm games clubhouse generated}: back to the generated room (built and checked first). */
    public String generated() {
        return generated(false);
    }

    /**
     * {@code /hcm games clubhouse generated [confirm]} (0.42.0): back to HCM's generated room. With a save in use it
     * asks for {@code confirm} first (it replaces the owner's blocks); the saves are kept, so {@code restore} brings
     * one back.
     */
    public String generated(boolean confirm) {
        if ((savedId != null || editing) && !confirm) {
            return "&eThat puts HCM's own room back over your saved one. &7Your saves are kept (&e/hcm games clubhouse"
                    + " saves&7, &erestore <n>&7). &e/hcm games clubhouse generated confirm &7to go ahead.";
        }
        if (!setMeta(MODE_KEY, null) || !setMeta(OFF_KEY, null) || !setMeta(SAVED_KEY, null)
                || !setMeta(EDIT_KEY, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        hand = false;
        off = false;
        editing = false;
        savedId = null;
        savedSite = null;
        settle();
        return "&aBack to the generated Clubhouse. &7It is built and checked before anyone comes in.";
    }

    private boolean setMeta(String key, String value) {
        try {
            host.meta(key, value);
            return true;
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Clubhouse: could not save " + key, e);
            return false;
        }
    }

    // ---- reading it -----------------------------------------------------------------------------

    /**
     * Whether this room was made for these settings: the same box and Games world. The Clubhouse
     * starts a new room when they change ({@code /hcm reload}).
     */
    public boolean builtWith(ClubhouseSettings s, String worldName) {
        return s != null && box.equals(s.mainBox()) && rooms.equals(s.rooms())
                && world.equals(worldName == null ? "" : worldName.trim());
    }

    /** Whether visitors may come in now. */
    public boolean open() {
        return phase == Phase.READY;
    }

    public Phase phase() {
        return phase;
    }

    /** Whether a build's verify has passed in this run (generated room). */
    public boolean verified() {
        return verified;
    }

    /** Whether the owner-built room is the Clubhouse. */
    public boolean hand() {
        return hand;
    }

    /** Whether an admin switched it off. */
    public boolean off() {
        return off;
    }

    /** Why it is closed, or {@code null}. */
    public String closedWhy() {
        return phase == Phase.CLOSED ? closedWhy : null;
    }

    /** The generated room (the plan and its spots), or {@code null} if it could not be made. */
    public ClubhouseSite site() {
        return site;
    }

    /** The generated room's box as built (0.41.0: with the side rooms' box when they are in), or the main box. */
    public Box box() {
        return site == null ? box : site.box();
    }

    /** The main room's box (the claim's). */
    public Box mainBox() {
        return box;
    }

    /** 0.41.0: whether the side rooms' box is part of the generated room, and why not when it was wanted. */
    public boolean annexIn() {
        return annexIn;
    }

    public String annexOut() {
        return annexOut;
    }

    /** The room's world: the owner-built room's, or the Games world. */
    public String world() {
        return hand && handSpawn != null ? handSpawn.world() : world;
    }

    /** Where arrival {@code n} stands (round and round), or {@code null} while it isn't open. */
    public ClubhouseSite.Spot spawn(int n) {
        if (!open()) {
            return null;
        }
        return mine(handSpawn) ? handSpawn.spot() : site.spawn(n);
    }

    /**
     * 0.42.0: whether a spot recorded with the owner-built commands is used: always in an owner-built room, and in the
     * generated room once the owner edits or saved it (moving a spot their blocks covered, {@code spot}).
     */
    private boolean mine(Place p) {
        return hand || (overrides() && p != null && p.world().equalsIgnoreCase(world) && inBox(p));
    }

    /**
     * Whether a recorded spot is inside the Clubhouse's box (0.45.0): in an edited or saved room only those count. A
     * spot left from an earlier owner-built room somewhere else (its keys are kept when HCM's room comes back) sent
     * visitors, the podium, the boards and the cabinets out to it as soon as edit mode went on.
     */
    private boolean inBox(Place p) {
        Box b = site != null ? site.box() : box;
        return p != null && b != null && b.contains((int) Math.floor(p.x()), (int) Math.floor(p.y()),
                (int) Math.floor(p.z()));
    }

    /** Whether the generated room takes the owner's spots: in edit mode, or with a save in use. */
    boolean overrides() {
        return !hand && (editing || savedSite != null);
    }

    /** Podium place {@code n} (1-3), or {@code null} when there is none. */
    public ClubhouseSite.Spot podium(int n) {
        if (n < 1 || n > 3) {
            return null;
        }
        if (mine(handPodium[n - 1])) {
            Place p = handPodium[n - 1];
            return p == null || !p.world().equalsIgnoreCase(world()) ? null : p.spot();
        }
        return site == null ? null : site.podium().get(n - 1);
    }

    /**
     * Where the results board floats, or {@code null} for none (kept for the tests and the admin's check: the
     * boards are drawn from {@link #wall}).
     */
    public ClubhouseSite.Spot board() {
        if (hand) {
            return handBoard == null || !handBoard.world().equalsIgnoreCase(world()) ? null : handBoard.spot();
        }
        return site == null ? null : site.board();
    }

    /**
     * Where board {@code which} ({@link WallBoards#RESULTS} or one of {@link WallBoards#WALLS}) hangs, locked in
     * place (0.38.3), or {@code null} for none: the generated room's wall, or the owner-built room's spot (only in
     * its world) facing the way the admin faced.
     */
    public WallBoards.Wall wall(String which) {
        if (which == null) {
            return null;
        }
        Place at = WallBoards.RESULTS.equals(which) ? handBoard : handWalls.get(which);
        if (!mine(at)) {
            return site == null ? null : WallBoards.generated(which, box);
        }
        return at == null || !at.world().equalsIgnoreCase(world()) ? null : WallBoards.hand(which, at);
    }

    /**
     * The trophy case's frame positions (0.38.4), {x, y, z} each, or {@code null} for none: the generated room's
     * west wall ({@link ClubhousePlanner#trophyFrames}), or the owner-built room's row from the recorded spot
     * along the admin's right (only in its world). Both {@value ClubhousePlanner#TROPHY_STEP} blocks apart (0.38.7;
     * the owner-built row's frames were side by side, and their labels ran into each other).
     */
    public List<int[]> trophyFrames() {
        if (!mine(handTrophies)) {
            return site == null ? null : ClubhousePlanner.trophyFrames(box);
        }
        Place at = handTrophies;
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return null;
        }
        float yaw = WallBoards.snap(at.yaw());
        // the right hand of someone facing south (yaw 0) points west; north (180), east; west (90), north
        int dx = yaw == 0f ? -1 : yaw == 180f ? 1 : 0;
        int dz = yaw == 90f ? -1 : yaw == -90f ? 1 : 0;
        List<int[]> out = new ArrayList<>();
        for (int n = 0; n < ClubhousePlanner.TROPHY_MAX; n++) {
            int step = n * ClubhousePlanner.TROPHY_STEP;
            out.add(new int[]{(int) Math.floor(at.x()) + step * dx, (int) Math.floor(at.y()),
                    (int) Math.floor(at.z()) + step * dz});
        }
        return out;
    }

    /**
     * The way the trophy case faces (Minecraft yaw: 0 south, 90 west, 180 north, -90 east): west in the generated
     * room (its frames hang on the east wall under the All-time records board, 0.40.0), the way the admin faced in an
     * owner-built one.
     */
    public float trophyYaw() {
        return mine(handTrophies) ? (handTrophies == null ? 0f : WallBoards.snap(handTrophies.yaw())) : 90f;
    }

    /**
     * Where the cushions go (0.40.0, 26.3): the generated room's chairs, stools and armchairs; none in an owner-built
     * room (its chairs are the owner's), or while there is no room.
     */
    public List<ClubhousePlanner.Seat> seats() {
        return hand || site == null ? List.of() : ClubhousePlanner.seats(site.box());
    }

    /**
     * The jukebox block (0.38.4), {x, y, z}, or {@code null} for none: the generated room's corner
     * ({@link ClubhousePlanner#jukebox}), or the block under the owner-built room's recorded spot (only in its
     * world). Whether a jukebox is really there is the corner's to check.
     */
    public int[] jukeboxBlock() {
        if (!mine(handJukebox)) {
            return site == null ? null : ClubhousePlanner.jukebox(box);
        }
        Place at = handJukebox;
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return null;
        }
        return new int[]{(int) Math.floor(at.x()), (int) Math.floor(at.y()) - 1, (int) Math.floor(at.z())};
    }

    /**
     * The feast table's plates (0.38.6), {x, y, z} each (the air a plate's frame hangs in, facing up), or {@code null}
     * for none: the generated room's two tables ({@link ClubhousePlanner#feastCells}), or the owner-built room's two
     * rows of ten from the recorded spot along the admin's right (only in its world).
     */
    public List<int[]> feastCells() {
        if (!mine(handFeast)) {
            return site == null ? null : ClubhousePlanner.feastCells(box);
        }
        Place at = handFeast;
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return null;
        }
        int[] right = right(at.yaw());
        int[] ahead = ahead(at.yaw());
        int half = com.dierks.homecraft.arcade.feast.FeastRules.CELLS / 2;
        List<int[]> out = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            for (int n = 0; n < half; n++) {
                out.add(new int[]{(int) Math.floor(at.x()) + n * right[0] + row * ahead[0], (int) Math.floor(at.y()),
                        (int) Math.floor(at.z()) + n * right[1] + row * ahead[1]});
            }
        }
        return out;
    }

    /**
     * The feast table's line (0.38.6), or {@code null} for none: the generated room's over the aisle facing the
     * arrivals ({@link ClubhousePlanner#feastLine}); the owner-built room's two blocks over the middle of the plates,
     * facing back to where the admin stood.
     */
    public WallBoards.Wall feastLine() {
        if (!mine(handFeast)) {
            return site == null ? null : ClubhousePlanner.feastLine(box);
        }
        Place at = handFeast;
        if (at == null || !at.world().equalsIgnoreCase(world())) {
            return null;
        }
        int[] right = right(at.yaw());
        int[] ahead = ahead(at.yaw());
        double along = (com.dierks.homecraft.arcade.feast.FeastRules.CELLS / 2 - 1) / 2.0;
        double x = Math.floor(at.x()) + 0.5 + along * right[0] + 0.5 * ahead[0];
        double z = Math.floor(at.z()) + 0.5 + along * right[1] + 0.5 * ahead[1];
        return new WallBoards.Wall(x, Math.floor(at.y()) + 2.0, z, WallBoards.turn(WallBoards.snap(at.yaw() + 180f)),
                ClubhousePlanner.FEAST_LINE_SCALE, ClubhousePlanner.FEAST_LINE_WIDTH);
    }

    /** The admin's right for a yaw, {dx, dz}: facing south (0) it is west, north (180) east, west (90) north. */
    static int[] right(float yaw) {
        float y = WallBoards.snap(yaw);
        return new int[]{y == 0f ? -1 : y == 180f ? 1 : 0, y == 90f ? -1 : y == -90f ? 1 : 0};
    }

    /** One block ahead for a yaw, {dx, dz}: south (0) is +z, north (180) -z, west (90) -x, east (-90) +x. */
    static int[] ahead(float yaw) {
        float y = WallBoards.snap(yaw);
        return new int[]{y == 90f ? -1 : y == -90f ? 1 : 0, y == 0f ? 1 : y == 180f ? -1 : 0};
    }

    /** A stored value under {@link #META_PREFIX} (the family week's marker), or {@code null}; a failure throws. */
    String meta(String key) throws Exception {
        return host.meta(key);
    }

    /** Store {@code value} under {@link #META_PREFIX} ({@code null} removes it); a failure throws. */
    void meta(String key, String value) throws Exception {
        host.meta(key, value);
    }

    /**
     * Whether feet at (x, y, z) of {@code worldName} are in the Clubhouse: the generated room's air,
     * or within {@value #HAND_REACH} blocks of an owner-built room's arrival spot.
     */
    public boolean contains(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equalsIgnoreCase(world())) {
            return false;
        }
        if (hand) {
            return handSpawn != null && Math.abs(x - handSpawn.x()) <= HAND_REACH && Math.abs(z - handSpawn.z())
                    <= HAND_REACH && Math.abs(y - handSpawn.y()) <= HAND_REACH
                    || near(handSecretRoom, x, y, z, UP_REACH) || near(handParlorRoom, x, y, z, UP_REACH);
        }
        return site != null && site.in(x, y, z) || (overrides() && (near(handSecretRoom, x, y, z, UP_REACH)
                || near(handParlorRoom, x, y, z, UP_REACH)));
    }

    /** How far from an owner-built room's arrival spot still counts as in it. */
    public static final double HAND_REACH = 24;

    /**
     * Whether the box is guarded now: only the generated room, while it isn't off and (0.42.0) isn't being edited.
     * In edit mode nothing is put back and nothing is refused by the area guard; {@link #refuses} still keeps
     * everyone but an admin from changing a block.
     */
    public boolean guarded() {
        return !hand && !off && !editing;
    }

    /** Whether a player's block edit in the box is refused (0.42.0): always while guarded; in edit mode, a non-admin's. */
    public boolean refuses(boolean admin) {
        return guarded() || (editing && !hand && !off && !admin);
    }

    /** Whether the owner-built room's spots are set: the arrival spot, the podium places and the board. */
    public List<String> handSpots() {
        List<String> out = new ArrayList<>();
        out.add("arrival " + (handSpawn == null ? "not set" : handSpawn.text()));
        for (int n = 0; n < 3; n++) {
            out.add("podium " + (n + 1) + " " + (handPodium[n] == null ? "not set" : handPodium[n].text()));
        }
        out.add("board " + (handBoard == null ? "not set" : handBoard.text()));
        return out;
    }

    /** Whether every owner-built spot is set. */
    public boolean handComplete() {
        return handSpawn != null && handBoard != null && handPodium[0] != null && handPodium[1] != null
                && handPodium[2] != null;
    }

    /** The lines under the Clubhouse in {@code /hcm games clubhouse status}. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add((hand ? "owner-built room in " + world() : "generated room, box " + box.describe() + " in "
                + (world.isEmpty() ? "(no world)" : world)) + (off ? " - switched OFF" : ""));
        out.add(phase.name().toLowerCase(Locale.ROOT) + (verified ? ", built and checked" : "")
                + (waitingFor == null ? "" : ": waiting - " + waitingFor)
                + (phase == Phase.CLOSED && closedWhy != null ? ": " + closedWhy : ""));
        if (job != null) {
            out.add((jobScan ? "claim scan" : "build") + ": pass " + job.pass() + ", " + job.waiting() + " to write"
                    + (budget.paused() ? " (paused: the server is busy)" : ""));
        }
        if (!hand && site != null) {
            out.add("plan " + site.plan().hash() + ", " + site.plan().ops().size() + " blocks; builds " + builds
                    + (builds > 0 ? ", last wrote " + lastWrites : "") + (lastError == null ? "" : "; last failure: "
                    + lastError));
            out.add("side rooms: " + sideRoomsLine()); // 0.41.0
            out.add("room: " + (savedSite != null ? "your save " + savedId : "HCM's generated room")
                    + (editing ? " - EDIT MODE ON (save confirm keeps it, discard puts it back)" : "")
                    + (planChanged() ? " - HCM's plan changed in an update (generated to see it)" : "")
                    + (savedProblem == null ? "" : " - " + savedProblem)); // 0.42.0
        }
        if (hand) {
            out.addAll(handSpots());
            for (String which : WallBoards.WALLS) { // 0.38.3: optional, the check doesn't ask for them
                Place at = handWalls.get(which);
                out.add(which + " board " + (at == null ? "not set (optional)" : at.text()));
            }
            out.add("trophies " + (handTrophies == null ? "not set (optional)" : handTrophies.text())); // 0.38.4
            out.add("jukebox " + (handJukebox == null ? "not set (optional)" : handJukebox.text()));
            out.add("feast " + (handFeast == null ? "not set (optional)" : handFeast.text())); // 0.38.6
            out.add("arcade cabinets " + (handCabinets.isEmpty() ? "none set (optional)" : String.join(", ",
                    handCabinets.keySet()))); // 0.41.0
            out.add("trophy room " + (handTrophyRoom == null ? "not set (optional)" : handTrophyRoom.text()));
            for (String which : DOORS) {
                Place at = handDoors.get(which);
                out.add(which + " door sign " + (at == null ? "not set (optional)" : at.text()));
            }
        }
        return out;
    }

    /** The generated room's side rooms in a line: which are built, or why they are left out (0.41.0). */
    String sideRoomsLine() {
        if (!rooms.any()) {
            return annexIn ? "both switched off (their box is cleared)" : "both switched off";
        }
        if (annexOut != null) {
            return "left out - " + annexOut;
        }
        if (!annexIn) {
            return "not built yet";
        }
        return (rooms.arcade() ? "the Arcade Room" : "") + (rooms.arcade() && rooms.trophy() ? " and " : "")
                + (rooms.trophy() ? "the Trophy Room" : "") + ", " + annex.describe();
    }

    /** The build running now, or {@code null} (for the tests). */
    BuildJob job() {
        return job;
    }
}
