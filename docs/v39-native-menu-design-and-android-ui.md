# Halo v39 — Native OpenCE visual language and launcher consolidation

**Branch:** `halo/launcher-rank-insignia-v39`, following signed v38.
No upstream OpenCE submission or change to the fork's default branch.

## Immediate changes

1. **Twelve scalable rank insignias** are painted directly by Android
   `RankInsignia`, one distinct vector arrangement per career rank.
   Rank 1 through Rank 12 retain the v38 XP thresholds and original
   append-only history. Artwork is original and generated at any screen
   density; no borrowed commercial military insignia assets.
2. Removed duplicate profile button from Tools. The full profile remains
   accessible from the dedicated first tab. Removed Host Tips and its
   lengthy paragraphs. Preserved genuine warnings for destructive
   restore/import actions.
3. Replaced six full-width `Spinner` dropdowns with compact, right-aligned
   174dp controls and left-aligned labels. A tap opens one Android
   single-choice dialog; swiping the main page does not activate a full
   embedded dropdown. The selected indexes and all configuration/host
   join values are unchanged.
4. Join actions (Find Hosts / Check Update) share one horizontal row.
   Verified Xbox map import/export uses two equal-width buttons. Manual
   host IP and automatic discovery remain available.
5. Matched **the actual native OpenCE menu UI**: the native XML uses
   `#FF2896FF` highlight and `ui\\large_ui` / `ui\\small_ui`
   fonts; `port/assets/fonts/fonts.json` maps those to Overpass-900 and
   Overpass-750. Both OFL-licensed font instances and their license are
   embedded into Android assets. No network font download. The shared
   `HaloTheme` caches the Android Typeface instances and provides fallback
   fonts if assets cannot load. It unifies blue focus, readable light text,
   muted steel-blue outlines and dark navy panels.
6. The same theme applies to the **Android in-game touch HUD and its
   editor**, and to the host-only lobby bot-count +/- overlay. We do NOT
   recolor the original game's bitmap UI or override native renderer
   textures. The original menu already uses these colors and fonts.
7. Lowered the in-game bot adjustment control from 65dp to a **34dp bottom
   baseline**, keeping its existing bottom-center position. On Android
   window-inset changes, `HaloActivity` calculates max(34dp,
   bottomSystemGestureInset+12dp). This keeps it away from an unresponsive
   top edge without placing it inside the gesture-navigation region.
   Touch/gyro/gamepad interpretation and native bot authorization are
   untouched. Physical testing remains necessary on phones with abnormal
   cutouts, task bars, gesture overlays or dead zones.

## Next architecture, not part of v39

**Recommended: integrate game setup gradually into OpenCE's existing native
menus, instead of replacing the lobby.**

The port already builds XML-defined widgets into game tags via
`port/linux/game/menu_tags.c`, with game-owned pregame/lobby routines and
network state synchronization. A second independently implemented lobby
would duplicate complicated map selection, version negotiation, ready
states, team settings, game-type rules, bots, disconnect handling,
late-joins, touch input and player identity.

Keep the external Android **Profile / Find LAN / Updates / File Utilities**
as a lightweight entrypoint. Move only gameplay settings toward native
OpenCE screens in bounded phases: start with a read/write bot-setting
adapter to existing native lobby functions, then game-type and map
settings, then streamlined direct join/hosting. Each moved control must
retain one authoritative value, rollback and cross-device acceptance
tests before removing its external counterpart. Never bypass the
existing host-authoritative netcode or native lobby state.

## Gate

Run source tests, standalone Java tests for career/xbox map transfer,
Android Java source compilation, the v39 visual asset check, and one
Android-only exact-head Actions release build. Verify exact package,
incremented versionCode 39, signing certificate, SHA-256, and included
typefaces before transfer to the user's `Download/Halo` folder.
Install via **Update**; leave v38 APK and all maps/saves intact.
