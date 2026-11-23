package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.RaceScript;
import com.dierks.homecraft.games.chance.mobraces.RaceTrack;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mob Races in the room: the model racetrack drawn on its table, the tiny mobs that run on it, the board over it and
 * the sounds. {@link MobRaces} is the race (its clock, its rounds, who won); this only shows it, and tells the game
 * once a second that there is a track to race on.
 *
 * <p><b>The track</b> ({@link RaceTable}): block displays laid on the table's top, three tap targets that take in
 * the whole table (either click opens Mob Races' screen, as at every machine), a label ("Mob Races", and the seconds
 * left while entries are taken) and under it the board: who is in on which racer, who is in front, who won.
 *
 * <p><b>The racers</b> are real mobs drawn small (the scale attribute), spawned at the line when the first entry is
 * made and removed when the result has stood. They can't do anything by themselves: no AI, no sound, no gravity,
 * nothing can hurt or push them, they pick nothing up and lay nothing. They are moved along their lanes every tick
 * from the race's script, turned the way they run, each with a small flag in its colour riding on it.
 *
 * <p>Everything is a game entity ({@link WorldEntities#tag}): never saved, swept at every start and whenever a chunk
 * loads with one in it, and nobody can touch one (the games' own guard).
 *
 * <p><b>The track's trouble is never the Clubhouse's.</b> Anything that goes wrong drawing it (a block or a mob this
 * server doesn't have) takes the track down with one WARN and is tried again a minute later; the room stays open, and
 * Mob Races, with no track, takes no entries meanwhile.
 */
final class RaceShow {

    /** What the track's tap targets do: the same as a machine's tap, for Mob Races. */
    static final String TAP = "machine:" + ClubhouseRoom.MOB_RACES;
    /** How far the tap targets reach past the table, and how high over its top (past the generated room's barrier). */
    static final float TAP_PAST = 0.02f;
    static final float TAP_UP = 2.02f;
    /** The label and the board, over the table's middle. */
    static final double BOARD_UP = 1.5;
    static final double LABEL_UP = 2.55;
    /** How many entrants the board names before "and N more". */
    static final int BOARD_NAMES = 6;

    private final Clubhouse club;
    private RaceTable.Frame frame;
    private final List<UUID> drawn = new ArrayList<>();
    private UUID label;
    private UUID board;
    private String labelText;
    private String boardText;
    private final UUID[] mobs = new UUID[Racer.COUNT];
    private final UUID[] flags = new UUID[Racer.COUNT];
    private final boolean[] rides = new boolean[Racer.COUNT];
    private final Location[] placed = new Location[Racer.COUNT];
    private MobRaces.Phase lastPhase = MobRaces.Phase.IDLE;
    private long ticks;
    private long spawnAfter;
    /** How many racers have had to be made again since the last rest, and whether the console has been told. */
    private int vanished;
    private boolean saidVanish;
    /** After something went wrong: nothing is drawn until this tick, and the last trouble said. */
    private long restAfterTrouble;
    private String trouble;
    /** How long the track rests after trouble before it is tried again (ticks). */
    static final long TROUBLE_REST = 20 * 60;
    /** Why there is no track, for the admins; {@code null} while it is up. And the reason last written to the log. */
    private String why = "not looked for yet";
    private String logged;

    RaceShow(Clubhouse club) {
        this.club = club;
    }

    /** Mob Races, while it is there and open. */
    private MobRaces races() {
        Game g = club.games().game(ClubhouseRoom.MOB_RACES);
        return g instanceof MobRaces m && club.games().enabled(m) ? m : null;
    }

    // ---- once a second: the track --------------------------------------------------------------------

    /** Once a second while the room is open: draw the track where it belongs, keep its board, tell the game. */
    void second(ClubhouseRoom room) {
        if (ticks < restAfterTrouble) {
            return;
        }
        try {
            look(room);
        } catch (RuntimeException | LinkageError e) {
            troubled(e);
        }
    }

    /** Every tick: the racers stand at the line while entries are taken, run the race's script, then go. */
    void tick() {
        ticks++;
        if (ticks < restAfterTrouble) {
            return;
        }
        try {
            run();
        } catch (RuntimeException | LinkageError e) {
            troubled(e);
        }
    }

    /** Something went wrong: everything comes down, one WARN (a new one only for new trouble), and a rest. */
    private void troubled(Throwable e) {
        restAfterTrouble = ticks + TROUBLE_REST;
        String what = String.valueOf(e);
        why = "it could not be drawn (" + what + "); it is tried again in a minute";
        if (!what.equals(trouble)) {
            trouble = what;
            club.log().log(java.util.logging.Level.WARNING, "Clubhouse: Mob Races' track could not be drawn; the room"
                    + " stays open and the track is tried again every minute", e);
        }
        try {
            remove();
        } catch (RuntimeException | LinkageError ignored) {
            // what could not be removed is a game entity: the next start's sweep takes it
        }
    }

    private void look(ClubhouseRoom room) {
        MobRaces races = races();
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (races == null || w == null) {
            none(races == null ? "Mob Races is closed (/hcm games status says why)" : "the room's world isn't loaded",
                    false);
            return;
        }
        ClubhouseRoom.RaceSpot spot = club.settings().parlor() || room.hand() ? room.raceTrack() : null;
        if (spot == null) {
            none(room.hand() ? "not placed: build a table " + RaceTrack.LENGTH + " long, " + RaceTrack.DEPTH
                    + " wide and one block high, stand on a corner of it facing along it, and use /hcm games clubhouse"
                    + " parlor mob_races" : "the Game Parlor isn't built", false);
            return;
        }
        RaceTable.Frame f = spot.frame();
        if (!loaded(w, f)) {
            return; // nobody near: what is up stays, nothing is made
        }
        if (!spot.built() && !tableThere(w, f)) {
            String table = "a table " + RaceTrack.LENGTH + " long, " + RaceTrack.DEPTH + " wide and one block high";
            none(spot.own() ? "there is no table under the place you set (" + table + ", from the corner you stood"
                    + " on, the way you faced and to your right)"
                    : "your " + (room.editing() ? "edited" : "saved") + " room has no table where HCM's stands: build "
                    + table + " anywhere in the Parlor, stand on a corner of it facing along it and use /hcm games"
                    + " clubhouse parlor mob_races (or /hcm games clubhouse generated for HCM's room)", true);
            return;
        }
        why = null;
        logged = null;
        if (!f.equals(frame) || !alive()) {
            remove();
            draw(w, f);
        }
        texts(races);
        races.trackSeen();
    }

    /**
     * No track, and why. With {@code say}, the reason is written to the console once (an owner whose saved room has
     * no table would otherwise only find Mob Races missing): the same words as the status line's.
     */
    private void none(String reason, boolean say) {
        why = reason;
        if (say && !reason.equals(logged)) {
            logged = reason;
            club.log().info("Clubhouse: Mob Races has no track - " + reason);
        }
        remove();
    }

    private static boolean loaded(World w, RaceTable.Frame f) {
        double[] box = f.footprint();
        return w.isChunkLoaded((int) Math.floor(box[0]) >> 4, (int) Math.floor(box[1]) >> 4)
                && w.isChunkLoaded((int) Math.floor(box[2] - 0.01) >> 4, (int) Math.floor(box[3] - 0.01) >> 4);
    }

    /** Whether every block of the table is there to stand the track on (a solid block under each of its cells). */
    private static boolean tableThere(World w, RaceTable.Frame f) {
        for (int i = 0; i < RaceTrack.LENGTH; i++) {
            for (int k = 0; k < RaceTrack.DEPTH; k++) {
                int[] cell = f.cell(i, k);
                if (!w.getBlockAt(cell[0], f.blockY(), cell[1]).getType().isSolid()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean alive() {
        if (frame == null || drawn.isEmpty()) {
            return false;
        }
        for (UUID id : drawn) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) {
                return false;
            }
        }
        return true;
    }

    private void draw(World w, RaceTable.Frame f) {
        frame = f;
        for (RaceTable.Piece p : RaceTable.pieces()) {
            double[] box = p.box(f);
            drawn.add(w.spawn(new Location(w, box[0], box[1], box[2]), BlockDisplay.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setBlock(Bukkit.createBlockData(p.block()));
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f((float) box[3], (float) box[4], (float) box[5]), new Quaternionf()));
                // one light for the whole model: a display takes its light from the one block it stands in, so
                // without this each piece would be lit a little differently from the piece beside it
                e.setBrightness(new Display.Brightness(15, 15));
            }).getUniqueId());
        }
        // three tap targets side by side take in the whole table, from the floor to over the track
        float wide = RaceTrack.DEPTH + 2 * TAP_PAST;
        for (double u : new double[]{RaceTrack.DEPTH / 2.0, RaceTrack.LENGTH / 2.0,
                RaceTrack.LENGTH - RaceTrack.DEPTH / 2.0}) {
            Location at = new Location(w, f.x(u, RaceTrack.DEPTH / 2.0), f.top() - 1 - TAP_PAST,
                    f.z(u, RaceTrack.DEPTH / 2.0));
            drawn.add(w.spawn(at, Interaction.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setInteractionWidth(wide);
                e.setInteractionHeight(1 + TAP_UP + 2 * TAP_PAST);
                e.setResponsive(true);
                e.getPersistentDataContainer().set(Keys.CLUB_TAP, PersistentDataType.STRING, TAP);
            }).getUniqueId());
        }
        double mx = f.x(RaceTrack.LENGTH / 2.0, RaceTrack.DEPTH / 2.0);
        double mz = f.z(RaceTrack.LENGTH / 2.0, RaceTrack.DEPTH / 2.0);
        label = text(w, new Location(w, mx, f.top() + LABEL_UP, mz), 0.7f, 0).getUniqueId();
        board = text(w, new Location(w, mx, f.top() + BOARD_UP, mz), 0.5f, 120).getUniqueId();
        drawn.add(label);
        drawn.add(board);
        labelText = null;
        boardText = null;
    }

    private TextDisplay text(World w, Location at, float size, int shade) {
        return w.spawn(at, TextDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBillboard(Display.Billboard.VERTICAL); // upright, turned to whoever looks: read from all round
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size),
                    new Quaternionf()));
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(shade, 0, 0, 0));
            e.setShadowed(shade == 0);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setLineWidth(260);
            e.text(Text.of(" "));
        });
    }

    /** The label and the board as they read now; an entity's text is only set when it changes. */
    private void texts(MobRaces races) {
        String l = labelLine(races.phase(), races.secondsLeft());
        if (!l.equals(labelText) && Bukkit.getEntity(label) instanceof TextDisplay t) {
            t.text(Text.of(l));
            labelText = l;
        }
        List<String[]> in = new ArrayList<>();
        List<String> winners = new ArrayList<>();
        Racer won = races.winner();
        for (MobRaces.Entry e : races.entries()) {
            in.add(new String[]{e.name(), e.pick().label()});
            if (won != null && e.pick() == won) {
                winners.add(e.name());
            }
        }
        RaceScript script = races.script();
        Racer front = script == null || races.phase() != MobRaces.Phase.RUNNING ? null
                : Racer.of(script.leaderAt(Math.min(races.raceTick(), script.ticks())));
        List<String> card = new ArrayList<>();
        if (races.phase() == MobRaces.Phase.IDLE || races.phase() == MobRaces.Phase.GATHERING) {
            List<Integer> open = races.settings().open();
            RaceCard c = races.card();
            for (Racer r : Racer.values()) {
                int stake = open.isEmpty() ? 0 : open.get(0);
                RaceCard.Pay p = races.pay(r, stake, false);
                card.add(cardLine(r, c.oneIn(r), stake, p == null ? 0 : p.pays(), c.favourite() == r,
                        c.longest() == r));
            }
        }
        List<Racer> order = new ArrayList<>();
        if (won != null && script != null) {
            for (int place = 0; place < Racer.COUNT; place++) {
                order.add(Racer.of(script.finisher(place)));
            }
        }
        String b = String.join("\n", boardLines(races.phase(), races.secondsLeft(), in, front, won, winners,
                races.lastWinner(), card, order));
        if (!b.equals(boardText) && Bukkit.getEntity(board) instanceof TextDisplay t) {
            t.text(Text.of(b));
            boardText = b;
        }
    }

    /** The table's label: "Mob Races", with the seconds left while entries are taken (pure). */
    static String labelLine(MobRaces.Phase phase, int secondsLeft) {
        return switch (phase) {
            case GATHERING -> "&a&lMob Races &7· &e" + Math.max(0, secondsLeft) + " s";
            case RUNNING -> "&a&lMob Races &7· &eracing";
            default -> "&a&lMob Races";
        };
    }

    /**
     * One racer's line on the board while entries are taken (pure): its chance and what it pays at the smallest
     * stake, the favourite and the longest shot marked. {@code pays} 0 is a racer not offered at that stake.
     */
    static String cardLine(Racer r, String oneIn, int stake, int pays, boolean favourite, boolean longest) {
        String mark = favourite ? " " + RaceCard.FAVOURITE : longest ? " " + RaceCard.LONGEST : "";
        return r.label() + mark + " &7wins 1 in " + oneIn + " &7· " + (pays > 0 ? stake + " in → &e" + pays
                : "&8not at " + stake);
    }

    /**
     * The board's lines (pure): the race's card and what to do while the track is free, the countdown, the card and
     * who is in on which racer while entries are taken, who is in front while they run, and the result (the winner,
     * the order behind it, whoever picked it).
     *
     * @param in      each entrant's name and their racer's label, in the order they entered
     * @param front   who is in front now (while the race is run), or {@code null}
     * @param won     the winner once it is home, or {@code null}
     * @param winners the entrants who picked the winner
     * @param last    who won the last race, or {@code null}
     * @param card    each racer's line on this race's card ({@link #cardLine}), shown while entries are taken
     * @param order   the finishing order once the winner is home (empty before)
     */
    static List<String> boardLines(MobRaces.Phase phase, int secondsLeft, List<String[]> in, Racer front, Racer won,
                                   List<String> winners, Racer last, List<String> card, List<Racer> order) {
        List<String> out = new ArrayList<>();
        switch (phase) {
            case GATHERING -> {
                out.add("&eThe race starts in " + Math.max(0, secondsLeft) + " s");
                out.addAll(card);
                names(out, in);
                out.add("&7Tap the table to enter too");
            }
            case RUNNING -> {
                out.add("&eThey're off!");
                if (front != null) {
                    out.add("&7In front: " + front.label());
                }
                names(out, in);
            }
            case RESULT -> {
                out.add(won == null ? "&6The race is over" : "&6The winner: " + won.label());
                if (order.size() == Racer.COUNT) {
                    List<String> rest = new ArrayList<>();
                    for (int place = 1; place < order.size(); place++) {
                        rest.add(order.get(place).label());
                    }
                    out.add("&7Then: " + String.join("&7, ", rest));
                }
                if (winners.isEmpty()) {
                    out.add("&7Nobody picked it this time.");
                } else {
                    for (int n = 0; n < winners.size() && n < BOARD_NAMES; n++) {
                        out.add("&a" + winners.get(n) + " picked it!");
                    }
                }
            }
            default -> {
                out.add("&fSix racers. Pick one.");
                out.addAll(card);
                out.add("&7Tap the table to enter");
                if (last != null) {
                    out.add("&7Last winner: " + last.label());
                }
            }
        }
        return out;
    }

    private static void names(List<String> out, List<String[]> in) {
        for (int n = 0; n < in.size() && n < BOARD_NAMES; n++) {
            out.add("&f" + in.get(n)[0] + " &7on " + in.get(n)[1]);
        }
        if (in.size() > BOARD_NAMES) {
            out.add("&7and " + (in.size() - BOARD_NAMES) + " more");
        }
    }

    // ---- every tick: the racers -----------------------------------------------------------------------

    private void run() {
        MobRaces races = frame == null ? null : races();
        World w = races == null ? null : Bukkit.getWorld(club.room() == null ? "" : club.room().world());
        MobRaces.Phase phase = races == null || w == null ? MobRaces.Phase.IDLE : races.phase();
        if (phase == MobRaces.Phase.IDLE) {
            removeRacers();
            lastPhase = phase;
            return;
        }
        RaceTable.Frame f = frame;
        RaceScript script = races.script();
        int at = races.raceTick();
        double length = races.laps() * RaceTrack.LAP;
        Location lead = null;
        double leadAt = -1;
        Location winner = null;
        for (Racer r : Racer.values()) {
            int lane = r.lane();
            double run = script == null ? 0 : script.at(lane, at);
            double dist = script == null ? 0 : run * length + script.beyond(lane, at);
            RaceTrack.Point p = RaceTrack.at(lane, dist);
            Location loc = new Location(w, f.x(p.u(), p.w()), f.top() + RaceTable.SURFACE, f.z(p.u(), p.w()),
                    f.yaw(p.heading()), 0f);
            place(w, r, loc);
            if (run > leadAt) {
                leadAt = run;
                lead = loc;
            }
            if (script != null && script.winner() == lane) {
                winner = loc;
            }
        }
        if (phase == MobRaces.Phase.RUNNING) {
            if (lastPhase != MobRaces.Phase.RUNNING) {
                Location line = new Location(w, f.x(RaceTrack.LINE_U, 0.5), f.top() + 0.4, f.z(RaceTrack.LINE_U, 0.5));
                w.playSound(line, Sound.BLOCK_BELL_USE, 0.6f, 1.3f);
            } else if (ticks % 4 == 0 && lead != null) {
                w.playSound(lead, Sound.ENTITY_CHICKEN_STEP, 0.18f, 1.5f); // a light patter where the leader is
            }
        } else if (phase == MobRaces.Phase.RESULT && winner != null) {
            Location over = winner.clone().add(0, 0.35, 0);
            if (lastPhase != MobRaces.Phase.RESULT) {
                w.playSound(over, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
                w.playSound(over, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.35f, 1.2f);
                w.spawnParticle(Particle.FIREWORK, over, 30, 0.2, 0.25, 0.2, 0.05);
            } else if (ticks % 10 == 0) {
                w.spawnParticle(Particle.HAPPY_VILLAGER, over, 3, 0.15, 0.1, 0.15, 0.0);
            }
        }
        lastPhase = phase;
    }

    /** Put {@code r}'s mob at {@code loc} (made there if it isn't up), its flag with it. */
    private void place(World w, Racer r, Location loc) {
        int n = r.lane();
        Entity mob = mobs[n] == null ? null : Bukkit.getEntity(mobs[n]);
        if (mob == null || !mob.isValid()) {
            if (ticks < spawnAfter || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                return;
            }
            if (mobs[n] != null && ++vanished > 2 * Racer.COUNT) {
                // the racers don't stay (a server with animals switched off removes them): no making them over and
                // over. The race itself goes on, and the board still says how it comes out
                vanished = 0;
                spawnAfter = ticks + 200;
                if (!saidVanish) {
                    saidVanish = true;
                    club.log().warning("Clubhouse: Mob Races' racers keep disappearing from the track (does this"
                            + " server or world let animals be?). The races still run and pay; only the mobs are"
                            + " missing.");
                }
                removeRacer(n);
                return;
            }
            removeRacer(n);
            mob = spawn(w, r, loc);
            if (mob == null) {
                spawnAfter = ticks + 20; // something here won't let it be made: not asked again every tick
                return;
            }
            mobs[n] = mob.getUniqueId();
            placed[n] = loc;
            flag(w, r, mob);
            return;
        }
        Location was = placed[n];
        if (was != null && was.getX() == loc.getX() && was.getZ() == loc.getZ() && was.getYaw() == loc.getYaw()) {
            return; // standing where it stood
        }
        placed[n] = loc;
        Entity flag = flags[n] == null ? null : Bukkit.getEntity(flags[n]);
        if (!mob.teleport(loc) && rides[n]) {
            // this server won't move a mob with a rider: the flag gets off and is moved over it instead
            if (flag != null) {
                flag.leaveVehicle();
            }
            rides[n] = false;
            mob.teleport(loc);
        }
        if (mob instanceof LivingEntity living) {
            living.setBodyYaw(loc.getYaw());
        }
        if (rides[n] && (flag == null || !flag.isInsideVehicle())) {
            rides[n] = false; // the move set its rider down: from here the flag is moved with it
        }
        if (!rides[n] && flag != null) {
            flag.teleport(loc.clone().add(0, flagUp(r), 0));
        }
    }

    /** How far over the track a racer's flag floats when it can't ride: just over the mob's head. */
    static double flagUp(Racer r) {
        return 1.5 * r.scale() + 0.05;
    }

    private Entity spawn(World w, Racer r, Location loc) {
        Class<? extends Entity> type;
        try {
            type = EntityType.valueOf(r.mob()).getEntityClass();
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            return null;
        }
        try {
            return w.spawn(loc, type, e -> dress(e, r));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A racer: small, and able to do nothing by itself. */
    private void dress(Entity e, Racer r) {
        WorldEntities.tag(e, club, null);
        e.setSilent(true);
        e.setInvulnerable(true);
        e.setGravity(false);
        if (e instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
            living.setCanPickupItems(false);
            living.setRemoveWhenFarAway(false);
            AttributeInstance scale = living.getAttribute(Attribute.SCALE);
            if (scale == null) {
                living.registerAttribute(Attribute.SCALE);
                scale = living.getAttribute(Attribute.SCALE);
            }
            if (scale != null) {
                scale.setBaseValue(r.scale());
            }
        }
        if (e instanceof Mob mob) {
            mob.setAware(false);
        }
        if (e instanceof Ageable grown) {
            grown.setAdult();
        }
        if (e instanceof Breedable breeder) {
            breeder.setAgeLock(true);
            breeder.setBreed(false);
        }
        if (e instanceof Chicken chicken) {
            chicken.setEggLayTime(Integer.MAX_VALUE); // it lays nothing
        }
        if (e instanceof Sheep sheep) {
            sheep.setColor(DyeColor.WHITE);
        }
        if (e instanceof Rabbit rabbit) {
            rabbit.setRabbitType(Rabbit.Type.BROWN);
        }
        if (e instanceof Fox fox) {
            fox.setFoxType(Fox.Type.RED);
        }
    }

    /** A racer's flag: its number on its colour, riding on it (or, if it can't ride, moved with it). */
    private void flag(World w, Racer r, Entity mob) {
        int n = r.lane();
        int[] rgb = r.rgb();
        TextDisplay flag = w.spawn(mob.getLocation().add(0, flagUp(r), 0), TextDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformation(new Transformation(new Vector3f(0f, 0.04f, 0f), new Quaternionf(),
                    new Vector3f(0.32f, 0.32f, 0.32f), new Quaternionf()));
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(235, rgb[0], rgb[1], rgb[2]));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTeleportDuration(1);
            e.text(Text.of("&f&l " + r.number() + " "));
        });
        flags[n] = flag.getUniqueId();
        boolean on;
        try {
            on = mob.addPassenger(flag);
        } catch (RuntimeException e) {
            on = false;
        }
        rides[n] = on;
    }

    private void removeRacer(int n) {
        for (UUID id : new UUID[]{flags[n], mobs[n]}) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        flags[n] = null;
        mobs[n] = null;
        rides[n] = false;
        placed[n] = null;
    }

    private void removeRacers() {
        vanished = 0;
        for (int n = 0; n < Racer.COUNT; n++) {
            if (mobs[n] != null || flags[n] != null) {
                removeRacer(n);
            }
        }
    }

    /** Everything comes down (the room closed, moved or stopped, or the track is gone). */
    void remove() {
        removeRacers();
        for (UUID id : drawn) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        drawn.clear();
        frame = null;
        label = null;
        board = null;
        labelText = null;
        boardText = null;
    }

    /** Whether the track is up. */
    boolean up() {
        return frame != null;
    }

    /** For the status: where the track is, or why there is none. */
    String statusLine() {
        if (frame != null) {
            double[] box = frame.footprint();
            return "Mob Races' track: up, on the table at " + (int) box[0] + " " + frame.blockY() + " " + (int) box[1]
                    + " to " + ((int) Math.ceil(box[2]) - 1) + " " + frame.blockY() + " " + ((int) Math.ceil(box[3]) - 1);
        }
        return "Mob Races' track: none - " + (why == null ? "not drawn yet (nobody is near it)" : why);
    }
}
