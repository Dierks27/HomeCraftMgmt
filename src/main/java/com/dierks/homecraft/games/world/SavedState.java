package com.dierks.homecraft.games.world;

import java.util.UUID;

/**
 * Everything about a player that a world game changes, saved BEFORE it changes anything
 * ({@code game_saved_state}, spec §7.3, R2.2-R2.5, R3.7).
 *
 * <p>Players' state is sacred: entering a game clears the inventory, effects, XP, health, hunger
 * and game mode and moves the player; every way out (finish, leave, quit, death, a foreign
 * teleport, a server stop or a crash) puts back exactly what is here, by OVERWRITING — never by
 * adding — so a restore that runs twice cannot duplicate anything. One row per session: written
 * before the first change, marked DONE only after the player is back where they were (and kept a
 * week for support); at most one live row per player.
 *
 * <p>A plain record with no Bukkit types, so the storage and the codec can be tested without a
 * server; the adapter that captures and applies it is separate. {@code items} is the whole
 * {@code PlayerInventory#getContents()} (storage, armour, off-hand, empty slots kept) as one blob
 * from {@code ItemStack.serializeItemsAsBytes}; {@code carry} holds stacks that turned up during
 * the game and could not be handed back yet. The two byte arrays make {@code equals} identity-
 * based for them; compare fields.
 *
 * @param phase        {@link #ACTIVE}, {@link #RETURN} or {@link #DONE}
 * @param sessionId    a random id per session: every later write is guarded by it
 * @param sessionWorld the world the session runs in (restores apply only there, R2.4)
 * @param world        where the player came from, with {@code x}..{@code pitch}
 * @param effects      the potion-effect text codec ({@code key|amplifier|duration|ambient|particles|icon} per line)
 * @param doneAt       when it reached DONE, or {@code null}
 */
public record SavedState(UUID player, String gameId, String ref, String phase, String sessionId, String sessionWorld,
                         byte[] items, byte[] carry,
                         int xpLevel, float xpProgress, int xpTotal,
                         double health, int food, float saturation, float exhaustion, int fireTicks, int air,
                         String gameMode, boolean allowFlight, boolean flying,
                         float walkSpeed, float flySpeed, double absorption,
                         String effects, String world, double x, double y, double z, float yaw, float pitch,
                         long createdAt, Long doneAt) {

    /** In a game: on the next join after a crash, restore it. */
    public static final String ACTIVE = "ACTIVE";
    /** Restored already: only send the player back to where they came from, never apply again. */
    public static final String RETURN = "RETURN";
    /** Finished; kept a week for support, then pruned. */
    public static final String DONE = "DONE";
}
