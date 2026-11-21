package com.dierks.homecraft.games.clubhouse;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The bar's fizzy drinks (0.50.0; the owner: "my daughter wanted to grab a bottle of 'Drink' some and it sure would be
 * fun if you could. And it would give you a random silly thing"). Tap a bottle on the 19th Hole's back bar: three
 * gulps, a bottle comes off the shelf (the shelf fills itself again within a few seconds), and something silly
 * happens to you for a little while. Nothing hurts, nothing is frightening, nothing needs reading: every one is a
 * picture, a sound or a feeling.
 *
 * <p><b>The silly things</b> ({@link Silly}): tiny you, super bouncy, floaty, zoomy, glowing, big burps and hiccups,
 * hearts, a rainbow trail, a music cloud. Each lasts {@link #SILLY_TICKS} or less; a new drink replaces the last one.
 * Potion effects are the plugin's own (the games' effect guard lets a plugin's through) and end when the visit does,
 * since leaving puts the player's own state back. Tiny you is a transient scale modifier (never saved with the
 * player), taken off when it runs out, when the player leaves the Clubhouse and when the Clubhouse stops.
 *
 * <p>Everything here runs on the main thread from the Clubhouse's tick.
 */
final class BarDrinks {

    /** How long a silly thing lasts (ticks): 20 seconds. */
    static final int SILLY_TICKS = 20 * 20;
    /** The gulps: one now, then these many ticks apart; the silly thing comes after the last. */
    static final int GULP_TICKS = 6;
    static final int GULPS = 3;
    /** A tap on the shelf within this long of the last is the same tap (both hands, a held button). */
    static final long TAP_MS = 1_200;
    /** Tiny you's size. */
    static final double TINY = 0.5;

    /** What a drink does. */
    enum Silly {
        TINY, BOUNCY, FLOATY, ZOOMY, GLOWY, BURPS, HEARTS, RAINBOW, MUSIC
    }

    private final Clubhouse club;
    private final NamespacedKey tinyKey;
    /** Each drinker: the drink in progress or the silly thing running. */
    private final Map<UUID, Drinker> drinkers = new HashMap<>();
    private final Map<UUID, Long> tapAt = new HashMap<>();

    private static final class Drinker {
        final Silly silly;
        /** Ticks since the first gulp. */
        int age;

        Drinker(Silly silly) {
            this.silly = silly;
        }
    }

    BarDrinks(Clubhouse club) {
        this.club = club;
        this.tinyKey = club.plugin() == null ? null : new NamespacedKey(club.plugin(), "clubhouse_tiny");
    }

    /** A pick of what a drink does, never the same twice in a row for one player when it can help it. */
    static Silly pick(Silly last, int roll) {
        Silly[] all = Silly.values();
        Silly s = all[Math.floorMod(roll, all.length)];
        if (s == last) {
            s = all[Math.floorMod(roll + 1, all.length)];
        }
        return s;
    }

    /**
     * {@code p} tapped a bottle on the back bar (the shelf block at {@code shelf}, the slot they aimed at or -1): drink
     * it. Taps too close together are one tap.
     */
    void drink(Player p, org.bukkit.block.Block shelf, int slot, long now) {
        UUID id = p.getUniqueId();
        Long last = tapAt.get(id);
        if (last != null && now - last < TAP_MS) {
            return;
        }
        tapAt.put(id, now);
        if (!takeBottle(shelf, slot)) {
            p.playSound(p.getLocation(), Sound.BLOCK_GLASS_HIT, 0.6f, 1.4f); // an empty place: nothing to drink
            return;
        }
        Drinker was = drinkers.get(id);
        if (was != null) {
            end(p, was.silly); // a new drink replaces the last silly thing
        }
        Silly s = pick(was == null ? null : was.silly, ThreadLocalRandom.current().nextInt(1 << 16));
        drinkers.put(id, new Drinker(s));
        p.swingMainHand();
        gulp(p);
    }

