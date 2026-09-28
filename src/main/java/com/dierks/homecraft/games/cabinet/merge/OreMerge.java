package com.dierks.homecraft.games.cabinet.merge;

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
import com.dierks.homecraft.gui.games.cabinet.merge.OreMergeMenu;
import com.dierks.homecraft.storage.GamesDao;
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
 * Ore Merge (spec §10b, R1.22, R2.13): 2048 with ores on a 4 by 4 grid.
 *
 * <p>Slide the ores; two the same merge into the next one up, from coal to a dragon egg. The
 * score is the sum of every merge (higher is better) and is recorded when no move is left — or
 * when the player taps "End game", since a long game shouldn't be lost to a closed screen. The
 * milestones are about the BIGGEST ore made (a diamond, netherite, a nether star), and so is the
 * daily goal (a diamond), which is why a Classic finish passes the biggest tile to
 * {@link #finishClassic(Player, String, long, long, boolean)} separately from the score. The rules
 * live in {@link MergeEngine}.
 */
public final class OreMerge extends CabinetGame {

    /** Built: the game follows its config switch. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<OreMergeSettings> SPEC = new GameSpec<>("ore_merge", GameKind.CABINET,
            OreMergeSettings.KEYS, OreMergeSettings.defaults(), OreMergeSettings::parse,
            OreMerge::new, null);

    public OreMerge(GameContext ctx) {
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
        return "Ore Merge";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_MERGE;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Slide the ores. Two the same merge into the next one.",
                "Every merge adds to your score.",
                "From coal up to a dragon egg: can you make a diamond?");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer, Scores.CLASSIC);
        String fact = best != null ? "your best " + best + " points" : "can you make a diamond?";
        List<String> lore = new ArrayList<>();
        for (String line : rules().subList(0, Math.min(3, rules().size()))) {
            lore.add("&7" + line);
        }
        lore.add(dailyLine(viewer));
        lore.add("&eClick to play");
        return Menus.icon(Material.DIAMOND, "&b" + name() + " &7- " + fact, lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        new OreMergeMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), Scores.CLASSIC, false);
        out.cabinet(id(), name(), Scores.CLASSIC, "points", false, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public OreMergeSettings settings() {
        return ctx.games().settings(SPEC);
    }

    // ---- runs ---------------------------------------------------------------------------------

    /**
     * How a run was started.
     *
     * @param daily the daily start, or {@code null} for Classic
     */
    public record Run(DailyStart daily) {

        public boolean isDaily() {
            return daily != null;
        }

        /** A daily board played again: nothing is recorded. */
        public boolean practice() {
            return daily != null && !daily.scored();
        }
    }

    /** A Classic run. */
    public Run classic() {
        return new Run(null);
    }

    /** Deal today's board to the player (their first deal today is the scored try) and say which it is. */
    public Run daily(Player player) {
        DailyStart start = startDaily(player);
        if (start.scored()) {
            player.sendMessage(Text.of("&bToday's board &7- your scored try. Closing the game uses it up."));
        } else if (dailyTried(player)) {
            player.sendMessage(Text.of("&7Today's board again: practice, so nothing is recorded."));
        } // else: it can't count where they are, and startDaily said so
        return new Run(start);
    }

    /** A fresh grid for {@code run}. */
    public MergeEngine deal(Run run) {
        return new MergeEngine(run.isDaily() ? run.daily().seed() : ThreadLocalRandom.current().nextLong());
    }

    /** A game just ended (no move left, or "End game"): say how it went, record it and pay. */
    public void finish(Player player, Run run, MergeEngine grid) {
        long score = grid.score();
        int biggest = grid.biggest();
        player.sendMessage(Text.of("&b" + name() + ": &f" + score + " points&7, biggest ore: &f"
                + MergeEngine.name(biggest) + "&7."));
        boolean goal = biggest >= MergeEngine.DIAMOND;
        Finish f = run.isDaily()
                ? finishDaily(player, run.daily(), score, false, goal)
                : finishClassic(player, Scores.CLASSIC, score, MergeEngine.value(biggest), false);
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice: nothing is recorded."));
        } else if (run.isDaily()) {
            player.sendMessage(Text.of(goal ? "&a✔ You made a diamond: today's goal!"
                    : "&7Today's goal was a diamond."));
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
        return "&7Daily: make a diamond" + (reward > 0 ? " for &6" + reward + " token" + (reward == 1 ? "" : "s") : "");
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
