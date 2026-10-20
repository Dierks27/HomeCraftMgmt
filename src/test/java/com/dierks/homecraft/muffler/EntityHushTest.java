package com.dierks.homecraft.muffler;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a muffler silences the minecarts or bees in its box: the two loops each player's game plays
 * by itself, which only the entity's silent flag can stop.
 */
class EntityHushTest {

    private static final UUID OWNER = UUID.randomUUID();

    private static Muffler at(int x) {
        return Muffler.placed(new MufflerPos("world", x, 64, 0), OWNER, 4, 25);
    }

    @Test
    void theMinecartsGroupSetToSilentSilencesMinecarts() {
        Muffler m = at(0).withGroup("minecarts", MuffleLevel.SILENT);
        assertTrue(EntityHush.silences(m, EntityHush.Kind.MINECART));
        assertFalse(EntityHush.silences(m, EntityHush.Kind.BEE));
        assertEquals(EnumSet.of(EntityHush.Kind.MINECART), EntityHush.kindsSilencedBy(m));
    }

    @Test
    void nothingIsSilencedByDefaultOrWhenSwitchedOff() {
        assertTrue(EntityHush.kindsSilencedBy(at(0)).isEmpty());
        Muffler off = at(0).withGroup("minecarts", MuffleLevel.SILENT).withEnabled(false);
        assertTrue(EntityHush.kindsSilencedBy(off).isEmpty(), "a switched-off muffler silences nothing");
    }

    @Test
    void quieterBeesDoNotSilenceTheBeeOnlySilentDoes() {
        assertFalse(EntityHush.silences(at(0).withGroup("bees", MuffleLevel.QUIETER), EntityHush.Kind.BEE),
                "Quieter can't turn a buzz down, and must not silence the whole bee either");
        assertTrue(EntityHush.silences(at(0).withGroup("bees", MuffleLevel.SILENT), EntityHush.Kind.BEE));
    }

    @Test
    void aSinglePickOfTheLoopWorksBothWays() {
        Muffler picked = at(0).withSound("minecraft:entity.minecart.riding", MuffleLevel.SILENT);
        assertTrue(EntityHush.silences(picked, EntityHush.Kind.MINECART), "picking the rumble alone works");
        Muffler exception = at(0).withGroup("minecarts", MuffleLevel.SILENT)
                .withSound("minecraft:entity.minecart.riding", MuffleLevel.ALLOW);
        assertFalse(EntityHush.silences(exception, EntityHush.Kind.MINECART),
                "Always play on the rumble is the exception to a silenced Minecarts group");
    }

    @Test
    void onlyTheBoxThatHoldsTheEntityCounts() {
        List<Muffler> zones = List.of(at(0).withGroup("minecarts", MuffleLevel.SILENT), at(40));
        assertTrue(EntityHush.shouldSilence(zones, EntityHush.Kind.MINECART, 3.5, 64.0, 0.5));
        assertFalse(EntityHush.shouldSilence(zones, EntityHush.Kind.MINECART, 5.0, 64.0, 0.5),
                "x = 5.0 is just outside a range-4 box around x = 0");
        assertFalse(EntityHush.shouldSilence(zones, EntityHush.Kind.MINECART, 40.5, 64.0, 0.5),
                "the other muffler doesn't silence minecarts");
        assertFalse(EntityHush.shouldSilence(zones, EntityHush.Kind.BEE, 0.5, 64.0, 0.5));
    }

    @Test
    void overlappingBoxesAnyOneSilencingIsEnough() {
        Muffler mine = at(0).withGroup("minecarts", MuffleLevel.SILENT);
        Muffler neighbours = at(2).withSound("minecraft:entity.minecart.riding", MuffleLevel.ALLOW);
        assertTrue(EntityHush.shouldSilence(List.of(neighbours, mine), EntityHush.Kind.MINECART, 1.5, 64.0, 0.5),
                "a neighbour's Always play can't undo my Silent");
    }

    @Test
    void eachKindIsDecidedByItsLoop() {
        assertEquals(EntityHush.Kind.MINECART, EntityHush.Kind.ofLoop("minecraft:entity.minecart.riding"));
        assertEquals(EntityHush.Kind.BEE, EntityHush.Kind.ofLoop("minecraft:entity.bee.loop"));
        assertNull(EntityHush.Kind.ofLoop("minecraft:entity.minecart.inside"));
        for (EntityHush.Kind k : EntityHush.Kind.values()) {
            assertTrue(SoundNames.isKey(k.loop()), k.loop());
            assertFalse(SoundGroups.of(k.loop()).isEmpty(), k.loop() + " belongs to a group a player can click");
        }
    }

    @Test
    void aSilentPickOfAnotherLoopSilencesTheKindToo() {
        Muffler rider = at(0).withSound("minecraft:entity.minecart.inside", MuffleLevel.SILENT);
        assertTrue(EntityHush.silences(rider, EntityHush.Kind.MINECART),
                "the rider's rumble can only be stopped by silencing the cart");
        Muffler angry = at(0).withSound("minecraft:entity.bee.loop_aggressive", MuffleLevel.SILENT);
        assertTrue(EntityHush.silences(angry, EntityHush.Kind.BEE));
        Muffler allowOther = at(0).withSound("minecraft:entity.minecart.inside", MuffleLevel.ALLOW);
        assertFalse(EntityHush.silences(allowOther, EntityHush.Kind.MINECART));
        Muffler quieterOther = at(0).withSound("minecraft:entity.bee.loop_aggressive", MuffleLevel.QUIETER);
        assertFalse(EntityHush.silences(quieterOther, EntityHush.Kind.BEE), "only Silent silences");
    }

