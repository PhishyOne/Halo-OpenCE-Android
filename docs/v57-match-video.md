# Halo v57 — Match-scoped multiplayer video

Consent remains explicit when launching with recording enabled. A single
consented MediaProjection owns a virtual display initially without a surface.
The native multiplayer engine publishes a small consent-scoped local match
state with the same ID as HC3/HLM1, and sampled monotonic game ticks.
Campaign, co-op and lobbies do not create recording files. The game Activity
must be visible and focused, the marker current and the launch token match.
At a match boundary, finalize the current MP4 and detach the encoder surface.
A subsequent match uses a new encoder on the same virtual display; revoked
projection requires a new user consent. No network protocol changes.

The default is highlights only after verified success. Optional KEEP FULL
MATCH RECORDINGS retains new originals. New sidecars bind the raw snapshot
to an exact match ID and clock anchors; do not use approximate legacy wall
time for these sources. Require complete non-DNF match result, actual
selected windows, strict mux coverage, readable nonblack samples and synced
reel before removing only its own unchanged source. Existing recordings,
failed or uncertain sources, and old reels are not automatically trimmed.
No-highlight matches retain originals because absent journal entries can
also mean missing data. Manual deletion remains available in v56 manager.

Conservative initial scope: up to 20 minutes / 160 MiB per match, 512 MiB
raw budget, 500 MiB free-space reservation, stop on low free space.
Backgrounding, losing focus, an expired heartbeat, cap or interrupted
capture preserves the source and waits for the next match rather than
trying to stitch multiple segments. No long-match segmentation yet.
No mic, no uploads, no cloud processing. A system recording indicator can
remain visible while armed. On-device acceptance is still required.

Acceptance: approve consent once, spend time in campaign/co-op, enter two
successive competitive matches without closing the native game, check
reels and raw retention, test LAN host and guest, offline bots, keep-full
setting, stop consent/background, low-space and failed mux behavior.
Existing footage is not used as a deletion fixture.
