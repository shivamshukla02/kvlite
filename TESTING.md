# Testing & Benchmarking Strategy

Test runner: hand-written stdlib-only assert harness (avoids the JUnit dev-dependency grey area entirely — a few dozen `assert`-based test methods run via a small `TestRunner.main()`).

## Unit tests
| Area | What to test | Why | Expected behavior |
|---|---|---|---|
| WAL codec | encode/decode a record round-trip | core durability primitive | byte-identical after decode |
| WAL CRC | corrupt one byte, replay | must not silently accept bad data | record skipped, logged, replay continues |
| BloomFilter | known member vs. known non-member | correctness of the Guava replacement | no false negatives, documented false-positive rate |
| SSTable index | binary search on sparse index | read-path correctness | correct block located for arbitrary key |

## Integration tests
| Area | What to test | Why | Expected behavior |
|---|---|---|---|
| put/get/del round trip | full stack through CLI | end-to-end correctness | value matches, delete returns not-found after |
| restart-and-recover | write N keys, kill process, restart | WAL replay correctness | all N keys present after restart |
| compaction correctness | write, overwrite, delete, compact | compaction must resolve to latest value | old versions and tombstones gone post-compaction |

## Edge cases / invalid input
- empty key, empty value, very large value (MB-scale)
- rapid put/delete of the same key many times
- restart with zero WAL segments (fresh store)
- get on a key that was deleted then never re-added

## Concurrency tests
- N writer threads + M reader threads against the same store simultaneously — assert no reader ever sees a torn/partial write
- concurrent flush + concurrent read — reader must see either pre- or post-flush state, never in-between

## Crash / recovery tests
- kill -9 mid-WAL-write → restart → verify no corruption, last complete write recovered, partial write discarded
- kill -9 mid-SSTable-flush → restart → verify old SSTable set intact (atomic rename means the flush never partially landed)

## Performance / stress tests
- sustained write throughput (ops/sec) at increasing MemTable sizes
- read latency (p50/p99) with cold vs. warm bloom filters
- compaction pause impact on concurrent read latency
- large dataset soak test (millions of keys) to confirm no unbounded memory growth

All numbers reported in README/benchmarks must come from actual runs on the day — no invented figures.
