package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.LookDao;
import org.bukkit.DyeColor;
import org.bukkit.Material;

import java.util.List;

/** The wardrobe tests' own rows (S2 ships the shelf's; these are only what the rules need). */
final class LookFixtures {

    static final long DAY = LookRules.DAY;

    private LookFixtures() {
    }

    /** A rented cap: a dyed leather helmet for 90 days, with an optional lower buy-again price. */
    static Prize cap(String id, String display, int cost, int renew) {
        return new Prize(id, PrizeTab.LOOKS, display, List.of("A comfy cap to wear!", "Yours for 90 days."), cost,
                new Icon("", Material.LEATHER_HELMET), PrizeType.HAT, true, null, 1, DyeColor.LIME, null, null, null,
                0, 0, null, 90, "", List.of(), List.of(), null, null, List.of(), null,
                new PrizeExtra(Material.LEATHER_HELMET, renew, null, OnlyIf.ANY, 0, null));
    }

    /** A rented trail: {@code days} for {@code cost}; a trail row never reads {@code renew_tokens}. */
    static Prize trail(String id, String display, int cost, int days) {
        return new Prize(id, PrizeTab.COSMETICS, display, List.of("Snowflakes follow you", "for a month."), cost,
                new Icon("", Material.SNOWBALL), PrizeType.TRAIL, true, null, 1, null, null, null, null, 0, 0,
                "SNOWFLAKE", days, "", List.of(), List.of(), null, null, List.of(), null,
                new PrizeExtra(null, 50, null, OnlyIf.ANY, 0, null));
    }

    /** A rented animal hat: a textured head. */
    static Prize bunny() {
        return new Prize("hat_bunny", PrizeTab.LOOKS, "&fBunny Hat", List.of("Wear it on your head!"), 120,
                new Icon("", Material.LEATHER_HELMET), PrizeType.HAT, true, null, 1, null, null, null, null, 0, 0,
                null, 90, "texture", List.of(), List.of(), null, null, List.of(), null, PrizeExtra.NONE);
    }

    static LookDao.Look row(String id, String kind, long expiresAt, long since) {
        return new LookDao.Look(id, kind, expiresAt, since, false, 0, 0, "");
    }

    static LookDao.Look row(String id, String kind, long expiresAt, long since, boolean enabled) {
        return new LookDao.Look(id, kind, expiresAt, since, enabled, 0, 0, "");
    }
}
