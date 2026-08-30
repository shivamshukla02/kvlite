package kvlite;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns the on-disk set of SSTable files for one engine instance, newest
 * first. Concurrency model:
 *
 *   - readers acquire the read lock, take a stable snapshot of the current
 *     file list, then release the lock immediately and read the files
 *     without holding it — so slow disk reads never block other readers
 *     or a compaction from making progress.
 *   - a flush or compaction acquires the WRITE lock only for the instant it
 *     takes to swap the in-memory list reference (compaction already wrote
 *     the merged file to disk under a temp name before taking the lock).
 *
 * This means a reader never sees a torn/partial file set: it either sees
 * the pre-compaction list or the post-compaction list, atomically, and the
 * expensive I/O work never happens while the lock is held.
 */
public class SSTableManager {

    private final String dataDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SSTable> tables = new ArrayList<>(); // index 0 = newest

    public SSTableManager(String dataDir) throws IOException {
        this.dataDir = dataDir;
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        loadExisting();
    }

    private void loadExisting() throws IOException {
        File dir = new File(dataDir);
        File[] files = dir.listFiles((d, name) -> name.startsWith("sstable-") && name.endsWith(".dat"));
        if (files == null) return;

        // Filenames are sstable-<sequence>.dat — sort descending by sequence so index 0 is newest.
        java.util.Arrays.sort(files, (a, b) -> extractSequence(b.getName()) - extractSequence(a.getName()));

        List<SSTable> loaded = new ArrayList<>();
        for (File f : files) {
            loaded.add(SSTable.open(f.getAbsolutePath()));
        }
        this.tables = loaded;
    }

    private static int extractSequence(String filename) {
        String digits = filename.replace("sstable-", "").replace(".dat", "");
        return Integer.parseInt(digits);
    }

    public String nextSSTablePath() {
        int nextSeq = tables.isEmpty() ? 0 : (highestSequence() + 1);
        return dataDir + File.separator + "sstable-" + nextSeq + ".dat";
    }

    private int highestSequence() {
        int max = -1;
        for (SSTable t : tables) {
            max = Math.max(max, extractSequence(new File(t.path()).getName()));
        }
        return max;
    }

    /** Called after a new SSTable has been fully written (atomic rename already done). Adds it as the newest. */
    public void addFlushedTable(SSTable table) {
        lock.writeLock().lock();
        try {
            List<SSTable> updated = new ArrayList<>(tables.size() + 1);
            updated.add(table); // newest first
            updated.addAll(tables);
            this.tables = updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Called after compaction has written the merged SSTable to disk under
     * its final name. Atomically replaces the given old tables with the one
     * new table, and deletes the old files.
     */
    public void replaceWithCompacted(List<SSTable> oldTables, SSTable newTable) {
        lock.writeLock().lock();
        List<SSTable> toDelete;
        try {
            List<SSTable> updated = new ArrayList<>();
            updated.add(newTable);
            for (SSTable t : tables) {
                if (!oldTables.contains(t)) {
                    updated.add(t);
                }
            }
            toDelete = oldTables;
            this.tables = updated;
        } finally {
            lock.writeLock().unlock();
        }
        for (SSTable t : toDelete) {
            t.delete();
        }
    }

    /** Snapshot of current tables, newest first. Safe to read from after the lock is released. */
    public List<SSTable> snapshot() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tables);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int tableCount() {
        return snapshot().size();
    }
}
