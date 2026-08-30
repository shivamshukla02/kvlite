# STDLIB.md

## Dependency philosophy

Every row below is a package this project would normally reach for, replaced with a specific JDK standard-library feature that was actually used in the shipped code (verified: `grep` across every `.java` file in `src/` and `tests/` finds no import outside `java.*` and `kvlite.*`). Where the stdlib version is rougher or slower than the package it replaces, that's stated in the Trade-off column rather than hidden.

## Substitutions actually used in this codebase

| # | Normally used | Standard-library replacement | Purpose | Trade-off |
|---|---|---|---|---|
| 1 | `com.google.common.hash.BloomFilter` (Guava) | `java.util.BitSet` + hand-rolled hashing (`BloomFilter.java`) | probabilistic membership test per SSTable, skips disk reads for keys that provably aren't present | **Package Killer candidate.** Guava's BloomFilter has millions of weekly downloads. Sizing formulas (optimal bits/hashes) and the Kirsch-Mitzenmacher double-hashing technique are implemented directly rather than imported — see in-code doc comment in `BloomFilter.java` for the derivation. |
| 2 | `com.fasterxml.jackson` / `com.google.gson` | Hand-rolled binary encoding via `DataOutputStream`/`DataInputStream`/`RandomAccessFile` (`WriteAheadLog.java`, `SSTable.java`) | on-disk WAL record and SSTable file serialization | Binary format has no schema versioning — a real production system would need to add that. Simpler and faster than JSON for this fixed, internal format. |
| 3 | `org.rocksdb` / `org.mapdb` / any embedded-DB library | The entire `kvlite` package | persistent embedded key-value storage | This is the point of the submission. |
| 4 | `com.google.common.util.concurrent` (Guava concurrency utilities) | `java.util.concurrent.ConcurrentSkipListMap`, `java.util.concurrent.locks.ReentrantReadWriteLock`, `java.util.concurrent.atomic.AtomicInteger`/`AtomicLong` | lock-free sorted in-memory index (MemTable), coordinating the SSTable file-set swap during flush/compaction | JDK concurrency primitives were fully sufficient; no Guava utility classes were needed anywhere in the concurrency model. |
| 5 | `commons-cli` / `picocli` | Hand-rolled `String[] args` parsing (`Main.java`, plain `switch`) | CLI argument parsing across 6 subcommands | A dedicated parser library is unjustified at this flag surface; the trade-off is no auto-generated `--help` formatting, which is written by hand instead. |
| 6 | `slf4j` + `logback`/`log4j` | `System.err.println` (used in `LSMEngine`'s corruption-reporting path) | reporting recoverable errors (corrupted WAL record skipped) | No log levels, no structured logging, no appenders — acceptable for a CLI tool with one class of warning to report. |
| 7 | `org.apache.commons-codec` (CRC/checksum utilities) | `java.util.zip.CRC32` | WAL record integrity checking to detect torn writes from a crash | Direct fit — this is exactly what `java.util.zip.CRC32` is for. Explicitly documented in code as corruption *detection*, not a security guarantee. |
| 8 | `junit` / `testng` | Hand-written test methods (`assert*` helpers) run via a small reflection-based `TestRunner.main()` (`tests/kvlite/tests/TestRunner.java`) | the entire 21-test suite | Sidesteps the hackathon's one disclosed-dev-dependency grey area entirely — no test framework is used at all, so there's nothing to disclose. Trade-off: no parameterized tests, no rich assertion library, no test discovery beyond a reflection scan for `test*` methods. |
| 9 | Guava `Files`/`MoreFiles` (atomic file operations) | `java.io.File.renameTo()` for atomic swap, `RandomAccessFile.getFD().sync()` for fsync | atomic SSTable publication (write-to-temp-then-rename) and durable WAL writes | `java.io`/`java.nio` file APIs cover both needs natively; no helper library required. |
| 10 | `com.google.common.hash.Hashing` (Murmur3 etc.) | Hand-rolled FNV-1a-style hash combined with `String.hashCode()` for the bloom filter's k hash positions | generating the two independent-enough hash values the bloom filter needs | Documented directly in `BloomFilter.java` — chosen specifically to be algorithmically different from `String.hashCode()` so the two aren't correlated, which matters for the Kirsch-Mitzenmacher combination technique to behave like true independent hash functions. |

## Trade-offs and limitations (project-wide)

- the on-disk binary format has no versioning/schema-evolution support
- CLI argument parsing is minimal by design and won't scale past this tool's ~6 subcommands
- logging is `stderr`-only with no levels or structured output
- `scan` merges full SSTable scans client-side rather than using an optimized range index

## Development-only dependencies

**None.** The test suite uses zero external test frameworks (see substitution #8), so there is nothing to disclose in this section — no dev-only dependency was needed at all.

## Runtime dependency verification

```bash
# No build manifest exists to declare dependencies in — plain javac + Makefile.
ls *.pom pom.xml build.gradle 2>&1   # expected: "No such file or directory" for all three

# Confirm every import in the codebase is JDK or project-internal:
grep -rh "^import" src/ tests/ | sort -u
# expected: every line starts with "import java." or "import kvlite."
```

## Dependency audit instructions (for judges)

1. Clone the repo fresh.
2. Confirm there is no `pom.xml`, `build.gradle`, or any dependency manifest file at all.
3. Run `grep -rh "^import" src/ tests/ | sort -u` — every import resolves to `java.*` or `kvlite.*`.
4. Run `make build` on a machine with only a JDK installed, with networking disabled — it succeeds with no network access required.
5. Run `make test` — 21 tests pass using zero external test frameworks.
6. See `deps-proof.txt` for the actual command output captured on the development machine.
