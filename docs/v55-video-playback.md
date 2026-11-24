# Halo v55 — Local video player

## Device evidence

v54 original recording: 1200 seconds, five of five frames decoded,
zero nearly black. The launcher showed a black playback window.
The player exported the existing 24-second reel and confirmed it
plays outside the launcher. This isolates the reported black reel
to internal playback, not a wholly failed recorder or unplayable
muxed file. It does not prove highlight timing accuracy.

## Scoped fix

- The black background belongs to the viewport behind VideoView.
  Its own background is removed, and its dialog window is opaque.
  This addresses a plausible SurfaceView composition problem.
- Keep the platform VideoView; no additional media dependencies.
  Center and fit the video in a weighted viewport.
- Persistent play/pause/replay, seek bar and position/length.
- Fullscreen expands the same dialog; exit restores the window
  and controls. Does not start a second decoder.
- Distinguish preparation, first-frame signal, completion and
  player error. A missing render callback is not proof of black
  video. Frame inspection and explicit export are preserved.
- Pause on export; keep the last position across surface recreation
  and release the player, surface callback and UI ticker on dismiss.

Recorder, reel muxer, game source, career, maps and networking remain
unchanged. No source media is uploaded, deleted or re-encoded.

## On-device acceptance

Install by Update, never uninstall. Open the existing reel and
confirm the actual picture matches the exported file. Pause, seek,
resume, replay and toggle fullscreen in landscape. Open the existing
full recording and test position, playback and aspect ratio.
Check Frames and Save a Copy must still work. Close and reopen the
dialog, including returning from the Android document picker.
No new recording is required. Confirm career and LAN settings persist.
Do not mark the visual bug fixed until the phone test passes.
