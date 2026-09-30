package com.dierks.homecraft.games.cabinet.whack;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.cabinet.whack.WhackAZombieMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Whack-a-Zombie (spec §10b, R1.22, R2.13): a short timed round on a 3 by 3 grid of holes.
 * Zombies pop up for a moment — whack them for a point; now and then a villager pops up instead,
 * and bonking one costs a point.
 *
 * <p>The round lasts {@code seconds} (shipped 30). The score is points (higher is better, never
 * below zero); milestones at 15, 25 and 35, and the daily goal is 20 on today's round — the same
 * pops for everyone, drawn from the day's secret seed before the round starts, so nothing a player
 * does changes what comes next. Bedrock players see each pop for half as long again.
 *
 * <p>The {@link WhackEngine} holds the round; the screen ticks it and draws the holes.
 */
public final class WhackAZombie extends CabinetGame {

    /** Built and working. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<WhackAZombieSettings> SPEC = new GameSpec<>("whack_a_zombie", GameKind.CABINET,
            WhackAZombieSettings.KEYS, WhackAZombieSettings.defaults(), WhackAZombieSettings::parse,
            WhackAZombie::new, null);

    /** Today's round: this many points on the scored try. */
    public static final int DAILY_GOAL = 20;
    /** Scores are points: more is better. */
    public static final boolean LOWER_IS_BETTER = false;
    /** The feed's unit for the {@code classic} board. */
    static final String UNIT = "points";

    public WhackAZombie(GameContext ctx) {
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
        return "Whack-a-Zombie";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_WHACK;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Hit the zombies when they pop up: +1 each.",
                "Don't bonk the villagers! -1 each.",
                "A round lasts " + settings().seconds() + " seconds.",
                "Daily: " + DAILY_GOAL + " points in today's round.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer);
        String fact = best == null || best == 0 ? "bonk the zombies" : "best " + best + " points";
        return Menus.icon(Material.ZOMBIE_HEAD, "&b" + name() + " &7- " + fact,
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        new WhackAZombieMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), Scores.CLASSIC, LOWER_IS_BETTER);
        out.cabinet(id(), name(), Scores.CLASSIC, UNIT, LOWER_IS_BETTER, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public WhackAZombieSettings settings() {
        return games().settings(SPEC);
    }

    // ---- for the screens ----------------------------------------------------------------------

    /** A fresh seed for a Classic round. */
    public long classicSeed() {
        return ThreadLocalRandom.current().nextLong();
    }

    /** The player's best Classic score, or {@code null}. */
    public Long best(Player player) {
        return games().scores().best(player.getUniqueId(), id(), Scores.CLASSIC);
    }

    /** Whether the player has had today's scored try (their next daily is practice). */
    public boolean triedToday(Player player) {
        try {
            return games().dao().dailyAttempt(player.getUniqueId(), id(), today());
        } catch (SQLException e) {
            return false;
        }
    }

    /** Open a high-score board: {@code classic}, or today's daily board. */
    public void scores(Player player, String board, Runnable back) {
        openScores(player, board, LOWER_IS_BETTER, back);
    }

    /** Today's daily board name. */
    public String todayBoard() {
        return Scores.daily(today());
    }

    /**
     * A round is over: record it (Classic, or the scored daily try), pay what it earned and tell
     * the player.
     *
     * @param daily the daily start, or {@code null} for Classic
     */
    public Finish finish(Player player, DailyStart daily, int points) {
        boolean goal = points >= DAILY_GOAL;
        Finish f = daily == null
                ? finishClassic(player, Scores.CLASSIC, points, LOWER_IS_BETTER)
                : finishDaily(player, daily, points, LOWER_IS_BETTER, goal);
        String score = points + " point" + (points == 1 ? "" : "s");
        String best = points <= 0 ? "" : daily == null ? standing(f.result()) : dayStanding(f.result());
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice round over: " + score + ". Practice isn't recorded."));
        } else if (daily != null) {
            player.sendMessage(Text.of((goal
                    ? "&a✔ Daily challenge done: " + score + "!"
                    : "&7Daily challenge: " + score + ". The goal was " + DAILY_GOAL + ".") + best));
        } else {
            player.sendMessage(Text.of("&aTime's up! You scored " + score + "." + best));
        }
        if (daily == null ? points > 0 && f.result().personalBest() : !f.practice() && goal) {
            Sounds.won(player);
        }
        return f;
    }

    /** A Classic score's standing: " New best! (was 22)" and " ★ Server record!", or nothing. */
    static String standing(ScoreResult r) {
        if (r == null || !r.personalBest()) {
            return "";
        }
        String out = " &eNew best!" + (r.previous() != null ? " &7(was " + r.previous() + ")" : "");
        return r.record() ? out + " &6★ Server record!" : out;
    }

    /** A scored daily try's place on today's board: " ★ Top score today!" or " #3 today.". */
    static String dayStanding(ScoreResult r) {
        if (r == null || r.rank() <= 0) {
            return "";
        }
        return r.record() ? " &6★ Top score today!" : " &7#" + r.rank() + " today.";
    }
}
