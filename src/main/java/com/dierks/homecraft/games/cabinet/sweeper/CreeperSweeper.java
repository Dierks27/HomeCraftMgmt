package com.dierks.homecraft.games.cabinet.sweeper;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.cabinet.sweeper.CreeperSweeperMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creeper Sweeper (spec §10b, R1.22, R2.13): Minesweeper on a 9 by 5 board.
 *
 * <p>Dig every safe square without digging a creeper. Easy, normal and hard are each their own
 * Classic board, scored by clear time (lower is better); the daily board uses the normal creeper
 * count, starts with a safe opening already dug, and its goal is simply to clear it. The rules
 * live in {@link SweeperEngine}; this class deals boards, records and pays what a finished board
 * earned (through {@link CabinetGame}), and answers the screen's questions about the player.
 *
 * <p>Only a CLEARED board is a finish: a Boom has no time to record, so it records and pays
 * nothing — on the scored daily try it still uses the try up, as closing the screen would.
 */
public final class CreeperSweeper extends CabinetGame {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<CreeperSweeperSettings> SPEC = new GameSpec<>("creeper_sweeper", GameKind.CABINET,
            CreeperSweeperSettings.KEYS, CreeperSweeperSettings.defaults(), CreeperSweeperSettings::parse,
            CreeperSweeper::new, null);

    /** The board the website publishes. */
    public static final String FEED_BOARD = CreeperSweeperSettings.DAILY_LEVEL;

