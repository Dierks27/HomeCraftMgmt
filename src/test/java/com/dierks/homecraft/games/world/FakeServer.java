package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A server with no server: players, worlds, a clock, a scheduler and teleports that arrive only
 * when a test says so — enough to walk every world-session path through {@link SessionCore}
 * without Bukkit. Items are strings; a kit item starts with {@code "kit:"}. An item blob is the
 * slots joined by newlines; a blob starting {@code JUNK} can't be read.
 */
final class FakeServer implements SessionCore.Port<FakeServer.Body, String> {

    static final int SLOTS = 41;
    static final int STORAGE = 36;

    /** A player, as much of one as the state machine can see. */
    static final class Body {
        final UUID id;
        final String name;
        boolean online = true;
        boolean dead;
        Place place;
        SessionCore.Standing standing = SessionCore.Standing.still();
        String gameMode = "SURVIVAL";
        double health = 20;
        double maxHealth = 20;
        double absorption;
        int food = 18;
        float saturation = 3f;
        float exhaustion = 1f;
        int level = 12;
        float exp = 0.25f;
        int totalXp = 300;
        float walk = 0.2f;
        float fly = 0.1f;
        boolean allowFlight;
        boolean flying;
        int fire;
        int air = 300;
        List<SavedStateCodec.Effect> effects = new ArrayList<>();
        String[] slots = new String[SLOTS];
        String cursor;
        String[] grid = new String[4];
        final List<String> ender = new ArrayList<>();
        /** A view another plugin holds open keeps the cursor where it is. */
        boolean stickyCursor;
        final List<String> messages = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        int applies;
        final List<String> appliedIn = new ArrayList<>();
        int saves;
        /** The mark in the player's own data (on the server it is saved with their inventory). */
        String mark;
        /** How far they have fallen since they last stood on something (the server keeps it through a teleport). */
        float fall;
        /** How fast they are moving (the server keeps that through a teleport too). */
        double speed;
        /** Where they were when {@code resetMode} last took them out of a game's mode (a watcher's spectator). */
        Place modeResetAt;
        /** The player's own data file: the body as of its last save (ours, or the server's autosave). */
        private DataFile file;

        /** What the player's data file holds. */
        private record DataFile(String[] slots, String mark, String gameMode, Place place, float fall) {
        }

        Body(String name, Place place) {
            this(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, place);
        }

        /** A body that shares another bench's player id ({@link SessionBench}: one player, two benches). */
        Body(UUID id, String name, Place place) {
            this.id = id;
            this.name = name;
            this.place = place;
        }

