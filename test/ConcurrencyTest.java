package kvlite.tests;

import kvlite.LSMEngine;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrencyTest {

    private static String tempDir() throws Exception {
        File dir = Files.createTempDirectory("kvlite-concurrency-test").toFile();
        dir.deleteOnExit();
        return dir.getAbsolutePath();
    }

    /**
     * N writer threads each write to their own disjoint key range, forcing
     * many flushes (and therefore compactions) to happen WHILE writes and
     * reads continue concurrently on other threads. A background reader
     * thread continuously reads keys and must never see an exception, a
     * torn value, or a value that doesn't match what was actually written.
     */
    public static void testConcurrentWritersAndReaders() throws Exception {
        String dir = tempDir();
        LSMEngine engine = new LSMEngine(dir);

        int writerThreads = 4;
        int writesPerThread = 500;
        ExecutorService writerPool = Executors.newFixedThreadPool(writerThreads);
        AtomicReference<Exception> writerError = new AtomicReference<>();

        CountDownLatch writersDone = new CountDownLatch(writerThreads);
        for (int t = 0; t < writerThreads; t++) {
            final int threadId = t;
            writerPool.submit(() -> {
                try {
                    for (int i = 0; i < writesPerThread; i++) {
                        String key = "writer-" + threadId + "-key-" + i;
                        String value = "value-" + threadId + "-" + i;
                        engine.put(key, value);
                    }
                } catch (Exception e) {
                    writerError.compareAndSet(null, e);
                } finally {
                    writersDone.countDown();
                }
            });
        }

        // Background reader: continuously reads keys already known to have
        // been written, and must always get back the CORRECT value or null
        // (if not written yet) — never a corrupted/wrong value, never a
        // thrown exception.
        AtomicInteger readErrors = new AtomicInteger(0);
        AtomicReference<Exception> readerException = new AtomicReference<>();
        ExecutorService readerPool = Executors.newFixedThreadPool(2);
        for (int r = 0; r < 2; r++) {
            readerPool.submit(() -> {
                try {
                    while (writersDone.getCount() > 0) {
                        for (int t = 0; t < writerThreads; t++) {
                            for (int i = 0; i < writesPerThread; i += 37) { // sample, not every key, to keep this fast
                                String key = "writer-" + t + "-key-" + i;
                                String expected = "value-" + t + "-" + i;
                                String actual = engine.get(key);
                                // actual is either null (not written yet) or MUST match exactly.
                                if (actual != null && !actual.equals(expected)) {
                                    readErrors.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    readerException.compareAndSet(null, e);
                }
            });
        }

        writersDone.await(60, TimeUnit.SECONDS);
        writerPool.shutdown();
        readerPool.shutdown();
        readerPool.awaitTermination(10, TimeUnit.SECONDS);

        if (writerError.get() != null) {
            throw new AssertionError("writer thread threw an exception: " + writerError.get(), writerError.get());
        }
        if (readerException.get() != null) {
            throw new AssertionError("reader thread threw an exception: " + readerException.get(), readerException.get());
        }
        TestRunner.assertEquals(0, readErrors.get(), "readers must never observe a value that doesn't match what was written");

        // Final correctness pass, single-threaded, now that writers are done:
        // every single key written must be readable with the correct value.
        for (int t = 0; t < writerThreads; t++) {
            for (int i = 0; i < writesPerThread; i++) {
                String key = "writer-" + t + "-key-" + i;
                String expected = "value-" + t + "-" + i;
                TestRunner.assertEquals(expected, engine.get(key), "final read after all writers finished");
            }
        }

        engine.close();
    }
}
