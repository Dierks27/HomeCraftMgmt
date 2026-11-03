package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.hunt.HuntMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The How It Works Mini Radar tile says what the radar does now, read from {@code arcade.radar}, so the guide never
 * disagrees with the radar: Cold, Warm or Hot, how often, for how long, and once per Mini.
 */
class GuideRadarTest {

    @Test
    void theShippedRadarIsAMinuteOfReadingsEverySixSecondsOncePerMini() {
        assertArrayEquals(new String[] {"&7Cold, Warm or Hot", "&7every 6 seconds", "&7for a minute.",
                "&7One per Mini."}, GuideMenu.radarLore(PluginConfig.Radar.DEFAULTS));
    }

    @Test
    void theTileFollowsTheSettings() {
        assertArrayEquals(new String[] {"&7Cold, Warm, Hot or Burning!", "&7every 2 seconds",
                "&7until it's caught or gets away."},
                GuideMenu.radarLore(new PluginConfig.Radar(2, 0, new HuntMath.Bands(12, 32, 64), false)));
        assertArrayEquals(new String[] {"&7Cold, Warm or Hot", "&7every 10 seconds", "&7for 2 minutes.",
                "&7One per Mini."}, GuideMenu.radarLore(new PluginConfig.Radar(10, 120, HuntMath.Bands.SHIPPED, true)));
        assertArrayEquals(new String[] {"&7Cold, Warm or Hot", "&7every 5 seconds", "&7for 45 seconds.",
                "&7One per Mini."}, GuideMenu.radarLore(new PluginConfig.Radar(5, 45, HuntMath.Bands.SHIPPED, true)));
    }
}
