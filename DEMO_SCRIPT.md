# Demo Video Script (5 minutes)

## 0:00–0:30 — Problem
**Narration:** "Every small service that needs local persistent storage reaches for RocksDB, an embedded database, or a full external service — a dependency tree, just to store key-value pairs on disk. We built that layer from scratch, using nothing but the Java standard library."
**On screen:** title card, then a quick shot of a typical `pom.xml` with 5+ storage-related dependencies (for contrast) fading to kvlite's empty one.

## 0:30–1:00 — Why existing solutions have dependencies
**Narration:** "RocksDB needs native bindings. H2 needs its own JAR. Guava's bloom filter alone is a dependency most from-scratch attempts still reach for. We replaced every one of them with something already in the JDK."
**On screen:** side-by-side: "normally imported" list vs. "stdlib replacement" list (pulled straight from STDLIB.md).

## 1:00–2:00 — Architecture
**Narration:** walk through the ASCII diagram — write path (WAL → MemTable → SSTable flush), read path (MemTable → bloom filter → sparse index), background compaction.
**On screen:** ARCHITECTURE.md diagram, highlighted section by section as narrated.

## 2:00–3:30 — Live demonstration
**Narration:** "Let's see it work." Run through: `put` several keys, `get` them back, `del` one, `scan` a range, then the crash-recovery moment: kill -9 the process mid-write, restart, and show the data survived.
**On screen:** live terminal, unedited. This is the most important 90 seconds — no cuts on the crash-recovery segment.

## 3:30–4:15 — Zero-dependency proof
**Narration:** "Here's the manifest — empty. Here's the build, running with no network access at all, to prove nothing gets pulled in."
**On screen:** `cat` the build file, then run `make build` with networking disabled (per deps-proof.txt), show it succeed.

## 4:15–4:45 — Technical depth / standard library
**Narration:** "The bloom filter is the one we're proudest of — hand-rolled on top of `java.util.BitSet`, replacing Guava's version, which has millions of downloads across the JVM ecosystem." Mention concurrency model briefly (ConcurrentSkipListMap, ReadWriteLock) and real benchmark numbers.
**On screen:** benchmark output (real numbers), STDLIB.md scrolled briefly.

## 4:45–5:00 — Impact + conclusion
**Narration:** "Every line here is stdlib. No supply chain, nothing to audit but our own code. That's kvlite."
**On screen:** closing card with repo link.

## Recording notes
- record the crash-recovery segment live, don't fake it with a script that "simulates" a crash — an actual `kill -9` is the credible version
- keep narration matched to the "boring is honest" tone the hackathon rewards — no hype language about numbers that aren't real
- capture benchmark numbers from an actual run shortly before recording, not from an earlier estimate
