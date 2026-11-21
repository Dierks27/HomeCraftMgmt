package com.dierks.homecraft.games.chance.slots;

/** Test access to the slot machines' package-private helpers. */
public final class SlotMachineTestAccess {
    private SlotMachineTestAccess() {
    }

    public static String tokenBlocker(int stake, int balance, int playsLeft, int tokensIn, int limit) {
        return SlotMachine.tokenBlocker(stake, balance, playsLeft, tokensIn, limit);
    }
}
