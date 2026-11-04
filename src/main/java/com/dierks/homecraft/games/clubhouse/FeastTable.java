package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.world.WorldEntities;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Family Feast's tables in the Clubhouse (0.38.6): {@value FeastRules#CELLS} plates (item frames facing up on the
 * glass) that fill with food as the family's total passes each twentieth of the goal, and one line over them,
 * "Family Feast 137 / 150" with a bar under it. A child sees the plates fill; nothing needs reading. Food is never
 * handed in here (a visitor carries only the kit): the Feast Pot is in the home world, and this only shows it.
 *
 * <p>As the trophy case ({@link TrophyWall}): every frame is fixed, drops nothing, and is a game entity
 * ({@link WorldEntities#tag}): never saved with its chunk, swept at every start. It is redrawn once a second while
 * anyone is in, and only the plates that changed (or whose frame went with an unloaded chunk) are touched; nothing
 * happens in a chunk that isn't loaded. Outside the Feast's window the tables are empty (the frames and the line
 * come down). Bedrock draws item frames and the line (as a name tag) natively.
 *
 * <p>The room's ceremony fires its bursts through {@link #burst}: harmless, detonated at once, game-tagged so the
 * Clubhouse cancels any damage, a few blocks over the tables and under the glass roof.
 */
final class FeastTable {

    /** The bursts' height over the plates: from 3 to 8 blocks (the roof is 13 over them). */
    static final int BURST_LOW = 3;
    static final int BURST_HIGH = 8;

    /** One plate as drawn: where, the food on it ({@code null}: an empty plate), and its frame. */
    private record Plate(int[] at, String kind, UUID frame) {
    }

    private final Game game;
    private final Map<Integer, Plate> shown = new HashMap<>();
    private final BoardDisplay line;

    FeastTable(Game game) {
        this.game = game;
        this.line = new BoardDisplay(game);
    }

    // ---- pure -------------------------------------------------------------------------------------

    /** The food plate {@code cell} shows with {@code plates} full, or {@code null} for an empty plate. */
    static String want(int cell, int plates) {
        return cell < plates ? FeastRules.kindAt(cell) : null;
    }

    /**
     * The cells to draw again for {@code plates} full plates: those whose food differs from {@code shown} (a cell's
     * shown food, {@code null} for an empty plate) or whose frame is gone ({@code alive} false).
     */
    static List<Integer> changed(String[] shown, boolean[] alive, int plates) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < shown.length; i++) {
            if (!alive[i] || !Objects.equals(shown[i], want(i, plates))) {
                out.add(i);
            }
        }
        return out;
    }

    /** The line's text: the family's line, then the bar. */
    static String text(long total, int goal) {
        return FeastRules.line(total, goal) + "\n" + FeastRules.bar(total, goal);
    }

    // ---- the world --------------------------------------------------------------------------------

    /**
     * Once a second while anyone is in: the plates and the line for {@code view} at {@code room}'s table, changed only
     * where they differ. Outside the Feast's window, or with no table, everything comes down.
     */
    void redraw(ClubhouseRoom room, FeastRules.FeastView view) {
        List<int[]> cells = room == null ? null : room.feastCells();
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (cells == null || w == null || view == null || !view.open()) {
            remove();
            return;
        }
        int n = Math.min(cells.size(), FeastRules.CELLS);
        String[] kinds = new String[n];
        boolean[] alive = new boolean[n];
        for (int i = 0; i < n; i++) {
            Plate p = shown.get(i);
            boolean same = p != null && Arrays.equals(p.at(), cells.get(i));
            kinds[i] = same ? p.kind() : "-";
            alive[i] = same && alive(p.frame(), w);
        }
        for (int i : changed(kinds, alive, view.plates())) {
            int[] at = cells.get(i);
            if (!w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
                continue; // asked, never loaded: drawn when it loads
            }
            Plate p = shown.get(i);
            String kind = want(i, view.plates());
            Entity e = p == null ? null : Bukkit.getEntity(p.frame());
            if (alive[i] && e instanceof ItemFrame f) {
                f.setItem(item(kind), false); // the same frame: only its food changes
                shown.put(i, new Plate(at.clone(), kind, p.frame()));
                continue;
            }
            if (e != null) {
                e.remove();
            }
            shown.put(i, new Plate(at.clone(), kind, spawn(w, at, kind)));
        }
        WallBoards.Wall wall = room.feastLine();
        if (wall != null) {
            line.show(room.world(), wall, text(view.total(), view.goal()));
        }
    }

    private UUID spawn(World w, int[] at, String kind) {
        ItemFrame frame = w.spawn(new Location(w, at[0] + 0.5, at[1] + 0.5, at[2] + 0.5), ItemFrame.class, f -> {
            WorldEntities.tag(f, game, null);
            f.setFacingDirection(BlockFace.UP, true);
            f.setItem(item(kind), false); // the frame's item, nobody's inventory
            f.setFixed(true);
            f.setItemDropChance(0f);
        });
        return frame.getUniqueId();
    }

    /** A plate's food, or nothing for an empty plate. */
    private static ItemStack item(String kind) {
        Material m = kind == null ? null : Material.matchMaterial(kind);
        return m == null ? null : new ItemStack(m);
    }

    private static boolean alive(UUID id, World w) {
        Entity e = id == null ? null : Bukkit.getEntity(id);
        return e != null && e.isValid() && e.getWorld().equals(w);
    }

    /** Every plate and the line come down (the Feast closed, the room closed or moved, or the Clubhouse stopped). */
    void remove() {
        for (Plate p : shown.values()) {
            Entity e = p.frame() == null ? null : Bukkit.getEntity(p.frame());
            if (e != null) {
                e.remove();
            }
        }
        shown.clear();
        line.remove();
    }

    /** One harmless burst in {@code colours} over a random plate of {@code room}'s table (nothing with no table). */
    void burst(ClubhouseRoom room, List<Color> colours) {
        List<int[]> cells = room == null ? null : room.feastCells();
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (cells == null || cells.isEmpty() || w == null || colours == null || colours.isEmpty()) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int[] at = cells.get(r.nextInt(cells.size()));
        if (!w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
            return;
        }
        Location l = new Location(w, at[0] + 0.5, at[1] + r.nextInt(BURST_LOW, BURST_HIGH + 1), at[2] + 0.5);
        Firework fw = w.spawn(l, Firework.class, f -> {
            WorldEntities.tag(f, game, null);
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(colours)
                    .withFade(Color.YELLOW).trail(false).flicker(true).build());
            meta.setPower(0);
            f.setFireworkMeta(meta);
        });
        fw.detonate();
    }

    /** How many plates stand now (for the admin's status and the tests). */
    int count() {
        return shown.size();
    }
}
