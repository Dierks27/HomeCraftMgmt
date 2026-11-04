package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;

/**
 * The Harvest Basket's reward: the Pilgrim Hat 2026, an earned collector, kept for good and shown in My Looks.
 * It is granted through the Prize Counter's own earned path ({@code PrizeService.grantEarned}), which claims it
 * and puts it on an empty head or in the bag, never on the ground.
 */
public final class PilgrimHat implements ShowerReward {

    @Override
    public String title() {
        return "Pilgrim Hat";
    }

    @Override
    public String word() {
        return "hat";
    }

    /** As a collector cap is placed: an empty head, or a free slot. */
    @Override
    public boolean hasRoom(Player p) {
        ItemStack helmet = p.getInventory().getHelmet();
        return helmet == null || helmet.getType().isAir() || p.getInventory().firstEmpty() != -1;
    }

    @Override
    public boolean give(HomeCraftManagement plugin, Player p, int year, int goal) {
        if (plugin.prizes() == null) {
            return false;
        }
        var out = plugin.prizes().grantEarned(p, Collectors.PILGRIM_ID);
        return out != null && out.ok();
    }

    /** The toast, "Pilgrim Hat! / Yours to keep!" and a firework in view: no reading needed. */
    @Override
    public void moment(Player p, int year, int goal) {
        try {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            p.showTitle(Title.title(Text.of("&6Pilgrim Hat!"), Text.of("&eYours to keep!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
            Fireworks.burstInView(p);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }
}
