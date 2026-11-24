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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * The Floor Is Lava's playroom, running: the room's build and its check, the round being played (the pure
 * {@link LavaRound}, driven once a tick), the lava and the furniture ({@link LavaWriter}), the stars, and what every
 * round event means for the players in the room. Modelled on Falling Floors' {@code ArenaService}.
 *
 * <p><b>The gate.</b> Nobody comes in until the box has been verified equal to the plan in this run: the room's
 * build is Fresh Courses' own converge-and-verify ({@link BuildJob} over the box). The plan has no lava in it and the
 * furniture where it was built, so after a crash or a restart the same check that builds the room is what takes away
 * whatever a round left behind.
 *
 * <p><b>The claim</b> (Fresh Courses' idea, as the arena's): the first time the box is used it must be empty. An
 * unclaimed box is only counted; empty, it is claimed ({@value #CLAIM_KEY}) and built; anything in it closes the
 * game, touching nothing, until an admin's {@code /hcm games lava claim confirm}. Before anything is written the box
 * must also be clear of every Fresh Courses area, the kept courses, the Clubhouse, the arena and hand-built courses
 * ({@link LavaHost#regionProblems}). 0.57.0: the room grew from 32 to 48 across from the same corner; a server whose
 * claim is the old room's counts only the new ground (the chunks the old room never had) and, empty, claims the
 * bigger box and builds the new room over the old ({@link #grownFrom}).
 *
 * <p><b>0.57.0: a room that changes.</b> Every game starts with the furniture somewhere new ({@link LavaPlanner#site},
 * worked out off the main thread before it is needed), and from the second round of Group and Challenge it moves
 * again while the floor is safe: the pieces sparkle and hum for a few seconds ({@link LavaEvent.ShiftComing}), then
 * move ({@link LavaWriter#moveTo}); whoever stands on a piece rides along with it and whoever stands where a piece
 * lands is lifted onto it ({@link LavaShift}). Stars come out during the lava too, on furniture a hop or two away
 * from where everyone stands. A surprise most rounds ({@link LavaRound.Surprise}), each with its own warning: a piece
 * that wobbles and crumbs (then sinks), the floor glowing on one side (where the lava comes first), a quick rumble
 * (and the lava all at once), a sparkling shower (twice the stars), a cushion bubbling green (slime: boing).
 *
 * <p><b>The lava and the furniture are put away on every path.</b> The round's end, the last player leaving, a new
 * round, the game stopping or closing: each goes through {@link LavaWriter#restore}, which empties every lava layer,
 * puts the furniture back where it was built and the floor back, whatever state the round was in. When the room
 * empties it is checked against its plan again, in the background.
 *
 * <p><b>One room, one round.</b> A second player choosing the same mode joins the round going (not a Challenge
 * that has started). The Clubhouse's cabinet offers every mode (0.57.0); a child choosing Little while a Group round
 * is on joins that (nobody can lose there either). Any other mix is told the room is in use.
 *
 * <p>Everything runs on the main thread, inside the game's guard, but the next layout's planning
 * ({@link LavaHost#offThread}). Pure apart from the {@link LavaHost}, so the tests run it tick by tick on a fake world.
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
    /** The warning's rising notes, one every this many ticks (twice as often before a quick rise). */
    static final int RISE_EVERY = 10;
    /** 0.57.0: how long the lava stays on its first side before the rest of the room follows (a surprise). */
    static final int PATCH_TICKS = 3 * LavaRound.TICKS_PER_SECOND;
    /** 0.57.0: the lava's stars keep at least this far (blocks) from where the players stand, while there is room. */
    static final int STAR_AWAY = 3;
    /** 0.57.0: Little's stars on the furniture are no higher than this (feet over the floor block). */
    static final double LITTLE_LOW = 3.5;
    /** 0.57.0: a third of the floor's stars go up on the furniture. */
    static final int UP_EVERY = 3;

    private final LavaHost host;
    private final Box box;
    private final String world;
    private final BuildBudget budget;
    /** The room as built (the build's plan), and the room of the moment (its furniture where the round has it). */
    private LavaSite site;
    private LavaSite room;
    /** 0.57.0: this game's (or this round's) layout, before a surprise changed a piece. */
    private LavaSite base;
    private String planProblem;

    // the room's build
    private BuildJob job;
    private boolean jobScan;
    private boolean jobGrow;
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

    // 0.57.0: the moving room and the surprises
    private long gamesMade;
    private CompletableFuture<LavaSite> nextGame;
    private CompletableFuture<LavaSite> nextShift;
    private long nextShiftSeed;
    private int shifts;
    private LavaRound.Surprise surprise;
    private int surprisePick;
    private int sinkSlot = -1;
    private CompletableFuture<LavaSite> sinkNext;
    private int bouncySlot = -1;
    private LavaWriter.Side patchSide;
    private long patchUntil = -1;
    private int surprisesSeen;

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
            room = site;
            planProblem = null;
            pending = true;
            prepareGame();
        } catch (RuntimeException e) {
            planProblem = "the playroom could not be planned: " + e.getMessage();
            host.logger().log(Level.WARNING, "The Floor Is Lava: " + planProblem, e);
        }
    }

    /** Stop: the lava away, the furniture back, the stars and bars gone, no build left running. */
    public void stop() {
        cancelJob();
        putAway();
        round = null;
        kits.clear();
        dryUntil.clear();
        hold(false);
    }

    /** The lava, the glow, the furniture, the stars and the bars: all away, now. */
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
        room = site;
        base = null;
        clearSurprise();
        nextShift = null;
        host.hideStars();
        stars.clear();
        for (UUID p : new ArrayList<>(barShown)) {
            host.hideBar(p);
        }
        barShown.clear();
    }

    private void clearSurprise() {
        surprise = null;
        sinkSlot = -1;
        sinkNext = null;
        bouncySlot = -1;
        patchSide = null;
        patchUntil = -1;
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
            if (patchUntil >= 0 && ticks >= patchUntil) {
                patchUntil = -1; // the rest of the room's lava follows its first side
                writer.glowOnly(null);
                writer.glow(2);
                writer.release();
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.RUMBLE, 0);
                }
            }
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
        boolean up = writer != null && writer.level() > 0;
        Map<UUID, double[]> at = new LinkedHashMap<>();
        List<UUID> wet = new ArrayList<>();
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f == null) {
                continue;
            }
            at.put(p, f);
            if (up && ticks >= dryUntil.getOrDefault(p, 0L)
                    && (room.touches(f[0], f[1], f[2], writer::lavaAt) || host.inLava(p))) {
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
                case LavaEvent.ShiftComing s -> shiftComing(r, s);
                case LavaEvent.Shift s -> shift(r, s);
                case LavaEvent.SurpriseComing s -> surpriseComing(r, s);
                case LavaEvent.StarsOut s -> starsOut(r, s);
                case LavaEvent.StarsIn s -> {
                    host.hideStars();
                    stars.clear();
                }
                case LavaEvent.StarTaken t -> starTaken(r, t);
                case LavaEvent.PutBack b -> putBack(r, b);
                case LavaEvent.Out o -> out(r, o);
                case LavaEvent.Goal g -> goal(r, g);
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
                if (base != null && room != base) {
                    move(r, base, true); // last round's sunk piece and bouncy cushion are back as they were
                }
                clearSurprise();
                writer.glowOnly(null);
                writer.target(p.level());
                writer.settle(); // the floor is dry (or the lava where it stays) before a star is put out
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
                String small = LavaText.CLIMB_SMALL;
                if (surprise == LavaRound.Surprise.PATCHES) {
                    patchSide = LavaWriter.Side.values()[Math.floorMod(surprisePick, 4)];
                    writer.glowOnly(patchSide); // the side where it comes first glows
                    small = LavaText.PATCH_SMALL;
                } else if (surprise == LavaRound.Surprise.SINK) {
                    sinkSlot = pickSink(r);
                    if (sinkSlot >= 0) {
                        List<LavaLayout.Placed> without = LavaLayout.without(room.layout(), sinkSlot);
                        sinkNext = host.offThread(() -> LavaPlanner.plan(box, without));
                        small = LavaText.SINK_SMALL;
                    }
                } else if (surprise == LavaRound.Surprise.FAST_RISE) {
                    small = LavaText.FAST_SMALL;
                }
                writer.glow(1);
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.RUMBLE, 0);
                    host.title(m, LavaText.CLIMB_BIG, small, 60);
                }
            }
            case LAVA -> {
                writer.glow(2);
                if (patchSide != null) {
                    writer.targetSide(p.level(), patchSide);
                    patchUntil = ticks + Math.min(PATCH_TICKS, Math.max(LavaRound.TICKS_PER_SECOND, p.ticks() / 3));
                } else {
                    writer.target(p.level());
                }
                if (surprise == LavaRound.Surprise.FAST_RISE) {
                    writer.settle(); // all at once
                }
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.LAVA, 0);
                }
                if (sinkSlot >= 0) {
                    sink(r);
                }
                if (surprise == LavaRound.Surprise.STAR_SHOWER) {
                    Box in = site.inside();
                    for (int n = 0; n < 8; n++) {
                        host.fx(world, LavaHost.Fx.SHOWER, in.minX() + 4 + (n * 13) % (in.sizeX() - 8) + 0.5,
                                in.minY() + 10, in.minZ() + 4 + (n * 19) % (in.sizeZ() - 8) + 0.5);
                    }
                    for (UUID m : r.members()) {
                        host.sound(m, LavaHost.Cue.SHOWER, 0);
                        host.actionBar(m, LavaText.SHOWER_SMALL);
                    }
                }
            }
            case DRAIN -> {
                patchUntil = -1;
                writer.glowOnly(null);
                writer.glow(0);
                writer.target(0);
            }
            default -> {
                // GATHER and OVER have no lava of their own
            }
        }
        bars(r);
    }

    /** 0.57.0: the furniture is about to move: a hum and a title; its sparkles are {@link #tickUi}'s. */
    private void shiftComing(LavaRound r, LavaEvent.ShiftComing s) {
        nextShiftSeed = s.seed();
        nextShift = host.offThread(() -> LavaPlanner.site(box, s.seed()));
        host.hideStars();
        stars.clear();
        for (UUID m : r.members()) {
            host.sound(m, LavaHost.Cue.SHIFT, 0);
            host.title(m, LavaText.SHIFT_BIG, LavaText.SHIFT_SMALL, s.ticks());
        }
    }

    /** 0.57.0: the furniture moves now. */
    private void shift(LavaRound r, LavaEvent.Shift s) {
        LavaSite next = null;
        if (nextShift != null && nextShiftSeed == s.seed()) {
            next = await(nextShift);
        }
        nextShift = null;
        if (next == null) {
            next = LavaPlanner.site(box, s.seed());
        }
        shifts++;
        move(r, next, true);
        base = room;
    }

    /**
     * The furniture to {@code next}'s layout: the blocks moved, everyone on a piece carried with it, everyone a piece
     * landed on lifted onto it ({@link LavaShift}); a puff where each moved piece is now.
     */
    private void move(LavaRound r, LavaSite next, boolean show) {
        if (writer == null || next == null || next == room) {
            return;
        }
        LavaSite was = room;
        int level = writer.level();
        writer.moveTo(next);
        room = next;
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f == null || was.inGallery(f[0], f[1], f[2])) {
                continue;
            }
            LavaSite.Spot to = LavaShift.carry(was, next, f[0], f[1], f[2], level);
            if (to != null) {
                host.carry(p, world, to);
            }
        }
        if (show) {
            for (int slot : LavaLayout.moved(was.layout(), next.layout())) {
                double[] c = top(next, slot);
                if (c != null) {
                    host.fx(world, LavaHost.Fx.LANDED, c[0], c[1], c[2]);
                }
            }
            for (UUID m : r.members()) {
                host.sound(m, LavaHost.Cue.MOVED, 0);
            }
        }
    }

    /** The middle of a piece's top, or {@code null} for a piece that isn't there. */
    private static double[] top(LavaSite s, int slot) {
        LavaLayout.Placed p = slot < s.layout().size() ? s.layout().get(slot) : null;
        if (p == null) {
            return null;
        }
        double x = s.box().minX() + p.x() + p.sizeX() / 2.0;
        double z = s.box().minZ() + p.z() + p.sizeZ() / 2.0;
        double y = s.box().minY() + 1;
        for (LavaSite.Stand st : s.column((int) Math.floor(x), (int) Math.floor(z))) {
            if (s.pieceOf(st) == slot) {
                y = Math.max(y, st.y());
            }
        }
        return new double[]{x, y + 0.3, z};
    }

    private static LavaSite await(CompletableFuture<LavaSite> f) {
        try {
            return f.join();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 0.57.0: the round's surprise: a bouncy cushion now; the others at the warning. */
    private void surpriseComing(LavaRound r, LavaEvent.SurpriseComing s) {
        surprise = s.surprise();
        surprisePick = s.pick();
        surprisesSeen++;
        if (surprise == LavaRound.Surprise.BOUNCY && writer != null) {
            List<Integer> cushions = new ArrayList<>();
            for (int i = 0; i < room.layout().size(); i++) {
                LavaLayout.Placed p = room.layout().get(i);
                if (p != null && p.kind() == LavaLayout.Kind.CUSHION) {
                    cushions.add(i);
                }
            }
            if (!cushions.isEmpty()) {
                bouncySlot = cushions.get(Math.floorMod(surprisePick, cushions.size()));
                move(r, LavaPlanner.plan(box, LavaLayout.bouncy(room.layout(), bouncySlot)), false);
                for (UUID m : r.members()) {
                    host.sound(m, LavaHost.Cue.BOING, 0);
                    host.actionBar(m, LavaText.BOUNCY_SMALL);
                }
            }
        }
    }

    /**
     * 0.57.0: the piece that sinks this round: one that would be dry at the coming lava (so its sinking matters),
     * never the sofa, and only while plenty of dry ground is left for everyone without it; -1 for none.
     */
    private int pickSink(LavaRound r) {
        int level = r.nextLevel();
        List<Integer> can = new ArrayList<>();
        for (int i = 1; i < room.layout().size(); i++) {
            if (room.layout().get(i) != null) {
                for (LavaSite.Stand st : room.safe(level)) {
                    if (room.pieceOf(st) == i) {
                        can.add(i);
                        break;
                    }
                }
            }
        }
        if (can.isEmpty()) {
            return -1;
        }
        int slot = can.get(Math.floorMod(surprisePick, can.size()));
        int theirs = 0;
        for (LavaSite.Stand st : room.safe(level)) {
            theirs += room.pieceOf(st) == slot ? 1 : 0;
        }
        int left = room.safe(level).size() - theirs;
        return left >= Math.max(LavaSettings.MAX_PLAYERS, 2 * r.members().size()) ? slot : -1;
    }

    /** 0.57.0: the wobbling piece goes under. */
    private void sink(LavaRound r) {
        LavaSite next = sinkNext == null ? null : await(sinkNext);
        int slot = sinkSlot;
        sinkSlot = -1;
        sinkNext = null;
        if (next == null) {
            return;
        }
        double[] c = top(room, slot);
        move(r, next, false);
        if (c != null) {
            host.fx(world, LavaHost.Fx.SMOKE, c[0], c[1], c[2]);
        }
        for (UUID m : r.members()) {
            host.sound(m, LavaHost.Cue.SANK, 0);
        }
    }

    private void starsOut(LavaRound r, LavaEvent.StarsOut s) {
        host.hideStars();
        stars.clear();
        List<LavaSite.Spot> spots = starPlaces(r, s.ids().size(), s.lava(), seed + 31L * s.set());
        for (int n = 0; n < s.ids().size() && n < spots.size(); n++) {
            LavaSite.Spot at = spots.get(n);
            LavaSite.Spot star = new LavaSite.Spot(at.x(), at.y() + STAR_UP, at.z(), 0f);
            stars.put(s.ids().get(n), star);
            host.showStar(world, s.ids().get(n), star.x(), star.y(), star.z());
        }
        if (s.lava() && !spots.isEmpty()) {
            for (UUID m : r.members()) {
                if (r.playing(m)) {
                    host.actionBar(m, LavaText.LAVA_STARS);
                }
            }
        }
    }

    /**
     * 0.57.0: where {@code n} stars go. With the floor dry: on the floor, a third up on the furniture (Little: the
     * low furniture, a walk from the floor). With lava: dry tops the players can get to without touching it (walks
     * and hops: {@link LavaSite#hopComponent}), away from where they stand while there is room, so they must move.
     */
    List<LavaSite.Spot> starPlaces(LavaRound r, int n, boolean lava, long seed) {
        LavaSite s = room;
        int level = Math.max(r.level(), writer == null ? 0 : writer.level());
        if (r.mode() == LavaMode.LITTLE) {
            List<LavaSite.Stand> low = new ArrayList<>();
            for (LavaSite.Stand st : s.lowStands(LITTLE_LOW)) {
                if (s.dry(st, Math.max(level, lava ? 1 : 0))) {
                    low.add(st);
                }
            }
            if (lava) {
                return LavaSite.starSpots(awayFrom(low, r, n), n, seed, 3);
            }
            List<LavaSite.Spot> out = new ArrayList<>(LavaSite.starSpots(low, Math.min(low.size(), n / UP_EVERY), seed,
                    4));
            out.addAll(s.starSpots(n - out.size(), seed + 1));
            return out;
        }
        if (!lava && level == 0) {
            List<LavaSite.Stand> up = new ArrayList<>();
            double floor = box.minY() + 1;
            for (LavaSite.Stand st : s.hopReach()) {
                if (st.y() > floor + 1e-9 && st.y() - box.minY() <= LavaPlanner.FLOOD) {
                    up.add(st);
                }
            }
            List<LavaSite.Spot> out = new ArrayList<>(LavaSite.starSpots(up, n / UP_EVERY, seed, 4));
            out.addAll(s.starSpots(n - out.size(), seed + 1));
            return out;
        }
        Set<LavaSite.Stand> can = s.hopComponent(level, standing(r, level));
        if (can.size() < n) {
            can = new LinkedHashSet<>(can);
            for (LavaSite.Stand st : s.hopReach()) {
                if (s.dry(st, level)) {
                    can.add(st);
                }
            }
        }
        return LavaSite.starSpots(awayFrom(can, r, n), n, seed, 4);
    }

    /** The stands the players in the round stand on now (or the nearest dry ones). */
    private List<LavaSite.Stand> standing(LavaRound r, int level) {
        List<LavaSite.Stand> out = new ArrayList<>();
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f == null || !r.playing(p)) {
                continue;
            }
            LavaSite.Stand st = room.standUnder(f[0], f[1], f[2]);
            if (st == null || !room.dry(st, level)) {
                st = room.nearestDry(level, f[0], f[1], f[2]);
            }
            if (st != null) {
                out.add(st);
            }
        }
        return out;
    }

    /** {@code from} less what is within {@value #STAR_AWAY} of a player, while {@code n} are left. */
    private List<LavaSite.Stand> awayFrom(Collection<LavaSite.Stand> from, LavaRound r, int n) {
        List<double[]> feet = new ArrayList<>();
        for (UUID p : r.members()) {
            double[] f = host.feet(p);
            if (f != null && r.playing(p)) {
                feet.add(f);
            }
        }
        List<LavaSite.Stand> out = new ArrayList<>();
        for (LavaSite.Stand st : from) {
            boolean near = false;
            for (double[] f : feet) {
                near |= Math.max(Math.abs(st.x() + 0.5 - f[0]), Math.abs(st.z() + 0.5 - f[2])) < STAR_AWAY;
            }
            if (!near) {
                out.add(st);
            }
        }
        return out.size() >= n ? out : new ArrayList<>(from);
    }

    private void starTaken(LavaRound r, LavaEvent.StarTaken t) {
        LavaSite.Spot at = stars.remove(t.id());
        host.hideStar(t.id());
        if (at != null) {
            host.fx(world, LavaHost.Fx.SPARKLE, at.x(), at.y(), at.z());
        }
        for (UUID m : r.members()) {
            host.sound(m, LavaHost.Cue.STAR, r.mode() == LavaMode.CHALLENGE ? r.stars() : t.have());
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
            to = room.nearestSafe(deep, f[0], f[1], f[2]);
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
        host.tell(o.player(), o.starless() ? LavaText.starless(o.survived()) : LavaText.out(o.place(), o.survived()));
        host.tell(o.player(), LavaText.WATCHING);
        bars(r);
    }

    /** 0.57.0, Group: the round's goal, reached (a chord and a tick) or missed (a heart goes). */
    private void goal(LavaRound r, LavaEvent.Goal g) {
        for (UUID m : r.members()) {
            host.sound(m, g.made() ? LavaHost.Cue.GOAL : LavaHost.Cue.MISSED, 0);
            host.title(m, g.made() ? LavaText.GOAL_BIG : LavaText.MISSED_BIG, LavaText.goalSmall(g.got(), g.goal(),
                    g.made(), g.hearts()), 40);
        }
        bars(r);
    }

    /** The round is over: the lava and the furniture away at once, the bests recorded, then the party. */
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
            if (f != null && (site.inGallery(f[0], f[1], f[2]) || site.collides(f[0], f[1], f[2]))) {
                host.teleport(p, world, site.spawn(spawnNext + n++)); // down from the gallery (or out of a piece) for the party
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
        return kid && asked == LavaMode.LITTLE && r.mode() == LavaMode.GROUP ? LavaMode.GROUP : null;
    }

    /**
     * Why {@code player} can't come in now ({@code &}-coded, for chat), or {@code null}: the room isn't built and
     * checked yet, it is closed, in use for another mode or full, a Challenge has started, or they are in already.
     *
     * @param kid the Clubhouse's cabinet was tapped (Little there joins the Group round going)
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
        return room.entry(deep, spawnNext++);
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
        boolean fresh = round == null;
        if (fresh) {
            newRound(mode);
            for (UUID p : carry) {
                round.join(p);
                host.teleport(p, world, room.spawn(spawnNext++));
            }
        }
        LavaRound r = round;
        LavaRound.Join j = r.join(player);
        if (j != LavaRound.Join.JOINED && j != LavaRound.Join.ALREADY_IN) {
            return j == LavaRound.Join.FULL ? LavaText.full(r.rules().maxPlayers()) : LavaText.CHALLENGE_GOING;
        }
        if (fresh) {
            host.teleport(player, world, room.spawn(spawnNext++)); // onto the sofa, wherever it stands this game
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

    /** A new round of {@code mode}, with the furniture somewhere new (0.57.0) when the room moves. */
    private void newRound(LavaMode mode) {
        WorldPort port = host.world(world);
        if (port != null && (writer == null || writerPort != port)) {
            writerPort = port;
            writer = new LavaWriter(port, site);
        }
        hold(true);
        gamesMade++;
        seed = LavaLayout.mix(host.now(), gamesMade);
        round = new LavaRound(mode, host.settings().rules(mode), seed);
        overAt = -1;
        phaseTicks = Math.max(1, round.rules().gatherTicks());
        stars.clear();
        dryUntil.clear();
        clearSurprise();
        if (writer != null && host.settings().movingRoom()) {
            LavaSite next = takeGame();
            if (next != null && next != room) {
                writer.moveTo(next);
                room = next;
            }
        }
        base = room;
    }

    /** 0.57.0: start working out the next game's layout (off the main thread), so it is ready when it is wanted. */
    private void prepareGame() {
        long s = LavaLayout.mix(host.now() ^ 0x5DEECE66DL, gamesMade + 101);
        nextGame = host.offThread(() -> LavaPlanner.site(box, s));
    }

    /** The next game's room (waiting for it if it isn't ready yet), and the one after it set going. */
    private LavaSite takeGame() {
        CompletableFuture<LavaSite> f = nextGame;
        LavaSite next = f == null ? null : await(f);
        prepareGame();
        return next == null ? site : next;
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
     * room, each back on the sofa (wherever it stands this game).
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
            host.teleport(p, world, room.spawn(spawnNext++));
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
        if (ph == LavaRound.Phase.SAFE && r.shifting() && (phaseTicks - r.left()) % 10 == 0) {
            for (int slot = 0; slot < room.layout().size(); slot++) { // every piece sparkles: it is about to move
                double[] c = top(room, slot);
                if (c != null) {
                    host.fx(world, LavaHost.Fx.SHIFT, c[0], c[1], c[2]);
                }
            }
        }
        if (bouncySlot >= 0 && ph.inPlay() && ticks % 20 == 0) {
            double[] c = top(room, bouncySlot);
            if (c != null) {
                host.fx(world, LavaHost.Fx.SLIME, c[0], c[1], c[2]);
            }
        }
        if (ph == LavaRound.Phase.WARNING) {
            int gone = phaseTicks - r.left();
            int every = surprise == LavaRound.Surprise.FAST_RISE ? RISE_EVERY / 2 : RISE_EVERY;
            if (gone % every == 0) {
                int step = gone / every;
                for (UUID p : r.members()) {
                    host.sound(p, LavaHost.Cue.RISE, step);
                }
                Box in = site.inside();
                for (int n = 0; n < 6; n++) { // embers, here and there over the floor
                    double x = in.minX() + 2 + ((step * 13 + n * 17) % (in.sizeX() - 4));
                    double z = in.minZ() + 2 + ((step * 7 + n * 23) % (in.sizeZ() - 4));
                    if (patchSide == null || patchSide.has(room, (int) x, (int) z)) {
                        host.fx(world, LavaHost.Fx.EMBERS, x + 0.5, in.minY() + r.level() + 0.2, z + 0.5);
                    }
                }
                if (sinkSlot >= 0) {
                    double[] c = top(room, sinkSlot);
                    if (c != null) {
                        host.fx(world, LavaHost.Fx.WOBBLE, c[0], c[1], c[2]);
                        for (UUID p : r.members()) {
                            host.sound(p, LavaHost.Cue.WOBBLE, step);
                        }
                    }
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

    /** Everyone's bar for where the round is: pictures first (a row of stars, hearts, dots for rounds). */
    private void bars(LavaRound r) {
        if (r.over()) {
            return;
        }
        if (r.phase() == LavaRound.Phase.GATHER) {
            String title = LavaText.gatherBar(r.mode(), (r.left() + LavaRound.TICKS_PER_SECOND - 1)
                    / LavaRound.TICKS_PER_SECOND, r.members().size());
            float progress = r.left() / (float) Math.max(1, r.rules().gatherTicks());
            for (UUID p : r.members()) {
                show(p, title, progress, false);
            }
            return;
        }
        for (UUID p : r.members()) {
            switch (r.mode()) {
                case LITTLE -> show(p, LavaText.starRow(r.stars(), r.goal()), r.stars() / (float) Math.max(1, r.goal()),
                        false);
                case GROUP -> show(p, LavaText.groupBar(r.stars(), r.round(), r.rules().rounds(), r.roundStars(),
                        r.roundGoal(), r.hearts(), r.rules().hearts()), r.roundStars() / (float) Math.max(1,
                        r.roundGoal()), false);
                default -> show(p, LavaText.challengeBar(r.round(), r.alive().size(), r.starsThisRound(p), r.need()),
                        r.phase() == LavaRound.Phase.LAVA ? 1f : 1f - r.left() / (float) phaseTicks, true);
            }
        }
    }

    private void show(UUID p, String title, float progress, boolean red) {
        host.bar(p, title, Math.max(0f, Math.min(1f, progress)), red);
        barShown.add(p);
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
        Box old = claimed ? null : grownFrom(claim, world, box);
        if (!claimed && claim != null && old == null) {
            host.logger().info("The Floor Is Lava: the box moved from " + claim + " - the old one's blocks are left"
                    + " where they are");
        }
        if (old != null) {
            host.logger().info("The Floor Is Lava: the playroom grows from " + old.describe() + " to " + box.describe()
                    + " - checking that its new ground is empty");
        }
        if (!verified) {
            for (Person p : host.people()) { // a first build: nobody stands where the room goes
                if (p.in(world, box)) {
                    host.toSafety(p.id(), world);
                }
            }
        }
        startJob(port, !claimed, old);
    }

    /**
     * 0.57.0: the room before it grew, when {@code claim} is its claim (the same world and corner, the old size), so
     * only the new ground needs counting; else {@code null}.
     */
    static Box grownFrom(String claim, String world, Box box) {
        if (claim == null) {
            return null;
        }
        Box old = Box.sized(box.minX(), box.minY(), box.minZ(), LavaPlanner.OLD_SIZE, box.sizeY(), LavaPlanner.OLD_SIZE);
        return !old.equals(box) && claim.equals(claimText(world, old)) ? old : null;
    }

    /** Whether a claim is this box's, or the room's before it grew (it becomes this box's at the next start). */
    public static boolean claimedOrGrowing(String claim, String world, Box box) {
        return claim != null && (claim.equals(claimText(world, box)) || grownFrom(claim, world, box) != null);
    }

    private void startJob(WorldPort port, boolean scan, Box old) {
        job = new BuildJob(port, box, scan ? null : site.plan(), scan ? BuildJob.Mode.SCAN : BuildJob.Mode.CONVERGE);
        jobScan = scan;
        jobGrow = scan && old != null;
        if (jobGrow) {
            // the old room's own chunks are left out: only the new ground is counted
            job.only((cx, cz) -> !(cx >= old.minX() >> 4 && cx <= old.maxX() >> 4 && cz >= old.minZ() >> 4
                    && cz <= old.maxZ() >> 4 && (old.minX() & 15) == 0 && (old.minZ() & 15) == 0
                    && ((old.maxX() + 1) & 15) == 0 && ((old.maxZ() + 1) & 15) == 0));
        }
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
                claimScanned(done, jobGrow);
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

    /** The claim's count is in: an empty box (or new ground) is claimed and built; anything in it closes the game. */
    private void claimScanned(BuildJob scan, boolean grow) {
        if (scan.found() > 0) {
            closedNote = scan.firstFound().isEmpty() ? null : "first at " + String.join(" ", scan.firstFound());
            close("its box has " + scan.found() + " block" + (scan.found() == 1 ? "" : "s") + " that aren't the"
                    + " playroom's" + (grow ? " where it grows to" : "") + " - /hcm games lava claim confirm clears"
                    + " them, or move it with games.floor_is_lava.origin");
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
        host.logger().info("The Floor Is Lava: claimed its " + (grow ? "bigger" : "empty") + " box ("
                + box.describe() + ")");
        WorldPort port = host.world(world);
        if (port == null) {
            pending = true;
            return;
        }
        startJob(port, false, null);
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
                    : grownFrom(claim, world, box) != null ? "&eThe playroom's old box is claimed: its new ground is"
                    + " claimed at its next build when it is empty (" + box.describe() + ")."
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
                    : writer.level()) + " deep, " + r.stars() + " stars, " + stars.size() + " out"
                    + (room == site ? ", the furniture as built" : ", the furniture moved")
                    + (r.surprise() == null ? "" : ", surprise: " + r.surprise().name().toLowerCase(Locale.ROOT)));
        }
        if (job != null) {
            out.add((jobScan ? (jobGrow ? "new ground scan" : "claim scan") : "build") + ": pass " + job.pass() + ", "
                    + job.waiting() + " to write" + (budget.paused() ? " (paused: the server is busy)" : ""));
        } else if (waitingFor != null) {
            out.add("build waiting: " + waitingFor);
        }
        out.add("builds: " + builds + " done; last " + lastWrites + " blocks; " + roundsPlayed + " rounds played; "
                + shifts + " furniture moves; " + surprisesSeen + " surprises; " + (writer == null ? 0
                : writer.written()) + " lava, furniture and floor writes"
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

    /** The room as it stands now (0.57.0: its furniture where the round has it), or {@code null} when unplanned. */
    public LavaSite site() {
        return room;
    }

    /** The room as built: the plan the box is checked against. */
    public LavaSite built() {
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

    /** 0.57.0: the lava's level where it is deepest now (what an NPC reads). */
    public int lavaNow() {
        return writer == null ? 0 : writer.level();
    }

    /** 0.57.0: whether the cell is lava now (an NPC's touch). */
    public boolean lavaAt(int x, int y, int z) {
        return writer != null && writer.lavaAt(x, y, z);
    }
}
