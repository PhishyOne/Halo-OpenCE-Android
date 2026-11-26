# Halo v70 — Group separators, sharing checkboxes, title retry

User reported v69 ran preparation but no animation behind launcher. Phone
package manager confirmed versionCode 69. Android sandbox denied access
to app data and app-specific logs from Termux, so exact failure is unknown.
Do not claim the real title works until physical verification.

- Thin translucent blue 1dp foreground borders around existing Offline
  mode/bots/teams/rewards, Host connection/bots/network/rewards, Join
  connection/network/recording groups. Foreground decorations do not add
  vertical rows or padding, or alter Play dock policy.
- LAN Video Sharing checkbox above LAN Career Sharing, no bottom button.
  Preserve independent saved video preference and effective career AND
  video permission, label paused when career disabled. Remove redundant
  toggle from own videos page; recording management remains.
- Reduce overlay and panel opacity for visible original backdrop.
- Title capture waits for valid SDL surface instead of failing immediately
  when native audio-ready arrives before surface readiness. Use JPEG 65
  within existing bounded cache, tolerate slower capture with minimum
  150 visible copies and 12 changed signatures, abort if no visible
  frame within ten seconds of sampling. Real original frames still required.
- Use an open private WAV file descriptor for MediaPlayer; report decoder
  and audio failures instead of silently swallowing them. Persist bounded
  status (phase, frame counts, copy errors, audio duration) across game
  process shutdown; show failure as a notice and a conditional Tools
  status line, no extra setup button. Automatic retry once on new APK
  version, no startup loop. No native/gameplay protocol change.
- Title assets remain local, valid prior cache is preserved. Actual capture
  and visibility/audio need phone retest. If still failing, read the
  conditional Tools status and isolate capture vs playback.

All changes preserve existing game, campaign saves, maps, career,
recording preferences and normal Play paths.
