package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Bedrock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What players see of a golf ball (spec §12, R3.13): two entities at one spot, moved together
 * every tick, because Java and Bedrock players can't be shown the same thing.
 * <ul>
 *   <li>an {@link ItemDisplay} of the ball at half scale, gliding between positions
 *       (teleport duration 2) — for Java players;</li>
 *   <li>a small, invisible, marker armour stand wearing the ball, hidden from everyone by default
 *       and shown to each Bedrock player near it — who get the display hidden instead (Geyser
 *       can't draw display entities).</li>
 * </ul>
 * A Bedrock player in range is sent the stand afresh for a few passes ({@link BedrockSends}, 0.38.7): Paper
 * sends a shown entity only once, and a client that lost that spawn (mid-teleport) never saw the ball.
 * Both are {@link WorldEntities#tag tagged} and never saved with the chunk, so a crash can't leave
 * a ball behind: anything tagged is swept at start and whenever a chunk loads. If a chunk unload
 * takes them anyway, the next move puts them back.
 *
 * <p>The ball item is a plain head with the texture of one of the player's own Minis (never the
 * Mini's own item, and never tagged as one), a plain white ball, or for a Bedrock player a white
 * block — see {@link MiniGolf#ballItem}.
 */
final class BallView {

    /** Bedrock players this close are shown the armour stand. */
    static final double BEDROCK_RANGE = 64;
    /**
     * A head in a display at half scale is a quarter block tall and hangs below the display's
     * position; lift it this much so it sits on the ground. A block (a Bedrock player's ball) is
     * shown at a quarter scale, centred, so it needs half the lift.
     */
    static final double HEAD_LIFT = 0.25;
    static final double BLOCK_LIFT = 0.125;
    /** A small marker stand draws its helmet this far above its feet: lower it by that much. */
    static final double STAND_DROP = 0.72;

    private final HomeCraftManagement plugin;
    private final Game game;
    private final UUID owner;
    private final ItemStack look;
    /** The look is a block, not a head: drawn smaller and centred. */
    private final boolean block;
    private ItemDisplay display;
    private ArmorStand stand;
    /** The Bedrock players near the stand, and how often each has been sent it (0.38.7). */
    private final BedrockSends sends = new BedrockSends();

    BallView(HomeCraftManagement plugin, Game game, UUID owner, ItemStack look) {
        this.plugin = plugin;
        this.game = game;
        this.owner = owner;
        this.look = look.clone();
        this.block = look.getType() != Material.PLAYER_HEAD;
    }

    /** Show the ball at ({@code x}, {@code y}, {@code z}) — the bottom of it — facing {@code yaw}. */
    void move(World world, double x, double y, double z, float yaw) {
        if (world == null) {
            return;
        }
        Location at = new Location(world, x, y + (block ? BLOCK_LIFT : HEAD_LIFT), z, yaw, 0);
        Location feet = new Location(world, x, y - STAND_DROP, z, yaw, 0);
        if (display == null || !display.isValid() || stand == null || !stand.isValid()
                || display.getWorld() != world || stand.getWorld() != world) {
            remove();
            if (world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
                spawn(at, feet); // else it comes back once the chunk is loaded
            }
            return;
        }
        display.teleport(at);
        stand.teleport(feet);
    }

    /**
     * Show the stand (and hide the display) for Bedrock players near the ball: their first pass shows it,
     * the next few send it afresh (hidden, then shown: a show alone sends nothing once it is on), as
     * {@link BedrockSends} says.
     */
    void showToBedrock() {
        if (display == null || stand == null || !stand.isValid()) {
            return;
        }
        Location here = stand.getLocation();
        Set<UUID> near = new HashSet<>();
        for (Player p : here.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(here) <= BEDROCK_RANGE * BEDROCK_RANGE && Bedrock.is(p)) {
                near.add(p.getUniqueId());
                BedrockSends.Send send = sends.pass(p.getUniqueId());
                if (send == BedrockSends.Send.RESEND) {
                    p.hideEntity(plugin, stand);
                }
                if (send != BedrockSends.Send.NONE) {
                    p.showEntity(plugin, stand);
                }
                p.hideEntity(plugin, display);
            }
        }
        sends.retain(near); // out of range: sent afresh when they come back
    }

    /** A new hole: the stand is sent afresh to Bedrock players on the next passes. */
    void freshForBedrock() {
        sends.clear();
    }

    /** Whether the ball is showing: both its display and its stand (a lost stand comes back too, 0.38.7). */
    boolean shown() {
        return display != null && display.isValid() && stand != null && stand.isValid();
    }

    /** Take the ball away. */
    void remove() {
        removeQuietly(display);
        removeQuietly(stand);
        display = null;
        stand = null;
        sends.clear();
    }

    private void spawn(Location at, Location feet) {
        World world = at.getWorld();
        sends.clear(); // a new stand: nobody has it yet
        display = world.spawn(at, ItemDisplay.class, d -> {
            WorldEntities.tag(d, game, owner);
            d.setItemStack(look);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            float scale = block ? 0.25f : 0.5f;
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setTeleportDuration(2);
            d.setBillboard(Display.Billboard.FIXED);
            d.setShadowRadius(0.15f);
            d.setShadowStrength(0.6f);
            d.setViewRange(1.0f);
        });
        stand = world.spawn(feet, ArmorStand.class, s -> {
            WorldEntities.tag(s, game, owner);
            s.setVisibleByDefault(false);
            s.setInvisible(true);
            s.setSmall(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setCollidable(false);
            s.getEquipment().setHelmet(look);
        });
        showToBedrock();
    }

    /**
     * Gone for good, unless it already is. A ball whose chunk unloaded is already gone (Paper removes it with
     * the chunk, and it is never saved), so nothing is left to remove here; a tagged one that comes back is
     * taken by the sweep when its chunk loads ({@link WorldEntities}).
     */
    private static void removeQuietly(Entity e) {
        if (e != null && !e.isDead()) {
            e.remove();
        }
    }
}
