package kvlite.tests;

import kvlite.LSMEngine;

import java.io.File;
import java.nio.file.Files;

public class LSMEngineTest {

    private static String tempDir() throws Exception {
        File dir = Files.createTempDirectory("kvlite-engine-test").toFile();
        dir.deleteOnExit();
        return dir.getAbsolutePath();
    }

    public static void testPutGet() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        engine.put("name", "shivam");
        TestRunner.assertEquals("shivam", engine.get("name"), "put then get should return the value");
        engine.close();
    }

    public static void testGetMissingKey() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        TestRunner.assertNull(engine.get("nope"), "missing key should return null");
        engine.close();
    }

    public static void testDelete() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        engine.put("k", "v");
        engine.delete("k");
        TestRunner.assertNull(engine.get("k"), "deleted key should return null");
        engine.close();
    }

    public static void testOverwrite() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        engine.put("k", "first");
        engine.put("k", "second");
        TestRunner.assertEquals("second", engine.get("k"), "overwrite should return the latest value");
        engine.close();
    }

    /** Core durability guarantee: write, close (simulating shutdown), reopen against the same dir, confirm data survives. */
    public static void testRestartRecoversAllData() throws Exception {
        String dir = tempDir();

        LSMEngine engine = new LSMEngine(dir);
        engine.put("alpha", "1");
        engine.put("beta", "2");
        engine.put("gamma", "3");
        engine.delete("beta");
        engine.put("gamma", "3-updated");
        engine.close();

        LSMEngine reopened = new LSMEngine(dir);
        TestRunner.assertEquals("1", reopened.get("alpha"), "alpha should survive restart");
        TestRunner.assertNull(reopened.get("beta"), "deleted beta should stay deleted after restart");
        TestRunner.assertEquals("3-updated", reopened.get("gamma"), "gamma should reflect the latest write, not the first one");
        reopened.close();
    }

    public static void testEmptyKeyAllowed() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        engine.put("", "empty-key-value");
        TestRunner.assertEquals("empty-key-value", engine.get(""), "empty string key should be a valid key");
        engine.close();
    }

    public static void testNullKeyRejected() throws Exception {
        LSMEngine engine = new LSMEngine(tempDir());
        boolean threw = false;
        try {
            engine.put(null, "value");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        TestRunner.assertTrue(threw, "null key must be rejected explicitly, not silently accepted");
        engine.close();
    }

    /** Explicit flush: data must be readable from the SSTable after the memtable is cleared. */
    public static void testExplicitFlushThenReadFromSSTable() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);
        engine.put("flushed-key", "flushed-value");
        engine.forceFlush();
        TestRunner.assertTrue(engine.sstableCount() == 1, "forceFlush should produce exactly one SSTable");
        TestRunner.assertEquals("flushed-value", engine.get("flushed-key"), "value must still be readable after flush, now from the SSTable");
        engine.close();
    }

    /** Restart after a flush: WAL was truncated, so recovery must come from the SSTable, not WAL replay. */
    public static void testRestartAfterFlushReadsFromSSTable() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);
        engine.put("k1", "v1");
        engine.forceFlush();
        engine.close();

        LSMEngine reopened = new LSMEngine(dir);
        TestRunner.assertEquals("v1", reopened.get("k1"), "value flushed before restart must be readable from the SSTable after restart");
        reopened.close();
    }

    /** A newer SSTable's value must win over an older SSTable's value for the same key. */
    public static void testNewerSSTableWinsOverOlder() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);
        engine.put("k", "old-value");
        engine.forceFlush(); // SSTable 0: k -> old-value
        engine.put("k", "new-value");
        engine.forceFlush(); // SSTable 1 (newer): k -> new-value
        TestRunner.assertEquals("new-value", engine.get("k"), "the newer SSTable's value should win");
        engine.close();
    }

    /** A tombstone in a newer SSTable must shadow a value in an older SSTable. */
    public static void testTombstoneInNewerSSTableShadowsOlderValue() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);
        engine.put("k", "value");
        engine.forceFlush();
        engine.delete("k");
        engine.forceFlush();
        TestRunner.assertNull(engine.get("k"), "tombstone in the newer SSTable must shadow the older value");
        engine.close();
    }

    /** Compaction must merge multiple SSTables into one and preserve correct (latest, non-deleted) data. */
    public static void testCompactionPreservesCorrectData() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);

        engine.put("a", "1");
        engine.forceFlush();
        engine.put("b", "2");
        engine.forceFlush();
        engine.put("a", "1-updated"); // overwrite from an even newer write
        engine.forceFlush();
        engine.put("c", "3");
        engine.delete("c"); // never flushed as its own file, but let's flush it deleted
        engine.forceFlush();

        int beforeCompaction = engine.sstableCount();
        TestRunner.assertTrue(beforeCompaction == 4, "expected 4 SSTables before compaction, got " + beforeCompaction);

        engine.forceCompact();
        TestRunner.assertEquals(1, engine.sstableCount(), "compaction should merge everything into a single SSTable");

        TestRunner.assertEquals("1-updated", engine.get("a"), "compacted result should keep the newest value for 'a'");
        TestRunner.assertEquals("2", engine.get("b"), "compacted result should keep 'b'");
        TestRunner.assertNull(engine.get("c"), "compacted result should have dropped 'c' entirely — it was tombstoned");

        engine.close();
    }
}
