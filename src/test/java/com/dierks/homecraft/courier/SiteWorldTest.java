package com.dierks.homecraft.courier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a courier site's record may go because its world is gone ({@code BuildingService.worldGone}).
 *
 * <p>plugin.yml loads this plugin before Multiverse-Core (so the Games' void world gets its generator at
 * every restart), and Multiverse loads its worlds while it enables: the courier's sweep at enable runs
 * while none of them is loaded yet. A site in one of them must wait for its chunks, not be forgotten,
 * or the building it put up there would stand for good.
 */
class SiteWorldTest {

    @Test
    void aWorldThatIsntLoadedYetButIsOnDiskKeepsItsSites(@TempDir File container) {
        assertTrue(new File(container, "survival2").mkdir(), "the fixture: a Multiverse world's folder");
        assertFalse(BuildingService.worldGone(false, container, "survival2"), "not loaded at the enable sweep, but on"
                + " disk: Multiverse loads it in a moment, so the site waits for its chunks");
    }

    @Test
    void aWorldWithNoFolderIsGoneAndALoadedOneNeverIs(@TempDir File container) {
        assertTrue(BuildingService.worldGone(false, container, "deleted"), "no folder: nothing left to put back");
        assertFalse(BuildingService.worldGone(true, container, "deleted"), "a loaded world is never gone");
        assertTrue(BuildingService.worldGone(false, container, ""), "a site with no world name is gone");
        assertTrue(BuildingService.worldGone(false, null, "survival2"), "and, as before, one whose world folder can't be"
                + " looked for");
    }
}
