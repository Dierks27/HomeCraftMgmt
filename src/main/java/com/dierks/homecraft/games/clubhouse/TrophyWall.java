package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trophy case (0.38.4; the owner: a trophy case on a Clubhouse wall): one item frame per all-time record,
 * from the Fame board's list (the courses first, then golf), at most {@value #MAX} of them, each with a label over
 * it: the course, the score and who holds it, a short line each. A gold ingot for a course's record, a golf ball (the
 * Games screen's golf icon, a snowball) for a golf course's. In the generated room the frames hang on the west wall's
 * skirting under the My stats board ({@link ClubhousePlanner#trophyFrames}); in an owner-built one from the
 * {@code trophies} spot along the admin's right. Either way they are {@value ClubhousePlanner#TROPHY_STEP} blocks
 * apart.
 *
 * <p><b>Labels that never touch</b> (0.38.7; they ran into each other as one jumble when the case was full). On
 * Java a label is drawn at {@value #SCALE}x and wraps at {@value #LINE_WIDTH} px, so its widest line is
 * {@link #width()} blocks, less than the step: it never reaches the next frame's label. Bedrock keeps neither (Geyser
 * draws a text display as a name tag, full size, turned to the viewer), only the line breaks, so the lines are short
 * (a course and a name are cut as the boards cut them) and every other label hangs {@value #STAGGER} block higher
 * ({@link #lift}): labels at one height are two steps apart, and a raised one still ends well under My stats.
 *
 * <p>Nothing to tap: a frame is fixed (it can't be turned, popped or taken), drops nothing, and like every board
 * is a game entity ({@link WorldEntities#tag}): never saved with its chunk, swept at every start, so a crash or a
 * reload leaves nothing, and a new jar's labels are drawn fresh where the old ones were. It is redrawn from the boards' cache once a second while anyone is in, writing only when
 * a row changed, an entity went (a chunk unloaded; it is made again when it loads: asked, never loaded) or a slot
 * moved or turned (an owner set the trophies spot again: {@link #moved}, the boards' rule); a label's height
 * follows its slot, so a slot drawn again stands where the old one stood. Bedrock draws item frames and a text
 * display (as a name tag) natively.
 *
 * <p>The rows and the label's size and place are pure ({@link #rows}, {@link #line}, {@link #width},
 * {@link #lift}); the rest talks to the world.
 */
final class TrophyWall {

    /** At most this many trophies (the Fame board's rows). */
    static final int MAX = ClubBoard.MAX_ROWS;
    /**
     * The label's size on Java and where its lines wrap (px): at most {@link #width()} blocks wide, under the
     * frames' step. Geyser ignores both.
     */
    static final float SCALE = 0.6f;
    static final int LINE_WIDTH = 120;
    /** Where a label hangs: its bottom edge this far over its frame's block, a little into the room. */
    static final double LINE_UP = 1.15;
    static final double LINE_OUT = 0.5;
    /**
     * How much higher every other label hangs, so labels at one height are two frames apart: more than three lines
     * of a Bedrock name tag (0.75 of a block), so a raised label clears the one under it.
     */
    static final double STAGGER = 1.0;
    /** The lines of a label: the course, the score, who holds it. */
    static final int LINES = 3;

    /** One trophy: the course, who holds its record, the score as shown, and whether it is golf's. */
    record Trophy(String course, String holder, String score, boolean golf) {
    }

    /** What is shown at one frame now: the trophy, where the frame stands and faces, and the two entities. */
    private record Shown(Trophy trophy, int[] at, float yaw, UUID frame, UUID line) {
    }

    private final Game game;
    private final Map<Integer, Shown> shown = new HashMap<>();

    TrophyWall(Game game) {
        this.game = game;
    }

    // ---- pure -------------------------------------------------------------------------------------

    /** The trophies of {@code f}: each course's record, then each golf course's, cut to {@value #MAX}. */
    static List<Trophy> rows(FameBoard.Fame f) {
        List<Trophy> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        for (FameBoard.CourseTime t : f.records()) {
            if (out.size() >= MAX) {
                return out;
            }
            out.add(new Trophy(ClubBoardText.course(t.course()), ClubBoardText.name(t.name()), TrialText.time(t.ms()),
                    false));
        }
        for (FameBoard.GolfRound r : f.golfRecords()) {
            if (out.size() >= MAX) {
                return out;
            }
            out.add(new Trophy(ClubBoardText.course(r.course()), ClubBoardText.name(r.name()),
                    r.strokes() + " (" + ClubBoardText.vs(r.vsPar()) + ")", true));
        }
        return out;
    }

    /**
     * "&amp;6Sky Rings\n&amp;f1:02.4\n&amp;7Sam": the label over a trophy, {@value #LINES} short lines (the course
     * and the name already cut by {@link #rows}), so it stays narrow on Bedrock too, where only the line breaks are
     * kept.
     */
    static String line(Trophy t) {
        return "&6" + t.course() + "\n&f" + t.score() + "\n&7" + t.holder();
    }

    /** The widest a label is drawn on Java (blocks): its wrap width and the background's pixel each side, scaled. */
    static double width() {
        return (LINE_WIDTH + 2) * WallBoards.PX * SCALE;
    }

    /** How high slot {@code slot}'s label hangs over its frame's block: the odd slots {@value #STAGGER} higher. */
    static double lift(int slot) {
        return slot % 2 == 0 ? LINE_UP : LINE_UP + STAGGER;
    }

    /** The item in a trophy's frame: a gold ingot for a course, a golf ball for golf, named. */
    static ItemStack item(Trophy t) {
        return t.golf() ? Menus.icon(Material.SNOWBALL, "&fGolf ball &7- " + t.course() + " record")
                : Menus.icon(Material.GOLD_INGOT, "&6" + t.course() + " record");
    }

    /**
     * Whether a frame shown at {@code from} facing {@code fromYaw} has to be made again for a slot now at
     * {@code to} facing {@code toYaw} (an owner set the trophies spot again, or faced another way): the boards'
     * "moved or turned" rule ({@code BoardDisplay.current}), for the frames.
     */
    static boolean moved(int[] from, float fromYaw, int[] to, float toYaw) {
        return !Arrays.equals(from, to) || WallBoards.snap(fromYaw) != WallBoards.snap(toYaw);
    }

    /** The way a frame hangs for a yaw (0 south, 90 west, 180 north, -90 east), as {@link WallBoards}' boards. */
    static BlockFace facing(float yaw) {
        float y = WallBoards.snap(yaw);
        return y == 0f ? BlockFace.SOUTH : y == 90f ? BlockFace.WEST : y == 180f ? BlockFace.NORTH : BlockFace.EAST;
    }

    // ---- the world --------------------------------------------------------------------------------

    /**
     * Once a second while anyone is in: the frames and lines for {@code fame}'s records at {@code room}'s
     * positions, made or changed only where they differ; frames beyond the rows come down. Nothing happens in a
     * chunk that isn't loaded.
     */
    void redraw(ClubhouseRoom room, FameBoard.Fame fame) {
        List<int[]> frames = room == null ? null : room.trophyFrames();
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (frames == null || w == null) {
            remove();
            return;
        }
        List<Trophy> rows = rows(fame);
        float yaw = room.trophyYaw();
        for (int i = 0; i < frames.size(); i++) {
            Trophy t = i < rows.size() ? rows.get(i) : null;
            int[] at = frames.get(i);
            Shown s = shown.get(i);
            if (t == null) {
                if (s != null) {
                    take(s);
                    shown.remove(i);
                }
                continue;
            }
            boolean moved = s != null && moved(s.at(), s.yaw(), at, yaw);
            if (s != null && !moved && s.trophy().equals(t) && alive(s.frame(), w) && alive(s.line(), w)) {
                continue; // as it is
            }
            if (!w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
                if (moved) {
                    take(s); // the old wall's frame doesn't linger while the new spot's chunk isn't loaded
                    shown.remove(i);
                }
                continue; // asked, never loaded: drawn when it loads
            }
            if (s != null) {
                take(s);
            }
            shown.put(i, spawn(w, at, yaw, t, lift(i)));
        }
    }

    private Shown spawn(World w, int[] at, float yaw, Trophy t, double up) {
        ItemFrame frame = w.spawn(new Location(w, at[0] + 0.5, at[1] + 0.5, at[2] + 0.5), ItemFrame.class, f -> {
            WorldEntities.tag(f, game, null);
            f.setFacingDirection(facing(yaw), true);
            f.setItem(item(t), false); // the frame's item, nobody's inventory
            f.setFixed(true);
            f.setItemDropChance(0f);
        });
        BlockFace out = facing(yaw);
        Location l = new Location(w, at[0] + 0.5 + out.getModX() * LINE_OUT, at[1] + up,
                at[2] + 0.5 + out.getModZ() * LINE_OUT, WallBoards.snap(yaw), 0f);
        TextDisplay line = w.spawn(l, TextDisplay.class, e -> {
            WorldEntities.tag(e, game, null);
            e.setBillboard(Display.Billboard.FIXED);
            e.setLineWidth(LINE_WIDTH); // a long word wraps before it reaches the next label (Java)
            e.setRotation(WallBoards.snap(yaw), 0f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE), new Quaternionf()));
            e.setShadowed(true);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.text(Text.of(line(t)));
        });
        return new Shown(t, at.clone(), WallBoards.snap(yaw), frame.getUniqueId(), line.getUniqueId());
    }

    private static boolean alive(UUID id, World w) {
        Entity e = id == null ? null : Bukkit.getEntity(id);
        return e != null && e.isValid() && e.getWorld().equals(w);
    }

    private static void take(Shown s) {
        for (UUID id : new UUID[]{s.frame(), s.line()}) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Every frame and line comes down (the room closed or moved, or the Clubhouse stopped). */
    void remove() {
        for (Shown s : shown.values()) {
            take(s);
        }
        shown.clear();
    }

    /** How many trophies stand now (for the admin's status). */
    int count() {
        return shown.size();
    }
}
