# bluemap-s3-storage

S3 storage addon for [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) that keeps render threads off the
network. Tile writes return as soon as the bytes are in memory and in a local spool file, and background threads
upload them. It uses only the JDK (no AWS SDK, no Netty), signs requests with SigV4, and works with Hetzner
Object Storage, Cloudflare R2, MinIO and other S3-compatible services.

Storage type key: `klrnbk-bluemap:s3`

## How it works

```
render threads / webserver / scheduler
        |  write() returns an in-memory stream, close() enqueues the bytes   (microseconds)
        v
write-behind queue   latest write per key wins, reads are served from it, bounded by bytes and entries
        |  every acknowledged write is also a file in the local spool (crash safety)
        v
upload threads       one request per key at a time, rate limit, retries with backoff
        v
S3 endpoint

render state (tile, chunk and region state) lives on local disk, never in the bucket
```

- A read is one GET (and one HEAD for `exists`). Pending writes are served from memory first.
- Object keys are the same as BlueMap's file storage layout, so existing tiles need no migration.
- If the buffer is full, `write()` blocks (backpressure) and the blocked time shows up in the metrics line.

## Install

1. Build with `./gradlew jar` and copy `build/libs/bluemap-s3-storage-*.jar` to `plugins/BlueMap/packs/`.
2. Create `plugins/BlueMap/storages/<name>.conf` (examples below).
3. Set `storage: "<name>"` in the map config and restart BlueMap.

Requires Java 21 and BlueMap 5.x (compiled against the 5.3 API, checked against the 5.28 source, see
[docs/api-notes.md](docs/api-notes.md)).

### Example: Hetzner Object Storage

```hocon
storage-type: "klrnbk-bluemap:s3"
endpoint-url: "https://hel1.your-objectstorage.com"
region: "hel1"
bucket-name: "my-bucket"
access-key-id: "..."
secret-access-key: "..."
root-path: ""                       # "" or "." = bucket root
compression: "gzip"
render-state-path: "bluemap/rstate-s3"
spool-path: "bluemap/s3-spool"
upload-threads: 32
max-in-flight-requests: 48
max-requests-per-second: 600        # Hetzner allows 750 per bucket and per source IP
```

### Example: Cloudflare R2

```hocon
storage-type: "klrnbk-bluemap:s3"
endpoint-url: "https://<ACCOUNT_ID>.r2.cloudflarestorage.com"   # EU jurisdiction: https://<ACCOUNT_ID>.eu.r2.cloudflarestorage.com
region: "auto"
force-path-style: true
bucket-name: "my-r2-bucket"
access-key-id: "..."          # R2 > Manage API tokens, "Object Read & Write", scoped to the bucket
secret-access-key: "..."
root-path: ""
compression: "gzip"
render-state-path: "bluemap/rstate-r2"
spool-path: "bluemap/s3-spool-r2"
upload-threads: 32
max-in-flight-requests: 48
max-requests-per-second: 600
```

- **Use a separate `render-state-path` and `spool-path` for every bucket.** The render state records which tiles
  are rendered. If an empty bucket is paired with the render state of another bucket, BlueMap skips tiles it
  believes exist and the map stays empty. A leftover spool would replay its writes into the wrong bucket.
- An `AccessDenied` on startup with R2 usually means a token that is not scoped to the bucket in `bucket-name`,
  often a typo in the bucket name, because R2 answers `AccessDenied` and not `NoSuchBucket`.
- A bucket created with the EU jurisdiction needs the `.eu.` endpoint. Location and jurisdiction cannot be changed
  after the bucket is created.
- R2 bills PUT and LIST as Class A operations. The `PUT n/s` value in the metrics line times 2,592,000 is the monthly
  count at that rate.

To serve tiles to browsers directly from R2, see [docs/web-serving.md](docs/web-serving.md).

## Configuration reference

All keys are kebab-case in the storage file.

