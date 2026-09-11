package com.dierks.homecraft.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two nav anchors a hand learns, pinned so they cannot drift apart again.
 *
 * <p>In a 54-slot menu, slots 45 and 53 are the page arrows and slot 49 is the way out. They had
 * drifted: the Mini list put Previous on 45 and Back on 49, and the editor you reach by clicking a
 * Mini put Back on 45, Save on 49 and <strong>Delete on 53</strong> — so a hand that had learned
 * "53 to see more Minis" deleted one instead. The Mailbox, separately, had three identical arrows
 * in one row, and the middle one threw away your place in the list.
 *
 * <p>The rule enforced here is deliberately the narrow one, because it is the one that bites:
 * <ul>
 *   <li>45 and 53 carry a page arrow and nothing else. Not an exit, not an action, not Delete.</li>
 *   <li>49 is never a page arrow, so an exit can never be confused with a page step.</li>
 * </ul>
 *
 * <p>It does not assert that 49 always holds an exit. The Store is a root screen with no exit tile
 * at all — Esc closes it, and the slot carries a destination instead. That is a deliberate
 * exception, not drift, and a test that forbade it would be a test people learn to work around.
 *
 * <p>Registry-free: this reads source text, so it needs no server and no Bukkit call.
 */
class NavAnchorTest {

    private static final Path GUI = Path.of("src", "main", "java", "com", "dierks", "homecraft", "gui");

    /** A {@code set(<slot>,} call. */
    private static final Pattern SET = Pattern.compile("\\bset\\(\\s*(\\d+)\\s*,");
    private static final Pattern MATERIAL = Pattern.compile("Material\\.([A-Z0-9_]+)");

    @Test
    void pageArrowsOwnSlots45And53AndNothingElseDoes() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : guiSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = SET.matcher(lines.get(i));
                while (m.find()) {
                    int slot = Integer.parseInt(m.group(1));
                    if (slot != 45 && slot != 53) {
                        continue;
                    }
                    Set<String> materials = materialsIn(lines, i);
                    if (!materials.isEmpty() && !materials.equals(Set.of("ARROW"))) {
                        offences.add(String.format("  %s:%d slot %d carries %s",
                                file.getFileName(), i + 1, slot, materials));
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Slots 45 and 53 are the page arrows. Anything else there is a tile a player will "
                        + "press while meaning to page:\n" + String.join("\n", offences));
    }

    @Test
    void theExitIsNeverAPageArrow() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : guiSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = SET.matcher(lines.get(i));
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) != 49) {
                        continue;
                    }
                    if (materialsIn(lines, i).contains("ARROW")) {
                        offences.add("  " + file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Slot 49 is where the way out lives; an arrow there is indistinguishable from the "
                        + "page arrows either side of it:\n" + String.join("\n", offences));
    }

    /**
     * Materials named inside one {@code set(...)} call.
     *
     * <p>Bounded by the next {@code set(} rather than a fixed line count: a three-line window
     * spills into the following statement, which made an arrow on 53 look like an arrow on the
     * 49 above it. An empty result means the tile came from a helper with no literal to judge,
     * and is passed over rather than guessed at.
     */
    private Set<String> materialsIn(List<String> lines, int start) {
        Set<String> found = new TreeSet<>();
        for (int i = start; i < Math.min(start + 3, lines.size()); i++) {
            if (i > start && SET.matcher(lines.get(i)).find()) {
                break;
            }
            Matcher m = MATERIAL.matcher(lines.get(i));
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    private List<Path> guiSources() throws IOException {
        assertTrue(Files.isDirectory(GUI),
                "expected to scan " + GUI.toAbsolutePath() + " — if the working directory moved, "
                        + "this test is silently checking nothing");
        try (Stream<Path> files = Files.walk(GUI)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
