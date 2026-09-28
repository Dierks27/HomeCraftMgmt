package com.dierks.homecraft.games.cabinet.match;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.CabinetSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.cabinet.match.MiniMatchMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mini Match (spec §10b, R1.22, R2.13): the memory game with the server's own Minis as the faces.
 *
 * <p>Sixteen cards, eight pairs, face down; turn two at a time. A pair stays up, a miss turns back
 * after about a second. The score is flips — one per go of two cards — and lower is better: a
 * player who never forgets a card needs 8 to 15, so the milestones (30 / 24 / 20) and the daily
 * goal (24) reward paying attention rather than luck.
 *
 * <p><b>Faces.</b> Eight catalog Minis with a texture, as bare heads built for this game only
 * ({@link Heads#textured}): never the Mini item itself, so they carry no Mini id and nothing that
 * acts on real Minis ever sees them. Bedrock players (no textured heads), or a catalog with fewer
 * than eight textured Minis, get eight plain items instead. Every face-down card is the one
 * shared {@link #faceDown()} item, so nothing about a hidden card reaches the client.
 *
 * <p>The {@link MatchEngine} holds the board; the screens only draw it and pass taps along.
 */
public final class MiniMatch extends CabinetGame {

    /** Built and working. */
    static final boolean IMPLEMENTED = true;

    public static final GameSpec<MiniMatchSettings> SPEC = new GameSpec<>("mini_match", GameKind.CABINET,
            MiniMatchSettings.KEYS, MiniMatchSettings.defaults(), MiniMatchSettings::parse,
            MiniMatch::new, null);

    /** Today's board: finish it in this many flips or fewer on the scored try. */
    public static final int DAILY_GOAL = 24;
    /** Scores are flips: fewer is better. */
    public static final boolean LOWER_IS_BETTER = true;
    /** The feed's unit for the {@code classic} board. */
    static final String UNIT = "flips";

    /** Plain faces for Bedrock or a catalog short of textured Minis: eight colours easy to tell apart. */
    private static final Material[] PLAIN = {Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
            Material.LAPIS_LAZULI, Material.AMETHYST_SHARD, Material.APPLE, Material.COOKIE, Material.SNOWBALL};
    private static final String[] PLAIN_NAMES = {"&bDiamond", "&aEmerald", "&6Gold", "&9Lapis", "&dAmethyst",
            "&cApple", "&6Cookie", "&fSnowball"};

    /** The one face-down card (spec R2.13: one identical item, no PDC). */
    private ItemStack faceDown;

    public MiniMatch(GameContext ctx) {
        super(ctx);
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
        return "Mini Match";
    }

    @Override
    public TokenService.Source source() {
        return TokenService.Source.GAMES_MATCH;
    }

    @Override
    public boolean configEnabled() {
        return settings().enabled() && IMPLEMENTED;
    }

    @Override
    public List<String> rules() {
        return List.of("Turn two cards each go. Find all 8 pairs.",
                "Each go is one flip. Fewer flips is better.",
                "Daily: finish today's board in " + DAILY_GOAL + " flips or fewer.");
    }

    @Override
    public ItemStack tile(Player viewer) {
        Long best = best(viewer);
        String fact = best == null ? "find the pairs" : "best " + best + " flips";
        return Menus.icon(Material.PAINTING, "&b" + name() + " &7- " + fact,
                rules().stream().map(line -> "&7" + line).toArray(String[]::new));
    }

    @Override
    public void open(Player player, Runnable back) {
        new MiniMatchMenu(ctx.plugin(), this, player, back).open(player);
    }

    @Override
    public void feed(FeedWriter out) {
        GamesDao.ScoreRow record = games().scores().record(id(), Scores.CLASSIC, LOWER_IS_BETTER);
        out.cabinet(id(), name(), Scores.CLASSIC, UNIT, LOWER_IS_BETTER, record == null ? null : record.score(),
                record != null && out.showNames() ? Bukkit.getOfflinePlayer(record.player()).getName() : null);
    }

    @Override
    protected CabinetSettings cabinetSettings() {
        return settings();
    }

    /** The live settings (read on every use, never cached across a reload). */
    public MiniMatchSettings settings() {
        return games().settings(SPEC);
    }

    // ---- for the screens ----------------------------------------------------------------------

    /** A fresh seed for a Classic board. */
    public long classicSeed() {
        return ThreadLocalRandom.current().nextLong();
    }

    /** The player's best Classic score, or {@code null}. */
    public Long best(Player player) {
        return games().scores().best(player.getUniqueId(), id(), Scores.CLASSIC);
    }

    /** Whether the player has had today's scored try (their next daily is practice). */
    public boolean triedToday(Player player) {
        try {
            return games().dao().dailyAttempt(player.getUniqueId(), id(), today());
        } catch (SQLException e) {
            return false;
        }
    }

    /** Open a high-score board: {@code classic}, or today's daily board. */
    public void scores(Player player, String board, Runnable back) {
        openScores(player, board, LOWER_IS_BETTER, back);
    }

    /** Today's daily board name. */
    public String todayBoard() {
        return Scores.daily(today());
    }

    /** The one face-down card every hidden card shows. */
    public ItemStack faceDown() {
        if (faceDown == null) {
            faceDown = Menus.icon(Material.LIGHT_BLUE_CONCRETE, "&bTap to turn", "&7Find its pair.");
        }
        return faceDown;
    }

    /**
     * The eight faces for one game (pair id = index), built now and used for the whole game: Mini
     * heads picked by {@code seed} from the textured catalog Minis, or the plain set.
     */
    public List<ItemStack> faces(Player viewer, long seed) {
        List<MiniDef> textured = Bedrock.is(viewer) ? List.of() : texturedMinis();
        int[] pick = MatchEngine.pick(textured.size(), MatchEngine.PAIRS, seed);
        List<ItemStack> out = new ArrayList<>(MatchEngine.PAIRS);
        for (int i = 0; i < MatchEngine.PAIRS; i++) {
            if (pick.length == MatchEngine.PAIRS) {
                MiniDef def = textured.get(pick[i]);
                out.add(Heads.textured(def.texture(), "&d" + def.name(), List.of()));
            } else {
                out.add(Menus.icon(PLAIN[i], PLAIN_NAMES[i]));
            }
        }
        return out;
    }

    /** Catalog Minis that are heads with a texture, in catalog order. */
    private List<MiniDef> texturedMinis() {
        MiniService minis = ctx.plugin().miniService();
        if (minis == null) {
            return List.of();
        }
        List<MiniDef> out = new ArrayList<>();
        for (MiniDef def : minis.catalog()) {
            if (def.type() == MiniType.HEAD && def.texture() != null && !def.texture().isBlank()) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * A board is finished: record it (Classic, or the scored daily try), pay what it earned
     * (milestones or the daily challenge, through the capped rewards) and tell the player.
     *
     * @param daily the daily start, or {@code null} for Classic
     */
    public Finish finish(Player player, DailyStart daily, int flips) {
        boolean goal = flips <= DAILY_GOAL;
        Finish f = daily == null
                ? finishClassic(player, Scores.CLASSIC, flips, LOWER_IS_BETTER)
                : finishDaily(player, daily, flips, LOWER_IS_BETTER, goal);
        String score = flips + " flip" + (flips == 1 ? "" : "s");
        if (f.practice()) {
            player.sendMessage(Text.of("&7Practice board done in " + score + ". Practice isn't recorded."));
        } else if (daily != null) {
            player.sendMessage(Text.of((goal
                    ? "&a✔ Daily challenge done in " + score + "!"
                    : "&7Daily challenge: " + score + ". The goal was " + DAILY_GOAL + " or fewer.")
                    + dayStanding(f.result())));
        } else {
            player.sendMessage(Text.of("&aAll pairs found in " + score + "!" + standing(f.result())));
        }
        if (daily == null ? f.result().personalBest() : !f.practice() && goal) {
            Sounds.won(player);
        }
        return f;
    }

    /** A Classic score's standing: " New best! (was 16)" and " ★ Server record!", or nothing. */
    static String standing(ScoreResult r) {
        if (r == null || !r.personalBest()) {
            return "";
        }
        String out = " &eNew best!" + (r.previous() != null ? " &7(was " + r.previous() + ")" : "");
        return r.record() ? out + " &6★ Server record!" : out;
    }

    /** A scored daily try's place on today's board: " ★ Top score today!" or " #3 today.". */
    static String dayStanding(ScoreResult r) {
        if (r == null || r.rank() <= 0) {
            return "";
        }
        return r.record() ? " &6★ Top score today!" : " &7#" + r.rank() + " today.";
    }
}
