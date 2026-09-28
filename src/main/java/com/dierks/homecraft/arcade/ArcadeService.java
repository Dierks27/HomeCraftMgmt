package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Crate;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.config.PluginConfig.RewardType;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade's games (§3.9): weighted loot crates, the pity exchange (a Rare-or-better Card),
 * the Scratch Ticket, and Card trade-in. Token balances, the streak, playtime and the ledger live
 * in {@link TokenService}; the Prize Counter in {@link PrizeService}.
 *
 * <p><b>No Arcade path takes or pays dollars.</b> Crates cost tokens and pay Cards, packs,
 * filament, tokens or Prize Counter prizes. The Scratch Ticket costs and pays tokens, with a
 * progressive pot. The dollar "better odds" tier and the dollar lotto are gone. Every Card issued
 * here passes {@link com.dierks.homecraft.mini.CardService#canIssue}, the one cap check every Card
 * path shares; a crate never mints a finished Mini.
 */
public final class ArcadeService {

    /**
     * The result of opening a crate / pity / lotto — carries a display icon for the reveal GUI.
     *
     * @param win      whether the pull actually paid something worth celebrating. A loss is still
     *                 {@code ok} (the ticket was bought and resolved), but the reveal must not
     *                 call it a win.
     * @param big      a headline result (the Card jackpot from a crate, the Scratch Ticket jackpot,
     *                 a Rare-or-better pity Card): the reveal adds a title and a firework
     * @param returned tokens handed back by a result that is NOT a win — some or all of what was
     *                 put in (spec §0.9, R1.18). Said plainly as "tokens back", never dressed up:
     *                 the reveal reads this flag, not the icon it happens to be drawn with.
     */
    public record Outcome(boolean ok, String error, ItemStack icon, String label, boolean win, boolean big,
                          int returned) {
        public Outcome(boolean ok, String error, ItemStack icon, String label, boolean win, boolean big) {
            this(ok, error, icon, label, win, big, 0);
        }

        public Outcome(boolean ok, String error, ItemStack icon, String label, boolean win) {
            this(ok, error, icon, label, win, false, 0);
        }

        /** Whether this was no win but some (or all) of the tokens put in came back. */
        public boolean someBack() {
            return !win && returned > 0;
        }

        static Outcome fail(String e) {
            return new Outcome(false, e, null, null, false, false);
        }

        static Outcome won(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label, true, false);
        }

        static Outcome big(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label, true, true);
        }

        static Outcome lost(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label, false, false);
        }

        static Outcome back(ItemStack icon, String label, int tokens) {
            return new Outcome(true, null, icon, label, false, false, Math.max(0, tokens));
        }
    }

    /** The Scratch Ticket pot, in {@code arcade_state}. */
    private static final String POT = "lotto_pot";
    /** The pity exchange's own weekly limit key in {@code prize_purchases}. */
    private static final String PITY_ID = "pity";

    private final HomeCraftManagement plugin;

    public ArcadeService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.config().arcade().enabled()) {
            return;
        }
        validateCrates();
    }

    public void stop() {
        // nothing scheduled here — the streak/playtime tick lives in TokenService
    }

    private TokenService tokens() {
        return plugin.tokens();
    }

    /** Warn (once, on load) about crate rewards that point at something missing. */
    private void validateCrates() {
        for (Crate crate : plugin.config().arcade().crates().values()) {
            for (CrateReward r : crate.rewards()) {
                if ((r.type() == RewardType.MINI || r.type() == RewardType.CARD) && !r.usesTag()
                        && plugin.miniService().def(r.miniId()) == null) {
                    plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown Mini '"
                            + r.miniId() + "' — that reward is skipped; the crate still works.");
                }
                if (r.type() == RewardType.PACK && plugin.packs() != null && plugin.packs().pack(r.packId()) == null) {
                    plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown pack '"
                            + r.packId() + "' — that reward is skipped; the crate still works.");
                }
                if (r.type() == RewardType.PRIZE) {
                    for (String id : r.prizeIds()) {
                        if (plugin.config().arcade().prize(id) == null) {
                            plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown prize '"
                                    + id + "' — skipped; the crate still works.");
                        }
                    }
                }
            }
        }
    }

    // ---- crates ---------------------------------------------------------------

    public Crate crate(String id) {
        return plugin.config().arcade().crates().get(id);
    }

    /**
     * Open a crate. Tokens are charged only once a reward is guaranteed available, so a player
     * is never charged for nothing.
     */
    public Outcome openCrate(Player player, String crateId) {
        if (!plugin.sandbox().check(player, "crate open " + crateId)) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        Crate crate = plugin.config().arcade().crates().get(crateId);
        if (crate == null) {
            return Outcome.fail("No such crate.");
        }
        String paused = chanceRefusal(plugin, player, crate.costTokens());
        if (paused != null) {
            return Outcome.fail(paused);
        }
        if (crate.rewards().isEmpty()) {
            return Outcome.fail("This crate is empty right now.");
        }
        UUID id = player.getUniqueId();
        int have = tokens().balance(id);
        if (have < crate.costTokens()) {
            return Outcome.fail("You need " + (crate.costTokens() - have) + " more tokens.");
        }
        List<CrateReward> pool = eligiblePool(crate);
        if (pool.isEmpty()) {
            return Outcome.fail("Nothing is available in this crate right now.");
        }
        if (!tokens().spend(id, crate.costTokens(), TokenService.Source.CRATE, crate.display())) {
            return Outcome.fail("You need " + crate.costTokens() + " tokens.");
        }
        Outcome outcome = grantFromPool(player, pool);
        if (outcome.ok() && plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_crate");
            plugin.achievements().increment(player, "crates", 1);
        }
        if (outcome.ok() && plugin.quests() != null) {
            plugin.quests().record(player, PluginConfig.QuestType.OPEN_CRATE, 1);
        }
        return outcome;
    }

    /** Weighted-pick and grant a reward from an already-eligible pool. */
    private Outcome grantFromPool(Player player, List<CrateReward> pool) {
        List<CrateReward> working = new ArrayList<>(pool);
        while (!working.isEmpty()) {
            CrateReward r = weightedPick(working);
            switch (r.type()) {
                case CARD, MINI -> {
                    // A crate hands out the Mini's CARD (printed into a graded Mini at a Printer),
                    // never a finished Mini — from a fixed id, a tag pool, or "*" (every Mini: the
                    // crate's jackpot).
                    MiniDef def = r.usesTag()
                            ? plugin.miniService().pickByRarity(tagPool(r.tag()))
                            : plugin.miniService().def(r.miniId());
                    if (def != null && plugin.cards().canIssue(def)) {
                        var cr = plugin.cards().issue(player, def.id());
                        if (cr.ok()) {
                            ItemStack ic = plugin.miniService().cardFor(def.id());
                            ItemStack shown = ic != null ? ic : icon(Material.PAPER, "&bCard");
                            String label = plugin.miniService().rarityText(def.rarity()) + " " + def.name() + " Card";
                            if ("*".equals(r.tag())) {
                                shout("&d✦ " + player.getName() + " &dgot a " + label + " &dfrom a crate!");
                                return Outcome.big(shown, label);
                            }
                            return Outcome.won(shown, label);
                        }
                    }
                    working.remove(r); // sold out between check and issue — drop and re-roll
                }
                case PACK -> {
                    ItemStack pack = plugin.packs() != null ? plugin.packs().packItem(r.packId()) : null;
                    if (pack == null) {
                        working.remove(r);
                        continue;
                    }
                    giveOrDrop(player, pack);
                    var def = plugin.packs().pack(r.packId());
                    return Outcome.won(pack.clone(), "&d" + (def != null ? def.displayName() : r.packId()));
                }
                case FILAMENT -> {
                    org.bukkit.DyeColor color = r.color() != null ? r.color()
                            : org.bukkit.DyeColor.values()[ThreadLocalRandom.current().nextInt(org.bukkit.DyeColor.values().length)];
                    ItemStack fil = plugin.miniService().filamentItems().filament(color, r.amount());
                    giveOrDrop(player, fil);
                    return Outcome.won(fil.clone(), "&f" + r.amount() + " " + niceName(color) + " Filament");
                }
                case TOKENS -> {
                    tokens().award(player, r.amount(), TokenService.Source.CRATE, "Crate prize");
                    return Outcome.won(icon(Material.SUNFLOWER, "&e+" + r.amount() + " tokens"),
                            "&e" + r.amount() + " tokens");
                }
                case PRIZE, TRAIL -> {
                    List<Prize> choices = rewardPrizes(r);
                    if (choices.isEmpty()) {
                        working.remove(r);
                        continue;
                    }
                    Prize prize = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
                    Outcome o = plugin.prizes().grant(player, prize, r.type() == RewardType.TRAIL ? r.days() : 0);
                    if (o.ok()) {
                        return o;
                    }
                    working.remove(r);
                }
            }
        }
        // Everything left was a Mini whose Cards are gone; a pull that resolved to nothing.
        return Outcome.lost(icon(Material.BARRIER, "&7No prize this time"), "&7nothing this time");
    }

    /**
     * The prizes a PRIZE or TRAIL reward can hand out right now. A PRIZE with none (say, only
     * hats and every hat still lacks a texture) is skipped, not paid as nothing.
     */
    private List<Prize> rewardPrizes(CrateReward r) {
        List<Prize> out = new ArrayList<>();
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (r.type() == RewardType.TRAIL && r.prizeIds().isEmpty()) {
            for (Prize p : arc.prizes()) {
                if (p.type() == PrizeType.TRAIL && plugin.prizes().grantable(p)) {
                    out.add(p);
                }
            }
            return out;
        }
        for (String id : r.prizeIds()) {
            Prize p = arc.prize(id);
            if (p != null && plugin.prizes().grantable(p)
                    && (r.type() != RewardType.TRAIL || p.type() == PrizeType.TRAIL)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Rewards that can actually pay out now. */
    private List<CrateReward> eligiblePool(Crate crate) {
        List<CrateReward> out = new ArrayList<>();
        for (CrateReward r : crate.rewards()) {
            if (droppable(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Whether one crate reward can pay out right now (also drives the published odds). */
    public boolean droppable(CrateReward r) {
        return switch (r.type()) {
            case MINI, CARD -> r.usesTag() ? !tagPool(r.tag()).isEmpty()
                    : plugin.cards().canIssue(plugin.miniService().def(r.miniId()));
            case PACK -> plugin.packs() != null && plugin.packs().pack(r.packId()) != null;
            case PRIZE, TRAIL -> !rewardPrizes(r).isEmpty();
            case FILAMENT, TOKENS -> true;
        };
    }

    /** The issuable Minis carrying a tag; {@code "*"} is every Mini. */
    public List<MiniDef> tagPool(String tag) {
        if ("*".equals(tag)) {
            return plugin.cards().issuable(plugin.miniService().catalog());
        }
        return plugin.cards().issuable(plugin.miniService().poolFromTag(tag));
    }

    /** Prizes a PRIZE/TRAIL reward can give now (for the odds screen). */
    public List<Prize> prizesFor(CrateReward r) {
        return rewardPrizes(r);
    }

    // ---- pity exchange --------------------------------------------------------

    /** Rare-or-better Cards left for this player this week (the limit is per local week). */
    public int pityLeft(UUID player) {
        int perWeek = plugin.config().arcade().pityPerWeek();
        if (perWeek <= 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Math.max(0, perWeek - plugin.prizes().dao().purchases(player, PITY_ID,
                    plugin.prizes().periodKey(LimitPer.WEEK)));
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Spend the configured tokens for a Card at or above the configured rarity. Once a week. */
    public Outcome pity(Player player) {
        if (!plugin.sandbox().check(player, "pity exchange")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        int cost = arc.pityTokens();
        if (cost <= 0) {
            return Outcome.fail("This isn't available right now.");
        }
        UUID id = player.getUniqueId();
        if (pityLeft(id) <= 0) {
            return Outcome.fail("You've had this week's " + floorWords(arc.pityRarity()) + " Card. "
                    + "There's a new one next week!");
        }
        int have = tokens().balance(id);
        if (have < cost) {
            return Outcome.fail("You need " + (cost - have) + " more tokens.");
        }
        List<MiniDef> pool = new ArrayList<>();
        for (MiniDef def : plugin.miniService().catalog()) {
            if (def.rarity().ordinal() >= arc.pityRarity().ordinal() && plugin.cards().canIssue(def)) {
                pool.add(def);
            }
        }
        String none = "No " + floorWords(arc.pityRarity()) + " Card is left right now. Nothing was charged.";
        // Weighted by rarity, like crates and wild drops: the floor guarantees Rare-or-better, it
        // was never meant to make a Legendary as likely as a Rare.
        MiniDef chosen = plugin.miniService().pickByRarity(pool);
        if (chosen == null) {
            return Outcome.fail(none);
        }
        if (!tokens().spend(id, cost, TokenService.Source.PITY, chosen.name() + " Card")) {
            return Outcome.fail("You need " + cost + " tokens.");
        }
        var cr = plugin.cards().issue(player, chosen.id());
        if (!cr.ok()) {
            tokens().grant(id, cost, TokenService.Source.REFUND, chosen.name() + " Card sold out");
            return Outcome.fail(cr.error());
        }
        try {
            plugin.prizes().dao().addPurchase(id, PITY_ID, plugin.prizes().periodKey(LimitPer.WEEK));
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not record this week's pity Card: " + e.getMessage());
        }
        ItemStack ic = plugin.miniService().cardFor(chosen.id());
        ItemStack shown = ic != null ? ic : icon(Material.PAPER, "&bCard");
        String label = plugin.miniService().rarityText(chosen.rarity()) + " " + chosen.name() + " Card";
        if (chosen.rarity().ordinal() >= Rarity.RARE.ordinal()) {
            shout("&b✦ " + player.getName() + " &bgot a " + label + "&b!");
            return Outcome.big(shown, label);
        }
        return Outcome.won(shown, label);
    }

    private static String floorWords(Rarity floor) {
        return floor == Rarity.LEGENDARY ? floor.display() : floor.display() + "-or-better";
    }

    // ---- the Scratch Ticket (tokens) -------------------------------------------

    /** The pot a jackpot pays right now. */
    public int pot() {
        PluginConfig.Jackpot j = plugin.config().arcade().lotto().jackpot();
        try {
            return (int) Math.min(j.cap(), plugin.prizes().dao().state(POT, j.seed()));
        } catch (SQLException e) {
            return j.seed();
        }
    }

    /**
     * Scratch a ticket. The result is decided and paid HERE, at purchase; the scratch screen that
     * follows is only a reveal, so closing it early can neither lose nor repeat anything.
     */
    public Outcome scratch(Player player) {
        if (!plugin.sandbox().check(player, "scratch ticket")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Lotto l = plugin.config().arcade().lotto();
        String paused = chanceRefusal(plugin, player, l.ticketTokens());
        if (paused != null) {
            return Outcome.fail(paused);
        }
        if (l.payouts().isEmpty()) {
            return Outcome.fail("The Scratch Ticket isn't set up right now.");
        }
        UUID id = player.getUniqueId();
        int have = tokens().balance(id);
        if (have < l.ticketTokens()) {
            return Outcome.fail("You need " + (l.ticketTokens() - have) + " more tokens.");
        }
        if (!tokens().spend(id, l.ticketTokens(), TokenService.Source.LOTTO, "Scratch Ticket")) {
            return Outcome.fail("You need " + l.ticketTokens() + " tokens.");
        }
        PluginConfig.Jackpot j = l.jackpot();
        int pot;
        try {
            long grown = plugin.prizes().dao().addState(POT, j.perTicket(), j.seed());
            if (grown > j.cap()) {
                plugin.prizes().dao().setState(POT, j.cap());
            }
            pot = (int) Math.min(j.cap(), grown);
        } catch (SQLException e) {
            pot = j.seed();
        }
        if (plugin.quests() != null) {
            plugin.quests().record(player, PluginConfig.QuestType.SCRATCH, 1);
        }
        PluginConfig.LottoPayout result = roll(l.payouts());
        if (result.jackpot()) {
            try {
                plugin.prizes().dao().setState(POT, j.seed());
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not reset the Scratch Ticket pot: " + e.getMessage());
            }
            tokens().grant(id, pot, TokenService.Source.LOTTO, "Scratch Ticket jackpot");
            shout("&6&l✦ JACKPOT! &e" + player.getName() + " &6won &e" + pot + " tokens &6on a Scratch Ticket!");
            if (plugin.achievements() != null) {
                plugin.achievements().tryAward(player, "jackpot");
                plugin.achievements().increment(player, "jackpots", 1);
            }
            return Outcome.big(icon(Material.GOLD_BLOCK, "&6&lJACKPOT! &e" + pot + " tokens"),
                    "&6the jackpot: " + pot + " tokens");
        }
        int won = result.tokens();
        if (won > 0) {
            tokens().grant(id, won, TokenService.Source.LOTTO, "Scratch Ticket");
        }
        // Only more than the ticket is a win. Getting some or all of it back is said as exactly
        // that — never dressed up as a near win, never the win sound (spec §2, R1.18).
        if (won > l.ticketTokens()) {
            return Outcome.won(icon(Material.SUNFLOWER, "&e+" + won + " tokens"), "&e" + won + " tokens");
        }
        if (won > 0) {
            return Outcome.back(icon(Material.IRON_NUGGET, "&7Tokens back: &f" + won + " &7of &f" + l.ticketTokens()),
                    "&7You got " + backText(won, l.ticketTokens()), won);
        }
        return Outcome.lost(icon(Material.GRAY_DYE, "&7No win this time"), "&7no win");
    }

    /** "3 of your 10 tokens back", or "your 10 tokens back" when it was all of them. */
    static String backText(int back, int in) {
        return (back >= in ? "&7your &f" + back : "&f" + back + " &7of your &f" + in) + " tokens &7back";
    }

    /**
     * Take a break's say before a game of chance takes tokens (spec §4.2, R1.11): the player's own
     * and a parent's pause and daily limit, the {@code hcm.games.chance} permission, and the
     * server's daily limit while the games are on. Crates, the Scratch Ticket and token Card Packs
     * all ask this right after their world check. It fails closed: no service, or a service that
     * throws, means no.
     *
     * @return why not, as a plain line, or {@code null} to go ahead
     */
    public static String chanceRefusal(HomeCraftManagement plugin, Player player, int cost) {
        com.dierks.homecraft.games.Breaks breaks = plugin.breaks();
        String closed = com.dierks.homecraft.games.Refusal.CHANCE_CLOSED.message();
        if (breaks == null) {
            return closed;
        }
        try {
            com.dierks.homecraft.games.Refusal r = breaks.chanceAllowed(player, cost);
            if (r == null) {
                return null;
            }
            return r.message().isEmpty() ? closed : r.message();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Take a break could not check "
                    + player.getName() + " - games of chance refuse until it can", e);
            return closed;
        }
    }

    private static PluginConfig.LottoPayout roll(List<PluginConfig.LottoPayout> payouts) {
        double total = 0;
        for (PluginConfig.LottoPayout p : payouts) {
            total += p.weight();
        }
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (PluginConfig.LottoPayout p : payouts) {
            r -= p.weight();
            if (r <= 0) {
                return p;
            }
        }
        return payouts.get(payouts.size() - 1);
    }

    /**
     * The Scratch Ticket's return to player: expected tokens back per token spent, counting the
     * jackpot at its steady state — the seed plus what the pot grows by in the average number of
     * tickets between jackpots ({@code seed + per_ticket / p}), capped.
     */
    public static double rtp(PluginConfig.Lotto l) {
        double total = 0;
        for (PluginConfig.LottoPayout p : l.payouts()) {
            total += p.weight();
        }
        if (total <= 0 || l.ticketTokens() <= 0) {
            return 0;
        }
        double jackpotP = 0;
        double ev = 0;
        for (PluginConfig.LottoPayout p : l.payouts()) {
            double prob = p.weight() / total;
            if (p.jackpot()) {
                jackpotP += prob;
            } else {
                ev += prob * p.tokens();
            }
        }
        if (jackpotP > 0) {
            ev += jackpotP * steadyStatePot(l.jackpot(), jackpotP);
        }
        return ev / l.ticketTokens();
    }

    /** The pot's expected size when it is won: {@code seed + per_ticket / p}, never past the cap. */
    public static double steadyStatePot(PluginConfig.Jackpot j, double jackpotP) {
        if (jackpotP <= 0) {
            return j.cap();
        }
        return Math.min(j.cap(), j.seed() + j.perTicket() / jackpotP);
    }

    // ---- Card trade-in ---------------------------------------------------------

    /** Tokens one Card is worth at the trade-in counter (0 = not a Card, or not accepted). */
    public int tradeInValue(ItemStack item) {
        String cardId = plugin.miniService().cardItems().cardIdOf(item);
        MiniDef def = cardId == null ? null : plugin.miniService().def(cardId);
        return def == null ? 0 : plugin.config().arcade().tradeInValue(def.rarity());
    }

    /**
     * Trade Cards in for tokens: a small, one-way money-to-tokens trickle (a Card may have been
     * bought with dollars). Never the other way. The caller removes the items only when this
     * returns a positive amount.
     */
    public int tradeIn(Player player, List<ItemStack> cards) {
        // grant() skips the world sandbox (it also pays refunds), so ask here: a creative world
        // must not turn copied Cards into tokens.
        if (!plugin.sandbox().check(player, "card trade-in")) {
            return 0;
        }
        int total = 0;
        int count = 0;
        for (ItemStack it : cards) {
            int each = tradeInValue(it);
            if (each > 0) {
                total += each * it.getAmount();
                count += it.getAmount();
            }
        }
        if (total <= 0) {
            return 0;
        }
        if (tokens().grant(player.getUniqueId(), total, TokenService.Source.TRADE_IN,
                count + (count == 1 ? " Card" : " Cards")) < 0) {
            return 0;
        }
        return total;
    }

    // ---- odds report -----------------------------------------------------------

    /** Lines for {@code /hcm arcade odds}: the Scratch Ticket's RTP and each crate's value. */
    public List<String> oddsReport() {
        List<String> out = new ArrayList<>();
        PluginConfig.Arcade arc = plugin.config().arcade();
        PluginConfig.Lotto l = arc.lotto();
        double total = l.payouts().stream().mapToDouble(PluginConfig.LottoPayout::weight).sum();
        double jp = l.payouts().stream().filter(PluginConfig.LottoPayout::jackpot)
                .mapToDouble(PluginConfig.LottoPayout::weight).sum() / Math.max(1e-9, total);
        out.add("&6Scratch Ticket &7(" + l.ticketTokens() + " tokens): RTP &f"
                + String.format(Locale.ROOT, "%.1f%%", rtp(l) * 100) + " &7with the jackpot at its steady state of &f"
                + String.format(Locale.ROOT, "%.0f", steadyStatePot(l.jackpot(), jp)) + " &7(pot now &f" + pot()
                + "&7, 1 in " + (jp > 0 ? String.format(Locale.ROOT, "%.0f", 1 / jp) : "never") + ")");
        for (Crate c : arc.crates().values()) {
            double w = 0;
            double value = 0;
            for (CrateReward r : c.rewards()) {
                if (!droppable(r)) {
                    continue;
                }
                w += r.weight();
                value += r.weight() * counterValue(r);
            }
            double ev = w > 0 ? value / w : 0;
            out.add("&6" + Text.plain(c.display()) + " &7(" + c.costTokens() + " tokens): counter-value &f"
                    + String.format(Locale.ROOT, "%.1f", ev) + " &7tokens a pull = &f"
                    + String.format(Locale.ROOT, "%.0f%%", c.costTokens() > 0 ? ev / c.costTokens() * 100 : 0)
                    + " &8(Cards at trade-in value)");
        }
        return out;
    }

    /** What one reward would cost (or fetch) at the counter, in tokens. */
    private double counterValue(CrateReward r) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        return switch (r.type()) {
            case TOKENS -> r.amount();
            case PRIZE, TRAIL -> {
                List<Prize> ps = rewardPrizes(r);
                double sum = 0;
                for (Prize p : ps) {
                    double cost = p.costTokens();
                    if (p.type() == PrizeType.TRAIL && p.days() > 0 && r.type() == RewardType.TRAIL) {
                        cost = cost * r.days() / p.days();
                    }
                    sum += cost;
                }
                yield ps.isEmpty() ? 0 : sum / ps.size();
            }
            case FILAMENT -> {
                double per = 0;
                for (Prize p : arc.prizes()) {
                    if (p.type() == PrizeType.FILAMENT && p.amount() > 0) {
                        per = (double) p.costTokens() / p.amount();
                        break;
                    }
                }
                yield per * r.amount();
            }
            case CARD, MINI -> {
                List<MiniDef> pool = r.usesTag() ? tagPool(r.tag())
                        : java.util.Collections.singletonList(plugin.miniService().def(r.miniId()));
                double wsum = 0;
                double vsum = 0;
                for (MiniDef d : pool) {
                    if (d == null) {
                        continue;
                    }
                    double w = plugin.miniService().loot().rarityWeight(d.rarity());
                    wsum += w;
                    vsum += w * arc.tradeInValue(d.rarity());
                }
                yield wsum > 0 ? vsum / wsum : 0;
            }
            case PACK -> 0;
        };
    }

    // ---- helpers --------------------------------------------------------------

    private static CrateReward weightedPick(List<CrateReward> pool) {
        double total = 0;
        for (CrateReward r : pool) {
            total += r.weight();
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (CrateReward r : pool) {
            roll -= r.weight();
            if (roll <= 0) {
                return r;
            }
        }
        return pool.get(pool.size() - 1);
    }

    /** A server-wide line for a big win. */
    private static void shout(String legacy) {
        Bukkit.broadcast(Text.of(legacy));
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private ItemStack icon(Material material, String name) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private String niceName(org.bukkit.DyeColor color) {
        String n = color.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
