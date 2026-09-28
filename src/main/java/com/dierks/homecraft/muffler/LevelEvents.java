package com.dierks.homecraft.muffler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Sounds that don't travel as sounds.
 *
 * <p>A few noisy blocks tell the player's game "a level event happened here" and leave the
 * noise to it: every dispenser and dropper click is one of these, and so are anvils, brewing
 * stands, grindstones, crafters and zombies banging on doors. A muffler that only looked at sound
 * packets would never hush a dispenser, which is the first thing anyone with a farm asks for.
 *
 * <p>Each entry names the sound the game plays for the event, and the category, volume and pitch
 * it plays it at, so "Quieter" can put back a quieter copy of it. Only events that are pure
 * sound are listed: one that also draws particles (a block breaking, bone meal, a composter
 * filling) would lose its particles if it were dropped, so those are left alone.
 */
public final class LevelEvents {

    /** One event and the sound the game plays for it. {@code category} is a SoundCategory name. */
    public record Entry(int id, String key, String category, float volume, float pitch) {
    }

    private static final Map<Integer, Entry> BY_ID;

    static {
        Map<Integer, Entry> m = new HashMap<>();
        put(m, 1000, "block.dispenser.dispense", "BLOCKS", 1.0f, 1.0f);
        put(m, 1001, "block.dispenser.fail", "BLOCKS", 1.0f, 1.2f);
        put(m, 1002, "block.dispenser.launch", "BLOCKS", 1.0f, 1.2f);
        put(m, 1004, "entity.firework_rocket.shoot", "NEUTRAL", 1.0f, 1.2f);
        put(m, 1015, "entity.ghast.warn", "HOSTILE", 10.0f, 1.0f);
        put(m, 1016, "entity.ghast.shoot", "HOSTILE", 10.0f, 1.0f);
        put(m, 1018, "entity.blaze.shoot", "HOSTILE", 2.0f, 1.0f);
        put(m, 1019, "entity.zombie.attack_wooden_door", "HOSTILE", 2.0f, 1.0f);
        put(m, 1020, "entity.zombie.attack_iron_door", "HOSTILE", 2.0f, 1.0f);
        put(m, 1021, "entity.zombie.break_wooden_door", "HOSTILE", 2.0f, 1.0f);
        put(m, 1025, "entity.bat.takeoff", "NEUTRAL", 0.05f, 1.0f);
        put(m, 1026, "entity.zombie.infect", "HOSTILE", 2.0f, 1.0f);
        put(m, 1027, "entity.zombie_villager.converted", "HOSTILE", 2.0f, 1.0f);
        put(m, 1029, "block.anvil.destroy", "BLOCKS", 1.0f, 1.0f);
        put(m, 1030, "block.anvil.use", "BLOCKS", 1.0f, 1.0f);
        put(m, 1031, "block.anvil.land", "BLOCKS", 0.3f, 1.0f);
        put(m, 1035, "block.brewing_stand.brew", "BLOCKS", 1.0f, 1.0f);
        put(m, 1042, "block.grindstone.use", "BLOCKS", 1.0f, 1.0f);
        put(m, 1044, "block.smithing_table.use", "BLOCKS", 1.0f, 1.0f);
        put(m, 1049, "block.crafter.craft", "BLOCKS", 1.0f, 1.0f);
        put(m, 1050, "block.crafter.fail", "BLOCKS", 1.0f, 1.0f);
        BY_ID = Collections.unmodifiableMap(m);
    }

    private LevelEvents() {
    }

    /** The sound behind an event id, or null for an event a muffler leaves alone. */
    public static Entry of(int id) {
        return BY_ID.get(id);
    }

    /** Every event understood, for tests. */
    static Map<Integer, Entry> all() {
        return BY_ID;
    }

    private static void put(Map<Integer, Entry> m, int id, String path, String category, float volume, float pitch) {
        m.put(id, new Entry(id, "minecraft:" + path, category, volume, pitch));
    }
}
