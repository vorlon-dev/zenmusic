# zenmusic

**One dependency. The whole engine. Zero platform fingerprints.**

`zenmusic` is a Kotlin library extracted from the engine of
[Zen-music](https://github.com/vorlon-dev/Zen-music): search + stream
extraction, BotGuard **PO-token minting**, stream resolution, and a Media3
transport — all bundled. An app plugs it in, wires one init call, and plays.
It never touches a third-party extractor, a minter SDK, or a media URL: the
upstream platform is **architecturally invisible** behind the disguised
`zen://` scheme.

```kotlin
// 1. settings.gradle.kts → repositories { maven("https://jitpack.io") }
// 2. build.gradle.kts    → implementation("com.github.vorlon-dev:zenmusic:v1.1.0")

val zen = ZenMusic.initialize(context)

lifecycleScope.launch {
    val songs = zen.search.songs("night drive")        // List<ZenTrack> — zen:// uris
    val stream = zen.resolve(songs[0].uri)             // opaque handle, no URLs
    player.setMediaItem(ZenMediaItems.from(stream, songs[0].title))
    player.prepare(); player.play()
}

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(zen.dataSourceFactory))
    .build()
```

Everything the app ever sees looks like:

```
zen://dQw4w9WgXcQ.google.video          ← play uri (pseudo-host decoy)
zen://a/dQw4w9WgXcQ.google.video        ← artwork uri
```

No `youtube`, no `googlevideo`, no media URLs — in the API, in the logs, or
in the app-facing binary surface (internals repackage to `zen.a.b.*`).
Verified by `strings` on the R8-minified sample APK: the engine's own dex
is marker-free except **one inert placeholder** (`x.googlevideo.com`, a
fake host inside the cipher engine's URL-parsing template — not an
endpoint); the bundled cipher configs are fully obfuscated (0 markers).

---

## Modules (cherry-pick or take the umbrella)

| Coordinate (`com.github.vorlon-dev:…`) | What it gives you | Depends on |
| --- | --- | --- |
| `zenmusic` | **Umbrella** — one-line wiring of everything below | all |
| `zen-core` | Models, `zen://` scheme, AES-256-GCM vault, decoy shower, log scrubber | — |
| `zen-cipher` | Vendored [zemer-cipher](https://github.com/ZemerTeam/zemer-cipher): WebView BotGuard PO tokens + sig/n decipher, remote self-healing player configs | — |
| `zen-potoken` | PO-token coordinator (caching, single-flight, backoff) + Rhino fallback minter | core |
| `zen-extractor` | InnerTube client: music + open-platform search, streams, quality ladder, radio/related, LRCLIB lyrics | core, potoken |
| `zen-resolver` | `zen://` → opaque stream handles + byte-channel transport | extractor |
| `zen-datasource` | Media3 `DataSource`/`Factory` (serves **streams and artwork uris**) + `MediaItem` builders | resolver |

Feature scope: **search** (songs — music catalog **+ open-platform uploads**
merged, videos/albums/artists), **streams** (resolve-and-play), **artwork**
(covered end-to-end through the engine), **synced lyrics** (LRCLIB),
**radio + related** (autoplay queue), **quality ladder** (LOW → MEDIUM →
HIGH → best-available with automatic fallback). minSdk **26**.

### Resolver-only architecture

Per design, `zenmusic` does **not** own a player or a notification service.
Your app keeps its own Media3/ExoPlayer (and its own UI/session logic); the
library feeds it through `ZenDataSourceFactory`. Your player stack never
learns where the bytes come from.

---

## What's built in (and hidden)

- **Stream extraction** — hand-rolled InnerTube client (WEB_REMIX → VISIONOS
  1.02/1.03 → TVHTML5_SIMPLY identity chain — the zemer-app device-verified
  set; IOS/ANDROID_VR are proven dead for full-song delivery — automatic
  visitor-data bootstrap, signatureTimestamp, playability handling).
- **PO-token engine (zemer-cipher)** — WebView-sandboxed BotGuard minting
  with **remote self-healing player configs**: when the upstream rotates its
  player, the vendored engine refreshes its configs at runtime — no app
  update needed. Session-bound tokens ride the `player` request;
  content-bound tokens (`&pot=`) + `n`-parameter transform ride every stream
  URL (both verified against the live CDN — the URL token must bind to the
  VIDEO id, not the visitor). Cached with TTLs, single-flight, failure
  backoff, graceful degradation to tokenless fallback clients.
- **Stream resolver** — broker that keeps real media locations XOR-obfuscated
  in memory, hands out random opaque handles with TTLs, and supports seek.
- **Stream transport** — Media3 DataSource; `getUri()` keeps reporting the
  zen:// uri even inside player diagnostics.
- **AES-256-GCM vault** — every endpoint host, path, API key, client
  identity, header name, and the decoy suffix is stored as ciphertext
  (`iv‖ct‖tag`), decrypted transiently at runtime. Key = `SHA-256(A⊕B⊕C ‖ domain)`
  split across three classes. Tampered ciphertexts fail loudly (GCM).
- **Fake-stream shower** — plausible-but-synthetic streams/URLs/handles for
  pollution; served *instead of* real playback metadata when tampering is
  detected (debugger/emulator watch on Android, pluggable via
  `TamperDetector`).
- **Log scrubber** — even with debug logging enabled, hosts/tokens/IPs are
  redacted before leaving the process; R8 strips call sites in release.
- **ProGuard/R8 hardening** — full-mode keeps only the facade, repackage to
  `zen.a.b.*`, logging stripped, optional junk-identifier dictionary
  (`proguard-dict.txt`).

See **[docs/PROTECTION.md](docs/PROTECTION.md)** for the full threat model
and the honest limitations.

---

## Wiring guide

### A. Take everything (umbrella)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }

// app/build.gradle.kts
dependencies {
    implementation("com.github.vorlon-dev:zenmusic:v1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
}
```

### B. Cherry-pick modules

```kotlin
implementation("com.github.vorlon-dev:zen-core:v1.1.1")
implementation("com.github.vorlon-dev:zen-potoken:v1.1.1")
implementation("com.github.vorlon-dev:zen-extractor:v1.1.1")
implementation("com.github.vorlon-dev:zen-resolver:v1.1.1")
implementation("com.github.vorlon-dev:zen-datasource:v1.1.1") // optional
```

### Release builds

Copy nothing: `zenmusic/consumer-rules.pro` + `zenmusic/proguard-rules.pro`
ship inside the AAR and apply automatically when your app builds with R8.
For the optional junk-identifier renaming, copy `proguard-dict.txt` next to
your app's rules and add:

```
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt
```

---

## API surface (safe facade)

```kotlin
ZenMusic.initialize(context, config): ZenEngine
ZenMusic.require(): ZenEngine
ZenMusic.shutdown()

ZenEngine
 ├─ search.songs(query, scope?)                // List<ZenTrack> — ALL scope merges both catalogs
 ├─ search.openVideos(query)                   // open-platform uploads only
 ├─ search.videos/albums/artists(query)        // music-catalog shelves
 ├─ search.all(query)                          // SearchResults (music shelves + open uploads)
 ├─ radio.radioFor(uri) / radio.related(uri)    // List<ZenTrack> autoplay queue
 ├─ lyrics.get(track): ZenLyrics?               // synced LRC / plain
 ├─ track(uri): ZenTrack                        // metadata
 ├─ resolve(uri, quality?): ZenStream           // opaque handle + format meta
 ├─ artwork(uri): ByteArray?                    // disguised artwork fetch
 ├─ artworkBitmap(uri): Bitmap?                 // decoded, ready for ImageView
 └─ dataSourceFactory                           // → your ExoPlayer (plays + serves artwork)

ZenStream   // uri, format(quality/bitrate/codec/container), isLive, handle, expiry
ZenTrack    // + source: ZenContentSource (MUSIC / VIDEO), viewsCount
ZenMediaItems.from(stream, track?)              // MediaItem — full metadata + artworkUri
ZenMediaItems.from(stream, title?, artist?)     // MediaItem (handle in #zh= fragment)
```

### Search scopes

```kotlin
zen.search.songs("query")                                  // ALL: music first, then open-platform uploads
zen.search.songs("query", ZenSearchScope.MUSIC_ONLY)       // classic music-catalog behavior
zen.search.songs("query", ZenSearchScope.OPEN_VIDEOS)      // open-platform uploads only
zen.search.openVideos("query")                             // same, explicit
```

Open-platform results carry `source = ZenContentSource.VIDEO` and resolve
through the same engine and the same `zen://` uris — one playback path for
everything.

### Artwork

Every `ZenTrack` carries `artworkUri: zen://a/<id>.<decoy>`. Two ways to
render it:

```kotlin
// 1) In-app UI — one call, bitmap ready for ImageView:
lifecycleScope.launch {
    imageView.setImageBitmap(zen.artworkBitmap(track.artworkUri))
}

// 2) Media notification / MediaController / Android Auto — the zen datasource
//    serves artwork uris, so plug a DataSourceBitmapLoader into your MediaSession:
MediaSession.Builder(context, player)
    .setBitmapLoader(
        CacheBitmapLoader(DataSourceBitmapLoader(context, zen.dataSourceFactory))
    )
    .build()

// and build MediaItems with full metadata (title/artist/album/artworkUri):
player.setMediaItem(ZenMediaItems.from(stream, track))
```

No real image host ever reaches app code — bytes are fetched by the engine
with the neutral engine UA and decoded on IO.

JVM-only embedding (no Android) is supported for everything except
`zen-datasource` and the umbrella's tamper detector — the four JVM modules
compile and test on any JDK 17+.

---

## The zen:// scheme

```
zen://<media-id>.<decoy-suffix>          stream (playable)
zen://a/<media-id>.<decoy-suffix>        artwork (fetch via engine)
zen://b/<browse-id>.<decoy-suffix>       album/artist browse reference
```

- The **decoy suffix** (default `google.video`) is a fake pseudo-host stored
  sealed in the vault — the artifact contains no plaintext of it.
- The scheme is **engine-agnostic**: URIs do not encode which upstream
  platform served the item, and the resolver is the only component that
  knows.
- `#zh=<handle>` fragments (added by `ZenMediaItems`) carry the opaque
  broker handle for fast re-open; without one the resolver re-resolves.

---

## Building & publishing

```bash
./gradlew build                      # all modules + tests
./gradlew :zenmusic:assembleRelease  # umbrella AAR
./gradlew publishToMavenLocal        # local install
```

**JitPack:** push this repo to GitHub, then cut a tag — the tag string is
the version consumers use (a `v`-prefixed tag serves as `v1.1.0`):

```bash
git tag -a v1.1.0 -m "ZenMusic 1.1.0 — open-platform search scopes + end-to-end artwork"
git push origin v1.1.0
```

JitPack builds all modules on request (see `jitpack.yml`: JDK 17 +
`publishToMavenLocal`); watch the build at
`https://jitpack.io/com/github/vorlon-dev/zenmusic/v1.1.0/build.log` or
browse versions at `https://jitpack.io/#vorlon-dev/zenmusic`. Verified:
both group forms resolve — `com.github.vorlon-dev:zenmusic:v1.1.0` (short,
repo-level alias) and `com.github.vorlon-dev.zenmusic:zenmusic:v1.1.0`
(canonical multi-module form); transitive modules publish under
`com.github.vorlon-dev.zenmusic:<module>` in either case.

**Snapshots:** without a tag, branch HEAD builds on request as
`main-SNAPSHOT`:

```kotlin
implementation("com.github.vorlon-dev:zenmusic:main-SNAPSHOT")
```

**Rotating the vault** (new client versions / updated endpoint values):
edit the secret table in `scripts/gen_zenvault.py` (+ `gen_zenvault_extra.py`
for the attestation ids), run `gen_zenvault.py`, `gen_zenvault_extra.py`,
then `gen_kotlin_consts.py` — which regenerates `ZenVaultConsts.kt` — and
copy the printed fragments into `ZenVaultKey`. Re-tag a release.

---

## Honest notes (read this)

- **Extraction drifts — now self-healing.** InnerTube clients, BotGuard, and
  PO-token enforcement change over time. PO-token minting runs on the
  vendored zemer-cipher engine whose player configs refresh at runtime from
  upstream CI. The InnerTube client identities (versions/UA/device fields)
  are vault-sealed and refreshed with the vault generator
  (`scripts/gen_zenvault.py`) — one re-seal + re-tag per platform rotation
  (last refresh: 2026-11: WEB_REMIX `1.20260707.12.00` (Firefox UA),
  VISIONOS `1.02`/`1.03`, TVHTML5_SIMPLY `1.0`).
- **No obfuscation is unbreakable.** This stack removes plaintext platform
  markers and makes static analysis noisy, but a determined runtime
  analysis can always observe network traffic. Design accordingly.
- **LOSSLESS tier** = "highest available bitrate" for engines that don't
  serve true lossless audio; `ZenFormat.genuinelyLossless` is `true` only
  for genuinely lossless codecs.
- **Legal:** this project is derived from the Zen-music engine and
  vendors [zemer-cipher](https://github.com/ZemerTeam/zemer-cipher)
  (`zen-cipher` module, sources unmodified, license preserved). Released
  under **GPL-3.0**. Use it for personal/educational purposes and
  respect the terms of the platforms you access through it.

## License

GPL-3.0 — see [LICENSE](LICENSE).
