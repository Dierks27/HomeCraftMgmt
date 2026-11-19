package com.dierks.homecraft.games.hub;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hcm games hub ...} (0.44.0): {@code status}, {@code here} (visitors arrive where you stand) and
 * {@code here clear} (the Hub world's spawn again); 0.44.1: {@code exit [clear]}, the exit pad.
 */
final class HubAdmin implements GameAdmin {

    static final List<String> SUBS = List.of("status", "here", "exit");

    private final Hub hub;

    HubAdmin(Hub hub) {
        this.hub = hub;
    }

    @Override
    public String name() {
        return "hub";
    }

    @Override
    public void handle(CommandSender sender, String[] args) {
        String sub = args == null || args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "here" -> {
                boolean clear = args.length > 1 && args[1].equalsIgnoreCase("clear");
                if (clear) {
                    out.add(hub.clearSpot());
                } else if (sender instanceof Player p) {
                    out.add(hub.setSpot(p));
                } else {
                    out.add("&cOnly a player can do that - stand where visitors should arrive.");
                }
            }
            case "exit" -> { // 0.44.1: the exit pad
                boolean clear = args.length > 1 && args[1].equalsIgnoreCase("clear");
                if (clear) {
                    out.add(hub.clearExit());
                } else if (sender instanceof Player p) {
                    out.add(hub.setExit(p));
                } else {
                    out.add("&cOnly a player can do that - stand where the pad should be.");
                }
            }
            case "status" -> {
                for (String line : hub.statusLines()) {
                    out.add("&7" + line);
                }
            }
            default -> out.addAll(help());
        }
        for (String line : out) {
            sender.sendMessage(Text.of(line));
        }
    }

    @Override
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args == null || args.length <= 1) {
            String typed = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String s : SUBS) {
                if (s.startsWith(typed)) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && ("here".equalsIgnoreCase(args[0]) || "exit".equalsIgnoreCase(args[0]))
                && "clear".startsWith(
                args[1].toLowerCase(Locale.ROOT))) {
            out.add("clear");
        }
        return out;
    }

    @Override
    public List<String> help() {
        return List.of("&e/hcm games hub status &7- open or why not, who's in, the arrival spot",
                "&e/hcm games hub here [clear] &7- visitors arrive where you stand (clear: the world's spawn)",
                "&e/hcm games hub exit [clear] &7- the exit pad where you stand: visitors go home, others to the"
                        + " home world's spawn");
    }
}
