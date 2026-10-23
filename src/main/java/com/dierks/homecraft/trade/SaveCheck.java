package com.dierks.homecraft.trade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Whether a player save reached the disk (red-team finding TCR-FA-3). {@code Player.saveData} never says: vanilla
 * catches every failure of the write (a full disk, a file it may not replace) and only logs "Failed to save player
 * data". A save that works writes a new file and moves it over the old one, so the file's time, size or identity
 * changes; one that fails leaves it as it was. Pure over a {@link Path}, so a test pins it.
 */
final class SaveCheck {

    private SaveCheck() {
    }

    /** What a file looks like on disk: its time, size and identity (the inode, where there is one). */
    record Stamp(FileTime modified, long size, Object key) {
    }

    /** The file as it is now, or null when it isn't there (or can't be looked at). */
    static Stamp stamp(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(a.lastModifiedTime(), a.size(), a.fileKey());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Run {@code save} and say whether it reached {@code file}: true when the file is new or changed, false when it is
     * as it was (or still isn't there), null when its folder isn't there at all (the server keeps its players
     * somewhere else, so there is no answer).
     */
    static Boolean reached(Path file, Runnable save) {
        Path dir = file.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            save.run();
            return null;
        }
        Stamp before = stamp(file);
        save.run();
        Stamp after = stamp(file);
        return after != null && !after.equals(before);
    }
}
