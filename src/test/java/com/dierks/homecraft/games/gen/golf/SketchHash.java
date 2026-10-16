package com.dierks.homecraft.games.gen.golf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A drawn hole's fingerprint (GOLF-V4-SPEC §7, {@code SketchSizeTest}): SHA-256 over everything the
 * rasteriser decides — its words, the mirror, T, the tee, the cup, the bounds, the sign, the tee
 * sign's feature, the quota's features, and every block and scenery block in the order drawn. Two
 * holes with the same fingerprint are the same hole, block for block.
 */
final class SketchHash {

    private SketchHash() {
    }

    static String of(HoleLayout l) {
        StringBuilder b = new StringBuilder();
        b.append(l.describe()).append('|').append(l.mirrored()).append('|').append(l.turfY()).append('|')
                .append(l.teeX()).append(' ').append(l.teeY()).append(' ').append(l.teeZ()).append('|')
                .append(l.cupX()).append(' ').append(l.cupZ()).append(' ').append(l.cupTop()).append('|')
                .append(l.laneMinX()).append(' ').append(l.laneMinZ()).append(' ').append(l.laneMaxX()).append(' ')
                .append(l.laneMaxZ()).append(' ').append(l.boundsTop()).append('|')
                .append(l.signX()).append(' ').append(l.signY()).append(' ').append(l.signZ()).append('|')
                .append(l.teeFeature()).append('|').append(l.features().stream().map(Enum::name).sorted().toList())
                .append('\n');
        blocks(b, l.blocks());
        b.append("scenery\n");
        blocks(b, l.scenery());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void blocks(StringBuilder b, List<HoleLayout.Placed> placed) {
        for (HoleLayout.Placed p : placed) {
            b.append(p.x()).append(' ').append(p.y()).append(' ').append(p.z()).append(' ').append(p.blockData())
                    .append('\n');
        }
    }
}
