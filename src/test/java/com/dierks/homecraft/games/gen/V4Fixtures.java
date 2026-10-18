package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
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
 * The algo-4 Mountain Runs (Mountain Run v2) frozen before the wide road (MOUNTAIN-V21-SPEC §5.1, D13),
 * for every package's tests: two Winding Roads and a Slalom, the medium road the owner's own preview seed
 * ({@code 4a68f2c5671adda9}, the run they rode in the 0.37.0 live test, 17-18 October 2026), each with the
 * row it would be flipped into (its gen tag).
 *
 * <p>They live in {@code src/test/resources/gen/v4/}, in {@link V3Fixtures}' format: {@code index.txt}
 * names each (slot, tier, half, seed, day and the plan's hash), {@code <name>.plan.b64} is the archived
 * plan ({@link PlanCodec#encode}, base64) and {@code <name>.row.yml} the {@code game_courses} data the
 * flip writes (built at 1,790,000,000,000 ms, cadence 7). They were made by the algo-4 planner (0.37.0)
 * in Ice Boat's half A (480 x 176 x 640 at 6080, 96, 2880) from those seeds, before the planner became
 * algo 5. The day is a stand-in: the planner never read it. They must pass the frozen v4 rules of
 * {@code MountainValidator}, through {@code BoatValidator}'s dispatch, forever: never capture them again.
 */
public final class V4Fixtures {

    /** Where they are on the test class path. */
    public static final String DIR = "/gen/v4/";
    /** The owner's preview seed: the Winding Road they rode (medium). */
    public static final long OWNER_SEED = 0x4a68f2c5671adda9L;

    /**
     * One frozen layout.
     *
     * @param name    its file name ({@code boat-road-medium}, {@code boat-road-easy}, {@code boat-slalom-hard})
     * @param slot    the slot it was made for
     * @param tier    the tier it was made at
     * @param half    the half it was made in
     * @param seed    its seed (its style is the seed's, {@code BoatStyle.of})
     * @param day     its edition's first day (a stand-in)
     * @param hash    its plan's hash, as the index pins it
     * @param plan    the archived plan, read back
     * @param rowData the course row's data (YAML) as the flip writes it
     * @param tag     the row's gen tag
     */
    public record Fixture(String name, Slots.Def slot, String tier, char half, long seed, long day, String hash,
                          Plan plan, String rowData, GenTag tag) {

        /** Its planned trial. */
        public PlannedTrial trial() {
            return (PlannedTrial) plan.course();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Fixture> all;

    private V4Fixtures() {
    }

    /** Every fixture, in index order: the medium road, the easy road, the hard slalom. */
    public static synchronized List<Fixture> all() {
        if (all == null) {
            all = List.copyOf(load());
        }
        return all;
    }

    /** The fixture of {@code style} ({@code road}, {@code slalom}) and {@code tier}. */
    public static Fixture boat(String style, String tier) {
        return all().stream().filter(f -> f.name().equals("boat-" + style + "-" + tier)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no v4 fixture of " + style + " " + tier));
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
                throw new IllegalStateException("a bad v4 index line: " + l);
            }
            Slots.Def slot = Slots.of(p[1]);
            if (slot == null) {
                throw new IllegalStateException("a v4 fixture of an unknown slot: " + l);
            }
            byte[] stored = Base64.getMimeDecoder().decode(text(p[0] + ".plan.b64"));
            PlanCodec.Read read = PlanCodec.decode(stored);
            if (!read.ok()) {
                throw new IllegalStateException("v4 fixture " + p[0] + " can't be read: " + read.problem());
            }
            String row = text(p[0] + ".row.yml");
            GenTag tag = com.dierks.homecraft.games.trial.CourseCodec.decode(slot.id(), row).course().gen();
            out.add(new Fixture(p[0], slot, p[2], p[3].charAt(0), Long.parseUnsignedLong(p[4], 16),
                    Long.parseLong(p[5]), p[6], read.plan(), row, tag));
        }
        return out;
    }

    private static String text(String file) {
        try (InputStream in = V4Fixtures.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("v4 fixture file " + DIR + file + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
