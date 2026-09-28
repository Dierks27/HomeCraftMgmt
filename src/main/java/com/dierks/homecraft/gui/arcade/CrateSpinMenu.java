package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The crate spin: a strip of what the crate can give scrolls along the middle row, slowing down
 * over about three seconds with a click per step, and stops on the prize under the two hoppers.
 *
 * <p>The prize was chosen and handed over before the spin started; this is the show. Closing it
 * early just prints the result. Java gets {@code arcade.reveal.frames} steps, Bedrock
 * {@code bedrock_frames} (rapid inventory updates stutter through Geyser), both over
 * {@code spin_ticks}.
 */
public final class CrateSpinMenu extends Menu {

    private static final int STRIP_START = 9;
    private static final int STRIP_LEN = 9;
    private static final int WIN = 13;
    private static final int[] POINTERS = {4, 22};

    private final Player player;
    private final String crateId;
    private final ArcadeService.Outcome outcome;
    private final Runnable back;
    private final List<ItemStack> strip;
    private final long[] delays;
    private int step;
    private boolean landed;
    private boolean told;
    private BukkitTask task;

    public CrateSpinMenu(HomeCraftManagement plugin, Player player, String crateId,
                         ArcadeService.Outcome outcome, Runnable back) {
        super(plugin);
        this.player = player;
        this.crateId = crateId;
        this.outcome = outcome;
        this.back = back;
        PluginConfig.Reveal reveal = plugin.config().reveal();
        int frames = Bedrock.is(player) ? reveal.bedrockFrames() : reveal.frames();
        this.delays = delays(reveal.spinTicks(), frames);
        this.strip = buildStrip(frames);
        init(27, Text.of("&5Opening…"));
    }

    /**
     * Tick gaps that grow as the strip slows: step i waits in proportion to 1 + 3(i/n)^2, scaled
     * so the whole spin takes about {@code totalTicks}. Every gap is at least one tick.
     */
    static long[] delays(int totalTicks, int frames) {
        int n = Math.max(1, frames);
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double x = (double) i / n;
            w[i] = 1 + 3 * x * x;
            sum += w[i];
        }
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.max(1, Math.round(totalTicks * w[i] / sum));
        }
        return out;
    }

    /** Random candidates, weighted like the crate, with the real prize where the strip stops. */
    private List<ItemStack> buildStrip(int frames) {
        List<ItemStack> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        PluginConfig.Crate crate = plugin.arcade().crate(crateId);
        if (crate != null) {
            for (CrateReward r : crate.rewards()) {
                ItemStack ic = candidate(r);
                if (ic != null && plugin.arcade().droppable(r)) {
                    pool.add(ic);
                    weights.add(r.weight());
                }
            }
        }
        if (pool.isEmpty()) {
            pool.add(Menus.icon(Material.CHEST, "&7?"));
            weights.add(1.0);
        }
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        List<ItemStack> out = new ArrayList<>();
        int length = frames + STRIP_LEN;
        for (int i = 0; i < length; i++) {
            double r = ThreadLocalRandom.current().nextDouble() * total;
            int pick = 0;
            for (int j = 0; j < weights.size(); j++) {
                r -= weights.get(j);
                if (r <= 0) {
                    pick = j;
                    break;
                }
            }
            out.add(pool.get(pick));
        }
        // After `frames` steps the window shows strip[frames .. frames+8]; slot 13 is its fifth.
        out.set(frames + (WIN - STRIP_START), prizeIcon());
        return out;
    }

    private ItemStack prizeIcon() {
        return outcome.icon() != null ? outcome.icon() : Menus.icon(Material.CHEST, "&7Prize");
    }

    /** A plain picture of one reward, for the strip. */
    private ItemStack candidate(CrateReward r) {
        return switch (r.type()) {
            case PRIZE, TRAIL -> {
                var ps = plugin.arcade().prizesFor(r);
                if (ps.isEmpty()) {
                    yield null;
                }
                var p = ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
                yield Menus.icon(p.icon().material(), p.display());
            }
            case CARD, MINI -> "*".equals(r.tag())
                    ? Menus.glint(Menus.icon(Material.NETHER_STAR, "&d&lAny Mini's Card!"), true)
                    : Menus.icon(Material.PLAYER_HEAD, "&bA Card");
            case PACK -> Menus.icon(Material.PAPER, "&dA Card Pack");
            case FILAMENT -> Menus.icon(Material.STRING, "&fFilament");
            case TOKENS -> Menus.icon(Material.SUNFLOWER, "&e+" + r.amount() + " tokens");
        };
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int p : POINTERS) {
            set(p, Menus.icon(Material.HOPPER, "&e▼"), null);
        }
        paintStrip();
        if (landed) {
            // One way on, the same for a prize or a miss: no "Open another" nudging the next spin
            // (spec R1.17). The prize is already in the bag, so there is nothing to collect.
            set(22, Menus.icon(Material.BARRIER, back != null ? "&cBack to the crate" : "&cBack",
                    outcome.win() ? "&7Your prize is already in your bag." : "&7No prize this time."),
                    e -> leave());
        }
    }

    private void paintStrip() {
        for (int i = 0; i < STRIP_LEN; i++) {
            int idx = Math.min(strip.size() - 1, step + i);
            getInventory().setItem(STRIP_START + i, strip.get(idx));
        }
    }

    @Override
    public void open(Player viewer) {
        super.open(viewer);
        if (!isOpenFor(viewer)) {
            tell(); // the screen never opened (another plugin said no): the result, now
            return;
        }
        if (task == null && !landed) {
            next();
        }
    }

    private void next() {
        if (step >= delays.length) {
            land();
            return;
        }
        long wait = delays[step];
        task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            task = null;
            if (!isOpenFor(player)) {
                tell();
                return;
            }
            step++;
            paintStrip();
            float pitch = 0.6f + 1.0f * Math.min(1f, (float) step / delays.length);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, pitch);
            next();
        }, wait);
    }

    private void land() {
        landed = true;
        refresh();
        tell();
    }

    /** The result, once — on landing, or when the spin is closed early. */
    private void tell() {
        if (told) {
            return;
        }
        told = true;
        if (outcome.win()) {
            Sounds.won(player);
            if (outcome.label() != null) {
                player.sendMessage(Text.of("&aYou got " + outcome.label() + "&a!"));
            }
            if (outcome.big()) {
                BigWin.celebrate(player, outcome.label());
            }
        } else {
            Sounds.miss(player);
            player.sendMessage(Text.of("&7No prize this time."));
        }
    }

    private void leave() {
        if (back != null) {
            back.run();
        } else {
            player.closeInventory();
        }
    }

    @Override
    protected void onClose(Player viewer) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        tell();
    }
}
