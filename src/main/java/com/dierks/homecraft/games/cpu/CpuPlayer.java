package com.dierks.homecraft.games.cpu;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One NPC player (the owner: "is there any way we can make CPU players? I am on the server all the time and its kind
 * of lonely"): a friendly invented name, a look (one of Minecraft's built-in skins), a small personality and a level.
 * Pure and fixed: the owner's own name or skin for one ({@code /hcm games npc name|skin}) is kept by
 * {@link CpuPlayers}, and the marker and the stars are added whatever the name ({@link CpuRoster#label}).
 *
 * <p>Its {@link #id} is a UUID made from its key, never a real account's: every game that keys anything by a player's
 * UUID can tell it apart ({@link CpuRoster#isCpu}), and nothing about it is ever written to a player's row.
 *
 * @param key         its short id ({@code pip}): what admins type, and where its looks are kept
 * @param name        its own name, as shipped
 * @param skin        its own built-in skin
 * @param personality how it plays by taste
 * @param level       how well it plays
 * @param favourite   its favourite Mob Races racer, by lane (a hunch picks it every time)
 */
public record CpuPlayer(String key, String name, NpcSkin.BuiltIn skin, CpuPersonality personality, CpuLevel level,
                        int favourite) {

    /** Its UUID: made from {@code "hcm-npc:" + key}, a version-3 UUID no Mojang or offline account can have. */
    public UUID id() {
        return idOf(key);
    }

    static UUID idOf(String key) {
        return UUID.nameUUIDFromBytes(("hcm-npc:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
