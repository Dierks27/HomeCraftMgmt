package com.dierks.homecraft.games.kidrace;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.RaceScript;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.gui.Menus;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade Room's Mob Races (the owner: "we need to take the mob race and plop it into the arcade! ... make it for
 * my daughter as she thought that was amazing!", "I mean a copy of it", "Free, prize only if she wins", "A second
 * track in the Arcade Room"): the Game Parlor's six tiny mobs on a second model track, made for a four-year-old who
 * can't read and plays with a controller.
 *
 * <p><b>Free, and nothing like a game of chance.</b> Its own game ({@code kid_races}, a skill game's kind, never behind
 * {@code hcm.games.chance}), with no stake, no round, no Parlor limit and no money: it never touches the rounds of the
 * games of chance. A tap on the Arcade Room's table opens a screen of the six racers as big pictures (a number, a colour
 * and the mob); one tap on a racer enters. The first entry starts a short wait ({@code gather_seconds}) for anyone else
 * in the room; a big green picture starts it now (when everyone in it asked). The race is run on the Arcade Room's
 * track, with the Parlor's show ({@code RaceShow}).
 *
 * <p><b>The winner</b> is drawn from the race's card ({@link RaceCard}) with exactly its chances, as in the Parlor, and
 * every racer pays the same: if your racer wins, fireworks, a happy fanfare and {@code prize} tokens through the skill
 * games' rewards ({@link SkillRewards}: the game's {@code daily_cap} and the server's skill cap, once per race), so it
 * can't be farmed. If it doesn't, a friendly sound and a cheer for the winner: nothing is taken and nothing sad is said.
 * When the track is free again, each entrant still in the room gets the kid screen: one big green Play again picture
 * and All done.
 *
 * <p><b>NPC players</b> ({@link #enterNpc}) may join a race a real player is in, picking a racer each: they change
 * nothing about the race and are paid nothing. A race with only NPCs in it never runs.
 *
 * <p>The track itself is drawn by the Clubhouse, which reads this through {@link #phase()}, {@link #script()} and
 * {@link #raceTick()}, says once a second that the track is there ({@link #trackSeen()}) and who is in the Arcade Room
 * ({@link #guests}).
 */
public final class KidRaces implements Game {

    static final String NAME = "Mob Races";

    public static final GameSpec<KidRacesSettings> SPEC = new GameSpec<>(KidRacesSettings.ID, GameKind.TRIAL,
            KidRacesSettings.KEYS, KidRacesSettings.defaults(), KidRacesSettings::parse, KidRaces::new, null);

    /** How long the result stands before the track is free again: long enough for the last racer to come home. */
    public static final long RESULT_MS = 6_000;
    /** The track counts as there while the Clubhouse said so this recently. */
    static final long TRACK_MS = 5_000;
    /** The most NPC entries in one race. */
    public static final int MAX_NPCS = 2;
    /** The Games screen's tab its tile is on: 0.59.0, Little ones (it was on Together); never Luck. */
    public static final Tab TAB = Tab.LITTLE;

    static final Refusal NOT_HERE = Refusal.of("This Mob Races is run on the track in the Clubhouse's Arcade Room.");
    static final Refusal NO_TRACK = Refusal.of("Mob Races has no track in the Arcade Room yet.");

    /** One entry: a real player's, or an NPC's. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final Racer pick;
        private final boolean npc;
        private boolean startNow;

        private Entry(UUID id, String name, Racer pick, boolean npc) {
            this.id = id;
            this.name = name;
            this.pick = pick;
            this.npc = npc;
            this.startNow = npc; // an NPC never holds the start up
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Racer pick() {
            return pick;
        }

        public boolean npc() {
            return npc;
        }

        public boolean startNow() {
            return startNow;
        }
    }

    private final GameContext ctx;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    /** Who is in the Arcade Room now (the Clubhouse's word, once a second, and a tap's). */
    private final Set<UUID> here = new HashSet<>();
    private RaceCard card;
    /** A test's own card for every race, or {@code null} (a random one each race, as in play). */
    private RaceCard testCard;
    private Phase phase = Phase.IDLE;
    private long gatherUntil;
    private int shownSeconds = -1;
    private RaceScript script;
    private int laps = 1;
    private long startedAt;
    private long resultUntil;
    private Racer lastWinner;
    private long trackSeenAt = Long.MIN_VALUE / 2;
    /** A test's own seed for the next race, or {@code null} (a random one, as in play). */
    private Long nextSeed;

    public KidRaces(GameContext ctx) {
        this.ctx = ctx;
    }

    public GamesService games() {
        return ctx.games();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    /** The live settings (read on every use). */
    public KidRacesSettings settings() {
        return games().settings(SPEC);
    }

    /** For tests: the next race is drawn from {@code seed}. */
    public void seedNextRace(long seed) {
        this.nextSeed = seed;
    }

    // ---- the Game -----------------------------------------------------------------------------------------

    @Override
    public String id() {
        return SPEC.id();
    }

    @Override
    public GameKind kind() {
        return SPEC.kind();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_KID_RACES;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    /** Never today's featured game: its only prize is its own. */
    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> rules() {
        return List.of("Six tiny mobs race round the track in the Arcade Room.",
                "Tap a racer to pick it. It's free!",
                "If your racer wins, you get " + settings().prize() + " tokens.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        lore.add("&7Tap the track in the Arcade Room.");
        return Menus.icon(Material.SADDLE, "&dMob Races &7- free", lore.toArray(new String[0]));
    }

    /** Its tile is shown only where it can be played: to a player standing in the Arcade Room. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        if (viewer == null || !here.contains(viewer.getUniqueId())) {
            return List.of();
        }
        return List.of(new GameTile(TAB, tile(viewer), id(), 0));
    }

    /** The table's tap, or {@code /hcm play kid_races}: the racers' screen, in the Arcade Room, while no race is on. */
    @Override
    public void open(Player player, Runnable back) {
        Refusal why = whyNotHere(player);
        if (why != null) {
            games().tell(player, why);
            return;
        }
        if (phase == Phase.RUNNING || phase == Phase.RESULT) {
            cheerOn(player); // the race is on: watch the track (a sound, nothing to read)
            return;
        }
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.kidrace.KidRacesMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void start() {
        reset();
        card = null;
        if (ctx.plugin() != null) {
            games().every(this, 1, 1, this::tick);
        }
    }

    @Override
    public void stop() {
        reset();
        card = null;
        changed();
    }

    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        screens.remove(id);
        here.remove(id);
        if (phase == Phase.GATHERING && entries.remove(id) != null) {
            if (!anyReal()) {
                reset(); // a race of NPCs alone never runs
            }
            changed();
        }
    }

    @Override
    public List<String> statusLines() {
        return List.of((trackUp() ? "track: up" : "track: none (see /hcm games clubhouse status)") + ", "
                + phase.name().toLowerCase(java.util.Locale.ROOT) + ", " + entries.size() + " in");
    }

    // ---- what the track and the screen read ---------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry entry(UUID id) {
        return entries.get(id);
    }

    /** Seconds until the race starts, or -1 when no entries are being taken. */
    public int secondsLeft() {
        return phase != Phase.GATHERING ? -1 : (int) Math.max(0, (gatherUntil - now() + 999) / 1000);
    }

    /** The race being run or just finished, or {@code null}. */
    public RaceScript script() {
        return phase == Phase.RUNNING || phase == Phase.RESULT ? script : null;
    }

    /** How many ticks into the race it is (0 before it starts). */
    public int raceTick() {
        return script() == null ? 0 : (int) Math.max(0, (now() - startedAt) / 50);
    }

    public int laps() {
        return laps;
    }

    /** The winner, once it is home ({@code null} before). */
    public Racer winner() {
        return phase == Phase.RESULT && script != null ? Racer.of(script.winner()) : null;
    }

    /** Who won the last race here, or {@code null} before the first. */
    public Racer lastWinner() {
        return lastWinner;
    }

    /** The Clubhouse's word, once a second, that the track is drawn and can be tapped. */
    public void trackSeen() {
        trackSeenAt = now();
    }

    public boolean trackUp() {
        return now() - trackSeenAt <= TRACK_MS;
    }

    /** The Clubhouse's word, once a second: who is in the Arcade Room now. */
    public void guests(Collection<UUID> now) {
        here.clear();
        if (now != null) {
            here.addAll(now);
        }
    }

    /** {@code player} is in the Arcade Room now (a tap on its table: in, for this tap). */
    public void guest(UUID player) {
        if (player != null) {
            here.add(player);
        }
    }

    /** Whether {@code player} is in the Arcade Room. */
    public boolean inRoom(UUID player) {
        return player != null && here.contains(player);
    }

    public void watch(UUID id, Runnable refresh) {
        if (refresh != null) {
            screens.put(id, refresh);
        }
    }

    public void unwatch(UUID id) {
        screens.remove(id);
    }

    /** This race's card: made the first time it is asked for after a race was run, fixed until one is run on it. */
    public RaceCard card() {
        if (card == null) {
            card = testCard != null ? testCard : RaceCard.of(ThreadLocalRandom.current().nextLong());
        }
        return card;
    }

    /** For tests: every race from now on is run on {@code card} (nothing in play calls this). */
    public void useCard(RaceCard card) {
        this.testCard = card;
        if (phase == Phase.IDLE && entries.isEmpty()) {
            this.card = null;
        }
    }

    /**
     * A racer's stars for a screen (pure): 1 for the weakest on this card to 5 for the strongest, by its chance today
     * (the same chances the race is drawn with).
     */
    public static int stars(RaceCard card, Racer racer) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (Racer r : Racer.values()) {
            low = Math.min(low, card.parts(r));
            high = Math.max(high, card.parts(r));
        }
        if (high <= low) {
            return 3;
        }
        return 1 + (int) Math.round(4.0 * (card.parts(racer) - low) / (high - low));
    }

    /** A racer's line on the Arcade Room's board while entries are taken (pure): its stars today, 1 to 5. */
    public static String boardLine(Racer r, int stars) {
        int n = Math.max(0, Math.min(5, stars));
        return r.label() + " &e" + "\u2605".repeat(n) + "&8" + "\u2606".repeat(5 - n);
    }

    // ---- entering ------------------------------------------------------------------------------------------

    private Refusal whyNotHere(Player player) {
        if (!inRoom(player.getUniqueId())) {
            return NOT_HERE;
        }
        return trackUp() ? null : NO_TRACK;
    }

    /**
     * Enter the race on {@code pick}: free, nothing is put in. The first entry starts the wait for the others.
     *
     * @return false when it can't be entered now (a race is on, they're in it already, or there is no track here)
     */
    public boolean enter(Player player, Racer pick) {
        UUID id = player.getUniqueId();
        if (pick == null || phase == Phase.RUNNING || phase == Phase.RESULT || entries.containsKey(id)
                || !games().enabled(this)) {
            return false;
        }
        Refusal why = whyNotHere(player);
        if (why != null) {
            games().tell(player, why);
            return false;
        }
        entries.put(id, new Entry(id, player.getName(), pick, false));
        card(); // the race's card is the one shown
        if (phase == Phase.IDLE) {
            phase = Phase.GATHERING;
            gatherUntil = now() + settings().gatherSeconds() * 1000L;
            shownSeconds = -1;
        }
        changed();
        return true;
    }

    /**
     * An NPC picks a racer in the race gathering now: only while a real player is in it, once per NPC, at most {@link
     * #MAX_NPCS}. It is paid nothing, told nothing, and changes nothing about the race.
     */
    public boolean enterNpc(UUID id, String label, Racer pick) {
        if (phase != Phase.GATHERING || !anyReal() || pick == null || id == null || entries.containsKey(id)
                || npcCount() >= MAX_NPCS) {
            return false;
        }
        entries.put(id, new Entry(id, label, pick, true));
        changed();
        return true;
    }

    /** Whether a real player is in the race. */
    public boolean anyReal() {
        for (Entry e : entries.values()) {
            if (!e.npc) {
                return true;
            }
        }
        return false;
    }

    public int npcCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            n += e.npc ? 1 : 0;
        }
        return n;
    }

    /** The big green picture: {@code player} asks to start now; when everyone in it has, the race starts. */
    public void startNow(Player player) {
        Entry e = entries.get(player.getUniqueId());
        if (e == null || phase != Phase.GATHERING || e.startNow) {
            return;
        }
        e.startNow = true;
        if (everyoneAsked()) {
            startRace();
        } else {
            changed();
        }
    }

    private boolean everyoneAsked() {
        if (entries.isEmpty()) {
            return false;
        }
        for (Entry e : entries.values()) {
            if (!e.startNow) {
                return false;
            }
        }
        return true;
    }

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Every tick while the game is open: the wait, the race, the result. */
    public void tick() {
        long now = now();
        switch (phase) {
            case GATHERING -> {
                dropLeavers();
                if (!anyReal()) {
                    reset(); // nobody real is left: no race (NPCs alone never race)
                    changed();
                } else if (now >= gatherUntil || everyoneAsked()) {
                    startRace();
                } else if (secondsLeft() != shownSeconds) {
                    shownSeconds = secondsLeft();
                    changed();
                }
            }
            case RUNNING -> {
                if (raceTick() >= script.ticks()) {
                    phase = Phase.RESULT;
                    resultUntil = now + RESULT_MS;
                    lastWinner = Racer.of(script.winner());
                    finish();
                    changed();
                }
            }
            case RESULT -> {
                if (now >= resultUntil) {
                    Map<UUID, Boolean> again = new LinkedHashMap<>();
                    for (Entry e : entries.values()) {
                        if (!e.npc) {
                            again.put(e.id, e.pick == lastWinner);
                        }
                    }
                    Racer won = lastWinner;
                    reset();
                    card = null; // the race on that card has been run: the next race gets its own
                    changed();
                    endScreens(again, won);
                }
            }
            default -> {
            }
        }
    }

    /** Entrants gone before the start: off the server, or out of the Arcade Room. Nothing was put in: nothing back. */
    private void dropLeavers() {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (!e.npc && (games().online(e.id) == null || !inRoom(e.id))) {
                entries.remove(e.id);
                screens.remove(e.id);
                changed();
            }
        }
    }

    /** Entries are closed: the race is drawn from one seed with the card's chances, before a mob moves. */
    private void startRace() {
        if (!anyReal()) {
            reset();
            changed();
            return;
        }
        long seed = nextSeed != null ? nextSeed : ThreadLocalRandom.current().nextLong();
        nextSeed = null;
        KidRacesSettings s = settings();
        script = RaceScript.of(seed, s.raceTicks(), card().parts());
        laps = s.laps();
        startedAt = now();
        phase = Phase.RUNNING;
        changed();
    }

    /**
     * The winner is home: every real entrant whose racer won gets the prize (once for this race, under the caps), with
     * fireworks and a fanfare; everyone else a friendly sound and a cheer for the winner. Nothing is ever taken.
     */
    private void finish() {
        Racer won = Racer.of(script.winner());
        KidRacesSettings s = settings();
        for (Entry e : entries.values()) {
            if (e.npc) {
                continue;
            }
            Player p = games().online(e.id);
            if (p == null) {
                continue;
            }
            if (e.pick == won) {
                if (s.prize() > 0) {
                    games().rewards().pay(p, this, source(), RewardKind.RACE_WIN, SkillRewards.raceRef(startedAt),
                            s.prize(), s.dailyCap(), NAME + ": " + won.mobName() + " won the race");
                }
                p.sendMessage(com.dierks.homecraft.util.Text.of("&a" + NAME + ": " + won.label()
                        + " &awon, and it was yours! Hooray!"));
                celebrate(p);
            } else {
                p.sendMessage(com.dierks.homecraft.util.Text.of("&a" + NAME + ": " + won.label()
                        + " &awon the race! Hooray for " + won.mobName() + "!"));
                cheer(p);
            }
        }
    }

    /** The kid screen for everyone who was in it and is still in the room: Play again, or All done. */
    private void endScreens(Map<UUID, Boolean> who, Racer won) {
        if (ctx.plugin() == null || won == null) {
            return;
        }
        for (Map.Entry<UUID, Boolean> e : who.entrySet()) {
            Player p = games().online(e.getKey());
            if (p != null && inRoom(e.getKey())) {
                new com.dierks.homecraft.gui.games.kidrace.KidRacesMenu(ctx.plugin(), this, p, null)
                        .ended(won, e.getValue()).open(p);
            }
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        entries.clear();
        script = null;
        shownSeconds = -1;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // one screen's trouble is never the race's
            }
        }
    }

    // ---- sounds and sparkles (a live server only) -----------------------------------------------------------

    /** Their racer won: fireworks over them and a happy fanfare. */
    private void celebrate(Player p) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            Location at = p.getLocation().add(0, 1.2, 0);
            p.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.1f);
            p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f);
            p.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.2f);
            p.getWorld().spawnParticle(Particle.FIREWORK, at.clone().add(0, 1.0, 0), 60, 0.6, 0.6, 0.6, 0.12);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.5, 0.8, 0.5, 0.35);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the race
        }
    }

    /** Another racer won: a friendly sound and a little cheer for it. Never a sad one. */
    private void cheer(Player p) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            Location at = p.getLocation().add(0, 1.2, 0);
            p.playSound(at, Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f);
            p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
            p.spawnParticle(Particle.HAPPY_VILLAGER, at, 12, 0.5, 0.6, 0.5, 0.0);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the race
        }
    }

    /** A tap while the race is on: a little chime (watch the track). */
    private void cheerOn(Player p) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f);
        } catch (RuntimeException | LinkageError ignored) {
            // nothing to read, nothing lost
        }
    }
}
