package dev.vellum.preview;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watches directories and runs a callback on the Swing thread when anything in them changes, once per burst of
 * changes. (macOS JDKs poll for changes every two seconds; Linux and Windows report them immediately.)
 */
final class FileWatcher implements AutoCloseable {
    private final WatchService service;
    private final Set<Path> watched = ConcurrentHashMap.newKeySet();

    FileWatcher(Runnable onChange) throws IOException {
        service = FileSystems.getDefault().newWatchService();
        Thread.ofPlatform().daemon().name("vellum-preview-watcher").start(() -> loop(onChange));
    }

    void watch(Path directory) {
        if (directory == null || !watched.add(directory)) return;
        try {
            directory.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            System.err.println("[vellum-preview] Cannot watch " + directory + ": " + e.getMessage());
        }
    }

    private void loop(Runnable onChange) {
        try {
            while (true) {
                drain(service.take());
                // Editors save in bursts (temp file, rename, touch): wait for a quiet moment.
                for (WatchKey more; (more = service.poll(100, TimeUnit.MILLISECONDS)) != null; ) drain(more);
                SwingUtilities.invokeLater(onChange);
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closed
        }
    }

    private static void drain(WatchKey key) {
        key.pollEvents();
        key.reset();
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}
