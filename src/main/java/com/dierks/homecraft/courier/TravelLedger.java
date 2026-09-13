package com.dierks.homecraft.courier;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a courier actually travelled, read from vanilla movement statistics.
 *
 * <p>A job snapshots every movement statistic when it is accepted and diffs them at
 * turn-in. The multiplier is then a <b>weighted blend by fraction of centimetres moved</b>
 * in each group — never a total. That distinction is the whole mechanism: a player who
 * walks the route once and one who walks it four times both score 1.00, so there is
 * nothing to gain by padding the trip, and someone who rides halfway and walks the rest
 * lands between the two multipliers rather than at whichever they touched last.
 *
 * <p>Vanilla's movement accounting is mutually exclusive — swimming, walking on water,
 * climbing, sprinting, crouching, walking, elytra and creative flight each increment
 * exactly one counter per tick — so the fractions are a real partition and cannot sum to
 * more than the distance travelled. {@code FALL_ONE_CM} is deliberately in no group:
 * falling is not travelling, and the denominator is the sum of the grouped statistics
 * only, so an ungrouped counter can never dilute a multiplier.
 */
public final class TravelLedger {

    /** Centimetres per block, the unit vanilla counts distance in. */
    public static final int CM_PER_BLOCK = 100;

    /** One way of covering ground, and the statistics that mean it. */
    public enum TravelGroup {
        FOOT("foot"),
        MOUNT("mount"),
        BOAT("boat"),
        RAIL("rail"),
        ELYTRA("elytra"),
        GHAST("ghast"),
        CREATIVE("creative");

        private final String configKey;

        TravelGroup(String configKey) {
            this.configKey = configKey;
        }

        /** The key this group is tuned under in {@code courier.travel}. */
        public String configKey() {
            return configKey;
        }
    }

    /**
     * The statistics behind each group.
     *
     * <p>{@code HAPPY_GHAST_ONE_CM} and {@code NAUTILUS_ONE_CM} are resolved <b>by name</b>
     * rather than referenced directly, because they are recent additions and this plugin is
     * built against a pinned API that may predate them. A server without one simply does not
     * count it; a server with one gets it for free, and neither case needs a code change.
     * Everything else is a long-standing constant and is referenced normally so the compiler
     * checks it.
     */
    private static final Map<TravelGroup, List<Statistic>> STATS = buildStats();

    /** The result of diffing a snapshot: how the fee should scale, and how far they went. */
    public record Blend(double multiplier, long centimetres) {
        /** Distance actually covered, in blocks. */
        public double blocks() {
            return centimetres / (double) CM_PER_BLOCK;
        }
    }

    private TravelLedger() {
    }

    /** Every group's current lifetime total for a player, in {@link TravelGroup} order. */
    public static long[] snapshot(Player player) {
        TravelGroup[] groups = TravelGroup.values();
        long[] out = new long[groups.length];
        if (player == null) {
            return out;
        }
        for (int i = 0; i < groups.length; i++) {
            long sum = 0;
            for (Statistic s : STATS.getOrDefault(groups[i], List.of())) {
                try {
                    sum += Math.max(0, player.getStatistic(s));
                } catch (RuntimeException ignored) {
                    // a statistic this server will not key is simply not counted
                }
            }
            out[i] = sum;
        }
        return out;
    }

    /**
     * Blend the multipliers by how far the player moved in each group since {@code taken}.
     *
     * @param multipliers per-group weights from {@code courier.travel}; a missing group scores 0
     * @return the blended multiplier and the total distance tracked
     */
    public static Blend since(Player player, long[] taken, Map<TravelGroup, Double> multipliers) {
        long[] now = snapshot(player);
        long total = 0;
        double weighted = 0;
        TravelGroup[] groups = TravelGroup.values();
        for (int i = 0; i < groups.length && i < now.length; i++) {
            long before = taken != null && i < taken.length ? taken[i] : 0;
            long delta = Math.max(0, now[i] - before);
            total += delta;
            weighted += delta * multipliers.getOrDefault(groups[i], 0.0);
        }
        // No tracked movement at all means there is no blend to compute. Returning 0 rather
        // than 1 matters: a player who arrived without moving should not be handed the
        // on-foot rate by default.
        return total <= 0 ? new Blend(0.0, 0) : new Blend(weighted / total, total);
    }

    /** Groups the player actually moved in since {@code taken}, for a debug line. */
    public static String describe(Player player, long[] taken) {
        long[] now = snapshot(player);
        TravelGroup[] groups = TravelGroup.values();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < groups.length && i < now.length; i++) {
            long before = taken != null && i < taken.length ? taken[i] : 0;
            long delta = Math.max(0, now[i] - before);
            if (delta > 0) {
                parts.add(groups[i].configKey() + "=" + (delta / CM_PER_BLOCK) + "b");
            }
        }
        return parts.isEmpty() ? "no tracked movement" : String.join(" ", parts);
    }

    /** Serialise a snapshot for the job row: one number per group, in order. */
    public static String encode(long[] snapshot) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snapshot.length; i++) {
            sb.append(i == 0 ? "" : ",").append(Math.max(0, snapshot[i]));
        }
        return sb.toString();
    }

    /**
     * Read a snapshot back. A row written by an older build with fewer groups, or one that is
     * unreadable, yields zeros for what is missing — which reads as "moved a very long way"
     * rather than "teleported", so a storage change can never quietly dock someone's pay.
     */
    public static long[] decode(String encoded) {
        long[] out = new long[TravelGroup.values().length];
        if (encoded == null || encoded.isBlank()) {
            return out;
        }
        String[] parts = encoded.split(",");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try {
                out[i] = Math.max(0, Long.parseLong(parts[i].trim()));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static Map<TravelGroup, List<Statistic>> buildStats() {
        Map<TravelGroup, List<Statistic>> map = new EnumMap<>(TravelGroup.class);
        map.put(TravelGroup.FOOT, List.of(
                Statistic.WALK_ONE_CM,
                Statistic.SPRINT_ONE_CM,
                Statistic.CROUCH_ONE_CM,
                Statistic.SWIM_ONE_CM,
                Statistic.WALK_ON_WATER_ONE_CM,
                Statistic.WALK_UNDER_WATER_ONE_CM,
                Statistic.CLIMB_ONE_CM));
        // A Nautilus is a tameable, saddled, armoured mount — a Strider for water, not a
        // boat — so it is weighted with the mounts rather than with BOAT_ONE_CM.
        map.put(TravelGroup.MOUNT, withOptional(
                List.of(Statistic.HORSE_ONE_CM, Statistic.STRIDER_ONE_CM, Statistic.PIG_ONE_CM),
                "NAUTILUS_ONE_CM"));
        map.put(TravelGroup.BOAT, List.of(Statistic.BOAT_ONE_CM));
        map.put(TravelGroup.RAIL, List.of(Statistic.MINECART_ONE_CM));
        map.put(TravelGroup.ELYTRA, List.of(Statistic.AVIATE_ONE_CM));
        map.put(TravelGroup.GHAST, withOptional(List.of(), "HAPPY_GHAST_ONE_CM"));
        map.put(TravelGroup.CREATIVE, List.of(Statistic.FLY_ONE_CM));
        return map;
    }

    private static List<Statistic> withOptional(List<Statistic> base, String... names) {
        List<Statistic> out = new ArrayList<>(base);
        for (String name : names) {
            try {
                out.add(Statistic.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // not in this API version — nothing to count, and nothing to fix
            }
        }
        return List.copyOf(out);
    }
}
