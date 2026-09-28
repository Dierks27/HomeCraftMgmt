package com.dierks.homecraft.muffler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One placed Sound Muffler and everything it has been told: on or off, how far it reaches, how
 * quiet "Quieter" is, and a level per group and per single sound. Immutable — every change makes
 * a new one, so the copy the packet threads are reading is never half-written.
 *
 * <p>It hushes sounds MADE inside its box, for everyone who hears them, wherever they stand: put
 * it by the chicken farm and the chickens are quiet from the house too. The box is every block
 * within {@code radius} of the muffler in each direction, the same shape a player builds a farm in.
 *
 * @param groups a {@link SoundGroups} id → QUIETER or SILENT (Normal is absent)
 * @param sounds a namespaced sound key → any level; ALLOW is the exception to a group
 */
public record Muffler(MufflerPos pos, UUID owner, boolean enabled, int radius, int quietPercent,
                      Map<String, MuffleLevel> groups, Map<String, MuffleLevel> sounds) {

    /** The smallest box: the muffler and one block around it. */
    public static final int MIN_RADIUS = 1;
    /** No config can reach further than this: a muffler is for a farm, not a town. */
    public static final int HARD_MAX_RADIUS = 32;
    /** The "Quieter" volumes a player can pick, loudest first. */
    public static final List<Integer> QUIET_STEPS = List.of(50, 25, 10);
    /** Single sounds one muffler remembers. A menu is no way to manage more than this. */
    public static final int MAX_PICKS = 200;
    /** The ranges the Range button steps through (capped by the server's maximum). */
    public static final List<Integer> RANGE_STEPS = List.of(1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24, 32);

    public Muffler {
        radius = Math.max(MIN_RADIUS, Math.min(HARD_MAX_RADIUS, radius));
        quietPercent = Math.max(1, Math.min(90, quietPercent));
        groups = frozen(groups);
        sounds = frozen(sounds);
    }

    /** A fresh muffler: on, nothing picked yet. */
    public static Muffler placed(MufflerPos pos, UUID owner, int radius, int quietPercent) {
        return new Muffler(pos, owner, true, radius, quietPercent, Map.of(), Map.of());
    }

    // ---- the box ----------------------------------------------------------------------------

    /** Whether a sound made at (x, y, z) is inside this muffler's box. */
    public boolean contains(double x, double y, double z) {
        return x >= pos.x() - radius && x < pos.x() + radius + 1
                && y >= pos.y() - radius && y < pos.y() + radius + 1
                && z >= pos.z() - radius && z < pos.z() + radius + 1;
    }

    /** Blocks along one side of the box. */
    public int boxSize() {
        return radius * 2 + 1;
    }

    /**
     * The next range up or down from {@code current}: the next {@link #RANGE_STEPS} value, with
     * {@code max} itself always reachable, never past it and never below {@link #MIN_RADIUS}.
     * Stepping past either end stays put.
     */
    public static int stepRadius(int current, int max, boolean up) {
        List<Integer> steps = new ArrayList<>();
        for (int s : RANGE_STEPS) {
            if (s < max) {
                steps.add(s);
            }
        }
        steps.add(Math.max(MIN_RADIUS, max));
        if (up) {
            for (int s : steps) {
                if (s > current) {
                    return s;
                }
            }
            return steps.get(steps.size() - 1);
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) < current) {
                return steps.get(i);
            }
        }
        return steps.get(0);
    }

    /** The next "Quieter" volume along {@link #QUIET_STEPS} (quieter), or back (louder); wraps around. */
    public static int stepQuiet(int current, boolean quieter) {
        int i = QUIET_STEPS.indexOf(current);
        if (i < 0) {
            return QUIET_STEPS.get(0);
        }
        int n = QUIET_STEPS.size();
        return QUIET_STEPS.get(Math.floorMod(i + (quieter ? 1 : -1), n));
    }

    /** "Quieter" as a volume multiplier. */
    public float quietFactor() {
        return quietPercent / 100f;
    }

    // ---- decisions --------------------------------------------------------------------------

    /**
     * What this muffler does to a sound: its single-sound pick if it has one (so a pick is the
     * exception to its group), otherwise the strongest level among the groups the sound is in.
     * Null for "leave it alone"; {@link MuffleLevel#ALLOW} means the same thing, on purpose.
     */
    public MuffleLevel levelFor(String key) {
        MuffleLevel exact = sounds.get(key);
        return exact != null ? exact : groupLevel(key);
    }

    /** The strongest level any of the sound's groups is set to, or null. */
    public MuffleLevel groupLevel(String key) {
        if (groups.isEmpty()) {
            return null;
        }
        MuffleLevel best = null;
        for (SoundGroups.SoundGroup g : SoundGroups.of(key)) {
            MuffleLevel l = groups.get(g.id());
            if (l != null && l.hushes() && (best == null || l.ordinal() > best.ordinal())) {
                best = l;
            }
        }
        return best;
    }

    /** The group of the sound that sets its level, for "Silent (from Chickens)". Null if none does. */
    public SoundGroups.SoundGroup decidingGroup(String key) {
        MuffleLevel level = groupLevel(key);
        if (level == null) {
            return null;
        }
        for (SoundGroups.SoundGroup g : SoundGroups.of(key)) {
            if (groups.get(g.id()) == level) {
                return g;
            }
        }
        return null;
    }

    public int ruleCount() {
        return groups.size() + sounds.size();
    }

    // ---- changes (each returns a new muffler) -------------------------------------------------

    public Muffler withEnabled(boolean on) {
        return new Muffler(pos, owner, on, radius, quietPercent, groups, sounds);
    }

    public Muffler withRadius(int r) {
        return new Muffler(pos, owner, enabled, r, quietPercent, groups, sounds);
    }

    public Muffler withQuietPercent(int percent) {
        return new Muffler(pos, owner, enabled, radius, percent, groups, sounds);
    }

    /** Set a group's level; null (or ALLOW, which a group never holds) puts it back to Normal. */
    public Muffler withGroup(String id, MuffleLevel level) {
        Map<String, MuffleLevel> next = new LinkedHashMap<>(groups);
        if (level == null || !level.hushes()) {
            next.remove(id);
        } else {
            next.put(id, level);
        }
        return new Muffler(pos, owner, enabled, radius, quietPercent, next, sounds);
    }

    /** Set one sound's level; null forgets the pick. Adding past {@link #MAX_PICKS} changes nothing. */
    public Muffler withSound(String key, MuffleLevel level) {
        Map<String, MuffleLevel> next = new LinkedHashMap<>(sounds);
        if (level == null) {
            next.remove(key);
        } else {
            if (!next.containsKey(key) && next.size() >= MAX_PICKS) {
                return this;
            }
            next.put(key, level);
        }
        return new Muffler(pos, owner, enabled, radius, quietPercent, groups, next);
    }

    /** Everything picked is forgotten; power, range and the Quieter volume stay. */
    public Muffler cleared() {
        return new Muffler(pos, owner, enabled, radius, quietPercent, Map.of(), Map.of());
    }

    /** The same settings on a muffler somewhere else, for a picked-up muffler placed again. */
    public Muffler movedTo(MufflerPos to, UUID newOwner) {
        return new Muffler(to, newOwner, enabled, radius, quietPercent, groups, sounds);
    }

    // ---- storage ------------------------------------------------------------------------------

    /**
     * The picks as text, one per line: {@code g chickens SILENT} for a group and
     * {@code s minecraft:entity.chicken.egg ALLOW} for a single sound. Neither an id nor a key
     * can hold a space or a newline, so no escaping is needed.
     */
    public String encodeRules() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, MuffleLevel> e : groups.entrySet()) {
            out.append("g ").append(e.getKey()).append(' ').append(e.getValue().name()).append('\n');
        }
        for (Map.Entry<String, MuffleLevel> e : sounds.entrySet()) {
            out.append("s ").append(e.getKey()).append(' ').append(e.getValue().name()).append('\n');
        }
        return out.toString();
    }

    /** Read {@link #encodeRules} text back. Lines that don't parse are skipped, never fatal. */
    public static Rules decodeRules(String text) {
        Map<String, MuffleLevel> groups = new LinkedHashMap<>();
        Map<String, MuffleLevel> sounds = new LinkedHashMap<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) {
                    continue;
                }
                MuffleLevel level = MuffleLevel.parse(parts[2]);
                if (level == null) {
                    continue;
                }
                if (parts[0].equals("g") && level.hushes() && !parts[1].isEmpty()) {
                    groups.put(parts[1], level);
                } else if (parts[0].equals("s") && SoundNames.isKey(parts[1]) && sounds.size() < MAX_PICKS) {
                    sounds.put(parts[1], level);
                }
            }
        }
        return new Rules(groups, sounds);
    }

    /** Decoded picks. */
    public record Rules(Map<String, MuffleLevel> groups, Map<String, MuffleLevel> sounds) {
    }

    /**
     * Everything but the position and owner, as text for a picked-up muffler's item, so it is
     * placed again exactly as it was: {@code 1 <on 1|0> <radius> <quiet %>} and then the picks.
     */
    public String encodeMemory() {
        return "1 " + (enabled ? 1 : 0) + " " + radius + " " + quietPercent + "\n" + encodeRules();
    }

    /** A muffler at {@code pos} set up from {@link #encodeMemory} text; null when the text isn't that. */
    public static Muffler fromMemory(String text, MufflerPos pos, UUID owner) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] lines = text.split("\n", 2);
        String[] head = lines[0].trim().split(" ");
        if (head.length != 4 || !head[0].equals("1")) {
            return null;
        }
        int radius;
        int quiet;
        try {
            radius = Integer.parseInt(head[2]);
            quiet = Integer.parseInt(head[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        Rules rules = decodeRules(lines.length > 1 ? lines[1] : "");
        return new Muffler(pos, owner, !head[1].equals("0"), radius, quiet, rules.groups(), rules.sounds());
    }

    /** Whether anything differs from a muffler just placed with these defaults. */
    public boolean isDefault(int defaultRadius, int defaultQuietPercent) {
        return enabled && groups.isEmpty() && sounds.isEmpty()
                && radius == defaultRadius && quietPercent == defaultQuietPercent;
    }

    /** The picked single sounds in the order they were first picked, as a list for paging. */
    public List<String> pickedSounds() {
        return new ArrayList<>(sounds.keySet());
    }

    private static Map<String, MuffleLevel> frozen(Map<String, MuffleLevel> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(in));
    }
}
