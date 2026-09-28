package com.dierks.homecraft.games.cabinet.snake;

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
import com.dierks.homecraft.gui.games.cabinet.snake.SnakeMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Snake (spec §10b, R1.22, R2.13, R3.14): steer a snake round a 7 by 5 field with "turn left"
 * and "turn right".
 *
 * <p>Eat apples, don't hit the wall or yourself. The score is apples in one run (higher is
 * better); the snake moves every {@code tick_java} ticks ({@code tick_bedrock} for Bedrock
 * players, whose taps arrive later) and a little faster every few apples. On the daily board the
 * apples come in the day's order and the goal is {@value #DAILY_GOAL}. The rules live in
 * {@link SnakeEngine}; this class deals runs, reads the player's speed once per run (a reload
 * mid-run changes nothing), and records and pays what a run earned through {@link CabinetGame}.
 */
public final class Snake extends CabinetGame {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<SnakeSettings> SPEC = new GameSpec<>("snake", GameKind.CABINET,
            SnakeSettings.KEYS, SnakeSettings.defaults(), SnakeSettings::parse,
            Snake::new, null);

    /** Apples for the daily challenge. */
    public static final int DAILY_GOAL = 15;

    public Snake(GameContext ctx) {
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
        return "Snake";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SNAKE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Steer the snake to the apples.",
                "Don't run into the walls or yourself.",
                "The side buttons turn it left or right, the way it faces.",
                "It gets a little faster every 5 apples.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer, Scores.CLASSIC);
        String fact = best != null ? "your best " + apples(best) : "how long can it grow?";
        List<String> lore = new ArrayList<>();
        for (String line : rules().subList(0, Math.min(3, rules().size()))) {
            lore.add("&7" + line);
        }
        lore.add(dailyLine(viewer));
        lore.add("&eClick to play");
        return Menus.icon(Material.LIME_CONCRETE, "&b" + name() + " &7- " + fact, lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        new SnakeMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), Scores.CLASSIC, false);
        out.cabinet(id(), name(), Scores.CLASSIC, "apples", false, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public SnakeSettings settings() {
        return ctx.games().settings(SPEC);
    }

    // ---- runs ---------------------------------------------------------------------------------

    /**
     * How a run was started.
     *
     * @param daily the daily start, or {@code null} for Classic
     * @param tick  ticks between moves at the start, read once for the whole run
     */
    public record Run(DailyStart daily, int tick) {

        public boolean isDaily() {
            return daily != null;
        }

        /** A daily board played again: nothing is recorded. */
        public boolean practice() {
            return daily != null && !daily.scored();
        }

        /** The high-score board this run belongs to. */
        public String board() {
            return daily != null ? Scores.daily(daily.day()) : Scores.CLASSIC;
        }
    }

    /** A Classic run at the player's speed. */
    public Run classic(Player player) {
        return new Run(null, tick(player));
    }

    /** Deal today's board to the player (their first deal today is the scored try) and say which it is. */
    public Run daily(Player player) {
        DailyStart start = startDaily(player);
        if (start.scored()) {
            player.sendMessage(Text.of("&bToday's board &7- your scored try. Closing the game uses it up."));
        } else if (dailyTried(player)) {
            player.sendMessage(Text.of("&7Today's board again: practice, so nothing is recorded."));
        } // else: it can't count where they are, and startDaily said so
        return new Run(start, tick(player));
    }

    /** A fresh field for {@code run}. */
    public SnakeEngine deal(Run run) {
        return new SnakeEngine(run.isDaily() ? run.daily().seed() : ThreadLocalRandom.current().nextLong());
    }

    /** Ticks between moves for this player: Bedrock taps arrive later, so it gets the slower setting. */
    private int tick(Player player) {
        SnakeSettings s = settings();
        return Bedrock.is(player) ? s.tickBedrock() : s.tickJava();
    }

    /** A run just ended: say how it went, record it and pay. */
    public void finish(Player player, Run run, SnakeEngine field) {
        int apples = field.apples();
        if (field.state() == SnakeEngine.State.FULL) {
            player.sendMessage(Text.of("&a✔ You filled the whole field!"));
        }
        player.sendMessage(Text.of("&b" + name() + ": &f" + apples(apples) + "&7."));
        boolean goal = apples >= DAILY_GOAL;
        Finish f = run.isDaily()
                ? finishDaily(player, run.daily(), apples, false, goal)
                : finishClassic(player, Scores.CLASSIC, apples, false);
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice: nothing is recorded."));
        } else if (run.isDaily()) {
            player.sendMessage(Text.of(goal ? "&a✔ " + DAILY_GOAL + " apples: today's goal!"
                    : "&7Today's goal was " + DAILY_GOAL + " apples."));
        }
        boolean best = !f.practice() && f.result().personalBest();
        if (best) {
            player.sendMessage(Text.of(f.result().record() ? "&e★ New best - and the server record!" : "&e★ New best!"));
        }
        if (best || (goal && run.isDaily() && !f.practice())) {
            Sounds.won(player);
        } else {
            Sounds.miss(player);
        }
    }

    /** "1 apple", "12 apples". */
    public static String apples(long n) {
        return n + " apple" + (n == 1 ? "" : "s");
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

    /** Whether Classic milestone {@code n} (1 bronze .. 3 gold) has paid the player. */
    public boolean milestonePaid(Player player, int n) {
        return check(() -> games().dao().rewardPaid(player.getUniqueId(), id(), RewardKind.MILESTONE,
                SkillRewards.milestoneRef(Scores.CLASSIC, n)));
    }

    /** Open one of this game's high-score boards. */
    public void showScores(Player player, String board, Runnable back) {
        openScores(player, board, false, back);
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
        return "&7Daily: " + DAILY_GOAL + " apples" + (reward > 0 ? " for &6" + reward + " token" + (reward == 1 ? "" : "s") : "");
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
