# Halo v51 — Opt-in real gameplay video highlights (Android MVP)

## Goal, feature boundaries

Halo v42–v50 saved correct per-match `HLM1` event markers but **no
video frames**. v51 adds an **actual Android MediaProjection capture** into
a private local H.264 MP4 session, then extracts video samples from
the selected 3 highest-priority match moments using
`MediaExtractor` and `MediaMuxer`. It produces **playable .mp4
reel files containing real gameplay footage** once the player has
consented to recording and completed a match with eligible HLM1
markers. Not a reconstructed HREC replay, not a cloud render, and
not video retroactively generated from existing v50 bookmarks.

This is an experimental, **video-only** first release: no game
soundtrack or voice audio is recorded, no cinematic transitions,
and highlight cuts may be offset until real-device clock alignment
has been tested. There are no server writes, network sockets, analytics
SDK, microphone recording, camera recording, new LAN packets or
changes to career XP and saved matches.

## Capturing

- Launcher has **RECORD MATCH VIDEO (OPT-IN • SILENT • 20 MIN MAX)**,
  initially OFF. Setting persists locally but every recording still
  requires a new system capture approval on Android 14 and later.
- User chooses Play. If opt-in, Android shows a screen-recording consent
  dialog. Denial/error allows normal gameplay without video.
- Android 14+ requests capturing the **default display**; the screen can
  include other visible content/notifications if the player changes
  apps while recording. Avoid sensitive content and use the STOP
  RECORDING notification action.
- A private foreground service in the launcher's Java process manages
  `MediaProjection`, one `VirtualDisplay`, a `MediaRecorder` and
  finalization. It is isolated from the native `:game` process to
  avoid disturbing its fixed guest-memory window.
- Hardware H.264 video-only, target 640px width with original
  landscape aspect ratio, 20 fps and 1 Mb/s. Recording stops at
  **20 minutes or 160 MiB**, when projection is revoked, or when
  Halo's `:game` process exits. If a game crashes before the
  first process poll, the recorder also stops after a grace period.
- Refuse recording if less than **500 MiB** free. No endless
  background capture or user-invisible permission reuse.
- `reels/session-<epoch_ms>.mp4` is finalized from `.pending`
  only after MediaRecorder.stop and MP4 metadata validation. One
  bounded, fsynced private `reels/video-sessions-v1.log` index
  stores filename, start/end UNIX timestamps and encoder dimensions.

## Assembling reel footage

- The existing `career-matches-v1.log` (HC1/HC2/HC3) contains
  finished match timestamps and durations; `career-highlights-v1.log`
  (HLM1) contains 30 Hz tick windows and exact match IDs.
- A pure-Java matching model converts ticks into estimated wall-clock
  footage offsets: **end time – recorded match duration + tick / 30**.
  Only matching match UUID/map pairs in the same captured session
  produce clip windows. Negative/out-of-range windows are dropped,
  overlapping windows merged, and unfinished/co-op matches ignored.
- After service stops, a local-only worker uses MediaExtractor to
  seek to H.264 keyframes and MediaMuxer to join actual recorded
  compressed frames into `reels/reel-<matchid>.mp4`. This path
  doesn't re-render scenes or re-encode, so it is relatively cheap.
  The output is seekable MP4, playable and optionally exportable.
- Clip starts can include a little additional pre-roll because
  H.264 can be cut at preceding keyframes. No intentional soundtrack
  or scene/title transition has been baked into export.
- If a capture reaches its cap before the match ends, generation
  is retried when the launcher next resumes and the career record
  is available. A failed mux or missing marker leaves the original
  full recording unchanged; it never invents footage.
- App retains the latest **3 finished reels** and **2 original
  recordings** where possible. Older original recordings are only
  cleaned after at least one valid reel exists. This is an
  intentionally bounded, opt-in storage policy.

## Viewing and exporting

- Profile → **WATCH / SAVE LOCAL VIDEO HIGHLIGHTS** opens
  **HALO VIDEO HIGHLIGHTS**. It lists finished actual MP4 reels
  and plays them inside Halo's launcher with normal media controls.
- **SAVE A COPY** opens Android ACTION_CREATE_DOCUMENT. It streams
  the chosen existing MP4 in 64 KiB blocks to the player-selected
  folder and retains the original. It doesn't publish a URL,
  broadcast it to LAN guests, or share files without explicit choice.

## Build and acceptance gates

Source branch `halo/mp4-highlights-v51`, derived from v50 signed source.
Build exact head once with Android-only signing (versionCode 51).
No upstream PR, main merge or production server storage.

Local code checks:
- Android 35 Java source compiles.
- ReelSessionIndexTest uses real HC3 match record syntax, tests
  wall-clock mapping, overlapping moments, bounds and tamper rejection.
- Source-contract tests enforce consent-first recording, private
  FGS, bounded storage, remuxing and manual SAF export.
- Existing Halo 30+ regression suites and CI build/test gate.

**Physical phone testing still required:**
1. Install v51 over v50, keep saves/XP unchanged.
2. Enable RECORD MATCH VIDEO before Play and approve Android's
   screen-recording permission. Leave notifications/private apps alone.
3. Start one short offline Slayer match, achieve 3+ kills without
   dying, then finish the match and exit Halo.
4. Reopen Profile, Watch Video Reels, play the newly generated
   actual MP4 and Save a Copy to Downloads.
5. Verify footage shows the kill itself at the selected times.
   Compare with HLM1 event timestamps; report skew if any.
6. Verify recording notifications stop when game exits or consent
   is revoked, Play still works when capture consent is denied,
   and performance/heat/storage remain acceptable.
7. Reopen and inspect existing full match history and prior XP.
   No past highlight markers should turn into fabricated videos.

**Limitations before claiming fully finished reels:**
- Captured video is silent in this iteration.
- Real gameplay and MP4 rendering cannot be verified until the
  user grants MediaProjection consent on the Android device.
- Epoch/time mapping can drift on a slow device, lengthy pauses,
  or device clock jumps. A later iteration should use clock
  synchronization from actual in-game event times.
- Capture stops after one 20-minute session; long multiplayer
  evenings need multi-segment rolling capture, a future feature.
- Android MediaProjection consent and foreground service are required;
  system/device policy can forbid capture.
