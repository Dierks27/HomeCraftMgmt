package com.dierks.homecraft.util;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
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
        spawn(at, -1);
    }

    /**
     * One harmless firework that bursts right in front of the player: 4 blocks along where they look
     * (flattened, so never into the floor or the sky), at eye height, after 4 ticks. {@link #spawn}'s
     * rocket bursts overhead, out of a first-person view; this one is in it, on Java and Bedrock.
     */
    public static void burstInView(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        Vector flat = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)).multiply(4);
        spawn(eye.clone().add(flat), 4);
    }

    /** {@code ticksToDetonate} below 0 keeps the rocket's own flight. */
    private static void spawn(Location at, int ticksToDetonate) {
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
                meta.setPower(1);
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
