package com.dierks.homecraft.util;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Harmless fireworks. Each one is tagged {@link Keys#FIREWORK_SHOW}, and the prize listener
 * cancels any damage a tagged firework does — a celebration must never hurt the child it is
 * celebrating, or the pet standing next to them.
 */
public final class Fireworks {

    private static final Color[] PALETTE = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.FUCHSIA, Color.WHITE};

    private Fireworks() {
    }

    /** One random, colourful, harmless firework at {@code at}. */
    public static void spawn(Location at) {
        launch(at, 1, -1);
    }

    /**
     * {@link #spawn(Location)} at flight power {@code power} (0-3; 1 is {@link #spawn(Location)}'s): 0 bursts about 5
     * blocks up, 1 about 15, 2 about 28, 3 about 45 (0.38.1, the long firework shows' mix of heights).
     */
    public static void spawn(Location at, int power) {
        launch(at, Math.max(0, Math.min(3, power)), -1);
    }

    /** How far in front of the eyes {@link #burstInView} bursts, in the open. */
    static final double BURST_AHEAD = 4.0;

    /**
     * One harmless firework that bursts right in front of the player: 4 blocks along where they look
     * (flattened, so never into the floor or the sky), at eye height, after 4 ticks. {@link #spawn}'s
     * rocket bursts overhead, out of a first-person view; this one is in it, on Java and Bedrock. A
     * wall nearer than that brings it closer ({@link #burstDistance}), so indoors it still bursts on
     * this side of the wall rather than inside it or behind it.
     */
    public static void burstInView(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        Vector flat = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        double wall = -1;
        try {
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, flat, BURST_AHEAD, FluidCollisionMode.NEVER, true);
            if (hit != null) {
                wall = hit.getHitPosition().distance(eye.toVector());
            }
        } catch (RuntimeException ignored) {
            // no ray, the open-field distance
        }
        launch(eye.clone().add(flat.multiply(burstDistance(wall))), 1, 4);
    }

    /**
     * Where the burst goes along the look: {@link #BURST_AHEAD} with nothing in the way ({@code wall}
     * below 0), else 0.7 short of the wall. Never nearer than 1.5, which would be in the player's
     * face, so a player nose to a wall (closer than 2.2) gets it 1.5 ahead, at the wall.
     */
    static double burstDistance(double wall) {
        if (wall < 0 || wall >= BURST_AHEAD) {
            return BURST_AHEAD;
        }
        return Math.max(1.5, wall - 0.7);
    }

    /** {@code ticksToDetonate} below 0 keeps the rocket's own flight, which {@code power} sets. */
    private static void launch(Location at, int power, int ticksToDetonate) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        try {
            at.getWorld().spawn(at, Firework.class, fw -> {
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .withColor(PALETTE[r.nextInt(PALETTE.length)], PALETTE[r.nextInt(PALETTE.length)])
                        .withFade(Color.WHITE)
                        .with(FireworkEffect.Type.values()[r.nextInt(FireworkEffect.Type.values().length)])
                        .flicker(r.nextBoolean()).trail(true).build());
                meta.setPower(power);
                fw.setFireworkMeta(meta);
                if (ticksToDetonate >= 0) {
                    fw.setTicksToDetonate(ticksToDetonate);
                }
                fw.getPersistentDataContainer().set(Keys.FIREWORK_SHOW, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }
}
