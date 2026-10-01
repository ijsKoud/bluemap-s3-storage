# Serving the map from Cloudflare R2

There are two ways to get tiles to the browser. Both work with this addon.

| | A. Through BlueMap's webserver (default) | B. Directly from R2 |
| --- | --- | --- |
| Setup | none | custom domain, CORS, two lines in `webapp.conf` |
| Tile path | browser, BlueMap webserver, addon, R2 | browser, Cloudflare, R2 |
| R2 reads | one GET per tile request (Class B) | mostly served from Cloudflare's cache |
| Server load | every tile request passes through the Minecraft host | none for tiles |
| Egress cost | free on R2 | free on R2 |

Use B when the Minecraft host has little spare bandwidth or CPU, or when many people view the map.
A needs no changes, and stays available as the fallback at any time.

## How the webapp finds its data

The BlueMap webapp builds every URL from two settings of `plugins/BlueMap/webapp.conf`
(read from the BlueMap v5.28 webapp source):

| Setting | Default | Used for |
| --- | --- | --- |
| `map-data-root` | `maps` | `<root>/<mapId>/settings.json`, `textures.json[.gz]`, `tiles/0/...prbm[.gz]`, `tiles/<lod>/....png`, `assets/...` |
| `live-data-root` | `maps` | `<root>/<mapId>/live/players.json`, `live/markers.json` and the live connection |
| `client-decompression` | `false` | requests `.prbm.gz` and `textures.json.gz` and unzips them in the browser |

Object keys written by this addon are identical to BlueMap's file layout, so `<map-data-root>/<mapId>/...`
maps one to one onto `<bucket>/<root-path>/<mapId>/...`.

## Setup for option B

### 1. Make the bucket publicly readable on a custom domain

