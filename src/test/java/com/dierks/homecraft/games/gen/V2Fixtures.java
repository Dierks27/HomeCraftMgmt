package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The algo-2 plans frozen before Course Variety (§1.4), for every package's tests: three Ice Boat
 * loops (one per tier) and three golf courses (Golf of the Week's EEEMMMMHH twice, Tiny Golf's EEE
 * once), each with the row it was flipped into (its gen tag, and golf's witness lines).
 *
 * <p>They live in {@code src/test/resources/gen/v2/}: {@code index.txt} names each (slot, tier or
 * mix, half, seed, day and the plan's hash), {@code <name>.plan.b64} is the archived plan
 * ({@link PlanCodec#encode}, base64) and {@code <name>.row.yml} the {@code game_courses} data the
 * flip wrote (built at 1,790,000,000,000 ms, cadence 7). They were made by the algo-2 planners from
 * those seeds, and their hashes are the algo-2 goldens. They must pass the frozen v2 validators,
 * and the golf witnesses must replay, forever: never capture them again.
 */
public final class V2Fixtures {

    /** Where they are on the test class path. */
    public static final String DIR = "/gen/v2/";

    /**
     * One frozen layout.
     *
     * @param name      its file name ({@code boat-easy}, {@code golf-9-a}...)
     * @param slot      the slot it was made for
     * @param tierOrMix the tier ({@code easy}) or mix ({@code EEEMMMMHH}) it was made at
     * @param half      the half it was made in
     * @param seed      its seed
     * @param day       its edition's first day
     * @param hash      its plan's hash, as the index pins it
     * @param plan      the archived plan, read back
     * @param rowData   the course row's data (YAML) as the flip wrote it
     * @param tag       the row's gen tag
     */
    public record Fixture(String name, Slots.Def slot, String tierOrMix, char half, long seed, long day, String hash,
                          Plan plan, String rowData, GenTag tag) {

        /** Whether it is a golf course. */
        public boolean golf() {
            return slot.golf();
        }

        /** Its planned trial (a boat); {@code null} for golf. */
        public PlannedTrial trial() {
            return plan.course() instanceof PlannedTrial t ? t : null;
        }

        /** Its planned golf course; {@code null} for a boat. */
        public PlannedGolf golfCourse() {
            return plan.course() instanceof PlannedGolf g ? g : null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Fixture> all;

    private V2Fixtures() {
    }

    /** Every fixture, in index order. */
    public static synchronized List<Fixture> all() {
        if (all == null) {
            all = List.copyOf(load());
        }
        return all;
    }

    /** The three Ice Boat loops: easy, medium, hard. */
    public static List<Fixture> boats() {
        return all().stream().filter(f -> !f.golf()).toList();
    }

    /** The three golf courses: EEEMMMMHH twice, then EEE. */
    public static List<Fixture> golf() {
        return all().stream().filter(Fixture::golf).toList();
    }

    /** The fixture called {@code name}. */
    public static Fixture named(String name) {
        return all().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no v2 fixture " + name));
    }

    /** The boat loop of {@code tier} ({@code easy}, {@code medium}, {@code hard}). */
    public static Fixture boat(String tier) {
        return named("boat-" + tier);
    }

    private static List<Fixture> load() {
        List<Fixture> out = new ArrayList<>();
        for (String line : text("index.txt").split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] p = l.split(" ");
            if (p.length != 7) {
                throw new IllegalStateException("a bad v2 index line: " + l);
            }
            Slots.Def slot = Slots.of(p[1]);
            if (slot == null) {
                throw new IllegalStateException("a v2 fixture of an unknown slot: " + l);
            }
            byte[] stored = Base64.getMimeDecoder().decode(text(p[0] + ".plan.b64"));
            PlanCodec.Read read = PlanCodec.decode(stored);
            if (!read.ok()) {
                throw new IllegalStateException("v2 fixture " + p[0] + " can't be read: " + read.problem());
            }
            String row = text(p[0] + ".row.yml");
            GenTag tag = slot.golf() ? com.dierks.homecraft.games.golf.CourseCodec.readGen(row)
                    : com.dierks.homecraft.games.trial.CourseCodec.decode(slot.id(), row).course().gen();
            out.add(new Fixture(p[0], slot, p[2], p[3].charAt(0), Long.parseUnsignedLong(p[4], 16),
                    Long.parseLong(p[5]), p[6], read.plan(), row, tag));
        }
        return out;
    }

    private static String text(String file) {
        try (InputStream in = V2Fixtures.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("v2 fixture file " + DIR + file + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
