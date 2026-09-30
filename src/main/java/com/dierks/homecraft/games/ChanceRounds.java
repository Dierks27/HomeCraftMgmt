package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Crash-safe settlement for games of chance (spec §5.2, R2.11, R3.5).
 *
 * <p>The rule is "decide first, then show": the tokens put in, the outcome and the tokens back
 * are committed to the database in ONE transaction before any animation plays. A screen closed
 * early just prints the result; a crash mid-spin has already paid. Nothing here can pay twice —
 * every close is a guarded {@code UPDATE ... WHERE state = 'OPEN'} and only the call that flips
 * the row pays.
 *
 * <p>Two shapes of game:
 * <ul>
 *   <li><b>Instant</b> (Ore Slots, the Wheel): {@link #play} runs the gate, draws a seed, asks the
 *       engine for the outcome FROM THE SEED, and settles it in one go.</li>
 *   <li><b>Multi-step</b> (Twenty-One, Higher or Lower): {@link #open} puts the tokens in and
 *       writes an OPEN round carrying the seed and every payout parameter; each action is
 *       appended with {@link #step} before its card is shown; {@link #close} settles once.
 *       Every card is a pure function of (seed, index), so a round is fully determined by its
 *       seed and its actions — which is what lets the game's {@link ExitSettler} finish a round
 *       the player walked away from, from {@code data} alone, even after a restart or a retune.</li>
 * </ul>
 *
 * <p>Lifecycle of a multi-step round: closing the screen leaves it OPEN and opening the game
 * resumes it ({@link #openRound}); a quit settles it by the exit rule; start-up settles the OPEN
 * rounds of offline players and any round untouched for 10 minutes; a one-minute sweep does the
 * same for online players; a join settles anything a crash left behind and tells the player.
 * Settling never depends on the game still being enabled.
 */
public final class ChanceRounds {

    /** A round still being played (multi-step games). */
    public static final String OPEN = "OPEN";
    /** A finished round: its payout was paid in the same transaction that set this. */
    public static final String SETTLED = "SETTLED";
    /** A skill game's one scored daily attempt (spec R2.13): no tokens, just "already dealt". */
    public static final String DAILY = "DAILY";

    /**
     * One round, as stored in {@code game_rounds}.
     *
     * @param id     the row id
     * @param gameId the game
     * @param player who put the tokens in
     * @param seed   what decided it (every card, reel and segment comes from this)
     * @param stake  tokens put in so far (a Double included)
     * @param data   the engine's own record: its version, every payout parameter, every action
     * @param state  {@link #OPEN}, {@link #SETTLED} or {@link #DAILY}
     */
    public record Round(long id, String gameId, UUID player, long seed, int stake, String data, String state) {

        /** Whether it is still being played. */
        public boolean open() {
            return OPEN.equals(state);
        }
    }

    /**
     * A game whose whole outcome is one draw (Ore Slots, the Wheel). Pure: no Bukkit, no config
     * read at play time beyond what the engine was built with.
     *
     * @param <O> the outcome (the reels, the segment)
     */
    public interface InstantEngine<O> {

        /** The outcome for this seed and stake. Same inputs, same outcome, always. */
        O decide(long seed, int stake);

        /** The tokens back for it (0 for nothing), already capped by {@code games.max_payout}. */
        int payout(O outcome);

        /** What to store with the round (enough to show and audit it again). */
        String data(O outcome);
    }

    /** A round nobody touched for this long is finished by its exit rule (R3.5). */
    public static final long STALE_MS = 10 * 60_000L;
    /** The {@code game_prefs} key prefix for a line waiting for the player's next join. */
    public static final String NOTICE = "notice.";

    /**
     * The ledger source and name of each game of chance, for settling a round whose game could
     * not even be built this run — its tokens still have to go back under the right source.
     */
    private static final Map<String, TokenService.Source> SOURCES = Map.of(
            "ore_slots", TokenService.Source.ARCADE_SLOTS,
            "twenty_one", TokenService.Source.ARCADE_TWENTY_ONE,
            "wheel", TokenService.Source.ARCADE_WHEEL,
            "higher_lower", TokenService.Source.ARCADE_HILO,
            "coin_flip", TokenService.Source.ARCADE_COIN_FLIP);

    /** An open screen whose settings changed under it (a reload): reopen to play. */
    static final Refusal CHANGED = Refusal.of("That game's settings just changed. Open it again to play.");

    private final GamesService games;
    /** Game ids whose exit rule is missing or threw, already logged this run. */
    private final Set<String> logged = new HashSet<>();

    public ChanceRounds(GamesService games) {
        this.games = games;
    }

    /**
     * One instant play: gate (every step), seed, decide, then debit + SETTLED row + payout in one
     * transaction. Returns the outcome for the screen to show, or {@code null} when refused (the
     * player has already been told).
     */
    public <O> O play(Player player, Game game, int stake, InstantEngine<O> engine) {
        if (stake <= 0) {
            throw new IllegalArgumentException("a play puts tokens in: " + stake);
        }
        Refusal refusal = games.canStake(player, game, stake);
        if (refusal != null) {
            games.tell(player, refusal);
            return null;
        }
        long seed = ThreadLocalRandom.current().nextLong();
        O outcome = engine.decide(seed, stake);
        int decided = Math.max(0, engine.payout(outcome));
        int payout = capped(game, stake, decided);
        if (payout != decided) {
            // The screen shows the engine's number, so paying anything else would tell the player
            // one thing and pay another. It happens only when the engine didn't cap (a bug) or a
            // reload lowered games.max_payout under a screen still open: nothing is taken, and
            // reopening the game builds it with today's settings.
            games.tell(player, CHANGED);
            return null;
        }
        Round round;
        try {
            round = games.dao().settleRound(player.getUniqueId(), game.id(), game.source(), clock().dayKey(), stake,
                    payout, seed, engine.data(outcome), stakeDetail(game.name(), stake),
                    payoutDetail(game.name(), stake, payout), clock().nowMillis());
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not settle a " + game.id() + " play - nothing was taken", e);
            games.tell(player, Refusal.CLOSED);
            return null;
        }
        if (round == null) {
            games.tell(player, shortBy(player, stake));
            return null;
        }
        games.gate().clicked(player);
        return outcome;
    }

    /**
     * Start a multi-step round: gate (every step), debit and write the OPEN row with the seed and
     * {@code data} (which must already hold every payout parameter). {@code null} when refused
     * (told) or when this game already has an OPEN round for the player (resume that instead).
     *
     * <p>A new round is also refused, before anything is taken, while a scheduled restart is
     * minutes away ({@link GamesService#restartRefusal}): the restart would leave it to the exit
     * rule. An OPEN round is never held up (it is resumed, not opened), and instant plays
     * ({@link #play}) aren't either: they are over as soon as they start.
     */
    public Round open(Player player, Game game, int stake, String data) {
        if (stake <= 0) {
            throw new IllegalArgumentException("a round puts tokens in: " + stake);
        }
        Refusal refusal = games.canStake(player, game, stake);
        if (refusal != null) {
            games.tell(player, refusal);
            return null;
        }
        UUID id = player.getUniqueId();
        Round round;
        try {
            if (games.dao().openRoundFor(id, game.id()) != null) {
                return null; // the game resumes that one
            }
            Refusal held = games.restartRefusal();
            if (held != null) {
                games.tell(player, held);
                return null;
            }
            round = games.dao().openRound(id, game.id(), game.source(), clock().dayKey(), stake,
                    ThreadLocalRandom.current().nextLong(), data, stakeDetail(game.name(), stake), clock().nowMillis());
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not open a " + game.id() + " round - nothing was taken", e);
            games.tell(player, Refusal.CLOSED);
            return null;
        }
        if (round == null) {
            games.tell(player, shortBy(player, stake));
            return null;
        }
        games.gate().clicked(player);
        return round;
    }

    /**
     * Put more in mid-round (a Double): re-runs gate steps 2, 4, 7 and 8 for the extra amount at
     * the moment of the debit (R1.10) and debits only while the round is still OPEN.
     *
     * @return false when refused (nothing changed; the player has been told)
     */
    public boolean raise(Player player, Round round, int extra) {
        if (extra <= 0) {
            throw new IllegalArgumentException("a raise puts more in: " + extra);
        }
        Game game = games.game(round.gameId());
        if (game == null) {
            games.tell(player, Refusal.CLOSED);
            return false;
        }
        Refusal refusal = games.gate().extra(player, game, extra);
        if (refusal != null) {
            games.tell(player, refusal);
            return false;
        }
        UUID id = player.getUniqueId();
        boolean ok;
        try {
            // The pause and the day's limit again, inside the transaction that takes the tokens.
            ok = games.dao().raiseStake(round.id(), id, game.source(), extra, () -> {
                Breaks.Today today = games.breaks() == null ? null : games.breaks().today(id);
                return today != null && !today.paused(clock().nowMillis()) && !today.over(extra);
            }, round.data(), stakeDetail(game.name(), extra), clock().nowMillis()); // the action lands with the tokens
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not add to a " + game.id() + " round - nothing was taken", e);
            games.tell(player, Refusal.CLOSED);
            return false;
        }
        if (!ok) {
            Refusal why = games.gate().extra(player, game, extra);
            games.tell(player, why != null ? why : Refusal.CLOSED);
        }
        return ok;
    }

    /**
     * Record the round's new {@code data} (an action) BEFORE its card is shown. Guarded on the
     * round still being OPEN.
     *
     * @return the updated round, or {@code null} if it was already settled
     */
    public Round step(Round round, String data) {
        try {
            if (!games.dao().updateRound(round.id(), data, clock().nowMillis())) {
                return null;
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not record a " + round.gameId() + " move", e);
            return null;
        }
        return new Round(round.id(), round.gameId(), round.player(), round.seed(), round.stake(), data, OPEN);
    }

    /**
     * Settle the round once: SETTLED + payout in one guarded transaction.
     *
     * @return false if it was already settled — it never pays twice
     */
    public boolean close(Round round, int payout, String data) {
        try {
            Round current = games.dao().round(round.id());
            if (current == null || !current.open()) {
                return false;
            }
            int pay = Math.max(0, payout);
            return games.dao().closeRound(round.id(), pay, data, source(current.gameId()),
                    payoutDetail(name(current.gameId()), current.stake(), pay), clock().nowMillis());
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not settle a " + round.gameId() + " round - it stays open", e);
            return false;
        }
    }

    /** The player's OPEN round in this game, to resume; {@code null} for none. */
    public Round openRound(UUID player, String gameId) {
        try {
            return games.dao().openRoundFor(player, gameId);
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not read an open " + gameId + " round", e);
            return null;
        }
    }

    /**
     * Settle every OPEN round of this player by each game's {@link ExitSettler}; a game with none
     * (or one that throws) gives the tokens back, and the settlement is logged. Queues or sends
     * the "finished for you" line.
     */
    public void settleOpen(UUID player) {
        settleOpen(player, games.host().online(player) == null);
    }

    /** As {@link #settleOpen(UUID)}; {@code queue} keeps the line for the next join (a quit, a join). */
    void settleOpen(UUID player, boolean queue) {
        try {
            for (Round r : games.dao().openRounds(player)) {
                settle(r, queue);
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not read a player's open rounds", e);
        }
    }

    /**
     * The one-minute sweep: finish every round nobody has touched for {@link #STALE_MS}, telling
     * an online player in chat and queuing the line for anyone else.
     */
    void sweep() {
        try {
            for (Round r : games.dao().staleOpenRounds(clock().nowMillis() - STALE_MS)) {
                settle(r, games.host().online(r.player()) == null);
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not read the open rounds", e);
        }
    }

    /**
     * Start-up: finish the OPEN rounds of everyone offline (a crash or a shutdown left them) and
     * any round untouched for {@link #STALE_MS}. An online player's fresh round (a {@code /reload})
     * stays open to resume.
     */
    void settleAtStart() {
        try {
            Set<Long> stale = new HashSet<>();
            for (Round r : games.dao().staleOpenRounds(clock().nowMillis() - STALE_MS)) {
                stale.add(r.id());
            }
            for (Round r : games.dao().openRounds()) {
                boolean offline = games.host().online(r.player()) == null;
                if (offline || stale.contains(r.id())) {
                    settle(r, offline);
                }
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not read the open rounds at start-up", e);
        }
    }

    /** A game was switched off: finish its OPEN rounds now (R2.6). */
    void settleGame(String gameId) {
        try {
            for (Round r : games.dao().openRounds()) {
                if (r.gameId().equals(gameId)) {
                    settle(r, games.host().online(r.player()) == null);
                }
            }
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not read the open " + gameId + " rounds", e);
        }
    }

    /** Every OPEN round on the server (for {@code /hcm games status}); -1 if it can't be read. */
    public int openCount() {
        try {
            return games.dao().openRounds().size();
        } catch (SQLException e) {
            return -1;
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    /** What finishing a round for the player pays, and whether it simply gave the tokens back. */
    record Exit(int payout, boolean returned) {
    }

    /**
     * The game's exit rule on the round's own data. A game with no rule, or a rule that throws or
     * answers nonsense, gives the tokens back (logged once per game): the player never loses a
     * round to a bug.
     */
    Exit exit(Round r) {
        GameSpec<?> spec = games.spec(r.gameId());
        ExitSettler settler = spec == null ? null : spec.settler();
        if (settler == null) {
            once(r.gameId(), Level.WARNING, r.gameId() + " has no exit rule - its unfinished rounds give the tokens back",
                    null);
            return new Exit(r.stake(), true);
        }
        try {
            int payout = settler.payoutOnExit(r.seed(), r.stake(), r.data());
            if (payout < 0) {
                throw new IllegalStateException("a payout of " + payout);
            }
            return new Exit(payout, false);
        } catch (RuntimeException | LinkageError e) {
            once(r.gameId(), Level.SEVERE, r.gameId() + "'s exit rule failed - its unfinished rounds give the tokens back",
                    e);
            return new Exit(r.stake(), true);
        }
    }

    /** Finish one OPEN round by its exit rule, pay once, and tell (or queue) the player. */
    private void settle(Round r, boolean queue) {
        Exit exit = exit(r);
        String name = name(r.gameId());
        String detail = exit.returned() ? name + ": round returned" : payoutDetail(name, r.stake(), exit.payout());
        boolean paid;
        try {
            paid = games.dao().closeRound(r.id(), exit.payout(), null, source(r.gameId()), detail, clock().nowMillis());
        } catch (SQLException e) {
            log().log(Level.SEVERE, "Could not finish " + r.gameId() + " round " + r.id() + " - it stays open", e);
            return;
        }
        if (!paid) {
            return; // someone else finished it first; it paid once, there
        }
        log().info("Finished " + r.gameId() + " round " + r.id() + " for " + r.player() + " by its "
                + (exit.returned() ? "fallback (tokens back)" : "exit rule") + ": " + r.stake() + " in, "
                + exit.payout() + " back");
        games.notice(r.player(), finishedLine(name, r.stake(), exit), queue);
    }

    /** "Your Twenty-One game from before was finished for you: 9 tokens back." — never a "win" for less. */
    static String finishedLine(String name, int stake, Exit exit) {
        if (exit.returned()) {
            return "&7Your " + name + " game from before was stopped, so your &6" + stake + " &7token"
                    + (stake == 1 ? "" : "s") + " came back.";
        }
        String result = exit.payout() == 0 ? "no win this time"
                : "&6" + exit.payout() + " &7token" + (exit.payout() == 1 ? "" : "s") + " back";
        return "&7Your " + name + " game from before was finished for you: " + result + "&7.";
    }

    /**
     * The payout, held to the game's cap ({@code games.max_payout}, never below its largest
     * stake). The engine already caps; this only catches an engine that didn't (logged once).
     */
    private int capped(Game game, int stake, int payout) {
        int pay = Math.max(0, payout);
        List<Integer> stakes = new ArrayList<>();
        GameSpec<?> spec = games.spec(game.id());
        Object v = spec == null ? null : PlayGate.component(games.config().settings(spec), "stakes");
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Integer n) {
                    stakes.add(n);
                }
            }
        }
        stakes.add(stake);
        int cap = games.config().common().maxPayoutFor(stakes);
        if (pay > cap) {
            once(game.id() + ":cap", Level.WARNING, game.id() + " tried to pay " + pay + " - held to games.max_payout ("
                    + cap + ")", null);
            return cap;
        }
        return pay;
    }

    /** "You need N more tokens", after a debit the database refused (the balance moved). */
    private Refusal shortBy(Player player, int stake) {
        return Refusal.needMore(Math.max(1, stake - games.host().balance(player.getUniqueId())));
    }

    private TokenService.Source source(String gameId) {
        Game game = games.game(gameId);
        if (game != null) {
            return game.source();
        }
        return SOURCES.getOrDefault(gameId, TokenService.Source.ADMIN);
    }

    private String name(String gameId) {
        Game game = games.game(gameId);
        if (game != null) {
            return game.name();
        }
        TokenService.Source s = SOURCES.get(gameId);
        return s != null ? s.label() : gameId;
    }

    private void once(String key, Level level, String line, Throwable cause) {
        if (logged.add(key)) {
            log().log(level, line, cause);
        }
    }

    private GameClock clock() {
        return games.host().clock();
    }

    private Logger log() {
        return games.host().logger();
    }

    /** The ledger detail for tokens put in: "Ore Slots: 5 in". */
    public static String stakeDetail(String gameName, int stake) {
        return gameName + ": " + stake + " in";
    }

    /**
     * The ledger detail for tokens back: "Ore Slots: won 25" when more came back than went in,
     * "Ore Slots: 3 back" otherwise — a partial return is never called a win (R1.3).
     */
    public static String payoutDetail(String gameName, int stake, int payout) {
        return gameName + ": " + (payout > stake ? "won " + payout : payout + " back");
    }
}
