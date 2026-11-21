package com.dierks.homecraft.games.cpu;

import java.util.Locale;

/**
 * One player's NPC setting: whether NPC players are on for them, and how they want them to play. Kept in
 * {@code game_prefs} as one short line ({@link #text}, {@code "on:any"}). Pure.
 *
 * <p>A player sets their own ({@code npc.own}); a parent or admin may set one for them and lock it ({@code npc.lock}),
 * as Take a break's admin limit: while it is locked, theirs is set aside and they can't change it
 * ({@link Prefs#effective}).
 *
 * @param on    whether NPCs join their tables, enter their races and stand about where they can see them
 * @param level how NPCs play in a game they are in
 */
public record CpuChoice(boolean on, Pref level) {

    /** How a player wants NPCs to play in a game they are in. */
    public enum Pref {
        /** Every NPC in the game plays at easy, whatever its own level (the owner, for Lilah). */
        EASY,
        /** Near this player's own record in that game, where there is an honest way to tell; else medium. */
        MATCHED,
        /** Each NPC at its own level. */
        ANY;

        /** "easy", "matched", "any". */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The pref this word names, or {@code null}. */
        public static Pref of(String word) {
            if (word == null) {
                return null;
            }
            for (Pref p : values()) {
                if (p.word().equalsIgnoreCase(word.trim())) {
                    return p;
                }
            }
            return null;
        }
    }

    /** Everyone's before they choose: NPCs on, each at its own level. */
    public static final CpuChoice DEFAULT = new CpuChoice(true, Pref.ANY);

    public CpuChoice {
        if (level == null) {
            level = Pref.ANY;
        }
    }

    /** {@code "on:any"}. */
    public String text() {
        return (on ? "on" : "off") + ":" + level.word();
    }

    /** What was kept, or {@code null} for nothing kept or a line that can't be read. */
    public static CpuChoice parse(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.trim().split(":");
        if (p.length != 2 || !(p[0].equals("on") || p[0].equals("off")) || Pref.of(p[1]) == null) {
            return null;
        }
        return new CpuChoice(p[0].equals("on"), Pref.of(p[1]));
    }

    /**
     * A player's own setting and the one a parent or admin locked for them.
     *
     * @param own  theirs, or {@code null} (never set: {@link #DEFAULT})
     * @param lock the locked one, or {@code null} for none
     */
    public record Prefs(CpuChoice own, CpuChoice lock) {

        public static final Prefs NONE = new Prefs(null, null);

        /** What applies: the lock while there is one, else their own, else the default. */
        public CpuChoice effective() {
            return lock != null ? lock : own != null ? own : DEFAULT;
        }

        /** Whether a parent or admin locked it: the player can't change it. */
        public boolean locked() {
            return lock != null;
        }

        /**
         * The player changes their own to {@code wanted}: refused ({@code null}) while it is locked, so a lock can't
         * be lifted by the player.
         */
        public Prefs withOwn(CpuChoice wanted) {
            return locked() ? null : new Prefs(wanted, null);
        }
    }
}
