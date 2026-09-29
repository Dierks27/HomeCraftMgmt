package com.dierks.homecraft.games.event;

import com.dierks.homecraft.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Race Night's bossbars (EVENTS-DROPPER-SPEC §A.6): one bar per player at most, reused and redrawn
 * in place (never per tick: the callers redraw every 10 ticks). Racers see their place ("2nd of 5 ·
 * Lap 1/2", yellow on the last lap), watchers see the leader, and everyone with news on sees the
 * join window draining. Nobody else gets one. A bar goes at the end of the night, when its line is
 * {@code null}, and when its player quits. Bedrock shows bossbars through Geyser.
 */
final class RaceBars {

    /** What a bar is for: its colour. */
    enum Tone {
        /** The join window: blue. */
        JOIN,
        /** A racer's place, the warm-up and the break: green. */
        RACE,
        /** A racer's last lap: yellow. */
        LAST_LAP,
        /** A watcher's: purple. */
        WATCH
    }

    private final Map<UUID, BossBar> bars = new HashMap<>();

    /** Show (or redraw) the player's bar; a {@code null} line hides it. */
    void show(UUID player, String line, float progress, Tone tone) {
        Player p = Bukkit.getPlayer(player);
        if (line == null || p == null) {
            hide(player);
            return;
        }
        float f = Math.max(0f, Math.min(1f, Float.isNaN(progress) ? 0f : progress));
        BossBar.Color colour = switch (tone) {
            case JOIN -> BossBar.Color.BLUE;
            case RACE -> BossBar.Color.GREEN;
            case LAST_LAP -> BossBar.Color.YELLOW;
            case WATCH -> BossBar.Color.PURPLE;
        };
        BossBar bar = bars.get(player);
        if (bar == null) {
            bar = BossBar.bossBar(Text.of(line), f, colour, BossBar.Overlay.PROGRESS);
            bars.put(player, bar);
            p.showBossBar(bar);
            return;
        }
        bar.name(Text.of(line));
        bar.progress(f);
        bar.color(colour);
    }

    /** Whether the player has a bar now. */
    boolean has(UUID player) {
        return bars.containsKey(player);
    }

    /** Take the player's bar away. */
    void hide(UUID player) {
        BossBar bar = bars.remove(player);
        Player p = bar == null ? null : Bukkit.getPlayer(player);
        if (p != null) {
            p.hideBossBar(bar);
        }
    }

    /** Take every bar away (the night ended, the game stopped). */
    void clear() {
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hide(id);
        }
    }

    /** Everyone with a bar now. */
    java.util.Set<UUID> ids() {
        return java.util.Set.copyOf(bars.keySet());
    }
}