In the Cloudflare dashboard: R2, your bucket, Settings, Custom Domains, connect a domain such as
`tiles.dawnofempires.net`. The domain has to be in your Cloudflare account. Per Cloudflare's
[public bucket docs](https://developers.cloudflare.com/r2/buckets/public-buckets/):

- Do not use the `r2.dev` address for the live map. It is meant for development, is rate limited and has no caching.
- A public bucket cannot be listed. Anyone who knows or guesses a key can read it, so use a bucket that holds
  only map data.

### 2. Add a CORS policy

The webapp (`https://map.dawnofempires.net`) and the tiles (`https://tiles.dawnofempires.net`) are different
origins, so the browser needs CORS headers. Bucket, Settings, CORS Policy:

```json
[
  {
    "AllowedOrigins": ["https://map.dawnofempires.net"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "MaxAgeSeconds": 3600
  }
]
```

The origin has to match exactly, including `https://` and without a trailing slash. Custom domains connected to the
bucket return these headers automatically ([CORS docs](https://developers.cloudflare.com/r2/buckets/cors/)).

### 3. Cache the tiles on Cloudflare

Cloudflare caches only some file types by default, and does not purge an object when it is overwritten.
Add a Cache Rule for the tiles hostname that makes the responses eligible for cache and respects the
origin `Cache-Control`. The addon stores `public, max-age=60` on tiles, so a changed tile is visible
within about a minute. The exact wording of the rule options in the dashboard may differ from this description.

### 4. Point the webapp at the bucket

In `plugins/BlueMap/webapp.conf`:

```hocon
map-data-root: "https://tiles.dawnofempires.net"
client-decompression: true
```

If the addon's `root-path` is not empty, append it: `"https://tiles.dawnofempires.net/<root-path>"`.
Leave `live-data-root` alone (see below). Keep `update-settings-file: true` so BlueMap rewrites the webapp
`settings.json`, then run `/bluemap reload` or restart.

`client-decompression: true` needs the storage `compression` to be `gzip` (the default), because the webapp
then requests `.prbm.gz` tiles and `textures.json.gz`.

### 5. Check it with curl

```bash
curl -sI -H "Origin: https://map.dawnofempires.net" https://tiles.dawnofempires.net/world_earth/settings.json
curl -sI -H "Origin: https://map.dawnofempires.net" https://tiles.dawnofempires.net/world_earth/textures.json.gz
```

Expected on the second response:

- status `200`
- `content-type: application/octet-stream`
- **no** `content-encoding` header
- `cache-control: public, max-age=60` (stored on the object by the addon; Cloudflare's docs do not say how it treats a stored value, so check that it arrives)
- `access-control-allow-origin: https://map.dawnofempires.net`

## Object headers set by the addon

| Object | Content-Type | Cache-Control |
| --- | --- | --- |
| hires tiles `tiles/0/....prbm.gz` | `application/octet-stream` | `tile-cache-control`, default `public, max-age=60` |
| lowres tiles `tiles/<lod>/....png` | `image/png` | `tile-cache-control` |
| `settings.json`, `live/*.json` | `application/json` | `meta-cache-control`, default `no-cache` |
| `textures.json.gz` | `application/octet-stream` | `meta-cache-control` |
| assets | by file extension | `meta-cache-control` |

Compressed objects have no `Content-Encoding` header. This matters with `client-decompression: true`: if the
bucket or a proxy added `Content-Encoding: gzip`, the browser would unzip the tile itself and the webapp would
then fail to unzip it a second time.

## What happens when a tile does not exist

Chunks that were never rendered have no tile. The two options answer differently:

- BlueMap's webserver answers `204 No Content`.
- R2 answers with an error status for a missing object. Cloudflare's docs do not state which, so check
  with `curl -sI` on a tile that cannot exist. Expect `404`, and `403` if the bucket is not public.

Both are handled the same way and neither needs a fix. In the webapp source (`RevalidatingFileLoader.js`) any
status other than 200 raises an error, `Tile.js` unloads the tile in its rejection handler, and
`TileManager.js` swallows the error with an empty `catch`. There is no retry loop and nothing is shown to the
user. The only visible effect is red entries in the browser's network tab.

## Live data (players and markers)

Keep `live-data-root` at its default, so the webapp keeps getting players and markers from BlueMap's own server
through its live connection. That feed comes from memory on the Minecraft server, not from storage, and works
the same with any storage backend.

You will still see a `live/` folder in the bucket. BlueMap writes `live/players.json` (an empty `{}`, to clear old
data) when a map loads, and `live/markers.json` together with `settings.json` on every map save, about every 15 seconds
while rendering. These files are not the live feed. The addon skips a write when the content is identical to the last
one it wrote in this run, so a steady map costs one PUT per file after each restart, not one per save.

Besides that, nothing is written for live data: in `plugin.conf`, `write-players-interval` and
`write-markers-interval` default to `0`, which means never.

**If you set those intervals above 0, every write is a billable PUT.** A players write every 3 seconds is 28,800
writes per day per map, about 864,000 per month, which uses up most of R2's 1 million free Class A operations. Markers
every 10 seconds add about 259,000 per month. Leave them at `0` unless the webapp is hosted somewhere that cannot
reach BlueMap's live connection.

## Cost notes

- Tile uploads are Class A operations ($4.50 per million) with 1 million free per month. The metrics line shows `PUT n/s`,
  so `n x 2,592,000` is the monthly count at that rate. Retries count as requests too.
- Tile reads are Class B ($0.36 per million, 10 million free). With the Cloudflare cache in front, most reads never reach R2.
- Egress is free.

## Switching back

Remove `map-data-root` and `client-decompression` from `webapp.conf` (or set them to `maps` and `false`), reload,
and BlueMap serves everything through the addon again. No data moves.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Map loads but no tiles, console shows CORS errors | `AllowedOrigins` does not exactly match the webapp origin |
| Tiles fail with decoding errors | a proxy added `Content-Encoding: gzip`, or `compression` is not `gzip` while `client-decompression` is `true` |
| Tiles are stale for a long time | a Cache Rule with a long Edge TTL that ignores `Cache-Control`; Cloudflare does not purge overwritten objects |
| `403` on every tile | the custom domain is not connected, or public access is off for the bucket |
| Lowres tiles load, hires do not | `.prbm.gz` is blocked by a WAF or cache rule on the hostname |
