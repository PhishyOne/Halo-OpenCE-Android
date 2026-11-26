# Halo v59 — Automatic local social hub

Leaderboard is now the first launcher screen, except when handling an
explicit internet invite or importing missing game data. Compact rows show
rank, player name, live state, Practice SR, K/D, wins and XP. Select any
row, including yourself, for an actual Halo-themed service-record page.
A separate video-library page lists local or remote saved videos; Back
returns to the player report, then to the leaderboard. These pages reuse
the launcher Activity and its existing scroll area and live monitor. The
own report retains career achievements, match history, and local video
management, export and diagnostics. Remote reports display only exchanged
summary fields and update when stats arrive. No generic player/catalog
dialogs. Named peers use the existing LAN identity. Cached offline players
remain visible and their last-sync dates stay separate from live presence.

One LAN SHARING • STATS + HIGHLIGHTS switch is enabled by default, unless
the old preference explicitly disabled sharing. This preserves an earlier
choice to opt out. A single switch disables both; no separate confirmation
or per-match video sharing checkbox. Android screen-capture permission is
unchanged and still required to create new recordings. A visible notice
explains that any reachable same-LAN device can access shared saved reels;
this trusted-friends protocol is not encrypted/authenticated.

Auto-sync runs only while the launcher is visible: newly detected named
peers are synchronized in bounded batches and again around every 30 seconds.
The game process responds while opted in, including campaign and offline
matches. The launcher initiates sync; two game processes do not independently
poll during a match. Existing explicit refresh and manual IP fallback remain.
No server/cloud traffic. No automatic uploads or persistent presence cache.

HBP2/HPA2 advertises video availability while preserving HBP1/HPA1 fallback.
Tap a live player, VIEW HIGHLIGHTS / VIDEOS, then choose a dated saved reel.
HRL1/HRQ1 only expose completed reel-[32 hex].mp4 files in the app's own reels
folder, not originals, pending files, exports or arbitrary paths. The server
checks private interface/subnet and master setting on each bounded request.
Catalog up to 32 reels, 48 MiB per reel, chunks of 256 KiB. The MediaDataSource
reads exact ranges through an in-memory buffer; MediaPlayer/TextureView
provide play/pause/seek. No remote MP4 is written to disk. Closing the viewer
or leaving the launcher closes playback and the current connection. Sender
revocation stops new requests; already buffered bytes may remain briefly.
No gameplay network wire changes.

Acceptance needs two phones running v59: check default sharing and prior
explicit off, verify automatic sync and last-sync dates, launcher/game
handoff, two-way playback and seek, delete/disable/disconnect during a
stream, and verify full match recordings cannot be requested. On-device
decoder behavior and hotspot throughput remain unverified.
