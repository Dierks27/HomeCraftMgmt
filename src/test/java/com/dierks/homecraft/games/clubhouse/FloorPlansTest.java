package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wing's floor plans ({@link FloorPlans}) draw for the grown wing; with {@code HCM_FLOORPLAN_DIR} set (and
 * {@code HCM_FLOORPLAN_PREFIX}, "rooms" if not) they are written there as PNGs for the owner to look at. Nothing is
 * written in a normal test run.
 */
class FloorPlansTest {

    @Test
    void theWingsFloorsDrawAndAreWrittenWhenAsked() throws Exception {
        Box box = ClubhouseSettings.defaults().box();
        ClubhouseSite site = ClubhousePlanner.plan(box);
        List<FloorPlans.Floor> floors = FloorPlans.floors(box);
        assertEquals(2, floors.size(), "downstairs and upstairs");
        for (FloorPlans.Floor f : floors) {
            BufferedImage img = FloorPlans.draw(site, f);
            assertTrue(img.getWidth() > 32 * FloorPlans.CELL && img.getHeight() > 30 * FloorPlans.CELL);
            assertFalse(f.markers().isEmpty(), "places to mark: " + f.name());
        }
        String dir = System.getenv("HCM_FLOORPLAN_DIR");
        if (dir != null && !dir.isBlank()) {
            String prefix = System.getenv().getOrDefault("HCM_FLOORPLAN_PREFIX", "rooms");
            for (File out : FloorPlans.write(site, new File(dir), prefix)) {
                assertTrue(out.isFile() && out.length() > 1000, "written: " + out);
            }
        }
    }
}
