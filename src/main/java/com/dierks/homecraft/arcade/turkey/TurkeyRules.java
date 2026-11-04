package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyRules.Offset;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.storage.TokenDao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Catch the Turkey's rules (0.38.6), as plain functions over what the world and the player are, so nothing here
 * touches a server and every rule is pinned by {@code TurkeyRulesTest}. {@code TurkeyService} reads the facts and
 * asks here; {@link TurkeyRuns} keeps the runs.
 *
 * <ul>
 *   <li><b>When:</b> the candy shower's slots ({@link CandyRules#dueSlot}, {@link CandyRules#heldByRestart}),
 *       read only. A run's id names its slot, or the day and the owner of a welcome turkey ({@link #runId},
 *       {@link #welcomeRunId}).</li>
 *   <li><b>Who:</b> the candy shower's skips, then the Overworld only ({@link #skip}): no turkey by a Nether
 *       lava lake or on the End's edge.</li>
 *   <li><b>Where:</b> 4-6 blocks out in front of the player ({@link #spotOffsets}), on solid ground with room
 *       above it, dry, away from lava and in sight ({@link #spotOk}).</li>
 *   <li><b>A catch:</b> a tap that counts ({@link #catchStep}), claimed once a run a player ({@link #claimKey}),
 *       paid under its own daily cap ({@link #paidToday}).</li>
 * </ul>
 *
 * <p>Fair to a 4-year-old: every player gets their own turkey and everyone who catches one in a run gets the same
 * prize, so nobody has to be first. Nothing here draws a random number; the service rolls and passes the roll in.
 */
public final class TurkeyRules {

    private TurkeyRules() {
    }

    /** One turkey for each player, for up to this many players in a run; status says "cap" beyond it. */
    public static final int MAX_TURKEYS_PER_RUN = 6;
    /** The welcome turkey comes this long after the first join of the day. */
    public static final long WELCOME_DELAY_MS = 90_000L;
    /** Someone who arrives mid-run gets a turkey only while more than this is left of it. */
    public static final long JOIN_MIN_LEFT_MS = 60_000L;
    /** Two taps on one turkey both count only this far apart (a held tap, or Bedrock's double taps). */
    public static final long TAP_GAP_MS = 600L;

    /** Where the turkey appears: this many blocks out (by the larger axis), within this many degrees of the look. */
    public static final int SPOT_MIN = 4;
    public static final int SPOT_MAX = 6;
    public static final double SPOT_ARC_DEGREES = 60;
    /** The floors tried at each spot, relative to the block under the player's feet: their own floor first. */
    public static final List<Integer> FLOOR_STEPS = List.of(0, 1, -1, 2, -2);
    /** A chicken's height in blocks before it is scaled. */
    public static final double CHICKEN_HEIGHT = 0.7;

    /** Past this many blocks from where it appeared the turkey is walked back; past {@link #LOST_BLOCKS} it ends. */
    public static final double LEASH_BLOCKS = 6;
    public static final double LOST_BLOCKS = 24;

    /** Why a player gets no turkey when they are somewhere else than the Overworld: the basket's words. */
    public static final String NOT_OVERWORLD = CandyRules.NOT_OVERWORLD;

    // ---- ids and keys ---------------------------------------------------------------------

    /** A scheduled run's id: {@code "s" + slot id}, so a slot can only ever be one run. */
    public static String runId(long slotId) {
        return "s" + slotId;
    }

    /** A welcome run's id: {@code "w" + epoch day + "-" + the first 8 hex digits of the owner's UUID}. */
    public static String welcomeRunId(long epochDay, UUID owner) {
        return "w" + epochDay + "-" + owner.toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    /** A test run's id ({@code /hcm turkey now} outside the window), from when it started: never claimed. */
    public static String testRunId(long startedAtMillis) {
        return "t" + startedAtMillis;
    }

    /** Whether a run id is a welcome run's. */
    public static boolean welcomeRun(String runId) {
        return runId != null && runId.startsWith("w");
    }

    /**
     * The catch's mark in {@code player_counters}: {@code "turkey:<year>:<run id>"}, written with
     * {@code addCounter == 1} before anything is given, so a restart or a second tap never pays twice.
     */
    public static String claimKey(int year, String runId) {
        return "turkey:" + year + ":" + runId;
    }

    /** The welcome turkey's mark for a local day: written when it appears, so a restart never gives a second one. */
    public static String welcomeKey(int year, long epochDay) {
        return "turkey:" + year + ":welcome:" + epochDay;
    }

    /** The mark of the one extra Turkey Mini a player can get from catching turkeys in a season. */
    public static String miniKey(int year) {
        return "turkey:" + year + ":mini";
    }

    /** The Turkey Mini of the event's year: {@code turkey_mini_<year>}. */
    public static String miniId(int year) {
        return "turkey_mini_" + year;
    }

    /** {@code Keys.TURKEY}'s value on a turkey: {@code "<run id>|<owner uuid>"}. */
    public static String tag(String runId, UUID owner) {
        return runId + "|" + owner;
    }

    /** The run id a {@link #tag} names, or null when it isn't one. */
    public static String tagRun(String tag) {
        if (tag == null) {
            return null;
        }
        int bar = tag.indexOf('|');
        return bar <= 0 ? null : tag.substring(0, bar);
    }

    // ---- when -----------------------------------------------------------------------------

    /** Every slot of a local day ({@code every} minutes from {@code offset}): for "caught today". */
    public static List<Long> slotsOn(LocalDate day, int every, int offset) {
        List<Long> out = new ArrayList<>();
        if (day == null || every <= 0) {
            return out;
        }
        for (int m = CandyRules.offsetOn(offset, every); m < CandyRules.MINUTES_PER_DAY; m += every) {
            out.add(CandyRules.slotId(day, m));
        }
        return out;
    }

    // ---- who ------------------------------------------------------------------------------

    /**
     * Why the player gets no turkey, or null when they get one: the candy shower's skips (dead, spectator, not an
     * economy world, the Games world, in a game, idle 5+ minutes, flying, in lava or water, in a vehicle), then
     * anywhere but the Overworld. The shower's live-candy caps don't apply: {@code liveOwn} and {@code liveServer}
     * are read as 0.
     */
    public static String skip(CandyRules.State s, boolean overworld) {
        CandyRules.State own = new CandyRules.State(s.dead(), s.sleeping(), s.mode(), s.economyWorld(), s.gamesWorld(),
                s.inGame(), s.home(), s.idleMillis(), s.gliding(), s.flying(), s.feetY(), s.groundTop(), s.inLava(),
                s.inWater(), s.inVehicle(), 0, 0);
        String why = CandyRules.skip(own);
        if (why != null) {
            return why;
        }
        return overworld ? null : NOT_OVERWORLD;
    }

    /** What kind of run a turkey belongs to. */
    public enum Kind {
        /** On the schedule (or {@code /hcm turkey now}): anyone may catch any of its turkeys, once each. */
        SCHEDULED,
        /** One player's own, after their first join of the day: only they can catch it. */
        WELCOME
    }

    /**
     * Whether a player gets a turkey: never in a run they have already caught (a restart keeps the claim), and
     * never a second welcome turkey on a day they had one.
     */
    public static boolean spawnFor(boolean claimed, boolean welcomeGiven, Kind kind) {
        if (claimed) {
            return false;
        }
        return kind != Kind.WELCOME || !welcomeGiven;
    }

    // ---- where ----------------------------------------------------------------------------

    /**
     * The spots to try, in order: every offset 4-6 blocks out (by the larger axis) within 60 degrees of the look,
     * the middle distance and straight ahead first. A child looks where she is going, so the turkey is in front.
     */
    public static List<Offset> spotOffsets(float yaw) {
        List<Offset> out = new ArrayList<>();
        for (Offset o : CandyRules.ringCandidates(SPOT_MIN, SPOT_MAX)) {
            if (CandyRules.inFront(o, yaw, SPOT_ARC_DEGREES)) {
                out.add(o);
            }
        }
        double rad = Math.toRadians(yaw);
        double lx = -Math.sin(rad);
        double lz = Math.cos(rad);
        int middle = (SPOT_MIN + SPOT_MAX) / 2;
        out.sort(Comparator.<Offset>comparingInt(o -> Math.abs(o.distance() - middle))
                .thenComparingDouble(o -> -(o.bx() * lx + o.bz() * lz) / Math.hypot(o.bx(), o.bz()))
                .thenComparingInt(Offset::bx).thenComparingInt(Offset::bz));
        return out;
    }

    /** Air blocks the turkey needs above its floor: its scaled height rounded up, and one more. */
    public static int headroom(double scale) {
        return (int) Math.ceil(CHICKEN_HEIGHT * scale - 1e-9) + 1;
    }

    /**
     * What the service read about one floor block and the blocks above it.
     *
     * @param loaded     the chunk is loaded (one that isn't is never read, and never loaded for a turkey)
     * @param floorSolid the floor block is solid
     * @param floorName  its material's name, for the hazards (cactus, magma, a campfire, powder snow)
     * @param clear      how many blocks above the floor are passable, dry and not in the way (a berry bush,
     *                   powder snow, a cobweb, a portal), counted up to what is needed
     * @param wetOrFire  the floor or a block above it is water, lava or fire
     * @param lavaNear   lava beside the floor or the turkey's feet (a neighbour in an unloaded chunk counts)
     * @param inSight    one ray from the player's eyes to the turkey hits nothing first
     */
    public record Ground(boolean loaded, boolean floorSolid, String floorName, int clear, boolean wetOrFire,
                         boolean lavaNear, boolean inSight) {
    }

    /** Whether a turkey of this {@code scale} may appear on that floor. */
    public static boolean spotOk(Ground g, double scale) {
        return g != null && g.loaded() && g.floorSolid() && !CandyRules.hazard(g.floorName())
                && g.clear() >= headroom(scale) && !g.wetOrFire() && !g.lavaNear() && g.inSight();
    }

    /** What a turkey that has wandered does now. */
    public enum Leash {
        /** Near where it appeared: it wanders. */
        STAY,
        /** Past {@value #LEASH_BLOCKS} blocks: it is walked back. */
        BRING_BACK,
        /** Gone, wet, in another world or past {@value #LOST_BLOCKS} blocks: that turkey ends. */
        LOST
    }

    /** {@code distance} is from where it appeared, in the same world; NaN or negative means another world. */
    public static Leash leash(boolean valid, boolean inWater, boolean inLava, double distance) {
        if (!valid || inWater || inLava || Double.isNaN(distance) || distance < 0 || distance > LOST_BLOCKS) {
            return Leash.LOST;
        }
        return distance > LEASH_BLOCKS ? Leash.BRING_BACK : Leash.STAY;
    }

    // ---- a catch --------------------------------------------------------------------------

    /**
     * One tap (a hit or a use, on Java or Bedrock) on a turkey, as the service read it.
     *
     * @param mainHand     the main hand's tap (a use fires once for each hand; a hit is always the main hand)
     * @param lastTapAt    this player's last tap on this same turkey, in ms, or null
     * @param now          now, in ms
     * @param economyWorld the tapper is in an economy world
     * @param gamesWorld   the tapper is in a Games world
     * @param home         the tapper is home from every game
     * @param inSession    the tapper is in a world game's session
     * @param welcome      the turkey is someone's welcome turkey
     * @param owner        the tapper is the turkey's owner
     */
    public record Tap(boolean mainHand, Long lastTapAt, long now, boolean economyWorld, boolean gamesWorld,
                      boolean home, boolean inSession, boolean welcome, boolean owner) {
    }

    /** What a tap does. Every tap on a turkey is cancelled, whatever this says. */
    public enum CatchStep {
        /** Nothing: the off hand, a held tap, or the tapper is in a game or somewhere else. */
        IGNORE,
        /** Someone else's welcome turkey: a soft cluck, never silence. */
        NOT_YOURS,
        /** A try at catching it: the run decides whether it is a catch or "already caught". */
        CATCH
    }

    /** The tap's step: main hand, {@value #TAP_GAP_MS} ms since the last tap on it, at home and in the open. */
    public static CatchStep catchStep(Tap t) {
        if (!t.mainHand() || !CandyRules.cooledDown(t.lastTapAt(), t.now(), TAP_GAP_MS)) {
            return CatchStep.IGNORE;
        }
        if (!t.economyWorld() || t.gamesWorld() || !t.home() || t.inSession()) {
            return CatchStep.IGNORE;
        }
        if (t.welcome() && !t.owner()) {
            return CatchStep.NOT_YOURS;
        }
        return CatchStep.CATCH;
    }

    /** Tokens already paid for turkeys today: the {@code EVENT_TURKEY} lines of the ledger since local midnight. */
    public static long paidToday(List<TokenDao.SourceTotal> totals) {
        long paid = 0;
        if (totals != null) {
            for (TokenDao.SourceTotal t : totals) {
                if (TokenService.Source.EVENT_TURKEY.name().equals(t.source())) {
                    paid += t.earned();
                }
            }
        }
        return paid;
    }

    /**
     * The tokens a catch pays: {@code perCatch}, cut to what is left of the day's {@code cap}; 0 in a test run, out
     * of the window or in Creative. The catch moment plays either way.
     */
    public static int tokensToPay(long paidToday, int perCatch, int cap, boolean open, boolean paysTokens, boolean test) {
        return test ? 0 : CandyRules.tokensToPay(paidToday, perCatch, cap, open, paysTokens);
    }

    /**
     * Whether a welcome turkey waits for the restart: inside the restart hold, or when its run would still be going
     * at the next restart. The turkeys aren't saved and the welcome's day mark is written when it appears, so a
     * restart mid-run would take the day's welcome for good. It stays owed and comes after the restart instead.
     *
     * @param now       the clock (ms)
     * @param runMillis how long a run lasts
     * @param hold      the restart hold, or null when there is none
     */
    public static boolean welcomeHeld(long now, long runMillis, RestartHold hold) {
        if (hold == null) {
            return false;
        }
        if (hold.holding(now)) {
            return true;
        }
        long next = hold.next(now);
        return next >= 0 && now + runMillis > next;
    }

    /**
     * Whether a catch also gives a Turkey Mini: a roll in {@code [0, 1)} under {@code percent} in 100, for a player
     * who hasn't had their one extra this season and has an empty slot, and never in a test run. With a full bag the
     * catch doesn't roll at all, so the season's one chance is kept for a later catch instead of a Mini dropped on the
     * ground, where nothing protects it.
     */
    public static boolean extraMini(double roll, int percent, boolean hadExtra, boolean test, boolean hasRoom) {
        return !test && !hadExtra && hasRoom && percent > 0 && roll < percent / 100.0;
    }
}
