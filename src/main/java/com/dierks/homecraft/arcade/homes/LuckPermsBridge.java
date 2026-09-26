package com.dierks.homecraft.arcade.homes;

import com.dierks.homecraft.HomeCraftManagement;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that touches the LuckPerms API, in one class that is only loaded when LuckPerms is
 * running — so the plugin still starts without it.
 */
final class LuckPermsBridge {

    private final HomeCraftManagement plugin;
    private final HomeService homes;
    private final LuckPerms api;
    /** Players with a refresh already queued, so a burst of node events runs one refresh. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    LuckPermsBridge(HomeCraftManagement plugin, HomeService homes) {
        this.plugin = plugin;
        this.homes = homes;
        this.api = LuckPermsProvider.get();
        api.getEventBus().subscribe(plugin, NodeMutateEvent.class, this::onMutate);
    }

    /**
     * Give the player exactly {@code desired} of the managed nodes (none when null) and clear
     * {@code alsoRemove} where they hold it. Writes nothing when that is already true.
     */
    void apply(Player player, String desired, Set<String> alsoRemove) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return; // not loaded yet; the join refresh will come back
        }
        Set<String> current = new HashSet<>();
        Set<String> legacy = new HashSet<>();
        for (PermissionNode n : user.getNodes(NodeType.PERMISSION)) {
            if (!n.getValue() || n.hasExpiry() || !n.getContexts().isEmpty()) {
                continue;
            }
            if (n.getKey().startsWith(HomeMath.OWN_NODE_PREFIX)) {
                current.add(n.getKey());
            } else if (alsoRemove.contains(n.getKey())) {
                legacy.add(n.getKey());
            }
        }
        HomeMath.Change change = HomeMath.change(current, desired, legacy);
        if (change.none()) {
            return;
        }
        for (String key : change.remove()) {
            user.data().remove(PermissionNode.builder(key).value(true).build());
        }
        for (String key : change.add()) {
            user.data().add(PermissionNode.builder(key).value(true).build());
        }
        api.getUserManager().saveUser(user);
        plugin.getLogger().info("Homes for " + player.getName() + ": +" + change.add() + " −" + change.remove());
    }

    /**
     * A permission change somewhere. A player's groups (or a group's home tiers) changing means
     * their base may have moved. The perk's own {@code hcm_} writes are ignored, so a refresh
     * never answers itself. Runs on a LuckPerms thread; the refresh goes to the main thread.
     */
    private void onMutate(NodeMutateEvent event) {
        Node node = event instanceof NodeAddEvent a ? a.getNode()
                : event instanceof NodeRemoveEvent r ? r.getNode() : null;
        if (node != null && node.getKey().startsWith(HomeMath.OWN_NODE_PREFIX)) {
            return;
        }
        boolean relevant = node == null || node.getType() == NodeType.INHERITANCE
                || node.getKey().startsWith(HomeMath.MULTIPLE);
        if (!relevant) {
            return;
        }
        if (event.isUser() && event.getTarget() instanceof User u) {
            queue(u.getUniqueId());
        } else if (event.isGroup()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                queue(p.getUniqueId());
            }
        }
    }

    private void queue(UUID id) {
        if (!queued.add(id)) {
            return;
        }
        // A second's delay lets LuckPerms recalculate the player's Bukkit permissions first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            queued.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                homes.refresh(p, 0);
            }
        }, 20L);
    }
}
