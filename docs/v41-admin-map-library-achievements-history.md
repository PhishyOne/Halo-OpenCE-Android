# Halo v41 — Admin map library and detailed Spartan service record

Source branch `halo/admin-career-v41` from signed v40. No upstream
submission or main-branch merge. Android-only release/versionCode 41.

## The requested UI changes

- Removed **View All Ranks** in both locations, and retired its unused
  gallery implementation. A rank and insignia appear as a player earns
  that actual UNSC Service Rank. The same twelve thresholds remain intact.
- **TOP ACHIEVEMENTS** now has three horizontal, compact cards on the
  launcher Profile tab. Each card uses an original scalable medal matching
  its Common / Uncommon / Rare / Legendary tier. Only earned achievements
  appear, ordered rarest first. Empty slots are labeled LOCKED / NOT EARNED.
- Renaming from **Full Service Record → Rename** persists to the same
  `career_profile_v1` local preferences and refreshes the still-visible
  Profile tab via an explicit change callback. The full record reopens
  with the new name. No forced tab switch and no network upload.

## Detailed match history

The original HC1 18-field, append-only, app-private
`career-matches-v1.log` stays unchanged on disk. New matches are
written as **HC2** (25 fields), extending HC1 with seven per-mode counters:

1. CTF flag grabs
2. Oddball time held (native 30-Hz game ticks)
3. Oddball carrier kills
4. Oddball kills while carrying
5. Race laps
6. Race best lap time (ticks)
7. Race last lap time (ticks)

Earlier HC1 records remain readable, retain identical XP totals and
historical kills/deaths/wins, and show the extra mode-specific fields as
**not recorded**, not fabricated zeros. The reader validates all fields,
rejects malformed lines and partial appends, and deduplicates match IDs.

Full Service Record now displays lifetime CTF, Oddball, King and Race
totals and a **View Complete Match History** list, newest first. Each
match can be inspected for map, mode, date, result, kills, human/bot split
(or Unknown), assists, K/D, streak, duration, suicides, team kills, and
recorded per-mode performance.

### Accuracy and headshots

The decompiled `struct game_statistics` defines `shots_fired` and
`shots_hit`, but neither is actually updated anywhere in the present
source. The engine's `game_statistics_record_damage` is also currently
a no-op. **Never display an accuracy percentage derived from these
unmaintained counters.** The profile explicitly says Not tracked until
firing, hit attribution, pellet/projectile and damage events are
instrumented and tested. Likewise, weapon-specific kills, vehicle kills,
damage dealt/taken and headshots require further instrumentation.

### Bot classification and fair ratings

The Android match recorder calls `bots_machine_is_bot` on the credited
victim's actual machine slot and maintains separate human and bot kill
counts. If a LAN client's credit events do not reconcile with the
engine's total, the match stores both split fields as -1 (Unclassified)
rather than inventing a human/bot breakdown.

**Recommended rating phase 1:** human-only rated sessions with persistent
player identity, host/peer confirmed match ID, game mode, placement/wins
and immutable receipts. Bots are allowed in casual play and count
toward service XP/medals. Mixed bot/human sessions should not change
competitive rating until an explicit policy and verified opponent
exclusion model handle bots' impact on the winner. The shared career
leaderboard is a feasible next project, but no unauthenticated
Internet/Discord/public leaderboard is activated here.

## Admin campaign repair library

In Tools → Map Repair, **Export Admin Repair Library** lets the user
pick a local Android destination using the Storage Access Framework
`ACTION_CREATE_DOCUMENT`. The app streams from its private `maps`
folder into a single ZIP without staging another 1–2GB inside the
runtime directory or using a cloud backend. It checks each of the ten
stock Xbox campaign map caches with `SidewinderRepair.validate` first,
including full decompression.

The ZIP contains only validated `maps/a10.map` through `maps/d40.map`
assets and `CAMPAIGN-MAP-INVENTORY.txt` listing PASS/MISSING/INVALID,
per-file bytes and SHA-256, plus a 10-map summary. Incorrect header,
scenario, decompression or missing maps are **not** copied as good.
Streaming output requires up to 1–2GB in the selected destination;
the UI warns first and publishes progress from a worker thread.
Stop Halo before exporting. If the export fails, discard any partial ZIP.

The previous screenshot showed 8/10 valid stock Xbox campaign caches,
with `b40.map` and `c10.map` invalid. That is **not** a complete
repair library. To obtain 10/10, import verified original Xbox copies
from the user's own legitimately obtained game data; the missing map
bytes cannot be reconstructed from arbitrary internet Custom Edition
maps. Once exported, the admin can extract a verified individual
`*.map`, privately share it with a friend who owns the game, and
have them use Map Repair → select mission → Import Map. The archive
isn't directly importable through the single-map picker and contains
no save games or credentials.

## Verification

- Native fake-world recorder compiled and executed: 4 HC2 match modes,
  one postgame write per match, no quit write.
- Pure-JVM mixed HC1/HC2 reader and rarest-three medal selection tests.
- Pure-JVM admin ZIP fixture: all 10 validated, then 8 validated with one
  missing and one invalid, fixed allowlist, exact bytes, manifest.
- Android API 35 Java source compilation, source-contract tests and the
  existing bot/LAN/game-mode/map-transfer regressions.
- Exact-head hosted Android-only signed release (versionCode 41), signature
  matching v40, verified package name, checksum and original icon/fonts.
- Transfer to `Download/Halo/Halo-v41.apk`. Keep only v40 and v41
  installers after verification; do not uninstall the app to update.

### Physical acceptance

Install via **Update**. Open Profile, view top-three earned medals and
locked slots. Open Full Service Record, rename, return: displayed name
updates immediately without switching tabs. No View All Ranks buttons.
Complete new Slayer, CTF, Oddball and Race sessions and inspect full
history, per-mode details and human/bot split. Attempt Admin Repair
Library export to a local folder with enough free space; read its
manifest and verify that invalid b40/c10 are listed but never archived.
Do not claim any live or cross-phone statistical result without a
physical multiplayer test.
