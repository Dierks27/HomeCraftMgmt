package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * My picks (54 slots): the open ones first, each with what it was locked in at and what a win gives back, then the
 * last settled ones with how they went. Rows 0-4 hold them; 46 how it works, 49 Back.
 */
public final class MyPicksMenu extends GameMenu {

    static final int SHOWN = 45;

    private final Sportsbook sb;

    public MyPicksMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.sb = game;
        init(54, Text.of(MenuStyle.title("Sports Board", "my picks")));
    }

    @Override
    protected void build() {
        fill();
        long now = sb.games().clock().nowMillis();
        List<Pick> picks = sb.picks(viewer.getUniqueId());
        if (picks.isEmpty()) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7No picks yet", "&7Pick a side on the board to see it here."),
                    null);
        }
        ZoneId zone = sb.games().clock().zone();
        for (int i = 0; i < picks.size() && i < SHOWN; i++) {
            Pick p = picks.get(i);
            set(i, Menus.icon(icon(p), title(p), lore(p, now, zone).toArray(new String[0])), null);
        }
        set(46, SportsTiles.howTile(sb.settings().rtp()), null);
        exitTile();
    }

    private static Material icon(Pick p) {
        return switch (p.status()) {
            case SportsPicksDao.OPEN -> Material.PAPER;
            case SportsPicksDao.WON -> Material.EMERALD;
            case SportsPicksDao.LOST -> Material.GRAY_DYE;
            default -> Material.GOLD_NUGGET;
        };
    }

    /** A pick's name (pure). */
    static String title(Pick p) {
        return switch (p.status()) {
            case SportsPicksDao.OPEN -> "&e" + p.pick() + " &7- waiting for the game";
            case SportsPicksDao.WON -> "&a" + p.pick() + " won: &6" + Parlor.money(p.paid()) + " back";
            case SportsPicksDao.LOST -> "&7" + p.pick() + " didn't win";
            case SportsPicksDao.VOID -> "&7" + p.pick() + ": called off, &6" + Parlor.money(p.paid()) + " &7back";
            default -> "&7" + p.pick() + ": no result, &6" + Parlor.money(p.paid()) + " &7back";
        };
    }

    /** A pick's lines (pure). */
    static List<String> lore(Pick p, long now, ZoneId zone) {
        List<String> out = new ArrayList<>();
        out.add("&7" + p.game() + (p.startsAt() > 0 ? " &7(" + (p.startsAt() > now ? "starts " : "started ")
                + Sportsbook.when(p.startsAt(), now, zone) + ")" : ""));
        out.add("&7Put in: &6" + Parlor.dollars(p.stake()) + " &7at about " + Lines.inHundred(p.price()) + " in 100");
        if (p.open()) {
            out.add("&7If they win: &6" + Parlor.money(p.payout()) + " &7back");
            out.add("&7Locked in when you picked.");
            out.add("&7Paid when the real game is decided.");
        } else if (SportsPicksDao.VOID.equals(p.status()) || SportsPicksDao.LAPSED.equals(p.status())) {
            out.add("&7Your money came back.");
        }
        return out;
    }
}
