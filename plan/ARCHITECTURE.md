# Architecture — kvlite (working name)

## One-line pitch
A persistent, crash-safe, concurrent embedded key-value store built entirely on the Java standard library — no Guava, no third-party serialization, no external storage engine.

## Track
D — Data & Storage

## Problem
Every "just use a database" answer for a small service pulls in RocksDB, LevelDB bindings, or a full Postgres/Redis dependency. Nobody remembers that a durable, reasonably fast KV store is buildable in an afternoon with nothing but files, buffers, and a lock. This project proves it, replacing the one third-party piece a from-scratch attempt usually still reaches for (Guava's BloomFilter) with a hand-rolled one.

## Target users
Backend engineers who want an embedded store for a small service, CLI tools that need local persistent state, and anyone curious what's actually inside an LSM-based database.

## Components

```
                      ┌─────────────────────┐
                      │        CLI           │
                      │ put/get/del/bench/   │
                      │ scan/recover-test     │
                      └──────────┬───────────┘
                                 │
                      ┌──────────▼───────────┐
                      │      KVEngine         │  <- public API facade
                      └──────────┬───────────┘
              ┌──────────────────┼───────────────────┐
              │                  │                    │
     ┌────────▼───────┐ ┌────────▼────────┐  ┌────────▼────────┐
     │    MemTable     │ │       WAL        │  │  SSTable Manager │
     │ ConcurrentSkip  │ │  append-only,    │  │  binary format,   │
     │ ListMap<K,V>    │ │  group-commit    │  │  sorted, indexed  │
     └────────┬────────┘ └──────────────────┘  └────────┬─────────┘
              │ flush on threshold                        │
              └─────────────────────►  Compactor  ◄───────┘
                                     merges SSTables,
                                     drops tombstones,
                                     rebuilds bloom filters

     ┌────────────────────┐
     │   BloomFilter        │  <- per-SSTable, BitSet-backed,
     │   (stdlib BitSet +   │     double hashing (no Guava)
     │   MurmurHash-lite)   │
     └────────────────────┘
```

## Data flow (write path)
1. `put(key, value)` → append entry to WAL (fsync per group-commit batch)
2. entry written into in-memory MemTable (ConcurrentSkipListMap)
3. when MemTable exceeds size threshold → flushed to an immutable SSTable file on a background thread
4. WAL segment truncated once flush is durable
5. background Compactor periodically merges SSTables, drops tombstoned/overwritten keys, rebuilds bloom filters

## Data flow (read path)
1. check MemTable first (freshest data)
2. check each SSTable newest→oldest, using its bloom filter to skip files that provably don't contain the key
3. binary search the SSTable's sparse index to locate the block, then scan the block

## Concurrency model
- writes: single writer thread per WAL segment (group-commit batching avoids fsync-per-write)
- reads: `ReentrantReadWriteLock` per SSTable set; MemTable itself is lock-free (ConcurrentSkipListMap)
- compaction runs on a dedicated background thread; readers see either the pre- or post-compaction file set, never a half-written one (swap via atomic rename)

## Durability / crash recovery
- WAL is append-only; every mutation is logged before being applied to the MemTable
- on startup, `WAL.replay()` reconstructs the MemTable from the last durable WAL segment
- SSTable writes are written to a temp file and atomically renamed into place — a crash mid-flush leaves the old SSTable set intact and the WAL still holds the un-flushed data
- crash-recovery is demonstrated live in the demo: kill -9 mid-write, restart, verify data present

## File formats
- **WAL segment**: sequence of `[4-byte length][1-byte op][key bytes][value bytes][CRC32]` records
- **SSTable**: sorted key-value blocks + trailing sparse index + trailing bloom filter bitmap + footer with offsets (all via `java.nio.ByteBuffer` / `RandomAccessFile`)

## CLI design
```
kvlite put <key> <value>
kvlite get <key>
kvlite del <key>
kvlite scan <from> <to>
kvlite bench --writes 1000000 --threads 4
kvlite recover-test        # kills itself mid-write, then restarts and verifies
```
Exit codes: 0 success, 1 key not found, 2 usage error, 3 corruption detected on recovery.

## Error handling
- corrupted WAL record (bad CRC) → skip record, log to stderr, continue replay (documented, not silent)
- corrupted SSTable footer → refuse to load that file, log to stderr, continue with remaining files
- all documented explicitly in README under "Limitations"
