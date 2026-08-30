package kvlite;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-line entry point.
 *
 * Exit codes:
 *   0 = success
 *   1 = key not found (get)
 *   2 = usage error
 *   3 = corruption detected during recover-test verification
 *
 * Data directory defaults to ./kvlite-data, overridable with KVLITE_DATA_DIR
 * so the recover-test command and benchmarks can point at a scratch dir
 * without clobbering real data.
 */
public class Main {

    private static String dataDir() {
        String env = System.getenv("KVLITE_DATA_DIR");
        return (env != null) ? env : "kvlite-data";
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(2);
        }

        String command = args[0];
        try {
            switch (command) {
                case "put" -> cmdPut(args);
                case "get" -> cmdGet(args);
                case "del" -> cmdDelete(args);
                case "scan" -> cmdScan(args);
                case "bench" -> cmdBench(args);
                case "recover-test" -> cmdRecoverTest(args);
                default -> {
                    System.err.println("unknown command: " + command);
                    printUsage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.err.println("""
                usage:
                  kvlite put <key> <value>
                  kvlite get <key>
                  kvlite del <key>
                  kvlite scan <from> <to>
                  kvlite bench --writes N [--threads N]
                  kvlite recover-test
                """);
    }

    private static void cmdPut(String[] args) throws IOException {
        if (args.length != 3) { printUsage(); System.exit(2); return; }
        try (var engine = new EngineHandle(dataDir())) {
            engine.engine.put(args[1], args[2]);
        }
        System.out.println("OK");
    }

    private static void cmdGet(String[] args) throws IOException {
        if (args.length != 2) { printUsage(); System.exit(2); return; }
        try (var engine = new EngineHandle(dataDir())) {
            String value = engine.engine.get(args[1]);
            if (value == null) {
                System.exit(1);
            } else {
                System.out.println(value);
            }
        }
    }

    private static void cmdDelete(String[] args) throws IOException {
        if (args.length != 2) { printUsage(); System.exit(2); return; }
        try (var engine = new EngineHandle(dataDir())) {
            engine.engine.delete(args[1]);
        }
        System.out.println("OK");
    }

    /**
     * Range scan. NOTE: the current engine has no native range-scan API
     * (Phase 3 core scope was point lookups); this CLI command walks the
     * in-memory MemTable's sorted key set plus flushed SSTables' full scans
     * and merges them client-side. Documented as a straightforward but not
     * performance-optimized implementation — see README limitations.
     */
    private static void cmdScan(String[] args) throws IOException {
        if (args.length != 3) { printUsage(); System.exit(2); return; }
        String from = args[1], to = args[2];
        try (var handle = new EngineHandle(dataDir())) {
            java.util.TreeMap<String, String> merged = new java.util.TreeMap<>();
            for (SSTable table : handle.sstableManagerSnapshot()) {
                for (SSTable.Entry e : table.scanAll()) {
                    if (e.key.compareTo(from) >= 0 && e.key.compareTo(to) <= 0) {
                        if (e.tombstone) merged.remove(e.key);
                        else merged.put(e.key, e.value);
                    }
                }
            }
            for (var entry : merged.entrySet()) {
                System.out.println(entry.getKey() + " = " + entry.getValue());
            }
        }
    }

    private static void cmdBench(String[] args) throws IOException {
        int writes = 100_000;
        int threads = 1;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--writes")) writes = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--threads")) threads = Integer.parseInt(args[i + 1]);
        }

        String benchDir = dataDir() + "-bench";
        deleteRecursive(new java.io.File(benchDir));

        try (var handle = new EngineHandle(benchDir)) {
            final LSMEngine engine = handle.engine;
            final int writesPerThread = writes / threads;
            final AtomicLong totalLatencyNanos = new AtomicLong(0);
            final long[] p99Sample = new long[writesPerThread]; // per-thread-0 sample for p99 estimate

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long start = System.nanoTime();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    for (int i = 0; i < writesPerThread; i++) {
                        long writeStart = System.nanoTime();
                        try {
                            engine.put("bench-key-" + threadId + "-" + i, "bench-value-" + i);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        long elapsed = System.nanoTime() - writeStart;
                        totalLatencyNanos.addAndGet(elapsed);
                        if (threadId == 0) p99Sample[i] = elapsed;
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long durationNanos = System.nanoTime() - start;
            double durationSeconds = durationNanos / 1_000_000_000.0;
            int totalWrites = writesPerThread * threads;
            double throughput = totalWrites / durationSeconds;

            java.util.Arrays.sort(p99Sample);
            long p50 = p99Sample[p99Sample.length / 2];
            long p99 = p99Sample[(int) (p99Sample.length * 0.99)];

            System.out.println("=== kvlite benchmark ===");
            System.out.println("writes:          " + totalWrites);
            System.out.println("threads:         " + threads);
            System.out.println("duration:        " + String.format("%.2f", durationSeconds) + "s");
            System.out.println("throughput:      " + String.format("%.0f", throughput) + " writes/sec");
            System.out.println("p50 latency:     " + (p50 / 1000) + " µs (thread 0 sample)");
            System.out.println("p99 latency:     " + (p99 / 1000) + " µs (thread 0 sample)");
        }
    }

    /**
     * Kills itself mid-write (via a hard process exit right after an fsync'd
     * WAL append but before the CLI would normally exit cleanly), to prove
     * crash recovery actually works — not simulated, an actual abrupt
     * process termination. Run this command twice:
     *   1st run: writes data, then Runtime.halt()s (no clean shutdown at all)
     *   2nd run: pass --verify, reopens the same dir, confirms data survived
     */
    private static void cmdRecoverTest(String[] args) throws IOException {
        String testDir = dataDir() + "-recover-test";
        boolean verify = args.length > 1 && args[1].equals("--verify");

        if (!verify) {
            deleteRecursive(new java.io.File(testDir));
            LSMEngine engine = new LSMEngine(testDir);
            engine.put("crash-key-1", "crash-value-1");
            engine.put("crash-key-2", "crash-value-2");
            engine.put("crash-key-3", "crash-value-3");
            System.out.println("wrote 3 keys, now killing the process abruptly (Runtime.halt, no clean shutdown)...");
            System.out.flush();
            Runtime.getRuntime().halt(0); // hard kill, bypasses shutdown hooks and finally blocks entirely
        } else {
            LSMEngine engine = new LSMEngine(testDir);
            boolean ok = true;
            ok &= "crash-value-1".equals(engine.get("crash-key-1"));
            ok &= "crash-value-2".equals(engine.get("crash-key-2"));
            ok &= "crash-value-3".equals(engine.get("crash-key-3"));
            engine.close();
            if (ok) {
                System.out.println("RECOVERY OK — all 3 keys survived the abrupt process kill");
            } else {
                System.out.println("RECOVERY FAILED — data loss detected after crash");
                System.exit(3);
            }
        }
    }

    private static void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** Small AutoCloseable wrapper so CLI commands can use try-with-resources to guarantee engine.close(). */
    private static final class EngineHandle implements AutoCloseable {
        final LSMEngine engine;
        EngineHandle(String dir) throws IOException {
            this.engine = new LSMEngine(dir);
        }
        /** Flushes the memtable first so scan (which only reads SSTables) sees the latest writes too. */
        Iterable<SSTable> sstableManagerSnapshot() throws IOException {
            engine.forceFlush();
            return engine.sstableSnapshotForScan();
        }
        public void close() throws IOException {
            engine.close();
        }
    }
}
