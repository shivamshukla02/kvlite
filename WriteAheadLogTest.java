package kvlite.tests;

import kvlite.WriteAheadLog;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class WriteAheadLogTest {

    private static String tempPath() throws Exception {
        File f = File.createTempFile("kvlite-wal-test", ".log");
        f.deleteOnExit();
        f.delete(); // WAL creates it fresh
        return f.getAbsolutePath();
    }

    /** Basic round trip: write records, replay, confirm they come back in order and intact. */
    public static void testRoundTrip() throws Exception {
        String path = tempPath();
        WriteAheadLog wal = new WriteAheadLog(path);
        wal.appendPut("alpha", "1");
        wal.appendPut("beta", "2");
        wal.appendDelete("alpha");
        wal.close();

        List<WriteAheadLog.Record> records = new ArrayList<>();
        WriteAheadLog.replayFromPath(path, new WriteAheadLog.ReplayListener() {
            public void onRecord(WriteAheadLog.Record r) { records.add(r); }
            public void onCorruption(String reason, long offset) {
                throw new AssertionError("unexpected corruption: " + reason + " at " + offset);
            }
        });

        TestRunner.assertEquals(3, records.size(), "should replay 3 records");
        TestRunner.assertEquals(WriteAheadLog.OP_PUT, records.get(0).op, "record 0 op");
        TestRunner.assertEquals("alpha", records.get(0).key, "record 0 key");
        TestRunner.assertEquals("1", records.get(0).value, "record 0 value");
        TestRunner.assertEquals(WriteAheadLog.OP_DELETE, records.get(2).op, "record 2 op");
        TestRunner.assertNull(records.get(2).value, "delete record should have null value");
    }

    /** Replaying a WAL that was never created (fresh store) should yield zero records, no error. */
    public static void testReplayNonexistentFile() throws Exception {
        String path = tempPath(); // deleted, never created
        List<WriteAheadLog.Record> records = new ArrayList<>();
        WriteAheadLog.replayFromPath(path, new WriteAheadLog.ReplayListener() {
            public void onRecord(WriteAheadLog.Record r) { records.add(r); }
            public void onCorruption(String reason, long offset) {
                throw new AssertionError("should not report corruption on a missing file");
            }
        });
        TestRunner.assertEquals(0, records.size(), "fresh store should replay zero records");
    }

    /**
     * Corrupt one byte inside a valid record's body, confirm replay detects
     * the checksum mismatch and stops rather than returning garbage data.
     */
    public static void testCorruptedRecordDetected() throws Exception {
        String path = tempPath();
        WriteAheadLog wal = new WriteAheadLog(path);
        wal.appendPut("good", "value");
        wal.close();

        // Flip a byte inside the record body (offset 6 lands inside the key/value area).
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(6);
            int b = raf.read();
            raf.seek(6);
            raf.write(b ^ 0xFF);
        }

        List<WriteAheadLog.Record> records = new ArrayList<>();
        List<String> corruptions = new ArrayList<>();
        WriteAheadLog.replayFromPath(path, new WriteAheadLog.ReplayListener() {
            public void onRecord(WriteAheadLog.Record r) { records.add(r); }
            public void onCorruption(String reason, long offset) { corruptions.add(reason); }
        });

        TestRunner.assertEquals(0, records.size(), "corrupted record must not be returned as valid");
        TestRunner.assertTrue(corruptions.size() == 1, "should report exactly one corruption event");
        TestRunner.assertTrue(corruptions.get(0).contains("checksum"), "should identify it as a checksum mismatch");
    }

    /**
     * Simulate a crash mid-write: truncate the file so the last record's
     * tail (checksum bytes) is missing. Replay must recover everything
     * before the truncated record and stop cleanly, not throw.
     */
    public static void testTruncatedTailRecordSkipped() throws Exception {
        String path = tempPath();
        WriteAheadLog wal = new WriteAheadLog(path);
        wal.appendPut("complete", "yes");
        wal.close();

        long fullLength;
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            fullLength = raf.length();
        }

        // Append a second record, then truncate the file mid-way through it
        // to simulate a crash during the write.
        wal = new WriteAheadLog(path);
        wal.appendPut("incomplete-write-victim", "this-should-not-fully-land");
        wal.close();

        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            long currentLength = raf.length();
            long extraBytesWritten = currentLength - fullLength;
            raf.setLength(fullLength + (extraBytesWritten / 2)); // chop it in half
        }

        List<WriteAheadLog.Record> records = new ArrayList<>();
        List<String> corruptions = new ArrayList<>();
        WriteAheadLog.replayFromPath(path, new WriteAheadLog.ReplayListener() {
            public void onRecord(WriteAheadLog.Record r) { records.add(r); }
            public void onCorruption(String reason, long offset) { corruptions.add(reason); }
        });

        TestRunner.assertEquals(1, records.size(), "only the complete record should survive replay");
        TestRunner.assertEquals("complete", records.get(0).key, "the surviving record should be the first one");
        TestRunner.assertTrue(corruptions.size() == 1, "the truncated record should be reported as corruption, not silently dropped");
    }

    /** Truncate the WAL (as would happen after a successful SSTable flush), confirm it's empty. */
    public static void testTruncate() throws Exception {
        String path = tempPath();
        WriteAheadLog wal = new WriteAheadLog(path);
        wal.appendPut("k", "v");
        wal.truncate();
        wal.close();

        List<WriteAheadLog.Record> records = new ArrayList<>();
        WriteAheadLog.replayFromPath(path, new WriteAheadLog.ReplayListener() {
            public void onRecord(WriteAheadLog.Record r) { records.add(r); }
            public void onCorruption(String reason, long offset) {
                throw new AssertionError("truncated WAL should not report corruption");
            }
        });
        TestRunner.assertEquals(0, records.size(), "truncated WAL should replay to zero records");
    }
}
