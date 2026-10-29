package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * The Clubhouse's things in the world that aren't blocks (CLUBHOUSE-SPEC §3, §4): the results board
 * (one text display) and the firework over 1st place; one of these per wall board too (0.38.3: this week's best,
 * now playing).
 *
 * <p><b>Locked in place</b> (0.38.3; the owner: "it rotates as you walk so it only works if you are directly in
 * front of it"): a board is a FIXED display, flat on its wall like a sign, facing into the room and pulled just off
 * the wall ({@link WallBoards}), drawn big enough to read from anywhere in the room. It was a VERTICAL billboard that
 * swung round to each viewer, half into the wall from the side; one of those left by a crash is swept at the start
 * like any other, and one still standing is made again ({@link #current}). On Bedrock Geyser draws a display as a
 * name tag, which turns to face the viewer.
 *
 * <p><b>Never two boards.</b> Both are game entities ({@link WorldEntities#tag}): never saved with
 * their chunk, and every one the Clubhouse ever made is swept when it starts, so a reload or a crash
 * can't leave a second board standing. The board is made again when its chunk had unloaded it, and
 * its text is set only when {@link ClubBoard#offer} says so (never per tick).
 *
 * <p><b>Kid-safe firework.</b> A burst of colour a few blocks above the podium, set off at once;
 * everyone in the Clubhouse is in a world session (no damage), and the Clubhouse cancels any damage
 * one of its fireworks could do to anyone else.
 */
final class BoardDisplay {

    private final Game game;
    private final TextDisplay.TextAlignment alignment;
    private UUID board;
    private String text;

    /** The results board: its lines centred. */
    BoardDisplay(Game game) {
        this(game, TextDisplay.TextAlignment.CENTER);
    }

    /** A board whose lines are {@code alignment} (a list reads best LEFT). */
    BoardDisplay(Game game, TextDisplay.TextAlignment alignment) {
        this.game = game;
        this.alignment = alignment == null ? TextDisplay.TextAlignment.CENTER : alignment;
    }

    /**
     * Show {@code text} at {@code at} in {@code worldName}: the display is made (again) if it isn't
     * there, and its text set. Nothing happens while the chunk isn't loaded.
     *
     * @return whether the board shows it now
     */
    boolean show(String worldName, WallBoards.Wall at, String text) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null || at == null || text == null
                || !w.isChunkLoaded((int) Math.floor(at.x()) >> 4, (int) Math.floor(at.z()) >> 4)) {
            return false;
        }
        TextDisplay d = current(w, at);
        if (d == null) {
            d = w.spawn(new Location(w, at.x(), at.y(), at.z(), at.yaw(), 0f), TextDisplay.class,
                    e -> lockInPlace(e, game, null, at, alignment));
            board = d.getUniqueId();
            this.text = null;
        }
        if (!text.equals(this.text)) {
            d.text(Text.of(text));
            this.text = text;
        }
        return true;
    }

    /**
     * A board's display as it spawns (inside the spawn's consumer, before anyone is sent it): tagged as the game's
     * ({@code owner} null for a shared board, the viewer for one only they see), never saved, flat on its wall
     * facing {@code at}'s way, at its size, on a dark background, lit as by day.
     */
    static void lockInPlace(TextDisplay e, Game game, UUID owner, WallBoards.Wall at,
                            TextDisplay.TextAlignment alignment) {
        WorldEntities.tag(e, game, owner);
        e.setBillboard(Display.Billboard.FIXED); // flat on its wall like a sign (it swung round to each viewer)
        e.setRotation(at.yaw(), 0f);
        e.setLineWidth(at.lineWidth());
        e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(at.scale(), at.scale(), at.scale()), new Quaternionf())); // Geyser ignores it
        e.setShadowed(true);
        e.setDefaultBackground(false);
        e.setBackgroundColor(Color.fromARGB(160, 0, 0, 0));
        e.setBrightness(new Display.Brightness(15, 15));
        e.setAlignment(alignment);
    }

    /** Whether the board stands now (its chunk loaded and the display there). */
    boolean standing(String worldName, WallBoards.Wall at) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        return w != null && at != null && current(w, at) != null;
    }

    /**
     * The board's display if it is still right: in {@code w}, within half a block of {@code at}, FIXED and facing
     * {@code at}'s way. Anything else (an owner who set the board again, an old VERTICAL board) is removed, and
     * {@code null} says to make it again.
     */
    private TextDisplay current(World w, WallBoards.Wall at) {
        if (board == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(board);
        if (e instanceof TextDisplay d && d.isValid() && d.getWorld().equals(w)
                && still(d.getBillboard() == Display.Billboard.FIXED, d.getLocation().getYaw(),
                d.getLocation().distanceSquared(new Location(w, at.x(), at.y(), at.z())), at)) {
            return d;
        }
        if (e != null && e.isValid()) {
            e.remove(); // moved or turned (an owner set a new spot), or not locked in place: the old one goes
        }
        board = null;
        return null;
    }

    /** Whether a display {@code fixed}, at {@code yaw}, {@code distanceSquared} from {@code at}, is still that board. */
    static boolean still(boolean fixed, float yaw, double distanceSquared, WallBoards.Wall at) {
        return fixed && distanceSquared < 0.25 && WallBoards.sameWay(yaw, at.yaw());
    }

    /** Take the board away (the Clubhouse stopped or closed). */
    void remove() {
        if (board != null) {
            Entity e = Bukkit.getEntity(board);
            if (e != null) {
                e.remove();
            }
        }
        board = null;
        text = null;
    }

    /** A firework burst {@code up} blocks above {@code at}: colours only, set off at once. */
    void firework(String worldName, ClubhouseSite.Spot at, double up) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null || at == null) {
            return;
        }
        Location l = new Location(w, at.x(), at.y() + up, at.z());
        Firework fw = w.spawn(l, Firework.class, f -> {
            WorldEntities.tag(f, game, null);
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.YELLOW, Color.AQUA, Color.FUCHSIA).withFade(Color.WHITE).trail(false)
                    .flicker(true).build());
            meta.setPower(0);
            f.setFireworkMeta(meta);
        });
        fw.detonate();
    }

    /** Whether {@code e} is one of the Clubhouse's fireworks (its burst never hurts anyone). */
    static boolean ours(Entity e, Game game) {
        return e instanceof Firework && game.id().equals(WorldEntities.gameId(e));
    }
}
