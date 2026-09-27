package com.dierks.homecraft.config;

import com.dierks.homecraft.market.sim.SimSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every live-market sound ships NAMESPACED ({@code minecraft:block.note_block.bell}).
 *
 * <p>{@code AnnounceService.sound(String)} turns a key without a {@code :} into a dotted path by
 * replacing every {@code _} with {@code .}, so the enum-style {@code BLOCK_NOTE_BLOCK_BELL}
 * becomes {@code block.note.block.bell} — a sound that does not exist — and the lookup
 * silently falls back to the XP-orb pickup. A namespaced key skips that rewrite and resolves
 * as written. Pinned here: every shipped {@code market.sim.announce.sound_*} (and any other
 * sound key under {@code market.sim}) contains {@code :} and is a well-formed lower-case key;
 * the shipped keys equal the code defaults, which are namespaced too; and a plain name in a
 * live config is replaced by the shipped sound with a WARN rather than passed through.
 *
 * <p>Needs paper-api on the test classpath for {@link YamlConfiguration}; no server is started.
 */
class SoundKeysTest {

    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final List<String> SOUND_KEYS = List.of("sound_up", "sound_down", "sound_story", "sound_other");

    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = SoundKeysTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    @Test
    void everyShippedAnnounceSoundIsNamespaced() throws Exception {
        ConfigurationSection announce = bundled().getConfigurationSection("market.sim.announce");
        assertNotNull(announce, "config.yml ships market.sim.announce");
        List<String> sounds = new ArrayList<>();
        for (String key : announce.getKeys(false)) {
            if (key.startsWith("sound_")) {
                sounds.add(key);
            }
        }
        assertEquals(SOUND_KEYS, sounds, "the four announcement sounds, in file order");
        for (String key : sounds) {
            String value = announce.getString(key);
            assertNotNull(value, key);
            assertTrue(value.contains(":"), "market.sim.announce." + key + " \"" + value
                    + "\" has no namespace: AnnounceService would play the XP orb instead");
            assertTrue(NAMESPACED.matcher(value).matches(), key + " \"" + value + "\" is not a lower-case sound key");
            assertTrue(value.startsWith("minecraft:"), key + " should be a vanilla sound");
        }
    }

    /** A sound key anywhere under market.sim, not only the four we know about today. */
    @Test
    void noSoundUnderMarketSimLacksANamespace() throws Exception {
        YamlConfiguration c = bundled();
        ConfigurationSection sim = c.getConfigurationSection("market.sim");
        assertNotNull(sim);
        int seen = 0;
        for (String key : sim.getKeys(true)) {
            String leaf = key.substring(key.lastIndexOf('.') + 1);
            if (leaf.startsWith("sound") && !sim.isConfigurationSection(key)) {
                seen++;
                assertTrue(String.valueOf(sim.get(key)).contains(":"), "market.sim." + key + " = " + sim.get(key));
            }
        }
        assertEquals(SOUND_KEYS.size(), seen);
    }

    @Test
    void theShippedSoundsAreTheCodeDefaults() throws Exception {
        SimSettings.Announce d = SimSettings.Announce.defaults();
        for (String s : List.of(d.soundUp(), d.soundDown(), d.soundStory(), d.soundOther())) {
            assertTrue(NAMESPACED.matcher(s).matches(), "code default " + s + " must be namespaced");
        }
        YamlConfiguration c = bundled();
        assertEquals(d.soundUp(), c.getString("market.sim.announce.sound_up"));
        assertEquals(d.soundDown(), c.getString("market.sim.announce.sound_down"));
        assertEquals(d.soundStory(), c.getString("market.sim.announce.sound_story"));
        assertEquals(d.soundOther(), c.getString("market.sim.announce.sound_other"));
        assertEquals(d, MarketSimConfig.parse(c, w -> { }).settings().announce());
    }

    /** The trap itself: an enum-style name in a live config never reaches AnnounceService. */
    @Test
    void aPlainNameInALiveConfigIsReplacedWithAWarning() throws Exception {
        YamlConfiguration c = bundled();
        c.set("market.sim.announce.sound_up", "BLOCK_NOTE_BLOCK_BELL");
        c.set("market.sim.announce.sound_story", "block.note_block.pling");
        c.set("market.sim.announce.sound_other", "Minecraft:Block.Note_Block.Chime");
        List<String> warns = new ArrayList<>();

        SimSettings.Announce a = MarketSimConfig.parse(c, warns::add).settings().announce();

        assertEquals(SimSettings.Announce.BELL, a.soundUp());
        assertEquals(SimSettings.Announce.PLING, a.soundStory());
        assertEquals(SimSettings.Announce.CHIME, a.soundOther(), "a namespaced key is kept, lower-cased");
        assertEquals(2, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("market.sim.announce.sound_up "), warns.toString());
        assertTrue(warns.get(1).startsWith("market.sim.announce.sound_story "), warns.toString());
        assertFalse(warns.stream().anyMatch(w -> w.contains("sound_other")), warns.toString());
    }
}
