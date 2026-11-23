# Halo v50 — Match archive and disclosure arrows

Branch: `halo/history-chevron-v50`, based on the latest v49 Android build
source (`f7bd72b932b7143c75698f0784724b7d8fefd66a`).
Scope: Android UI only, not network, cloud backend, gameplay or career writes.

## What changed

- **Complete Match History** opens an original-themed **UNSC / MATCH ARCHIVE**
  panel rather than Android's generic `AlertDialog.setItems` list.
- Each game gets a compact tappable card with status (WIN, NOT WON,
  DNF / LOSS, MISSION COMPLETE or UNFINISHED), map, game mode,
  local time, K/D/A and duration. A down-chevron and subtle border
  indicate that a row is expanded. Details reuse the existing
  `CareerProfile.matchDetails` data, including human/bot kill split,
  objective-specific stats and honest not-yet-tracked accuracy.
- Category filters: ALL, WINS, LOSSES, DNF, CO-OP. DNF includes both
  interrupted multiplayer and unfinished co-op sessions; WINS and LOSSES
  are completed multiplayer results, while the CO-OP filter includes
  completed and unfinished missions. A pure Java model handles ordering
  and filtering from the existing HC1/HC2/HC3 match entries.
- Cards use a recycling `ListView` / `BaseAdapter` rather than a
  huge `ScrollView` of every saved match. Expanded matches are keyed
  by the existing match ID and never written to game data.
- Six actual launcher disclosure buttons gain drawn vector chevrons:
  NETWORK OPTIONS, KILLSTREAK REWARDS, MAP REPAIR,
  SHARE MAPS & BACKUPS, CUSTOM MAPS, CAMPAIGN SAVES.
  Right means collapsed; down means expanded. This reuses the existing
  `setSelected` state so the blue active-button styling remains
  distinctive. **One-shot actions do not get arrows**.
- No old profile, co-op statistics, highlight marker, XP, rank insignia,
  scoreboard or map data was migrated, rewritten or uploaded.
  The match-history screen uses only existing local files.

## Storage and compatibility

- Local worktree and temporary Java compilation are under Forge's
  secondary `/mnt/nomatoka-terrain-scratch` volume only.
- No additions to main disk, production site, cloud database or
  new server endpoint.
- No heavy PNG assets, screenshot recordings or media processing.
  The chevrons render as vectors (a few lines per card), so app-size
  impact should be negligible.
- Build one signed **Android-only** APK with versionCode 50 and verify
  identity / signature against v49. Existing APK remains available for
  rollback and save data remains on the phone when updating in place.

## Verification

- Pure JVM `MatchHistoryModelTest` verifies mixed HC3 data, sorting,
  completed PvP wins/losses, DNF and co-op filters, invalid filter
  rejection and existing career counts.
- `test_v50_match_history_ui.py` verifies the themed dialog,
  ListView recycling, chevron direction/state and that only six
  expandable section controls get disclosure arrows.
- Android API 35 Java compilation includes the latest SDL version
  interface through a test-only stub on the secondary volume.
- Source regression tests must pass in the hosted Android runner;
  locally, an unrelated inherited CE-tag fixture cannot compile with
  Forge's Tiny C Compiler (`cc=tcc`) because of a multi-character
  literal warning. The source code and test fixture were not changed.
- Physical acceptance: scroll history, expand and collapse entries,
  filter all five categories, verify records remain unchanged after
  reopening, tap all six disclosure headers and confirm arrows and
  colors track state. Check landscape height, long map names and the
  actual v49/50 record. No server write should occur.
