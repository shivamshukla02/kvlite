# Judge Q&A Prep

Answers below are frameworks — fill in real numbers/specifics once the implementation is done.

## Architecture
1. **Why an LSM-tree instead of a simpler B-tree/hashmap-on-disk?** LSM trees turn random writes into sequential ones (append to WAL/memtable, flush sequentially), which is the standard tradeoff for write-heavy workloads — and it's a well-understood architecture to explain and defend.
2. **Why ConcurrentSkipListMap for the memtable?** Lock-free, sorted, JDK-native — gives ordered iteration for flush without a separate sort step.
3. **What happens if the memtable grows unbounded before a flush?** Flush threshold is a fixed size; document the threshold and what happens if a single write pipeline can't keep up with the flush rate (backpressure or blocking write, state which one was chosen).
4. **Why not a proper LSM level structure (leveled compaction)?** Single-level compaction was the MVP tradeoff to hit 72 hours reliably; leveled compaction was scoped as stretch, honestly labeled as future work.

## Why this project / language
5. **Why storage over CLI/parsers/web?** Track D lets us demonstrate durability, concurrency, and correctness under crash conditions — the deepest, most defensible category of the six.
6. **Why Java?** Strong stdlib concurrency primitives (`java.util.concurrent`), `java.nio` for fast/atomic file IO, and prior hands-on experience building this class of system, meaning time went into rigor, not learning a new language mid-hackathon.
7. **Why not Rust/Go for a storage engine?** Both are reasonable; Java's `java.nio` + `ConcurrentSkipListMap` gave the fastest reliable path to a correct implementation in 72 hours given the team's existing depth in it.

## Why no dependencies / stdlib choices
8. **What was hardest to replace?** The bloom filter (Guava) — name the specific hashing approach used and why it was chosen.
9. **Why BitSet for the bloom filter instead of a raw byte array?** Built-in bit-level operations, no manual bit-shifting bugs.
10. **Why CRC32 for WAL integrity instead of a stronger hash?** It's a JDK-native, cheap-to-compute checksum sufficient for corruption detection, not a security guarantee — say this outright.
11. **What would you have imported for the CLI if allowed?** picocli/commons-cli, but the flag surface (5-6 subcommands) didn't justify it.

## Performance
12. **What's your write throughput?** Cite the actual benchmarked number, and compare honestly to the RocksDB/LevelDB range, noting it's expected to be lower given no C++/native IO tricks.
13. **What's your read latency at p99?** Cite the actual number; explain what dominates it (bloom filter check + block scan).
14. **How does compaction affect read latency?** Cite the measured pause/impact from the stress test.
15. **Why is your bloom filter false-positive rate what it is?** State the bit-per-key ratio chosen and the tradeoff against memory.

## Concurrency
16. **How do you prevent a reader from seeing a half-written SSTable?** Atomic rename — a reader only ever sees the old file or the fully-written new one, never a partial one.
17. **What happens if two writers write concurrently?** WAL append is serialized under a single writer lock; explain the actual synchronization primitive used.
18. **Is your memtable read path lock-free?** Yes — `ConcurrentSkipListMap`; explain what is and isn't lock-free elsewhere (SSTable set swap uses a lock).

## Security
19. **Do you encrypt data at rest?** No — out of scope for Track D; state this as an explicit limitation, not an oversight.
20. **What about file permissions on the data directory?** Left to OS defaults — documented as a limitation.

## Failure handling
21. **What happens to a corrupted WAL record?** Skipped, logged to stderr, replay continues — a deliberate availability choice, not silent data loss.
22. **What if the process crashes mid-SSTable-flush?** Atomic rename means the old file set is untouched; unflushed data is still in the WAL and gets replayed.
23. **What if the disk fills up mid-write?** State actual behavior observed during testing — if untested, say so honestly rather than guessing.

## Trade-offs
24. **What would you do differently with more time?** Leveled compaction, range-scan optimization, encryption at rest as an opt-in feature.
25. **What's the biggest correctness risk in the current implementation?** Name it honestly — likely candidate: compaction racing with a long-running read snapshot.

## Testing
26. **How did you test crash recovery?** Actual `kill -9` mid-write/mid-flush in an automated test harness, not a simulated abort.
27. **How did you test concurrency?** N writer + M reader threads hammering the store; describe what invariant was checked.
28. **What's your test coverage philosophy?** Correctness-critical paths (WAL replay, compaction, bloom filter) over exhaustive coverage of the CLI layer.

## Scalability / alternatives
29. **How would this scale to a distributed setting?** Out of scope here — this is a single-node embedded store; note it as a natural "roadmap" extension (e.g. Raft-based replication) rather than something attempted this weekend.
30. **What existing tools solve this problem better?** RocksDB, LevelDB, BadgerDB — name them honestly, and state clearly that kvlite's value is in being dependency-free and understandable, not in outperforming battle-tested engines.

## AI usage
31. **How did you use AI in this project?** Scaffolding boilerplate, debugging, and documentation drafts — every architectural decision and the final code were reviewed and are defensible by the author. Be ready to explain any specific function line-by-line if asked.
