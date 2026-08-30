package kvlite.tests;

import kvlite.BloomFilter;

import java.util.ArrayList;
import java.util.List;

public class BloomFilterTest {

    /** Correctness guarantee bloom filters must never violate: no false negatives. */
    public static void testNoFalseNegatives() {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            filter.add(key);
            inserted.add(key);
        }
        for (String key : inserted) {
            TestRunner.assertTrue(filter.mightContain(key), "inserted key must never be reported absent: " + key);
        }
    }

    /** False-positive rate should land in the right ballpark of the requested target (not exact, but not wildly off). */
    public static void testFalsePositiveRateIsReasonable() {
        int n = 10_000;
        double target = 0.01;
        BloomFilter filter = new BloomFilter(n, target);
        for (int i = 0; i < n; i++) {
            filter.add("present-" + i);
        }

        int falsePositives = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            if (filter.mightContain("absent-" + i)) {
                falsePositives++;
            }
        }
        double observedRate = falsePositives / (double) trials;
        // Allow generous slack (up to 3x target) since this is a statistical
        // property, not an exact one, and we don't want a flaky test.
        TestRunner.assertTrue(observedRate < target * 3,
                "observed false-positive rate " + observedRate + " should be within ~3x of target " + target);
    }

    public static void testEmptyFilterReportsNothingPresent() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        TestRunner.assertTrue(!filter.mightContain("anything"), "empty filter should not claim any key is present");
    }
}
