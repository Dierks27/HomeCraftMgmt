package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/** The Candy Apple shower's reward: the Trick-or-Treat Bag, into a free slot, as 0.38.5 gave it. */
public final class CandyBag implements ShowerReward {

    @Override
    public String title() {
        return "Trick-or-Treat Bag";
    }

    @Override
    public String word() {
        return "bag";
    }

    /** A free slot: the bag never goes on the ground. */
    @Override
    public boolean hasRoom(Player p) {
        return p.getInventory().firstEmpty() != -1;
    }

    @Override
    public boolean give(HomeCraftManagement plugin, Player p, int year, int goal) {
        return p.getInventory().addItem(CandyItems.bag(year, goal)).isEmpty();
    }

    /** The toast, the title and a burst in view, then the line that says what the bag is for. */
    @Override
    public void moment(Player p, int year, int goal) {
        try {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            p.showTitle(Title.title(Text.of("&6Trick-or-Treat Bag!"),
                    Text.of("&eYou found " + goal + " candy apples!"),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
            Fireworks.burstInView(p);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        p.sendMessage(Text.of("&6✦ &eThe " + CandyItems.bagName(year) + " &eis yours! &7It's in your"
                + " bag. Put your candy apples in it!"));
    }
}
