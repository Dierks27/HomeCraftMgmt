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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trophy case (0.38.4; the owner: a trophy case on a Clubhouse wall): one item frame per all-time record,
 * from the Fame board's list (the courses first, then golf), at most {@value #MAX} of them, each with a one-line
 * text display over it ("Sky Rings 1:02.4 - Sam"). A gold ingot for a course's record, a golf ball (the Games
 * screen's golf icon, a snowball) for a golf course's. In the generated room the frames hang on the west wall's
 * skirting under the My stats board ({@link ClubhousePlanner#trophyFrames}); in an owner-built one from the
 * {@code trophies} spot along the admin's right.
 *
 * <p>Nothing to tap: a frame is fixed (it can't be turned, popped or taken), drops nothing, and like every board
 * is a game entity ({@link WorldEntities#tag}): never saved with its chunk, swept at every start, so a crash or a
 * reload leaves nothing. It is redrawn from the boards' cache once a second while anyone is in, writing only when
 * a row changed or an entity went (a chunk unloaded; it is made again when it loads: asked, never loaded).
 * Bedrock draws item frames and a text display (as a name tag) natively.
 *
 * <p>The rows are pure ({@link #rows}, {@link #line}); the rest talks to the world.
 */
final class TrophyWall {

    /** At most this many trophies (the Fame board's rows). */
    static final int MAX = ClubBoard.MAX_ROWS;
    /** The line's size and where it hangs: over the frame, a little into the room. */
    static final float SCALE = 0.75f;
    static final double LINE_UP = 1.15;
    static final double LINE_OUT = 0.5;

    /** One trophy: the course, who holds its record, the score as shown, and whether it is golf's. */
    record Trophy(String course, String holder, String score, boolean golf) {
    }

    /** What is shown at one frame now. */
    private record Shown(Trophy trophy, UUID frame, UUID line) {
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

    /** "&amp;6Sky Rings &amp;f1:02.4 &amp;7- Sam": the line over a trophy. */
    static String line(Trophy t) {
        return ClubBoardText.trophyLine(t.course(), t.score(), t.holder());
    }

    /** The item in a trophy's frame: a gold ingot for a course, a golf ball for golf, named. */
    static ItemStack item(Trophy t) {
        return t.golf() ? Menus.icon(Material.SNOWBALL, "&fGolf ball &7- " + t.course() + " record")
                : Menus.icon(Material.GOLD_INGOT, "&6" + t.course() + " record");
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
            if (s != null && s.trophy().equals(t) && alive(s.frame(), w) && alive(s.line(), w)) {
                continue; // as it is
            }
            if (!w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
                continue; // asked, never loaded: drawn when it loads
            }
            if (s != null) {
                take(s);
            }
            shown.put(i, spawn(w, at, yaw, t));
        }
    }

    private Shown spawn(World w, int[] at, float yaw, Trophy t) {
        ItemFrame frame = w.spawn(new Location(w, at[0] + 0.5, at[1] + 0.5, at[2] + 0.5), ItemFrame.class, f -> {
            WorldEntities.tag(f, game, null);
            f.setFacingDirection(facing(yaw), true);
            f.setItem(item(t), false); // the frame's item, nobody's inventory
            f.setFixed(true);
            f.setItemDropChance(0f);
        });
        BlockFace out = facing(yaw);
        Location l = new Location(w, at[0] + 0.5 + out.getModX() * LINE_OUT, at[1] + LINE_UP,
                at[2] + 0.5 + out.getModZ() * LINE_OUT, WallBoards.snap(yaw), 0f);
        TextDisplay line = w.spawn(l, TextDisplay.class, e -> {
            WorldEntities.tag(e, game, null);
            e.setBillboard(Display.Billboard.FIXED);
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
        return new Shown(t, frame.getUniqueId(), line.getUniqueId());
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
