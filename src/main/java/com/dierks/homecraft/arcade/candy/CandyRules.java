package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.GameMode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Candy Apple shower's rules (HALLOWEEN-SPEC D10-D18), as plain functions over what the world and the
 * player are. {@code CandyService} reads the facts (the blocks, the player, the ledger) and asks here, so
 * nothing in this class touches a server and every rule is pinned by {@code CandyRulesTest}.
 *
 * <ul>
 *   <li><b>When:</b> the window ({@link #open}), the slot due this minute ({@link #dueSlot}), the restart
 *       hold ({@link #heldByRestart}) and the next shower ({@link #nextSlot}).</li>
 *   <li><b>Who:</b> the skips, in their order, each with the reason {@code /hcm candy now} prints
 *       ({@link #skip}).</li>
 *   <li><b>Where:</b> the landing ring by block distance, the front arc first ({@link #ringOffsets}); a
 *       column from the sky ({@link #columnOk}); a pop from the floor ({@link #popOk}); and the retry
 *       ({@link #requeue}). No spot the passes can pick is in reach of a player who doesn't move.</li>
 *   <li><b>What it gives:</b> the golden roll ({@link #golden}), the tokens under their daily cap
 *       ({@link #tokensToPay}), what counts ({@link #counts}, {@link #touchCounts}) and the goal
 *       ({@link #crossed}).</li>
 * </ul>
 */
public final class CandyRules {

    private CandyRules() {
    }

    // ---- the numbers fixed in code (D11, D17) -----------------------------------------------

    /** Minutes in a local day: a slot's id is {@code epochDay * 1440 + minute of the day}. */
    public static final int MINUTES_PER_DAY = 1440;
    /** A slot fires in its first this-many minutes, so a start or reload mid-slot waits for the next one. */
    public static final int FIRE_MINUTES = 2;
    /** No slot. */
    public static final long NO_SLOT = -1;

    /** Idle this long or more: skipped. */
    public static final int IDLE_MINUTES = 5;
    /** Live candy caps: per player and on the whole server. */
    public static final int LIVE_PER_PLAYER = 12;
    public static final int LIVE_ON_SERVER = 120;
    /** How long a candy lies before the sweep removes it (a slow 4-year-old has time). */
    public static final long LIFETIME_MS = 180_000L;
    /** One apple every this many ticks: a 3-second shower, not a heap. */
    public static final int SPREAD_TICKS = 10;

    /** The landing ring, by block distance from the player's own block: never nearer than 2. */
    public static final int RING_MIN = 2;
    /** Pass A (from the sky) reaches 4 blocks out; pass B (popped from the floor) 3. */
    public static final int SKY_MAX = 4;
    public static final int POP_MAX = 3;
    /** Tries a pass: the front arc's first, then anywhere. */
    public static final int TRIES = 8;
    public static final int FRONT_TRIES = 6;
    /** The front arc: within this many degrees either side of the look. */
    public static final double FRONT_ARC_DEGREES = 70;
    /**
     * How far vanilla collects an item, from the player's centre on each axis: half the player's width
     * (0.3), the 1.0 the box is grown by, and half an item's (0.125).
     */
    public static final double REACH = 1.43;

    /** A candy falls from this many blocks above its landing block. */
    public static final int FALL_HEIGHT = 8;
    /** Falling and popping speeds, and how high above the floor a pop starts. */
    public static final double FALL_SPEED = -0.1;
    public static final double POP_SPEED = 0.25;
    public static final double POP_HEIGHT = 0.6;
    /** Pass B's floor heights, relative to the block under the feet: the player's own floor first. */
    public static final List<Integer> POP_STEPS = List.of(0, 1, -1);

    /** A spot no pass finds goes back in the queue for a second, up to this many times; then it is dropped. */
    public static final int MAX_REQUEUES = 10;
    public static final int REQUEUE_TICKS = 20;

    /** "Flying" only this many blocks or more above the ground's top + 1, so a Creative child hopping is not. */
    public static final int FLYING_HIGH_BLOCKS = 2;
    /** A full bag still counts candy within this distance of its owner's feet. */
    public static final double TOUCH_DISTANCE = 1.0;
    /** The 3rd apple of a shower (index 2) is golden while the player has never picked one up. */
    public static final int GOLDEN_INDEX = 2;

    /** Blocks a candy never lands or pops on. */
    static final Set<String> HAZARDS = Set.of("CACTUS", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE", "POWDER_SNOW");

    // ---- the counter keys (D14, D15) -------------------------------------------------------

    /** The player's count for the year ({@code player_counters}); the year in the key starts next October at 0. */
    public static String counterKey(int year) {
        return "candy:" + year;
    }

    /** Written at the first golden pickup, so every shower until then has one. */
    public static String goldenSeenKey(int year) {
        return "candy:" + year + ":golden_seen";
    }

    /** The bag's mark, written before the bag is given. */
    public static String bagKey(int year) {
        return "candy:" + year + ":bag";
    }

    /** {@code Keys.CANDY_DROP}'s value on a shower candy: {@code "<owner uuid>:<slot id>"}. */
    public static String dropTag(UUID owner, long slotId) {
        return owner + ":" + slotId;
    }

    /** The owner a {@link #dropTag} names, or null when it isn't one. */
    public static UUID dropOwner(String tag) {
        if (tag == null) {
            return null;
        }
        int colon = tag.indexOf(':');
        try {
            return UUID.fromString(colon < 0 ? tag : tag.substring(0, colon));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- when (D10, D18) -------------------------------------------------------------------

    /** The window, local dates, both ends included. The service adds {@code enabled} and {@code arcade.enabled}. */
    public static boolean open(LocalDate today, LocalDate from, LocalDate to) {
        return today != null && from != null && to != null && !today.isBefore(from) && !today.isAfter(to);
    }

    /** The minutes between showers on a day: {@code lastDayEvery} on the last day (when set), else {@code every}. */
    public static int everyOn(int every, int lastDayEvery, boolean isLastDay) {
        return isLastDay && lastDayEvery > 0 ? lastDayEvery : every;
    }

    /** The day's first slot: the offset folded into that day's interval (10 mod 10 = 0 on the last day). */
    public static int offsetOn(int offset, int every) {
        return every <= 0 ? 0 : Math.floorMod(offset, every);
    }

    /** A slot's id: the local epoch day times 1440, plus its minute of the day. */
    public static long slotId(LocalDate day, int minuteOfDay) {
        return day.toEpochDay() * MINUTES_PER_DAY + minuteOfDay;
    }

    /** When a slot starts, on the local wall clock. */
    public static LocalDateTime slotStart(long slotId) {
        long day = Math.floorDiv(slotId, MINUTES_PER_DAY);
        int minute = (int) Math.floorMod(slotId, MINUTES_PER_DAY);
        return LocalDate.ofEpochDay(day).atStartOfDay().plusMinutes(minute);
    }

    /**
     * The slot due at {@code now} (local), or {@link #NO_SLOT}. A slot starts at each minute {@code m} of the
     * day with {@code m >= offset} and {@code (m - offset) mod every == 0}, and is due during its first
     * {@value #FIRE_MINUTES} minutes; the caller fires it when its id differs from the last one fired. The
     * last day's interval and offset are {@link #everyOn} and {@link #offsetOn}'s.
     */
    public static long dueSlot(LocalDateTime now, int every, int offset, int lastDayEvery, boolean isLastDay) {
        if (now == null) {
            return NO_SLOT;
        }
        int e = everyOn(every, lastDayEvery, isLastDay);
        if (e <= 0) {
            return NO_SLOT;
        }
        int o = offsetOn(offset, e);
        int m = now.getHour() * 60 + now.getMinute();
        for (int late = 0; late < FIRE_MINUTES; late++) {
            int start = m - late;
            if (start >= o && (start - o) % e == 0) {
                return slotId(now.toLocalDate(), start);
            }
        }
        return NO_SLOT;
    }

    /**
     * Whether a restart is close at the slot's start: it is then marked fired and skipped, because the
     * candy isn't saved and the restart would wipe it. {@code zone} is the clock's ({@code clock.time_zone}).
     */
    public static boolean heldByRestart(long slotId, ZoneId zone, RestartHold hold) {
        if (hold == null || zone == null || slotId == NO_SLOT) {
            return false;
        }
        return hold.holding(slotStart(slotId).atZone(zone).toInstant().toEpochMilli());
    }

    /**
     * The next slot to start after {@code after} inside the window, or null when there is none (the window is
     * over). Before the window, it is the first slot of {@code from}. For "Next shower at 7:30 PM."
     */
    public static LocalDateTime nextSlot(LocalDateTime after, LocalDate from, LocalDate to,
                                         int every, int offset, int lastDayEvery) {
        if (after == null || from == null || to == null || from.isAfter(to)) {
            return null;
        }
        LocalDate day = after.toLocalDate().isBefore(from) ? from : after.toLocalDate();
        // Every day has a slot (the offset is below the interval, which is at most 120), so this is two days at most.
        for (; !day.isAfter(to); day = day.plusDays(1)) {
            int e = everyOn(every, lastDayEvery, day.equals(to));
            if (e <= 0) {
                return null;
            }
            for (int m = offsetOn(offset, e); m < MINUTES_PER_DAY; m += e) {
                LocalDateTime start = day.atStartOfDay().plusMinutes(m);
                if (start.isAfter(after)) {
                    return start;
                }
            }
        }
        return null;
    }

    // ---- who (D17) -------------------------------------------------------------------------

    /**
     * What a shower needs to know about a player, read once at the shower.
     *
     * @param economyWorld {@code sandbox().allowed(world)} (true everywhere when {@code economy_enabled} is empty)
     * @param gamesWorld   the world is one of {@code games.worlds} ({@link #gamesWorld})
     * @param inGame       {@code games != null && sessions().session(p) != null}
     * @param home         {@code games == null || sessions().home(p)}: false on the way back from a game
     * @param groundTop    {@code getHighestBlockYAt(x, z, MOTION_BLOCKING)} under the player
     * @param liveOwn      the player's candy still out; {@code liveServer} everyone's
     */
    public record State(boolean dead, boolean sleeping, GameMode mode, boolean economyWorld, boolean gamesWorld,
                        boolean inGame, boolean home, long idleMillis, boolean gliding, boolean flying,
                        double feetY, int groundTop, boolean inLava, boolean inWater, boolean inVehicle,
                        int liveOwn, int liveServer) {
    }

    /**
     * Why the player gets no shower, or null when they get one: the first failure, in D17's order. Creative
     * passes (young children are often put in Creative; it never earns tokens, {@link #paysTokens}).
     */
    public static String skip(State s) {
        if (s.dead() || s.sleeping()) {
            return "not here";
        }
        if (s.mode() == GameMode.SPECTATOR) {
            return "spectator";
        }
        if (!s.economyWorld()) {
            return "not an economy world";
        }
        if (s.gamesWorld()) {
            return "in the Games world";
        }
        if (s.inGame() || !s.home()) {
            return "in a game";
        }
        long idle = idleMinutes(s.idleMillis());
        if (idle >= IDLE_MINUTES) {
            return "idle " + idle + " min";
        }
        if (s.gliding() || (s.flying() && s.feetY() - (s.groundTop() + 1) > FLYING_HIGH_BLOCKS)) {
            return "flying";
        }
        if (s.inLava()) {
            return "in lava";
        }
        if (s.inWater()) {
            return "in water";
        }
        if (s.inVehicle()) {
            return "in a boat or minecart";
        }
        if (s.liveOwn() >= LIVE_PER_PLAYER) {
            return LIVE_PER_PLAYER + " candies already out";
        }
        if (s.liveServer() >= LIVE_ON_SERVER) {
            return LIVE_ON_SERVER + " candies already out on the server";
        }
        return null;
    }

    /** Whole minutes idle (Paper's {@code getIdleDuration}), for the skip and {@code /hcm candy status}. */
    public static long idleMinutes(long idleMillis) {
        return Math.max(0, idleMillis) / 60_000L;
    }

    /** A Games world, by name, case-insensitive (as the Games' own check). */
    public static boolean gamesWorld(String world, Collection<String> gamesWorlds) {
        if (world == null || gamesWorlds == null) {
            return false;
        }
        for (String w : gamesWorlds) {
            if (w != null && w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    // ---- where (D11) -----------------------------------------------------------------------

    /** A block offset from the player's own block. */
    public record Offset(int bx, int bz) {

        /** Its block distance: the larger of the two axes. */
        public int distance() {
            return Math.max(Math.abs(bx), Math.abs(bz));
        }
    }

    /**
     * Every offset with {@code min <= max(|bx|, |bz|) <= max}. At 2 or more, a candy's centre is at least 1.5
     * from every point of the player's block on one axis, past vanilla's {@value #REACH}.
     */
    public static List<Offset> ringCandidates(int min, int max) {
        int lo = Math.max(RING_MIN, min);
        List<Offset> out = new ArrayList<>();
        for (int bx = -max; bx <= max; bx++) {
            for (int bz = -max; bz <= max; bz++) {
                int d = Math.max(Math.abs(bx), Math.abs(bz));
                if (d >= lo && d <= max) {
                    out.add(new Offset(bx, bz));
                }
            }
        }
        return out;
    }

    /**
     * Whether the offset is within {@code arcDegrees} of the look. Minecraft's yaw: 0 faces +Z (south), 90
     * faces -X (west); the look is {@code (-sin yaw, cos yaw)}.
     */
    public static boolean inFront(Offset o, float yaw, double arcDegrees) {
        double rad = Math.toRadians(yaw);
        double lx = -Math.sin(rad);
        double lz = Math.cos(rad);
        double len = Math.hypot(o.bx(), o.bz());
        if (len == 0) {
            return false;
        }
        return (o.bx() * lx + o.bz() * lz) / len >= Math.cos(Math.toRadians(arcDegrees)) - 1e-9;
    }

    /**
     * A pass's tries, by block distance {@code min..max}: {@value #FRONT_TRIES} drawn from those within
     * {@value #FRONT_ARC_DEGREES} degrees of the look, then the rest of the {@value #TRIES} from anywhere
     * (no offset twice). A full ring would put half the candy behind a child, who doesn't turn round.
     */
    public static List<Offset> ringOffsets(float yaw, int min, int max, Random random) {
        List<Offset> front = new ArrayList<>();
        List<Offset> rest = new ArrayList<>();
        for (Offset o : ringCandidates(min, max)) {
            (inFront(o, yaw, FRONT_ARC_DEGREES) ? front : rest).add(o);
        }
        shuffle(front, random);
        List<Offset> out = new ArrayList<>(front.subList(0, Math.min(FRONT_TRIES, front.size())));
        rest.addAll(front.subList(out.size(), front.size()));
        shuffle(rest, random);
        for (int i = 0; i < rest.size() && out.size() < TRIES; i++) {
            out.add(rest.get(i));
        }
        return out;
    }

    private static <T> void shuffle(List<T> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    /** True when an item this far from the player's centre, on both axes, would be collected without a step. */
    public static boolean inReach(double dx, double dz) {
        return Math.abs(dx) < REACH && Math.abs(dz) < REACH;
    }

    /** A block a candy never lands or pops on, by its material name. */
    public static boolean hazard(String materialName) {
        return materialName != null && HAZARDS.contains(materialName.toUpperCase(Locale.ROOT));
    }

    /**
     * The height band: one step down, or up to 2 up (her pickup box reaches about 2.3 above her feet), so no
     * candy lands in a 2-deep hole a child can't jump out of. {@code feetBlockY} is {@code floor(feetY)}.
     */
    public static boolean inBand(int landingY, int feetBlockY) {
        int rise = landingY - feetBlockY;
        return rise >= -1 && rise <= 2;
    }

    /**
     * A column from the sky, read in a loaded chunk only.
     *
     * @param top                   {@code getHighestBlockYAt(x, z, MOTION_BLOCKING)}; the candy lands on {@code top + 1}
     * @param topSolid              the top block is solid; {@code topLiquid} it is water or lava
     * @param topName               the top block's material name (for the hazard list)
     * @param landingAir            the landing block is air
     * @param landingReplaceable    it is {@code Tag.REPLACEABLE} (grass, a fern); {@code landingLiquidOrFire} it is
     *                              liquid or fire, which is never allowed
     * @param lavaBeside            lava in any of the 4 blocks beside the landing block
     * @param inSight               a ray from the eye to the landing block's centre hits nothing before it
     */
    public record Column(int top, boolean topSolid, boolean topLiquid, String topName, boolean landingAir,
                         boolean landingReplaceable, boolean landingLiquidOrFire, boolean lavaBeside,
                         boolean inSight) {
    }

    /**
     * Whether a candy may land on the column: in the height band, at or above the world's floor, on a solid
     * top that is neither liquid nor a hazard, into air (or, in the second round when all 8 tries failed, a
     * replaceable block that isn't liquid or fire), with no lava beside, and in sight.
     */
    public static boolean columnOk(Column c, int feetBlockY, int minHeight, boolean replaceableRound) {
        if (c.top() < minHeight || !inBand(c.top() + 1, feetBlockY)) {
            return false;
        }
        if (!c.topSolid() || c.topLiquid() || hazard(c.topName())) {
            return false;
        }
        boolean room = c.landingAir()
                || (replaceableRound && c.landingReplaceable() && !c.landingLiquidOrFire());
        return room && !c.lavaBeside() && c.inSight();
    }

    /** Where a falling candy starts: {@value #FALL_HEIGHT} above its landing block, under the build limit. */
    public static int spawnY(int top, int maxHeight) {
        return Math.max(top + 1, Math.min(top + 1 + FALL_HEIGHT, maxHeight - 2));
    }

    /**
     * A floor for pass B, under a roof or a tree, at {@code floor(feetY) - 1 + d} for {@code d} in
     * {@link #POP_STEPS}.
     *
     * @param solid             the floor block is solid; {@code name} its material name (for the hazard list)
     * @param lowerPassable     the block on the floor is passable; {@code lowerLiquidOrFire} it is water, lava or fire
     * @param upperPassable     the block above that; {@code upperLiquidOrFire} likewise
     * @param lavaBeside        lava beside the block on the floor
     * @param inSight           a ray from the eye to the block on the floor hits nothing before it
     */
    public record Floor(boolean solid, String name, boolean lowerPassable, boolean lowerLiquidOrFire,
                        boolean upperPassable, boolean upperLiquidOrFire, boolean lavaBeside, boolean inSight) {
    }

    /** Whether a candy may pop up from this floor: solid and safe, two clear blocks above it, no lava, in sight. */
    public static boolean popOk(Floor f) {
        return f.solid() && !hazard(f.name())
                && f.lowerPassable() && !f.lowerLiquidOrFire()
                && f.upperPassable() && !f.upperLiquidOrFire()
                && !f.lavaBeside() && f.inSight();
    }

    /** Where a pop starts: {@value #POP_HEIGHT} above the floor block's top. */
    public static double popY(int floorY) {
        return floorY + 1 + POP_HEIGHT;
    }

    /**
     * Whether an apple neither pass could place goes back in the queue ({@value #REQUEUE_TICKS} ticks later)
     * after being requeued {@code requeued} times already: up to {@value #MAX_REQUEUES} times, then it is
     * dropped silently (nothing spawns and nothing counts).
     */
    public static boolean requeue(int requeued) {
        return requeued < MAX_REQUEUES;
    }

    // ---- what it gives (D14-D16) -----------------------------------------------------------

    /**
     * The apple of a shower that is golden while the player has never picked up a golden one: the 3rd, or the
     * last of a shorter shower.
     */
    public static int goldenIndex(int apples) {
        return Math.max(0, Math.min(GOLDEN_INDEX, apples - 1));
    }

    /**
     * Whether an apple is golden: the {@link #goldenIndex} apple while no golden has been picked up, else a
     * roll under {@code percent} in 100. {@code roll} is in {@code [0, 1)}.
     */
    public static boolean golden(double roll, int percent, boolean thirdApple, boolean goldenSeen) {
        if (thirdApple && !goldenSeen) {
            return true;
        }
        return roll < percent / 100.0;
    }

    /** {@link #golden(double, int, boolean, boolean)} with the roll drawn from {@code random}. */
    public static boolean golden(Random random, int percent, boolean thirdApple, boolean goldenSeen) {
        return golden(random.nextDouble(), percent, thirdApple, goldenSeen);
    }

    /** Candy pays tokens only in Survival or Adventure: Creative gets the shower, the count and the bag. */
    public static boolean paysTokens(GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    /** Tokens already paid for candy today: the {@code EVENT_CANDY} lines of the ledger since local midnight. */
    public static long paidToday(List<TokenDao.SourceTotal> totals) {
        long paid = 0;
        if (totals != null) {
            for (TokenDao.SourceTotal t : totals) {
                if (TokenService.Source.EVENT_CANDY.name().equals(t.source())) {
                    paid += t.earned();
                }
            }
        }
        return paid;
    }

    /**
     * The tokens a golden pickup pays: {@code perGolden}, cut to what is left of the day's {@code cap}; 0 out
     * of the window or outside Survival and Adventure. At 0 the "GOLDEN!" moment still plays, with no line
     * about tokens.
     */
    public static int tokensToPay(long paidToday, int perGolden, int cap, boolean open, boolean paysTokens) {
        if (!open || !paysTokens || perGolden <= 0) {
            return 0;
        }
        long left = cap - Math.max(0, paidToday);
        return (int) Math.max(0, Math.min(perGolden, left));
    }

    /** How many a pickup counts: only shower candy ({@code CANDY_DROP}) picked up by its own owner. */
    public static int counts(boolean tagged, boolean ownedByPicker, int got) {
        return tagged && ownedByPicker && got > 0 ? got : 0;
    }

    /**
     * Whether the sweep counts a candy its owner is standing on but can't pick up: their own, within
     * {@value #TOUCH_DISTANCE} of their feet, and no room for it. With room, vanilla picks it up.
     */
    public static boolean touchCounts(boolean owned, double distance, boolean hasRoom) {
        return owned && distance <= TOUCH_DISTANCE && !hasRoom;
    }

    /** Whether the count crosses the goal with this pickup ({@code before < goal <= after}). */
    public static boolean crossed(long before, long after, int goal) {
        return before < goal && after >= goal;
    }

    /** Whether the count passes 10, 20, 30 ... on the way to the goal (a sound for a child who can't read yet). */
    public static boolean milestone(long before, long after, int goal) {
        for (long m = (before / 10 + 1) * 10; m <= after; m += 10) {
            if (m < goal) {
                return true;
            }
        }
        return false;
    }

    /** The bar's fill, never above 1. */
    public static float progress(long found, int goal) {
        if (goal <= 0) {
            return 1f;
        }
        return (float) Math.max(0, Math.min(1.0, (double) found / goal));
    }

    /** The window's length in days, both ends included (for the config's long-window warning). */
    public static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
