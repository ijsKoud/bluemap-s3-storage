# Changelog

## Unreleased
- Phase 0: BlueMap API notes.
- Phase 1: project skeleton, config class, addon registration (`klrnbk-bluemap:s3`), key layout.
- Phase 2: dependency-free S3 client (SigV4, retries with full jitter, rate and concurrency gate), fake S3 contract tests.
- Phase 3: synchronous storage (single PUT/GET), local render state with one-time import, semantics tests.
- Phase 4: write-behind queue (coalescing, per-key ordering, backpressure), crash-safe spool with replay,
  failure policy with periodic retry, throughput test (producers at 1.6% of the synchronous time).
