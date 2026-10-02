package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.AdventureKit;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.storage.GenArchiveDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's own server on 2 Oct 2026, as 0.36 left it, and its update to v4 (S/v4/CONTEXT.md "The
 * owner's server state"): one scenario for the engine test and its mutation proof.
 *
 * <p><b>0.36.</b> Fresh Courses is on in a void world called {@code games}: Golf of the Week is live at
 * 7488,160,4096 (an algo-3 Adventure course with ponds, 64 x 16 x 128 halves), Easy, Parkour, Hard,
 * Sky Rings, Tiny Golf and both Droppers are live at their spots, Classic Golf is empty but claimed at
 * 7488,160,4800, the Clubhouse stands at 6080,160,8544, and Ice Boat is switched off with an algo-3
 * preview standing in its half A at 6080,160,5888 (its claim says 128 x 16 x 128).
 *
 * <p><b>The update.</b> The code's shipped areas are v4's (Slots): golf 128 x 16 x 224 at x 8768 and the
 * boat 480 x 176 x 640 at z 2880; config revision 21 has rewritten the untouched origins, which here means
 * config is the shipped defaults. The server restarts with golf's planner at version 4.
 *
 * <p>{@link #failures} is the oracle: everything the update must do, said as what went wrong.
 */
final class OwnerServer {

    static final String W = GenKit.WORLD;
    static final String GOLF = Slots.DAILY_GOLF.id();
    static final String BOAT = Slots.ICE_BOAT.id();
    static final String CLASSIC = Slots.CLASSIC_GOLF.id();
    static final String TINY = Slots.TINY_GOLF.id();

    /** 0.36's claims, as 0.36 wrote them (half size and the 576 gap recorded). */
    static final String GOLF_036 = "games,7488,160,4096,64,16,128,576";
    static final String CLASSIC_036 = "games,7488,160,4800,64,16,128,576";
    static final String BOAT_036 = "games,6080,160,5888,128,16,128,576";

    /** 0.36's halves, written out (never from today's Slots, whose sizes are v4's). */
    static final Box GOLF_A = Box.sized(7488, 160, 4096, 64, 16, 128);
    static final Box GOLF_B = Box.sized(8128, 160, 4096, 64, 16, 128);
    static final Box CLASSIC_A = Box.sized(7488, 160, 4800, 64, 16, 128);
    static final Box CLASSIC_B = Box.sized(8128, 160, 4800, 64, 16, 128);
    static final Box BOAT_A = Box.sized(6080, 160, 5888, 128, 16, 128);
    static final Box BOAT_B = Box.sized(6784, 160, 5888, 128, 16, 128);
    static final Box CLUBHOUSE = Box.sized(6080, 160, 8544, 32, 16, 32);

    /** v4's halves of the moved areas (the merged layout table). */
    static final Box GOLF_V4_A = Box.sized(8768, 160, 4096, 128, 16, 224);
    static final Box GOLF_V4_B = Box.sized(9472, 160, 4096, 128, 16, 224);
    static final Box BOAT_V4_A = Box.sized(6080, 96, 2880, 480, 176, 640);
    static final Box BOAT_V4_B = Box.sized(7136, 96, 2880, 480, 176, 640);

    /** The courses live on the owner's server besides golf. */
    static final List<String> OTHERS = List.of("fresh_parkour_easy", "fresh_parkour", "fresh_parkour_hard", "fresh_rings",
            TINY, "fresh_dropper_easy", "fresh_dropper");

    /** The boxes the update may change: the moved areas, old and new. */
    static final List<Box> MOVED = List.of(GOLF_A, GOLF_B, CLASSIC_A, CLASSIC_B, BOAT_A, BOAT_B, GOLF_V4_A, GOLF_V4_B,
            BOAT_V4_A, BOAT_V4_B);

    /** A world that remembers every write, in order, with what it replaced. */
    static final class LoggingWorld extends GenKit.FakeWorld {

        record Write(int x, int y, int z, String was, String now) {
        }

        final List<Write> log = new ArrayList<>();

        LoggingWorld(String name) {
            super(name);
        }

        @Override
        public void set(int x, int y, int z, String state) {
            String was = blocks.get(GenKit.pos(x, y, z));
            super.set(x, y, z, state);
            log.add(new Write(x, y, z, was, state));
        }

        long count(Box b, boolean water) {
            long n = 0;
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                long p = e.getKey();
                int x = (int) (p >> 38);
                int z = (int) ((p << 26) >> 38);
                int y = (int) ((p << 52) >> 52);
                if (b.contains(x, y, z) && e.getValue().startsWith("minecraft:water") == water) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * Two Adventure holes in the first two v3 plots of whatever half it is given, each with a sealed pond
     * beside it (water at T - 1 over blue concrete, rimmed with moss): the same layout for any seed, so a
     * heal makes it again.
     */
    static final class PondGolf implements Planner {
        final int algo;

        PondGolf(int algo) {
            this.algo = algo;
        }

        @Override
        public String id() {
            return Slots.GOLF;
        }

        @Override
        public int algo() {
            return algo;
        }

        @Override
        public Plan plan(PlanInput in) {
            return plan(in.slot(), in.half(), in.seed(), algo);
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return plan(in);
        }

        static Plan plan(Slots.Def slot, Box half, long seed, int algo) {
            int turf = half.minY() + GolfPlanner.TURF_ABOVE_FLOOR;
            List<AdventureKit.Drawn> holes = new ArrayList<>();
            List<Map.Entry<int[], String>> ponds = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                AdventureKit.Drawn d = AdventureKit.draw(half.minX() + 22 * i, half.minZ(), turf, STRAIGHT);
                holes.add(d);
                ponds.addAll(pond(d, turf));
            }
            return AdventureKit.plan(slot, half, seed, algo, holes, List.of(holes.get(0).line(), holes.get(1).line()),
                    ponds);
        }

        private static final String[] STRAIGHT = {
                "#######",
                "#00000#",
                "#00t00#",
                "#00000#",
                "#00000#",
                "#00000#",
                "#00000#",
                "#00c00#",
                "#00000#",
                "#######"};

        private static List<Map.Entry<int[], String>> pond(AdventureKit.Drawn d, int turf) {
            List<Map.Entry<int[], String>> out = new ArrayList<>();
            int x0 = d.bounds().maxX() + 3;
            int z0 = d.bounds().minZ() + 2;
            for (int x = x0 - 1; x <= x0 + 3; x++) {
                for (int z = z0 - 1; z <= z0 + 4; z++) {
                    boolean water = x >= x0 && x <= x0 + 2 && z >= z0 && z <= z0 + 3;
                    if (water) {
                        out.add(Map.entry(new int[]{x, turf - 2, z}, "minecraft:blue_concrete"));
                        out.add(Map.entry(new int[]{x, turf - 1, z}, "minecraft:water[level=0]"));
                    } else {
                        out.add(Map.entry(new int[]{x, turf - 1, z}, Palette.MOSS));
                    }
                }
            }
            return out;
        }
    }

    final Host host;
    final LoggingWorld world;
    GenService gen;
    /** The live tags of the other courses at 0.36, by slot. */
    final Map<String, GenTag> before = new LinkedHashMap<>();
    /** Golf of the Week's 0.36 tag. */
    GenTag oldGolf;
    /** Every block outside {@link #MOVED} at 0.36, with its sign text. */
    Map<Long, String> outside;
    /** Where the log stood at the update. */
    int upgradeAt;
    /** Golf's old area, guarded at its recorded sizes once the engine has read its record. */
    boolean golfGuarded;
    boolean boatGuarded;

    OwnerServer() {
        host = new Host(GenKit.at(2026, 10, 2, 19, 0), OTHERS.toArray(String[]::new));
        world = new LoggingWorld(W);
        host.worlds.put(W, world);
        host.settings = GenKit.weekly(OTHERS.toArray(String[]::new));
        host.extras = List.of(new Regions.Extra("clubhouse", CLUBHOUSE));
    }

    static Map<String, Planner> planners(int golfAlgo) {
        Map<String, Planner> out = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.BOAT)) {
            out.put(id, new FakePlanner(id));
        }
        out.put(Slots.DROPPER, new FlatPlanner(Slots.DROPPER));
        out.put(Slots.GOLF, new PondGolf(golfAlgo));
        return out;
    }

    /**
     * {@link GenKit#plan}'s three pads in a row along x, so they fit a Dropper's 64 x 64 x 16 half too (the
     * engine doesn't care what a course is; only that it stands where it was planned).
     */
    static final class FlatPlanner implements Planner {
        final String id;

        FlatPlanner(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int algo() {
            return 1;
        }

        @Override
        public Plan plan(PlanInput in) {
            Plan p = GenKit.plan(in.slot(), Box.sized(in.half().minX(), in.half().minY(), in.half().minZ(), 64, 48, 64),
                    in.seed(), 1);
            // fold GenKit's pads into the first 16 rows: z offset 3 for every pad
            List<BlockOp> ops = new ArrayList<>();
            for (BlockOp op : p.ops()) {
                ops.add(new BlockOp(op.x(), op.y(), in.half().minZ() + 3 + Math.floorMod(op.z() - in.half().minZ(), 3),
                        op.state()));
            }
            List<SignText> signs = new ArrayList<>();
            for (SignText t : p.signs()) {
                signs.add(new SignText(t.x(), t.y(), in.half().minZ() + 4, t.blockData(), t.lines()));
            }
            com.dierks.homecraft.games.trial.Course c = ((com.dierks.homecraft.games.gen.api.PlannedTrial) p.course())
                    .course();
            int z = in.half().minZ() + 4;
            com.dierks.homecraft.games.trial.Course flat = new com.dierks.homecraft.games.trial.Course(c.id(), c.kind(),
                    c.name(), c.tier(), c.world(), new com.dierks.homecraft.games.trial.Course.Spot(c.start().x(),
                    c.start().y(), z + 0.5, 0f, 0f), List.of(new com.dierks.homecraft.games.trial.Course.Mark(
                    c.checkpoints().get(0).x(), c.checkpoints().get(0).y(), z + 0.5, 2.2)),
                    new com.dierks.homecraft.games.trial.Course.Mark(c.finish().x(), c.finish().y(), z + 0.5, 3.0),
                    c.fallY(), null, false, false, 1);
            return Plan.of(in.slot().id(), 1, in.seed(), in.half(), p.palette(), ops, signs, List.of(),
                    new com.dierks.homecraft.games.gen.api.PlannedTrial(flat, 30_000), List.of("3 pads in a row"), 42);
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return plan(in);
        }
    }

    void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** One tick and, every 20th, a check. */
    void tick(int n) {
        gen.tick();
        host.now += 50;
        if (n % 20 == 19) {
            gen.check();
        }
    }

    /** 0.36 as it stands tonight: the other courses built by the engine, golf, the boat's preview and the claims as 0.36 left them. */
    OwnerServer at036() throws SQLException {
        // the Clubhouse's room (its own builder's: Fresh Courses must never touch it)
        for (int x = CLUBHOUSE.minX(); x <= CLUBHOUSE.maxX(); x++) {
            for (int z = CLUBHOUSE.minZ(); z <= CLUBHOUSE.maxZ(); z++) {
                world.put(x, 160, z, "minecraft:oak_planks");
            }
        }
        gen = new GenService(host, planners(3));
        gen.start();
        gen.worldsReady();
        for (int s = 0; s < 20 * 60 && !allLive(); s++) {
            drive(1);
        }
        if (!allLive()) {
            throw new IllegalStateException("0.36's courses never all went up: " + gen.summary());
        }
        drive(120); // their idle halves checked, nothing more to do
        for (String id : OTHERS) {
            before.put(id, gen.liveTag(id));
        }
        gen.stop();

        // Golf of the Week, live in 0.36's half A, with its ponds; its row, archive and claim as 0.36 flipped them
        Plan plan = PondGolf.plan(Slots.DAILY_GOLF, GOLF_A, 0x5eedL, 3);
        place(plan);
        GenScheduler.Target t = GenScheduler.target(null, host.now, host.settings.edition(GenKit.ZONE,
                java.time.DayOfWeek.MONDAY), 0);
        String mix = Slots.DAILY_GOLF.tierOrMix();
        oldGolf = GenService.tagFor(Slots.DAILY_GOLF, Slots.GOLF, plan, t.start(), 0, 0x5eedL, 'A', mix,
                host.settings.stars(), host.now, t.cadence());
        GenArchiveDao.Row archived = new GenArchiveDao.Row(GOLF, oldGolf.editionKey(), null, 0, oldGolf.day(),
                oldGolf.seed(), "golf/3", "golf", mix, "Golf of the Week", host.now, null, PlanCodec.encode(plan), 0, 0,
                host.now, null);
        host.store.flip(GenService.row(Slots.DAILY_GOLF, W, plan.course(), oldGolf, null, host.now),
                Map.of(GenAdminKeys.mix(GOLF), plan.hash() + ":" + mix), archived, host.now);
        host.store.meta(GenAdminKeys.claim(GOLF), GOLF_036);
        host.store.meta(GenAdminKeys.claim(CLASSIC), CLASSIC_036);
        host.store.meta(GenAdminKeys.claim(BOAT), BOAT_036);

        // Ice Boat's algo-3 preview in its half A (in memory only in 0.36: no row, no archive)
        for (int x = BOAT_A.minX() + 4; x <= BOAT_A.maxX() - 4; x++) {
            for (int z : new int[]{BOAT_A.minZ() + 4, BOAT_A.maxZ() - 4}) {
                for (int w = -3; w <= 3; w++) {
                    world.put(x, 165, z + w, "minecraft:packed_ice");
                }
                world.put(x, 166, z - 4, Palette.TRACK_WALL);
                world.put(x, 166, z + 4, Palette.TRACK_WALL);
            }
        }
        world.put(BOAT_A.minX() + 6, 166, BOAT_A.minZ() + 4, Palette.sign(0));
        world.sign(BOAT_A.minX() + 6, 166, BOAT_A.minZ() + 4, List.of("", "Mountain Run", "", ""));

        outside = new HashMap<>();
        for (Map.Entry<Long, String> e : world.blocks.entrySet()) {
            if (!moved(e.getKey())) {
                outside.put(e.getKey(), e.getValue() + world.signs.getOrDefault(e.getKey(), List.of()));
            }
        }
        return this;
    }

    private void place(Plan plan) {
        for (BlockOp op : plan.ops()) {
            world.put(op.x(), op.y(), op.z(), plan.palette().get(op.state()));
        }
        for (SignText s : plan.signs()) {
            world.put(s.x(), s.y(), s.z(), s.blockData());
            world.sign(s.x(), s.y(), s.z(), s.lines());
        }
    }

    private boolean allLive() {
        for (String id : OTHERS) {
            GenTag tag = gen.liveTag(id);
            if (tag == null || !gen.live(id, tag)) {
                return false;
            }
        }
        return true;
    }

    private static boolean moved(long p) {
        int x = (int) (p >> 38);
        int z = (int) ((p << 26) >> 38);
        int y = (int) ((p << 52) >> 52);
        for (Box b : MOVED) {
            if (b.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The update: Golf of the Week switched on at its shipped (v4) spot as config revision 21 left it, golf's
     * planner at version 4, a restart; then up to {@code minutes} of the engine running.
     *
     * @param geometry where the engine finds an old claim's halves ({@link GenService#RECORDED}, or a mutant)
     */
    OwnerServer upgrade(GenService.OldHalves geometry, int minutes) {
        startUpgrade(geometry);
        for (int s = 0; s < minutes * 60 && !(golfUp() && gen.oldAreas().isEmpty()); s++) {
            drive(1);
        }
        drive(60); // and whatever else is due (the other courses' idle halves)
        return this;
    }

    /** The restart into v4, up to the worlds being ready: nothing has run yet. */
    OwnerServer startUpgrade(GenService.OldHalves geometry) {
        List<String> on = new ArrayList<>(OTHERS);
        on.add(GOLF);
        host.settings = GenKit.weekly(on.toArray(String[]::new));
        upgradeAt = world.log.size();
        gen = new GenService(host, planners(4), geometry);
        gen.start();
        golfGuarded = guarded(GOLF_A) && guarded(GOLF_B);
        boatGuarded = guarded(BOAT_A) && guarded(BOAT_B);
        gen.worldsReady();
        return this;
    }

    boolean golfUp() {
        GenTag t = gen.liveTag(GOLF);
        return t != null && t.algo() == 4 && gen.live(GOLF, t);
    }

    boolean guarded(Box b) {
        return gen.inArea(W, b.minX(), b.minY(), b.minZ()) && gen.inArea(W, b.maxX(), b.maxY(), b.maxZ())
                && gen.inArea(W, (b.minX() + b.maxX()) / 2, b.minY(), (b.minZ() + b.maxZ()) / 2);
    }

    List<LoggingWorld.Write> writesSinceUpgrade() {
        return world.log.subList(upgradeAt, world.log.size());
    }

    /** Everything the update must have done, as what went wrong; empty when all is right. */
    List<String> failures() throws SQLException {
        List<String> out = new ArrayList<>();
        // the old areas were guarded at their recorded sizes before anything was emptied
        if (!golfGuarded) {
            out.add("golf's old halves weren't guarded at the sizes its claim recorded");
        }
        if (!boatGuarded) {
            out.add("the boat's old halves weren't guarded at the sizes its claim recorded");
        }
        // Golf of the Week: at its new spot, on a fresh board, the same week
        GenTag golf = gen.liveTag(GOLF);
        if (golf == null || !gen.live(GOLF, golf) || golf.algo() != 4) {
            out.add("Golf of the Week isn't open with a v4 course: " + golf + " / " + gen.summary());
        } else {
            Box at = gen.half(golf);
            if (!at.equals(GOLF_V4_A) && !at.equals(GOLF_V4_B)) {
                out.add("Golf of the Week stands at " + at.describe() + ", not its new spot");
            }
            if (world.count(at) == 0) {
                out.add("Golf of the Week's new half is empty");
            }
            if (!golf.edition().equals(oldGolf.edition()) || golf.reroll() != oldGolf.reroll() + 1) {
                out.add("golf's new course isn't this week's next reroll: " + golf.editionKey() + " after "
                        + oldGolf.editionKey());
            }
            if (GenBoards.day(golf).equals(GenBoards.day(oldGolf))) {
                out.add("golf's new course is on the old board");
            }
        }
        // the old golf halves: emptied, ponds first
        if (world.count(GOLF_A) + world.count(GOLF_B) != 0) {
            out.add("golf's old halves still hold " + (world.count(GOLF_A) + world.count(GOLF_B)) + " blocks");
        }
        for (Box half : List.of(GOLF_A, GOLF_B)) {
            boolean solidGone = false;
            for (LoggingWorld.Write w : writesSinceUpgrade()) {
                if (!half.contains(w.x(), w.y(), w.z()) || w.was() == null) {
                    continue;
                }
                boolean water = w.was().startsWith("minecraft:water");
                if (!water) {
                    solidGone = true;
                } else if (solidGone) {
                    out.add("a pond in golf's old half " + half.describe() + " was drained after a wall went");
                    break;
                }
            }
        }
        // the boat: its old halves emptied, still off, nothing of it left anywhere, ready for its new spot
        if (world.count(BOAT_A) + world.count(BOAT_B) != 0) {
            out.add("the boat's old halves still hold " + (world.count(BOAT_A) + world.count(BOAT_B)) + " blocks");
        }
        GenService.SlotReport boat = gen.report().stream().filter(r -> r.id().equals(BOAT)).findFirst().orElseThrow();
        if (boat.wanted() || boat.live() != null || boat.claimed()) {
            out.add("the boat isn't off and empty: " + boat);
        }
        for (String id : List.of(GOLF, BOAT, CLASSIC)) {
            if (host.store.meta(GenAdminKeys.old(id)) != null) {
                out.add(id + "'s old area is still recorded: " + host.store.meta(GenAdminKeys.old(id)));
            }
            if (OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(id))) == null) {
                out.add(id + "'s emptied old area isn't on record for the check");
            }
        }
        for (String id : List.of(BOAT, CLASSIC)) {
            if (host.store.meta(GenAdminKeys.claim(id)) != null) {
                out.add(id + " still claims " + host.store.meta(GenAdminKeys.claim(id)));
            }
        }
        if (!gen.oldAreas().isEmpty()) {
            out.add("old areas still stand: " + gen.oldAreas());
        }
        // every write went into the moved areas: the old ones' recorded halves, or golf's new one
        for (LoggingWorld.Write w : writesSinceUpgrade()) {
            boolean ok = false;
            for (Box b : List.of(GOLF_A, GOLF_B, BOAT_A, BOAT_B, CLASSIC_A, CLASSIC_B, GOLF_V4_A, GOLF_V4_B)) {
                ok |= b.contains(w.x(), w.y(), w.z());
            }
            if (!ok) {
                out.add("a block was written outside the recorded old halves and golf's new area: " + w);
                break;
            }
        }
        // nothing else touched
        Map<Long, String> now = new HashMap<>();
        for (Map.Entry<Long, String> e : world.blocks.entrySet()) {
            if (!moved(e.getKey())) {
                now.put(e.getKey(), e.getValue() + world.signs.getOrDefault(e.getKey(), List.of()));
            }
        }
        if (!now.equals(outside)) {
            out.add("blocks outside the moved areas changed");
        }
        for (String id : OTHERS) {
            GenTag t = gen.liveTag(id);
            if (t == null || !t.sameLayout(before.get(id)) || !gen.live(id, t)) {
                out.add(id + " isn't open with its 0.36 course any more");
            }
        }
        return out;
    }
}
