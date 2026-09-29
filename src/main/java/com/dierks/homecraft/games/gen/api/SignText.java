package com.dierks.homecraft.games.gen.api;

import java.util.List;

/**
 * A sign a plan puts up (GEN-SPEC §4.0, §7): the block (a standing oak sign with its rotation, or a
 * wall sign with its facing) and its front text. The builder places the block with the plan's
 * other blocks, then writes the text glowing and waxed, so nobody can edit it.
 *
 * <p>The lines are checked here, when the plan is made: at most {@value GenCopy#SIGN_LINES} lines of
 * at most {@value GenCopy#SIGN_CHARS} plain ASCII characters, none of them a word the Games never
 * say ({@link GenCopy#signProblems}). A plan can't carry a sign a child can't read.
 */
public record SignText(int x, int y, int z, String blockData, List<String> lines) {

    public SignText {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("a sign needs its block");
        }
        List<String> problems = GenCopy.signProblems(lines);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        lines = List.copyOf(lines);
    }
}
