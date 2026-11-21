package com.dierks.homecraft.games.cpu;

/**
 * A world game's NPC players (stage 2: "any of the multiplayer games like Rings or racing parkour"): the small class
 * each world game keeps beside itself, fed by the game's public state, that brings NPC players into it as mannequins
 * and moves them. The game itself never tracks one as a player: no NPC is ever in its rounds, its sessions or its
 * boards, and nothing here writes a block.
 *
 * <p>{@link CpuPlayers} runs every one once a tick inside its own guard, so a bug in an NPC switches the NPC players
 * off, never the game; and takes them all down when NPCs go off or the game stops.
 */
public interface WorldNpcs {

    /** Once a tick while NPC players and the game are both on. */
    void tick(CpuPlayers npcs, long tick);

    /** Every NPC out of the game and every body gone (NPCs off, the game stopping, trouble). */
    void clear();

    /** One line for the admins' status. */
    String statusLine();

    /** A world game that keeps NPC players beside it. */
    interface Host {

        /** Its NPC players, or {@code null} while it isn't running. */
        WorldNpcs npcs();
    }
}
