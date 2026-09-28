package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The small token rewards of the skill games (spec §6.1, R1.22, R2.12, R2.16).
 *
 * <p>The games are for fun, not a token farm: the token economy's budget (DESIGN §3.9, about 28
 * tokens on an active day) comes first. So every reward except a first clear counts toward the
 * game's {@code daily_cap} and the server-wide {@code games.skill_daily_cap} (shipped 6); a reward
 * that would pass a cap pays what is left, maybe nothing ("scores still count!"); a personal best
 * pays nothing; and one-time rewards (a milestone, today's challenge, a first clear) carry a stable
 * ref the database refuses to pay twice. Checked BEFORE anything one-time is marked as used: a
 * player who can't earn here (wrong world, creative) keeps the reward for later. Nothing is ever
 * earned by playing a game of chance (R1.17).
 *
 * <p>The ref builders below are the one spelling of each ref, so two games can never disagree
 * about what "today's featured bonus" is called.
 */
public final class SkillRewards {

    /** What a capped player reads, at most once per finish. */
    public static final String CAPPED = "&7You've won all the game tokens you can today — scores still count!";
    /** What a player who can't earn where they are reads (creative mode, a world without games). */
    public static final String NOT_HERE = "&7No tokens can be earned here — scores still count!";

    /** Lines about the caps closer together than this are one finish: say it once. */
    private static final long SAY_ONCE_MS = 5_000L;

    private final GamesService games;
    /** Player → when they were last told they are capped (or can't earn here). */
    private final Map<UUID, Long> told = new HashMap<>();

    public SkillRewards(GamesService games) {
        this.games = games;
    }

    /**
     * Pay a reward: capped, once-only by {@code ref} (an empty ref = repeatable), one transaction,
     * then the chat line and sound ("✦ +1 token (Snake: daily challenge). You have N.").
     *
     * <p>Nothing is paid for a game of chance, for a personal best, or to a player who can't earn
     * here ({@link #canEarnHere}) — and that is checked BEFORE anything one-time is recorded, so
     * the reward is still there to earn later. A {@link RewardKind#acrossGames() once-a-day across
     * games} reward (the featured bonus, the course of the week) counts toward the server-wide cap
     * only; a first clear toward neither.
     *
     * @param source       the ledger source (a course's own source for the time trials)
     * @param gameDailyCap the game's {@code daily_cap}
     * @return the tokens actually paid, 0 to {@code tokens}
     */
    public int pay(Player player, Game game, TokenService.Source source, RewardKind kind, String ref, int tokens,
                   int gameDailyCap, String detail) {
        if (player == null || game == null || kind == null || tokens <= 0) {
            return 0;
        }
        if (game.kind().chance() || kind == RewardKind.PERSONAL_BEST) {
            return 0; // nothing rewards a game of chance (R1.17); a best is announced, never paid
        }
        String r = ref == null ? "" : ref.trim();
        boolean once = !r.isEmpty();
        if (!once && !kind.repeatable()) {
            games.host().logger().warning(game.id() + " tried to pay a " + kind + " reward without a ref - not paid");
            return 0;
        }
        if (!canEarnHere(player)) {
            sayOnce(player, NOT_HERE);
            return 0;
        }
        UUID id = player.getUniqueId();
        long day = games.host().clock().dayKey();
        int capGame = kind.acrossGames() ? -1 : Math.max(-1, gameDailyCap);
        int capAll = Math.max(-1, games.config().common().skillDailyCap());
        String line = detail == null || detail.isBlank() ? (source == null ? game.source() : source).label() : detail;
        int paid;
        try {
            paid = games.dao().payReward(id, game.id(), source == null ? game.source() : source, day, kind, r, tokens,
                    capGame, capAll, once, line, games.host().clock().nowMillis());
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not pay a " + game.id() + " reward", e);
            return 0;
        }
        if (paid > 0) {
            player.sendMessage(Text.of("&e✦ &a+" + paid + " token" + (paid == 1 ? "" : "s") + " &7(" + Text.plain(line)
                    + ")&7. You have &6" + games.host().balance(id) + "&7."));
            orb(player);
        }
        if (paid < tokens && kind.capped() && !(paid == 0 && once && alreadyPaid(id, game, kind, r))) {
            sayOnce(player, CAPPED);
        }
        return paid;
    }

    /**
     * Whether the player may earn game tokens where they are now (§6.1, R1.22): online, not in
     * creative or spectator, and in a world games are played in (an economy world,
     * {@code games.worlds} or {@code games.play_worlds}). World games pay from the Games world,
     * which is not an economy world: the session is what proves the play was real.
     */
    public boolean canEarnHere(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return false;
        }
        return games.gate().worldAllowed(player.getWorld());
    }

    /** Forget what the player was told (they left). */
    void forget(UUID player) {
        told.remove(player);
    }

    private boolean alreadyPaid(UUID player, Game game, RewardKind kind, String ref) {
        try {
            return games.dao().rewardPaid(player, game.id(), kind, ref);
        } catch (SQLException e) {
            return false;
        }
    }

    /** One line per finish, however many rewards in it were held back. */
    private void sayOnce(Player player, String line) {
        long now = games.host().clock().nowMillis();
        Long last = told.get(player.getUniqueId());
        if (last != null && now - last < SAY_ONCE_MS) {
            return;
        }
        told.put(player.getUniqueId(), now);
        player.sendMessage(Text.of(line));
    }

    /** The bright pickup sound TokenService plays for "+N tokens". */
    private static void orb(Player player) {
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        } catch (RuntimeException | LinkageError ignored) {
            // a cosmetic sound never breaks a payment
        }
    }

    /** Today's daily challenge: {@code daily:<day>}. */
    public static String dailyRef(long day) {
        return "daily:" + day;
    }

    /** A board milestone, 1 = bronze .. 3 = gold: {@code ms:<board>:<n>}. */
    public static String milestoneRef(String board, int n) {
        return "ms:" + board + ":" + n;
    }

    /** Today's featured bonus: {@code featured:<day>}. */
    public static String featuredRef(long day) {
        return "featured:" + day;
    }

    /** A course's first clear: {@code first_clear:<course>}. */
    public static String firstClearRef(String course) {
        return "first_clear:" + course;
    }

    /** The week's best on a course: {@code weekly:<course>:<weekKey>}. */
    public static String weeklyRef(String course, long weekKey) {
        return "weekly:" + course + ":" + weekKey;
    }

    /** Finishing the course of the week today: {@code cotw:<day>}. */
    public static String courseOfWeekRef(long day) {
        return "cotw:" + day;
    }

    /** Golf at par or better on a course today: {@code par:<course>:<day>}. */
    public static String parRef(String course, long day) {
        return "par:" + course + ":" + day;
    }

    /** A hole-in-one on a hole today: {@code hio:<course>:<hole>:<day>}. */
    public static String holeInOneRef(String course, int hole, long day) {
        return "hio:" + course + ":" + hole + ":" + day;
    }
}
