# v24: Physical Android lobby bot controls and recon voice correction

**Status:** Candidate on `halo/lobby-controls-v24`, rebased on v23
`37cd4aa`. The installed v23 remains untouched until Android build,
signature compatibility and actual physical UI testing.

## Reported v23 failures

- Despite source-level XML tests proving a `BOTS -` / `BOTS +` widget
  existed in the generated multiplayer lobby menu, the real phone
  displayed **no adjustable bot controls**.
- The approved five-kill British woman voice played, but "Recon active"
  sounded like "Recon activer" in gameplay.

These are user-observed issues. The exact cause of the invisible XML buttons
was not reproduced in an emulator; the previous gate was tied to the
*client's* visual pregame state, which may differ from the host's actual
pregame server state. Source tests validated widget definitions rather
than Android touch visibility.

## Bot-control correction

- A new **native Android overlay** `LobbyBotControls` is created after
  the full-screen SDL and `TouchControls` views, so its minus/count/plus
  touch targets are on top of the menu. It sits near the lower center,
  above the regular start buttons. Text reads `LAN BOTS N / 16` or
  `BOTS N / 127`. Both buttons use finger-sized 49dp targets.
- The overlay reads a bounded short-lived status from the game process's
  native lobby and shows only when the authoritative server is in pregame,
  has not sent the start-game message, the local phone is the game server,
  the session is LAN-only or offline, and the selected mode supports bots
  (Slayer, or CTF/King with objectives enabled).
- Taps cross the existing Android guest/host import table through a
  mutex-protected, one-shot, strictly ±1 **preset step** queue. The
  original C `lobby_bot_count_change(0, direction)` rechecks host authority
  and pregame eligibility before changing `bots.system_link_count` or
  `bots.count` in native `config.toml`. The existing authoritative
  server bot reconciler adds/removes players on its next update.
- The visible XML controls remain as secondary controls for desktop or
  where they do work. No network protocol, packet IDs, bot AI, maps or
  game data changed, and guests cannot adjust the host's bots.
- Host publishes a count only when its lobby update runs. The Java strip
  polls at 125ms; after a 900ms lease expires it hides and refuses more
  input. This prevents controls lingering in a match or another menu.

## Voice correction

- Changed only the five-kill line from `Recon active` to
  **`Recon enabled`** (matching HUD: `5 kills: Recon enabled (30s)`).
- Re-synthesized offline using the original Kokoro
  `kokoro-v1.0.int8.onnx`, `bf_emma` en-gb voice,
  speed 0.96, 24 kHz mono PCM16, with 450ms head and 500ms tail padding.
- Exact five-kill WAV SHA256:
  `599153153e501161b9b942923f16c18abf849abf1070e9f767c0c91322ff5b3f`.
  The approved three-kill `Enhancements online` WAV has not changed.
  The two orbital clips and their existing authority rules also remain
  unchanged. Only the five-kill phrase's sound needs player acceptance.

## Verification and remaining acceptance

```sh
CC=cc python3 -m unittest -q \
  tools.test_lobby_bot_count \
  tools.test_killstreak_recon \
  tools.test_killstreak_orbital
```

Also run cross-compilation/smoke tests for the JNI bridge and complete
isolated Android-only build. The source test explicitly checks that the
Android View is layered **after** the fullscreen touch controller and
that its native status/delta functions exist and are exported; prior tests
did not establish that.

**Physical acceptance required:**

1. Host a LAN Slayer pregame lobby on Android with bots set to 4.
   Verify the bottom-center minus/count/plus strip *appears*.
2. Tap plus several times (4→6→8), then minus; watch the roster update
   without anyone reconnecting. Check lobby only: no midmatch spawns.
3. Have a friend join, verify their game has **no** bot controls.
4. Test offline Slayer and opt-in LAN CTF/King. Verify the display
   disappears after game start, on guest, campaign/co-op, and when online.
5. Earn five kills without dying. Confirm "Recon enabled" sounds correct
   on the phone speaker and the private red recon markers remain.
6. Confirm existing LAN update feature, Voice and Orbital sounds still work.

Do not uninstall the existing app to apply the update. Same signed
`com.halo.decomp.experiment` package and increasing versionCode are
required, preserving user game data and imported maps.
