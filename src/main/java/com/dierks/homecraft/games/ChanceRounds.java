package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

import java.util.UUID;

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

    private final GamesService games;

    public ChanceRounds(GamesService games) {
        this.games = games;
    }

    /**
     * One instant play: gate (every step), seed, decide, then debit + SETTLED row + payout in one
     * transaction. Returns the outcome for the screen to show, or {@code null} when refused (the
     * player has already been told).
     */
    public <O> O play(Player player, Game game, int stake, InstantEngine<O> engine) {
        // F1b: games.canStake(player, game, stake) -> tell and return null if refused; seed from
        // ThreadLocalRandom; outcome = engine.decide(seed, stake); dao.settleRound(... day, stake,
        // min(payout, cap), seed, engine.data(outcome), stakeDetail, payoutDetail, now) inside
        // guard; null round -> tell needMore and return null; else record the click and return it.
        return null;
    }

    /**
     * Start a multi-step round: gate (every step), debit and write the OPEN row with the seed and
     * {@code data} (which must already hold every payout parameter). {@code null} when refused
     * (told) or when this game already has an OPEN round for the player (resume that instead).
     */
    public Round open(Player player, Game game, int stake, String data) {
        // F1b: gate, seed, dao.openRound(...).
        return null;
    }

    /**
     * Put more in mid-round (a Double): re-runs gate steps 2, 4, 7 and 8 for the extra amount at
     * the moment of the debit (R1.10) and debits only while the round is still OPEN.
     *
     * @return false when refused (nothing changed; the player has been told)
     */
    public boolean raise(Player player, Round round, int extra) {
        // F1b: gate.extra(...), then dao.raiseStake(... limit, dayStart ...).
        return false;
    }

    /**
     * Record the round's new {@code data} (an action) BEFORE its card is shown. Guarded on the
     * round still being OPEN.
     *
     * @return the updated round, or {@code null} if it was already settled
     */
    public Round step(Round round, String data) {
        // F1b: dao.updateRound(round.id(), data, now) -> new Round with data, or null.
        return null;
    }

    /**
     * Settle the round once: SETTLED + payout in one guarded transaction.
     *
     * @return false if it was already settled — it never pays twice
     */
    public boolean close(Round round, int payout, String data) {
        // F1b: dao.closeRound(round.id(), cap(payout), data, source, payoutDetail, now).
        return false;
    }

    /** The player's OPEN round in this game, to resume; {@code null} for none. */
    public Round openRound(UUID player, String gameId) {
        // F1b: dao.openRoundFor(player, gameId).
        return null;
    }

    /**
     * Settle every OPEN round of this player by each game's {@link ExitSettler}; a game with none
     * (or one that throws) gives the tokens back, and the settlement is logged. Queues or sends
     * the "finished for you" line.
     */
    public void settleOpen(UUID player) {
        // F1b: for each dao.openRounds(player): spec(gameId).settler().payoutOnExit(seed, stake,
        // data) inside try/catch (-> stake back + WARN), closeRound, tell or queue the message.
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
