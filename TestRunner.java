package kvlite.tests;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny test runner, stdlib only. No JUnit, no TestNG.
 *
 * Why hand-rolled instead of importing a test framework: the JDK ships no
 * test framework, so a dev-only test dependency would technically be
 * permitted under the hackathon's one grey-area rule — but this sidesteps
 * that disclosure entirely and keeps the submission unambiguously zero-dep.
 *
 * Convention: any public static void method starting with "test" in a
 * registered class is run. A test passes if it returns normally, fails if
 * it throws (AssertionError from assert(), or any other exception).
 */
public class TestRunner {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void assertEquals(Object expected, Object actual, String message) {
        boolean equal = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(message + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " — expected null but was <" + value + ">");
        }
    }

    public static void run(Class<?> testClass) {
        for (Method method : testClass.getMethods()) {
            if (method.getName().startsWith("test") && method.getParameterCount() == 0) {
                try {
                    method.invoke(null);
                    passed++;
                    System.out.println("  PASS  " + testClass.getSimpleName() + "." + method.getName());
                } catch (Exception e) {
                    failed++;
                    Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                    String msg = testClass.getSimpleName() + "." + method.getName() + " — " + cause.getMessage();
                    failures.add(msg);
                    System.out.println("  FAIL  " + msg);
                }
            }
        }
    }

    public static void summarize() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("kvlite test suite");
        System.out.println("==================");
        run(Class.forName("kvlite.tests.WriteAheadLogTest"));
        run(Class.forName("kvlite.tests.LSMEngineTest"));
        run(Class.forName("kvlite.tests.BloomFilterTest"));
        run(Class.forName("kvlite.tests.ConcurrencyTest"));
        summarize();
    }
}
