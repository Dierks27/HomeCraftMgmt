package com.dierks.homecraft.courier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.UUID;

/**
 * One delivery run, exactly as it was agreed.
 *
 * <p>Everything that decides the <b>travel fee</b> is locked at acceptance — the clamped
 * distance, the band, and the player's movement statistics at that moment — so walking a
 * longer way round earns nothing extra and the fee cannot be re-priced by anything that
 * happens on the way. The cargo is the deliberate exception: its acceptance quote is recorded
 * here for the board to show, but a trade run is paid at the <b>live</b> market rate when it
 * arrives, because paying the stale quote would mint the difference.
 *
 * @param lockedDistance  horizontal straight line accept → waypoint, in blocks, already clamped
 * @param lockedCargoValue the cargo's quote at acceptance — displayed, never paid; 0 for a courier run
 * @param statSnapshot    {@link TravelLedger#encode} of the movement counters at acceptance
 * @param day             UTC epoch-day of acceptance, for the per-band daily cap
 */
public record CourierJob(
        long id, UUID player, Type type, State state, Band band,
        String world, int acceptX, int acceptY, int acceptZ,
        int wayX, int wayY, int wayZ,
        int lockedDistance,
        String cargoMaterial, int cargoAmount, double lockedCargoValue,
        String statSnapshot,
        long day, long acceptedAt, long expiresAt) {

    /** What kind of run this is. */
    public enum Type {
        /**
         * A free crate from the board: the payout is the travel fee and nothing else. The
         * grind-free path, and deliberately the smaller number — it is pure faucet, with no
         * goods moving into the market to balance it.
         */
        COURIER,
        /**
         * The player supplies the cargo. It is sold into the market on arrival at the
         * <b>live</b> rate — stock goes +N and the daily limits apply exactly as if they had
         * sold at home — and the travel fee is a bonus on top. The value quoted at acceptance
         * is recorded and shown, but never paid: paying it would mint the difference, and
         * only the fee half is meant to be new money.
         */
        TRADE_RUN;

        public static Type parse(String s) {
            try {
                return valueOf(String.valueOf(s).trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return COURIER;
            }
        }
    }

    /** Where the job is in its life. */
    public enum State {
        /** Accepted and being run. Holds a band slot for the day. */
        ACTIVE,
        /** Turned in and paid. Keeps its band slot spent. */
        DELIVERED,
        /** Ran out of time or was abandoned. Releases its band slot. */
        EXPIRED;

        public static State parse(String s) {
            try {
                return valueOf(String.valueOf(s).trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return EXPIRED;
            }
        }
    }

    /** The distance bracket, which sets both the range offered and the per-day cap. */
    public enum Band {
        LOCAL("local"), REGIONAL("regional"), LONG_HAUL("long_haul");

        private final String configKey;

        Band(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }

        /** The label a player sees. */
        public String display() {
            return switch (this) {
                case LOCAL -> "Local";
                case REGIONAL -> "Regional";
                case LONG_HAUL -> "Long haul";
            };
        }

        public static Band parse(String s) {
            for (Band b : values()) {
                if (b.name().equalsIgnoreCase(String.valueOf(s).trim())
                        || b.configKey.equalsIgnoreCase(String.valueOf(s).trim())) {
                    return b;
                }
            }
            return LOCAL;
        }
    }

    /** True once this job can no longer be turned in. */
    public boolean expired(long now) {
        return state != State.ACTIVE || now >= expiresAt;
    }

    /** The waypoint, or null if its world is gone (an admin removed it mid-job). */
    public Location waypoint() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, wayX + 0.5, wayY, wayZ + 0.5);
    }

    /** The movement counters as they stood when this job was accepted. */
    public long[] snapshot() {
        return TravelLedger.decode(statSnapshot);
    }
}
