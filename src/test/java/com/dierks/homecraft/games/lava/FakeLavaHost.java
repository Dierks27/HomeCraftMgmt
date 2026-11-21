package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The server as the playroom sees it, for the tests: a movable clock, one fake world, fake players who stand where
 * the test (or a teleport) puts them, and a record of everything the room did to them.
 */
final class FakeLavaHost implements LavaHost {

    static final String WORLD = "games";

    /** One player. */
    static final class P {
        final UUID id;
        final String name;
        String world = WORLD;
        double x;
        double y = 64;
        double z;
        boolean online = true;
        boolean session;
        boolean inLava;
        Kit kit;
        String bar;
        float barProgress;
        final List<String> said = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<Cue> sounds = new ArrayList<>();
        final List<LavaSite.Spot> teleportedTo = new ArrayList<>();
        int calmed;
        int movedToSafety;
        int sessionsEnded;
        int endScreens;

        P(String name) {
            this.id = UUID.nameUUIDFromBytes(name.getBytes());
            this.name = name;
        }

        void at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        String heard() {
            return String.join("\n", said);
        }
    }

    long now = 1_790_000_000_000L;
    String claim;
    boolean claimFails;
    List<String> regionProblems = new ArrayList<>();
    LavaSettings settings = LavaSettings.defaults();
    final Map<String, WorldPort> worlds = new HashMap<>();
    final Map<UUID, P> players = new LinkedHashMap<>();
    double mspt = 20;
    final List<LavaResult> scored = new ArrayList<>();
    final Map<String, Long> bests = new HashMap<>();
    final Map<Integer, double[]> stars = new LinkedHashMap<>();
    final List<Fx> fx = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();
    /** What ending a session does in the real game: it tells the room the player left. */
    Consumer<UUID> onSessionEnd = id -> {
    };

    FakeLavaHost(FakeWorldPort world) {
        worlds.put(WORLD, world);
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    P player(String name) {
        P p = new P(name);
        players.put(p.id, p);
        return p;
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    public long nanoTime() {
        return 0L; // the build's time budget is never used up in a test
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public LavaSettings settings() {
        return settings;
    }

    @Override
    public String worldName() {
        return WORLD;
    }

    @Override
    public WorldPort world(String name) {
        return worlds.get(name);
    }

    @Override
    public String claim() throws Exception {
        if (claimFails) {
            throw new java.sql.SQLException("the database is busy");
        }
        return claim;
    }

    @Override
    public void claim(String value) throws Exception {
        if (claimFails) {
            throw new java.sql.SQLException("the database is busy");
        }
        claim = value;
    }

    @Override
    public List<String> regionProblems(Box box, String world) {
        return regionProblems;
    }

    @Override
    public List<Person> people() {
        List<Person> out = new ArrayList<>();
        for (P p : players.values()) {
            if (p.online) {
                out.add(new Person(p.id, p.name, p.world, p.x, p.y, p.z, p.session ? LavaService.REF : null,
                        p.session ? LavaService.REF : null));
            }
        }
        return out;
    }

    @Override
    public boolean anyoneOnline() {
        return players.values().stream().anyMatch(p -> p.online);
    }

    @Override
    public double mspt() {
        return mspt;
    }

    @Override
    public double[] feet(UUID player) {
        P p = players.get(player);
        return p == null || !p.online ? null : new double[]{p.x, p.y, p.z};
    }

    @Override
    public boolean inLava(UUID player) {
        P p = players.get(player);
        return p != null && p.inLava;
    }

    @Override
    public boolean teleport(UUID player, String world, LavaSite.Spot spot) {
        P p = players.get(player);
        if (p == null || !p.online || spot == null) {
            return false;
        }
        p.world = world;
        p.at(spot.x(), spot.y(), spot.z());
        p.inLava = false;
        p.teleportedTo.add(spot);
        return true;
    }

    @Override
    public void toSafety(UUID player, String world) {
        P p = players.get(player);
        if (p != null) {
            p.at(0.5, 64, 0.5);
            p.movedToSafety++;
        }
    }

    @Override
    public void endSession(UUID player) {
        P p = players.get(player);
        if (p != null) {
            p.sessionsEnded++;
            p.session = false;
            p.at(0.5, 64, 0.5);
        }
        onSessionEnd.accept(player);
    }

    @Override
    public void calm(UUID player) {
        P p = players.get(player);
        if (p != null) {
            p.calmed++;
        }
    }

    @Override
    public void kit(UUID player, Kit kit) {
        P p = players.get(player);
        if (p != null) {
            p.kit = kit;
        }
    }

    @Override
    public void tell(UUID player, String line) {
        P p = players.get(player);
        if (p != null) {
            p.said.add(line);
        }
    }

    @Override
    public void actionBar(UUID player, String line) {
        P p = players.get(player);
        if (p != null) {
            p.said.add("[bar] " + line);
        }
    }

    @Override
    public void title(UUID player, String big, String small, int stayTicks) {
        P p = players.get(player);
        if (p != null) {
            p.titles.add(big);
        }
    }

    @Override
    public void sound(UUID player, Cue cue, int step) {
        P p = players.get(player);
        if (p != null) {
            p.sounds.add(cue);
        }
    }

    @Override
    public void fx(String world, Fx fx, double x, double y, double z) {
        this.fx.add(fx);
    }

    @Override
    public void bar(UUID player, String title, float progress, boolean red) {
        P p = players.get(player);
        if (p != null) {
            p.bar = title;
            p.barProgress = progress;
        }
    }

    @Override
    public void hideBar(UUID player) {
        P p = players.get(player);
        if (p != null) {
            p.bar = null;
        }
    }

    @Override
    public void showStar(String world, int id, double x, double y, double z) {
        stars.put(id, new double[]{x, y, z});
    }

    @Override
    public void hideStar(int id) {
        stars.remove(id);
    }

    @Override
    public void hideStars() {
        stars.clear();
    }

    @Override
    public String name(UUID player) {
        P p = players.get(player);
        return p == null ? null : p.name;
    }

    @Override
    public Long best(UUID player, LavaMode mode) {
        return bests.get(player + ":" + mode.board());
    }

    @Override
    public void scored(LavaResult result) {
        scored.add(result);
        for (LavaScores.Entry e : LavaScores.entries(result)) {
            bests.merge(e.player() + ":" + e.board(), e.score(), Math::max);
        }
    }

    @Override
    public void endScreen(UUID player, LavaResult result) {
        P p = players.get(player);
        if (p != null) {
            p.endScreens++;
        }
    }
}
