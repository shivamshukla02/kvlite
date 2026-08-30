# kvlite

> A persistent, crash-safe, concurrent key-value store built entirely on the Java standard library. No frameworks. No third-party packages. No dependency manifest at all — the build has nothing to declare dependencies in.

**Track:** D — Data & Storage
**Built for:** Zero Dependency 2026 (Hackathon Raptors), Aug 28–31 2026

## Problem

A small service that needs local persistent state usually reaches for RocksDB bindings, an embedded H2 instance, or a full external database — dragging in a dependency tree just to store key-value pairs on disk. This project builds that layer from first principles, using only what the JDK ships.

## Solution

kvlite is a log-structured merge-tree (LSM-tree) key-value store: writes go to a write-ahead log and an in-memory table, both are periodically flushed to immutable sorted files (SSTables) on disk, and a compactor merges those files back down when they accumulate. It survives abrupt process kills, serves concurrent readers and writers correctly, and needs nothing beyond a JDK to build and run.

## Features

- durable writes via a CRC32-checked write-ahead log
- crash recovery via WAL replay on startup — proven with an actual `Runtime.getRuntime().halt(0)` kill, not a simulated one
- SSTable flush with a sparse index for fast point lookups
- hand-rolled bloom filter (on `java.util.BitSet`) to skip SSTables that can't contain a key — replaces Guava's `BloomFilter`
- single-level compaction that merges duplicate keys to the newest version and drops tombstones
- concurrent reads during writes, flushes, and compaction (proven under a multi-threaded stress test)
- CLI: `put`, `get`, `del`, `scan`, `bench`, `recover-test`

## Architecture

```
CLI → LSMEngine → { MemTable (ConcurrentSkipListMap), WriteAheadLog, SSTableManager } → Compactor
```

