package com.dierks.homecraft.arcade.candy;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * A shower's own sounds and particles (0.38.6): everything that says Halloween or Thanksgiving. The sounds
 * every shower shares (the chime, the pickup, the pop, the sparkles on the ground, the level-up, the toast and
 * the eat) stay in the service.
 *
 * <p>The sound is a {@link Supplier}: Bukkit's sounds are read from the server's registry, so a profile can be
 * built, and checked in a test, without one.
 *
 * @param second        the sound after the chime when a shower starts (and when the bag is tapped)
 * @param secondVolume  its volume, and {@code secondPitch} its pitch
 * @param fall          the particle on a falling item and the toy; a {@code DUST} particle takes {@code fallRgb}
 * @param fallRgb       the dust colour of an everyday item; {@code goldenRgb} a golden one's
 * @param spark         the little spark on a golden item as it appears and falls
 */
public record ShowerFx(Supplier<Sound> second, float secondVolume, float secondPitch, Particle fall, int fallRgb,
                       int goldenRgb, Particle spark) {

    /** The Candy Apple shower's: a high, quiet witch giggle, witch sparkles and a little flame. */
    public static ShowerFx candy() {
        return new ShowerFx(() -> Sound.ENTITY_WITCH_CELEBRATE, 0.5f, 1.6f, Particle.WITCH, 0, 0,
                Particle.SMALL_FLAME);
    }

    /** The Harvest Basket's: a note-block bell, orange dust (gold for the Golden Pie) and crit sparks. */
    public static ShowerFx harvest() {
        return new ShowerFx(() -> Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.0f, Particle.DUST, 0xE67E22, 0xFFD700,
                Particle.CRIT);
    }

    /** The second sound, at the player. */
    public void playSecond(Player p) {
        p.playSound(p.getLocation(), second.get(), secondVolume, secondPitch);
    }

    /** Two of the fall particle at a falling item, and the spark at a golden one, for its owner. */
    public void falling(Player owner, Location at, boolean golden) {
        show(owner, fall, golden ? goldenRgb : fallRgb, at, 2, 0.1, 0);
        if (golden) {
            owner.spawnParticle(spark, at, 1, 0.05, 0.05, 0.05, 0);
        }
    }

    /** The spark as a golden item appears. */
    public void goldenAppears(Player owner, Location at) {
        owner.spawnParticle(spark, at, 2, 0.1, 0.1, 0.1, 0);
    }

    /** Eight of the fall particle in view, when the toy is tapped. */
    public void toy(Player p, Location inView) {
        show(p, fall, fallRgb, inView, 8, 0.3, 0);
    }

    private static void show(Player p, Particle particle, int rgb, Location at, int count, double spread,
                             double speed) {
        if (particle.getDataType() == Particle.DustOptions.class) {
            p.spawnParticle(particle, at, count, spread, spread, spread, speed,
                    new Particle.DustOptions(Color.fromRGB(rgb), 1.0f));
        } else {
            p.spawnParticle(particle, at, count, spread, spread, spread, speed);
        }
    }
}
