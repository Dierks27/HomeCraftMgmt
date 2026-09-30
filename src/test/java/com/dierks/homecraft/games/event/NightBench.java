package com.dierks.homecraft.games.event;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Race Night's package-private parts for the cross-feature journeys: a boot's {@link RaceNight#recover}
 * over a test's {@link EventDao} (a bench RaceNight has no plugin to open the database from), and a
 * {@link PayLoop} whose payer finds the bench's players online (RaceNight's own asks {@code Bukkit},
 * which a bench has none of, so to it everyone is offline and owed).
 */
public final class NightBench {

    private NightBench() {
    }

    /** What a boot does ({@code RaceNight.start}'s {@code recover()}): over {@code dao}. */
    public static void recover(RaceNight night, EventDao dao) {
        night.dao(dao);
        night.recover();
    }

    /**
     * {@code RaceNight.payLoop}'s payer over the bench: EVENT_PRIZE through the real rewards, once per
     * ref, to whoever {@code online} finds; paid() is the rewards table's own record.
     */
    public static PayLoop payLoop(GamesService games, EventDao dao, Function<UUID, Player> online, LongSupplier clock) {
        Game night = games.game(RaceNight.SPEC.id());
        return new PayLoop(dao, new PayLoop.Payer() {
            @Override
            public int pay(UUID player, String ref, int tokens, String detail) {
                Player p = online.apply(player);
                if (p == null || !games.rewards().canEarnHere(p)) {
                    return -1;
                }
                int paid = games.rewards().pay(p, night, TokenService.Source.GAMES_RACE_NIGHT, RewardKind.EVENT_PRIZE,
                        ref, tokens, -1, detail);
                return paid > 0 ? paid : paid(player, ref) ? 0 : -1;
            }

            @Override
            public boolean paid(UUID player, String ref) {
                try {
                    return games.dao().rewardPaid(player, RaceNight.SPEC.id(), RewardKind.EVENT_PRIZE, ref);
                } catch (SQLException e) {
                    return false;
                }
            }
        }, clock, Logger.getAnonymousLogger());
    }

    /** RaceNight's own pay loop (its payer asks the server who is online). */
    public static PayLoop ownPayLoop(RaceNight night, EventDao dao) {
        night.dao(dao);
        return night.payLoop();
    }
}
