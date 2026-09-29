package com.dierks.homecraft.games.arena.rules;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Falling Floors with fake players, tick by tick and with no server: the same {@link ArenaTick}
 * the game runs, fed by simple bodies that fall like a player does and bots that decide where to
 * walk. Deterministic: the same layout, settings and bots make the same log, byte for byte.
 *
 * <p>Why it exists: the round rules are only right if a real round ends, and "every round ends",
 * "standing still falls" and "jumping in place falls" are claims about bodies, not about one
 * method. The simulation is how the tests make those claims, and how a planner or an admin can ask
 * "how long does a round on this week's shape last?" without a server.
 *
 * <p><b>The body</b> is vanilla's in the ways that matter here: a 0.6-wide footprint, gravity 0.08
 * a tick with 0.98 drag, a 0.42 jump, walking at 4.317 blocks a second, no air control. Floors are
 * at least {@link FloorLayout#MIN_GAP} apart, so a body only ever meets a floor from above. A reset
 * in the simulation passes at once (or fails, when told to: {@link #failResets}).
 */
public final class ArenaSim {

    /** Blocks a tick lost to gravity. */
    public static final double GRAVITY = 0.08;
    /** Vertical speed kept each tick. */
    public static final double DRAG = 0.98;
    /** A jump's first tick. */
    public static final double JUMP = 0.42;
    /** Walking: 4.317 blocks a second. */
    public static final double WALK = 4.317 / RoundSettings.TICKS_PER_SECOND;

    /** A fake player's mind: where to go this tick, standing on a floor. */
    @FunctionalInterface
    public interface Bot {
        Move next(Body me, FloorRules floors);
    }

    /**
     * A step: how far to walk this tick (clamped to {@link #WALK}) and whether to jump.
     */
    public record Move(double dx, double dz, boolean jump) {
        public static final Move NONE = new Move(0, 0, false);
    }

    /** A fake player's body. */
    public static final class Body {
        private final UUID id;
        private final Bot bot;
        private double x;
        private double y;
        private double z;
        private double vy;
        private boolean onGround;
        private int layer = -1;
        private boolean inPlay;

        Body(UUID id, Bot bot) {
            this.id = id;
            this.bot = bot;
        }

        public UUID id() {
            return id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public double vy() {
            return vy;
        }

        public boolean onGround() {
            return onGround;
        }

        /** The floor it stands on, or -1 in the air. */
        public int layer() {
            return layer;
        }

        /** Whether it is on the floors in a round (not in the gallery). */
        public boolean inPlay() {
            return inPlay;
        }

        /** Its feet, as the game reads a player's. */
        public Feet feet() {
            return new Feet(x, y, z, vy);
        }

        /** Put it somewhere (a test's own starting spot). */
        public void place(double px, double py, double pz, boolean ground, int onLayer) {
            x = px;
            y = py;
            z = pz;
            vy = 0;
            onGround = ground;
            layer = ground ? onLayer : -1;
        }
    }

    private final FloorLayout layout;
    private final ArenaRound round;
    private final ArenaTick driver;
    private final Map<UUID, Body> bodies = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();
    private final List<RoundResult> results = new ArrayList<>();
    private boolean hold;
    private int failResets;
    private long tick;
    private long writes;
    private int maxWrites;
    private int added;

    public ArenaSim(FloorLayout layout, RoundSettings settings) {
        this.layout = layout;
        RoundSettings s = settings == null ? RoundSettings.defaults() : settings;
        this.round = new ArenaRound(s, layout.spawns().size());
        this.driver = new ArenaTick(round, layout, s);
    }

    /** A fake player joins the gallery; its id is the n-th made here (the same every run). */
    public UUID add(Bot bot) {
        UUID id = new UUID(0xF100_0000_0000_0000L, ++added);
        Body b = new Body(id, bot);
        ArenaRound.Join j = round.join(id);
        if (j != ArenaRound.Join.JOINED) {
            throw new IllegalStateException("a fake player could not join: " + j);
        }
        bodies.put(id, b);
        return id;
    }

    /** A fake player leaves (or disconnects). */
    public void leave(UUID id) {
        round.leave(id);
        Body b = bodies.remove(id);
        if (b != null) {
            b.inPlay = false;
        }
    }

    /** Everyone presses Ready. */
    public void readyAll() {
        for (UUID id : bodies.keySet()) {
            round.ready(id, true);
        }
    }

    /** The restart hold on or off. */
    public void hold(boolean on) {
        hold = on;
    }

    /** The next {@code n} resets fail their verify. */
    public void failResets(int n) {
        failResets = Math.max(0, n);
    }

    /** One tick: the floors and the round (through the game's own driver), then every body moves. */
    public ArenaTick.Output step() {
        tick++;
        Map<UUID, Feet> feet = new LinkedHashMap<>();
        for (Body b : bodies.values()) {
            if (b.inPlay) {
                feet.put(b.id, b.feet());
            }
        }
        ArenaTick.Output out = driver.tick(feet, Set.of(), hold);
        writes += out.writes().size();
        maxWrites = Math.max(maxWrites, out.writes().size());
        if (!out.writes().isEmpty()) {
            StringBuilder sb = new StringBuilder().append(tick).append(" writes");
            for (FloorWrite w : out.writes()) {
                sb.append(' ').append(w.x()).append(',').append(w.y()).append(',').append(w.z()).append(':')
                        .append(w.state() == CellState.RED ? 'R' : 'A');
            }
            log.add(sb.toString());
        }
        for (RoundEvent e : out.events()) {
            log.add(tick + " " + e);
            handle(e);
        }
        FloorRules floors = driver.floors();
        if (round.phase() == ArenaRound.Phase.PLAYING && floors != null) {
            for (Body b : bodies.values()) {
                if (b.inPlay) {
                    move(b, floors);
                }
            }
        }
        return out;
    }

    /**
     * Ticks until a round ends (or {@code maxTicks} pass).
     *
     * @return the round's result, or {@code null} if none ended in time
     */
    public RoundResult runUntilEnd(long maxTicks) {
        int before = results.size();
        for (long i = 0; i < maxTicks; i++) {
            step();
            if (results.size() > before) {
                return results.get(results.size() - 1);
            }
        }
        return null;
    }

    /** Ticks until the arena reaches {@code phase} (or {@code maxTicks} pass); whether it did. */
    public boolean runUntil(ArenaRound.Phase phase, long maxTicks) {
        for (long i = 0; i < maxTicks; i++) {
            if (round.phase() == phase) {
                return true;
            }
            step();
        }
        return round.phase() == phase;
    }

    private void handle(RoundEvent e) {
        switch (e) {
            case RoundEvent.TeleportTo t -> {
                Body b = bodies.get(t.player());
                if (b != null) {
                    Cell c = driver.layout().spawns().get(t.spawn());
                    b.place(c.centerX(), driver.layout().topY(0), c.centerZ(), true, 0);
                    b.inPlay = true;
                }
            }
            case RoundEvent.Out o -> {
                Body b = bodies.get(o.player());
                if (b != null) {
                    b.inPlay = false; // to the gallery
                }
            }
            case RoundEvent.Ended en -> {
                for (Body b : bodies.values()) {
                    b.inPlay = false; // the reset moves strays to the gallery
                }
                results.add(en.result());
            }
            case RoundEvent.ResetNeeded r -> {
                if (failResets > 0) {
                    failResets--;
                    round.resetDone(false);
                } else {
                    round.resetDone(true);
                }
            }
            default -> {
            }
        }
    }

    /** One tick of a body: its bot's step on the ground, falling and landing like a player. */
    static void move(Body b, FloorRules floors) {
        FloorLayout layout = floors.layout();
        Move m = b.onGround ? b.bot.next(b, floors) : Move.NONE;
        if (m == null) {
            m = Move.NONE;
        }
        double dx = m.dx();
        double dz = m.dz();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > WALK) {
            dx *= WALK / len;
            dz *= WALK / len;
        }
        b.x += dx;
        b.z += dz;
        if (b.onGround && !floors.holds(b.layer, b.x, b.z)) {
            b.onGround = false; // walked off, or the floor went
            b.layer = -1;
        }
        if (b.onGround && m.jump()) {
            b.vy = JUMP;
            b.onGround = false;
            b.layer = -1;
        }
        if (b.onGround) {
            b.vy = 0;
            return;
        }
        double ny = b.y + b.vy;
        if (b.vy < 0) {
            for (int i = 0; i < layout.layerCount(); i++) {
                int top = layout.topY(i);
                if (b.y >= top - FloorRules.STAND_BELOW && ny <= top && floors.holds(i, b.x, b.z)) {
                    b.y = top;
                    b.vy = 0;
                    b.onGround = true;
                    b.layer = i;
                    return;
                }
            }
        }
        b.y = ny;
        b.vy = (b.vy - GRAVITY) * DRAG;
    }

    // ---- bots -----------------------------------------------------------------------------------

    /** Stands still. */
    public static Bot still() {
        return (me, floors) -> Move.NONE;
    }

    /** Jumps in place, again and again. */
    public static Bot hopper() {
        return (me, floors) -> new Move(0, 0, true);
    }

    /** Walks one way and never turns. */
    public static Bot walker(double dx, double dz) {
        return (me, floors) -> new Move(dx, dz, false);
    }

    /**
     * Always heads for the nearest whole (not red) cell of its floor, the way a good player keeps
     * moving onto fresh glass; waits when there is none. Ties go to the lower x, then z.
     */
    public static Bot runner() {
        return (me, floors) -> {
            int layer = me.layer();
            if (layer < 0) {
                return Move.NONE;
            }
            List<Cell> cells = floors.layout().layer(layer).cells();
            double best = Double.MAX_VALUE;
            Cell target = null;
            for (int n = 0; n < cells.size(); n++) {
                if (floors.stateAt(layer, n) != CellState.SOLID) {
                    continue;
                }
                Cell c = cells.get(n);
                double ddx = c.centerX() - me.x();
                double ddz = c.centerZ() - me.z();
                double d = ddx * ddx + ddz * ddz;
                if (d < best) {
                    best = d;
                    target = c;
                }
            }
            return target == null ? Move.NONE : new Move(target.centerX() - me.x(), target.centerZ() - me.z(), false);
        };
    }

    /**
     * Wanders: a new heading from its own seeded stream every second, sometimes a jump. The same
     * seed wanders the same way every run.
     */
    public static Bot wanderer(long seed) {
        GenRandom r = new GenRandom(seed);
        double[] heading = {0, 0};
        int[] ticks = {0};
        return (me, floors) -> {
            if (ticks[0]++ % RoundSettings.TICKS_PER_SECOND == 0) {
                double a = r.nextDouble(0, 2 * Math.PI);
                heading[0] = StrictMath.cos(a);
                heading[1] = StrictMath.sin(a);
            }
            return new Move(heading[0], heading[1], r.chance(0.05));
        };
    }

    // ---- reading it -----------------------------------------------------------------------------

    public ArenaRound round() {
        return round;
    }

    public ArenaTick driver() {
        return driver;
    }

    public Body body(UUID id) {
        return bodies.get(id);
    }

    /** Every event and write so far, one line each, prefixed with its tick. */
    public List<String> log() {
        return List.copyOf(log);
    }

    /** Every round that ended, in order. */
    public List<RoundResult> results() {
        return List.copyOf(results);
    }

    /** Ticks run. */
    public long ticks() {
        return tick;
    }

    /** Blocks written in all. */
    public long writes() {
        return writes;
    }

    /** The most blocks written in one tick. */
    public int maxWritesPerTick() {
        return maxWrites;
    }

    public FloorLayout layout() {
        return layout;
    }
}
