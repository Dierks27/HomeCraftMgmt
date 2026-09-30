package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.admin.GenAdmin;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.games.world.SessionBench;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fresh Courses' real engine ({@link GenService}) on the cross-feature journeys' framework bench: over
 * the {@link GamesBench}'s own database and clock (so the engine's flips are the rows Time Trials and
 * the Weekly Cup read), a map-backed world and the deterministic planners of {@link GenKit}, installed
 * as the framework's gate ({@code games.generated(engine)}), telling Time Trials when its courses
 * changed ({@code games.coursesChanged}), and seeing the players the journey's world sessions hold
 * ({@link #people}). The admin's command is the real {@link GenAdmin}.
 */
public final class FreshBench {

    private final GamesBench bench;
    private final GenKit.Host host;
    private final GenKit.FakePlanner parkour = new GenKit.FakePlanner(Slots.PARKOUR);
    private final GenService engine;
    private final GenAdmin admin;
    private final Logger log = Logger.getAnonymousLogger();

    /**
     * The engine with Fresh Courses on at the shipped weekly cadence, {@code on} slots switched on,
     * started and past its boot check (drive it to build the week's sets).
     *
     * @param people who is online (the journey's world sessions: {@link #people(SessionBench, Collection)})
     * @param tester what {@code gen test} starts (Time Trials' test run, mirrored)
     */
    public FreshBench(GamesBench bench, Supplier<List<Person>> people, GenAdmin.Tester tester, String... on) {
        this(bench, people, tester, null, on);
    }

    /**
     * The same, the golf slots planned by {@code golf} ({@code null}: {@link GenKit}'s deterministic
     * one) — an Adventure Golf course with a pond, say, for a golf journey.
     */
    public FreshBench(GamesBench bench, Supplier<List<Person>> people, GenAdmin.Tester tester, Planner golf,
                      String... on) {
        this.bench = bench;
        log.setUseParentHandlers(false);
        host = new GenKit.Host(bench.db(), bench::now, on);
        host.settings = GenKit.weekly(on);
        host.peopleSource = people;
        host.onCoursesChanged = id -> games().coursesChanged(id);
        Map<String, Planner> planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, parkour);
        planners.put(Slots.RINGS, new GenKit.FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, golf != null ? golf : new GenKit.FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new GenKit.FakePlanner(Slots.BOAT));
        planners.put(Slots.DROPPER, new GenKit.FakePlanner(Slots.DROPPER));
        engine = new GenService(host, planners);
        engine.start();
        engine.worldsReady();
        games().generated(engine);
        admin = new GenAdmin(() -> engine, log, tester);
    }

    private GamesService games() {
        return bench.games();
    }

    public GenService engine() {
        return engine;
    }

    public GenAdmin admin() {
        return admin;
    }

    /** Twenty ticks and a check per second, 50 ms a tick on the bench's clock. */
    public void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                engine.tick();
                bench.move(50);
            }
            engine.check();
        }
    }

    /** Seconds of driving until {@code done}, at most {@code max}. Whether it came. */
    public boolean driveUntil(BooleanSupplier done, int max) {
        for (int s = 0; s < max && !done.getAsBoolean(); s++) {
            drive(1);
        }
        return done.getAsBoolean();
    }

    public GenTag liveTag(String slot) {
        return engine.liveTag(slot);
    }

    /** The Games world the engine builds in (a map of blocks), as its ports see it. */
    public WorldPort world() {
        return host.world();
    }

    /** Blocks the fake world holds inside {@code box}. */
    public long blocks(Box box) {
        return host.world().count(box);
    }

    /** Blocks the engine wrote to the world so far. */
    public long writes() {
        return host.world().writes;
    }

    /** A slot's half ({@code 'A'} or {@code 'B'}). */
    public static Box half(String slot, char half) {
        return Slots.any(slot).half(half);
    }

    /** SEVERE lines the engine logged. */
    public long severe() {
        return host.logs.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
    }

    /** What the engine logged at WARNING or worse, for a failure message. */
    public String logged() {
        StringBuilder out = new StringBuilder();
        host.logs.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .forEach(r -> out.append(r.getLevel()).append(' ').append(r.getMessage()).append('\n'));
        return out.toString();
    }

    /** Who ended up asked to leave (the engine's {@code endRun}). */
    public List<UUID> ended() {
        return host.ended;
    }

    /** A meta key's value (the engine's store). */
    public String meta(String key) throws java.sql.SQLException {
        return host.store.meta(key);
    }

    /**
     * Everyone the rail has in the Games world, with the game and course their session is on: who
     * the engine waits for before it clears a half.
     */
    public static List<Person> people(SessionBench rail, Collection<UUID> ids) {
        List<Person> out = new ArrayList<>();
        for (UUID id : ids) {
            SessionBench.Spot at = rail.place(id);
            if (!GenKit.WORLD.equals(at.world())) {
                continue;
            }
            Session s = rail.session(id);
            String name = rail.player(id) == null ? "someone" : rail.player(id).getName();
            out.add(new Person(id, name, at.world(), at.x(), at.y(), at.z(), s == null ? null : s.gameId(),
                    s == null ? null : s.ref()));
        }
        return out;
    }
}
