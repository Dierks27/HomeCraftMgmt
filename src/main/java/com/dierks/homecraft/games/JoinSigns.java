package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Join signs: how a physical hub hooks into the Games (spec §8.5, R3.16).
 *
 * <p>An admin writes a sign with {@code [Arcade]} on the first line and a game or course id on
 * the second. A tick later — once the sign really holds the text — it is re-read, reformatted
 * ("[Arcade]", the game's name, "Click to play"), tagged with the id ({@code Keys.GAME_SIGN}) and
 * waxed, so nobody can edit it by accident. Anyone right-clicking it then goes through
 * {@code /hcm play <id>}'s entry point, with the same gate as everywhere else. Anyone else who
 * writes {@code [Arcade]} just loses that line: a player can't make a sign that looks official.
 * Breaking one needs build rights as usual.
 *
 * <p>The click handler runs at HIGH without {@code ignoreCancelled} (clicks can arrive already
 * cancelled, e.g. in a protected hub) and cancels both results, so a sign never also places or
 * uses whatever is in the hand. It is a plain listener registered once; while the games are off
 * (or the service failed) it does nothing, and it never lets an exception out.
 */
public final class JoinSigns implements Listener {

    private final HomeCraftManagement plugin;

    public JoinSigns(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Whether a sign's first line asks to be a join sign: "[Arcade]", any case. */
    static boolean isHeader(String line) {
        return line != null && line.trim().equalsIgnoreCase("[Arcade]");
    }

    /** The id written on the second line, lower-cased, or {@code null} when it is blank. */
    static String idOf(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        return line.trim().toLowerCase(Locale.ROOT);
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** The games, or {@code null} while they are off or the service failed. */
    private GamesService games() {
        GamesService games = plugin.games();
        return games == null || !games.config().enabled() ? null : games;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWrite(SignChangeEvent event) {
        try {
            if (games() == null || !isHeader(plain(event.line(0)))) {
                return;
            }
            Player writer = event.getPlayer();
            if (!writer.hasPermission("hcm.games.admin")) {
                event.line(0, Component.empty());
                return;
            }
            Block block = event.getBlock();
            Side side = event.getSide();
            UUID who = writer.getUniqueId();
            plugin.getServer().getScheduler().runTask(plugin, () -> tag(block, side, who));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "A join sign could not be written", e);
        }
    }

    /** One tick after writing: re-read the sign, and tag, reformat and wax it. */
    private void tag(Block block, Side side, UUID writerId) {
        try {
            GamesService games = games();
            if (games == null || !(block.getState() instanceof Sign sign)) {
                return;
            }
            SignSide text = sign.getSide(side);
            if (!isHeader(plain(text.line(0)))) {
                return;
            }
            Player writer = plugin.getServer().getPlayer(writerId);
            String id = idOf(plain(text.line(1)));
            GamesService.Target target = id == null ? null : games.resolve(id);
            if (target == null) {
                if (writer != null) {
                    writer.sendMessage(Text.of("&cNo game or course is called '" + (id == null ? "" : id)
                            + "'. &7Write its id on the second line."));
                }
                return;
            }
            String playId = target.playable() != null ? target.playable().id() : target.game().id();
            String name = target.playable() != null ? target.playable().name() : target.game().name();
            text.line(0, Text.of("&5[Arcade]"));
            text.line(1, Text.of(name));
            text.line(2, Text.of("&eClick to play"));
            text.line(3, Component.empty());
            sign.getPersistentDataContainer().set(Keys.GAME_SIGN, PersistentDataType.STRING, playId);
            sign.setWaxed(true);
            sign.update();
            if (writer != null) {
                writer.sendMessage(Text.of("&aThis sign now opens " + name + "."));
            }
            plugin.getLogger().info("Join sign for " + playId + " at " + block.getWorld().getName() + " "
                    + block.getX() + "," + block.getY() + "," + block.getZ());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "A join sign could not be tagged", e);
        }
    }

    /** The id a tagged sign opens, or {@code null} for any other block. */
    private static String signId(Block block) {
        if (block == null || !Tag.ALL_SIGNS.isTagged(block.getType())) {
            return null;
        }
        BlockState state = block.getState(false);
        if (!(state instanceof Sign sign)) {
            return null;
        }
        return sign.getPersistentDataContainer().get(Keys.GAME_SIGN, PersistentDataType.STRING);
    }

    /** Right-click: open the game (main hand only; both results cancelled so nothing else happens). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            GamesService games = games();
            if (games == null) {
                return;
            }
            String id = signId(event.getClickedBlock());
            if (id == null) {
                return;
            }
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            if (event.getHand() != EquipmentSlot.HAND) {
                return; // the off-hand repeat of the same click: cancelled, but it opens nothing
            }
            games.open(event.getPlayer(), id, null);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "A join sign click failed", e);
        }
    }

    /** A tagged sign is never opened for editing. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onOpenSign(PlayerOpenSignEvent event) {
        try {
            if (plugin.games() == null) {
                return;
            }
            Sign sign = event.getSign();
            if (sign != null && sign.getPersistentDataContainer().has(Keys.GAME_SIGN, PersistentDataType.STRING)) {
                event.setCancelled(true);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "A join sign check failed", e);
        }
    }
}