| Key | Default | Meaning |
| --- | --- | --- |
| `endpoint-url` | `https://hel1.your-objectstorage.com` | S3 endpoint |
| `region` | `hel1` | signing region (`auto` for R2) |
| `bucket-name` | none | bucket |
| `access-key-id`, `secret-access-key` | none | credentials (excluded from BlueMap debug dumps) |
| `force-path-style` | `false` | `https://endpoint/bucket/key` instead of `https://bucket.endpoint/key` |
| `root-path` | `""` | key prefix, `""` or `"."` is the bucket root |
| `compression` | `gzip` | hires tiles and textures: `none`, `gzip`, `deflate`, `zstd`, `lz4` |
| `render-state-path` | `bluemap/rstate-s3` | local render state directory, relative to the server directory or absolute |
| `upload-threads` | `16` | parallel background uploads |
| `max-in-flight-requests` | `32` | cap on all concurrent requests, a share is reserved for reads |
| `max-requests-per-second` | `600` | global request rate |
| `write-buffer-max-bytes` | `268435456` | buffer size; producers block when full |
| `write-buffer-max-entries` | `20000` | buffer entries; producers block when full |
| `spool-enabled` | `true` | keep acknowledged writes on local disk until uploaded |
| `spool-path` | `bluemap/s3-spool` | spool directory, relative or absolute, fast local disk recommended |
| `spool-max-bytes` | `2147483648` | beyond this, writes are memory-only with a warning |
| `request-timeout-seconds` | `5` | base timeout of one upload or delete attempt, plus 1 s per MiB of body |
| `read-timeout-seconds` | `4` | timeout of one read attempt (GET, HEAD, list) |
| `connect-timeout-seconds` | `10` | connect timeout |
| `max-retries` | `5` | retries of a write after the first attempt |
| `read-max-retries` | `10` | retries of a read after the first attempt |
| `shutdown-flush-timeout-seconds` | `120` | how long shutdown waits for the queue to drain |
| `tile-cache-control` | `public, max-age=60` | Cache-Control stored on tiles |
| `meta-cache-control` | `no-cache` | Cache-Control stored on settings, textures, live data, assets |
| `log-file` | `""` | file that receives all addon log output, empty means none |
| `console-log-level` | `info` | `info`, `warn`, `error` or `off` (`off` requires `log-file`) |
| `log-file-max-bytes` | `10485760` | rotate the log file at this size |
| `log-file-keep` | `3` | rotated log files to keep |
| `metrics-log-interval-seconds` | `30` | interval of the metrics line, `0` disables it |
| `list-cache-ttl-seconds` | `300` | cache of the map id list |

## Logging

Everything the addon logs (metrics line, upload failures, spool and shutdown messages) can go to the BlueMap
console, to a rotating file, or both. To keep the console quiet and read the details from a file:

```hocon
log-file: "bluemap/s3-storage.log"
console-log-level: "warn"        # the console only shows warnings and errors, the file gets everything
```

With `console-log-level: "off"` nothing is printed to the console, and the file still gets everything. The log
file is rotated at `log-file-max-bytes` and `log-file-keep` old files are kept (`s3-storage.log.1` is the newest).
Messages that BlueMap itself writes, for example when a tile cannot be loaded, are not controlled by these settings.

## Recommended values (8 render threads, about 12 cores)

| Setting | File | Value |
| --- | --- | --- |
| `render-thread-count` | BlueMap's `core.conf` | `8` (at most 10); keep 2 to 4 cores free for the game, GC and network |
| `upload-threads` | storage `.conf` | `32` |
| `max-in-flight-requests` | storage `.conf` | `48`, at or above `upload-threads` |
| `max-requests-per-second` | storage `.conf` | `600` |
| `spool-path` | storage `.conf` | on local NVMe |
| `render-state-path` | storage `.conf` | persistent, backed up with the world |

Upload threads wait on the network and use little CPU. The upload rate is roughly `upload-threads / request latency`:
at 40 to 60 ms per PUT, 24 to 36 threads reach 600 requests/s. Restart BlueMap after changing either file.

## Timeouts and retries

Normal requests take tens of milliseconds, so a request that has not answered after a few seconds is hung. It is
abandoned and retried on a new connection, instead of holding a thread for a long time.

Reads retry more often than writes on purpose: BlueMap loads lowres tiles synchronously on a render thread, and when
that read fails it continues with an empty tile and overwrites the stored one.

Writes that still fail after all retries stay in the spool and are retried with exponential backoff (30 s, 60 s, up
to 10 minutes). At most `upload-threads / 8` of those retries run at once, so a few keys that keep hanging cannot
occupy the whole upload pool.

## Reading the metrics line

```
S3 30s: queue=120 entries/5.0MB inflight=12 uploading=11 | PUT 210.3/s p50/p95/p99=32/64/128ms | GET 3.1/s p50/p95/p99=16/32/32ms | retries=+2 timeouts=+0 ioErrors=+0 failedUploads=+0 failedOps=0 | spool=2.0MB coalesced=+12 blocked=+0ms (total 0ms)
```

