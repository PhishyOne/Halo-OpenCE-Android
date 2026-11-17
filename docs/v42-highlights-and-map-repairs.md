# Halo v42 — Verified repair maps and local highlight moments (phase 1)

Branch: `halo/highlights-and-map-repair-v42` on the user's public
`PhishyOne/Halo-OpenCE-Android` fork. No upstream PR, no default-branch
merge, no shared leaderboard, no hosted video service and no changes to
the Nomatoka production site.

## Campaign maps found and validated

The two files absent as *valid* Xbox caches in the user's previous
8/10 map audit were found on the public FractumSeraph Halo map host:
`https://halo.fractumseraph.net/assets/maps/b40.map` and
`https://halo.fractumseraph.net/assets/maps/c10.map`.

- **b40.map** / Assault on the Control Room:
  source 186,873,856 bytes; decoded 279,763,456 bytes;
  SHA-256 `0a7b26d6d828e7b773d36d9d5c798b9bf4b143332301a0c0e7a3d685782eab48`
- **c10.map** / 343 Guilty Spark:
  source 166,926,336 bytes; decoded 252,559,360 bytes;
  SHA-256 `d65683f92e2f78cf8ba4d31c26b212869652b5d5ec037add480865a49e18434e`

Both were downloaded into
`/mnt/nomatoka-terrain-scratch/halo-map-repair-candidates-20261117/`
on Forge's **secondary volume**, and passed the same exact
`SidewinderRepair.validate` method the Android launcher uses. It
checks the original Xbox v5 header, scenario identity, complete zlib
decompression, expected expanded size and `sgat` tag signature.

Two separately checked staging copies were placed at
`/sdcard/Download/Halo/Campaign-Repairs-Verified/b40.map`
and `.../c10.map` on the user's Android phone. They are not silently
installed into Android's protected app storage. This staging consumes
roughly 354 MB; the phone reported about 5.7 GB free afterward.
To install: **Halo → Tools → Map Repair → select Assault on the Control
Room → Import Map → b40.map**. Repeat for **343 Guilty Spark / c10.map**.
The importer revalidates and keeps the previous map under
`<name>.previous`. Re-run **Check All Campaign Maps**; 10/10 is the
target. Only then export the complete Admin Repair Library ZIP to a
chosen storage location and keep it as a recovery archive.

Neither full engine runtime, rendering nor co-op hosting has been
proven for these replacement maps yet. The original Xbox game assets
are third-party hosted and copyrighted; do not commit or publicly
redistribute them through the source repository. Keep asset use
consistent with the rights you hold to your original copy.

The reusable CLI `XboxMapValidationCli.java` is source-only and
performs a read-only validator plus SHA-256 on explicit files. There
are no copyrighted map files inside v42 source or APK.

## Match highlights — phase 1 *markers*, not an MP4 reel

The first stage integrates a tiny Android-only native event selector:

- `halo_highlights_start()`: resets an in-memory 256-event candidate
  buffer at each multiplayer match start
- `halo_highlights_kill()`: records each locally credited human/bot
  kill, elevated for verified 3/5/10-kill streaks, with game tick
- `halo_highlights_ctf_capture()`: records an actual flag score
  credited to the local player in the CTF scoring function
- `halo_highlights_finish()`: invoked only after the local HC2 career
  record has been durably written at postgame. It adds a win marker
  when applicable, selects up to three highest-scoring moments,
  enforces four seconds of spacing, and writes them chronologically
  to `career-highlights-v1.log`.

The selected moments include event type, match UUID shared with the
existing local career record, map identity, exact 30 Hz game tick,
importance score, and a proposed clip window (five seconds before,
three seconds after, clamped to the match). The file stays under the
same app-private data root as the career journal, never stores player
names or IP addresses, and has a **4 MiB storage cap**. No disk writes
on every kill, no continuous screenshots, no GPU readback, no network,
no cloud account, no replay playback, no MP4 encoding.

The Android `MatchHighlights` reader checks HLM1 line format,
identity, allowed events, numeric bounds, complete newline endings,
duplicate event IDs and per-match caps, then joins each marker to an
actual HC1/HC2 match with the **same map and unique match ID**.

The Profile tab shows recent selected highlight moments and a
**View Moment Timeline** button. Full Service Record opens the
per-match timeline. It explicitly warns these are timestamped
event bookmarks, **not recorded gameplay video or a 3D replay**.
The UI does not fabricate missing moment recordings from an HC2
scoreboard, and current v41/earlier matches cannot gain real
event ticks retroactively.

### Real replay / video — next gate, not claimed complete

A separate public OpenCE fork by FractumSeraph has an existing
`port/linux/game/network_recording.c` and HREC format, with a
JavaScript map-based replay viewer. Porting it safely needs adapting
fork-specific `network_fairplay`, event logging, player identity,
bots, network sync and scene/animation recording. It is **not** a
drop-in recorder for this fork. Phase 2 should design a bounded host
replay format compatible with our custom network and map build,
then benchmark file sizes, CPU and dropped frame time on the
Android phone. Phase 3 can use an optional Android hardware encoder
to produce an actual MP4 from a verified replay or video source.

No storage-expensive recording should be enabled by default until
physical performance testing and the user's explicit preferences
for per-match retention and sharing.

## Verification

- Pure C: `test_highlight_moments.py` compiles and runs the production
  selector/writer; checks streak threshold, flag capture, time windows,
  de-dup, chronological order, invalid match IDs, and empty matches.
- Native career fixture: four HC2 games + actual highlight module
  linked, no duplicate postgame, quit handling.
- Pure Java: `MatchHighlightsTest.java`, with malformed records,
  duplicate/partial handling, file cap and verified HC match ID join.
- Java SDK 35 compilation with existing SDL/resource stubs.
- Android-only exact-head CI, and one signed APK with versionCode 42,
  matching v41's signing identity and original app icon/fonts.
- Physical acceptance: import b40/c10, check 10/10, then play an
  actual multiplayer match with human or bot enemies. After reaching
  postgame, reopen Profile and inspect three tick markers, including
  their actual event timestamp, linked to the correct match. Match
  crashes should create no finalized moments.
