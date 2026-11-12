package com.dierks.homecraft.graves;

import java.util.UUID;

/**
 * The grave's decisions, pure so each is tested without a server ({@code GraveRulesTest}).
 */
public final class GraveRules {

    private GraveRules() {
    }

    /** Why a death makes no grave, or {@link #MAKE} / {@link #MAKE_FROM_INVENTORY} when it does. */
    public enum Decision {
        /** A grave is made from the drops (if there is anything to put in it). */
        MAKE,
        /**
         * A keep-inventory death (the gamerule, Essentials) of a player without {@code hcm.graves.keep}, with
         * {@code graves.keep_inventory_makes_grave}: the grave is made from the inventory itself, which the death then
         * doesn't keep. They still walk back for their things.
         */
        MAKE_FROM_INVENTORY,
        /** The player has {@code hcm.graves.keep}: they keep everything, items and levels, and no grave is made. */
        KEPT,
        /** {@code graves.enabled} is false: the death goes as it did before graves. */
        OFF,
        /** The world is in {@code graves.disabled_worlds}. */
        WORLD_OFF,
        /** The death was cancelled (a game's revive, another plugin). */
        CANCELLED,
        /**
         * The event keeps the inventory (the {@code keep_inventory} gamerule, or another plugin) and
         * {@code graves.keep_inventory_makes_grave} is false.
         */
        KEEP_INVENTORY,
        /** A death inside a game session: the Games handle it (KitGuardListener, SessionCore). */
        GAME_SESSION
    }

    /**
     * Whether a death makes a grave, and from what.
     *
     * @param keeps                  the player has {@code hcm.graves.keep}: they keep everything, never a grave
     * @param keepInventory          the event keeps the inventory (the gamerule, Essentials' keepinv)
     * @param keepInventoryMakesGrave {@code graves.keep_inventory_makes_grave}: such a death still makes a grave,
     *                               from the inventory
     */
    public static Decision decide(boolean enabled, boolean worldDisabled, boolean cancelled, boolean keepInventory,
                                  boolean inGameSession, boolean keeps, boolean keepInventoryMakesGrave) {
        if (!enabled) {
            return Decision.OFF;
        }
        if (cancelled) {
            return Decision.CANCELLED;
        }
        if (inGameSession) {
            return Decision.GAME_SESSION;
        }
        if (keeps) {
            return Decision.KEPT;
        }
        if (worldDisabled) {
            return Decision.WORLD_OFF;
        }
        if (keepInventory) {
            return keepInventoryMakesGrave ? Decision.MAKE_FROM_INVENTORY : Decision.KEEP_INVENTORY;
        }
        return Decision.MAKE;
    }

    /**
     * Whether a player with {@code hcm.graves.keep} keeps everything at this death (set early, at LOWEST, so every
     * later death listener sees a keep-inventory death): graves on, and not a game session (the Games handle those).
     */
    public static boolean keepsEverything(boolean enabled, boolean inGameSession, boolean keeps) {
        return enabled && keeps && !inGameSession;
    }

    /** Nothing to keep: no grave and no message. */
    public static boolean empty(int itemStacks, int xp) {
        return itemStacks <= 0 && xp <= 0;
    }

    /**
     * The experience points put in a grave: {@code percent} of everything the player had, none when the death keeps
     * their level (another plugin's keep-level, the gamerule).
     */
    public static int xpToStore(int totalXp, int percent, boolean keepLevel) {
        if (keepLevel || totalXp <= 0 || percent <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) totalXp * Math.min(100, percent) / 100L);
    }

    /** The experience points it takes to reach {@code level} from zero (the game's own curve). */
    public static long xpToReach(int level) {
        long l = Math.max(0, level);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            return l * (5 * l - 81) / 2 + 360; // 2.5L^2 - 40.5L + 360, exactly (the product is always even)
        }
        return l * (9 * l - 325) / 2 + 2220; // 4.5L^2 - 162.5L + 2220
    }

    /** The experience points from {@code level} to the next one. */
    public static int xpForNext(int level) {
        int l = Math.max(0, level);
        if (l <= 15) {
            return 2 * l + 7;
        }
        if (l <= 30) {
            return 5 * l - 38;
        }
        return 9 * l - 158;
    }

    /** Every experience point a player holds: their level, plus how far into the next one ({@code 0..1}). */
    public static int totalXp(int level, float progress) {
        float p = Float.isNaN(progress) ? 0f : Math.max(0f, Math.min(1f, progress));
        long total = xpToReach(level) + Math.round((double) p * xpForNext(level));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Whether {@code opener} may open the grave: its owner, an admin, or anyone once {@code unlockMinutes}
     * (0 = never) have passed since the death.
     */
    public static boolean canOpen(UUID owner, UUID opener, boolean admin, long createdAt, long now,
                                  int unlockMinutes) {
        if (admin || (owner != null && owner.equals(opener))) {
            return true;
        }
        return unlockMinutes > 0 && now - createdAt >= unlockMinutes * 60_000L;
    }

    /** Milliseconds until anyone may open it, or -1 when it never unlocks (or already has). */
    public static long unlocksIn(long createdAt, long now, int unlockMinutes) {
        if (unlockMinutes <= 0) {
            return -1;
        }
        long left = createdAt + unlockMinutes * 60_000L - now;
        return left > 0 ? left : -1;
    }

    /** Whether a grave made at {@code createdAt} has expired ({@code hours} 0 = never). */
    public static boolean expired(long createdAt, long now, int hours) {
        return hours > 0 && now - createdAt >= hours * 3_600_000L;
    }

    /** "just now", "5m", "2h 3m", "3d 4h": how long ago, for the name tag and the lists. */
    public static String age(long ms) {
        long m = Math.max(0, ms) / 60_000L;
        if (m < 1) {
            return "just now";
        }
        if (m < 60) {
            return m + "m";
        }
        long h = m / 60;
        if (h < 24) {
            return h + "h " + (m % 60) + "m";
        }
        return (h / 24) + "d " + (h % 24) + "h";
    }
}
