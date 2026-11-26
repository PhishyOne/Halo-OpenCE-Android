# Halo v60 — Consistent app panels and video opt-out

LAN career sharing and saved-video sharing remain on by default. The
leaderboard footer and own video library expose a single reversible
video-specific opt-out. Disabling videos does not disable presence or
career sync. The shared atomic LS1 file persists both choices across
launcher/game processes and app restarts; legacy career opt-out stays
off. Disabling career sharing suspends all serving without overwriting
the video choice. The server checks the current video permission before
each catalog/range request; already received memory-buffered footage
cannot be recalled. Raw full recordings remain inaccessible.

Replace all app-owned stock AlertDialog builders with the shared HaloDialog
layout. Halo-themed heading, border, actions, choices, fixed footer, bounded
scrolling and styles for supplied widgets. Preserve cancel/dismiss/show
listeners, explicit confirmation and overridable positive/negative/neutral
buttons. The existing match-history and playback windows keep their own
themed content using HaloWindow's window lifecycle. Replace app-owned
toasts and name validation popup with nonmodal Halo HUD notices, including
the topmost themed dialog. Service-only background warnings use the OS
notification system when the game isn't visible.

Android-owned file picker, installer, keyboard, permissions, recording
consent and system notifications cannot be replaced by custom app UI.
No gameplay, rating or capture algorithm changes. Practice SR remains
honest: fixed-reference opponent rating, incomplete opponent identities
and no proof of bot-free shared rosters. For official group SR, implement
common match receipts with actual roster, stable identities, completion
and bot eligibility, then deterministic peer-to-peer opponent-aware
rating with replay/dedup protection. No retrospective fabricated results.

Device acceptance: check appearance and landscape sizing across all
menus (including scroll and keyboard); choice selection, cancel/back,
nested reset/confirmation, rename validation, map repair, APK update
progress, video deletion and player notices. Test independent video
opt-out and re-enable on two phones while leaderboard sync continues.
No physical-device UI verification yet.
