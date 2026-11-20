package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Everything the games framework needs from the running server, in one place: the database, the
 * clock, the live config, the economy sandbox, token balances, who is online and the scheduler.
 *
 * <p>It exists so the framework's rules can be run for real in tests. The live host
 * ({@link #live}) reads the plugin; a test gives the framework its own host over an in-memory
 * SQLite, a fixed clock and fake players, and the play gate, the rounds, the rewards, Take a break
 * and the invites then run exactly the code the server runs — with nothing mocked but the server.
 * Package-private: games never see it, they see {@link GamesService}.
 */
interface GamesHost {

    /** The plugin, or {@code null} in tests. */
    HomeCraftManagement plugin();

    GamesDao dao();

    /** Which day it is where the players live. */
    GameClock clock();

    /** The live {@code games:} config (re-read every call). */
    GamesConfig.Parsed config();

    Logger logger();

    /** Whether the economy runs in this world (the sandbox; an empty list means everywhere). */
    boolean economyWorld(World world);

    /** A player's token balance (0 if it can't be read). */
    int balance(UUID player);

    /**
     * The server's money (0.46.0, the Game Parlor's money play): whole dollars in and out of a player's balance. It is
     * not this plugin's database, so nothing here shares a transaction with a round.
     */
    interface Bank {

        /** No bank: money play is closed. */
        Bank NONE = new Bank() {
            @Override
            public boolean on() {
                return false;
            }

            @Override
            public double balance(UUID player) {
                return 0;
            }

            @Override
            public boolean take(UUID player, int dollars) {
                return false;
            }

            @Override
            public boolean give(UUID player, int dollars) {
                return false;
            }
        };

        /** Whether the server has an economy to play with. */
        boolean on();

        double balance(UUID player);

        /** Take {@code dollars} from the player (online or not). @return true only if they were taken */
        boolean take(UUID player, int dollars);

        /** Give {@code dollars} to the player (online or not). @return true only if they were given */
        boolean give(UUID player, int dollars);
    }

    /** The server's money; {@link Bank#NONE} when there is none. */
    default Bank bank() {
        return Bank.NONE;
    }

    /** Where the Game Parlor's rules are kept, or {@code null} for memory only (a test). */
    default Parlor.Store parlorStore() {
        return null;
    }

    /** The online player with this id, or {@code null}. */
    Player online(UUID player);

    /** Everyone online. */
    Collection<? extends Player> online();

    /** Whether the server is shutting down (then nothing may teleport, and OPEN rounds wait). */
    boolean stopping();

    /**
     * Run {@code task} on the main thread after {@code ticks}.
     *
     * @return what cancels it, or {@code null} when nothing can be scheduled (the plugin is
     *         disabling) — the caller then does the work at once or not at all
     */
    Runnable later(long ticks, Runnable task);

    /**
     * Run {@code task} every {@code period} ticks after {@code delay}.
     *
     * @return what cancels it, or {@code null} when nothing can be scheduled
     */
    Runnable every(long delay, long period, Runnable task);

    /** The host over the running plugin. */
    static GamesHost live(HomeCraftManagement plugin, GamesDao dao) {
        return new GamesHost() {
            @Override
            public HomeCraftManagement plugin() {
                return plugin;
            }

            @Override
            public GamesDao dao() {
                return dao;
            }

            @Override
            public GameClock clock() {
                return plugin.clock();
            }

            @Override
            public GamesConfig.Parsed config() {
                return plugin.config().games();
            }

            @Override
            public Logger logger() {
                return plugin.getLogger();
            }

            @Override
            public boolean economyWorld(World world) {
                return plugin.sandbox() != null && plugin.sandbox().allowed(world);
            }

            @Override
            public int balance(UUID player) {
                return plugin.tokens() == null ? 0 : plugin.tokens().balance(player);
            }

            @Override
            public Bank bank() {
                return new Bank() {
                    @Override
                    public boolean on() {
                        return plugin.economy() != null && plugin.economy().isEnabled();
                    }

                    @Override
                    public double balance(UUID player) {
                        return on() ? plugin.economy().balance(plugin.getServer().getOfflinePlayer(player)) : 0;
                    }

                    @Override
                    public boolean take(UUID player, int dollars) {
                        return dollars > 0 && on()
                                && plugin.economy().withdraw(plugin.getServer().getOfflinePlayer(player), dollars);
                    }

                    @Override
                    public boolean give(UUID player, int dollars) {
                        return dollars > 0 && on()
                                && plugin.economy().deposit(plugin.getServer().getOfflinePlayer(player), dollars);
                    }
                };
            }

            @Override
            public Parlor.Store parlorStore() {
                com.dierks.homecraft.storage.GenMetaDao meta =
                        new com.dierks.homecraft.storage.GenMetaDao(plugin.database());
                return new Parlor.Store() {
                    @Override
                    public String get(String key) throws java.sql.SQLException {
                        return meta.get(key);
                    }

                    @Override
                    public void set(String key, String value) throws java.sql.SQLException {
                        meta.set(key, value);
                    }
                };
            }

            @Override
            public Player online(UUID player) {
                return player == null ? null : plugin.getServer().getPlayer(player);
            }

            @Override
            public Collection<? extends Player> online() {
                return plugin.getServer().getOnlinePlayers();
            }

            @Override
            public boolean stopping() {
                return Bukkit.isStopping();
            }

            @Override
            public Runnable later(long ticks, Runnable task) {
                if (!plugin.isEnabled()) {
                    return null;
                }
                BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, task, Math.max(0, ticks));
                return t::cancel;
            }

            @Override
            public Runnable every(long delay, long period, Runnable task) {
                if (!plugin.isEnabled()) {
                    return null;
                }
                BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, task, Math.max(0, delay),
                        Math.max(1, period));
                return t::cancel;
            }
        };
    }
}
