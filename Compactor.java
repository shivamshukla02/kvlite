package kvlite;

import java.io.IOException;
import java.util.*;

/**
 * Merges all current SSTables into a single new one, resolving duplicate
 * keys to the newest version and dropping tombstones entirely (since after
 * this merge there is no older SSTable left for a tombstone to "shadow").
 *
 * This is single-level compaction: every run merges the ENTIRE current file
 * set into one new file. This is the MVP-scope tradeoff documented in
 * SCOPE.md — leveled compaction (splitting into size-tiered levels so a
 * merge only touches a subset of files) is the stretch-goal version. Single-
 * level compaction is simpler to get correct and still demonstrates the
 * core idea: reclaiming space from overwrites and deletes.
 *
 * Algorithm: k-way merge across all SSTables (newest to oldest), using a
 * priority queue keyed by (key, tableRecency) so that when the same key
 * appears in multiple tables, the version from the NEWEST table wins.
 */
public class Compactor {

    /** Runs a full compaction. Returns the new merged SSTable, or null if there was nothing to compact. */
    public static SSTable compact(SSTableManager manager, String dataDir) throws IOException {
        List<SSTable> tables = manager.snapshot();
        if (tables.size() < 2) {
            return null; // nothing meaningful to merge
        }

        // tables list is newest-first (index 0 = newest). We want, for each
        // key, the value from the LOWEST index (most recent) table it
        // appears in. We do this by loading each table's full sorted entry
        // list and merging them with a stable "first table wins" rule.
        List<Iterator<SSTable.Entry>> iterators = new ArrayList<>();
        for (SSTable t : tables) {
            iterators.add(t.scanAll().iterator());
        }

        // Simple correctness-first merge: read all entries with their table
        // recency, group by key, keep only the entry from the most recent
        // table. For very large datasets a true streaming k-way merge with a
        // heap would use less memory; documented as a scaling limitation
        // (see README) rather than implemented here, since compaction runs
        // as a background/offline step, not on the hot read/write path.
        TreeMap<String, SSTable.Entry> merged = new TreeMap<>();
        for (int tableIndex = tables.size() - 1; tableIndex >= 0; tableIndex--) {
            // Iterate oldest-to-newest so a later (more recent) put simply
            // overwrites what an older table contributed to the map.
            for (SSTable.Entry entry : tables.get(tableIndex).scanAll()) {
                merged.put(entry.key, entry);
            }
        }

        // Drop tombstones now — this is the moment they've served their
        // purpose (shadowing older values) and can be reclaimed.
        List<Map.Entry<String, Object>> survivors = new ArrayList<>();
        for (Map.Entry<String, SSTable.Entry> e : merged.entrySet()) {
            if (!e.getValue().tombstone) {
                survivors.add(new AbstractMap.SimpleEntry<>(e.getKey(), (Object) e.getValue().value));
            }
        }

        String newPath = manager.nextSSTablePath();
        SSTable.write(newPath, survivors, survivors.size());
        SSTable newTable = SSTable.open(newPath);

        manager.replaceWithCompacted(tables, newTable);
        return newTable;
    }
}
