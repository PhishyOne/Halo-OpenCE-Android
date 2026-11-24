# Halo v54 — Diagnose black highlights without losing original video

Issue reported from phone testing v51/v52: **one 24-second highlight reel
played as a completely black image**. No original MP4 was uploaded or
accessible through the Termux RDC agent because Android scoped storage
denies Termux access to Halo's private Android/data directory. At this
point the cause is **unverified**.

The reel duration itself is consistent with the existing selection of
up to **three ~8-second HLM1 moment windows**. It does not prove that
only 24 seconds were captured by Android. The original recorder starts
when the player explicitly enables recording and approves the Android
MediaProjection prompt, and stops when the game activity/process exits
or reaches 20 minutes / 160 MiB. Every new capture needs a fresh
Android user consent. This is not seamless all-night recording yet.

## Source-derived mechanisms to distinguish

- **Original nearly black and reel black**: the MediaProjection/encoder
  likely failed to receive the game's rendered pixels; Android secure
  surfaces, captured-display mismatch, projection termination, or OEM
  rendering restrictions remain hypotheses, not verified facts.
  Avoid blaming the cutter before checking the original.
- **Original contains gameplay, reel nearly black**: the selected HLM1
  times may be wrong (elapsed simulation ticks vs wall-clock and lobby
  or pause delays) or MP4 samples may be cut/assembled incorrectly.
  Preserve original for an exact-frame comparison and rebuild only
  after determining the offset.
- **Original and reel both contain image data, player looks black**:
  focus on Android VideoView preparation, surface creation and playback
  rather than recording or recomputing timestamps.
- **Few frames decode, unavailable media metadata**: cannot determine
  whether content is black; don't claim capture failure.

Android's MediaProjection documentation says capture can stop and
existing virtual displays then cease producing content; a recorder can
continue generating black unless it handles onStop correctly:
https://developer.android.com/media/platform/av-capture
The project already registers onStop and uses
MediaProjectionConfig.createConfigForDefaultDisplay() for Android 14+.
No FLAG_SECURE, SurfaceView.setSecure(true), or protected EGL surface
was found in the game source during v54 inspection, but this alone
does not prove OEM screen capture behavior.

## Changes in v54

- Profile → WATCH / SAVE LOCAL VIDEO HIGHLIGHTS now lists
  **HIGHLIGHT REEL** and **ORIGINAL FULL RECORDING** entries independently.
  Previously only the short reels were accessible. Raw original names
  strictly match session-13digit.mp4; reel names match
  reel-32lowerhex.mp4. Files must reside in Halo's private reels folder.
- Each selected video has **CHECK FRAMES**. A background operation
  decodes at most 5 small 64×36 thumbnails, evenly spaced through the
  video, without storing photos or uploading pixels. It displays one
  of **PICTURE DETECTED**, **LIKELY BLACK VIDEO**, or **UNKNOWN**, with
  frame/sample count and clip duration. It does not claim a diagnosis
  from missing frames. This is a sampling heuristic; dark maps could
  be misclassified if all samples are near-black.
- Each video has **SAVE A COPY**, through Android
  ACTION_CREATE_DOCUMENT, streaming the chosen .mp4 in 64-KiB blocks
  to the explicitly selected destination. Full sources are permitted
  up to the preexisting 160 MiB cap. Private originals are preserved.
  No broad storage permission, no public file server, and no
  automatic upload.
- The internal VideoView playback handler is registered **before**
  a file is assigned; it starts playback only after the modal is
  shown and the VideoView's playback surface has initialized.
  This corrects one potential playback race, not a confirmed
  all-black capture cause.
- The MP4 recorder, reel cutter, game networking, maps, ratings,
  career XP and saved matches are unchanged. No background
  screenshots, raw media logging, upload, deletion or recorder
  re-encoding was added.

## Device acceptance (do not claim success before completing)

1. Install signed v54 by **Update** (never uninstall); keep v52/v53
   installer for rollback. Confirm career, maps, LAN and profile.
2. Open Profile → Video Reels. Select the existing 24-second
   HIGHLIGHT REEL, run CHECK FRAMES, record the diagnosis.
3. Select ORIGINAL FULL RECORDING, run CHECK FRAMES and play it.
   If it's non-black, export it via SAVE A COPY, inspect using
   Android's ordinary video player, then compare it to the reel.
4. If both reports show picture data, try exported reel in
   an external player; if that works, focus on the VideoView.
5. If the original is black, stop repeat recording tests,
   preserve the evidence, and investigate Android
   MediaProjection/SDL rendering interaction. A future native
   capture at the GLES final-frame boundary may be required,
   but do not implement invasive graphics hooks blindly.
6. If the original is good but reel black, fix match tick
   alignment/remux based on actual source timeline. The original
   source must be kept until a good reel is validated.
7. Avoid multiple large video re-recordings while resolving this,
   especially on a phone with limited storage.

## Tests and scope

- Full Android SDK35 Java compilation with existing local SDL stubs.
- Test pure-JDK VideoFrameBrightness for real picture, near-black,
  isolated bright icon, invalid thumbnails (7 cases).
- Source contracts check source/reel distinction, explicit private
  SAF export, accurate undecodable/black semantics, video callback
  order, and unchanged game/recorder.
- Existing v53 and previous Halo regression suites.
- One exact-head Android-only signed v54 build on GitHub, using the
  secondary Forge volume, with signer matching v53 and byte-exact
  font/icon/native guest image checks.
- **This release is a diagnostic and a possible playback fix, not
  a claim that v51 black recording is resolved.**
