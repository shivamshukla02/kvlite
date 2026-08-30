package kvlite;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sorted table of the most recent writes.
 *
 * Why ConcurrentSkipListMap: it's a lock-free, sorted map built into
 * java.util.concurrent. Lock-free means concurrent readers never block on a
 * writer here, and "sorted" means we get key-ordered iteration for free when
 * it's time to flush this to an SSTable (which is written as sorted blocks).
 * A plain HashMap would need a separate sort step at flush time and
 * explicit locking for concurrent access.
 *
 * A tombstone (deleted key) is represented as a Tombstone.INSTANCE marker
 * value rather than removing the key outright — this is standard LSM
 * practice, because the delete itself has to be remembered until compaction
 * has confirmed no older SSTable still holds the key. Physically removing
 * the entry here would let a stale value from an older, already-flushed
 * SSTable "reappear" on a subsequent read.
 */
public class MemTable {

    /** Sentinel value marking a deleted key. Distinct from any real value, including null. */
    public static final Object TOMBSTONE = new Object();

    private final ConcurrentSkipListMap<String, Object> map = new ConcurrentSkipListMap<>();
    private final AtomicLong approximateSizeBytes = new AtomicLong(0);

    public void put(String key, String value) {
        Object previous = map.put(key, value);
        approximateSizeBytes.addAndGet(estimateSize(key, value) - estimateSize(key, previous));
    }

    public void delete(String key) {
        Object previous = map.put(key, TOMBSTONE);
        approximateSizeBytes.addAndGet(estimateSize(key, TOMBSTONE) - estimateSize(key, previous));
    }

    /**
     * Look up a key. Returns:
     *   - the value, if present and not deleted
     *   - TOMBSTONE, if the key was deleted in this memtable (caller must
     *     NOT fall through to older SSTables in that case — the delete is
     *     the most recent fact about this key)
     *   - null, if this memtable has no record of the key at all (caller
     *     should check older SSTables)
     */
    public Object get(String key) {
        return map.get(key);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public long approximateSizeBytes() {
        return approximateSizeBytes.get();
    }

    /** Sorted view for flushing to an SSTable. Snapshot semantics: safe to iterate while writes continue elsewhere. */
    public Iterable<Map.Entry<String, Object>> entriesSorted() {
        return map.entrySet();
    }

    private static long estimateSize(String key, Object value) {
        if (key == null) return 0;
        long size = key.length() * 2L; // rough UTF-16 in-memory estimate
        if (value instanceof String s) {
            size += s.length() * 2L;
        }
        return size;
    }
}
