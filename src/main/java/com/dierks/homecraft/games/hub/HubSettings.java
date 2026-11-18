package com.dierks.homecraft.games.hub;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * {@code games.hub} (0.44.0): the Hub, the server's big first-join spawn world, visited like the Clubhouse.
 *
 * @param enabled whether anyone can go (games on, and the world listed in {@code games.worlds})
 * @param world   the Hub's world: one of {@code games.worlds}, with its own Multiverse-Inventories group
 */
public record HubSettings(boolean enabled, String world) {

    /** The keys it ships, in config order. */
    public static final List<String> KEYS = List.of("enabled", "world");

    /** The shipped world: the first-join spawn. */
    public static final String WORLD = "fairyhub";

    public static HubSettings defaults() {
        return new HubSettings(true, WORLD);
    }

    /** Read {@code games.hub} over {@code d}; never throws. */
    public static HubSettings parse(GamesConfig.Node n, HubSettings d) {
        boolean enabled = n.enabled(d.enabled());
        String world = n.text("world", d.world());
        if (world == null || world.isBlank()) {
            n.warn(n.key("world") + " is empty - using " + d.world());
            world = d.world();
        }
        return new HubSettings(enabled, world.trim());
    }
}