**Write path:** WAL append (fsync'd) → MemTable insert → if the MemTable exceeds 1MB, flush it to a new SSTable (write-to-temp-then-atomic-rename) → truncate the WAL → every 4th flush triggers a compaction of the whole SSTable set.

**Read path:** MemTable first (freshest) → SSTables newest-to-oldest, each checked via its bloom filter before touching disk, then a binary search on its sparse index to find the scan start point.

**Concurrency:** MemTable reads/writes are lock-free (`ConcurrentSkipListMap`). The SSTable file set is guarded by a `ReentrantReadWriteLock` held only for the instant it takes to swap the list reference — all the actual disk I/O for a flush or compaction happens *before* the lock is taken, so slow I/O never blocks concurrent readers.

Full diagram and design rationale: [ARCHITECTURE.md](./ARCHITECTURE.md).

## Installation / Build

One command, using only `javac` — no build tool with a dependency graph:

```bash
make build
```

## Usage

```bash
java -cp build kvlite.Main put mykey "hello world"
java -cp build kvlite.Main get mykey
java -cp build kvlite.Main del mykey
java -cp build kvlite.Main scan a z
java -cp build kvlite.Main bench --writes 200000 --threads 4
java -cp build kvlite.Main recover-test              # kills itself abruptly
java -cp build kvlite.Main recover-test --verify      # fresh process, confirms recovery
```

Exit codes: `0` success, `1` key not found on `get`, `2` usage error, `3` corruption detected during `recover-test --verify`.

## Examples (real transcripts, run during development)

```
$ java -cp build kvlite.Main put name shivam
OK
$ java -cp build kvlite.Main put role "systems engineer"
OK
$ java -cp build kvlite.Main get name
shivam
$ java -cp build kvlite.Main del name
OK
$ java -cp build kvlite.Main get name
$ echo $?
1
$ java -cp build kvlite.Main scan a z
role = systems engineer
```

Crash recovery, two separate JVM processes:

```
$ java -cp build kvlite.Main recover-test
wrote 3 keys, now killing the process abruptly (Runtime.halt, no clean shutdown)...
$ java -cp build kvlite.Main recover-test --verify
RECOVERY OK — all 3 keys survived the abrupt process kill
```

## Testing

```bash
make test
```

21 tests, hand-written against a stdlib-only test runner (no JUnit — see STDLIB.md for why). Covers WAL round-trip and corruption detection, engine put/get/delete/overwrite, restart-and-recover, cross-SSTable recency resolution, tombstone shadowing across compaction, bloom filter false-positive/false-negative behavior, and a multi-threaded concurrent reader/writer stress test. Full breakdown: [TESTING.md](./TESTING.md).

Current result: **21 passed, 0 failed.**

## Performance

Measured on the actual development machine, single run, `bench --writes 200000 --threads 4`:

| Metric | Value |
|---|---|
| Throughput | 7,528 writes/sec |
| p50 write latency | 339 µs |
| p99 write latency | 1,331 µs |

**Honest limitation:** every write fsyncs the WAL individually (`FileChannel`/`RandomAccessFile.getFD().sync()` per record). This is the main throughput ceiling. Group-commit batching — buffering several writes and fsyncing once per batch — is the documented next step to raise this substantially, and is left as roadmap rather than implemented under time pressure. A prior from-scratch LSM-tree build with batching reached ~117K writes/sec on similar hardware; the gap here is attributable almost entirely to the fsync-per-write choice, not the rest of the architecture.

Bloom filter false-positive rate: tested at ~1% target with 10,000 keys, observed rate stayed within the test's 3x tolerance band (see `BloomFilterTest.testFalsePositiveRateIsReasonable`).

## Zero-dependency explanation

Every component is built on `java.util.concurrent.ConcurrentSkipListMap`, `java.io`/`java.nio` file APIs, `java.util.BitSet`, `java.util.zip.CRC32`, and `java.util.concurrent` primitives. Verified: `grep`-ing every import across `src/` and `tests/` turns up nothing outside `java.*` and `kvlite.*`. Full substitution log: [STDLIB.md](./STDLIB.md).

## Security considerations

Not applicable — this is a storage engine, not a security tool. No encryption at rest, no access control; file permissions are left to OS defaults. This is a documented scope limitation, not an oversight (Track E exists separately for that concern).

## Limitations

- single-node, embedded only — no networking or replication
- single-level compaction (merges the entire SSTable set every run), not leveled compaction — simpler, correct, but doesn't scale as gracefully to very large datasets as a tiered approach would
- fsync-per-write bounds throughput; group-commit batching not yet implemented (see Performance above)
- `scan` is implemented by a full merge of all SSTables' `scanAll()`, not an optimized range index — correct but not fast for very large stores
- a corrupted WAL record is skipped-and-logged (availability over strictness) rather than halting the process — deliberate, documented here and in code comments
- keys/values are UTF-8 strings only, no arbitrary binary blob support in the current format

## Roadmap

- leveled/tiered compaction
- group-commit WAL batching for higher write throughput
- indexed range scans

## Project structure

```
kvlite/
├── src/kvlite/           # engine code, written during the 72-hour window
│   ├── WriteAheadLog.java
│   ├── MemTable.java
│   ├── SSTable.java
│   ├── SSTableManager.java
│   ├── BloomFilter.java
│   ├── Compactor.java
│   ├── LSMEngine.java
│   └── Main.java          # CLI entry point
├── tests/kvlite/tests/    # hand-written stdlib-only test suite
├── ARCHITECTURE.md
├── STDLIB.md
├── TESTING.md
├── EXECUTION_PLAN.md
├── SCOPE.md
├── deps-proof.txt
├── Makefile
├── README.md
└── LICENSE
```

## Reproducibility

```bash
make clean && make build
find build -name "*.class" | sort | xargs sha256sum | sha256sum
make clean && make build
find build -name "*.class" | sort | xargs sha256sum | sha256sum
```

Both runs on this machine produced the identical combined hash:
`7acda9fd25bfb9ccabf65b3932f58e8d545d37bfdb7e5cf415c93902e65b8e5a`

## License

MIT — see [LICENSE](./LICENSE).

## AI usage disclosure

Built with AI coding assistance (Claude) for scaffolding, test design, and documentation. Every architectural decision — WAL record format, SSTable layout, bloom filter sizing, compaction strategy, concurrency model — was reviewed and is defensible line-by-line. See [JUDGE_QA.md](./JUDGE_QA.md) for the prepared explanations behind each choice.
