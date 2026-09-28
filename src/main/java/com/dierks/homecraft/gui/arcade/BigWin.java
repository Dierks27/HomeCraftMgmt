package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * The moment for a big win — a crate's Mini jackpot, the Scratch Ticket jackpot, a Rare-or-better
 * Card: a title on screen, a harmless firework at the player and a toast sound. The server-wide
 * shout is sent by the Arcade when the prize is granted, so it goes out even if nobody watches the
 * animation.
 *
 * <p>Public for the skill games (a new record, a first clear). Games of chance never use it: a
 * chance result gets a private title at most, never "BIG WIN", a firework others can see or a
 * shout (spec R1.4).
 */
public final class BigWin {

    private BigWin() {
    }

    public static void celebrate(Player player, String label) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.showTitle(Title.title(Text.of("&6&lBIG WIN!"), Text.of(label == null ? "" : label),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        Fireworks.spawn(player.getLocation().add(0, 1, 0));
    }
}
