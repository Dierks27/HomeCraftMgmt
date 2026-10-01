package com.dierks.homecraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the plugin enables, as plugin.yml orders it: before Multiverse-Core, so the built-in void
 * generator is there when Multiverse loads the Games world at a restart.
 *
 * <p>Bukkit hands a world only the generator of a plugin that is already enabled
 * ({@code WorldCreator.getGeneratorForName}, and the server's own bukkit.yml lookup, both refuse one
 * that isn't). Multiverse loads its worlds while it enables. A plugin enabled after it (a soft
 * dependency on it) leaves {@code sky}, made with {@code /mv create sky normal -g HomeCraftManagement},
 * with vanilla terrain, biomes and mobs in every chunk generated after the first restart.
 *
 * <p>What used to rely on Multiverse going first is pinned too: a join handler that looks at where the
 * player is or what they carry runs after Multiverse's own (which may send them elsewhere and swap their
 * things), at a higher priority now that registering first no longer orders it. (Multiverse-Inventories
 * now disables first as well: {@code SessionCoreTest}'s Multiverse-Inventories cases.)
 */
class PluginLoadOrderTest {

    private static YamlConfiguration pluginYml() throws Exception {
        try (InputStream in = PluginLoadOrderTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void thePluginEnablesBeforeMultiverseSoItsVoidGeneratorIsThereWhenMultiverseLoadsTheGamesWorld()
            throws Exception {
        YamlConfiguration y = pluginYml();
        assertTrue(y.getStringList("loadbefore").contains("Multiverse-Core"),
                "loadbefore names Multiverse-Core, so the void generator is enabled when Multiverse loads 'sky': "
                        + y.getStringList("loadbefore"));
        assertTrue(y.getStringList("loadbefore").contains("JEIServerProxy"),
                "and still JEIServerProxy, which snapshots the recipes when it enables");
    }

    @Test
    void neitherMultiverseCoreNorMultiverseInventoriesIsADependencySoThereIsNoLoop() throws Exception {
        YamlConfiguration y = pluginYml();
        for (String key : List.of("softdepend", "depend")) {
            List<String> names = y.getStringList(key);
            assertFalse(names.contains("Multiverse-Core"), key + " can't name Multiverse-Core: it would enable this"
                    + " plugin after Multiverse has loaded its worlds, and loadbefore names it too: " + names);
            assertFalse(names.contains("Multiverse-Inventories"), key + " can't name Multiverse-Inventories: it"
                    + " depends on Multiverse-Core, which this plugin loads before, so Paper would find a loop: "
                    + names);
        }
    }

    @Test
    void thePluginStillEnablesAfterTheServersOwnWorldsAreLoaded() throws Exception {
        YamlConfiguration y = pluginYml();
        assertFalse("STARTUP".equalsIgnoreCase(y.getString("load", "POSTWORLD")),
                "load: STARTUP would enable it before the main world exists, which the config migration and every"
                        + " service reading a world at enable rely on");
    }

    @Test
    void joinHandlersThatLookAtTheWorldOrTheBagRunAfterMultiversesOwn() throws Exception {
        for (Class<?> listener : List.of(com.dierks.homecraft.courier.CourierListener.class,
                com.dierks.homecraft.arcade.ArcadeListener.class)) {
            EventHandler h = listener.getMethod("onJoin", PlayerJoinEvent.class).getAnnotation(EventHandler.class);
            assertNotNull(h, listener.getSimpleName() + ".onJoin is a handler");
            assertTrue(h.priority().getSlot() > EventPriority.NORMAL.getSlot(), listener.getSimpleName() + ".onJoin runs"
                    + " after Multiverse-Core's and Multiverse-Inventories' join handlers (NORMAL), which may move the"
                    + " player and swap their things; registered before them now, at NORMAL it would run first: "
                    + h.priority());
        }
    }
}
