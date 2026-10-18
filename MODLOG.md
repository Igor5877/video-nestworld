# Video Nestworld — GUI and screen assembly

2026-10-17. User scope: improve GUI and build rectangular walls of screen blocks,
auto-assemble from any tile; maximum requested size 100 × 100.
Existing route: Forge 1.20.1 / 47.4.6, Java 17, WaterMedia Curse file 6875641.
Use the existing loader APIs and preserve block IDs and old NBT keys.

Design: Minecraft font, slate panels (#263341), pale text (#E8EDF2), muted text
(#AAB8C7), warm cinema gold (#E7BE70), blue screen glass (#365C78).
Two tabs: searchable video catalogue with paging; screen construction with a
proportional preview and explicit assemble/separate actions. No fixed 4-item cap.
All labels have Ukrainian and English translations. One movie surface per screen.

Source findings: old manual assembly accepts holes and mismatched facing, stale
links after breaking tiles; controls overflow the GUI. Pause currently releases
the player; client ignores selection updates while stopped. Hotkey menu is a stub.

Validation: isolated test profile under build (never existing run saves/configs).
Baseline offline build cannot resolve Forge 47.4.6 fml artifacts; retry online.
Knowledge search: no matching WaterMedia screen note in local um KB.

Implementation: bounded planar BFS (`ScreenLayout`, max 100 per axis) before any
mutation; any-tile controller routing, facing inheritance while building, break
cleanup, deferred validation when chunks load. Server checks active menu and
interaction distance; video selection must match the server catalogue. GUI tabs,
category cycling, case-insensitive search, pagination, status and local volume.
Hotkey now opens the aimed-at block instead of the old nonfunctional catalogue.
Pause keeps decoder; stop releases it. NBT and protocol distinguish both states.
One master renders the entire aspect-correct video quad. Audio attenuation uses
distance to the screen surface rather than a corner/center. Creative entry,
crafting and block loot added. Corrected package identity/version and EN locale.

Validation 2026-10-17: two hidden real Forge client runs passed. Tests cover all
four horizontal facings, arbitrary tile origin, NBT round-trip, 100×100 (10,000
owners verified), 101-column rejection, holes/mixed facing, breaking master/slave,
repair, real C2S/S2C GUI assembly, 56-video catalogue paging/search, video selection
while stopped, real VLC decode, pause/resume preserving the same decoder, stop
and separation. First screenshot review found overlapping layout text; corrected
and visually rechecked at both GUI scales. Second 100×100 assembly took 383 ms in
this synthetic test (not a general performance guarantee). Source changes after
this check: preserve catalogue selection when expanding from a new tile; audio
surface distance; clear client catalogue on logout. Final rerun planned below.

Known limits: the server sends a playback clock correction every five seconds;
network loss can cause a short rebuffer before correction. A fully visible huge screen requires its master chunk to be loaded. Layout
scans require loaded boundary chunks and never force-load. No dedicated-server
end-to-end test; integrated-server network paths tested. Existing WaterMedia VLC
startup diagnostics include unsuccessful discovery/interface attempts; decoded
frames verified despite these messages. Original run/ and game profile untouched.

Post-release fix: AniUA's `video_url` is often an Ashdi/Moon HTML player page,
not a playable media URL. The adapter now fetches that page asynchronously,
extracts the provider's direct m3u8/mp4 URL, drops unresolved entries, and reports
an actionable error when no stream can be resolved. Verified against the real
AniUA slug `neko-no-ongaeshi-8f8f88`: one episode resolved to a direct m3u8.

AniUA integration: added a clean-room Java HTTP adapter for the public
`https://api.aniua.app/v1/episodes?slug=...` endpoint and operator command
`/aniua <slug>`. It validates the slug, uses HTTPS/HTTP URLs returned by AniUA,
filters unusable episodes, runs the request off the server thread, writes the
merged catalogue to `config/videos.json`, and broadcasts it to connected clients.
No AniUA application source, credentials, or token is bundled. This was based on
the public endpoint and is intentionally separate from the restricted AniUA app
source. Build verified after integration: `clean build` succeeds.

Final runtime check passed, including catalogue preservation on expansion.
100×100 assembly: 408 ms in the synthetic fixture. Screenshots and pass markers
preserved in dist/1.1.0 before the clean release build.

Clean release build succeeded. Inspected JAR: version 1.1.0, translations and
recipe included; no SelfTest, Minecraft or WaterMedia classes. SHA256: a2ffb331fe74c120be882521caf568bde5df50cd54365195b54258f0ed33bcf7

## 2026-10-18 — GUI 1.2.0

Replaced the flat episode/dub catalogue with Search AniUA, Library and Screen tabs.
Search accepts a partial title and returns explicit Hikka choices; never guesses
one anime from an ambiguous substring. Separate release, dub, numeric episode and
source selection. Franchise endpoint supplies related seasons and films. Season
numbers come from title/slug; unknown numbers are not invented from calendar seasons.
Hikka posters load asynchronously, with bounded bytes/dimensions and per-menu
texture disposal. Kept existing screen construction and playback behaviour.

Route: extend existing Forge GUI and typed network packets, using public Hikka
OpenAPI /anime, /anime/{slug}, /anime/{slug}/franchise and AniUA v1/episodes.
No command permission elevation in GUI. Validate active menu/distance and limit
in-flight requests; ignore stale replies. Catalogue merge remains on server thread
with atomic disk replacement. Protocol 3 requires matching client/server mod.
Optional Video/Category metadata preserves old JSON; unavailable streams remain
visible with disabled episode buttons. Explicit source mapping was preserved.

Design: Minecraft font, navy #1D2A36, row #34495C, focus #48617A,
text #E8EDF2, muted #AAB8C7 and gold #E7BE70. Release poster beside left-aligned
voice lists/episode grid; playback footer stays in place. Separate library filter
and remote query prevent stale categories from hiding results.

Validation: two hidden real Forge runs in build/selftest-run. Final run passed
layout/playback regression, partial "неос" search through Enter and real C2S/S2C,
explicit Grand Blue selection, real API episode metadata, poster texture upload,
Togarashi episodes 1–12 in numeric order, first-episode selection on server,
franchise seasons with undated releases last, and both GUI scales. Screenshots
inspected; close-button truncation corrected. Standalone CatalogueSelfTest passed
legacy JSON fallback, unavailable/empty releases, sorting, metadata, poster and
season parsing. No dedicated remote-server test or full remote-stream playback
claim; the playback regression decodes the isolated local test pattern.

Artifacts: dist/1.2.0/screenshots and selftest.log. Existing profile mods backup:
/home/igor/.universal-modder/backups/nestworld-before-gui-1.2.0/20261018-004415.zip.
Install target: /home/igor/.minecraftx/instances/Pizdec-1.0.0/mods.

Clean release build succeeded and JAR inspection confirmed no SelfTest classes.
Installed nestworldvideo-1.2.0.jar; build/dist/installed SHA256 all match:
92555be69637e1463f2fc8be6c352918e32a6f38c0361d8d04b5d35bba57bd81.
Previous JAR also retained outside mods in the profile's
mod-backups/before-1.2.0-20261018-004719. Profile now has exactly the new
Nestworld JAR and its existing WaterMedia 2.1.37 dependency.

Ashdi fix: AniUA `video_url` pages such as `/vod/54086` now resolve their
allow-listed `file: ... index.m3u8` entry asynchronously. A real request for
`high-school-dxd-new-f6ab27` resolved 13/13 episodes; the manifest starts with
`#EXTM3U`. Existing saved AniUA categories with blank URLs refresh automatically
when opened from Library. Release 1.2.1 is installed in the profile.
The rebuilt JAR includes the automatic stale-catalog refresh. Final
build/profile SHA256: 3b0b94f1b8afe07a9000aa9aa9343c5a35f099690afaf02b250dee9de951367a.
Backup before replacement:
/home/igor/.universal-modder/backups/nestworld-before-ashdi-refresh-1.2.1/20261018-005553.zip.

Movie UX fix 1.2.2: a Hikka `movie` with no AniUA episodes now says that the
film has no available AniUA video stream, instead of implying missing episode
numbering. The playback footer is labeled `Екран:` so an older video selected on
the physical screen is not confused with the item currently being browsed.
Loading animation 1.2.3: while a screen has a playing URL but WaterMedia has no
texture yet, the block renderer draws a dark panel with twelve animated dots.
The dots scale with the wall and change to red when the player reports broken;
the animation also covers the interval before the Display object is created.

## 2026-10-18 — GUI availability labels 1.2.4

Checked the real AniUA response for `kimetsu-no-yaiba-katanakaji-no-sato-hen-99b997`.
Amanogawa episodes 1–6 point to Ashdi pages that currently return `Файл не знайдено`.
Other Ashdi streams, including FanVoxUA, return a valid HLS playlist and segments.
The voice picker now shows `доступні/всього` episode counts, disables voices with no
accepted direct streams, and marks the episode view when the selected voice has no
playable stream. This prevents a deleted provider page from looking like a GUI bug.

## 2026-10-18 — Public-server load limits 1.3.0

AniUA episode responses are cached on the server for 15 minutes and Hikka search,
detail and franchise responses for 5–15 minutes. Concurrent requests for the same
key share one future, so many players opening the same title do not multiply remote
API calls. `/reloadvideo` clears both remote caches.

Client playback is bounded by a generated Forge client config: eight active streams
and 128 blocks by default. A 100×100 assembled wall still uses one player; distant
or over-budget independent screens wait until they become eligible. Video bytes are
downloaded directly by each client from the provider, so the Minecraft server does
not proxy 100 HLS streams.

Playback synchronization 1.3.0: the server stores the media position in milliseconds
and includes it in play, pause, stop and selection packets. Nearby clients receive a
clock correction every five seconds; WaterMedia seeks only when drift exceeds 750 ms,
so normal network jitter does not cause constant seeking. Network protocol version
is now 4; 1.2.x clients and servers must be upgraded together.

## 2026-10-18 — Distance rendering fix 1.3.1

The video surface now uses a larger front offset and a controlled polygon offset,
and paints a stable dark backing quad before the aspect-ratio-correct video quad.
This removes long-distance z-fighting and jagged letterbox edges against the block
model. The playback clock correction from 1.3.0 is included; protocol version stays 4.

## 2026-10-18 — Front-face z-fighting fix 1.3.3

The static front face was removed from the block model. The custom renderer now owns
the complete visible screen surface, so the model texture cannot leak through the
video at distance or form black stippled seams on the letterbox edges.

## 2026-10-18 — Smooth startup and no accidental repeat 1.3.4

Decoder creation and retry now happen from the client tick, never from the block
render callback. WaterMedia starts paused, seeks to the server clock before the
first frame, and has repeat mode disabled. Periodic sync packets are ignored after
a finite episode reaches its end, so an ended episode cannot restart from the
beginning by itself.

## 2026-10-18 — Viewer heartbeats 1.3.7

Clients report a lightweight heartbeat only while a screen decoder is active. The
server keeps a per-dimension viewer list, exposes the count and names in the screen
GUI, and sends periodic clock corrections only to active viewers. A slow client
buffers independently; it does not proxy or multiply video traffic through the
Minecraft server.
The network protocol is now version 5; update the server and all clients together.
Screens loaded after playback has already started are observed without creating a
decoder from the render callback; the decoder is started on the client tick.
