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
 * The algo-3 Mountain Runs frozen before Mountain Run v2 (MOUNTAIN-V2-SPEC §11.4, §15), for every
 * package's tests: three spirals, one per tier, the medium one the owner's own preview seed
 * ({@code c454d3d6504ca5bd}, the run he rode on 2 October 2026), each with the row it would be
 * flipped into (its gen tag).
 *
 * <p>They live in {@code src/test/resources/gen/v3/}, in {@link V2Fixtures}' format: {@code index.txt}
 * names each (slot, tier, half, seed, day and the plan's hash), {@code <name>.plan.b64} is the
 * archived plan ({@link PlanCodec#encode} v1, base64) and {@code <name>.row.yml} the
 * {@code game_courses} data the flip writes (built at 1,790,000,000,000 ms, cadence 7). They were
 * made by the algo-3 planner (0.36) in Ice Boat's 0.36 half A (128 x 16 x 128 at 6080, 160, 5888)
 * from those seeds, before the planner became algo 4. The day is a stand-in: the planner never read
 * it. They must pass the frozen {@code DownhillValidator}, through {@code BoatValidator}'s dispatch,
 * forever: never capture them again.
 */
public final class V3Fixtures {

    /** Where they are on the test class path. */
    public static final String DIR = "/gen/v3/";
    /** The owner's preview seed: the Mountain Run he rode (medium). */
    public static final long OWNER_SEED = 0xc454d3d6504ca5bdL;

    /**
     * One frozen layout.
     *
     * @param name    its file name ({@code boat-easy}, {@code boat-medium}, {@code boat-hard})
     * @param slot    the slot it was made for
     * @param tier    the tier it was made at
     * @param half    the half it was made in
     * @param seed    its seed
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

    private V3Fixtures() {
    }

    /** Every fixture, in index order: easy, medium, hard. */
    public static synchronized List<Fixture> all() {
        if (all == null) {
            all = List.copyOf(load());
        }
        return all;
    }

    /** The fixture of {@code tier} ({@code easy}, {@code medium}, {@code hard}). */
    public static Fixture boat(String tier) {
        return all().stream().filter(f -> f.name().equals("boat-" + tier)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no v3 fixture of tier " + tier));
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
                throw new IllegalStateException("a bad v3 index line: " + l);
            }
            Slots.Def slot = Slots.of(p[1]);
            if (slot == null) {
                throw new IllegalStateException("a v3 fixture of an unknown slot: " + l);
            }
            byte[] stored = Base64.getMimeDecoder().decode(text(p[0] + ".plan.b64"));
            PlanCodec.Read read = PlanCodec.decode(stored);
            if (!read.ok()) {
                throw new IllegalStateException("v3 fixture " + p[0] + " can't be read: " + read.problem());
            }
            String row = text(p[0] + ".row.yml");
            GenTag tag = com.dierks.homecraft.games.trial.CourseCodec.decode(slot.id(), row).course().gen();
            out.add(new Fixture(p[0], slot, p[2], p[3].charAt(0), Long.parseUnsignedLong(p[4], 16),
                    Long.parseLong(p[5]), p[6], read.plan(), row, tag));
        }
        return out;
    }

    private static String text(String file) {
        try (InputStream in = V3Fixtures.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("v3 fixture file " + DIR + file + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
