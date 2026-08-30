# 72-Hour Execution Plan

Kickoff: Aug 28, 18:00 UTC. Code freeze: Aug 31, 18:00 UTC. All times relative to kickoff (T+0).

## Phase 0 — Pre-hackathon (now until T+0)
Allowed: research, architecture, README/STDLIB skeletons, prompt prep, reading `java.nio` / `ConcurrentSkipListMap` / `RandomAccessFile` docs.
Not allowed: writing or committing any project code.
- [ ] finalize architecture (done — see ARCHITECTURE.md)
- [ ] pre-write README/STDLIB skeletons (done — fill in real numbers during hackathon)
- [ ] read up on `java.nio.channels.FileChannel.force()` (fsync equivalent), `ByteBuffer`, `BitSet`
- [ ] sketch WAL record format and SSTable footer format on paper
- [ ] prep AI prompts for component scaffolding so T+0 starts fast

## Phase 1 — Foundation (T+0 to T+6h)
- repo init, empty `pom.xml`/no build tool or plain `javac`+`Makefile`, package skeleton
- CLI arg parsing (stdlib only), command dispatch skeleton
- WAL writer + reader (append, replay) — no MemTable yet, just prove durable append/replay works

## Phase 2 — Core engine (T+6h to T+24h)
- MemTable (ConcurrentSkipListMap) wired to WAL
- SSTable writer (flush MemTable → sorted binary file)
- SSTable reader + sparse index + binary search lookup
- basic `put`/`get`/`del` end-to-end through CLI

## Phase 3 — Advanced functionality (T+24h to T+40h)
- BloomFilter from BitSet (kill the Guava dependency)
- Compactor (merge SSTables, drop tombstones, rebuild bloom filters)
- concurrent read/write correctness (ReentrantReadWriteLock around SSTable set swap)
- crash-recovery path: atomic rename on flush, WAL replay on startup

## Phase 4 — Testing (T+40h to T+52h)
- unit tests: WAL record encode/decode, bloom filter false-positive rate, SSTable binary search
- integration tests: put/get/del round trip, restart-and-recover, compaction correctness
- crash tests: kill -9 mid-write, mid-flush; verify no data loss or corruption on restart
- concurrency tests: N writer threads + M reader threads hammering the store simultaneously

## Phase 5 — Hardening (T+52h to T+58h)
- fix whatever the crash/concurrency tests exposed (budget this — it will find something)
- corrupted-record handling (bad CRC skip-and-log path)
- edge cases: empty key, huge value, rapid put/delete of same key, restart with zero WAL segments

## Phase 6 — Documentation (T+58h to T+64h)
- finalize README.md with real numbers
- finalize STDLIB.md with actual substitutions used (not the pre-written guesses)
- write deps-proof.txt (actual command output)
- write LICENSE, finalize repo structure

## Phase 7 — Demo prep (T+64h to T+68h)
- record benchmark run for real throughput/latency numbers
- record 5-minute demo video per DEMO_SCRIPT.md
- rehearse crash-recovery demo live at least twice before recording

## Phase 8 — Final dependency audit (T+68h to T+72h)
- run `mvn dependency:tree` or equivalent / confirm no build file has a dependency block
- fresh-clone the repo into a clean directory and run the one-command build to confirm it works cold
- submit

## Contingency
Built into Phase 5 (6 hours) and the gap between Phase 8 and the deadline (~4h buffer). If Phase 3 runs long, cut range-scan and leveled compaction from stretch — the MVP (single-level compaction, basic bloom filter) is still a complete, defensible submission on its own.
