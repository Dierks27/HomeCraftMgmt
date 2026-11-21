package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.gui.Menus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.random.RandomGenerator;

/**
 * The NPC players (stage 1 of the owner's "CPU players ... in the games it would make it a bit more fun for me"): the
 * shared foundation every game asks. A place, not a game to play: it has no tile and moves no tokens. It lives in the
 * catalog only for what that gives it: its settings ({@code games.cpu}, backfilled like every game's), its switch
 * (nothing happens while {@code games.enabled} or {@code games.cpu.enabled} is false), its once-a-second task and its
 * own failure isolation (a bug in an NPC switches the NPCs off, never a game).
 *
 * <p><b>Nothing real, ever.</b> An NPC has no account, no balance and no row anywhere: it never earns or spends tokens
 * or money, never gets a Card, a quest, an achievement or today's pick, is never on a board, a trophy, the Weekly Cup
 * or the secret room, and is never counted as a player online (it is never a {@code Player}). Its stakes at the table
 * and in a race are pretend and kept in memory, outside {@code ChanceRounds}, so a real player's round, odds, limits
 * and crash safety are exactly what they were.
 *
 * <p><b>Each player's setting</b> ({@link CpuChoice}): NPCs on or off for them, and how they play in a game they are in
 * (easy, matched or any). Kept in {@code game_prefs}; a parent or admin may set it for them and lock it, and then they
 * can't change it ({@link CpuChoice.Prefs}). If any real player in a game wants easy, every NPC in it plays easy
 * ({@link CpuLevels}).
 *
 * <p><b>The owner's looks</b>: a name and a skin for each NPC ({@code /hcm games npc name|skin}, or the admin screen),
 * kept in {@code hcm_meta} under {@code gen.npc.}. The gear and the stars are always added to the name.
 */
public final class CpuPlayers implements Game, TableNpcs {

    public static final GameSpec<CpuSettings> SPEC = new GameSpec<>("cpu", GameKind.TRIAL, CpuSettings.KEYS,
            CpuSettings.defaults(), CpuSettings::parse, CpuPlayers::new, null);

    /** The player's own setting ({@link CpuChoice#text}). */
    public static final String PREF_OWN = "npc.own";
    /** The setting a parent or admin locked for them. */
    public static final String PREF_LOCK = "npc.lock";
    /** Their matched level in a game against NPCs: {@code npc.matched.<game>} = 1-3 stars. */
    public static final String PREF_MATCHED = "npc.matched.";
    /** Where the owner's names and skins are kept: {@code gen.npc.<key>.name} and {@code .skin}. */
    static final String META = "gen.npc.";
    /** The games where a player's matched level is their record against NPCs. */
    static final Set<String> MATCHED_GAMES = Set.of("connect_four", "tic_tac_toe");

    private final GameContext ctx;
    private final Map<UUID, CpuChoice.Prefs> prefs = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, NpcSkin> skins = new ConcurrentHashMap<>();
    private final SplittableRandom rng = new SplittableRandom();
    private final CpuAdmin admin = new CpuAdmin(this);

    public CpuPlayers(GameContext ctx) {
        this.ctx = ctx;
    }

    /** The NPC players while they are on (the games and {@code games.cpu}), else {@code null}. */
    public static CpuPlayers of(GamesService games) {
        if (games == null) {
            return null;
        }
        return games.game(SPEC.id()) instanceof CpuPlayers c && games.enabled(c) ? c : null;
    }

    /** The NPC players whether or not they are on (an admin may set things up while they are off), or {@code null}. */
    public static CpuPlayers any(GamesService games) {
        return games != null && games.game(SPEC.id()) instanceof CpuPlayers c ? c : null;
    }

    public GamesService games() {
        return ctx.games();
    }

    public CpuSettings settings() {
        return games().settings(SPEC);
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
        return "NPC players";
    }