        List<String> items() {
            List<String> out = new ArrayList<>();
            for (String s : slots) {
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }

        boolean holdsKit() {
            return items().stream().anyMatch(i -> i.startsWith("kit:")) || ender.stream().anyMatch(i -> i.startsWith("kit:"))
                    || (cursor != null && cursor.startsWith("kit:"));
        }

        /**
         * Their data file is written as the body is now: our own {@code save}, or the server's autosave
         * (Paper saves every online player every few minutes, whatever a game is doing).
         */
        void autosave() {
            file = new DataFile(slots.clone(), mark, gameMode, place, fall);
        }

        /** A hard crash (power lost, the process killed): the body comes back as its data file last had it. */
        void crash() {
            if (file == null) {
                return; // never written: it comes back as it is
            }
            slots = file.slots().clone();
            mark = file.mark();
            gameMode = file.gameMode();
            place = file.place();
            fall = file.fall();
            cursor = null;
            Arrays.fill(grid, null);
        }

        /** The fall distance in their data file ({@code NaN} if it was never written). */
        float savedFall() {
            return file == null ? Float.NaN : file.fall();
        }

        int firstEmpty() {
            for (int i = 0; i < STORAGE; i++) {
                if (slots[i] == null) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A teleport on its way: it arrives (or fails) when the test says. */
    record Trip(Body body, Place to, Consumer<Boolean> done) {
    }

    private record Task(long due, Runnable run) {
    }

    long tick = 1_000;
    long now = 1_790_000_000_000L;
    boolean stopping;
    boolean syncTeleportsWork = true;
    final Set<String> worlds = new HashSet<>(Set.of("world", "games", "nether"));
    final Set<String> gamesWorlds = new HashSet<>(Set.of("games"));
    final List<Trip> trips = new ArrayList<>();
    /** Every async teleport ever started. */
    int started;
    final List<Place> syncTeleports = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();
    /** Runs just before a state is captured (to race another row in). */
    Runnable beforeCapture;
    /** Runs as each snapshot is decoded (to break the row or the database under a recovery). */
    Runnable beforePrepare;
    /** Every restore throws before it changes anything (a snapshot that won't go on). */
    boolean applyFails;

    // ---- the test's controls --------------------------------------------------------------------

    /** Advance one tick and run what was due. */
    void step() {
        tick++;
        now += 50;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.due() <= tick) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            t.run().run();
        }
    }

    void steps(int n) {
        for (int i = 0; i < n; i++) {
            step();
        }
    }

    /** Every trip on its way arrives. */
    void arriveAll() {
        while (!trips.isEmpty()) {
            arrive(0);
        }
    }

    /** One trip arrives (the player is moved, then the callback runs, as on the server). */
    void arrive(int index) {
        Trip t = trips.remove(index);
        if (t.body().online) {
            t.body().place = t.to();
        }
        t.done().accept(t.body().online);
    }

    /** One trip fails. */
    void fail(int index) {
        Trip t = trips.remove(index);
        t.done().accept(false);
    }

    int pendingTasks() {
        return tasks.size();
    }

    // ---- the port -------------------------------------------------------------------------------

    @Override
    public UUID id(Body p) {
        return p.id;
    }

    @Override
    public String name(Body p) {
        return p.name;
    }

    @Override
    public boolean online(Body p) {
        return p.online;
    }

    @Override
    public boolean dead(Body p) {
        return p.dead;
    }

    @Override
    public String world(Body p) {
        return p.place.world();
    }

    @Override
    public Place location(Body p) {
        return p.place;
    }

    @Override
    public boolean worldExists(String world) {
        return worlds.contains(world);
    }

    @Override
    public Place spawn(String world) {
        return worlds.contains(world) ? Place.of(world, 0, 64, 0) : null;
    }

    @Override
    public Place mainSpawn() {
        return Place.of("world", 0, 64, 0);
    }

    @Override
    public boolean gamesWorld(String world) {
        return gamesWorlds.contains(world);
    }

    @Override
    public boolean loaded(Place place) {
        return true;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    public boolean stopping() {
        return stopping;
    }

    @Override
    public void later(long ticks, Runnable task) {
        if (!stopping) {
            tasks.add(new Task(tick + Math.max(1, ticks), task));
        }
    }

    @Override
    public void teleport(Body p, Place to, Consumer<Boolean> done) {
        if (!stopping) {
            started++;
            trips.add(new Trip(p, to, done));
        }
    }

    @Override
    public boolean teleportNow(Body p, Place to) {
        syncTeleports.add(to);
        if (!syncTeleportsWork) {
            return false;
        }
        p.place = to;
        return true;
    }

    @Override
    public SessionCore.Standing standing(Body p, String gameId) {
        return p.dead ? new SessionCore.Standing(true, false, false, false, false, false, 0, true, 0, false, false,
                Long.MAX_VALUE) : p.standing;
    }

    @Override
    public void closeInventory(Body p) {
        if (p.stickyCursor) {
            return;
        }
        List<String> held = new ArrayList<>();
        if (p.cursor != null) {
            held.add(p.cursor);
            p.cursor = null;
        }
        for (int i = 0; i < p.grid.length; i++) {
            if (p.grid[i] != null) {
                held.add(p.grid[i]);
                p.grid[i] = null;
            }
        }
        for (String item : held) {
            int slot = p.firstEmpty();
            if (slot < 0) {
                p.dropped.add(item);
            } else {
                p.slots[slot] = item;
            }
        }
    }

    @Override
    public boolean handsFree(Body p) {
        return p.cursor == null && Arrays.stream(p.grid).allMatch(i -> i == null);
    }

    @Override
    public SavedState capture(Body p, String sessionId, String gameId, String ref, String sessionWorld, Place from,
                              long now) {
        if (beforeCapture != null) {
            beforeCapture.run();
        }
        return new SavedState(p.id, gameId, ref, SavedState.ACTIVE, sessionId, sessionWorld, blob(p.slots), null,
                p.level, p.exp, p.totalXp, p.health, p.food, p.saturation, p.exhaustion, p.fire, p.air, p.gameMode,
                p.allowFlight, p.flying, p.walk, p.fly, p.absorption, SavedStateCodec.encodeEffects(p.effects),
                from.world(), from.x(), from.y(), from.z(), from.yaw(), from.pitch(), now, null);
    }

    @Override
    public SessionCore.Restore<Body> prepare(SavedState s) {
        if (beforePrepare != null) {
            beforePrepare.run();
        }
        String[] contents = slots(s.items());
        List<SavedStateCodec.Effect> effects = SavedStateCodec.decodeEffects(s.effects());
        return p -> {
            if (applyFails) {
                throw new IllegalStateException("the snapshot won't go on");
            }
            p.applies++;
            p.appliedIn.add(p.place.world());
            SavedStateCodec.apply(s, effects, new FakeBody(p, contents));
        };
    }

    @Override
    public void clearForGame(Body p) {
        SavedStateCodec.clearForGame(new FakeBody(p, null));
    }

    @Override
    public List<String> takeExtras(Body p) {
        List<String> out = new ArrayList<>();
        if (p.cursor != null && !p.cursor.startsWith("kit:")) {
            out.add(p.cursor);
        }
        p.cursor = null;
        for (int i = 0; i < p.grid.length; i++) {
            if (p.grid[i] != null && !p.grid[i].startsWith("kit:")) {
                out.add(p.grid[i]);
            }
            p.grid[i] = null;
        }
        for (String s : p.slots) {
            if (s != null && !s.startsWith("kit:")) {
                out.add(s);
            }
        }
        p.slots = new String[SLOTS];
        return out;
    }

    @Override
    public void discardHeld(Body p) {
        p.cursor = null;
        Arrays.fill(p.grid, null);
    }

    @Override
    public void stripKit(Body p) {
        for (int i = 0; i < p.slots.length; i++) {
            if (p.slots[i] != null && p.slots[i].startsWith("kit:")) {
                p.slots[i] = null;
            }
        }
        if (p.cursor != null && p.cursor.startsWith("kit:")) {
            p.cursor = null;
        }
        p.ender.removeIf(i -> i.startsWith("kit:"));
    }

    @Override
    public List<String> give(Body p, List<String> items) {
        List<String> left = new ArrayList<>();
        for (String item : items) {
            int slot = p.firstEmpty();
            if (slot < 0) {
                left.add(item);
            } else {
                p.slots[slot] = item;
            }
        }
        return left;
    }

    @Override
    public int room(Body p, List<String> items) {
        int free = 0;
        for (int i = 0; i < STORAGE; i++) {
            if (p.slots[i] == null) {
                free++;
            }
        }
        return Math.min(free, items.size());
    }

    @Override
    public String mark(Body p) {
        return p.mark;
    }

    @Override
    public void setMark(Body p, String mark) {
        p.mark = mark;
    }

    @Override
    public byte[] encode(List<String> items) {
        return items.isEmpty() ? null : String.join("\n", items).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<String> decode(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new ArrayList<>();
        }
        String text = new String(blob, StandardCharsets.UTF_8);
        if (text.startsWith("JUNK")) {
            throw new IllegalArgumentException("unreadable");
        }
        return new ArrayList<>(List.of(text.split("\n")));
    }

    @Override
    public String describe(String item) {
        return item;
    }

    @Override
    public void drop(Body p, List<String> items) {
        p.dropped.addAll(items);
    }

    @Override
    public void save(Body p) {
        p.saves++;
        p.autosave();
    }

    @Override
    public void still(Body p) {
        p.fall = 0f;
        p.speed = 0;
    }

    @Override
    public void dismount(Body p) {
    }

    @Override
    public void removeGameVehicle(Body p) {
    }

    @Override
    public void resetMode(Body p, String sessionMode) {
        if (sessionMode != null && sessionMode.equals(p.gameMode)) {
            p.gameMode = "ADVENTURE";
            p.modeResetAt = p.place;
        }
    }

    @Override
    public String gameMode(Body p) {
        return p.gameMode;
    }

    @Override
    public void tell(Body p, String line) {
        p.messages.add(line);
    }

    // ---- blobs ------------------------------------------------------------------------------------

    static byte[] blob(String[] slots) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(slots[i] == null ? "" : slots[i]);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String[] slots(byte[] blob) {
        String text = blob == null ? "" : new String(blob, StandardCharsets.UTF_8);
        if (text.startsWith("JUNK")) {
            throw new IllegalArgumentException("unreadable");
        }
        String[] parts = text.split("\n", -1);
        String[] out = new String[SLOTS];
        for (int i = 0; i < Math.min(SLOTS, parts.length); i++) {
            out[i] = parts[i].isEmpty() ? null : parts[i];
        }
        return out;
    }

    /** {@link SavedStateCodec.Body} over a fake player. */
    private static final class FakeBody implements SavedStateCodec.Body {
        private final Body p;
        private final String[] contents;

        FakeBody(Body p, String[] contents) {
            this.p = p;
            this.contents = contents;
        }

        @Override
        public void gameMode(String mode) {
            p.gameMode = mode;
            if (!mode.equals("CREATIVE") && !mode.equals("SPECTATOR")) {
                p.allowFlight = false;
                p.flying = false;
            }
        }

        @Override
        public void clearEffects() {
            p.effects = new ArrayList<>();
        }

        @Override
        public void addEffect(SavedStateCodec.Effect effect) {
            p.effects.add(effect);
        }

        @Override
        public double maxHealth() {
            return p.maxHealth;
        }

        @Override
        public void health(double health) {
            p.health = health;
        }

        @Override
        public double maxAbsorption() {
            return 16;
        }

        @Override
        public void absorption(double amount) {
            p.absorption = amount;
        }

        @Override
        public void food(int level, float saturation, float exhaustion) {
            p.food = level;
            p.saturation = saturation;
            p.exhaustion = exhaustion;
        }

        @Override
        public void xp(int level, float progress, int total) {
            p.level = level;
            p.exp = progress;
            p.totalXp = total;
        }

        @Override
        public void speeds(float walk, float fly) {
            p.walk = walk;
            p.fly = fly;
        }

        @Override
        public void flight(boolean allowFlight, boolean flying) {
            p.allowFlight = allowFlight;
            p.flying = flying;
        }

        @Override
        public void still() {
            p.fall = 0f;
            p.speed = 0;
        }

        @Override
        public void fire(int ticks) {
            p.fire = ticks;
        }

        @Override
        public void air(int ticks) {
            p.air = ticks;
        }

        @Override
        public void contents() {
            p.slots = Arrays.copyOf(contents, SLOTS);
        }

        @Override
        public void clearContents() {
            p.cursor = null;
            p.slots = new String[SLOTS];
        }
    }

    /** A game that gives its kit when ready and remembers what it was told. */
    static final class Game implements SessionCore.Hooks<Body> {
        final String id;
        int ready;
        int voided;
        final List<EndReason> ended = new ArrayList<>();

        Game(String id) {
            this.id = id;
        }

        @Override
        public void ready(Body p) {
            ready++;
            p.slots[0] = "kit:" + id + ":checkpoint";
            p.slots[8] = "kit:" + id + ":leave";
            p.slots[38] = "kit:" + id + ":elytra";
        }

        @Override
        public void ended(Body p, EndReason reason) {
            ended.add(reason);
        }

        @Override
        public void voided(Body p) {
            voided++;
        }
    }
}
