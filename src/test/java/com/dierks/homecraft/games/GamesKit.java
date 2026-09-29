package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import com.dierks.homecraft.util.GameClock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What the framework tests share: a {@link GamesHost} over an in-memory SQLite with a clock that
 * can be moved, fake players and worlds (interfaces answered by a proxy — the tests need a
 * handful of their methods, never a server), and small games built from test specs.
 *
 * <p>Nothing here is mocked beyond the server: the gate, the rounds, the rewards, Take a break and
 * the invites under test are the plugin's own classes over a real database.
 */
final class GamesKit {

    /** Where the players live: a zone with DST, so local midnight isn't UTC's. */
    static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private GamesKit() {
    }

    /** Epoch millis of a local date-time in {@link #ZONE}. */
    static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    // ---- the host -----------------------------------------------------------------------------

    /** A movable clock (a zone view of it moves with it). */
    static final class MovableClock extends Clock {
        long now;

        MovableClock(long now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MovableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return self.withZone(other);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    /** A scheduled task the test runs by hand. */
    static final class Task {
        final long ticks;
        final Runnable work;
        boolean cancelled;

        Task(long ticks, Runnable work) {
            this.ticks = ticks;
            this.work = work;
        }
    }

    /** The server, as far as the framework can tell. */
    static final class Host implements GamesHost {
        final Connection connection;
        final Database db;
        final GamesDao dao;
        final TokenDao tokens;
        final MovableClock time;
        final GameClock clock;
        final Logger logger = Logger.getAnonymousLogger();
        final List<LogRecord> logs = new ArrayList<>();
        final Set<String> economyWorlds = new HashSet<>(Set.of("world"));
        final Map<UUID, Player> online = new LinkedHashMap<>();
        final List<Task> tasks = new ArrayList<>();
        GamesConfig.Parsed config;
        boolean stopping;
        boolean canSchedule = true;

        Host(long now) {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                db = Database.open(connection, Logger.getAnonymousLogger());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            dao = new GamesDao(db);
            tokens = new TokenDao(db);
            time = new MovableClock(now);
            clock = new GameClock(ZONE, time);
            config = GamesKit.config(common(true, 100, 600, 6));
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logs.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        void move(long millis) {
            time.now += millis;
        }

        void give(UUID player, int n) {
            try {
                tokens.change(player, n, "ADMIN", "seed", time.now);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        int balanceOf(UUID player) {
            try {
                return tokens.get(player).tokens();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Run every task that is due (all of them: the tests don't count ticks). */
        void runTasks() {
            List<Task> due = new ArrayList<>(tasks);
            tasks.clear();
            for (Task t : due) {
                if (!t.cancelled) {
                    t.work.run();
                }
            }
        }

        long severe() {
            return logs.stream().filter(r -> r.getLevel() == java.util.logging.Level.SEVERE).count();
        }

        @Override
        public HomeCraftManagement plugin() {
            return null;
        }

        @Override
        public GamesDao dao() {
            return dao;
        }

        @Override
        public GameClock clock() {
            return clock;
        }

        @Override
        public GamesConfig.Parsed config() {
            return config;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public boolean economyWorld(World world) {
            return world != null && economyWorlds.contains(world.getName());
        }

        @Override
        public int balance(UUID player) {
            return balanceOf(player);
        }

        @Override
        public Player online(UUID player) {
            return online.get(player);
        }

        @Override
        public Collection<? extends Player> online() {
            return online.values();
        }

        @Override
        public boolean stopping() {
            return stopping;
        }

        @Override
        public Runnable later(long ticks, Runnable task) {
            if (!canSchedule) {
                return null;
            }
            Task t = new Task(ticks, task);
            tasks.add(t);
            return () -> t.cancelled = true;
        }

        @Override
        public Runnable every(long delay, long period, Runnable task) {
            return later(delay, task);
        }
    }

    /**
     * The common keys with the ones the tests turn. No restart times: a test's clock never lands in
     * a restart hold unless it sets one ({@link GamesConfig.Common#withRestarts}).
     */
    static GamesConfig.Common common(boolean enabled, int chanceDailyTokens, int cooldownMs, int skillDailyCap) {
        GamesConfig.Common d = GamesConfig.Common.defaults();
        return new GamesConfig.Common(enabled, List.of("games"), List.of("hub"), cooldownMs, chanceDailyTokens,
                d.maxPayout(), skillDailyCap, d.featured(), d.featuredBonus(), List.of(), d.restartHoldMinutes(),
                d.breakDailyChoices(), d.breakPauseDays(), d.breakRaiseDelayDays());
    }

    /** The same common keys with {@code featured} set. */
    static GamesConfig.Common featured(GamesConfig.Common c, String featured) {
        return new GamesConfig.Common(c.enabled(), c.worlds(), c.playWorlds(), c.clickCooldownMs(),
                c.chanceDailyTokens(), c.maxPayout(), c.skillDailyCap(), featured, c.featuredBonus(),
                c.restartTimes(), c.restartHoldMinutes(), c.breakDailyChoices(), c.breakPauseDays(),
                c.breakRaiseDelayDays());
    }

    static GamesConfig.Parsed config(GamesConfig.Common common, Object... idThenSettings) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (int i = 0; i + 1 < idThenSettings.length; i += 2) {
            settings.put((String) idThenSettings[i], idThenSettings[i + 1]);
        }
        return new GamesConfig.Parsed(common, settings, Set.of());
    }

    // ---- players and worlds -------------------------------------------------------------------

    static World world(String name) {
        return (World) Proxy.newProxyInstance(GamesKit.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "World(" + name + ")";
                    default -> zero(m.getReturnType());
                });
    }

    /** A player the test can steer: world, game mode, permissions, online; it records what it was told. */
    static final class Fake implements InvocationHandler {
        private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

        final UUID id = UUID.randomUUID();
        final String name;
        final Set<String> denied = new HashSet<>();
        final List<String> said = new ArrayList<>();
        World world = world("world");
        GameMode mode = GameMode.SURVIVAL;
        boolean online = true;
        final Player player;

        Fake(String name) {
            this.name = name;
            this.player = (Player) Proxy.newProxyInstance(GamesKit.class.getClassLoader(),
                    new Class<?>[]{Player.class}, this);
        }

        /** Everything said to the player, colour codes stripped. */
        String heard() {
            return String.join("\n", said);
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            switch (m.getName()) {
                case "getUniqueId":
                    return id;
                case "getName":
                    return name;
                case "getWorld":
                    return world;
                case "getGameMode":
                    return mode;
                case "isOnline":
                    return online;
                case "hasPermission":
                    return a.length == 1 && a[0] instanceof String node ? !denied.contains(node) : true;
                case "sendMessage":
                    if (a != null && a.length == 1 && a[0] instanceof Component c) {
                        said.add(LEGACY.serialize(c).replaceAll("&[0-9a-fk-or]", ""));
                    } else if (a != null && a.length == 1 && a[0] instanceof String s) {
                        said.add(s);
                    }
                    return null;
                case "equals":
                    return proxy == a[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Player(" + name + ")";
                default:
                    return zero(m.getReturnType());
            }
        }
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ---- games --------------------------------------------------------------------------------

    /** A game of chance's settings: the components the framework reads by name. */
    record ChanceSettings(boolean enabled, List<Integer> stakes, int dailyLimit) {
    }

    /** A skill game's settings. */
    record SkillSettings(boolean enabled, int dailyCap) {
    }

    /** A game that does what the test says and counts what happened to it. */
    static final class TestGame implements Game {
        final String id;
        final GameKind kind;
        final String name;
        final TokenService.Source source;
        boolean open = true;
        boolean throwOnOpen;
        List<Game.Playable> playables = new ArrayList<>();
        List<String> aliases = List.of();
        int opened;
        int played;
        int started;
        int stopped;
        int joins;
        int quits;
        int coursesChanged;
        boolean throwOnCoursesChanged;

        TestGame(String id, GameKind kind, String name, TokenService.Source source) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.source = source;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public GameKind kind() {
            return kind;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TokenService.Source source() {
            return source;
        }

        @Override
        public boolean configEnabled() {
            return open;
        }

        @Override
        public List<String> rules() {
            return List.of("Play.");
        }

        @Override
        public ItemStack tile(Player viewer) {
            return null;
        }

        @Override
        public void open(Player player, Runnable back) {
            if (throwOnOpen) {
                throw new IllegalStateException("broken on purpose");
            }
            opened++;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }

        @Override
        public Collection<Game.Playable> playables() {
            return playables;
        }

        @Override
        public boolean play(Player player, String playableId, Runnable back) {
            played++;
            return true;
        }

        @Override
        public void start() {
            started++;
        }

        @Override
        public void stop() {
            stopped++;
        }

        @Override
        public void onJoin(Player player) {
            joins++;
        }

        @Override
        public void onQuit(Player player) {
            quits++;
        }

        @Override
        public void coursesChanged() {
            if (throwOnCoursesChanged) {
                throw new IllegalStateException("broken on purpose");
            }
            coursesChanged++;
        }

        @Override
        public List<String> oddsLines() {
            return kind.chance() ? List.of("&6" + name + " &7- gives back about 90 of every 100 tokens",
                    "&8stake 5: 90.0%") : List.of();
        }
    }

    /** A spec that builds {@code game} (the same object every time it is asked). */
    static <S> GameSpec<S> spec(TestGame game, S defaults, ExitSettler settler) {
        return spec(game.id, game.kind, defaults, ctx -> game, settler);
    }

    static <S> GameSpec<S> spec(String id, GameKind kind, S defaults, Function<GameContext, Game> create,
                                ExitSettler settler) {
        return new GameSpec<>(id, kind, List.of("enabled"), defaults, (n, d) -> d, create, settler);
    }

    /** A game of chance ({@code test_slots}, ARCADE_SLOTS: its debits count as tokens put in). */
    static TestGame chance() {
        return new TestGame("test_slots", GameKind.CHANCE, "Test Slots", TokenService.Source.ARCADE_SLOTS);
    }

    /** A skill game. */
    static TestGame skill(String id, String name) {
        return new TestGame(id, GameKind.CABINET, name, TokenService.Source.GAMES_SNAKE);
    }

    /** The framework over {@code host} with these specs, and its own Take a break. */
    static GamesService service(Host host, List<GameSpec<?>> specs) {
        return new GamesService(host, new Breaks(host), specs);
    }
}
