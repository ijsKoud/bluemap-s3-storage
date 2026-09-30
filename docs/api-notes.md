# BlueMap API notes (phase 0)

Source read: BlueMap-Minecraft/BlueMap tag `v5.28` (latest release, 2026-09-25).
Server version is assumed to be 5.28 (to be confirmed by the owner).

## Dependency availability (open issue)

`repo.bluecolored.de/releases` only publishes `de.bluecolored.bluemap:BlueMapCore` and
`BlueMapCommon` up to **5.3** (metadata last updated 2024-07). The snapshots repo stops at 4.1-45.
Version 5.28 is not published as a Maven artifact there, and the project is now built with
group `de.bluecolored` and subprojects `:core` and `:common`. Options: compile against the
5.28 release jar (`bluemap-5.28-paper.jar`, `compileOnly` from a local `libs/` file, not
committed), or build `:core` and `:common` from the v5.28 tag with Gradle and publish to
mavenLocal. To be decided with the owner.

## Interfaces to implement (package `de.bluecolored.bluemap.core.storage`)

`Storage extends Closeable`
- `void initialize() throws IOException` (new vs. the brief: must exist)
- `MapStorage map(String mapId)` (must return equal instances for the same id)
- `Stream<String> mapIds() throws IOException`
- `boolean isClosed()`; `close()`

`MapStorage`
- `GridStorage hiresTiles()`, `lowresTiles(int lod)`, `tileState()`, `chunkState()`, `regionState()`
- `ItemStorage asset(String name)`, `settings()`, `textures()`, `markers()`, `players()`
- `void delete(DoublePredicate onProgress) throws IOException` (`delete()` is a default method)
- `boolean exists() throws IOException`, `boolean isClosed()`
- static `escapeAssetName(String)`

`GridStorage`
- `OutputStream write(int x, int z)`, `@Nullable CompressedInputStream read(int x, int z)`,
  `void delete(int x, int z)`, `boolean exists(int x, int z)`, `ItemStorage cell(int x, int z)`,
  `Stream<Cell> stream()`, `boolean isClosed()`
- `Cell extends ItemStorage` with `getX()`, `getZ()`; helper `GridStorageCell`.

`ItemStorage`: `OutputStream write()`, `@Nullable CompressedInputStream read()`, `void delete()`,
`boolean exists()`, `boolean isClosed()`.

`CompressedInputStream(InputStream, Compression)` wraps the raw stream; the web handler sets
`Content-Encoding` from the compression id, so read() must return the *stored* (compressed) bytes.

`KeyedMapStorage` (abstract, used by SQL) is an alternative base, but the brief requires the
file layout, so we implement `MapStorage` directly.

## Key layout (from `FileMapStorage` v5.28)

- hires: `<map>/tiles/0/<gridPath>.prbm<compressionSuffix>`, lowres: `<map>/tiles/<lod>/<gridPath>.png`
- settings `settings.json`, textures `textures.json<suffix>`, `live/markers.json`, `live/players.json`,
  assets `assets/<escaped name split on />`
- **Render state differs from the brief**: `rstate/<gridPath>.tiles.dat`, `rstate/<gridPath>.chunks.dat`
  and `rstate/regions/<gridPath>.regions.dat`, all GZIP. There is a third state grid (`regionState()`).
- `gridPath`: encode `x<X>z<Z>`, start a new folder after every digit, last segment gets the suffix.
  Listing regex: `x(-?\d+)z(-?\d+)` after removing separators and the suffix.

## Config and registration

- `StorageConfig` (common.config.storage): abstract, `@ConfigSerializable`, field `storageType`,
  abstract `Storage createStorage() throws ConfigurationException`.
- `StorageType extends Keyed`: `getConfigType()`; `StorageType.Impl(Key, Class)`;
  `StorageType.REGISTRY.register(type)` (returns true if a key was already taken, sic).
- Config key is parsed with `Key.parse(value, "bluemap")`. Our key: `klrnbk-bluemap:s3` (owner's choice).
- Compression registry: `Compression.REGISTRY`, ids none, gzip, deflate, zstd, lz4.
- Addon descriptor `bluemap.addon.json`: `id`, `entrypoint` (a `Runnable`), optional `dependencies`,
  `softDependencies`. Gson with lower-case-with-dashes.

## Why render state must be local (confirmed in source)

`CellStorage.save()/cell()/loadCell()/saveCell()` are `synchronized` and call the GridStorage
directly, so any network latency there blocks every render thread.

## Web serving facts

`MapStorageRequestHandler` answers `204 No Content` for a missing hires/lowres tile and sets
`Content-Encoding` from the compression id. Webapp option `clientDecompression` (default false) exists
in `WebappConfig`.

## Deviations from the brief

1. `Storage.initialize()` exists; 2. `regionState()` exists; 3. render-state file names/layout as above;
4. Maven artifacts for 5.28 are not published.
