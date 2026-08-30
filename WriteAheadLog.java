package kvlite;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log.
 *
 * Every mutation (PUT or DELETE) is appended here BEFORE it touches the
 * in-memory MemTable. If the process crashes, replay() reconstructs the
 * MemTable state from this file alone.
 *
 * Record layout on disk (all integers big-endian, via ByteBuffer default):
 *
 *   [4 bytes]  total record length (everything after this field)
 *   [1 byte]   op code: 0 = PUT, 1 = DELETE
 *   [4 bytes]  key length
 *   [N bytes]  key bytes (UTF-8)
 *   [4 bytes]  value length (0 for DELETE)
 *   [M bytes]  value bytes (UTF-8, absent for DELETE)
 *   [8 bytes]  CRC32 checksum of everything from op code through value bytes
 *
 * Why CRC32 and not something cryptographic: this is corruption detection
 * (torn writes from a crash), not a security boundary. java.util.zip.CRC32
 * is built into the JDK and cheap to compute per record.
 *
 * Why length-prefixed records instead of a delimiter: keys/values can contain
 * any byte sequence, so a delimiter (like '\n') would be ambiguous. Length
 * prefixes make parsing unambiguous and let us detect truncated records
 * (a partial write at the tail from a crash) by checking if the declared
 * length actually fits in the remaining file.
 */
public class WriteAheadLog {

    public static final byte OP_PUT = 0;
    public static final byte OP_DELETE = 1;

    /** One decoded WAL record, produced during replay. */
    public static final class Record {
        public final byte op;
        public final String key;
        public final String value; // null for DELETE

        public Record(byte op, String key, String value) {
            this.op = op;
            this.key = key;
            this.value = value;
        }
    }

    /** Called for each valid record found during replay, and for corruption events. */
    public interface ReplayListener {
        void onRecord(Record record);
        void onCorruption(String reason, long offset);
    }

    private final RandomAccessFile file;

    public WriteAheadLog(String path) throws IOException {
        // "rw" = read/write, creates the file if it doesn't exist.
        this.file = new RandomAccessFile(path, "rw");
        // Start appending from the end of whatever's already there (important
        // for reopening a WAL after a restart, before/without truncation).
        this.file.seek(this.file.length());
    }

    /**
     * Append a PUT record and force it to disk before returning.
     *
     * We fsync (FileChannel.force) on every call here for correctness-first
     * simplicity. The group-commit batching optimization (buffer several
     * writes, fsync once) is a documented stretch-goal, not implemented yet
     * — see STDLIB.md / README limitations once that's decided.
     */
    public synchronized void appendPut(String key, String value) throws IOException {
        appendRecord(OP_PUT, key, value);
    }

    public synchronized void appendDelete(String key) throws IOException {
        appendRecord(OP_DELETE, key, null);
    }

    private void appendRecord(byte op, String key, String value) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (value == null) ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);

        // Build the portion of the record that gets checksummed: op + key + value.
        ByteBuffer body = ByteBuffer.allocate(1 + 4 + keyBytes.length + 4 + valueBytes.length);
        body.put(op);
        body.putInt(keyBytes.length);
        body.put(keyBytes);
        body.putInt(valueBytes.length);
        body.put(valueBytes);
        body.flip();

        byte[] bodyBytes = new byte[body.remaining()];
        body.get(bodyBytes);

        CRC32 crc = new CRC32();
        crc.update(bodyBytes);
        long checksum = crc.getValue();

        // Full record = [length][body][checksum]
        ByteBuffer record = ByteBuffer.allocate(4 + bodyBytes.length + 8);
        record.putInt(bodyBytes.length);
        record.put(bodyBytes);
        record.putLong(checksum);
        record.flip();

        byte[] recordBytes = new byte[record.remaining()];
        record.get(recordBytes);

        file.write(recordBytes);
        // fsync: force the OS to actually flush to durable storage, not just
        // its page cache. This is what makes the write survive a crash.
        file.getFD().sync();
    }

    /**
     * Replay every valid record in the log, in order, calling back into
     * listener. Used on startup to rebuild the MemTable.
     *
     * Design choice: a corrupted record (bad CRC or truncated at EOF) is
     * treated as "we've hit the crash tail" — log it and stop, rather than
     * throwing. This is a deliberate availability-over-strictness choice:
     * a crash mid-write should not prevent the store from starting up with
     * everything durably written before the crash.
     *
     * Implemented as a static method that reopens the file by path (rather
     * than an instance method reusing the writer's handle) so it can be
     * called cleanly during LSMEngine startup, before or independent of
     * opening the writer handle for appends.
     */
    public static void replayFromPath(String path, ReplayListener listener) throws IOException {
        java.io.File f = new java.io.File(path);
        if (!f.exists()) {
            return; // fresh store, nothing to replay
        }
        try (RandomAccessFile reader = new RandomAccessFile(f, "r")) {
            long length = reader.length();
            long offset = 0;

            while (offset < length) {
                // Need at least 4 bytes for the length prefix.
                if (offset + 4 > length) {
                    listener.onCorruption("truncated length prefix", offset);
                    break;
                }
                reader.seek(offset);
                int bodyLen = reader.readInt();

                if (bodyLen < 0 || offset + 4 + bodyLen + 8 > length) {
                    listener.onCorruption("truncated record body", offset);
                    break;
                }

                byte[] bodyBytes = new byte[bodyLen];
                reader.readFully(bodyBytes);
                long storedChecksum = reader.readLong();

                CRC32 crc = new CRC32();
                crc.update(bodyBytes);
                long actualChecksum = crc.getValue();

                if (actualChecksum != storedChecksum) {
                    listener.onCorruption("checksum mismatch", offset);
                    break; // stop at first corruption: everything after is
                           // presumed to be from an interrupted write
                }

                ByteBuffer body = ByteBuffer.wrap(bodyBytes);
                byte op = body.get();
                int keyLen = body.getInt();
                byte[] keyBytes = new byte[keyLen];
                body.get(keyBytes);
                int valueLen = body.getInt();
                byte[] valueBytes = new byte[valueLen];
                body.get(valueBytes);

                String key = new String(keyBytes, StandardCharsets.UTF_8);
                String value = (op == OP_DELETE) ? null : new String(valueBytes, StandardCharsets.UTF_8);

                listener.onRecord(new Record(op, key, value));

                offset = offset + 4 + bodyLen + 8;
            }
        }
    }

    /** Truncate the WAL back to empty — called after a successful SSTable flush. */
    public synchronized void truncate() throws IOException {
        file.setLength(0);
        file.seek(0);
    }

    public synchronized void close() throws IOException {
        file.close();
    }
}
