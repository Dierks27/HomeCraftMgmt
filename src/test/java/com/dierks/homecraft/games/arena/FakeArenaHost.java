package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Feet;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

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
 * The server as the arena sees it, for the tests: a movable clock, one fake world, fake players who
 * stand where the test puts them, and a record of everything the arena did to them.
 */
final class FakeArenaHost implements ArenaHost {

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
        boolean collidable = true;
        Kit kit;
        String bar;
        final List<String> said = new ArrayList<>();
        final List<String> bars = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<Boolean> collisions = new ArrayList<>();
        int teleports;
        int movedToSafety;
        int sessionsEnded;

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
    long week = 20_724L;
    Long secret = 12345L;
    String claim;
    boolean claimFails;
    List<String> regionProblems = new ArrayList<>();
    FallingFloorsSettings settings = withEnabled(FallingFloorsSettings.defaults());
    final Map<String, WorldPort> worlds = new HashMap<>();
    final Map<UUID, P> players = new LinkedHashMap<>();
    boolean holding;
    String heldFor;
    double mspt = 20;
    final List<RoundResult> scored = new ArrayList<>();
    final List<Long> scoredWeeks = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();
    /** What ending a session does in the real game: it tells the arena the player left. */
    Consumer<UUID> onSessionEnd = id -> {
    };

    FakeArenaHost(FakeWorldPort world) {
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

    static FallingFloorsSettings withEnabled(FallingFloorsSettings d) {
        return new FallingFloorsSettings(true, d.origin(), d.fadeTicks(), d.minPlayers(), d.maxPlayers(), d.solo(),
                d.roundSeconds(), d.resetBlocksPerTick(), d.dailyReward(), d.milestones(), d.milestoneRewards(),
                d.dailyCap());
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
        return 0L; // the reset's time budget is never used up in a test
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public FallingFloorsSettings settings() {
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
    public long weekKey(long now) {
        return week;
    }

    @Override
    public Long secret() {
        return secret;
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
                out.add(new Person(p.id, p.name, p.world, p.x, p.y, p.z, p.session ? ArenaService.REF : null,
                        p.session ? ArenaService.REF : null));
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
    public boolean holding() {
        return holding;
    }

    @Override
    public String heldFor() {
        return heldFor;
    }

    @Override
    public Feet feet(UUID player) {
        P p = players.get(player);
        return p == null || !p.online ? null : new Feet(p.x, p.y, p.z, 0);
    }

    @Override
    public void teleport(UUID player, String world, ArenaSite.Spot spot) {
        P p = players.get(player);
        if (p != null) {
            p.world = world;
            p.at(spot.x(), spot.y(), spot.z());
            p.teleports++;
        }
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
    public void collidable(UUID player, boolean on) {
        P p = players.get(player);
        if (p != null) {
            p.collidable = on;
            p.collisions.add(on);
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
    }

    @Override
    public void title(UUID player, String big, String small, int stayTicks) {
        P p = players.get(player);
        if (p != null) {
            p.titles.add(big);
        }
    }

    @Override
    public void sound(UUID player, Cue cue) {
    }

    @Override
    public void bar(UUID player, String title, float progress) {
        P p = players.get(player);
        if (p != null) {
            p.bar = title;
            p.bars.add(title);
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
    public String name(UUID player) {
        P p = players.get(player);
        return p == null ? null : p.name;
    }

    @Override
    public void scored(RoundResult result, long week) {
        scored.add(result);
        scoredWeeks.add(week);
    }
}
