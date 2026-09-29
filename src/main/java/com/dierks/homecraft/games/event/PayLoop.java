package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.storage.EventDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paying Race Night's prizes exactly once (EVENTS-DROPPER-SPEC §A.3, §A.9).
 *
 * <p>A prize is paid as {@code RewardKind.EVENT_PRIZE} with the ref {@code event:<id>}, which the
 * rewards table refuses twice: the payment itself is once-only. {@code paid_at} on the entry is
 * only the record that it happened, written after. So a crash between the two leaves an entry that
 * looks unpaid, and the next pass tries again: the payment is refused (0), the ref is found paid,
 * and {@code paid_at} is set. Nobody is ever paid twice, and nobody's prize is lost.
 *
 * <p>A racer who is offline, or can't earn where they are (creative, a world without games), is
 * <b>owed</b>: the prize stays unpaid and is tried again when they join and every 5 minutes while
 * they are online, with a queued notice that explains.
 */
public final class PayLoop {

    /** What an owed racer reads. */
    public static final String WAITING = "&6Your Race Night prize is waiting &7- it's paid in a world where you can "
            + "earn tokens.";

    /** Who pays: the rewards service, or a test's. */
    public interface Payer {

        /**
         * Pay {@code tokens} under {@code ref}: what was paid; 0 when the ref was paid already; -1 when
         * the player can't be paid right now (offline, or somewhere they can't earn).
         */
        int pay(UUID player, String ref, int tokens, String detail);

        /** Whether the ref was already paid to the player. */
        boolean paid(UUID player, String ref);
    }

    private final EventDao dao;
    private final Payer payer;
    private final LongSupplier clock;
    private final Logger log;

    public PayLoop(EventDao dao, Payer payer, LongSupplier clock, Logger log) {
        this.dao = dao;
        this.payer = payer;
        this.clock = clock;
        this.log = log;
    }

    /** Pay a night's unpaid prizes to whoever can be paid now. @return how many are still owed */
    public int payNight(String eventId) {
        List<UUID> owed = payNightOwed(eventId);
        return owed == null ? -1 : owed.size();
    }

    /**
     * {@link #payNight}, saying who is still owed (each owed racer once), or {@code null} when the
     * prizes can't be read: the night tells exactly those racers their prize is waiting (fx2-C #6).
     */
    public List<UUID> payNightOwed(String eventId) {
        try {
            List<UUID> owed = new ArrayList<>();
            for (UUID u : payAll(dao.unpaid(eventId))) {
                if (!owed.contains(u)) {
                    owed.add(u);
                }
            }
            return owed;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Race Night: could not read the prizes of " + eventId, e);
            return null;
        }
    }

    /** Pay what the player is owed from past nights. @return how many are still owed */
    public int payOwed(UUID player) {
        try {
            return pay(dao.owed(player));
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Race Night: could not read a player's owed prizes", e);
            return -1;
        }
    }

    private int pay(List<EventDao.EntryRow> rows) {
        return payAll(rows).size();
    }

    /** Pay each row that can be paid now; the players of the rows still owed, one per row. */
    private List<UUID> payAll(List<EventDao.EntryRow> rows) {
        List<UUID> owed = new ArrayList<>();
        for (EventDao.EntryRow r : rows) {
            if (r.prize() <= 0 || r.paidAt() != null) {
                continue;
            }
            String ref = SkillRewards.eventRef(r.eventId());
            int paid = payer.pay(r.player(), ref, r.prize(), detail(r.place()));
            if (paid > 0 || (paid == 0 && payer.paid(r.player(), ref))) {
                try {
                    dao.markPaid(r.eventId(), r.player(), clock.getAsLong());
                } catch (SQLException e) {
                    log.log(Level.SEVERE, "Race Night: paid a prize but could not record it (the ref stops a second "
                            + "payment)", e);
                }
            } else {
                owed.add(r.player());
            }
        }
        return owed;
    }

    /**
     * The ledger line: "Race Night: 2nd place" for the podium (1st to 3rd), "Race Night: finished a
     * race" for the finisher's prize everyone else gets.
     */
    static String detail(Integer place) {
        return place == null ? "Race Night prize" : place > 3 ? "Race Night: finished a race"
                : "Race Night: " + NightStandings.ordinal(place) + " place";
    }
}