    public CreeperSweeper(GameContext ctx) {
        super(ctx);
    }

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return "Creeper Sweeper";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SWEEPER;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Dig every safe square. Don't dig a creeper!",
                "A number says how many creepers are next to it.",
                "Switch to Flag to mark where a creeper hides.",
                "Your first dig is always safe.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer, FEED_BOARD);
        String fact = best != null ? "your best " + SweeperEngine.clock(best) : "can you clear it?";
        List<String> lore = new ArrayList<>();
        for (String line : rules().subList(0, Math.min(3, rules().size()))) {
            lore.add("&7" + line);
        }
        lore.add(dailyLine(viewer));
        lore.add("&eClick to play");
        return Menus.icon(Material.CREEPER_HEAD, "&b" + name() + " &7- " + fact, lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        new CreeperSweeperMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), FEED_BOARD, true);
        out.cabinet(id(), name(), FEED_BOARD, "ms", true, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    /** Each level keeps its own all-time board (easy, normal, hard): a leaderboard display may show any. */
    @Override
    public List<String> boards() {
        return CreeperSweeperSettings.LEVELS;
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public CreeperSweeperSettings settings() {
        return ctx.games().settings(SPEC);
    }

    // ---- runs ---------------------------------------------------------------------------------

    /**
     * How a run was started: a Classic difficulty, or today's daily board.
     *
     * @param level the difficulty whose creeper count the board uses
     * @param daily the daily start, or {@code null} for Classic
     */
    public record Run(String level, DailyStart daily) {

        public boolean isDaily() {
            return daily != null;
        }

        /** A daily board played again: nothing is recorded. */
        public boolean practice() {
            return daily != null && !daily.scored();
        }

        /** The high-score board this run belongs to. */
        public String board() {
            return daily != null ? Scores.daily(daily.day()) : level;
        }
    }

    /** A Classic run on {@code level}. */
    public Run classic(String level) {
        return new Run(level, null);
    }

    /**
     * Deal today's board to the player (their first deal today is the scored try) and say which it
     * is; {@code null} when a restart minutes away holds the scored try (told, nothing dealt).
     */
    public Run daily(Player player) {
        DailyStart start = startDaily(player);
        if (start == null) {
            return null;
        }
        if (start.scored()) {
            player.sendMessage(Text.of("&bToday's board &7- your scored try. Closing the game uses it up."));
        } else if (dailyTried(player)) {
            player.sendMessage(Text.of("&7Today's board again: practice, so nothing is recorded."));
        } // else: it can't count where they are, and startDaily said so
        return new Run(CreeperSweeperSettings.DAILY_LEVEL, start);
    }

    /** A fresh board for {@code run}, with the creeper count read now (a daily board's clock starts now). */
    public SweeperEngine deal(Run run) {
        CreeperSweeperSettings s = settings();
        if (run.isDaily()) {
            return SweeperEngine.daily(s.minesFor(CreeperSweeperSettings.DAILY_LEVEL), run.daily().seed(),
                    ctx.plugin().clock().nowMillis());
        }
        return SweeperEngine.classic(s.minesFor(run.level()), ThreadLocalRandom.current().nextLong());
    }

    /** A board just ended: say how it went, and record and pay a clear. */
    public void finish(Player player, Run run, SweeperEngine board) {
        if (board.state() == SweeperEngine.State.LOST) {
            finishedUnscored(player, run.practice()); // the board ran to its end: a finish (E4)
            player.sendMessage(Text.of("&cBoom! &7That square hid a creeper."));
            if (run.isDaily() && !run.practice()) {
                player.sendMessage(Text.of("&7That was today's scored try. You can still play the board for practice."));
            }
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.3f);
            } catch (RuntimeException | LinkageError ignored) {
                // a cosmetic sound never breaks a finish
            }
            return;
        }
        if (board.state() != SweeperEngine.State.WON) {
            return;
        }
        long ms = board.timeMs(0);
        player.sendMessage(Text.of("&a✔ Cleared in &f" + SweeperEngine.clock(ms) + "&a!"));
        Finish f = run.isDaily()
                ? finishDaily(player, run.daily(), ms, true, true)
                : finishClassic(player, run.level(), ms, true);
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice: nothing is recorded."));
        } else if (f.result().record()) {
            player.sendMessage(Text.of("&e★ New best - and the server record!"));
        } else if (f.result().personalBest()) {
            player.sendMessage(Text.of("&e★ New best!"));
        }
        Sounds.won(player);
    }

    // ---- what the screens show ----------------------------------------------------------------

    /** Today's local day (the daily board's). */
    public long day() {
        return today();
    }

    /** The player's best on {@code board}, or {@code null}. */
    public Long best(Player player, String board) {
        return games().scores().best(player.getUniqueId(), id(), board);
    }

    /** Whether the player has had today's scored try (the screen says "practice" before they start). */
    public boolean dailyTried(Player player) {
        return check(() -> games().dao().dailyAttempt(player.getUniqueId(), id(), today()));
    }

    /** Whether today's daily challenge has paid the player. */
    public boolean dailyDone(Player player) {
        return check(() -> games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.DAILY_CHALLENGE,
                SkillRewards.dailyRef(today())));
    }

    /** Whether milestone {@code n} (1 bronze .. 3 gold) of {@code board} has paid the player. */
    public boolean milestonePaid(Player player, String board, int n) {
        return check(() -> games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.MILESTONE,
                SkillRewards.milestoneRef(board, n)));
    }

    /** Open one of this game's high-score boards. */
    public void showScores(Player player, String board, Runnable back) {
        openScores(player, board, true, back);
    }

    /** The tile's line about today's challenge. */
    private String dailyLine(Player player) {
        if (dailyDone(player)) {
            return "&a✔ Today's challenge done";
        }
        if (dailyTried(player)) {
            return "&7Today's scored try is used - practice is free";
        }
        int reward = settings().dailyReward();
        return "&7Daily: clear today's board" + (reward > 0 ? " for &6" + reward + " token" + (reward == 1 ? "" : "s") : "");
    }

    /** A yes/no read that a database error turns into "no" (logged), so a screen always draws. */
    private boolean check(Check check) {
        try {
            return check.get();
        } catch (SQLException e) {
            ctx.plugin().getLogger().warning("Could not read " + id() + " progress: " + e.getMessage());
            return false;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean get() throws SQLException;
    }
}
