# Final Pre-Submission Checklist

## Rules
- [ ] correct track selected and stated (Track D)
- [ ] team size 1-4
- [ ] all code written during the official 72-hour window (no pre-kickoff commits)
- [ ] public GitHub repo
- [ ] no copied/vendored third-party source without disclosure
- [ ] zero runtime dependencies (manifest empty, verified)
- [ ] single-command build works on a fresh clone
- [ ] OSI-approved open-source license present

## Technical
- [ ] implementation actually works end to end (put/get/del/scan)
- [ ] tests pass
- [ ] edge cases tested (empty key, large value, rapid put/delete)
- [ ] error handling tested (corrupted WAL record, corrupted SSTable footer)
- [ ] performance tested with real, recorded numbers
- [ ] crash/recovery tested with an actual kill -9, not a simulated one
- [ ] concurrency tested (concurrent readers/writers, concurrent compaction)

## Documentation
- [ ] README.md complete with real numbers, no placeholders left
- [ ] STDLIB.md complete, at least 10 genuine substitutions if pursuing STDLIB Log bonus
- [ ] deps-proof.txt filled with real command output
- [ ] build instructions verified by literally following them on a clean machine/container
- [ ] usage examples are real transcripts, not invented output
- [ ] architecture explained (README + ARCHITECTURE.md)
- [ ] limitations section is honest and specific
- [ ] LICENSE file present

## Bonus (pick one, don't half-do several)
- [ ] Single File — only if it doesn't hurt readability
- [ ] Reproducible Build — two builds, matching sha256 hashes, both published
- [ ] Package Killer — Guava BloomFilter replacement clearly documented in STDLIB.md
- [ ] STDLIB Log — 10+ genuine substitutions, each with a real one-line rationale

## Demo
- [ ] 5-minute video recorded, matches DEMO_SCRIPT.md structure
- [ ] live functionality shown, not slides
- [ ] zero-dependency proof shown on screen (empty manifest + offline build)
- [ ] crash-recovery moment shown live and unedited
- [ ] clear problem statement and clear closing

## Last pass
- [ ] fresh `git clone` into a new directory, run the one-command build cold, confirm it works
- [ ] re-read STDLIB.md end to end — remove any substitution not actually used in the shipped code
- [ ] re-read README.md end to end — remove every placeholder/template bracket
- [ ] confirm repo is public
