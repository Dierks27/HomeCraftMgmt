package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Slots;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The algo-3 golf plans frozen before Golf v4 (GOLF-V4-SPEC §7), for every package's tests: Golf of
 * the Week's EEEMMMMHH twice and Tiny Golf's EEE once, made by Adventure Golf's planner at the 0.35
 * boxes, each with the row it was flipped into (its gen tag and witness lines).
 *
 * <p>They live in {@code src/test/resources/gen/v3/}: {@code golf-index.txt} names each (slot, mix,
 * half, seed, day and the plan's hash), {@code <name>.plan.b64} is the archived plan
 * ({@link PlanCodec#encode}, base64) and {@code <name>.row.yml} the {@code game_courses} data (built at
 * 1,790,000,000,000 ms, cadence 7). Their hashes are the algo-3 goldens. They must pass the frozen
 * Adventure Golf rules and their witnesses must replay, forever: never capture them again.
 */
public final class V3GolfFixtures {

    /** Where they are on the test class path. */
    public static final String DIR = "/gen/v3/";

    /**
     * One frozen layout.
     *
     * @param name    its file name ({@code golf-9-a}, {@code golf-9-b}, {@code golf-3})
     * @param slot    the slot it was made for
     * @param mix     the mix it was made at
     * @param half    the half it was made in
     * @param seed    its seed
     * @param day     its edition's first day
     * @param hash    its plan's hash, as the index pins it
     * @param plan    the archived plan, read back
     * @param rowData the course row's data (YAML)
     * @param tag     the row's gen tag
     */
    public record Fixture(String name, Slots.Def slot, String mix, char half, long seed, long day, String hash,
                          Plan plan, String rowData, GenTag tag) {

        /** Its planned golf course. */
        public PlannedGolf golf() {
            return (PlannedGolf) plan.course();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Fixture> all;

    private V3GolfFixtures() {
    }

    /** Every fixture, in index order. */
    public static synchronized List<Fixture> all() {
        if (all == null) {
            all = List.copyOf(load());
        }
        return all;
    }

    /** The fixture called {@code name}. */
    public static Fixture named(String name) {
        return all().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no v3 golf fixture " + name));
    }

    private static List<Fixture> load() {
        List<Fixture> out = new ArrayList<>();
        for (String line : text("golf-index.txt").split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] p = l.split(" ");
            if (p.length != 7) {
                throw new IllegalStateException("a bad v3 golf index line: " + l);
            }
            Slots.Def slot = Slots.of(p[1]);
            if (slot == null) {
                throw new IllegalStateException("a v3 golf fixture of an unknown slot: " + l);
            }
            PlanCodec.Read read = PlanCodec.decode(Base64.getMimeDecoder().decode(text(p[0] + ".plan.b64")));
            if (!read.ok()) {
                throw new IllegalStateException("v3 golf fixture " + p[0] + " can't be read: " + read.problem());
            }
            String row = text(p[0] + ".row.yml");
            out.add(new Fixture(p[0], slot, p[2], p[3].charAt(0), Long.parseUnsignedLong(p[4], 16), Long.parseLong(p[5]),
                    p[6], read.plan(), row, com.dierks.homecraft.games.golf.CourseCodec.readGen(row)));
        }
        return out;
    }

    private static String text(String file) {
        try (InputStream in = V3GolfFixtures.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("v3 golf fixture file " + DIR + file + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
