package com.dierks.homecraft.gui.games.cup;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.cup.live.CupDesk;
import com.dierks.homecraft.games.cup.live.CupWords;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One course's Weekly Cup (27, EVENTS-OWNER-DECISIONS §D2): everything a player should know before
 * paying, then one click to enter. It belongs to the Cup game, so its clicks run in the Cup's guard.
 *
 * <p>4 the Cup and the course; 10 how it's paid (the shares in the NAME); 12 the pool and when it
 * is paid in the NAME ("Cup pool: 35 tokens · 5 in - paid Mon 4:00 AM"; once an admin paid it out
 * early, that it was: {@link CupWords#poolName}); 14 enter ("Pay 5 tokens and
 * enter this week's Cup"), or that you're in with your Cup time ("your time 0:40.0"), or why you
 * can't, in the NAME; 16 your tokens; 22 back to the course screen. The key facts are in the NAMES
 * for Bedrock, which shows lore only on tap-and-hold.
 * Nothing is random, so none of Take a break's chance rules apply: the entry is a plain token spend,
 * and a double click can't pay twice (the table's key refuses a second entry).
 */
public final class CupMenu extends GameMenu {

    private final WeeklyCup cup;
    private final Course course;

    public CupMenu(HomeCraftManagement plugin, WeeklyCup cup, Course course, Player viewer, Runnable back) {
        super(plugin, cup, viewer, back);
        this.cup = cup;
        this.course = course;
        init(27, Text.of("&6Weekly Cup: " + course.name()));
    }

    @Override
    protected void build() {
        fill();
        CupDesk.View v = cup.view(course, viewer.getUniqueId(), true);
        set(4, Menus.icon(Material.GOLD_BLOCK, "&6Weekly Cup &7- " + course.name(),
                rules(CupWords.RULES).toArray(new String[0])), null);
        set(10, Menus.icon(Material.BOOK, CupWords.SHARES_NAME, rules(CupWords.SHARES).toArray(new String[0])), null);
        set(16, balanceTile(), null);
        exitTile();
        if (v == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7The Cup can't be read right now"), null);
            return;
        }
        String when = cup.when(v.endsAt());
        set(12, Menus.icon(Material.GOLD_INGOT, CupWords.poolName(v, when),
                CupWords.poolLore(v, when, cup.settings().serverTopup()).toArray(new String[0])), null);
        if (v.in() && !v.settledEarly()) { // paid out early: the grey "already paid out" below
            set(14, Menus.glint(Menus.icon(Material.LIME_CONCRETE, CupWords.enterName(v), CupWords.yourTime(v.mine()),
                    "&7Every counted run this week can beat it."), true), null);
            return;
        }
        if (v.refusal() != null) {
            set(14, Menus.icon(Material.GRAY_DYE, CupWords.enterName(v)), null);
            return;
        }
        set(14, Menus.icon(Material.EMERALD, CupWords.enterName(v), "&7" + CupText.enterPrompt(v.fee()),
                "&7Only runs you start after entering count.", "&eClick to pay and enter"), e -> enter());
    }

    private void enter() {
        hold(ClickHold.SETTLE_MS);
        cup.enter(viewer, course.id());
        if (showing()) {
            refresh();
        }
    }

    private static List<String> rules(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add("&7" + l);
        }
        return out;
    }
}
