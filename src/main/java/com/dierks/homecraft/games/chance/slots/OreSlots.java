package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.chance.OreSlotsMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Ore Slots (spec §5.3, R1.4): the reference game of chance.
 *
 * <p>Three reels of ores on one payline: three the same pays that ore's line, a Wild stands in
 * for any ore, two the same pays the small line, and Stone never pays. Stone's reel weight is the
 * one thing the engine solves, per stake, so the game gives back what {@code rtp} says and no
 * more — and the number players read is the one the engine computed, never the target.
 *
 * <p>A spin is one instant round ({@code ChanceRounds.play}): the gate, a seed, the engine's
 * outcome from that seed, and the tokens in and back in one transaction — all before the reels
 * move. The screen ({@link OreSlotsMenu}) only shows what already happened. Everything a player
 * or admin reads about the odds, here and on the website, comes from the one engine in
 * {@link OreSlotsSettings#engine()}.
 */
public final class OreSlots implements Game {

    /** Built: the game follows its config. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<OreSlotsSettings> SPEC = new GameSpec<>("ore_slots", GameKind.CHANCE,
            OreSlotsSettings.KEYS, OreSlotsSettings.defaults(), OreSlotsSettings::parse,
            OreSlots::new, null);

    private final GameContext ctx;

    public OreSlots(GameContext ctx) {
        this.ctx = ctx;
    }

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
        return "Ore Slots";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.ARCADE_SLOTS;
    }

    /** Its own switch, and at least one stake the solve could fit inside the band. */
    @Override
    public boolean configEnabled() {
        OreSlotsSettings s = settings();
        return IMPLEMENTED && s.enabled() && s.engine().playable();
    }

    @Override
    public List<String> rules() {
        return List.of("Three reels of ores. Match them on the line.",
                "Three the same pays the most. Two the same pays a little.",
                "A Wild stands in for any ore. Stone never pays.",
                "Every spin is decided before the reels move.");
    }

    /** "&amp;aOre Slots &amp;7- 1-5 tokens", with the rules, the give-back line and plays left. */
    @Override
    public ItemStack tile(Player viewer) {
        OreSlotsSettings s = settings();
        SlotsEngine engine = s.engine();
        List<String> lore = new ArrayList<>();
        for (String line : rules().subList(0, 3)) {
            lore.add("&7" + line);
        }
        if (engine.playable()) {
            String line = RtpLimits.playerLine(engine.lowestRtp());
            lore.add("&7" + Character.toUpperCase(line.charAt(0)) + line.substring(1) + ".");
        }
        int left = playsLeft(viewer.getUniqueId(), s);
        if (left >= 0) {
            lore.add("&7Plays left today: &f" + left);
        }
        lore.add("&eClick to play");
        return Menus.icon(Material.DIAMOND_ORE, "&a" + name() + " &7- " + SlotsCopy.stakeRange(engine.stakes()),
                lore.toArray(new String[0]));
    }

    @Override
    public void open(Player player, Runnable back) {
        OreSlotsSettings s = settings();
        if (!s.engine().playable()) {
            player.sendMessage(Text.of("&c" + name() + " is closed right now."));
            return;
        }
        new OreSlotsMenu(ctx.plugin(), this, player, back, s).open(player);
    }

    /** One line for everyone, then the per-stake detail for admins (spec R1.21). */
    @Override
    public List<String> oddsLines() {
        OreSlotsSettings s = settings();
        List<String> out = new ArrayList<>();
        out.add(SlotsCopy.oddsLine(name(), s.engine(), s.dailyLimit()));
        out.addAll(SlotsCopy.oddsDetail(s.engine()));
        return out;
    }

    @Override
    public void feed(FeedWriter out) {
        write(out, id(), name(), settings());
    }

    /** This game's {@code /api/arcade} entry, from the engine in {@code s} (pure, for tests). */
    static void write(FeedWriter out, String id, String name, OreSlotsSettings s) {
        SlotsEngine engine = s.engine();
        if (!engine.playable()) {
            return;
        }
        out.chance(id, name, engine.stakes(), engine.rtpByStake(), s.dailyLimit(), engine.feedRows(),
                SlotsCopy.feedRules(), Map.of());
    }

    /** The live settings (read on every use, never cached across a reload). */
    public OreSlotsSettings settings() {
        return ctx.games().settings(SPEC);
    }

    /** Spins the player has left today, or -1 if that can't be read right now. */
    public int playsLeft(UUID player, OreSlotsSettings s) {
        GamesService games = ctx.games();
        try {
            int played = games.dao().playsToday(player, id(), ctx.plugin().clock().dayKey());
            return Math.max(0, s.dailyLimit() - played);
        } catch (SQLException | RuntimeException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Could not read today's Ore Slots plays", e);
            return -1;
        }
    }

    /** "Today: 35 of 100 tokens" — tokens into games of chance today against the effective limit. */
    public String todayLine(UUID player) {
        Breaks breaks = ctx.games().breaks();
        if (breaks == null) {
            return "&7Today: &f?";
        }
        int in = breaks.tokensInToday(player);
        int limit = breaks.limit(player);
        return limit == Breaks.NO_LIMIT
                ? "&eToday: &6" + in + " &etoken" + (in == 1 ? "" : "s") + " in"
                : "&eToday: &6" + in + " of " + limit + " &etokens";
    }
}
