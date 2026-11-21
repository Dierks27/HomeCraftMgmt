package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /hcm games npc ...} (hcm.games.admin, checked by the framework): a parent's or admin's NPC setting for a
 * player, locked or not (as Take a break's {@code /hcm games break <player> ...}), and the owner's names and skins for
 * the NPCs. Every change writes a log line.
 *
 * <pre>
 *   /hcm games npc list
 *   /hcm games npc player &lt;player&gt; show
 *   /hcm games npc player &lt;player&gt; set &lt;on|off&gt; &lt;easy|matched|any&gt;    (their own; they can change it)
 *   /hcm games npc player &lt;player&gt; lock &lt;on|off&gt; &lt;easy|matched|any&gt;   (they can't change it)
 *   /hcm games npc player &lt;player&gt; unlock
 *   /hcm games npc name &lt;npc&gt; &lt;new name&gt;|reset
 *   /hcm games npc skin &lt;npc&gt; default:&lt;name&gt;[:slim]|player:&lt;name&gt;|texture:&lt;value&gt; [signature]|reset
 * </pre>
 */
final class CpuAdmin implements GameAdmin {

    private final CpuPlayers npcs;

    CpuAdmin(CpuPlayers npcs) {
        this.npcs = npcs;
    }

    @Override
    public String name() {
        return "npc";
    }

    @Override
    public List<String> help() {
        return List.of("&e/hcm games npc list &7- the NPC players, their levels, names and skins",
                "&e/hcm games npc player <player> show|set|lock <on|off> <easy|matched|any>|unlock &7- a player's NPC"
                        + " setting (lock: they can't change it)",
                "&e/hcm games npc name <npc> <name>|reset &7- rename an NPC (the gear and stars stay)",
                "&e/hcm games npc skin <npc> default:<name>[:slim]|player:<name>|texture:<value> [signature]|reset"
                        + " &7- an NPC's skin");
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return List.of("list", "player", "name", "skin");
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (verb.equals("name") || verb.equals("skin"))) {
            return CpuRoster.ALL.stream().map(CpuPlayer::key).toList();
        }
        if (verb.equals("player")) {
            return switch (args.length) {
                case 2 -> Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList();
                case 3 -> List.of("show", "set", "lock", "unlock");
                case 4 -> List.of("on", "off");
                case 5 -> List.of("easy", "matched", "any");
                default -> List.of();
            };
        }
        if (verb.equals("skin") && args.length == 3) {
            List<String> out = new ArrayList<>();
            for (String b : NpcSkin.BUILT_IN) {
                out.add("default:" + b);
                out.add("default:" + b + ":slim");
            }
            out.add("player:");
            out.add("texture:");
            out.add("reset");
            return out;
        }
        return List.of();
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        String verb = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "list" -> list(sender);
            case "player" -> player(sender, args);
            case "name" -> rename(sender, args);
            case "skin" -> skin(sender, args);
            default -> help().forEach(l -> sender.sendMessage(Text.of(l)));
        }
    }

    private void list(CommandSender sender) {
        sender.sendMessage(Text.of("&6NPC players &7(" + (CpuPlayers.of(npcs.games()) != null ? "on" : "off") + ")"));
        for (CpuPlayer c : CpuRoster.ALL) {
            sender.sendMessage(Text.of("&7  " + c.key() + ": " + npcs.label(c) + " &7- "
                    + c.personality().name().toLowerCase(Locale.ROOT) + ", wears " + npcs.skin(c).describe()));
        }
    }

    private void player(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm games npc player <player> show|set|lock <on|off>"
                    + " <easy|matched|any>|unlock"));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || !(target.hasPlayedBefore() || target.isOnline())) {
            sender.sendMessage(Text.of("&cNo player called " + args[1] + " has played here."));
            return;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? args[1] : target.getName();
        String what = args[2].toLowerCase(Locale.ROOT);
        switch (what) {
            case "show" -> show(sender, id, name);
            case "unlock" -> {
                if (!npcs.setLock(id, null)) {
                    sender.sendMessage(Text.of("&cCould not save that - see the console."));
                    return;
                }
                sender.sendMessage(Text.of("&aUnlocked &f" + name + "&a's NPC setting. &7Their own applies again."));
                logged(sender, "player " + name + " unlock");
            }
            case "set", "lock" -> {
                CpuChoice c = args.length >= 5 ? choice(args[3], args[4]) : null;
                if (c == null) {
                    sender.sendMessage(Text.of("&cUsage: /hcm games npc player " + name + " " + what
                            + " <on|off> <easy|matched|any>"));
                    return;
                }
                boolean ok = what.equals("lock") ? npcs.setLock(id, c) : npcs.setFor(id, c);
                if (!ok) {
                    sender.sendMessage(Text.of("&cCould not save that - see the console."));
                    return;
                }
                sender.sendMessage(Text.of("&aNPC players for &f" + name + "&a: " + describe(c)
                        + (what.equals("lock") ? " &7(locked: only an admin can change it - /hcm games npc player "
                        + name + " unlock)" : " &7(they can change it)")));
                logged(sender, "player " + name + " " + what + " " + c.text());
            }
            default -> sender.sendMessage(Text.of("&cUsage: /hcm games npc player <player> show|set|lock <on|off>"
                    + " <easy|matched|any>|unlock"));
        }
    }

    /** {@code on|off} and a level word, or {@code null}. */
    static CpuChoice choice(String onOff, String level) {
        CpuChoice.Pref p = CpuChoice.Pref.of(level);
        if (p == null || onOff == null || !(onOff.equalsIgnoreCase("on") || onOff.equalsIgnoreCase("off"))) {
            return null;
        }
        return new CpuChoice(onOff.equalsIgnoreCase("on"), p);
    }

    static String describe(CpuChoice c) {
        return (c.on() ? "&aon" : "&coff") + "&7, " + c.level().word();
    }

    private void show(CommandSender sender, UUID id, String name) {
        CpuChoice.Prefs p = npcs.prefs(id);
        sender.sendMessage(Text.of("&6NPC players &7- &f" + name));
        sender.sendMessage(Text.of("&7  Their own: " + (p.own() == null ? "not set (on, any)" : describe(p.own()))));
        sender.sendMessage(Text.of("&7  Locked: " + (p.lock() == null ? "no" : describe(p.lock()))));
        sender.sendMessage(Text.of("&7  Now: " + describe(p.effective())));
    }

    private void rename(CommandSender sender, String[] args) {
        CpuPlayer c = args.length >= 2 ? CpuRoster.byKey(args[1]) : null;
        if (c == null || args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm games npc name <" + keys() + "> <new name>|reset"));
            return;
        }
        String typed = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String now = npcs.rename(c, typed.equalsIgnoreCase("reset") ? null : typed);
        if (now == null) {
            sender.sendMessage(Text.of("&cThat name has nothing left to show (letters, digits, spaces, - ' _; up to "
                    + CpuRoster.MAX_NAME + ")."));
            return;
        }
        sender.sendMessage(Text.of("&aThe NPC " + c.key() + " is now " + npcs.label(c) + "&a."));
        logged(sender, "name " + c.key() + " " + now);
    }

    private void skin(CommandSender sender, String[] args) {
        CpuPlayer c = args.length >= 2 ? CpuRoster.byKey(args[1]) : null;
        if (c == null || args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm games npc skin <" + keys() + "> default:<name>[:slim]"
                    + "|player:<name>|texture:<value> [signature]|reset"));
            return;
        }
        String typed = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (typed.equalsIgnoreCase("reset")) {
            npcs.reskin(c, null);
            sender.sendMessage(Text.of("&a" + npcs.label(c) + " &awears its own skin again (" + c.skin().describe()
                    + ")."));
            logged(sender, "skin " + c.key() + " reset");
            return;
        }
        NpcSkin skin;
        try {
            skin = NpcSkin.parse(typed);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.of("&c" + e.getMessage()));
            return;
        }
        npcs.reskin(c, skin);
        sender.sendMessage(Text.of("&a" + npcs.label(c) + " &anow wears " + skin.describe() + "."
                + (skin instanceof NpcSkin.Account ? " &7It is fetched when it is worn; if it can't be, the NPC wears"
                + " its own skin and the console says why." : "")));
        logged(sender, "skin " + c.key() + " " + (skin instanceof NpcSkin.Texture ? "texture" : skin.text()));
    }

    private static String keys() {
        return String.join("|", CpuRoster.ALL.stream().map(CpuPlayer::key).toList());
    }

    private void logged(CommandSender sender, String what) {
        npcs.games().logger().info("[Games] " + sender.getName() + ": /hcm games npc " + what);
    }
}
