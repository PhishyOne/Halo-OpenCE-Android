# Halo v68 — Original title/home-base foundation

User-approved launcher migration gate: real original animated Halo ring and
title music, not imitation. First implementation uses the actual installed
ui.map and native renderer/mixer to produce a local title cache, avoiding a
second live engine while browsing the launcher. Nothing proprietary is
added to the APK and no game media is uploaded. User chooses ORIGINAL
TITLE on the masthead once. Dedicated :game title launch skips the intro,
loads real main menu, runs original camera/scripts/title1 sound, hides
native widgets, inhibits menu interaction and disables attract demos.
No networking, match, capture consent, microphone, or normal gameplay
services. Cancel or leaving the title view aborts setup.

PixelCopy copies only the SDL game SurfaceView. Bounded 480px wide JPEG
sequence at 15fps, up to 60 seconds, requires nonblack changing frames.
Host audio tee only in dedicated title mode, actual 48kHz stereo float mixer
converted to PCM16 WAV (first minute, at least 55s and non-silent).
Cache private app files, max 32 MiB frames + ~11 MiB audio. Validation
and atomic directory publication; no partial cache playback or overwriting
a valid previous cache. Invalid cache can be regenerated with only the
two known cache files removed. Normal launcher can always operate with
its original gradient. Playback behind current launcher with translucent
panels, audio focus, suspend/release on pause. Actual native game starts
normally on Play; full direct-launch/single-live-renderer integration is
a later milestone. Repeating a captured segment is not a seamless
reconstruction of the full original music or camera loop.

Acceptance: install v68, ORIGINAL TITLE, Prepare; observe actual ring
and music without original menu controls, cancel test, no blank/stale
cache, return to launcher and check motion/audio on every tab, restart
app and verify automatic startup, Play and background transitions no
overlapping music, standard campaign and LAN still work. Device renderer,
cache generation/audio, smoothness/loop seam unverified until tested.
BR/Flood still on roadmap; no game mode implementation in v68.
