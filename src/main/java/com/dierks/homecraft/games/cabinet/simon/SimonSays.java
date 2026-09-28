package com.dierks.homecraft.games.cabinet.simon;

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
import com.dierks.homecraft.gui.games.cabinet.simon.SimonSaysMenu;
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
 * Simon Says (spec §10b, R1.22, R2.13): four big coloured pads light up with a note each; play the
 * pattern back, and every round adds one more.
 *
 * <p>The score is the longest pattern played back in full (higher is better); milestones at 5, 10
 * and 15, and the daily goal is 8 on today's pattern — the same pattern for everyone, from the
 * day's secret seed. Bedrock plays each light longer ({@link SimonEngine.Tempo#BEDROCK}), because
 * a light that flickers past on a slow connection isn't a fair test of memory.
 *
 * <p>The {@link SimonEngine} holds the pattern and checks every press; the screen plays the lights
 * and notes on its own ticker and forwards taps. A press during the show is ignored, never
 * counted wrong.
 */
public final class SimonSays extends CabinetGame {

    /** Built and working. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<SimonSaysSettings> SPEC = new GameSpec<>("simon_says", GameKind.CABINET,
            SimonSaysSettings.KEYS, SimonSaysSettings.defaults(), SimonSaysSettings::parse,
            SimonSays::new, null);

    /** Today's pattern: play back this many on the scored try. */
    public static final int DAILY_GOAL = 8;
    /** Scores are pattern lengths: more is better. */
    public static final boolean LOWER_IS_BETTER = false;
    /** The feed's unit for the {@code classic} board. */
    static final String UNIT = "points";

    public SimonSays(GameContext ctx) {
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
        return "Simon Says";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_SIMON;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Watch the pads light up, then play them back.",
                "Each round the pattern grows by one.",
                "Daily: play back " + DAILY_GOAL + " on today's pattern.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer);
        String fact = best == null || best == 0 ? "repeat the pattern" : "best " + best;
        return Menus.icon(Material.NOTE_BLOCK, "&b" + name() + " &7- " + fact,
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        new SimonSaysMenu(ctx.plugin(), this, player, back).open(player);
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
    public SimonSaysSettings settings() {
        return games().settings(SPEC);
    }

    // ---- for the screens ----------------------------------------------------------------------

    /** A fresh seed for a Classic game. */
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
     * A game is over: record it (Classic, or the scored daily try), pay what it earned and tell
     * the player.
     *
     * @param daily the daily start, or {@code null} for Classic
     */
    public Finish finish(Player player, DailyStart daily, int score) {
        boolean goal = score >= DAILY_GOAL;
        Finish f = daily == null
                ? finishClassic(player, Scores.CLASSIC, score, LOWER_IS_BETTER)
                : finishDaily(player, daily, score, LOWER_IS_BETTER, goal);
        String line = "a pattern of " + score;
        String best = score <= 0 ? "" : daily == null ? standing(f.result()) : dayStanding(f.result());
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice over: you played back " + line + ". Practice isn't recorded."));
        } else if (daily != null) {
            player.sendMessage(Text.of((goal
                    ? "&a✔ Daily challenge done: you played back " + line + "!"
                    : "&7Daily challenge: you played back " + line + ". The goal was " + DAILY_GOAL + ".")
                    + best));
        } else {
            player.sendMessage(Text.of((score == 0 ? "&7Game over: no pads played back."
                    : "&aGame over! You played back " + line + ".") + best));
        }
        if (daily == null ? score > 0 && f.result().personalBest() : !f.practice() && goal) {
            Sounds.won(player);
        } else {
            Sounds.miss(player);
        }
        return f;
    }

    /** A Classic score's standing: " New best! (was 7)" and " ★ Server record!", or nothing. */
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
