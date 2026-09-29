package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Fresh Courses parkour level picker, "Parkour Levels" (27, GEN-SPEC §1.1, §5.4): what
 * {@code /hcm play fresh_parkour_tiers} opens. Three separate courses, each easy (or hard) from its
 * first jump to its last, each with its own board, side by side so the youngest player can always
 * find "Easy Parkour".
 *
 * <pre>
 *  4 what the three are     11 Easy Parkour     13 Parkour     15 Hard Parkour     22 Back/Close
 * </pre>
 *
 * The tiles are the Fresh Courses screen's ({@link DailyTiles}): the NAME carries your stars in
 * this set and the course code, and a click opens that course's own screen through the gate.
 */
public final class TierMenu extends GameMenu {

    /** The three tiers, easiest first, and where each goes. */
    static final List<Slots.Def> TIERS = List.of(Slots.DAILY_PARKOUR_EASY, Slots.DAILY_PARKOUR_MEDIUM,
            Slots.DAILY_PARKOUR_HARD);
    static final int[] AT = {11, 13, 15};

    /** The picker's name: the playable's ({@code DailyCourses.playables()}). */
    static final String NAME = "Parkour Levels";

    public TierMenu(HomeCraftManagement plugin, Game daily, Player viewer, Runnable back) {
        super(plugin, daily, viewer, back);
        init(27, Text.of("&e" + NAME));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        GamesService games = plugin.games();
        if (games == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7The games are closed right now"), null);
            return;
        }
        int cadence = DailyLookup.edition(games).cadenceDays();
        long now = games.clock().nowMillis();
        long next = games.generated().nextChangeAt();
        List<String> head = new ArrayList<>(List.of("&aEasy&7: short hops, no running.",
                "&eParkour&7: bigger jumps, run and jump.", "&cHard Parkour&7: long jumps, small pads.",
                "&7Green start, blue = saved, gold = finish."));
        if (next > now) {
            head.add(GenCopy.newIn(next - now));
        }
        set(4, Menus.icon(Material.FEATHER, "&e" + NAME + " &7- pick how hard", head.toArray(new String[0])), null);
        for (int i = 0; i < TIERS.size(); i++) {
            DailyTiles.View v = DailyTiles.view(games, TIERS.get(i));
            set(AT[i], DailyTiles.tile(games, viewer, v, cadence), e -> DailyTiles.click(games, viewer, v, this::reopen));
        }
    }

    private void reopen() {
        new TierMenu(plugin, game, viewer, back).open(viewer);
    }
}
