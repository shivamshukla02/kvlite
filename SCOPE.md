# Scope — MVP / Strong / Stretch

## MVP (must exist, scores well alone)
- WAL append + replay
- MemTable (ConcurrentSkipListMap)
- SSTable flush + read + sparse index
- single-level compaction
- BloomFilter from BitSet (Guava replacement)
- CLI: put/get/del
- crash-recovery test that actually kills the process and restarts it
- README.md + STDLIB.md + deps-proof.txt

## Strong version (implement if on schedule)
- concurrent reader/writer stress test with documented results
- group-commit batching for WAL writes (throughput number to show)
- CLI: scan (range queries)
- benchmark suite with real throughput/latency numbers vs. the lsmx baseline

## Stretch (only if MVP + strong are stable with time left)
- leveled compaction (multiple levels instead of single-level)
- reproducible build bonus (byte-identical double build + published hashes)
- single-file bonus refactor (collapse into one source file if it doesn't hurt readability — evaluate honestly, don't force it)

## Explicit non-goals
- no networking / client-server mode — this is an embedded library + CLI, not a server
- no custom crypto — not in this track
- no attempt to match RocksDB-level performance — honest numbers over inflated claims
