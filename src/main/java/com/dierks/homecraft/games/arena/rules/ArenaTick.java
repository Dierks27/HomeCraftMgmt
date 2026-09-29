package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One server tick of Falling Floors, the same for the real game and the simulation: the floors
 * react to where everyone's feet are, anyone below the bottom is out, then the round moves on.
 *
 * <p>Why one driver: the order matters (the floors see this tick's feet before the round settles
 * this tick's outs, so a player's time is the tick they fell, not the tick after), and if the game
 * and {@link ArenaSim} each wrote their own loop, the tests would prove a loop the server never
 * runs. {@code FallingFloors} calls {@link #tick} once a tick with the players' feet and writes
 * what comes back; the simulation does the same with fake players.
 *
 * <p>Why every position and not one a tick: a player holding jump touches the floor for exactly one
 * client tick before jumping again, and when two of their movement packets are handled in one
 * server tick (lag, jitter), reading {@code getLocation()} once a tick sees only the take-off. So
 * the game hands over every position the server accepted from each player this tick, in order,
 * and each one marks the floor; the last one is where they are now, and decides whether they are
 * out.
 *
 * <p>Why a backstop: "every round ends" must not depend on the wiring being perfect. Sudden death
 * clears every floor by {@link FloorRules#goneBy()}; anyone still in {@link #FALL_TICKS} after that
 * (their feet never came, or never dropped below {@code out_y}) is out then, together, so the
 * arena can never be held by a round that doesn't end.
 *
 * <p>The floors of a round are made at its Go from the layout in force then, so a new week's
 * layout ({@link #layout(FloorLayout)}) never changes a round already going.
 */
public final class ArenaTick {

    /**
     * How long after the last floor cell is gone anyone still in is out anyway: 5 s. A fall from the
     * top floor to below {@code out_y} takes about 1.3 s, so only a player the game isn't seeing
     * (or one hovering) is ever out this way.
     */
    public static final int FALL_TICKS = 5 * RoundSettings.TICKS_PER_SECOND;

    /**
     * What one tick did.
     *
     * @param writes the blocks to write, in order (through {@code FloorWriter})
     * @param events what happened, in order
     */
    public record Output(List<FloorWrite> writes, List<RoundEvent> events) {
        public Output {
            writes = List.copyOf(writes);
            events = List.copyOf(events);
        }
    }

    private final ArenaRound round;
    private final RoundSettings settings;
    private FloorLayout layout;
    private FloorLayout next;
    private FloorRules floors;
    private int floorsRound = -1;

    public ArenaTick(ArenaRound round, FloorLayout layout, RoundSettings settings) {
        if (round == null || layout == null) {
            throw new IllegalArgumentException("a tick needs the round and the week's floors");
        }
        this.round = round;
        this.layout = layout;
        this.settings = settings == null ? round.settings() : settings;
        round.spawns(layout.spawns().size());
    }

    /**
     * The week's new floors: in force from the next tick no round is going (the wiring also calls
     * {@link ArenaRound#requestReset()} so the box is rebuilt to them).
     */
    public void layout(FloorLayout nextLayout) {
        if (nextLayout == null) {
            throw new IllegalArgumentException("no layout");
        }
        this.next = nextLayout;
    }

    /**
     * One tick.
     *
     * @param feet   every position the server accepted from each of the round's players since the
     *               last tick, oldest first (each with its own {@code vy}); the last is where they are
     *               now. Anyone missing, or with an empty list, is skipped this tick.
     * @param voided players the void took this tick (the game's {@code onVoid}): out, whatever their feet say
     * @param hold   the restart hold
     */
    public Output tick(Map<UUID, List<Feet>> feet, Set<UUID> voided, boolean hold) {
        if (next != null && !round.phase().inRound()) {
            layout = next;
            next = null;
            round.spawns(layout.spawns().size());
        }
        List<FloorWrite> writes = List.of();
        if (round.phase() == ArenaRound.Phase.PLAYING) {
            if (floors == null || floorsRound != round.roundNo()) {
                floors = FloorRules.of(layout, settings);
                floorsRound = round.roundNo();
            }
            List<UUID> alive = round.alive();
            List<Feet> steps = new ArrayList<>(alive.size());
            for (UUID p : alive) {
                for (Feet f : samples(feet, p)) {
                    if (f != null) {
                        steps.add(f);
                    }
                }
            }
            writes = floors.step(round.playTicks(), steps);
            boolean backstop = round.playTicks() >= floors.goneBy() + FALL_TICKS;
            for (UUID p : alive) {
                if (backstop || (voided != null && voided.contains(p)) || floors.isOut(now(feet, p))) {
                    round.out(p, OutReason.FELL);
                }
            }
        }
        round.tick(hold);
        if (round.phase() != ArenaRound.Phase.PLAYING) {
            floors = null;
            floorsRound = -1;
        }
        return new Output(writes, round.drain());
    }

    private static List<Feet> samples(Map<UUID, List<Feet>> feet, UUID p) {
        List<Feet> l = feet == null ? null : feet.get(p);
        return l == null ? List.of() : l;
    }

    /** Where a player is now: the last position given this tick, or {@code null}. */
    private static Feet now(Map<UUID, List<Feet>> feet, UUID p) {
        List<Feet> l = samples(feet, p);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** The round. */
    public ArenaRound round() {
        return round;
    }

    /** The floors of the round being played, or {@code null} between rounds. */
    public FloorRules floors() {
        return floors;
    }

    /** The layout rounds start on now (the spawns of a {@link RoundEvent.TeleportTo} are its). */
    public FloorLayout layout() {
        return layout;
    }
}
