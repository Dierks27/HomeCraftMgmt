package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Show Map's markers (0.38.2; the owner: "Is there any way we can see a map that would show them as well?"):
 * where a player's launchers go on a vanilla filled map. Pure (plain numbers: the cursor types are the renderer's), so a
 * test reads every marker with no server; {@link ShowMapRenderer} draws them and {@link RemoteService#giveMap} makes
 * the map.
 *
 * <p>The map is a vanilla one at scale {@value #SCALE} (512 x 512 blocks, 4 blocks a pixel), centred on the player
 * when it is given: the terrain fills in as they walk with it, as any map's does. On it, each linked launcher is a
 * flag in its number's colour captioned "#1".."#10" ({@link Kind#LINKED}); one linked but off the map sits on the
 * nearest edge, still numbered, so its way shows. The player's own other launchers are a plain white X
 * ({@link Kind#MINE}), and only while on the map. Nobody else's launchers are drawn, except those in the player's own
 * set. Only the launchers in the map's own world are.
 */
public final class ShowMap {

    /** The tag on a Show Map item: it is a token prize, never a row (its label falls back to "Arcade prize"). */
    public static final String PRIZE_ID = "firework_show_map";
    /** {@code MapView.Scale.NORMAL}: 512 blocks across, a city's size ({@code CLOSE}, 1, is 256: a small town). */
    public static final int SCALE = 2;
    /** A player's Show Map is made again (a new map id) only when they are this far from its centre, or elsewhere. */
    public static final int REUSE_BLOCKS = 128;
    /** The edges of a map in cursor units (half a pixel each). */
    static final int MIN = -128;
    static final int MAX = 127;

    /** What a marker is. */
    public enum Kind {
        /** in the player's set: a flag in its number's colour, "#n" */
        LINKED,
        /** one of the player's own other launchers: a plain X */
        MINE
    }

    /** One marker: what, its number (0 for MINE), its place in cursor units, and whether it is pinned to an edge. */
    public record Marker(Kind kind, int number, byte x, byte y, boolean edge) {
        /** "#3" for a linked one, null for an X (Geyser shows no caption at all; the colours tell them apart). */
        public String caption() {
            return kind == Kind.LINKED ? "#" + number : null;
        }
    }

    private ShowMap() {
    }

    /**
     * A block's map coordinate, unclamped: half-pixels from the centre, {@code (block + 0.5 - centre) * 2 / 2^scale},
     * rounded, as vanilla places its own decorations. At scale 2 a unit is 2 blocks: 256 blocks either way of the
     * centre fill the map's -128..127.
     */
    static int raw(int block, int centre, int scale) {
        return (int) Math.round((block + 0.5 - centre) * 2.0 / (1 << Math.max(0, scale)));
    }

    /** {@link #raw}, pinned to the map's edges. */
    static int coord(int block, int centre, int scale) {
        return Math.max(MIN, Math.min(MAX, raw(block, centre, scale)));
    }

    /**
     * The markers on a map of {@code world} centred at ({@code cx}, {@code cz}) at {@code scale}: the owner's other
     * launchers ({@code mine}, on the map only) as X's first, then {@code linked} in order as #1, #2.. (one off the map
     * on its nearest edge), so the flags are drawn over the X's. A linked one is never drawn again as an X, and only
     * spots in {@code world} are drawn.
     */
    public static List<Marker> markers(List<Spot> linked, List<Spot> mine, String world, int cx, int cz, int scale) {
        List<Marker> out = new ArrayList<>();
        Set<Spot> numbered = new HashSet<>(linked);
        for (Spot s : mine) {
            if (numbered.contains(s) || !s.world().equals(world)) {
                continue;
            }
            int x = raw(s.x(), cx, scale);
            int y = raw(s.z(), cz, scale);
            if (x < MIN || x > MAX || y < MIN || y > MAX) {
                continue; // off the map: only a linked one is pinned to the edge
            }
            out.add(new Marker(Kind.MINE, 0, (byte) x, (byte) y, false));
        }
        for (int i = 0; i < linked.size(); i++) {
            Spot s = linked.get(i);
            if (!s.world().equals(world)) {
                continue;
            }
            int x = raw(s.x(), cx, scale);
            int y = raw(s.z(), cz, scale);
            boolean edge = x < MIN || x > MAX || y < MIN || y > MAX;
            out.add(new Marker(Kind.LINKED, i + 1, (byte) coord(s.x(), cx, scale), (byte) coord(s.z(), cz, scale),
                    edge));
        }
        return out;
    }
}
