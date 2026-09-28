package com.dierks.homecraft.muffler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Every placed muffler, grouped by world, frozen at one moment.
 *
 * <p>This is what the packet threads read. Sounds leave the server on network threads, not the
 * main one, so they can never look at the live registry while a menu click is changing it. The
 * service builds a new snapshot after every change and swaps it in whole; a sound either sees the
 * old muffler or the new one, never a muffler halfway through being edited.
 */
public final class MufflerZones {

    public static final MufflerZones EMPTY = new MufflerZones(Map.of());

    private final Map<String, List<Muffler>> byWorld;

    private MufflerZones(Map<String, List<Muffler>> byWorld) {
        this.byWorld = byWorld;
    }

    public static MufflerZones of(Collection<Muffler> mufflers) {
        if (mufflers.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<Muffler>> grouped = new HashMap<>();
        for (Muffler m : mufflers) {
            grouped.computeIfAbsent(m.pos().world(), w -> new ArrayList<>()).add(m);
        }
        Map<String, List<Muffler>> frozen = new HashMap<>();
        for (Map.Entry<String, List<Muffler>> e : grouped.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new MufflerZones(Collections.unmodifiableMap(frozen));
    }

    public boolean isEmpty() {
        return byWorld.isEmpty();
    }

    /** The mufflers in one world; empty (never null) when it has none. */
    public List<Muffler> in(String world) {
        List<Muffler> list = world == null ? null : byWorld.get(world);
        return list == null ? List.of() : list;
    }

    /** Whether any muffler's box holds this point — the cheap test run before a sound is read. */
    public static boolean anyContains(List<Muffler> zones, double x, double y, double z) {
        for (Muffler m : zones) {
            if (m.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What happens to one sound made at (x, y, z). Every muffler whose box holds the point is
     * asked, switched off or not, and told it heard the sound ({@code heard} — the "Heard nearby"
     * list). Of the ones that are on, the strongest answer wins: Silent beats Quieter, and two
     * Quieters give the quieter of the two volumes. A neighbour's "Always play" can't undo your
     * Silent, because it only ever speaks for its own muffler.
     */
    public static Verdict decide(List<Muffler> zones, double x, double y, double z, String key,
                                 BiConsumer<Muffler, String> heard) {
        boolean silent = false;
        float factor = 1f;
        for (Muffler m : zones) {
            if (!m.contains(x, y, z)) {
                continue;
            }
            if (heard != null) {
                heard.accept(m, key);
            }
            if (!m.enabled()) {
                continue;
            }
            MuffleLevel level = m.levelFor(key);
            if (level == MuffleLevel.SILENT) {
                silent = true;
            } else if (level == MuffleLevel.QUIETER) {
                factor = Math.min(factor, m.quietFactor());
            }
        }
        if (silent) {
            return Verdict.SILENT;
        }
        return factor < 1f ? new Verdict(false, factor) : Verdict.NONE;
    }

    /** The answer for one sound: drop it, turn it down by {@code factor}, or leave it be. */
    public record Verdict(boolean silent, float factor) {
        public static final Verdict NONE = new Verdict(false, 1f);
        public static final Verdict SILENT = new Verdict(true, 0f);

        /** Whether the sound is touched at all. */
        public boolean changes() {
            return silent || factor < 1f;
        }
    }
}
