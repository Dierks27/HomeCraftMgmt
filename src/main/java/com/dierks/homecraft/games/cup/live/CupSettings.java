package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.cup.CupRules;

import java.util.List;

/**
 * The Weekly Cup's settings: {@code games.cup} (EVENTS-OWNER-DECISIONS §D2).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} clamps an
 * out-of-range number with one WARN naming its full key and never throws.
 *
 * @param enabled     new entries and the Cup prompts. Off, nobody can enter and the prompts go;
 *                    Cups already running still finish their seven days and pay out (or refund), so
 *                    no entry is ever stranded
 * @param entry       tokens to enter one course's Cup for one week ({@link CupRules#MIN_ENTRY} to
 *                    {@link CupRules#MAX_ENTRY})
 * @param serverTopup tokens the server adds to a pool raced by 2 or more (0 to {@link CupRules#MAX_TOPUP})
 */
public record CupSettings(boolean enabled, int entry, int serverTopup) {

    /** The leaves under {@code games.cup}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "entry", "server_topup");

    public CupSettings {
        entry = Math.max(CupRules.MIN_ENTRY, Math.min(CupRules.MAX_ENTRY, entry));
        serverTopup = Math.max(0, Math.min(CupRules.MAX_TOPUP, serverTopup));
    }

    /** The shipped settings: on, 5 tokens to enter, 10 from the server once 2 or more are in. */
    public static CupSettings defaults() {
        return new CupSettings(true, CupRules.DEFAULT_ENTRY, CupRules.DEFAULT_TOPUP);
    }

    /** Read {@code games.cup} over {@code d}; never throws. */
    public static CupSettings parse(GamesConfig.Node n, CupSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int entry = n.whole("entry", d.entry(), CupRules.MIN_ENTRY, CupRules.MAX_ENTRY);
        int topup = n.whole("server_topup", d.serverTopup(), 0, CupRules.MAX_TOPUP);
        return new CupSettings(enabled, entry, topup);
    }
}
