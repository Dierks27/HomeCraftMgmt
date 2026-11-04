package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Random;

/**
 * What eating a shower's item does (0.38.6): its effects, particles, sounds and words. Particles go at
 * {@code eye + 1.2 x look} for the eater (at their own head they clip a first-person screen), and at their head
 * for anyone within {@value #VIEW_RANGE} blocks. Never levitation, blindness, nausea, darkness, slowness or
 * Glowing. Effects are read from the server's registry, so a profile holds this as code, never as values.
 */
@FunctionalInterface
public interface ShowerTreat {

    /** Others see an eater's treat particles from this far. */
    double VIEW_RANGE = 32;
    /** The candy apple's treat (Speed I or Jump Boost I). */
    int TREAT_TICKS = 20 * 20;
    /** A golden item's Speed II. */
    int GOLDEN_SPEED_TICKS = 30 * 20;
    /** A harvest treat's gentle Regeneration, and a golden one's Absorption. */
    int HARVEST_REGEN_TICKS = 5 * 20;
    int ABSORPTION_TICKS = 120 * 20;

    /**
     * The treat for {@code p}.
     *
     * @param touched eaten right away by a full bag's touch: no second title or burst (its pickup had them)
     */
    void eat(Player p, boolean golden, boolean touched, Random random);

    /**
     * The Candy Apple's, as 0.38.5: Speed I or Jump Boost I for 20 s for a red apple; Speed II for 30 s on top of
     * vanilla's golden apple for a golden one (and vanilla's own effects too when it was eaten by a touch).
     */
    static ShowerTreat candy() {
        return ShowerTreat::candyTreat;
    }

    /**
     * The Harvest Basket's: a few seconds of gentle Regeneration and a moment of Saturation for a treat; Speed II
     * for 30 s and two minutes of Absorption for the Golden Pie.
     */
    static ShowerTreat harvest() {
        return ShowerTreat::harvestTreat;
    }

    private static void candyTreat(Player p, boolean golden, boolean touched, Random random) {
        try {
            Location eye = p.getEyeLocation();
            Location inView = eye.clone().add(eye.getDirection().multiply(1.2));
            Location head = eye.clone().add(0, 0.5, 0);
            if (!golden) {
                boolean speedy = random.nextBoolean();
                p.addPotionEffect(new PotionEffect(speedy ? PotionEffectType.SPEED : PotionEffectType.JUMP_BOOST,
                        TREAT_TICKS, 0, false, true, true));
                particles(p, Particle.HEART, 6, inView, head, 0);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.2f);
                p.sendMessage(Text.of("&dYum! &7You feel " + (speedy ? "speedy" : "bouncy") + "!"));
                return;
            }
            if (touched) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 5 * 20, 1, false, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ABSORPTION_TICKS, 0, false, true,
                        true));
            }
            goldenTreat(p, touched, inView, head);
        } catch (RuntimeException ignored) {
            // cosmetic: the count never depends on the treat
        }
    }

    private static void harvestTreat(Player p, boolean golden, boolean touched, Random random) {
        try {
            Location eye = p.getEyeLocation();
            Location inView = eye.clone().add(eye.getDirection().multiply(1.2));
            Location head = eye.clone().add(0, 0.5, 0);
            if (!golden) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, HARVEST_REGEN_TICKS, 0, false, true,
                        true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 1, 0, false, false, false));
                particles(p, Particle.HEART, 6, inView, head, 0);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.2f);
                p.sendMessage(Text.of("&6Yum! &7You feel warm and cozy!"));
                return;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ABSORPTION_TICKS, 0, false, true, true));
            goldenTreat(p, touched, inView, head);
        } catch (RuntimeException ignored) {
            // cosmetic: the count never depends on the treat
        }
    }

    /** A golden item's Speed II, totem sparkles and level-up; the title and a burst unless a touch ate it. */
    private static void goldenTreat(Player p, boolean touched, Location inView, Location head) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, GOLDEN_SPEED_TICKS, 1, false, true, true));
        particles(p, Particle.TOTEM_OF_UNDYING, 20, inView, head, 0.1);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        if (!touched) {
            Fireworks.burstInView(p);
            p.showTitle(Title.title(Text.of("&6GOLDEN!"), Text.of("&eYou feel super speedy!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
        }
        p.sendMessage(Text.of("&6Yum! &7You feel super speedy!"));
    }

    /** The particles in view for the eater, and at their head for anyone near. */
    private static void particles(Player p, Particle particle, int count, Location inView, Location head,
                                  double speed) {
        p.spawnParticle(particle, inView, count, 0.3, 0.3, 0.3, speed);
        for (Player other : p.getWorld().getPlayers()) {
            if (!other.equals(p) && other.getLocation().distanceSquared(head) <= VIEW_RANGE * VIEW_RANGE) {
                other.spawnParticle(particle, head, count, 0.3, 0.3, 0.3, speed);
            }
        }
    }
}
