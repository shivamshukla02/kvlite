package kvlite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Sorted String Table — an immutable, sorted, on-disk representation of a
 * MemTable at the moment it was flushed.
 *
 * File layout (all multi-byte integers via DataOutputStream/DataInputStream,
 * which write big-endian — a stdlib-native binary format, no custom codec
 * needed):
 *
 *   [DATA SECTION]
 *     repeated: [4B keyLen][key bytes][1B tombstone flag][4B valLen][val bytes]
 *     (valLen is 0 and no val bytes follow when tombstone flag = 1)
 *
 *   [SPARSE INDEX SECTION]
 *     repeated (every INDEX_INTERVAL-th entry): [4B keyLen][key bytes][8B offset-into-data-section]
 *
 *   [BLOOM FILTER SECTION]
 *     [4B numBits][4B numHashes][4B bitsetByteLength][bitset bytes]
 *
 *   [FOOTER] (fixed 28 bytes at the very end, so a reader can seek(length-28)
 *             and know exactly where everything else starts)
 *     [8B dataSectionOffset]     (always 0, kept explicit for clarity)
 *     [8B indexSectionOffset]
 *     [8B bloomSectionOffset]
 *     [4B entryCount]
 *
 * Why a sparse index instead of indexing every key: indexing every Nth key
 * (INDEX_INTERVAL) keeps the index small in memory while still letting a
 * read narrow down to a small byte range via binary search, then linear-scan
 * that range. This is the standard LSM/SSTable tradeoff (also how real
 * engines like LevelDB/RocksDB structure their block indexes).
 *
 * Why the bloom filter lives in the file itself rather than being rebuilt
 * on every startup: a cold-started engine can read just the footer + bloom
 * section without scanning the whole data section, keeping engine startup
 * fast even with many SSTables on disk.
 */
public class SSTable {

    private static final int INDEX_INTERVAL = 16; // index every 16th entry
    private static final int FOOTER_SIZE = 8 + 8 + 8 + 4;

    /** One entry read back from the data section during a scan or merge. */
    public static final class Entry {
        public final String key;
        public final boolean tombstone;
        public final String value; // null if tombstone

        public Entry(String key, boolean tombstone, String value) {
            this.key = key;
            this.tombstone = tombstone;
            this.value = value;
        }
    }

    private final String path;
    private long indexSectionOffset;
    private long bloomSectionOffset;
    private int entryCount;
    private List<IndexEntry> sparseIndex;
    private BloomFilter bloomFilter;

    private static final class IndexEntry {
        final String key;
        final long offset;
        IndexEntry(String key, long offset) { this.key = key; this.offset = offset; }
    }

    private SSTable(String path) {
        this.path = path;
    }

    /**
     * Write a new SSTable from a sorted stream of memtable entries.
     * Caller MUST provide entries in ascending key order (MemTable's
     * ConcurrentSkipListMap.entrySet() already iterates in sorted order).
     */
    public static void write(String path, Iterable<Map.Entry<String, Object>> sortedEntries, int expectedCount) throws IOException {
        BloomFilter bloom = new BloomFilter(Math.max(expectedCount, 1), 0.01); // 1% target false-positive rate
        List<IndexEntry> index = new ArrayList<>();

        // Write to a temp file first, then atomically rename into place.
        // This is what makes a crash mid-flush safe: a reader either sees
        // the old SSTable set (temp file not yet renamed) or the fully
        // written new file (renamed only after everything is flushed and
        // closed) — never a half-written file under the real name.
        File tempFile = new File(path + ".tmp");
        int count = 0;

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
            long offset = 0;
            for (Map.Entry<String, Object> e : sortedEntries) {
                String key = e.getKey();
                Object value = e.getValue();
                boolean tombstone = (value == MemTable.TOMBSTONE);

                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

                if (count % INDEX_INTERVAL == 0) {
                    index.add(new IndexEntry(key, offset));
                }

                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeByte(tombstone ? 1 : 0);
                long entryBytes = 4 + keyBytes.length + 1 + 4;
                if (tombstone) {
                    out.writeInt(0);
                } else {
                    byte[] valBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    out.writeInt(valBytes.length);
                    out.write(valBytes);
                    entryBytes += valBytes.length;
                }

                bloom.add(key);
                offset += entryBytes;
                count++;
            }

            long indexOffset = offset;

            for (IndexEntry ie : index) {
                byte[] keyBytes = ie.key.getBytes(StandardCharsets.UTF_8);
                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeLong(ie.offset);
            }

            long bloomOffset = indexOffset;
            for (IndexEntry ie : index) {
                bloomOffset += 4 + ie.key.getBytes(StandardCharsets.UTF_8).length + 8;
            }

            byte[] bitsetBytes = bloom.rawBits().toByteArray();
            out.writeInt(bloom.numBits());
            out.writeInt(bloom.numHashes());
            out.writeInt(bitsetBytes.length);
            out.write(bitsetBytes);

            out.writeLong(0L);
            out.writeLong(indexOffset);
            out.writeLong(bloomOffset);
            out.writeInt(count);
        }

