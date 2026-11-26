# Halo v71 — Native title framebuffer capture

v70 phone screenshot: "Title failed: No visible original title frame
received. (frames 0/0, changes 0, copy errors 0, audio 0ms)".
PixelCopy reported success but its sampled image was never visible.
This establishes failure before cache publication, not a covered launcher.
Audio 0ms was previously unmeasured when visual capture aborted.

The renderer's own screenshot implementation explicitly forces alpha
opaque: Halo's destination alpha is scratch, not transparency. Android
PixelCopy/compositor premultiplication is a plausible explanation, not a
confirmed root cause. Switch to demand-driven native GL_RGBA readback from
the completed default framebuffer immediately before SDL_GL_SwapWindow.
The renderer already flips and presents its resolved scene there. Preserve
GL read framebuffer, pack-buffer/row/skip/alignment and read-buffer state;
CPU convert bottom-up RGBA to top-down opaque ARGB at bounded dimensions.
Java requests and consumes one frame via JNI, GL work only on render
thread. No recording on ordinary gameplay path, no frame queue, no asset
upload or second engine. Original capture cache and automatic retry
unchanged, with genuine nonblack/motion validation.

Separately report blank native frames, waiting requests, GL failures
(and error code category), and actual mixed audio duration on failed
capture. Audio duration by itself does not prove audible music.
No game-data modifications or gameplay protocol change. Real title
rendering and playback remain unverified until v71 phone test.
The menu's ring_loop XML label is not an explicit script-run directive;
do not assume this label alone starts the scene. Current patch does not
change camera scripts, widget initialization or native title music.
