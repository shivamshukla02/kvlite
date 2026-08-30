package kvlite;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public facade for the storage engine. This is what the CLI talks to.
 *
 * Write path: WAL append (durable) -> MemTable insert -> if MemTable exceeds
 * FLUSH_THRESHOLD_BYTES, flush it to a new immutable SSTable and truncate
 * the WAL. After every COMPACTION_TRIGGER_TABLES flushes, run a full
 * compaction to merge the SSTable set back down to one file.
 *
 * Read path: check MemTable first (freshest data). If not there, check each
 * SSTable newest-to-oldest — the FIRST table that has any record of the key
 * (including a tombstone) wins, since it's the most recent fact about that
 * key. A tombstone found in an SSTable means "deleted as of this point,
 * stop looking in older tables."
 *
 * Startup recovery: WAL replay rebuilds the MemTable exactly as it was
 * before an unclean shutdown; SSTables on disk are unaffected by a crash
 * because they're only ever created via write-to-temp-then-atomic-rename.
 */
public class LSMEngine {

    private static final long FLUSH_THRESHOLD_BYTES = 1_000_000; // 1MB per memtable before flush
    private static final int COMPACTION_TRIGGER_TABLES = 4; // compact once this many SSTables accumulate

    private final WriteAheadLog wal;
    private volatile MemTable memTable = new MemTable();
    private final SSTableManager sstableManager;
    private final String dataDir;
    private final AtomicInteger flushCount = new AtomicInteger(0);
    private final Object flushLock = new Object();

    public LSMEngine(String dataDir) throws IOException {
        this.dataDir = dataDir;
        java.io.File dir = new java.io.File(dataDir);
        if (!dir.exists()) dir.mkdirs();

        this.sstableManager = new SSTableManager(dataDir);

        String walPath = dataDir + java.io.File.separator + "wal.log";
        WriteAheadLog.replayFromPath(walPath, new WriteAheadLog.ReplayListener() {
            @Override
            public void onRecord(WriteAheadLog.Record record) {
                if (record.op == WriteAheadLog.OP_PUT) {
                    memTable.put(record.key, record.value);
                } else {
                    memTable.delete(record.key);
                }
            }

            @Override
            public void onCorruption(String reason, long offset) {
                System.err.println("[kvlite] WAL corruption at offset " + offset + ": " + reason
                        + " — stopping replay, continuing with data recovered so far");
            }
        });

        this.wal = new WriteAheadLog(walPath);
    }

    public void put(String key, String value) throws IOException {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        wal.appendPut(key, value);
        memTable.put(key, value);
        maybeFlush();
    }

    public void delete(String key) throws IOException {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        wal.appendDelete(key);
        memTable.delete(key);
        maybeFlush();
    }

    public String get(String key) throws IOException {
        // 1. MemTable — freshest data.
        Object memValue = memTable.get(key);
        if (memValue == MemTable.TOMBSTONE) {
            return null; // deleted; do not fall through to SSTables
        }
        if (memValue != null) {
            return (String) memValue;
        }

        // 2. SSTables, newest to oldest.
        List<SSTable> snapshot = sstableManager.snapshot();
        for (SSTable table : snapshot) {
            SSTable.Entry entry = table.get(key);
            if (entry != null) {
                return entry.tombstone ? null : entry.value;
            }
        }

        return null; // not found anywhere
    }

    private void maybeFlush() throws IOException {
        if (memTable.approximateSizeBytes() < FLUSH_THRESHOLD_BYTES) {
            return;
        }
        synchronized (flushLock) {
            if (memTable.approximateSizeBytes() < FLUSH_THRESHOLD_BYTES) {
                return; // another thread already flushed while we waited
            }
            doFlush();
            if (flushCount.incrementAndGet() % COMPACTION_TRIGGER_TABLES == 0
                    && sstableManager.tableCount() >= COMPACTION_TRIGGER_TABLES) {
                Compactor.compact(sstableManager, dataDir);
            }
        }
    }

    private void doFlush() throws IOException {
        MemTable toFlush = memTable;
        memTable = new MemTable(); // new writes go here immediately; toFlush is now read-only

        String newPath = sstableManager.nextSSTablePath();
        int approxCount = 0;
        for (var ignored : toFlush.entriesSorted()) approxCount++;
        SSTable.write(newPath, toFlush.entriesSorted(), approxCount);
        SSTable flushed = SSTable.open(newPath);
        sstableManager.addFlushedTable(flushed);

        // Only truncate the WAL after the SSTable is durably on disk — if we
        // crashed between flush and truncate, replay would just re-apply
        // already-flushed data into the new empty memtable, which is
        // harmless (idempotent), not lost.
        wal.truncate();
    }

    /** Force a flush regardless of size threshold — used by tests and the CLI's explicit flush path. */
    public void forceFlush() throws IOException {
        synchronized (flushLock) {
            if (memTable.isEmpty()) return;
            doFlush();
        }
    }

    /** Force a compaction regardless of the automatic trigger — used by tests and the CLI. */
    public void forceCompact() throws IOException {
        synchronized (flushLock) {
            Compactor.compact(sstableManager, dataDir);
        }
    }

    public int sstableCount() {
        return sstableManager.tableCount();
    }

    /** Exposes the current SSTable set for range-scan use by the CLI. Not part of the point-lookup hot path. */
    public List<SSTable> sstableSnapshotForScan() {
        return sstableManager.snapshot();
    }

    public void close() throws IOException {
        wal.close();
    }
}
