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
```

3. Point the maps at that storage (`storage: "<name>"` in the map config) and restart.

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
