package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.BuildBudget;
import com.dierks.homecraft.games.gen.engine.BuildJob;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *       Clubhouse: its arrival spot is where they stand, the podium and board where they set them.
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
    /** Where the claim is kept in {@code hcm_meta}. */
    public static final String CLAIM_KEY = META_PREFIX + "claim";
    /** "hand" while an owner-built room is the Clubhouse. */
    public static final String MODE_KEY = META_PREFIX + "mode";
    /** "1" while an admin has switched the Clubhouse off. */
    public static final String OFF_KEY = META_PREFIX + "off";
    /** The owner-built room's spots: "world;x;y;z;yaw". */
    public static final String SPAWN_KEY = META_PREFIX + "hand.spawn";
    public static final String BOARD_KEY = META_PREFIX + "hand.board";
    public static final String PODIUM_KEY = META_PREFIX + "hand.podium.";
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
    private final Box box;
    private final String world;
    private final BuildBudget budget;
    private final ClubhouseSite site;

    private Phase phase = Phase.WAITING;
    private String waitingFor;
    private String closedWhy;
    private String planProblem;
    private boolean hand;
    private boolean off;
    private Place handSpawn;
    private Place handBoard;
    private final Place[] handPodium = new Place[3];
    private BuildJob job;
    private boolean jobScan;
    private long jobStarted;
    private long ticks;
    private boolean confirmClaim;
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
        this.box = settings.box();
        String w = host.worldName();
        this.world = w == null ? "" : w.trim();
        this.budget = new BuildBudget(host::nanoTime);
        ClubhouseSite s;
        try {
            s = ClubhousePlanner.plan(box);
        } catch (RuntimeException e) {
            s = null;
            planProblem = e.getMessage();
        }
        this.site = s;
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
        if (site == null) {
            close("its room could not be made: " + planProblem);
            return;
        }
        phase = Phase.WAITING;
        tryBuild();
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
            if (jobScan) {
                scanned(done);
                return;
            }
            builds++;
            lastWrites = done.writes();
            verified = true;
            phase = Phase.READY;
            lastError = null;
            host.logger().info("Clubhouse: built and checked (" + box.describe() + ", " + done.writes()
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
        if (hand || off || site == null) {
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
        startJob(port, !claimed);
    }

    private void startJob(WorldPort port, boolean scan) {
        job = new BuildJob(port, box, scan ? null : site.plan(), scan ? BuildJob.Mode.SCAN : BuildJob.Mode.CONVERGE);
        jobScan = scan;
        jobStarted = ticks;
        phase = scan ? Phase.SCANNING : Phase.BUILDING;
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
        startJob(port, false);
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

    /** Before a build: anyone in the box who isn't a visitor is moved out of its way. */
    private void moveStrays() {
        for (Person p : host.people()) {
            if (p.in(world, box) && !visitor.test(p.id())) {
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

    /** {@code /hcm games clubhouse board}: where the owner-built room's results board floats. */
    public String board(Place at) {
        if (!setMeta(BOARD_KEY, at.text())) {
            return "&cThat can't be saved to the database right now.";
        }
        handBoard = at;
        return "&aThe results board floats where you stand." + (hand ? "" : " &7(It's used once the owner-built"
                + " room is on: /hcm games clubhouse here.)");
    }

    /** {@code /hcm games clubhouse generated}: back to the generated room (built and checked first). */
    public String generated() {
        if (!setMeta(MODE_KEY, null) || !setMeta(OFF_KEY, null)) {
            return "&cThat can't be saved to the database right now.";
        }
        hand = false;
        off = false;
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
        return s != null && box.equals(s.box()) && world.equals(worldName == null ? "" : worldName.trim());
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

    /** The generated room's box. */
    public Box box() {
        return box;
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
        return hand ? handSpawn.spot() : site.spawn(n);
    }

    /** Podium place {@code n} (1-3), or {@code null} when there is none. */
    public ClubhouseSite.Spot podium(int n) {
        if (n < 1 || n > 3) {
            return null;
        }
        if (hand) {
            Place p = handPodium[n - 1];
            return p == null || !p.world().equalsIgnoreCase(world()) ? null : p.spot();
        }
        return site == null ? null : site.podium().get(n - 1);
    }

    /** Where the board floats, or {@code null} for none. */
    public ClubhouseSite.Spot board() {
        if (hand) {
            return handBoard == null || !handBoard.world().equalsIgnoreCase(world()) ? null : handBoard.spot();
        }
        return site == null ? null : site.board();
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
                    <= HAND_REACH && Math.abs(y - handSpawn.y()) <= HAND_REACH;
        }
        return site != null && site.in(x, y, z);
    }

    /** How far from an owner-built room's arrival spot still counts as in it. */
    public static final double HAND_REACH = 24;

    /** Whether the box is guarded now: only the generated room, while it isn't off. */
    public boolean guarded() {
        return !hand && !off;
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
        }
        if (hand) {
            out.addAll(handSpots());
        }
        return out;
    }

    /** The build running now, or {@code null} (for the tests). */
    BuildJob job() {
        return job;
    }
}
