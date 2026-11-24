# Halo v52 — Tappable achievement medals and replay direction

Branch: `halo/achievement-details-v52`, based on the verified signed
v51 branch `halo/mp4-highlights-v51`. This is **Android UI-only**:
no native game changes, no network protocol changes, no file-format
migration, no XP/achievement logic changes, and no cloud/server write.

## Player-facing change

On Profile → Top Achievements, all three medal tiles (not just the tiny
medal icon) are accessible tap targets. Tapping an earned tile opens a
styled Halo achievement detail popup with the existing exact:
- medal icon and achievement name,
- rarity tier and **UNLOCKED** status,
- full unlock requirement, sourced from
  `CareerRecords.Achievement.description`.

Empty slots remain **LOCKED** and tapping one gives a generic
not-yet-earned explanation. It does **not** reveal specific future,
unearned achievements. No progress estimates are invented or saved.
The existing top-three earned achievement ordering remains unchanged.
A native touch ripple and accessibility description mark each card
as an interactive control.

## Recording architecture decision

v51's opt-in MediaProjection H.264 screen recording is the quick
route to real, already-rendered frame pixels. It requires new Android
user consent for each capture, carries storage and encoding overhead,
and records only the player's live camera view. It has **not been
physically verified on the phone yet**.

Longer term, prefer an adapted game-state recorder and replay viewer
over continuous screen recording. The independent
FractumSeraph/OpenCE fork demonstrates a local .hrec recording,
player-follow/free camera reconstruction, and per-game timeline.
`port/linux/game/network_recording.c` is relevant prior art,
**not drop-in compatible** with this customized Halo fork.

Required if developed:
- host-authoritative timeline with exact simulation ticks and stable
  identity, plus a bounded recorded event/snapshot stream,
- camera/view and visible objects' positions, orientations,
  animation, projectiles, damage, weapon/vehicle behavior,
  bot state and important audio/visual effect triggers,
- map/build compatibility and reproducible/renderable scene state,
- bounded file sizes, disk quotas and playback performance at
  8/15/31/64 bots, not assumed smaller than MP4 at high entity counts,
- playback fidelity tests (event/tick ordering, action causality,
  missing data, render interpolation, map unload, resets, reconnects),
- optional offline video export via actual reconstructed frames,
  with native optional sound and original camera/cinematic choices.

A hybrid system is practical: keep v51's optional video until
reconstruction is validated; later store compact replay data by
default (subject to measured costs) and export video on demand.
Do **not** replace v51 based on an untested assumption, or
describe current HLM1 timestamps as full replay data.

## Verification and rollback

- Android API 35 Java-source compilation, plus
  `tools/test_v52_achievement_details.py` source contract for
  earned and locked medallions and clickable cards.
- Retain all existing v51 video and career regression tests.
- One exact-head Android-only hosted release/versionCode 52,
  same signing identity as v51, no desktop/web build jobs.
- On phone, tap each of the three medals, inspect name/rarity/
  requirement, tap a locked placeholder if any, confirm no secrets
  are revealed. Verify career XP, v51 video controls and LAN launch
  remain unchanged. Update in place, not uninstall.
- Existing v51 APK remains rollback installer; preserve all
  app-scoped game data.