    /** A bottle comes off the shelf: the one aimed at, else any. False when the shelf is empty. */
    private static boolean takeBottle(org.bukkit.block.Block shelf, int slot) {
        if (!(shelf.getState() instanceof org.bukkit.block.Shelf s)) {
            return false;
        }
        org.bukkit.inventory.Inventory inv = s.getInventory();
        int size = Math.min(3, inv.getSize());
        int take = -1;
        if (slot >= 0 && slot < size && filled(inv.getItem(slot))) {
            take = slot;
        } else {
            for (int i = 0; i < size && take < 0; i++) {
                if (filled(inv.getItem(i))) {
                    take = i;
                }
            }
        }
        if (take < 0) {
            return false;
        }
        inv.setItem(take, null); // stocked again by ClubDecor.stockBar within its few seconds
        return true;
    }

    private static boolean filled(org.bukkit.inventory.ItemStack i) {
        return i != null && !i.getType().isAir();
    }

    private static void gulp(Player p) {
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.1f);
    }

    /** Every tick: the gulps, then the silly thing, then its end. */
    void tick() {
        if (drinkers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Drinker>> it = drinkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Drinker> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Drinker d = en.getValue();
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            d.age++;
            int start = GULP_TICKS * (GULPS - 1) + 4;
            if (d.age < start) {
                if (d.age % GULP_TICKS == 0) {
                    gulp(p);
                }
                continue;
            }
            int t = d.age - start;
            if (t == 0) {
                begin(p, d.silly);
            } else if (t >= SILLY_TICKS) {
                end(p, d.silly);
                it.remove();
            } else {
                during(p, d.silly, t);
            }
        }
    }

    /** The silly thing starts: a pop and a sparkle, then its own feeling. */
    private void begin(Player p, Silly s) {
        Location at = p.getLocation().add(0, 1, 0);
        p.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 12, 0.4, 0.5, 0.4, 0);
        int secs = SILLY_TICKS;
        switch (s) {
            case TINY -> {
                scale(p, TINY);
                p.getWorld().playSound(at, Sound.ENTITY_BAT_TAKEOFF, 0.5f, 2.0f);
            }
            case BOUNCY -> effect(p, PotionEffectType.JUMP_BOOST, secs, 2);
            case FLOATY -> {
                effect(p, PotionEffectType.LEVITATION, 30, 0); // up a little way, then a slow drift down
                effect(p, PotionEffectType.SLOW_FALLING, secs, 0);
            }
            case ZOOMY -> effect(p, PotionEffectType.SPEED, secs, 2);
            case GLOWY -> {
                effect(p, PotionEffectType.GLOWING, secs, 0);
                p.getWorld().spawnParticle(Particle.END_ROD, at, 25, 0.4, 0.7, 0.4, 0.03);
            }
            case BURPS -> p.getWorld().playSound(at, Sound.ENTITY_PLAYER_BURP, 1.0f, 0.7f);
            default -> {
            }
        }
    }

    /** While it lasts: the ones that are particles and sounds keep going. */
    private void during(Player p, Silly s, int t) {
        Location feet = p.getLocation();
        Location head = feet.clone().add(0, 2.1 * (s == Silly.TINY ? TINY : 1), 0);
        switch (s) {
            case BURPS -> {
                if (t % 50 == 25 && t < SILLY_TICKS - 40) { // a hiccup every couple of seconds
                    p.getWorld().playSound(feet, Sound.ENTITY_PLAYER_BURP, 0.6f, 1.6f);
                    p.getWorld().spawnParticle(Particle.BUBBLE_POP, head, 6, 0.2, 0.1, 0.2, 0.02);
                }
            }
            case HEARTS -> {
                if (t % 10 == 0) {
                    p.getWorld().spawnParticle(Particle.HEART, head, 1, 0.4, 0.2, 0.4, 0);
                }
            }
            case RAINBOW -> {
                if (t % 2 == 0) {
                    p.getWorld().spawnParticle(Particle.DUST, feet.clone().add(0, 0.15, 0), 2, 0.15, 0.05, 0.15, 0,
                            new Particle.DustOptions(rainbow(t), 1.4f));
                }
            }
            case MUSIC -> {
                if (t % 8 == 0 && t < SILLY_TICKS / 2) {
                    float pitch = (float) Math.pow(2, (ThreadLocalRandom.current().nextInt(13) - 12) / 12.0) * 2f;
                    p.getWorld().playSound(feet, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, Math.min(2f, pitch));
                    p.getWorld().spawnParticle(Particle.NOTE, head, 1, 0.4, 0.2, 0.4, 1);
                }
            }
            case GLOWY -> {
                if (t % 20 == 0) {
                    p.getWorld().spawnParticle(Particle.GLOW, head, 3, 0.3, 0.3, 0.3, 0);
                }
            }
            default -> {
            }
        }
    }

    /**
     * The shelf place a tap at {@code hit} (inside the block, 0 to 1 each way) aimed at, on a shelf facing
     * {@code facing}: 0, 1, 2 from the left of someone looking at it, or -1. (Not seen on a server: if the bottle that
     * comes off is the mirror of the one tapped, the left is the other side.)
     */
    static int slotAt(String facing, double hx, double hz) {
        double u = switch (facing) {
            case "east" -> 1 - hz;
            case "west" -> hz;
            case "north" -> 1 - hx;
            case "south" -> hx;
            default -> -1;
        };
        return u < 0 || u > 1 ? -1 : Math.min(2, (int) Math.floor(u * 3));
    }

    /** The colours of a rainbow, one after another. */
    static Color rainbow(int t) {
        Color[] bow = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.BLUE, Color.PURPLE};
        return bow[Math.floorMod(t / 6, bow.length)];
    }

    /** The silly thing is over: anything it changed goes back (its effects end by themselves). */
    private void end(Player p, Silly s) {
        if (s == Silly.TINY) {
            scale(p, 1);
        }
        for (PotionEffectType type : new PotionEffectType[]{PotionEffectType.JUMP_BOOST, PotionEffectType.LEVITATION,
                PotionEffectType.SLOW_FALLING, PotionEffectType.SPEED, PotionEffectType.GLOWING}) {
            PotionEffect e = p.getPotionEffect(type);
            if (e != null && e.getDuration() <= SILLY_TICKS && sillyOf(s, type)) {
                p.removePotionEffect(type);
            }
        }
    }

    /** Whether {@code type} is one {@code s} gave (only those are taken off). */
    static boolean sillyOf(Silly s, PotionEffectType type) {
        return switch (s) {
            case BOUNCY -> type == PotionEffectType.JUMP_BOOST;
            case FLOATY -> type == PotionEffectType.LEVITATION || type == PotionEffectType.SLOW_FALLING;
            case ZOOMY -> type == PotionEffectType.SPEED;
            case GLOWY -> type == PotionEffectType.GLOWING;
            default -> false;
        };
    }

    private static void effect(Player p, PotionEffectType type, int ticks, int amplifier) {
        p.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, false, true));
    }

    /** Tiny you: a transient modifier (never saved with the player); {@code 1} takes it off. */
    private void scale(Player p, double size) {
        if (tinyKey == null) {
            return;
        }
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a == null) {
            return;
        }
        a.removeModifier(tinyKey);
        if (size != 1) {
            a.addTransientModifier(new AttributeModifier(tinyKey, size - 1,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
    }

    /** {@code id} left the Clubhouse (or quit): whatever their drink changed goes back now. */
    void gone(UUID id, Player p) {
        tapAt.remove(id);
        Drinker d = drinkers.remove(id);
        if (p != null && d != null) {
            end(p, d.silly);
        }
        if (p != null) {
            scale(p, 1); // whatever happened, nobody leaves tiny
        }
    }

    /** The Clubhouse stops: everyone back to their own size. */
    void stop() {
        for (UUID id : drinkers.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                end(p, drinkers.get(id).silly);
                scale(p, 1);
            }
        }
        drinkers.clear();
        tapAt.clear();
    }
}
