package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.StandData;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Spawns and manages posed armor-stand Minis: a form-{@code ARMOR_STAND} Mini
 * placed in the world spawns a fully configured {@link ArmorStand} from its
 * stored pose/equipment, tagged with the Mini's identity + owner + exact item so
 * it is tracked, protected, and reclaimable. Identity lives in the entity PDC, so
 * it survives restarts with no extra table.
 */
public final class StandService {

    private final HomeCraftManagement plugin;

    public StandService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Spawn a configured armor-stand Mini and tag it. {@code oneItem} is the exact minted copy. */
    public ArmorStand spawn(Location loc, float yaw, MiniService.MiniRef ref, UUID owner, ItemStack oneItem) {
        StandData data = plugin.miniService().stand(ref.miniId());
        String itemB64 = Items.toBase64(oneItem);
        return loc.getWorld().spawn(loc, ArmorStand.class, stand -> {
            stand.setRotation(yaw, 0f);
            stand.setPersistent(true);
            if (data != null) {
                data.applyTo(stand);
            }
            PersistentDataContainer pdc = stand.getPersistentDataContainer();
            pdc.set(Keys.MINI_ID, PersistentDataType.STRING, ref.miniId());
            pdc.set(Keys.MINI_UID, PersistentDataType.STRING, ref.uid());
            pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, ref.mintNumber());
            pdc.set(Keys.MINI_OWNER, PersistentDataType.STRING, owner.toString());
            pdc.set(Keys.MINI_ITEM, PersistentDataType.STRING, itemB64);
        });
    }

    public boolean isMiniStand(Entity entity) {
        return entity instanceof ArmorStand
                && entity.getPersistentDataContainer().has(Keys.MINI_UID, PersistentDataType.STRING);
    }

    public UUID ownerOf(Entity entity) {
        String s = entity.getPersistentDataContainer().get(Keys.MINI_OWNER, PersistentDataType.STRING);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public MiniService.MiniRef refOf(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String uid = pdc.get(Keys.MINI_UID, PersistentDataType.STRING);
        String miniId = pdc.get(Keys.MINI_ID, PersistentDataType.STRING);
        if (uid == null || miniId == null) {
            return null;
        }
        Long mint = pdc.get(Keys.MINI_MINT, PersistentDataType.LONG);
        return new MiniService.MiniRef(uid, miniId, mint == null ? 0 : mint);
    }

    public ItemStack storedItem(Entity entity) {
        return Items.fromBase64(entity.getPersistentDataContainer().get(Keys.MINI_ITEM, PersistentDataType.STRING));
    }

    /** Remove the stand and return the exact minted Mini to the reclaimer. */
    public void reclaim(ArmorStand stand, Player to) {
        ItemStack item = storedItem(stand);
        if (item != null) {
            to.getInventory().addItem(item).values()
                    .forEach(drop -> to.getWorld().dropItemNaturally(stand.getLocation(), drop));
        }
        stand.remove();
    }
}