        File finalFile = new File(path);
        if (!tempFile.renameTo(finalFile)) {
            throw new IOException("failed to atomically rename SSTable into place: " + path);
        }
    }

    /** Open an existing SSTable file for reading — loads the footer, sparse index, and bloom filter into memory. */
    public static SSTable open(String path) throws IOException {
        SSTable table = new SSTable(path);
        table.loadMetadata();
        return table;
    }

    private void loadMetadata() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long length = raf.length();
            raf.seek(length - FOOTER_SIZE);
            raf.readLong(); // dataSectionOffset, always 0, read for alignment
            this.indexSectionOffset = raf.readLong();
            this.bloomSectionOffset = raf.readLong();
            this.entryCount = raf.readInt();

            this.sparseIndex = new ArrayList<>();
            raf.seek(indexSectionOffset);
            while (raf.getFilePointer() < bloomSectionOffset) {
                int keyLen = raf.readInt();
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                long entryOffset = raf.readLong();
                sparseIndex.add(new IndexEntry(new String(keyBytes, StandardCharsets.UTF_8), entryOffset));
            }

            raf.seek(bloomSectionOffset);
            int numBits = raf.readInt();
            int numHashes = raf.readInt();
            int bitsetLen = raf.readInt();
            byte[] bitsetBytes = new byte[bitsetLen];
            raf.readFully(bitsetBytes);
            this.bloomFilter = new BloomFilter(BitSet.valueOf(bitsetBytes), numBits, numHashes);
        }
    }

    /**
     * Point lookup. Returns the Entry if found in this SSTable (which may be
     * a tombstone — caller must check .tombstone rather than treating it as
     * "not found", since a tombstone here overrides a value in an OLDER
     * SSTable), or null if this SSTable has no record of the key at all.
     */
    public Entry get(String key) throws IOException {
        if (!bloomFilter.mightContain(key)) {
            return null; // definitely not in this file — no disk read needed
        }

        long scanStart = 0;
        int lo = 0, hi = sparseIndex.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int cmp = sparseIndex.get(mid).key.compareTo(key);
            if (cmp <= 0) {
                scanStart = sparseIndex.get(mid).offset;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            raf.seek(scanStart);
            while (raf.getFilePointer() < indexSectionOffset) {
                int keyLen = raf.readInt();
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String currentKey = new String(keyBytes, StandardCharsets.UTF_8);
                boolean tombstone = raf.readByte() == 1;
                int valLen = raf.readInt();

                int cmp = currentKey.compareTo(key);
                if (cmp == 0) {
                    if (tombstone) {
                        return new Entry(currentKey, true, null);
                    }
                    byte[] valBytes = new byte[valLen];
                    raf.readFully(valBytes);
                    return new Entry(currentKey, false, new String(valBytes, StandardCharsets.UTF_8));
                } else if (cmp > 0) {
                    return null;
                } else {
                    if (!tombstone) {
                        raf.skipBytes(valLen);
                    }
                }
            }
        }
        return null;
    }

    /** Full sorted scan of every entry — used by compaction to merge SSTables. */
    public List<Entry> scanAll() throws IOException {
        List<Entry> entries = new ArrayList<>(entryCount);
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            raf.seek(0);
            while (raf.getFilePointer() < indexSectionOffset) {
                int keyLen = raf.readInt();
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                boolean tombstone = raf.readByte() == 1;
                int valLen = raf.readInt();
                String value = null;
                if (!tombstone) {
                    byte[] valBytes = new byte[valLen];
                    raf.readFully(valBytes);
                    value = new String(valBytes, StandardCharsets.UTF_8);
                }
                entries.add(new Entry(key, tombstone, value));
            }
        }
        return entries;
    }

    public String path() {
        return path;
    }

    public int entryCount() {
        return entryCount;
    }

    public void delete() {
        new File(path).delete();
    }
}
