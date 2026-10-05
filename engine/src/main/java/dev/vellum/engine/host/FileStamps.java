package dev.vellum.engine.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Notices edits to the files pages were loaded from, for hosts that reload pages on save (the previewer, a
 * development client). It remembers each file's modification time, and {@link #changed} tells whether any differs
 * from the previous call. Polling a page's handful of files is instant on every platform, where a
 * {@code WatchService} on macOS polls whole directories every two seconds. Not thread-safe; the collection passed in
 * may be a concurrent one that other threads add to.
 */
public final class FileStamps {
    private Map<Path, Long> stamps = new HashMap<>();

    /**
     * Whether any of {@code files} was modified, created or deleted since the previous call. Files seen for the
     * first time are only remembered.
     */
    public boolean changed(Collection<Path> files) {
        Map<Path, Long> now = new HashMap<>();
        boolean changed = false;
        for (Path file : files) {
            long stamp = stamp(file);
            Long before = stamps.get(file);
            changed |= before != null && before != stamp;
            now.put(file, stamp);
        }
        stamps = now;
        return changed;
    }

    /** The modification time, or -1 for a missing file. */
    private static long stamp(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
