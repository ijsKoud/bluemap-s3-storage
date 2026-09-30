# bluemap-s3-storage

High-throughput S3 storage addon for [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap),
written for Hetzner Object Storage (Ceph RGW). Storage type key: `klrnbk-bluemap:s3`.

**Status: phase 4.** Tile writes return immediately (write-behind queue with a local spool), reads are
one GET, render state is on local disk. Metrics log, full docs and CI follow in phase 5.

## Install

1. `./gradlew jar`, copy `build/libs/bluemap-s3-storage-*.jar` to `plugins/BlueMap/packs/`.
2. In `plugins/BlueMap/storages/<name>.conf`:

```hocon
storage-type: "klrnbk-bluemap:s3"
endpoint-url: "https://hel1.your-objectstorage.com"
region: "hel1"
bucket-name: "my-bucket"
access-key-id: "..."
secret-access-key: "..."
root-path: ""          # "" or "." = bucket root, same as the TheMeinerLP addon
compression: "gzip"
render-state-path: "bluemap/rstate-s3"

# Upload tuning (see "Thread settings" below)
upload-threads: 32             # default 16, parallel background uploads
max-in-flight-requests: 48     # default 32, cap on all concurrent requests, keep >= upload-threads
max-requests-per-second: 600   # default 600

# Write-behind buffer and crash spool
spool-enabled: true            # keep acknowledged writes on local disk until they are uploaded
spool-path: "bluemap/s3-spool" # any directory, relative to the server working dir or absolute
spool-max-bytes: 2147483648    # beyond this, writes are memory-only (with a warning)
```

`spool-path` and `render-state-path` may be absolute (for example `/data/bluemap/s3-spool`) to put
them on a specific disk. The spool should be on fast local storage such as NVMe: every write goes
there before it is acknowledged.

3. Point the maps at that storage (`storage: "<name>"` in the map config) and restart.

## Thread settings

Two settings matter for speed, in two different files:

| Setting | File | Recommended (server limited to ~12 cores) |
|---|---|---|
| `render-thread-count` | BlueMap's own `plugins/BlueMap/core.conf` | `8` (at most 10). Keep it at or below the cores available to the server, minus 2 to 4 for the game, GC and network. |
| `upload-threads` | the storage `.conf` of this addon | `32` (default `16`) |
| `max-in-flight-requests` | the storage `.conf` of this addon | `48`, keep it at or above `upload-threads` |
| `max-requests-per-second` | the storage `.conf` of this addon | `600` (Hetzner allows 750 per bucket and per IP) |

Upload threads mostly wait on the network and use little CPU. The upload rate is roughly
`upload-threads / PUT latency`: at 40 to 60 ms per PUT, 24 to 36 threads are enough to reach 600
requests/s. If BlueMap produces far fewer writes than that, the default 16 is already enough.
Restart BlueMap after changing either file.

## Render state must be persistent

BlueMap's render state (`rstate`) lives on local disk in `render-state-path`, not in the bucket.
**Keep this directory on persistent storage and back it up together with the world.** Losing it
forces BlueMap to re-render every tile. On the first start per map, existing `rstate/` objects of a
previous S3 storage in the same bucket are imported once (marker file `.s3-imported`).

## Compatibility

Compiled against BlueMap 5.3 API, verified by source against 5.28 (see `docs/api-notes.md`).
Object keys match BlueMap's file storage layout, so existing tiles need no migration.

## License

MIT. The S3 client design was informed by [QarthO/bluemap-web-s3](https://github.com/QarthO/bluemap-web-s3) (MIT).
