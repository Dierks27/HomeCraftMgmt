package com.dierks.homecraft.command;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.external.vavr.control.Option;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code /hcm games check}'s read of a world's Multiverse game mode: Multiverse-Core 5's {@code Option}
 * is unwrapped though its vavr is shaded under Multiverse's own package (the 5.8.1 "can't tell" of the
 * live test, 2 Oct), any Optional-like value is unwrapped by its shape, the loaded-world finder is tried
 * too, only a real game mode is believed, and Multiverse-Core 5's {@code worlds.yml} is read as the
 * last resort.
 */
class MvGameModeTest {

    /** Multiverse-Core 5's world, as far as the check uses it. */
    public static final class FakeWorld {
        private final Object mode;

        FakeWorld(Object mode) {
            this.mode = mode;
        }

        public Object getGameMode() {
            return mode;
        }
    }

    /**
     * Multiverse-Core 5's {@code WorldManager}: {@code getWorld} and {@code getLoadedWorld} by name, plus
     * an overload.
     */
    public static final class FakeWorldManager {
        private final Option<FakeWorld> any;
        private final Option<FakeWorld> loaded;

        FakeWorldManager(Option<FakeWorld> any, Option<FakeWorld> loaded) {
            this.any = any;
            this.loaded = loaded;
        }

        /** The {@code getWorld(World)} overload: never the one called with a name. */
        public Option<FakeWorld> getWorld(Integer notAName) {
            throw new AssertionError("getWorld(String) should be called with a name");
        }

        public Option<FakeWorld> getWorld(String name) {
            return "games".equals(name) ? any : Option.none();
        }

        public Option<FakeWorld> getLoadedWorld(String name) {
            return "games".equals(name) ? loaded : Option.none();
        }
    }

    /** Only {@code isEmpty()} and {@code get()}. */
    public static final class EmptyGet {
        private final Object value;

        EmptyGet(Object value) {
            this.value = value;
        }

        public boolean isEmpty() {
            return value == null;
        }

        public Object get() {
            if (value == null) {
                throw new IllegalStateException("empty");
            }
            return value;
        }
    }

    /** Only {@code isDefined()} and {@code get()}. */
    public static final class DefinedGet {
        private final Object value;

        DefinedGet(Object value) {
            this.value = value;
        }

        public boolean isDefined() {
            return value != null;
        }

        public Object get() {
            if (value == null) {
                throw new IllegalStateException("empty");
            }
            return value;
        }
    }

    @Test
    void multiverseFivesShadedOptionIsUnwrapped() throws Exception {
        Option<String> some = Option.of("games");
        assertFalse(some.getClass().getName().startsWith("io.vavr."),
                "the stand-in sits where Multiverse-Core 5 shades vavr, not in vavr's own package");
        assertEquals("games", MvGameMode.unwrap(some), "a shaded Some gives its value");
        assertNull(MvGameMode.unwrap(Option.none()), "a shaded None gives nothing, without calling get()");
    }

    @Test
    void optionLikeValuesAreUnwrappedByTheirShape() throws Exception {
        assertEquals("x", MvGameMode.unwrap(Optional.of("x")), "java.util.Optional");
        assertNull(MvGameMode.unwrap(Optional.empty()), "an empty Optional");
        assertEquals("x", MvGameMode.unwrap(new EmptyGet("x")), "isEmpty() plus get()");
        assertNull(MvGameMode.unwrap(new EmptyGet(null)), "an empty isEmpty()/get() one, get() never called");
        assertEquals("x", MvGameMode.unwrap(new DefinedGet("x")), "isDefined() plus get()");
        assertNull(MvGameMode.unwrap(new DefinedGet(null)), "an empty isDefined()/get() one, get() never called");
        assertNull(MvGameMode.unwrap(null), "nothing stays nothing");
        FakeWorld world = new FakeWorld(GameMode.ADVENTURE);
        assertSame(world, MvGameMode.unwrap(world), "a plain value comes back as it is");
        assertEquals("adventure", MvGameMode.unwrap("adventure"), "a string (isEmpty() but no get()) is a plain value");
        List<String> list = List.of("a");
        assertSame(list, MvGameMode.unwrap(list), "a list (isEmpty() but only get(int)) is a plain value");
    }