    /** Never used: NPCs move no tokens. */
    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_RACE_NIGHT;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled();
    }

    @Override
    public boolean featurable() {
        return false;
    }

    @Override
    public List<String> aliases() {
        return List.of("npc", "npcs");
    }

    @Override
    public List<String> rules() {
        return List.of("NPC players join your games when you want them.",
                "Their name always has a gear after it.",
                "They play pretend: no tokens, no scores.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        return Menus.icon(Material.ARMOR_STAND, "&bNPC players &7" + CpuRoster.MARKER);
    }

    /** No tile on the Games screen: the setting has its own tile there. */
    @Override
    public List<GameTile> tiles(Player viewer) {
        return List.of();
    }

    /** {@code /hcm play npc}: the player's own setting. */
    @Override
    public void open(Player player, Runnable back) {
        if (ctx.plugin() != null) {
            new com.dierks.homecraft.gui.games.NpcMenu(ctx.plugin(), player, back).open(player);
        }
    }

    @Override
    public void start() {
        loadLooks();
        if (ctx.plugin() != null) {
            games().every(this, 20, 20, this::second);
        }
    }

    @Override
    public void stop() {
        prefs.clear();
    }

    @Override
    public void onQuit(Player player) {
        prefs.remove(player.getUniqueId());
    }

    @Override
    public GameAdmin admin() {
        return admin;
    }

    @Override
    public List<String> statusLines() {
        CpuSettings s = settings();
        return List.of(CpuRoster.ALL.size() + " NPCs, up to " + s.regulars() + " in the Clubhouse; one joins a player"
                + " alone at a table after " + s.joinAfterSeconds() + " s");
    }

    // ---- once a second ------------------------------------------------------------------------------------

    /** The Twenty-One table's NPCs, and NPC entries in a race that is gathering. */
    public void second() {
        GamesService g = games();
        if (g.game(TwentyOne.SPEC.id()) instanceof TwentyOne t && g.enabled(t)) {
            t.table().cpuTick(this);
        }
        if (g.game(MobRaces.SPEC.id()) instanceof MobRaces m && g.enabled(m)) {
            raceEntries(m);
        }
    }

    /**
     * While a race gathers with real entrants who all have NPCs on, now and then an NPC enters with a pretend stake: a
     * hunch on its favourite racer, anyone else on one at random.
     */
    void raceEntries(MobRaces races) {
        if (races.phase() != MobRaces.Phase.GATHERING || races.secondsLeft() < 4 || !races.anyReal()
                || races.npcCount() >= MobRaces.MAX_NPCS || rng.nextInt(100) >= 20) {
            return;
        }
        List<UUID> in = new ArrayList<>();
        for (MobRaces.Entry e : races.entries()) {
            if (!e.npc() && !wants(e.id())) {
                return; // someone in the race has NPCs off
            }
            in.add(e.id());
        }
        List<CpuPlayer> free = new ArrayList<>();
        for (CpuPlayer c : CpuRoster.ALL) {
            if (!in.contains(c.id())) {
                free.add(c);
            }
        }
        if (free.isEmpty()) {
            return;
        }
        CpuPlayer c = free.get(rng.nextInt(free.size()));
        Racer pick = c.personality() == CpuPersonality.HUNCH ? Racer.of(c.favourite())
                : Racer.values()[rng.nextInt(Racer.COUNT)];
        races.enterNpc(c.id(), label(c), pick, CpuTwentyOne.stake(races.settings().open(), c.personality()));
    }

    // ---- TableNpcs ----------------------------------------------------------------------------------------

    @Override
    public boolean wants(UUID real) {
        return real != null && !CpuRoster.isCpu(real) && choice(real).on();
    }

    @Override
    public CpuLevel level(CpuPlayer npc, Collection<UUID> reals, String gameId) {
        List<CpuChoice.Pref> wanted = new ArrayList<>();
        CpuLevel matched = null;
        for (UUID r : reals) {
            CpuChoice.Pref p = choice(r).level();
            wanted.add(p);
            if (p == CpuChoice.Pref.MATCHED && MATCHED_GAMES.contains(gameId)) {
                CpuLevel m = matched(r, gameId);
                matched = matched == null || m.ordinal() < matched.ordinal() ? m : matched;
            }
        }
        return CpuLevels.forGame(npc.level(), wanted, matched);
    }

    @Override
    public String label(CpuPlayer npc) {
        return CpuRoster.label(name(npc), npc.level());
    }

    @Override
    public List<CpuPlayer> roster() {
        return CpuRoster.ALL;
    }

    @Override
    public long joinAfterMs() {
        return settings().joinAfterSeconds() * 1000L;
    }

    @Override
    public boolean audience() {
        return games().anyInParlor();
    }

    @Override
    public RandomGenerator rng() {
        return rng;
    }

    // ---- each player's setting ----------------------------------------------------------------------------

    /** The player's own setting and any locked one (read once, then kept until they leave or it changes). */
    public CpuChoice.Prefs prefs(UUID player) {
        if (player == null) {
            return CpuChoice.Prefs.NONE;
        }
        return prefs.computeIfAbsent(player, id -> {
            try {
                return new CpuChoice.Prefs(CpuChoice.parse(games().dao().pref(id, PREF_OWN)),
                        CpuChoice.parse(games().dao().pref(id, PREF_LOCK)));
            } catch (SQLException e) {
                games().logger().log(Level.WARNING, "Could not read a player's NPC setting", e);
                return CpuChoice.Prefs.NONE;
            }
        });
    }

    /** What applies to the player now. */
    public CpuChoice choice(UUID player) {
        return prefs(player).effective();
    }

    /**
     * The player sets their own. Refused while a parent or admin has it locked.
     *
     * @return false when it is locked or could not be kept
     */
    public boolean setOwn(UUID player, CpuChoice wanted) {
        CpuChoice.Prefs next = prefs(player).withOwn(wanted);
        if (next == null) {
            return false;
        }
        try {
            games().dao().setPref(player, PREF_OWN, wanted.text());
        } catch (SQLException e) {
            games().logger().log(Level.WARNING, "Could not keep a player's NPC setting", e);
            return false;
        }
        prefs.put(player, next);
        return true;
    }

    /**
     * A parent or admin sets one for the player and locks it ({@code null} lifts the lock; the player's own comes
     * back).
     *
     * @return false when it could not be kept
     */
    public boolean setLock(UUID player, CpuChoice locked) {
        try {
            games().dao().setPref(player, PREF_LOCK, locked == null ? null : locked.text());
        } catch (SQLException e) {
            games().logger().log(Level.WARNING, "Could not keep a player's locked NPC setting", e);
            return false;
        }
        prefs.remove(player);
        return true;
    }

    /**
     * A parent or admin sets the player's own setting for them without locking it (they can change it later). A lock
     * is lifted with it.
     */
    public boolean setFor(UUID player, CpuChoice own) {
        try {
            games().dao().setPref(player, PREF_LOCK, null);
            games().dao().setPref(player, PREF_OWN, own.text());
        } catch (SQLException e) {
            games().logger().log(Level.WARNING, "Could not keep a player's NPC setting", e);
            return false;
        }
        prefs.remove(player);
        return true;
    }

    /** The player's matched level in {@code gameId}: their record against NPCs there (medium to start). */
    public CpuLevel matched(UUID player, String gameId) {
        try {
            String v = games().dao().pref(player, PREF_MATCHED + gameId);
            return v == null ? CpuLevel.MEDIUM : CpuLevel.ofStars(Integer.parseInt(v.trim()));
        } catch (SQLException | NumberFormatException e) {
            return CpuLevel.MEDIUM;
        }
    }

    /** A game against an NPC at {@code played} ended: the player's matched level there moves with how it went. */
    public void recordGame(UUID player, String gameId, CpuLevel played, boolean won, boolean lost) {
        if (!MATCHED_GAMES.contains(gameId)) {
            return;
        }
        CpuLevel next = CpuLevels.afterGame(played, won, lost);
        try {
            games().dao().setPref(player, PREF_MATCHED + gameId, Integer.toString(next.stars));
        } catch (SQLException e) {
            games().logger().log(Level.FINE, "Could not keep a player's matched NPC level", e);
        }
    }

    /** The level {@code npc} plays at in a game of {@code gameId} against {@code player} alone. */
    public CpuLevel levelAgainst(CpuPlayer npc, UUID player, String gameId) {
        return level(npc, List.of(player), gameId);
    }

    // ---- the owner's looks --------------------------------------------------------------------------------

    /** The NPC's name: the owner's for it, or its own. Never the gear or the stars: {@link #label} adds them. */
    public String name(CpuPlayer npc) {
        return names.getOrDefault(npc.key(), npc.name());
    }

    /** What the NPC wears: the owner's choice, or its own built-in skin. */
    public NpcSkin skin(CpuPlayer npc) {
        return skins.getOrDefault(npc.key(), npc.skin());
    }

    /** How many times the looks changed (the Clubhouse dresses its NPCs again when this moves on). */
    public int looksVersion() {
        return looksVersion;
    }

    private volatile int looksVersion;

    /**
     * Rename an NPC ({@code null} gives it its own name back).
     *
     * @return the name it now has, or {@code null} when nothing fit to show was typed
     */
    public String rename(CpuPlayer npc, String typed) {
        String clean = typed == null ? npc.name() : CpuRoster.cleanName(typed);
        if (clean == null) {
            return null;
        }
        if (clean.equals(npc.name())) {
            names.remove(npc.key());
            keep(META + npc.key() + ".name", null);
        } else {
            names.put(npc.key(), clean);
            keep(META + npc.key() + ".name", clean);
        }
        looksVersion++;
        return clean;
    }

    /** Dress an NPC in {@code skin} ({@code null}: its own built-in one). */
    public void reskin(CpuPlayer npc, NpcSkin skin) {
        if (skin == null || skin.equals(npc.skin())) {
            skins.remove(npc.key());
            keep(META + npc.key() + ".skin", null);
        } else {
            skins.put(npc.key(), skin);
            keep(META + npc.key() + ".skin", skin.text());
        }
        if (profiles != null) {
            profiles.forget(); // an account's skin is fetched again: the owner may have just changed it
        }
        looksVersion++;
    }

    private NpcProfiles profiles;

    /**
     * What the NPC wears now, as a profile for a mannequin or a head: its chosen skin, or its own built-in one while an
     * account's skin is fetched or if it can't be ({@link NpcProfiles}).
     */
    public io.papermc.paper.datacomponent.item.ResolvableProfile profile(CpuPlayer npc) {
        if (profiles == null) {
            profiles = new NpcProfiles(ctx.plugin(), games().logger());
        }
        return profiles.profile(npc, skin(npc), () -> looksVersion++);
    }

    private void keep(String key, String value) {
        Parlor.Store store = games().metaStore();
        if (store == null) {
            return;
        }
        try {
            store.set(key, value == null ? "" : value);
        } catch (SQLException | RuntimeException e) {
            games().logger().log(Level.SEVERE, "Could not keep an NPC's " + key
                    + " - it holds until the next restart only", e);
        }
    }

    /** Read the owner's names and skins (a value that can't be read is left out: the NPC's own is used). */
    void loadLooks() {
        names.clear();
        skins.clear();
        Parlor.Store store = games().metaStore();
        if (store == null) {
            return;
        }
        for (CpuPlayer c : CpuRoster.ALL) {
            try {
                String n = CpuRoster.cleanName(store.get(META + c.key() + ".name"));
                if (n != null && !n.equals(c.name())) {
                    names.put(c.key(), n);
                }
                String sk = store.get(META + c.key() + ".skin");
                if (sk != null && !sk.isBlank()) {
                    NpcSkin skin = NpcSkin.read(sk, null);
                    if (skin == null) {
                        games().logger().warning("NPC " + CpuRoster.plain(c.name()) + ": the kept skin can't"
                                + " be read - it wears its own built-in skin");
                    } else {
                        skins.put(c.key(), skin);
                    }
                }
            } catch (SQLException | RuntimeException e) {
                games().logger().log(Level.WARNING, "Could not read an NPC's name or skin", e);
            }
        }
        looksVersion++;
    }
}
