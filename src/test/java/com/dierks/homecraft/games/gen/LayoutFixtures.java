package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The config files and readings the Games layout tests share: 0.35.0's and 0.36.0's bundled config.yml
 * (verbatim copies, {@code src/test/resources/config-0.35.0.yml} and {@code config-0.36.0.yml}), this
 * version's, and every Games place's box as the plugin reads it from a file.
 */
public final class LayoutFixtures {

    private LayoutFixtures() {
    }

    /** 0.35.0's bundled config.yml, exactly as a 0.35 install has it on disk. */
    public static YamlConfiguration v035() {
        return load("/config-0.35.0.yml");
    }

    /**
     * 0.36.0's bundled config.yml, exactly as a 0.36 install has it on disk (a verbatim copy,
     * {@code src/test/resources/config-0.36.0.yml}, revision 19): the file the token balance's revision
     * 20 meets on the owner's server.
     */
    public static YamlConfiguration v036() {
        return load("/config-0.36.0.yml");
    }

    /** This version's bundled config.yml: a fresh install's file. */
    public static YamlConfiguration bundled() {
        return load("/config.yml");
    }

    private static YamlConfiguration load(String resource) {
        try (InputStream in = LayoutFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the test classpath");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    /** {@code c} written out and read back, as a save and the next start's load would. */
    public static YamlConfiguration reread(FileConfiguration c) {
        YamlConfiguration out = new YamlConfiguration();
        try {
            out.loadFromString(c.saveToString());
        } catch (Exception e) {
            throw new IllegalStateException("the file doesn't read back", e);
        }
        return out;
    }

    /** The Games settings the plugin reads from {@code c}, and every WARN it gives. */
    public static GamesConfig.Parsed parse(FileConfiguration c, List<String> warns) {
        return GamesConfig.parse(c, warns::add, null);
    }

    /**
     * Every place's boxes as the plugin reads them from {@code c}: each course's and Classic's two
     * halves (at its origin and gap), the keep area's first plots, the Clubhouse and the arena, by name.
     */
    public static Map<String, List<Box>> places(FileConfiguration c) {
        GamesConfig.Parsed p = parse(c, new ArrayList<>());
        DailySettings st = p.settings(DailyCourses.SPEC);
        Map<String, List<Box>> out = new LinkedHashMap<>();
        List<DailySettings.SlotConfig> all = new ArrayList<>(st.slots());
        all.addAll(st.archive().classics());
        for (DailySettings.SlotConfig s : all) {
            out.put(s.id(), Regions.halves(s));
        }
        List<Box> plots = new ArrayList<>();
        for (int n = 1; n <= st.archive().keep().maxPlots(); n++) {
            plots.add(st.archive().keep().plot(n));
        }
        out.put("keep", plots);
        ClubhouseSettings cs = p.settings(Clubhouse.SPEC);
        FallingFloorsSettings ff = p.settings(FallingFloors.SPEC);
        out.put("clubhouse", List.of(cs.box()));
        out.put("falling_floors", List.of(ff.box()));
        return out;
    }

    /** What 0.35.0 built each place as: the same map at its own spots and gaps. */
    public static Map<String, List<Box>> legacyPlaces() {
        Map<String, List<Box>> out = new LinkedHashMap<>();
        List<Slots.Def> all = new ArrayList<>(Slots.ALL);
        all.addAll(Slots.CLASSICS);
        for (Slots.Def d : all) {
            out.put(d.id(), List.of(com.dierks.homecraft.games.gen.api.LegacyBoxes.half(d, 'A'),
                    com.dierks.homecraft.games.gen.api.LegacyBoxes.half(d, 'B')));
        }
        List<Box> plots = new ArrayList<>();
        var keep = com.dierks.homecraft.games.gen.api.LegacyBoxes.keep();
        for (int n = 1; n <= keep.maxPlots(); n++) {
            plots.add(keep.plot(n));
        }
        out.put("keep", plots);
        out.put("clubhouse", List.of(com.dierks.homecraft.games.gen.api.LegacyBoxes.clubhouse()));
        out.put("falling_floors", List.of(com.dierks.homecraft.games.gen.api.LegacyBoxes.fallingFloors()));
        return out;
    }

    /** What this version ships each place as. */
    public static Map<String, List<Box>> shippedPlaces() {
        return places(bundled());
    }
}