    @Test
    void soundsAreTiedToTheEntityThatMakesThem() {
        assertEquals(EntityHush.Kind.BEE, EntityHush.Kind.ofKey("minecraft:entity.bee.sting"));
        assertEquals(EntityHush.Kind.MINECART, EntityHush.Kind.ofKey("minecraft:entity.minecart.inside"));
        assertNull(EntityHush.Kind.ofKey("minecraft:block.beehive.work"), "a hive is a block, not a bee");
        assertNull(EntityHush.Kind.ofKey("minecraft:entity.beetle.x"), "the prefix ends with a dot");
        assertNull(EntityHush.Kind.ofKey(null));
        assertEquals(EntityHush.Kind.MINECART, EntityHush.Kind.ofAnyLoop("minecraft:entity.minecart.inside.underwater"));
        assertEquals(EntityHush.Kind.BEE, EntityHush.Kind.ofAnyLoop("minecraft:entity.bee.loop"));
        assertNull(EntityHush.Kind.ofAnyLoop("minecraft:entity.bee.sting"));
    }

    @Test
    void aTaggedEntityIsAlwaysOursAndAnotherSilentOneNever() {
        assertEquals(EntityHusher.Claim.OURS, EntityHusher.claim(false, true, true),
                "a bee back out of its hive: new UUID, our tag, still silent — ours, so it can be given back");
        assertEquals(EntityHusher.Claim.OURS, EntityHusher.claim(true, true, true));
        assertEquals(EntityHusher.Claim.OURS, EntityHusher.claim(true, false, false),
                "on our list but un-silenced by someone: still ours, silenced again");
        assertEquals(EntityHusher.Claim.NOT_OURS, EntityHusher.claim(false, false, true),
                "silent by /data or another plugin: never touched");
        assertEquals(EntityHusher.Claim.NEW, EntityHusher.claim(false, false, false));
    }

    /** A stand-in entity of the given Bukkit type: enough for instanceof, nothing else is called. */
    private static org.bukkit.entity.Entity fake(Class<? extends org.bukkit.entity.Entity> type) {
        return (org.bukkit.entity.Entity) java.lang.reflect.Proxy.newProxyInstance(
                EntityHushTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void tntMinecartsAreNeverSilencedEveryOtherCartAndBeesAre() {
        assertNull(EntityHusher.kindOf(fake(org.bukkit.entity.minecart.ExplosiveMinecart.class)),
                "a silent TNT minecart would prime without its fuse hiss");
        for (Class<? extends org.bukkit.entity.Entity> cart : List.of(
                org.bukkit.entity.minecart.HopperMinecart.class, org.bukkit.entity.minecart.RideableMinecart.class,
                org.bukkit.entity.minecart.StorageMinecart.class, org.bukkit.entity.minecart.PoweredMinecart.class,
                org.bukkit.entity.minecart.SpawnerMinecart.class, org.bukkit.entity.minecart.CommandMinecart.class)) {
            assertEquals(EntityHush.Kind.MINECART, EntityHusher.kindOf(fake(cart)), cart.getSimpleName());
        }
        assertEquals(EntityHush.Kind.BEE, EntityHusher.kindOf(fake(org.bukkit.entity.Bee.class)));
        assertNull(EntityHusher.kindOf(fake(org.bukkit.entity.Chicken.class)));
    }

    @Test
    void theMinecartsButtonOnlyGoesNormalAndSilent() {
        SoundGroups.SoundGroup carts = SoundGroups.byId("minecarts");
        assertFalse(carts.quieter());
        assertEquals(MuffleLevel.SILENT, carts.next(null, false));
        assertNull(carts.next(MuffleLevel.SILENT, false));
        assertEquals(MuffleLevel.SILENT, carts.next(null, true), "right-click toggles the same way");
        assertEquals(MuffleLevel.SILENT, carts.next(MuffleLevel.QUIETER, false),
                "a Quieter that reached the database some other way moves on to Silent");

        SoundGroups.SoundGroup chickens = SoundGroups.byId("chickens");
        assertTrue(chickens.quieter());
        assertEquals(MuffleLevel.QUIETER, chickens.next(null, false));
        assertEquals(MuffleLevel.SILENT, chickens.next(null, true));
    }

    @Test
    void everyMinecartSoundIsInTheMinecartsGroup() {
        Set<String> ids = Set.of("minecarts");
        for (String key : List.of("minecraft:entity.minecart.riding", "minecraft:entity.minecart.inside",
                "minecraft:entity.minecart.inside.underwater")) {
            assertEquals(ids, Set.copyOf(SoundGroups.of(key).stream().map(SoundGroups.SoundGroup::id).toList()), key);
        }
    }
}
