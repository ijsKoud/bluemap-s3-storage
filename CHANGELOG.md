# Changelog

## Unreleased
- Phase 0: BlueMap API notes.
- Phase 1: project skeleton, config class, addon registration (`klrnbk-bluemap:s3`), key layout.
- Phase 2: dependency-free S3 client (SigV4, retries with full jitter, rate and concurrency gate), fake S3 contract tests.
- Phase 3: synchronous storage (single PUT/GET), local render state with one-time import, semantics tests.
- Phase 4: write-behind queue (coalescing, per-key ordering, backpressure), crash-safe spool with replay,
  failure policy with periodic retry, throughput test (producers at 1.6% of the synchronous time).
- Metrics: one INFO line per interval with interval rates, latency percentiles, queue, retries and producer blocked time.
- Shorter default timeouts (writes 15 s, new `read-timeout-seconds` 5 s) and `timeouts`/`ioErrors` counters, after metrics from a live server showed multi-second latency tails.
- Live server log analysis: persistent per-key hangs. Write timeout default 5 s (+1 s per MiB), read timeout 4 s,
  `read-max-retries` 10, and failed ops are retried with backoff and capped to upload-threads/8 concurrent retries.
