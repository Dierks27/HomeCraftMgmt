package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.storage.TokenDao;
import com.dierks.homecraft.util.GameClock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Arcade tokens: balances, the login streak, playtime, and the ledger (§3.9).
 *
 * <p>Split out of {@link ArcadeService}, which keeps the games. Everything that moves a balance
 * comes through {@link #change}, which is one guarded database transaction: the balance update
 * refuses to go below zero, and the ledger line recording what moved and why is written with it.
 * That is what makes {@code /hcm tokens audit} a report of what happened rather than an estimate.
 *
 * <p>Days here are LOCAL days ({@link GameClock}): the streak rolls over at the players' midnight,
 * not at 7 PM Central, and it is checked on the five-minute tick as well as on join, so somebody
 * who is online across midnight gets the new day's token without having to relog.
 */
public final class TokenService {

    /**
     * Every way a balance can move. The ledger stores the constant's name; the label is what a
     * player reads in their token history.
     */
    public enum Source {
        LOGIN_STREAK("Login streak"),
        PLAYTIME("Playtime"),
        QUEST("Quest"),
        ACHIEVEMENT("Achievement"),
        CRATE("Crate"),
        LOTTO("Scratch Ticket"),
        PRIZE("Prize Counter"),
        PITY("Rare Card"),
        PACK("Card Pack"),
        TRADE_IN("Card trade-in"),
        HUNT("Wild hunt"),
        ADMIN("Admin"),
        REFUND("Refund"),
        // The Games (0.35): one source per game, so /hcm tokens audit shows each game's real flow.
        // Games of chance (tokens in and tokens back):
        ARCADE_SLOTS("Ore Slots"),
        ARCADE_TWENTY_ONE("Twenty-One"),
        ARCADE_WHEEL("The Wheel"),
        ARCADE_HILO("Higher or Lower"),
        ARCADE_COIN_FLIP("Coin Flip"),
        // Skill games (capped rewards only; they never take tokens):
        GAMES_SWEEPER("Creeper Sweeper"),
        GAMES_MERGE("Ore Merge"),
        GAMES_SNAKE("Snake"),
        GAMES_MATCH("Mini Match"),
        GAMES_SIMON("Simon Says"),
        GAMES_WHACK("Whack-a-Zombie"),
        GAMES_CONNECT("Connect Four"),
        GAMES_TICTACTOE("Tic-Tac-Toe"),
        GAMES_PARKOUR("Parkour"),
        GAMES_ELYTRA("Elytra course"),
        GAMES_BOAT("Boat race"),
        GAMES_GOLF("Mini golf");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The constant for a stored name, or null for one this build does not know. */
        public static Source of(String name) {
            try {
                return name == null ? null : valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * What a streak claim should do.
     *
     * @param pay      whether today is a new day to be paid for
     * @param streak   the streak to store (and to pay by)
     * @param rewind   a stored day to pull back to today without paying, or -1 — see
     *                 {@link #decideStreak}
     */
    public record StreakDecision(boolean pay, int streak, long rewind) {
    }

    /** How often online players are checked for a new day and for playtime milestones. */
    private static final long TICK_SECONDS = 300L;

    private final HomeCraftManagement plugin;
    private final TokenDao dao;
    private BukkitTask tickTask;

    public TokenService(HomeCraftManagement plugin, TokenDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void start() {
        stop();
        if (!plugin.config().arcade().enabled()) {
            return;
        }
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                tick(p);
            }
        }, 20L * TICK_SECONDS, 20L * TICK_SECONDS);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // ---- reading ----------------------------------------------------------------

    public int balance(UUID player) {
        try {
            return dao.get(player).tokens();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read tokens: " + e.getMessage());
            return 0;
        }
    }

    public int streak(UUID player) {
        try {
            return dao.get(player).streak();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Minutes of play until the next playtime token, or -1 if playtime rewards are off. */
    public int minutesToNextPlaytimeToken(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.playtimeEnabled() || arc.playtimeMinutesPerToken() <= 0) {
            return -1;
        }
        long minutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        int per = arc.playtimeMinutesPerToken();
        int into = (int) (minutes % per);
        return per - into;
    }

    /** The player's most recent ledger lines, newest first. */
    public List<TokenDao.LedgerRow> history(UUID player, int limit) {
        try {
            return dao.history(player, limit);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read token history: " + e.getMessage());
            return List.of();
        }
    }

    /** Earned/spent per player per source since {@code since} (epoch ms); null player = everyone. */
    public List<TokenDao.SourceTotal> totals(long since, UUID player) {
        try {
            return dao.totals(since, player);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read the token ledger: " + e.getMessage());
            return List.of();
        }
    }

    // ---- the one write path -------------------------------------------------------

    /**
     * Move a balance by {@code delta} and record why, atomically.
     *
     * @param detail what it was for, in plain words ("Catch 8 fish", "Mini Radar"); colour
     *               codes are stripped
     * @return the new balance, or -1 if a spend was refused because it would go below zero
     */
    public int change(UUID player, int delta, Source source, String detail) {
        try {
            return dao.change(player, delta, source.name(), Text.plain(detail), plugin.clock().nowMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to change tokens (" + source + " " + delta + "): " + e.getMessage());
            return TokenDao.REFUSED;
        }
    }

    /** Take {@code tokens}; false (and nothing taken) if the balance is short. */
    public boolean spend(UUID player, int tokens, Source source, String detail) {
        if (tokens <= 0) {
            return true;
        }
        return change(player, -tokens, source, detail) != TokenDao.REFUSED;
    }

    /** Give {@code tokens} with no feedback and no sandbox check (refunds, admin). */
    public int grant(UUID player, int tokens, Source source, String detail) {
        return tokens <= 0 ? balance(player) : change(player, tokens, source, detail);
    }

    /**
     * True if {@code player} can be paid tokens right now — i.e. the world sandbox (§11 #1)
     * permits earning here. Refusals are logged like every other blocked economy action.
     *
     * <p><b>Check this before committing anything irreversible.</b> {@link #award} refuses
     * <i>silently</i> outside an economy-enabled world, so a caller that marks a quest claimed or
     * unlocks a one-time achievement and only then calls {@code award} spends the progression and
     * pays nothing. Callers that commit first must gate on this instead.
     */
    public boolean canEarn(Player player, String reason) {
        if (player == null) {
            return false;
        }
        if (!plugin.sandbox().allowed(player.getWorld())) {
            plugin.sandbox().log(player, "token earn (" + reason + ")");
            return false;
        }
        return true;
    }

    /**
     * Pay an online player with "+N tokens" feedback — the general earn path.
     *
     * @return true if the tokens were actually paid; false if the world sandbox refused (or there
     *         was nothing to pay) — see {@link #canEarn}
     */
    public boolean award(Player player, int tokens, Source source, String detail) {
        if (tokens <= 0) {
            return false;
        }
        if (!canEarn(player, source.label())) {
            return false;
        }
        int after = change(player.getUniqueId(), tokens, source, detail);
        if (after == TokenDao.REFUSED) {
            return false;
        }
        feedback(player, tokens, source, after);
        return true;
    }

    /** "+N tokens" feedback: a chat line and a bright pickup sound. */
    private void feedback(Player player, int tokens, Source source, int balance) {
        player.sendMessage(Text.of("&e✦ &a+" + tokens + " token" + (tokens == 1 ? "" : "s")
                + " &7(" + source.label() + ")&7. You have &6" + balance + "&7."));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    }

    // ---- admin ------------------------------------------------------------------

    /** Admin: add tokens to a player (may be offline). Returns the new balance. */
    public int adminAdd(UUID player, int tokens) {
        if (tokens <= 0) {
            return balance(player);
        }
        int after = change(player, tokens, Source.ADMIN, "Given by an admin");
        Player online = plugin.getServer().getPlayer(player);
        if (online != null && after != TokenDao.REFUSED) {
            feedback(online, tokens, Source.ADMIN, after);
        }
        return balance(player);
    }

    /** Admin: set a player's balance to an exact value. Returns the new balance. */
    public int adminSet(UUID player, int tokens) {
        try {
            return dao.setBalance(player, Math.max(0, tokens), Source.ADMIN.name(), "Set by an admin",
                    plugin.clock().nowMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to set tokens: " + e.getMessage());
            return balance(player);
        }
    }

    /** Admin: take tokens from a player (floored at 0). Returns the new balance. */
    public int adminTake(UUID player, int tokens) {
        int take = Math.min(Math.max(0, tokens), balance(player));
        if (take > 0) {
            change(player, -take, Source.ADMIN, "Taken by an admin");
        }
        return balance(player);
    }

    // ---- earning: login streak + playtime -------------------------------------------

    /** On join: today's streak token (once per local day) and any playtime catch-up. */
    public void onJoin(Player player) {
        if (!plugin.config().arcade().enabled()) {
            return;
        }
        if (!plugin.sandbox().allowed(player.getWorld())) {
            plugin.sandbox().log(player, "login-streak / playtime tokens");
            return;
        }
        claimStreak(player);
        grantPlaytime(player);
        if (plugin.achievements() != null) {
            plugin.achievements().sweep(player);
        }
    }

    /**
     * The five-minute check for somebody already online: a new local day (so staying on across
     * midnight pays the next streak day without a relog) and any playtime milestone.
     */
    private void tick(Player player) {
        if (!plugin.config().arcade().enabled() || !plugin.sandbox().allowed(player.getWorld())) {
            return;
        }
        claimStreak(player);
        grantPlaytime(player);
        if (plugin.achievements() != null) {
            plugin.achievements().sweep(player); // statistics, balance, collection: nothing pushes those
        }
    }

    /**
     * Decide a streak claim. Pure, so the one-off switch from UTC days to local days can be
     * pinned by a test.
     *
     * <p>{@code storedDay} may be a UTC epoch day written by an older build, which runs up to a
     * day AHEAD of the local calendar in the evening. A stored day past today therefore means
     * "already claimed today": pay nothing, and pull the stored day back to today so that
     * tomorrow is exactly one day later and the streak carries on. Nothing is ever paid twice
     * for one local day, and nobody's streak breaks because the calendar moved under it.
     *
     * @param storedStreak the streak as stored
     * @param storedDay    the day last claimed (0 = never)
     * @param today        today's local epoch day
     */
    public static StreakDecision decideStreak(int storedStreak, long storedDay, long today) {
        if (storedDay > today) {
            return new StreakDecision(false, storedStreak, today);
        }
        if (storedDay == today) {
            return new StreakDecision(false, storedStreak, -1);
        }
        int streak = storedDay > 0 && storedDay == today - 1 ? Math.max(0, storedStreak) + 1 : 1;
        return new StreakDecision(true, streak, -1);
    }

    /** Pay today's streak token if today has not been paid yet. */
    private void claimStreak(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.streakEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        try {
            TokenDao.TokenState s = dao.get(id);
            long today = plugin.clock().dayKey();
            StreakDecision d = decideStreak(s.streak(), s.lastStreakDay(), today);
            if (d.rewind() >= 0) {
                dao.rewindStreakDay(id, d.rewind());
            }
            if (!d.pay()) {
                return;
            }
            int reward = arc.streakReward(d.streak());
            String detail = "Day " + d.streak();
            int after = dao.claimStreak(id, d.streak(), today, reward, Source.LOGIN_STREAK.name(), detail,
                    plugin.clock().nowMillis());
            if (after != TokenDao.REFUSED && reward > 0) {
                player.sendMessage(Text.of("&e✦ &a+" + reward + " token" + (reward == 1 ? "" : "s")
                        + " &7(day " + d.streak() + " login streak)&7. You have &6" + after + "&7."));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
            }
            if (after != TokenDao.REFUSED && plugin.achievements() != null) {
                plugin.achievements().checkStreak(player, d.streak());
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed streak grant: " + e.getMessage());
        }
    }

    /**
     * Grant any whole playtime milestones the player has newly earned. Reads the LIFETIME
     * {@code PLAY_ONE_MINUTE} statistic against how many milestones were already paid, so it
     * catches up correctly and cannot be farmed by relogging.
     */
    private void grantPlaytime(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.playtimeEnabled() || arc.playtimeMinutesPerToken() <= 0) {
            return;
        }
        try {
            long ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE); // stat is in ticks
            long minutes = ticks / 20L / 60L;
            int earned = (int) Math.min(Integer.MAX_VALUE, minutes / arc.playtimeMinutesPerToken());
            int paid = dao.claimPlaytime(player.getUniqueId(), earned, Source.PLAYTIME.name(),
                    "Time played", plugin.clock().nowMillis());
            if (paid > 0) {
                feedback(player, paid, Source.PLAYTIME, balance(player.getUniqueId()));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed playtime grant: " + e.getMessage());
        }
    }
}
