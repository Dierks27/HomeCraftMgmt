package com.dierks.homecraft.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The present's burst in view (HALLOWEEN-SPEC D8; review BK-2): 4 blocks ahead in the open, and on this side
 * of a nearer wall, so a child indoors or in a tunnel still sees it instead of a rocket going off inside the
 * wall or behind it. And every firework, the Mega show's chosen looks too (0.38.1) and the Grand show's holiday looks
 * (0.38.2), is the harmless kind.
 */
class FireworksTest {

    @Test
    void inTheOpenItBurstsFourBlocksAhead() {
        assertEquals(4.0, Fireworks.burstDistance(-1), 1e-9, "nothing in the way");
        assertEquals(4.0, Fireworks.burstDistance(4.0), 1e-9, "a wall just at 4 is no nearer");
    }

    /**
     * Every spawn here, the random ones, the Mega Firework Show's chosen looks (0.38.1) and a holiday's look at a
     * flight power (0.38.2, the Grand show in a window), goes through the one
     * {@code world.spawn} that tags {@code FIREWORK_SHOW}, whose damage the prize listener cancels: a show never hurts
     * the child it is for, or her pet.
     */
    @Test
    void everySpawnGoesThroughTheOneTaggedSpawn() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/util/Fireworks.java"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertEquals(1, count(src, ".spawn(at, Firework.class"), "one world spawn");
        assertEquals(1, count(src, "Keys.FIREWORK_SHOW"), "and one tag, in it");
        assertTrue(src.indexOf(".spawn(at, Firework.class") < src.indexOf("Keys.FIREWORK_SHOW"));
        assertTrue(src.contains("private static void launch(Location at, FireworkEffect effect, int power,"
                + " int ticksToDetonate) {"), "the one spawn");
        assertEquals(2, count(src, "private static void launch("), "the random look's, and the one spawn");
        assertTrue(src.contains("launch(at, FireworkEffect.builder()"), "the random look goes through the one spawn");
        assertTrue(src.contains("launch(at, look, 1, ticksToDetonate);"), "and so does the Mega show's");
        assertTrue(src.contains("launch(at, look, Math.max(0, Math.min(3, power)), -1);"),
                "and a holiday look at a power (0.38.2)");
        assertTrue(src.contains("public static void spawn(Location at, int power, List<Color> colours, Color fade,"),
                "its spawn");
        assertEquals(0, count(src, "world.spawn(") + count(src, "getWorld().spawnEntity("), "no other spawn");
        String chosen = src.substring(src.indexOf("public static void spawn(Location at, List<Color> colours"));
        assertTrue(chosen.indexOf("fw.setFireworkMeta(meta);") < chosen.indexOf("fw.setTicksToDetonate("),
                "the look first, then the flight, so a volley bursts on one tick");
    }

    private static int count(String src, String what) {
        int n = 0;
        for (int at = src.indexOf(what); at >= 0; at = src.indexOf(what, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aNearerWallBringsItCloser() {
        assertEquals(2.3, Fireworks.burstDistance(3.0), 1e-9, "0.7 short of a wall 3 blocks away");
        assertEquals(1.5, Fireworks.burstDistance(2.2), 1e-9);
        assertEquals(1.5, Fireworks.burstDistance(0.4), 1e-9, "never nearer than 1.5, in her face");
    }
}