Rates and latencies are for the last interval only.

- `blocked=+Xms`: time render threads waited for buffer space. **Above zero means uploads are slower than rendering,
  so the render speed is set by the upload speed.** Raise `upload-threads`, or check latency and timeouts next to it.
- `queue`: what is waiting to upload. Growth over several lines means uploads cannot keep up.
- `PUT p50/p95/p99`: upload latency, including retries. Throughput is roughly `upload-threads / latency`.
- `timeouts`, `ioErrors`: attempts that hung (no answer in time) or failed at connection level. A hung attempt holds
  an upload thread until it times out, which shows up as a long latency tail.
- `retries`, `failedUploads`, `failedOps`: errors or throttling from the bucket; failed ops wait in the spool.

## Shutdown

On shutdown the addon stops accepting writes and waits up to `shutdown-flush-timeout-seconds` for the queue to drain.
It logs how many operations were left. Anything left stays in the spool and is uploaded at the next start (the spool is
replayed before new writes are accepted). Writes that were memory-only, because the spool is disabled or full, are lost
and reported as such.

## Render state must be persistent

BlueMap's render state (tile, chunk and region state) lives on local disk in `render-state-path`, not in the bucket.
**Keep this directory on persistent storage and back it up together with the world.** Losing it forces BlueMap to
re-render every tile. On the first start per map, `rstate/` objects that an earlier S3 storage left in the same bucket
are imported once (marker file `.s3-imported`); an interrupted import resumes safely.

## Migrating from TheMeinerLP's BlueMapS3Storage

The object layout is the same, so tiles stay where they are.

1. Stop the server and back up the world and the bucket if you can.
2. Keep `bucket-name`, `endpoint-url`, `region`, `root-path`, `force-path-style` and `compression` as they are.
   Change `storage-type` from `themeinerlp:s3` to `klrnbk-bluemap:s3`.
3. Add the new keys you want (`upload-threads`, `spool-path`, `render-state-path`, ...), and choose a persistent
   `render-state-path`.
4. Start the server. For each map the log reports the one-time import of the render state from the bucket.
   Rendering continues where it stopped.
5. When it works, remove the old addon jar from `plugins/BlueMap/packs/`.

### Rolling back

Stop the server, set `storage-type` back to `themeinerlp:s3`, and start again. Tiles written since the migration are in
the bucket and compatible. The old addon reads render state from the bucket, which is no longer updated by this addon,
so BlueMap may re-render some tiles that changed since the migration. That costs time but is safe.

## Releases

Every pushed version tag builds the addon and publishes the jar as a GitHub release:

```bash
git tag v0.1.1
git push origin v0.1.1
```

The version in the jar name comes from the tag (`v0.1.1` gives `bluemap-s3-storage-0.1.1.jar`). A tag with a dash,
such as `v0.2.0-rc1`, is published as a pre-release. Pushes to `main` and pull requests run the build and tests only.

## Tests

```bash
./gradlew test
```

- Unit and contract tests run against an in-process fake S3 with latency, error, timeout and connection reset injection.
- `MinioIntegrationTest` starts MinIO with Docker Compose and is skipped when Docker is not available.
- `RealBucketIntegrationTest` is opt-in: set `S3_TEST_ENDPOINT`, `S3_TEST_BUCKET`, `S3_TEST_REGION`, `S3_TEST_ACCESS_KEY`,
  `S3_TEST_SECRET_KEY` (and optionally `S3_TEST_PATH_STYLE=false`). It works under a random prefix and deletes it
  afterwards, but it makes a few hundred requests that count towards provider quotas.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `HTTP 403 AccessDenied` at startup | token without access to this bucket, wrong `bucket-name`, wrong jurisdiction endpoint (R2) |
| `SignatureDoesNotMatch` | wrong secret key, or an endpoint that does not match the region |
| `blocked` above 0 in the metrics line | uploads slower than rendering: check `timeouts` and `PUT p95`, raise `upload-threads` |
| Many `timeouts` | the endpoint is hanging requests; lower `request-timeout-seconds` is already the default, consider another bucket or provider |
| Map is empty after switching buckets | the old `render-state-path` was reused with a new empty bucket |
| `Failed to load tile` errors from BlueMap | a lowres read failed after all retries, BlueMap then overwrites the tile with an empty one |

## License

MIT, see [LICENSE](LICENSE). The client design was informed by [QarthO/bluemap-web-s3](https://github.com/QarthO/bluemap-web-s3)
(MIT); no code was copied from it.