    @Test
    void multiverseFivesWorldManagerGivesTheModeThroughItsShadedOption() {
        FakeWorldManager mv = new FakeWorldManager(Option.of(new FakeWorld(GameMode.ADVENTURE)), Option.none());
        assertEquals("ADVENTURE", MvGameMode.fromWorldManager(mv, "games"),
                "getWorld(name) -> shaded Option<MultiverseWorld> -> getGameMode(): the 5.8.1 case");
        assertNull(MvGameMode.fromWorldManager(mv, "elsewhere"), "a world Multiverse doesn't know: can't tell");
        assertNull(MvGameMode.fromWorldManager(null, "games"), "no world manager: can't tell");
    }

    @Test
    void theLoadedWorldIsAskedWhenGetWorldCantAnswer() {
        FakeWorldManager mv = new FakeWorldManager(Option.none(), Option.of(new FakeWorld(GameMode.CREATIVE)));
        assertEquals("CREATIVE", MvGameMode.fromWorldManager(mv, "games"), "getLoadedWorld(name) answers");

        FakeWorldManager odd = new FakeWorldManager(Option.of(new FakeWorld("Some(ADVENTURE)")),
                Option.of(new FakeWorld(GameMode.ADVENTURE)));
        assertEquals("ADVENTURE", MvGameMode.fromWorldManager(odd, "games"),
                "a mode that isn't a game mode isn't believed, and the next finder is asked");
    }

    @Test
    void onlyARealGameModeIsBelieved() throws Exception {
        assertEquals("ADVENTURE", MvGameMode.name(GameMode.ADVENTURE), "the enum");
        assertEquals("SURVIVAL", MvGameMode.name("sUrvivAl"), "a name in any case, as worlds.yml allows");
        assertEquals("SPECTATOR", MvGameMode.name(Option.of(GameMode.SPECTATOR)), "a wrapped mode");
        assertNull(MvGameMode.name(Option.none()), "an empty one: can't tell");
        assertNull(MvGameMode.name("Some(ADVENTURE)"), "an unwrapped Option's text isn't a mode");
        assertNull(MvGameMode.name(null), "nothing: can't tell");
    }

    @Test
    void multiverseFivesWorldsFileIsRead() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                world:
                  alias: my world
                  gamemode: survival
                games:
                  alias: ''
                  entry-fee:
                    enabled: false
                    amount: 0.0
                  gamemode: adventure
                  generator: HomeCraftManagement
                Games:
                  gamemode: creative
                world[dot]a:
                  gamemode: CREATIVE
                minecraft:new[dot]world:
                  gamemode: spectator
                myplugin:arena:
                  gamemode: sUrvivAl
                blank:
                  alias: no mode
                odd:
                  gamemode: adventur
                """);
        assertEquals("ADVENTURE", MvGameMode.fromWorldsFile(file, "games"), "the Games world, written in lower case");
        assertEquals("CREATIVE", MvGameMode.fromWorldsFile(file, "Games"),
                "the exact name wins over a case-only match");
        assertEquals("SURVIVAL", MvGameMode.fromWorldsFile(file, "WORLD"), "a name matched with case ignored");
        assertEquals("CREATIVE", MvGameMode.fromWorldsFile(file, "world.a"), "a dot is written [dot]");
        assertEquals("SPECTATOR", MvGameMode.fromWorldsFile(file, "new.world"),
                "a minecraft: key is the world of that name");
        assertEquals("SURVIVAL", MvGameMode.fromWorldsFile(file, "myplugin_arena"),
                "another namespace's key is namespace_key");
        assertNull(MvGameMode.fromWorldsFile(file, "nether"), "a world it doesn't list: can't tell");
        assertNull(MvGameMode.fromWorldsFile(file, "blank"), "no gamemode written: can't tell");
        assertNull(MvGameMode.fromWorldsFile(file, "odd"), "a mode that isn't one: can't tell");
        assertNull(MvGameMode.fromWorldsFile(null, "games"), "no file: can't tell");
        assertNull(MvGameMode.fromWorldsFile(file, ""), "no world: can't tell");
    }

    @Test
    void worldsFileKeysNameTheirWorlds() {
        assertEquals("games", MvGameMode.worldOf("games"), "a plain name");
        assertEquals("world.a.b", MvGameMode.worldOf("world[dot]a[dot]b"), "[dot] is a dot");
        assertEquals("games", MvGameMode.worldOf("minecraft:games"), "a minecraft: key");
        assertEquals("myplugin_games", MvGameMode.worldOf("myplugin:games"), "another namespace");
    }
}
