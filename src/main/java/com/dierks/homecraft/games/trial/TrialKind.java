package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The three kinds of course (spec §11): what you race in, how fast anyone could possibly go
 * between two checkpoints, how big a checkpoint is unless the builder says otherwise, and which
 * ledger line its rewards are written under — so {@code /hcm tokens audit} shows parkour, elytra
 * and boat courses apart.
 *
 * <p>The top speeds are deliberately generous (a sprint-jump is about 7 blocks a second, a boat
 * on blue ice about 70): they catch a teleport or a hacked client, never a good run.
 */
public enum TrialKind {
    PARKOUR("parkour", "Parkour", 14, 1.5, TokenService.Source.GAMES_PARKOUR,
            List.of("Jump from the start to the finish.",
                    "Touch every checkpoint in order.",
                    "Fall and you go back to your last checkpoint.")),
    ELYTRA("elytra", "Elytra", 80, 4.0, TokenService.Source.GAMES_ELYTRA,
            List.of("Glide through every ring in order.",
                    "Rockets give a boost: 3 more at every ring.",
                    "Landing or water sends you back to your last ring.")),
    BOAT("boat", "Boat", 75, 3.0, TokenService.Source.GAMES_BOAT,
            List.of("Row from the start to the finish.",
                    "Pass every checkpoint in order.",
                    "Getting out takes you back to your last checkpoint."));

    private final String id;
    private final String label;
    private final double maxSpeed;
    private final double defaultRadius;
    private final TokenService.Source source;
    private final List<String> rules;

    TrialKind(String id, String label, double maxSpeed, double defaultRadius, TokenService.Source source,
              List<String> rules) {
        this.id = id;
        this.label = label;
        this.maxSpeed = maxSpeed;
        this.defaultRadius = defaultRadius;
        this.source = source;
        this.rules = rules;
    }

    /** The storage and command word ({@code boat}). */
    public String id() {
        return id;
    }

    /** What players read ("Boat"). */
    public String label() {
        return label;
    }

    /** Blocks a second no honest run beats between two checkpoints. */
    public double maxSpeed() {
        return maxSpeed;
    }

    /** A checkpoint's radius when the builder doesn't give one. */
    public double defaultRadius() {
        return defaultRadius;
    }

    /** The ledger source this kind's rewards are paid under. */
    public TokenService.Source source() {
        return source;
    }

    /** Its rules, plain lines for the course screen. */
    public List<String> rules() {
        return rules;
    }

    /** The kind called {@code word} (any case), or {@code null}. */
    public static TrialKind of(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (TrialKind k : values()) {
            if (k.id.equals(w)) {
                return k;
            }
        }
        return null;
    }

    /** Every kind's word. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (TrialKind k : values()) {
            out.add(k.id);
        }
        return out;
    }
}
