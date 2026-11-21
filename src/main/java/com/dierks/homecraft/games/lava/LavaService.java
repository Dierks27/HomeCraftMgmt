package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.BuildBudget;
import com.dierks.homecraft.games.gen.engine.BuildJob;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaEvent;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;
import com.dierks.homecraft.games.lava.rules.LavaRound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Floor Is Lava's playroom, running: the room's build and its check, the round being played (the pure
 * {@link LavaRound}, driven once a tick), the lava ({@link LavaWriter}), the stars, and what every round event means
 * for the players in the room. Modelled on Falling Floors' {@code ArenaService}.
 *
 * <p><b>The gate.</b> Nobody comes in until the box has been verified equal to the plan in this run: the room's
 * build is Fresh Courses' own converge-and-verify ({@link BuildJob} over the box). The plan has no lava in it, so
 * after a crash or a restart the same check that builds the room is what takes away whatever a round left behind.
 *
 * <p><b>The claim</b> (Fresh Courses' idea, as the arena's): the first time the box is used it must be empty. An
 * unclaimed box is only counted; empty, it is claimed ({@value #CLAIM_KEY}) and built; anything in it closes the
 * game, touching nothing, until an admin's {@code /hcm games lava claim confirm}. Before anything is written the box
 * must also be clear of every Fresh Courses area, the kept courses, the Clubhouse, the arena and hand-built courses
 * ({@link LavaHost#regionProblems}).
 *
 * <p><b>The lava is put away on every path.</b> The round's end, the last player leaving, a new round, the game
 * stopping or closing: each goes through {@link LavaWriter#restore}, which empties every lava layer and puts the
 * floor back whatever state the round was in. When the room empties it is checked against its plan again, in the
 * background.
 *
 * <p><b>One room, one round.</b> A second player choosing the same mode joins the round going (not a Challenge
 * that has started). The Clubhouse's cabinet starts Little; a child tapping it while a Group round is on joins that
 * (nobody can lose there either). Any other mix is told the room is in use.
 *
 * <p>Everything runs on the main thread, inside the game's guard. Pure apart from the {@link LavaHost}, so the
 * tests run it tick by tick on a fake world.
 */
public final class LavaService {

    /** The world session's ref (and the game's id). */
    public static final String REF = "floor_is_lava";
    /** Where the claim is kept in {@code hcm_meta}. */
    public static final String CLAIM_KEY = "gen.lava.claim";
    /** The build's time budget a tick, its blocks, and the snapshots and chunk loads it may use. */
    static final int BUILD_MS = 3;
    static final int BUILD_BLOCKS = 600;
    static final int BUILD_SNAPSHOTS = 3;
    static final int BUILD_LOADS = 4;
    static final int PAUSE_MSPT = 40;
    /** A build that fails is tried this many times before the game closes. */
    static final int BUILD_TRIES = 3;
    /** After a touch a player isn't touched again for this many ticks (one dip is one touch). */
    static final int TOUCH_TICKS = 10;
    /** A star floats this high over the floor, and is collected from this near. */
    static final double STAR_UP = 0.9;
    static final double STAR_REACH = 1.3;
    /** The end: sparks for this long, then the end screen; and everyone still there this long after it goes home. */
    static final int PARTY_TICKS = 60;
    static final int LINGER_TICKS = 90 * LavaRound.TICKS_PER_SECOND;
    /** The warning's rising notes, one every this many ticks. */
    static final int RISE_EVERY = 10;

    private final LavaHost host;
    private final Box box;
    private final String world;
    private final BuildBudget budget;
    private LavaSite site;
    private String planProblem;

    // the room's build
    private BuildJob job;
    private boolean jobScan;
    private boolean pending;
    private String waitingFor;
    private boolean verified;
    private String closed;
    private String closedNote;
    private int tries;
    private int builds;
    private long lastWrites;
    private String lastError;

    // the round
    private LavaRound round;
    private LavaWriter writer;
    private WorldPort writerPort;
    private boolean held;
    private long seed;
    private long ticks;
    private long overAt = -1;
    private int phaseTicks = 1;
    private int spawnNext;
    private int galleryNext;
    private int roundsPlayed;
    private final Map<Integer, LavaSite.Spot> stars = new LinkedHashMap<>();
    private final Map<UUID, Long> dryUntil = new HashMap<>();
    private final Map<UUID, LavaHost.Kit> kits = new HashMap<>();
    private final Set<UUID> barShown = new LinkedHashSet<>();

    /** A room with the settings as they are now: the box is fixed until it stops. */
    public LavaService(LavaHost host) {
        this.host = host;
        this.box = host.settings().box();
        String w = host.worldName();
        this.world = w == null ? "" : w.trim();
        this.budget = new BuildBudget(host::nanoTime);
    }

    // ---- life -----------------------------------------------------------------------------------

    /** Plan the room; the first thing that happens then is its build and check. */
    public void start() {
        try {
            site = LavaPlanner.plan(box);
            planProblem = null;
            pending = true;
        } catch (RuntimeException e) {
            planProblem = "the playroom could not be planned: " + e.getMessage();
            host.logger().log(Level.WARNING, "The Floor Is Lava: " + planProblem, e);
        }
    }

    /** Stop: the lava away, the stars and bars gone, no build left running. Sessions are the framework's to end. */
    public void stop() {
        cancelJob();
        putAway();
        round = null;
        kits.clear();
        dryUntil.clear();
        hold(false);
    }

    /** The lava, the glow, the stars and the bars: all away, now. */
    private void putAway() {
        if (writer != null) {
            try {
                writer.restore();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "The Floor Is Lava: could not put the lava away - the room is"
                        + " checked against its plan before anyone comes in again", e);
                verified = false;
                pending = closed == null;
            }
        }
        host.hideStars();
        stars.clear();
        for (UUID p : new ArrayList<>(barShown)) {
            host.hideBar(p);
        }
        barShown.clear();
    }

    // ---- every tick -----------------------------------------------------------------------------

    /** One tick: the build's share of work, then the round. */
    public void tick() {
        ticks++;
        driveBuild();
        LavaRound r = round;
        if (r == null) {
            return;
        }
        List<UUID> members = r.members();
        if (members.isEmpty()) {
            dispose();
            return;
        }
        for (UUID p : members) {
            host.calm(p);
        }
        if (r.over()) {
            party(r);
            return;
        }
        act(r, r.tick());
        if (round != r || r.over()) {
            return;
        }
        if (writer != null) {
            writer.tick();
        }
        if (r.phase().inPlay()) {
            feet(r);
        }
        if (round == r && !r.over()) {
            tickUi(r);
        }
    }

    /** Once a second: a build waiting for its world, anyone who got out of the room, the bars. */
    public void check() {
        if (site == null) {
            return;
        }
        tryStartBuild();
        LavaRound r = round;
        if (r == null) {
            return;
        }
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f != null && !site.inside().contains(f[0], f[1], f[2])) {
                host.teleport(p, world, entry(r)); // nobody is ever outside the room
            }
        }
        if (!r.over()) {
            bars(r);
        }
    }

    /** Who is wet, then who is at a star. All of one tick's touches go to the rules together. */
    private void feet(LavaRound r) {
        int deep = writer == null ? 0 : writer.level();
        Map<UUID, double[]> at = new LinkedHashMap<>();
        List<UUID> wet = new ArrayList<>();
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f == null) {
                continue;
            }
            at.put(p, f);
            if (deep > 0 && ticks >= dryUntil.getOrDefault(p, 0L)
                    && (site.touches(deep, f[0], f[1], f[2]) || host.inLava(p))) {
                wet.add(p);
                dryUntil.put(p, ticks + TOUCH_TICKS);
            }
        }
        if (!wet.isEmpty()) {
            act(r, r.touched(wet));
        }
        if (round != r || r.over() || stars.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, double[]> e : at.entrySet()) {
            if (wet.contains(e.getKey())) {
                continue;
            }
            double[] f = e.getValue();
            for (Map.Entry<Integer, LavaSite.Spot> s : new ArrayList<>(stars.entrySet())) {
                double dx = s.getValue().x() - f[0];
                double dz = s.getValue().z() - f[2];
                if (dx * dx + dz * dz <= STAR_REACH * STAR_REACH && Math.abs(s.getValue().y() - f[1]) <= 2.0) {
                    act(r, r.collected(e.getKey(), s.getKey()));
                    if (round != r || r.over()) {
                        return;
                    }
                }
            }
        }
    }

    // ---- the events -----------------------------------------------------------------------------

    private void act(LavaRound r, List<LavaEvent> events) {
        for (LavaEvent e : events) {
            if (round != r) {
                return; // the round was put away by an earlier event
            }
            switch (e) {
                case LavaEvent.Started s -> started(r, s);
                case LavaEvent.PhaseChanged p -> phase(r, p);
                case LavaEvent.StarsOut s -> starsOut(s);
                case LavaEvent.StarsIn s -> {
                    host.hideStars();
                    stars.clear();
                }
                case LavaEvent.StarTaken t -> starTaken(r, t);
                case LavaEvent.PutBack b -> putBack(r, b);
                case LavaEvent.Out o -> out(r, o);
                case LavaEvent.Ended en -> ended(r, en.result());
            }
        }
    }

    private void started(LavaRound r, LavaEvent.Started s) {
        roundsPlayed++;
        String intro = switch (r.mode()) {
            case LITTLE -> LavaText.LITTLE_INTRO;
            case GROUP -> LavaText.GROUP_INTRO;
            case CHALLENGE -> LavaText.CHALLENGE_INTRO;
        };
        for (UUID p : s.players()) {
            giveKit(p, LavaHost.Kit.PLAY);
            host.tell(p, intro);
            host.sound(p, LavaHost.Cue.START, 0);
        }
        bars(r);
    }

    private void phase(LavaRound r, LavaEvent.PhaseChanged p) {
        phaseTicks = Math.max(1, p.ticks());
        if (writer == null) {
            return;
        }
        switch (p.phase()) {
            case SAFE -> {
                writer.target(p.level());
                writer.settle(); // the floor is dry before a star is put on it
                writer.glow(0);
                for (UUID m : r.members()) {
                    if (r.lavaCame() > 0 && r.mode() != LavaMode.CHALLENGE) {
                        host.sound(m, LavaHost.Cue.CLEAR, 0);
                    }
                    if (r.mode() == LavaMode.LITTLE) {
                        if (r.lavaCame() == 0) {
                            host.title(m, LavaText.SAFE_BIG, "", 30);
                        }
                    } else {
                        host.title(m, LavaText.roundTitle(p.round(), r.mode() == LavaMode.GROUP ? r.rules().rounds() : 0),
                                "", 30);
                    }
                }
            }
            case WARNING -> {
                writer.glow(1);
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.RUMBLE, 0);
                    host.title(m, LavaText.CLIMB_BIG, LavaText.CLIMB_SMALL, 60);
                }
            }
            case LAVA -> {
                writer.glow(2);
                writer.target(p.level());
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.LAVA, 0);
                }
            }
            case DRAIN -> {
                writer.glow(0);
                writer.target(0);
            }
            default -> {
                // GATHER and OVER have no lava of their own
            }
        }
        bars(r);
    }

    private void starsOut(LavaEvent.StarsOut s) {
        host.hideStars();
        stars.clear();
        List<LavaSite.Spot> spots = site.starSpots(s.ids().size(), seed + 31L * s.set());
        for (int n = 0; n < s.ids().size() && n < spots.size(); n++) {
            LavaSite.Spot at = spots.get(n);
            LavaSite.Spot star = new LavaSite.Spot(at.x(), at.y() + STAR_UP, at.z(), 0f);
            stars.put(s.ids().get(n), star);
            host.showStar(world, s.ids().get(n), star.x(), star.y(), star.z());
        }
    }

    private void starTaken(LavaRound r, LavaEvent.StarTaken t) {
        LavaSite.Spot at = stars.remove(t.id());
        host.hideStar(t.id());
        if (at != null) {
            host.fx(world, LavaHost.Fx.SPARKLE, at.x(), at.y(), at.z());
        }
        for (UUID m : r.members()) {
            host.sound(m, LavaHost.Cue.STAR, t.have());
        }
        bars(r);
    }

    /** A touch in Little or Group (or a Challenge watcher who got out of the gallery): a sizzle, a puff, and back. */
    private void putBack(LavaRound r, LavaEvent.PutBack b) {
        double[] f = host.feet(b.player());
        if (f != null) {
            host.fx(world, LavaHost.Fx.SMOKE, f[0], f[1] + 0.5, f[2]);
        }
        LavaSite.Spot to = null;
        if (r.mode() == LavaMode.CHALLENGE) {
            to = site.gallery(galleryNext++);
        } else if (f != null) {
            int deep = Math.max(writer == null ? 0 : Math.max(writer.level(), writer.target()), r.level());
            to = site.nearestSafe(deep, f[0], f[1], f[2]);
        }
        host.teleport(b.player(), world, to == null ? entry(r) : to);
        host.sound(b.player(), LavaHost.Cue.SIZZLE, 0);
        if (b.costStar()) {
            host.actionBar(b.player(), LavaText.LOST_STAR);
            bars(r);
        }
    }

    private void out(LavaRound r, LavaEvent.Out o) {
        if (o.left()) {
            return; // they left: the session has taken them away
        }
        double[] f = host.feet(o.player());
        if (f != null) {
            host.fx(world, LavaHost.Fx.SMOKE, f[0], f[1] + 0.5, f[2]);
        }
        host.teleport(o.player(), world, site.gallery(galleryNext++));
        host.sound(o.player(), LavaHost.Cue.OUT, 0);
        giveKit(o.player(), LavaHost.Kit.WATCH);
        host.tell(o.player(), LavaText.out(o.place(), o.survived()));
        host.tell(o.player(), LavaText.WATCHING);
        bars(r);
    }

    /** The round is over: the lava away at once, the bests recorded, then the party. */
    private void ended(LavaRound r, LavaResult result) {
        putAway();
        if (result.calledOff() || r.members().isEmpty()) {
            dispose();
            return;
        }
        overAt = ticks;
        Map<UUID, Long> bests = new HashMap<>();
        for (UUID p : r.members()) {
            bests.put(p, host.best(p, r.mode()));
        }
        host.scored(result);
        int n = 0;
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f != null && site.inGallery(f[0], f[1], f[2])) {
                host.teleport(p, world, site.spawn(spawnNext + n++)); // down from the gallery for the party
            }
            giveKit(p, LavaHost.Kit.OVER);
            host.title(p, LavaText.WIN_BIG, LavaText.endSmall(result, p), 60);
            host.tell(p, LavaText.result(result, p, bests.get(p), host::name));
            host.sound(p, LavaHost.Cue.WIN, 0);
        }
    }

    /** After the end: sparks over the room, then each player's end screen; whoever is still there much later goes home. */
    private void party(LavaRound r) {
        long t = ticks - overAt;
        if (t <= PARTY_TICKS && t % 10 == 5) {
            Box in = site.inside();
            int n = (int) (t / 10);
            double x = in.minX() + 6 + (n * 7) % (in.sizeX() - 12);
            double z = in.minZ() + 6 + (n * 11) % (in.sizeZ() - 12);
            host.fx(world, LavaHost.Fx.FIREWORKS, x + 0.5, in.minY() + 7, z + 0.5);
        }
        if (t == PARTY_TICKS) {
            for (UUID p : r.members()) {
                host.endScreen(p, r.result());
            }
        }
        if (t >= LINGER_TICKS) {
            for (UUID p : r.members()) {
                host.endSession(p);
            }
            if (round == r) {
                dispose();
            }
        }
    }

    /** The round is let go: everything away, and the empty room checked against its plan again. */
    private void dispose() {
        round = null;
        putAway();
        kits.clear();
        dryUntil.clear();
        overAt = -1;
        hold(false);
        if (closed == null && site != null) {
            pending = true;
        }
    }

    // ---- players --------------------------------------------------------------------------------

    /** The mode a player asking for {@code asked} would play now, or {@code null} when the room is in use for another. */
    LavaMode modeFor(LavaMode asked, boolean kid) {
        LavaRound r = round;
        if (r == null || r.over() || r.mode() == asked) {
            return asked;
        }
        return kid && r.mode() == LavaMode.GROUP ? LavaMode.GROUP : null;
    }

    /**
     * Why {@code player} can't come in now ({@code &}-coded, for chat), or {@code null}: the room isn't built and
     * checked yet, it is closed, in use for another mode or full, a Challenge has started, or they are in already.
     *
     * @param kid the Clubhouse's cabinet was tapped (Little, or the Group round going)
     */
    public String joinRefusal(UUID player, LavaMode asked, boolean kid) {
        if (closed != null) {
            return LavaText.CLOSED;
        }
        if (site == null || !verified || asked == null) {
            return LavaText.FIXING;
        }
        LavaRound r = round;
        if (r == null || r.over()) {
            return null;
        }
        if (r.isMember(player)) {
            return LavaText.ALREADY_IN;
        }
        LavaMode mode = modeFor(asked, kid);
        if (mode == null) {
            return LavaText.busy(r.mode());
        }
        if (r.members().size() >= r.rules().maxPlayers()) {
            return LavaText.full(r.rules().maxPlayers());
        }
        if (mode == LavaMode.CHALLENGE && r.phase() != LavaRound.Phase.GATHER) {
            return LavaText.CHALLENGE_GOING;
        }
        return null;
    }

    /** Where someone coming in is put: an arrival spot on the sofa, or dry ground when the lava is over it. */
    public LavaSite.Spot entrySpot() {
        return site == null ? null : entry(round);
    }

    private LavaSite.Spot entry(LavaRound r) {
        int deep = writer == null ? 0 : Math.max(writer.level(), writer.target());
        if (r != null && !r.over()) {
            deep = Math.max(deep, r.level());
            if (r.phase() == LavaRound.Phase.WARNING) {
                deep = Math.max(deep, r.nextLevel());
            }
        }
        return site.entry(deep, spawnNext++);
    }

    /**
     * A player's session is ready and they are in the room. They join the round going, or a new one starts for
     * them; {@code null} when they are in, or why not (their session should then end).
     */
    public String joined(UUID player, LavaMode asked, boolean kid) {
        String why = joinRefusal(player, asked, kid);
        if (why != null) {
            return why;
        }
        cancelJob();
        pending = false;
        LavaMode mode = modeFor(asked, kid);
        LavaRound old = round;
        List<UUID> carry = List.of();
        if (old != null && old.over()) {
            // a new round: whoever is still here from the last one plays on if it is the same mode, else goes home
            carry = old.members();
            round = null;
            putAway();
            if (old.mode() != mode) {
                for (UUID p : carry) {
                    host.endSession(p);
                }
                carry = List.of();
            }
        }
        if (round == null) {
            newRound(mode);
            for (UUID p : carry) {
                round.join(p);
            }
        }
        LavaRound r = round;
        LavaRound.Join j = r.join(player);
        if (j != LavaRound.Join.JOINED && j != LavaRound.Join.ALREADY_IN) {
            return j == LavaRound.Join.FULL ? LavaText.full(r.rules().maxPlayers()) : LavaText.CHALLENGE_GOING;
        }
        kits.remove(player);
        dryUntil.remove(player);
        if (r.phase() == LavaRound.Phase.GATHER && r.rules().gatherTicks() > 0) {
            giveKit(player, LavaHost.Kit.GATHER);
            host.tell(player, LavaText.gathering(r.mode()));
        } else if (r.phase().inPlay()) {
            giveKit(player, LavaHost.Kit.PLAY);
            host.tell(player, r.mode() == LavaMode.LITTLE ? LavaText.LITTLE_INTRO : LavaText.GROUP_INTRO);
        }
        bars(r);
        return null;
    }

    private void newRound(LavaMode mode) {
        WorldPort port = host.world(world);
        if (port != null && (writer == null || writerPort != port)) {
            writerPort = port;
            writer = new LavaWriter(port, site);
        }
        hold(true);
        round = new LavaRound(mode, host.settings().rules(mode));
        seed = host.now();
        overAt = -1;
        phaseTicks = Math.max(1, round.rules().gatherTicks());
        stars.clear();
        dryUntil.clear();
    }

    /** A player left the room (Leave game, {@code /hcm leave}, a quit, their session ending any way). */
    public void left(UUID player) {
        kits.remove(player);
        dryUntil.remove(player);
        if (barShown.remove(player)) {
            host.hideBar(player);
        }
        LavaRound r = round;
        if (r == null || !r.isMember(player)) {
            return;
        }
        act(r, r.leave(player));
        if (round == r && r.members().isEmpty()) {
            dispose();
        }
    }

    /** The kit's Start now: the gathering is over. */
    public void startNow(UUID player) {
        LavaRound r = round;
        if (r != null && r.isMember(player) && r.phase() == LavaRound.Phase.GATHER) {
            act(r, r.startNow());
        }
    }

    /**
     * Play again, from the end screen or the kit: a new round of the same mode, at once, for everyone still in the
     * room, each back on the sofa.
     *
     * @return whether a new round started
     */
    public boolean again(UUID player) {
        LavaRound old = round;
        if (old == null || !old.over() || !old.isMember(player) || closed != null || !verified) {
            return false;
        }
        if (overAt >= 0 && ticks - overAt < PARTY_TICKS) {
            return false; // the party first: a stray tap as the round ends never starts the next one
        }
        List<UUID> carry = old.members();
        round = null;
        putAway();
        newRound(old.mode());
        LavaRound r = round;
        for (UUID p : carry) {
            r.join(p);
            kits.remove(p);
            host.teleport(p, world, site.spawn(spawnNext++));
        }
        act(r, r.startNow());
        return true;
    }

    /** The void took a player (it can't: the room is shut; all the same): back onto the sofa. */
    public void voided(UUID player) {
        if (site != null) {
            host.teleport(player, world, entry(round));
        }
    }

    private void giveKit(UUID p, LavaHost.Kit k) {
        if (k != kits.get(p)) {
            kits.put(p, k);
            host.kit(p, k);
        }
    }

    // ---- the bits on screen ---------------------------------------------------------------------

    private void tickUi(LavaRound r) {
        LavaRound.Phase ph = r.phase();
        if (ph == LavaRound.Phase.WARNING) {
            int gone = phaseTicks - r.left();
            if (gone % RISE_EVERY == 0) {
                int step = gone / RISE_EVERY;
                for (UUID p : r.members()) {
                    host.sound(p, LavaHost.Cue.RISE, step);
                }
                Box in = site.inside();
                for (int n = 0; n < 6; n++) { // embers, here and there over the floor
                    double x = in.minX() + 2 + ((step * 13 + n * 17) % (in.sizeX() - 4));
                    double z = in.minZ() + 2 + ((step * 7 + n * 23) % (in.sizeZ() - 4));
                    host.fx(world, LavaHost.Fx.EMBERS, x + 0.5, in.minY() + r.level() + 0.2, z + 0.5);
                }
            }
            if (writer != null && r.left() == phaseTicks / 2) {
                writer.glow(2);
            }
        } else if (ph == LavaRound.Phase.GATHER) {
            int left = r.left();
            if (left > 0 && left <= 3 * LavaRound.TICKS_PER_SECOND && left % LavaRound.TICKS_PER_SECOND == 0) {
                for (UUID p : r.members()) {
                    host.sound(p, LavaHost.Cue.COUNT, 0);
                }
            }
        }
    }

    /** Everyone's bar for where the round is: pictures first (a row of stars, dots for rounds). */
    private void bars(LavaRound r) {
        if (r.over()) {
            return;
        }
        String title;
        float progress;
        boolean red = false;
        if (r.phase() == LavaRound.Phase.GATHER) {
            title = LavaText.gatherBar(r.mode(), (r.left() + LavaRound.TICKS_PER_SECOND - 1) / LavaRound.TICKS_PER_SECOND,
                    r.members().size());
            progress = r.left() / (float) Math.max(1, r.rules().gatherTicks());
        } else {
            switch (r.mode()) {
                case LITTLE -> {
                    title = LavaText.starRow(r.stars(), r.goal());
                    progress = r.stars() / (float) Math.max(1, r.goal());
                }
                case GROUP -> {
                    title = LavaText.groupBar(r.stars(), r.round(), r.rules().rounds());
                    progress = r.stars() / (float) Math.max(1, r.rules().rounds() * r.rules().stars());
                }
                default -> {
                    title = LavaText.challengeBar(r.round(), r.alive().size());
                    progress = r.phase() == LavaRound.Phase.LAVA ? 1f : 1f - r.left() / (float) phaseTicks;
                    red = true;
                }
            }
        }
        for (UUID p : r.members()) {
            host.bar(p, title, Math.max(0f, Math.min(1f, progress)), red);
            barShown.add(p);
        }
    }

    // ---- the build ------------------------------------------------------------------------------

    /** Start the build that is waiting, once the world is there, the box may be used and nobody is playing. */
    private void tryStartBuild() {
        if (!pending || job != null || closed != null || round != null) {
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
            close("its box can't be used: " + problems.get(0) + " - move it with games.floor_is_lava.origin");
            return;
        }
        String claim;
        try {
            claim = host.claim();
        } catch (Exception e) {
            waitingFor = "its claim can't be read from the database";
            return;
        }
        waitingFor = null;
        pending = false;
        boolean claimed = claimText().equals(claim);
        if (!claimed && claim != null) {
            host.logger().info("The Floor Is Lava: the box moved from " + claim + " - the old one's blocks are left"
                    + " where they are");
        }
        if (!verified) {
            for (Person p : host.people()) { // a first build: nobody stands where the room goes
                if (p.in(world, box)) {
                    host.toSafety(p.id(), world);
                }
            }
        }
        startJob(port, !claimed);
    }

    private void startJob(WorldPort port, boolean scan) {
        job = new BuildJob(port, box, scan ? null : site.plan(), scan ? BuildJob.Mode.SCAN : BuildJob.Mode.CONVERGE);
        jobScan = scan;
    }

    private void driveBuild() {
        if (job == null) {
            return;
        }
        if (host.world(world) == null) {
            host.logger().warning("The Floor Is Lava: the world " + world + " went away during its build");
            cancelJob();
            verified = false;
            pending = true;
            return;
        }
        budget.begin(new DailySettings.Budget(BUILD_BLOCKS, BUILD_BLOCKS, BUILD_MS, BUILD_SNAPSHOTS, BUILD_LOADS,
                PAUSE_MSPT), host.anyoneOnline(), host.mspt());
        try {
            job.tick(budget, BUILD_LOADS, peopleHere(), host.now());
        } finally {
            budget.end();
        }
        for (UUID stuck : job.stuckPeople()) {
            host.toSafety(stuck, world);
            host.logger().info("The Floor Is Lava: moved someone who was in the build's way for 30 s");
        }
        if (job.done()) {
            BuildJob done = job;
            job = null;
            done.release();
            if (jobScan) {
                claimScanned(done);
                return;
            }
            builds++;
            lastWrites = done.writes();
            tries = 0;
            closedNote = null;
            if (!verified) {
                host.logger().info("The Floor Is Lava: the playroom is built and checked (" + box.describe()
                        + ", plan " + site.plan().hash() + ")");
            }
            verified = true;
        } else if (job.failed()) {
            BuildJob failed = job;
            job = null;
            failed.release();
            tries++;
            verified = false; // nobody new comes in until a check passes again
            lastError = failed.error();
            closedNote = failed.firstFound().isEmpty() ? null : "first at " + String.join(" ", failed.firstFound());
            host.logger().warning("The Floor Is Lava: its build failed (try " + tries + " of " + BUILD_TRIES + "): "
                    + lastError);
            if (tries >= BUILD_TRIES) {
                close("its room couldn't be built: " + lastError);
            } else {
                pending = true;
            }
        }
    }

    /** The claim's count is in: an empty box is claimed and built; anything in it closes the game. */
    private void claimScanned(BuildJob scan) {
        if (scan.found() > 0) {
            closedNote = scan.firstFound().isEmpty() ? null : "first at " + String.join(" ", scan.firstFound());
            close("its box has " + scan.found() + " block" + (scan.found() == 1 ? "" : "s") + " that aren't the"
                    + " playroom's - /hcm games lava claim confirm clears them, or move it with"
                    + " games.floor_is_lava.origin");
            return;
        }
        try {
            host.claim(claimText());
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "The Floor Is Lava: could not record its claim", e);
            pending = true;
            waitingFor = "its claim can't be written to the database";
            return;
        }
        host.logger().info("The Floor Is Lava: claimed its empty box (" + box.describe() + ")");
        WorldPort port = host.world(world);
        if (port == null) {
            pending = true;
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

    /** Keep the room's chunks loaded while a round is on (the lava is written and taken away in loaded chunks). */
    private void hold(boolean on) {
        if (on == held) {
            return;
        }
        WorldPort port = writerPort != null ? writerPort : host.world(world);
        if (port == null) {
            held = false;
            return;
        }
        held = on;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (on) {
                    port.load(cx, cz, ok -> {
                    });
                } else {
                    port.release(cx, cz);
                }
            }
        }
    }

    private void close(String reason) {
        closed = reason;
        cancelJob();
        pending = false;
        verified = false;
        host.logger().warning("The Floor Is Lava closed: " + reason + (closedNote == null ? "" : " (" + closedNote + ")"));
        LavaRound r = round;
        round = null;
        putAway();
        hold(false);
        if (r != null) {
            for (UUID p : r.members()) {
                host.tell(p, LavaText.CLOSED);
                host.endSession(p);
            }
        }
    }

    /** Everyone in the room's world (the build waits for a write next to one of them). */
    private List<Person> peopleHere() {
        List<Person> out = new ArrayList<>();
        for (Person p : host.people()) {
            if (p.world() != null && p.world().equalsIgnoreCase(world)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The claim's text: the world, the box's corner and its size. */
    String claimText() {
        return claimText(world, box);
    }

    /** What {@value #CLAIM_KEY} holds for a box in a world. */
    public static String claimText(String world, Box box) {
        return (world == null ? "" : world.trim().toLowerCase(Locale.ROOT)) + "," + box.minX() + "," + box.minY() + ","
                + box.minZ() + "," + box.sizeX() + "," + box.sizeY() + "," + box.sizeZ();
    }

    // ---- admin ----------------------------------------------------------------------------------

    /**
     * {@code /hcm games lava rebuild}: check the room against its plan now and put back anything that differs (once
     * no round is going). A closed room is opened again, starting with a build like a boot's.
     */
    public String requestRebuild() {
        if (site == null) {
            return "&cThe playroom isn't planned: " + (planProblem == null ? "starting" : planProblem);
        }
        if (closed != null) {
            closed = null;
            closedNote = null;
            tries = 0;
            verified = false;
            pending = true;
            return "&aThe Floor Is Lava opens again: the playroom is being built and checked.";
        }
        if (round != null) {
            return "&eA round is going: the playroom is checked when it is empty.";
        }
        pending = true;
        return "&aThe playroom is being checked against its plan.";
    }

    /**
     * {@code /hcm games lava claim [confirm]}: say whether the box is claimed; with {@code confirm}, claim it (the
     * next build empties everything else in it) and open again.
     */
    public List<String> claim(boolean confirm) {
        List<String> out = new ArrayList<>();
        String claim;
        try {
            claim = host.claim();
        } catch (Exception e) {
            out.add("&cThe claim can't be read from the database right now.");
            return out;
        }
        boolean claimed = claimText().equals(claim);
        if (!confirm) {
            out.add(claimed ? "&aThe playroom's box is claimed (" + box.describe() + ")."
                    : "&eThe playroom's box isn't claimed yet (" + box.describe() + "): it is claimed at its first"
                    + " build when it is empty.");
            if (!claimed) {
                out.add("&7/hcm games lava claim confirm claims it now: the next build clears anything in it.");
            }
            return out;
        }
        List<String> problems = world.isEmpty() ? List.of("there is no Games world") : host.regionProblems(box, world);
        if (problems != null && !problems.isEmpty()) {
            out.add("&cThe box can't be claimed: " + problems.get(0));
            return out;
        }
        if (!claimed) {
            try {
                host.claim(claimText());
            } catch (Exception e) {
                out.add("&cThe claim can't be written to the database right now.");
                return out;
            }
            host.logger().warning("The Floor Is Lava: an admin claimed its box (" + box.describe() + "); the next"
                    + " build clears anything in it");
        }
        out.add("&aClaimed.");
        out.add(requestRebuild());
        return out;
    }

    /** The lines under the game in {@code /hcm games status} (and {@code lava status}). */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("box " + box.describe() + " in " + (world.isEmpty() ? "(no world)" : world));
        if (site == null) {
            out.add("not started: " + (planProblem == null ? "planning the playroom" : planProblem));
            return out;
        }
        out.add("plan " + site.plan().hash() + ": " + site.plan().ops().size() + " blocks, " + site.reach().size()
                + " places to stand, lava up to " + site.flood() + " deep, " + site.starStands().size()
                + " places for stars");
        LavaRound r = round;
        if (r == null) {
            out.add("empty" + (verified ? "" : ", not checked yet"));
        } else {
            out.add(r.mode().label() + ", " + r.phase().name().toLowerCase(Locale.ROOT) + ": " + r.members().size()
                    + "/" + r.rules().maxPlayers() + " here, round " + r.round() + ", lava " + (writer == null ? 0
                    : writer.level()) + " deep, " + r.stars() + " stars, " + stars.size() + " out");
        }
        if (job != null) {
            out.add((jobScan ? "claim scan" : "build") + ": pass " + job.pass() + ", " + job.waiting() + " to write"
                    + (budget.paused() ? " (paused: the server is busy)" : ""));
        } else if (waitingFor != null) {
            out.add("build waiting: " + waitingFor);
        }
        out.add("builds: " + builds + " done; last " + lastWrites + " blocks; " + roundsPlayed + " rounds played; "
                + (writer == null ? 0 : writer.written()) + " lava and floor writes"
                + (lastError == null ? "" : "; last failure: " + lastError));
        if (closed != null) {
            out.add("closed: " + closedWhy() + " - /hcm games lava rebuild opens it again");
        }
        return out;
    }

    // ---- reading it -----------------------------------------------------------------------------

    /** The round going (maybe over, with players still in the room), or {@code null}. */
    public LavaRound round() {
        return round;
    }

    /** The room, or {@code null} when it couldn't be planned. */
    public LavaSite site() {
        return site;
    }

    public Box box() {
        return box;
    }

    /** The room's world ({@code ""} for none). */
    public String world() {
        return world;
    }

    /** Whether a check has passed in this run. */
    public boolean verified() {
        return verified;
    }

    /** Whether players may come in now: the room is built and checked, and not closed. */
    public boolean open() {
        return verified && closed == null && site != null;
    }

    /** Why the room closed itself (with where a failed build found its first differences), or {@code null}. */
    public String closedWhy() {
        return closed == null ? null : closed + (closedNote == null ? "" : " (" + closedNote + ")");
    }

    /** How many are in the room. */
    public int playing() {
        LavaRound r = round;
        return r == null ? 0 : r.members().size();
    }

    /** The mode being played, or {@code null} when the room is free (empty, or its round over). */
    public LavaMode busyWith() {
        LavaRound r = round;
        return r == null || r.over() ? null : r.mode();
    }

    /** Whether this room was made for these settings: the same box and world. The round knobs are read per round. */
    public boolean builtWith(LavaSettings s, String worldName) {
        return s != null && box.equals(s.box()) && world.equals(worldName == null ? "" : worldName.trim());
    }

    /** The lava writer, once a round has been played. */
    LavaWriter writer() {
        return writer;
    }

    /** The build running now, or {@code null}. */
    BuildJob job() {
        return job;
    }

    /** The stars out now, by id. */
    Map<Integer, LavaSite.Spot> stars() {
        return Map.copyOf(stars);
    }
}
