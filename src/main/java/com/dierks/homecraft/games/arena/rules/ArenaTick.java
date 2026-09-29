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
 * <p>The floors of a round are made at its Go from the layout in force then, so a new week's
 * layout ({@link #layout(FloorLayout)}) never changes a round already going.
 */
public final class ArenaTick {

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
     * @param feet   where the round's players' feet are (anyone missing is skipped this tick)
     * @param voided players the void took this tick (the game's {@code onVoid}): out, whatever their feet say
     * @param hold   the restart hold
     */
    public Output tick(Map<UUID, Feet> feet, Set<UUID> voided, boolean hold) {
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
            List<Feet> standing = new ArrayList<>(alive.size());
            for (UUID p : alive) {
                Feet f = feet == null ? null : feet.get(p);
                if (f != null) {
                    standing.add(f);
                }
            }
            writes = floors.step(round.playTicks(), standing);
            for (UUID p : alive) {
                Feet f = feet == null ? null : feet.get(p);
                if ((voided != null && voided.contains(p)) || floors.isOut(f)) {
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
