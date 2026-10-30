package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubNext;
import com.dierks.homecraft.games.clubhouse.ClubRoute;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import com.dierks.homecraft.gui.games.trial.ResultMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A solo run's side of the Clubhouse (0.38.3, the owner: "Can you make it so we go to the clubhouse when
 * click into a game?"): the start through it, the hand-out from it, and the way back after the finish.
 * {@link TimeTrials} gains one line at each place (its {@code begin}, its finish and its Leave game); the
 * rules are here and in {@link ClubRoute}, modelled on {@link ClubRaces}.
 *
 * <p><b>Through the Clubhouse</b> ({@link #begin}): every Start (the course screen, the warm-up choice,
 * Today's pick, a join sign, {@code /hcm play <course>}, Play again) reaches {@code TimeTrials.begin}. A
 * player at home goes into the Clubhouse first ({@code solo_first}), carrying the course and their warm-up
 * choice ({@link ClubNext}); after the countdown, or "Go now", the Clubhouse hands the trip back here and
 * they are handed out. A player already in the Clubhouse is handed out at once ({@link #seat}): their
 * Clubhouse session goes to Time Trials in place (nothing restored, nothing saved again; anything that
 * arrived meanwhile is banked first), then the run's own teleport to the start (a boat is seated there by
 * its re-seat, as a race seat does), then the kit, the lines and the 3-2-1 exactly as a start from home. A
 * teleport that fails hands the session straight back: they are still in the Clubhouse, told why.
 *
 * <p><b>Back to the Clubhouse</b> ({@link #back}): a solo finish ({@code solo_after}) ends the run as the
 * session's end would (the boat, a dropper's state), then the Clubhouse takes the session and opens the
 * player's own results there, with Play again. Leave game partway through a run that was handed out from
 * the Clubhouse goes back there too ({@link #leftToClub}); {@code /hcm leave} always goes home.
 *
 * <p>Every fallback is {@link ClubRoute}'s: with the Clubhouse off, a course in another world, the restart
 * hold (the start) or the Clubhouse closing for it (the end), a rider, a test run or another session, the
 * run starts and ends exactly as before.
 */
public final class ClubSolo {

    private ClubSolo() {
    }

    /**
     * {@code TimeTrials.begin}'s Clubhouse branch: {@code null} when the Clubhouse has no part in this start
     * (it starts as before), else whether the player is on their way (handed out to the start, or into the
     * Clubhouse first). Told why when false.
     */
    static Boolean begin(TimeTrials t, Player p, Course c, boolean test) {
        ClubDoor club = test ? null : Clubhouse.door(t.games());
        UUID id = p.getUniqueId();
        boolean rider = t.riders().ofDriver(id) != null || t.riders().isRider(id);
        switch (ClubRoute.start(club, id, c.world(), test, rider, t.sessions().session(p) != null,
                t.games().restartHeld() != null)) {
            case HAND_OUT -> {
                Refusal r = seat(club, p, t, c.id(), c.world(), () -> t.sessions().entryRefusal(t),
                        q -> t.readyFromClub(q, c));
                if (r != null) {
                    t.games().tell(p, r);
                    return false;
                }
                return true;
            }
            case LOBBY -> {
                boolean warm = c.id().equalsIgnoreCase(t.warmups().wanted(id));
                Boolean in = Clubhouse.lobby(t.games(), p, new ClubNext(TimeTrials.SPEC.id(), c.id(), c.name(), warm));
                if (Boolean.TRUE.equals(in)) {
                    t.warmups().forget(p); // carried in the trip, wanted again on the way out
                }
                return in; // null: the Clubhouse closed meanwhile, straight to the start
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The hand-out from the Clubhouse to a course's start: {@code null} when they are on their way, else why
     * not (they stay in the Clubhouse). Nothing is taken or moved before the entry check ({@code entry}: the
     * game closed, the restart hold) passes; a start that can't be reached ({@code ready} false) hands them
     * back.
     */
    static Refusal seat(ClubDoor club, Player p, Game trials, String courseId, String courseWorld,
                        Supplier<Refusal> entry, Predicate<Player> ready) {
        if (!ClubRoute.sameWorld(club, courseWorld)) {
            return Refusal.of(ClubhouseText.OTHER_WORLD);
        }
        Refusal closed = entry.get();
        if (closed != null) {
            return closed;
        }
        if (!club.handOut(p, trials, courseId)) {
            return Refusal.of("Couldn't take you from the Clubhouse right now.");
        }
        if (!ready.test(p)) {
            club.handBack(p, ClubVisits.Kind.VISIT);
            return Refusal.of(ClubhouseText.NO_START);
        }
        return null;
    }

    /**
     * A solo finish: back to the Clubhouse with the result screen there (and, 0.38.4, the NEW RECORD line when the
     * run set the course's all-time record); false = home, as before.
     */
    static boolean back(TimeTrials t, Player p, TrialRun run, TimeTrials.Result result) {
        UUID id = p.getUniqueId();
        return back(Clubhouse.door(t.games()), p, p.getWorld().getName(), run.test, t.riders().ofDriver(id) != null,
                () -> t.end(p), ClubhouseText.backSolo(result.courseName(), TrialText.time(result.ms())),
                q -> new ResultMenu(t.plugin(), t, result, q).open(q), recordLine(p, result));
    }

    /** 0.38.4: the Clubhouse's "<name> set a new record on <course>: <time>" for a counted record, else null. */
    static String recordLine(Player p, TimeTrials.Result result) {
        return result.record() && result.counted()
                ? ClubhouseText.newRecordLine(p.getName(), result.courseName(), TrialText.time(result.ms())) : null;
    }

    /**
     * {@link #back(TimeTrials, Player, TrialRun, TimeTrials.Result)} with the server's parts passed in: false
     * (nothing touched) sends them home as before. While the Clubhouse is closing for the restart they read
     * why they go home. The results are theirs only: never the board or the shared Results.
     */
    static boolean back(ClubDoor club, Player p, String world, boolean test, boolean rider, Runnable forget,
                        String line, Consumer<Player> opener) {
        return back(club, p, world, test, rider, forget, line, opener, null);
    }

    /** {@link #back(ClubDoor, Player, String, boolean, boolean, Runnable, String, Consumer)} with the record line (0.38.4). */
    static boolean back(ClubDoor club, Player p, String world, boolean test, boolean rider, Runnable forget,
                        String line, Consumer<Player> opener, String recordLine) {
        if (!ClubRoute.after(club, world, test, rider)) {
            return false;
        }
        if (club.closingForRestart()) {
            p.sendMessage(Text.of(ClubhouseText.HOLD_NOT_TAKEN));
            return false; // home, as with no Clubhouse
        }
        forget.run(); // the run, its boat, a dropper's state: the session's end won't (it is handed over)
        if (!club.takeIn(p, ClubVisits.Kind.VISIT, line)) {
            return false;
        }
        club.soloResult(p, opener, recordLine);
        return true;
    }

    /** Leave game partway through a run that was handed out from the Clubhouse: back there (false: home). */
    static boolean leftToClub(TimeTrials t, Player p, TrialRun run) {
        UUID id = p.getUniqueId();
        return run.race == null && run.phase != TrialRun.Phase.DONE && leftToClub(Clubhouse.door(t.games()), p,
                p.getWorld().getName(), run.fromClub, run.test, t.riders().ofDriver(id) != null, () -> t.end(p),
                ClubhouseText.LEFT_RUN);
    }

    /** {@link #leftToClub(TimeTrials, Player, TrialRun)} with the server's parts passed in. */
    static boolean leftToClub(ClubDoor club, Player p, String world, boolean fromClub, boolean test, boolean rider,
                              Runnable forget, String line) {
        if (!ClubRoute.leaveBack(club, world, fromClub, test, rider)) {
            return false;
        }
        forget.run();
        return club.takeIn(p, ClubVisits.Kind.VISIT, line);
    }

    /** Whether the player is in the Clubhouse now, where Play again hands them out (the result screen lights it). */
    static boolean inClubhouse(GamesService games, UUID player) {
        try {
            ClubDoor club = Clubhouse.door(games);
            return club != null && club.seatable(player);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
