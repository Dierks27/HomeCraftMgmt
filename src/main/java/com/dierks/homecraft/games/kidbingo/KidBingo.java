package com.dierks.homecraft.games.kidbingo;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.NpcCrowd;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade Room's Picture Bingo (0.57.0): Bingo Night made for a four-year-old who can't read and plays with a
 * controller, as the Arcade Room's Mob Races is the Parlor's. Free, with a small prize only when your card wins.
 *
 * <p><b>Pictures, never numbers.</b> A card is three by three: eight animals ({@link Picture}) round a free star
 * ({@link PictureCard}). A big caller shows one animal at a time, big on the screen, and plays its sound; a square
 * with that animal lights up (glows) by itself. Three in a row is BINGO: fireworks, a fanfare and {@code prize} tokens
 * through the skill games' rewards ({@code daily_cap} and the server's skill cap, once a round), so it can't be farmed.
 * Nothing is ever put in, and nothing sad is said to anyone whose card didn't win: a cheer and a friendly sound.
 *
 * <p><b>Played together.</b> Tapping the cabinet (or {@code /hcm play kid_bingo}) deals you a card in the round
 * gathering now, or starts one ({@code gather_seconds}); anyone who taps in the meantime, the owner and Lilah say, is
 * in the same round, and a big green Go starts it as soon as everyone in it has tapped it. Someone who taps while a
 * round is being called watches it and gets the Play again picture with the others when it ends. The round ends on
 * the first picture that gives any card three in a row: every card that has it then wins.
 *
 * <p><b>NPC players</b> may take a card in a round a real player is in (when every real player in it has NPCs on):
 * never more than {@value #MAX_NPCS}, paid nothing, and always at the easy level, which here means a sleepy one: an
 * easy NPC only catches about half of the animals called on its card (a square it misses stays dark), so a child's
 * card, which never misses one, usually gets there first. A round with only NPCs in it never runs.
 *
 * <p>The whole round (the order of the animals and which ones each NPC catches) is drawn when the calling starts, as a
 * round of the Parlor's is, and only shown after.
 */
public final class KidBingo implements Game {

    static final String NAME = "Picture Bingo";

    public static final GameSpec<KidBingoSettings> SPEC = new GameSpec<>(KidBingoSettings.ID, GameKind.TRIAL,
            KidBingoSettings.KEYS, KidBingoSettings.defaults(), KidBingoSettings::parse, KidBingo::new, null);

    /** How long the win stands before the Play again picture. */
    public static final long RESULT_MS = 5_000;
    /** The most NPCs in one round. */
    public static final int MAX_NPCS = 2;
    /** The level every NPC plays Picture Bingo at. */
    public static final CpuLevel NPC_LEVEL = CpuLevel.EASY;
    /** The Games screen's tab its tile is on: 0.59.0, Little ones (it was on Together, among the grown-ups' games). */
    public static final Tab TAB = Tab.LITTLE;

    /** Where the round is. */
    public enum Phase {
        IDLE, GATHERING, CALLING, RESULT
    }

    /** One card in the round: a real player's, or an NPC's. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final boolean npc;
        private final PictureCard card;
        private final Set<Picture> caught = new LinkedHashSet<>();
        private boolean startNow;
        private boolean won;

        private Entry(UUID id, String name, boolean npc, PictureCard card) {
            this.id = id;
            this.name = name;
            this.npc = npc;
            this.card = card;
            this.startNow = npc; // an NPC never holds the start up
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean npc() {
            return npc;
        }

        public PictureCard card() {
            return card;
        }

        /** The animals its card has caught so far. */
        public Set<Picture> caught() {
            return Set.copyOf(caught);
        }

        public boolean startNow() {
            return startNow;
        }

        /** Whether its card got three in a row (once the round is won). */
        public boolean won() {
            return won;
        }
    }

    private final GameContext ctx;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /** Players who tapped while a round was being called: they watch it, and play the next. */
    private final Set<UUID> watchers = new LinkedHashSet<>();
    private final Map<UUID, Runnable> screens = new LinkedHashMap<>();
    private Phase phase = Phase.IDLE;
    private long gatherUntil;
    private int shownSeconds = -1;
    private long lastSecond;
    private Picture[] calls = new Picture[0];
    /** Which calls each NPC catches, drawn with the round. */
    private final Map<UUID, boolean[]> catches = new LinkedHashMap<>();
    private int winCall = -1;
    private int called;
    private long startedAt;
    private long resultUntil;
    /** A test's own seed for the next round, or {@code null} (a random one, as in play). */
    private Long nextSeed;

    public KidBingo(GameContext ctx) {
        this.ctx = ctx;
    }

    public GamesService games() {
        return ctx.games();
    }

    private long now() {
        return games().clock().nowMillis();
    }

    public KidBingoSettings settings() {
        return games().settings(SPEC);
    }

    /** For tests: the next round is drawn from {@code seed}. */
    public void seedNextRound(long seed) {
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
        return TokenService.Source.GAMES_KID_BINGO;
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
    public List<String> aliases() {
        return List.of("picture_bingo");
    }

    @Override
    public List<String> rules() {
        return List.of("A card of animal pictures. It's free!",
                "Each animal called lights up on your card.",
                "Three in a row wins " + settings().prize() + " tokens.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        List<String> lore = new ArrayList<>();
        for (String line : rules()) {
            lore.add("&7" + line);
        }
        lore.add("&7Tap its cabinet in the Arcade Room.");
        return Menus.icon(Material.PIG_SPAWN_EGG, "&dPicture Bingo &7- free",
                lore.toArray(new String[0]));
    }

    /** Its tile is on the Little ones tab, shown where the Arcade Room's own games are: to someone in the room. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        if (viewer == null || !inArcadeRoom(viewer.getUniqueId())) {
            return List.of();
        }
        return List.of(new GameTile(TAB, tile(viewer), id(), 0));
    }

    /** Whether {@code id} is in the Arcade Room (the Clubhouse tells the kid race once a second; no kid race, anywhere). */
    private boolean inArcadeRoom(UUID id) {
        Game race = games().game("kid_races");
        return !(race instanceof com.dierks.homecraft.games.kidrace.KidRaces k) || k.inRoom(id);
    }

    /** The cabinet's tap or {@code /hcm play kid_bingo}: a card in the round gathering now, and the kid screen. */
    @Override
    public void open(Player player, Runnable back) {
        join(player);
        if (ctx.plugin() == null) {
            return; // the framework's own tests: no screens
        }
        new com.dierks.homecraft.gui.games.kidbingo.KidBingoMenu(ctx.plugin(), this, player, back).open(player);
    }

    /** A cabinet in the Arcade Room was tapped: straight in, no first screen. */
    @Override
    public boolean playClassic(Player player, Runnable back) {
        open(player, back);
        return true;
    }

    @Override
    public void start() {
        reset();
        if (ctx.plugin() != null) {
            games().every(this, 1, 1, this::tick);
        }
    }

    @Override
    public void stop() {
        reset();
        changed();
    }

    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        screens.remove(id);
        watchers.remove(id);
        if (phase == Phase.GATHERING && entries.remove(id) != null) {
            if (!anyReal()) {
                reset();
            }
            changed();
        }
    }

    @Override
    public List<String> statusLines() {
        return List.of(phase.name().toLowerCase(java.util.Locale.ROOT) + ", " + entries.size() + " cards, "
                + watchers.size() + " watching");
    }

    // ---- what the screen reads ----------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry entry(UUID id) {
        return entries.get(id);
    }

    /** Whether {@code id} is watching the round being called (and plays the next). */
    public boolean watching(UUID id) {
        return watchers.contains(id);
    }

    /** Seconds until the calling starts, or -1 when no round is gathering. */
    public int secondsLeft() {
        return phase != Phase.GATHERING ? -1 : (int) Math.max(0, (gatherUntil - now() + 999) / 1000);
    }

    /** The animals called so far, in order. */
    public List<Picture> calledSoFar() {
        List<Picture> out = new ArrayList<>();
        for (int i = 0; i < called && i < calls.length; i++) {
            out.add(calls[i]);
        }
        return out;
    }

    /** The animal just called, or {@code null}. */
    public Picture current() {
        return called > 0 && called <= calls.length ? calls[called - 1] : null;
    }

    public void watch(UUID id, Runnable refresh) {
        if (refresh != null) {
            screens.put(id, refresh);
        }
    }

    public void unwatch(UUID id) {
        screens.remove(id);
    }

    // ---- joining ------------------------------------------------------------------------------------------

    /**
     * {@code player} taps in: a card in the round gathering now (the first starts it), or, while one is being called,
     * a seat to watch it. Nothing is put in.
     *
     * @return whether they have a card in the round now
     */
    public boolean join(Player player) {
        UUID id = player.getUniqueId();
        if (!games().enabled(this)) {
            return false;
        }
        if (entries.containsKey(id)) {
            return true;
        }
        if (phase == Phase.CALLING || phase == Phase.RESULT) {
            watchers.add(id);
            return false;
        }
        entries.put(id, new Entry(id, player.getName(), false, PictureCard.deal(ThreadLocalRandom.current()
                .nextLong())));
        watchers.remove(id);
        if (phase == Phase.IDLE) {
            phase = Phase.GATHERING;
            gatherUntil = now() + settings().gatherSeconds() * 1000L;
            shownSeconds = -1;
        }
        changed();
        return true;
    }

    /**
     * An NPC takes a card in the round gathering now: only while a real player is in it, once per NPC, at most
     * {@link #MAX_NPCS}. It is paid nothing and told nothing.
     */
    public boolean enterNpc(UUID id, String label) {
        if (phase != Phase.GATHERING || !anyReal() || id == null || entries.containsKey(id) || npcCount() >= MAX_NPCS) {
            return false;
        }
        entries.put(id, new Entry(id, label, true, PictureCard.deal(ThreadLocalRandom.current().nextLong())));
        changed();
        return true;
    }

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

    /** The big green Go: {@code player} asks to start now; when everyone in it has, the calling starts. */
    public void startNow(Player player) {
        Entry e = entries.get(player.getUniqueId());
        if (e == null || phase != Phase.GATHERING || e.startNow) {
            return;
        }
        e.startNow = true;
        if (everyoneAsked()) {
            startRound();
        } else {
            changed();
        }
    }

    private boolean everyoneAsked() {
        for (Entry e : entries.values()) {
            if (!e.startNow) {
                return false;
            }
        }
        return !entries.isEmpty();
    }

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Every tick while the game is open: the wait, the pictures, the win. */
    public void tick() {
        long now = now();
        switch (phase) {
            case GATHERING -> {
                dropLeavers();
                if (!anyReal()) {
                    reset();
                    changed();
                } else if (now >= gatherUntil || everyoneAsked()) {
                    startRound();
                } else {
                    if (now - lastSecond >= 1000) {
                        lastSecond = now;
                        npcSecond();
                    }
                    if (secondsLeft() != shownSeconds) {
                        shownSeconds = secondsLeft();
                        changed();
                    }
                }
            }
            case CALLING -> {
                long due = Math.min(calls.length, (now - startedAt) / settings().callMs() + 1);
                boolean moved = false;
                while (called < due && called <= winCall) {
                    call(called);
                    called++;
                    moved = true;
                }
                if (called > winCall) {
                    phase = Phase.RESULT;
                    resultUntil = now + RESULT_MS;
                    finish();
                    moved = true;
                }
                if (moved) {
                    changed();
                }
            }
            case RESULT -> {
                if (now >= resultUntil) {
                    Map<UUID, Boolean> again = new LinkedHashMap<>();
                    for (Entry e : entries.values()) {
                        if (!e.npc) {
                            again.put(e.id, e.won);
                        }
                    }
                    for (UUID w : watchers) {
                        again.putIfAbsent(w, false);
                    }
                    reset();
                    changed();
                    endScreens(again);
                }
            }
            default -> {
            }
        }
    }

    private void dropLeavers() {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (!e.npc && games().online(e.id) == null) {
                entries.remove(e.id);
                screens.remove(e.id);
                changed();
            }
        }
    }

    /** Now and then, while a round gathers with real players who all have NPCs on, a friendly NPC takes a card. */
    private void npcSecond() {
        CpuPlayers cpu = CpuPlayers.of(games());
        if (cpu == null || secondsLeft() < 3 || npcCount() >= MAX_NPCS || cpu.rng().nextInt(100) >= 25) {
            return;
        }
        List<UUID> reals = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.npc) {
                reals.add(e.id);
            }
        }
        if (!NpcCrowd.welcome(reals, cpu::wants)) {
            return;
        }
        List<CpuPlayer> free = NpcCrowd.pick(cpu.roster(), entries.keySet(), 1, cpu.rng());
        if (!free.isEmpty()) {
            CpuPlayer c = free.get(0);
            enterNpc(c.id(), CpuRoster.label(cpu.name(c), NPC_LEVEL));
        }
    }

    /** How often an NPC at {@code level} catches an animal on its card: an easy one half the time. */
    public static int catchPercent(CpuLevel level) {
        return switch (level) {
            case EASY -> 50;
            case MEDIUM -> 75;
            case HARD -> 100;
        };
    }

    /** The calling starts: the order of the animals and which each NPC catches are drawn now, and where it ends. */
    private void startRound() {
        dropLeavers();
        if (!anyReal()) {
            reset();
            changed();
            return;
        }
        long seed = nextSeed != null ? nextSeed : ThreadLocalRandom.current().nextLong();
        nextSeed = null;
        SplittableRandom r = new SplittableRandom(seed);
        calls = order(r);
        catches.clear();
        for (Entry e : entries.values()) {
            if (e.npc) {
                boolean[] c = new boolean[calls.length];
                for (int i = 0; i < c.length; i++) {
                    c[i] = r.nextInt(100) < catchPercent(NPC_LEVEL);
                }
                catches.put(e.id, c);
            }
        }
        winCall = winCall(entries.values(), calls, catches);
        called = 0;
        startedAt = now();
        phase = Phase.CALLING;
        changed();
    }

    /** All sixteen animals in a random order. */
    static Picture[] order(SplittableRandom r) {
        Picture[] out = Picture.values().clone();
        for (int i = out.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            Picture t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }

    /**
     * The first call (from 0) on which any card has three in a row (pure). A player's card catches every animal; an
     * NPC's only those its catches say. A player's card is full once every animal is called, so a round with a player
     * in it always ends.
     */
    static int winCall(java.util.Collection<Entry> cards, Picture[] calls, Map<UUID, boolean[]> catches) {
        Map<UUID, Set<Picture>> caught = new LinkedHashMap<>();
        for (Entry e : cards) {
            caught.put(e.id, new HashSet<>());
        }
        for (int i = 0; i < calls.length; i++) {
            for (Entry e : cards) {
                boolean[] c = catches.get(e.id);
                if (!e.npc || (c != null && c[i])) {
                    caught.get(e.id).add(calls[i]);
                }
                if (e.card.won(caught.get(e.id))) {
                    return i;
                }
            }
        }
        return calls.length - 1;
    }

    /** Picture {@code i} is called: every card that has it (and catches it) lights it; everyone hears the animal. */
    private void call(int i) {
        Picture p = calls[i];
        for (Entry e : entries.values()) {
            boolean[] c = catches.get(e.id);
            if (e.card.has(p) && (!e.npc || (c != null && c[i]))) {
                e.caught.add(p);
            }
        }
        for (UUID id : listeners()) {
            Player pl = games().online(id);
            if (pl != null) {
                Entry e = entries.get(id);
                pl.sendActionBar(Text.of("&e" + p.label()));
                animal(pl, p, e != null && e.card.has(p));
            }
        }
    }

    /** Every real player in the round and everyone watching it. */
    private List<UUID> listeners() {
        List<UUID> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.npc) {
                out.add(e.id);
            }
        }
        out.addAll(watchers);
        return out;
    }

    /** Three in a row: each card that has it now wins (a player's: the prize, fireworks); everyone else cheers. */
    private void finish() {
        KidBingoSettings s = settings();
        for (Entry e : entries.values()) {
            e.won = e.card.won(e.caught);
        }
        for (UUID id : listeners()) {
            Player p = games().online(id);
            if (p == null) {
                continue;
            }
            Entry e = entries.get(id);
            if (e != null && e.won) {
                if (s.prize() > 0) {
                    games().rewards().pay(p, this, source(), RewardKind.RACE_WIN, ref(startedAt), s.prize(),
                            s.dailyCap(), NAME + ": three in a row");
                }
                p.sendMessage(Text.of("&a" + NAME + ": &6BINGO! &aThree in a row! Hooray!"));
                celebrate(p);
            } else {
                p.sendMessage(Text.of("&a" + NAME + ": " + winnerNames() + " &agot three in a row! Hooray!"));
                cheer(p);
            }
        }
    }

    /**
     * The round's prize ref: once a round, {@code bingo:<start>} (no two rounds start together). The prize is the kid
     * race's kind of reward ({@link RewardKind#RACE_WIN}: a free kid round won, capped), kept under this game's id.
     */
    static String ref(long startedAt) {
        return "bingo:" + startedAt;
    }

    private String winnerNames() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.won) {
                out.add(e.npc ? e.name : "&f" + e.name);
            }
        }
        return String.join("&a, ", out);
    }

    /** The kid screen for everyone who played or watched and still has its screen open: Play again, or All done. */
    private void endScreens(Map<UUID, Boolean> who) {
        if (ctx.plugin() == null) {
            return;
        }
        for (Map.Entry<UUID, Boolean> e : who.entrySet()) {
            Player p = games().online(e.getKey());
            if (p != null && com.dierks.homecraft.gui.games.GameMenu.gameOnScreen(p) == this) {
                new com.dierks.homecraft.gui.games.kidbingo.KidBingoMenu(ctx.plugin(), this, p, null)
                        .ended(e.getValue()).open(p);
            }
        }
    }

    /** The card a just-ended screen shows: kept until the next round deals new ones. */
    private final Map<UUID, Entry> lastCards = new LinkedHashMap<>();

    /** {@code id}'s card in the round that just ended, or {@code null}. */
    public Entry lastCard(UUID id) {
        return lastCards.get(id);
    }

    private void reset() {
        if (phase == Phase.RESULT) {
            lastCards.clear();
            lastCards.putAll(entries);
        }
        phase = Phase.IDLE;
        entries.clear();
        watchers.clear();
        catches.clear();
        calls = new Picture[0];
        winCall = -1;
        called = 0;
        shownSeconds = -1;
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(screens.values())) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // one screen's trouble is never the round's
            }
        }
    }

    // ---- sounds and sparkles (a live server only) -----------------------------------------------------------

    /** An animal is called: its own sound, and a chime when it is on your card. */
    private void animal(Player p, Picture pic, boolean mine) {
        if (ctx.plugin() == null) {
            return;
        }
        try {
            p.playSound(p.getLocation(), "minecraft:" + pic.sound(), 1.0f, 1.0f);
            if (mine) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the round
        }
    }

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
            // a sound never stops the round
        }
    }

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
            // a sound never stops the round
        }
    }
}
