package com.dierks.homecraft.config;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import com.dierks.homecraft.games.gen.LayoutScenarios;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Games settings read before the database opens, on the update's first start
 * ({@code PluginConfig.games}): config.yml still carries the layout check's mark, its 0.35 origins at
 * this version's gaps, so that reading is quiet. The check decides next and the file is read again,
 * aloud; only a WARN that is true reaches the owner, on the one start where layout messages matter.
 */
class LayoutMarkParseTest {

    @Test
    void theMarkedFileIsReadWithoutTheFalseWarnsItsOldSpotsAtNewGapsWouldGive() {
        YamlConfiguration marked = LayoutScenarios.marked035();
        assertTrue(LayoutGuard.pending(marked), "the fixture: revision 19 marked it");
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        PluginConfig.games(marked, warns::add, infos::add);
        assertEquals(List.of(), warns, "no WARN before the check has decided");

        // the same file read aloud is what the owner saw: a Classic "on top of" another, which isn't true
        YamlConfiguration unmarked = LayoutScenarios.marked035();
        unmarked.set(LayoutGuard.PENDING_KEY, null);
        List<String> loud = new ArrayList<>();
        PluginConfig.games(unmarked, loud::add, null);
        assertTrue(loud.stream().anyMatch(w -> w.contains("fresh_classic")), "0.35's Classics at 576-block gaps meet: "
                + loud);
    }

    @Test
    void a035FileTheUpdateCouldntSaveIsReadQuietlyToo() {
        YamlConfiguration v035 = LayoutFixtures.v035(); // revision 18, never marked: the migration's save failed
        assertFalse(LayoutGuard.pending(v035), "the fixture: no mark");
        assertTrue(LayoutGuard.unmigrated(v035), "the fixture: still below revision 19");
        List<String> warns = new ArrayList<>();
        PluginConfig.games(v035, warns::add, null);
        assertEquals(List.of(), warns, "the same false WARNs are kept back: the layout check decides (or keeps the"
                + " Games off, saying why) before anything is read aloud");
    }

    @Test
    void onceTheCheckHasWrittenTheDecisionTheFileIsReadAloud() {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set("games.snake.tick_java", "fast");
        List<String> warns = new ArrayList<>();
        PluginConfig.games(c, warns::add, null);
        assertFalse(warns.isEmpty(), "an unmarked file's WARNs are said: " + warns);
    }
}
