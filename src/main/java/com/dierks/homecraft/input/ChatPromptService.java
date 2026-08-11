package com.dierks.homecraft.input;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bridges chat into GUI flows: an inventory can't take typed input, so an admin
 * form closes the menu, asks a question, and waits for the player's next chat
 * line. The captured line is delivered back on the main thread; the chat message
 * itself is swallowed (never broadcast). Typing "cancel" aborts.
 *
 * <p>One pending prompt per player; opening a new one replaces the old.
 */
public final class ChatPromptService implements Listener {

    private final HomeCraftManagement plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatPromptService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /**
     * Ask {@code player} a question and run {@code onInput} with their next chat
     * line (on the main thread). Closes any open inventory first.
     */
    public void prompt(Player player, String question, Consumer<String> onInput) {
        player.closeInventory();
        pending.put(player.getUniqueId(), onInput);
        player.sendMessage(Text.of("&e" + question));
        player.sendMessage(Text.of("&7Type your answer in chat, or type &fcancel &7to abort."));
    }

    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = pending.remove(player.getUniqueId());
        if (callback == null) {
            return;
        }
        event.setCancelled(true); // don't broadcast the answer
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // Hop back to the main thread — GUIs, config, and the economy are not
        // async-safe.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Text.of("&7Cancelled."));
                return;
            }
            callback.accept(message);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
