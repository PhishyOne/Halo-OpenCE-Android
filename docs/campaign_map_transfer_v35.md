# Halo Android v35 — Stock Campaign Map Audit and Private Transfer

## Problem being addressed

Several original Halo campaign levels show the Xbox **dirty-disc** dialog
while the host is choosing a mission, before the co-op lobby opens. This
is the classic Halo engine's `display_error_damaged_media()`, called
on map-cache lookup/copy/precaching failure. **It is not evidence that
an Android storage device is physically dirty or damaged.**

Read-only review identified distinct failure branches in
`source/cache/cache_files_windows.c`: missing Xbox map, internal
scenario name mismatch, no usable cache-file slot, or copying failure.
`source/cache/cache_files.c` also calls the fatal handler when
asynchronous precaching fails. The original error handler is not
changed in v35; these cases need the exact failing mission and
the source-log reason before a game-engine recovery rewrite.

Historical diagnostics around `a10` and `a30` show that those maps
were loaded in some sessions, and separately show a persistent-storage
checksum warning around profiles and menus. This warning alone is not
proof that a specific campaign map is bad.

The existing Android launcher already imported and backed up
`sidewinder.map` and `b30.map` (The Silent Cartographer).
Additional repaired/validated `b40.map` and `c10.map` candidates
were discussed in prior work, but their installation on the phone
has **not been confirmed**. v35 adds a way to check actual installed
files rather than assuming they were repaired.

## v35 features

### Ten original Xbox campaign maps

The **MAP REPAIR → ORIGINAL CAMPAIGN MAPS** section has a fixed
allowlist in native Halo's canonical campaign order:

| Scenario | Mission |
|---|---|
| `a10.map` | The Pillar of Autumn |
| `a30.map` | Halo |
| `a50.map` | The Truth and Reconciliation |
| `b30.map` | The Silent Cartographer |
| `b40.map` | Assault on the Control Room |
| `c10.map` | 343 Guilty Spark |
| `c20.map` | The Library |
| `c40.map` | Two Betrayals |
| `d20.map` | Keyes |
| `d40.map` | The Maw |

The **CHECK ALL CAMPAIGN MAPS** button runs a read-only audit
on a worker thread. It checks each installed map's v5 header,
internal scenario ID, declared decompressed size and tag-data
range, decompresses its entire zlib stream when compressed,
and requires a valid `sgat` tag-index signature/count. It reports
PASSED, MISSING, or FAILED per map with a final count.

This is a reliable **file integrity preflight**, not a substitute
for the engine's comprehensive tag/BSP and gameplay checks.
It deliberately does not suppress the dirty-disc dialog or
silently mark damaged mission data as playable.

### Export/import a repaired mission for a friend

Select a mission in **ORIGINAL CAMPAIGN MAPS**, then:

1. **EXPORT SELECTED CAMPAIGN MAP**: the same integrity check runs
   before any bytes are written. Android's `ACTION_CREATE_DOCUMENT`
   lets the user save a single `<id>.map` file to a chosen location,
   such as Downloads. SHA-256 and byte count are reported.
2. The user transfers that file privately to an authorized friend
   using a trusted phone-to-phone transfer method. This product
   does not upload proprietary game assets to public cloud storage,
   create public download links or silently send files.
3. The friend uses their signed Halo APK's
   **MAP REPAIR → ORIGINAL CAMPAIGN MAPS**, selects the **same**
   mission, and taps **IMPORT VERIFIED CAMPAIGN MAP**.
   The importer checks the file's real internal name and full
   zlib stream. A valid import backs up the previous map as
   `<id>.map.previous` and installs atomically. An invalid
   import touches neither the map nor the backup.

Every player must have legitimate Halo game data. We cannot
inspect or operate either friend's phone without their own
authorized connection; this release makes the owner-controlled
transfer workflow possible, not automatic remote installation.

The separate existing **Custom Edition map-sharing** mechanism
already downloads missing CE multiplayer and custom campaign maps
over LAN (port 5153 with `network.map_download` and
`network.map_share` on), including required external resources.
That system does **not** automatically send repaired original Xbox
`b30`, `b40` or `c10` campaign caches. Halo's APK updater is
separate and transfers the APK only.

### Log evidence

**SAVE DIAGNOSTIC REPORT** now includes key map loading failure
messages and a pointer to the full mission-map audit. It does not
decompress 10 mission files automatically when generating a report;
that potentially expensive operation is explicitly opt-in.

## Security and durability

- The destination map ID is **never** taken from the Android document
  display name, ZIP path or untrusted input: only ten fixed stock IDs
  are accepted.
- All imports preserve the previous map file as `.previous` unless
  the previous backup already exists, in which case they reject the
  operation rather than overwrite backup evidence.
- Audit and export are read-only for Halo's own app storage. Exports
  go only to a file destination the user explicitly selects.
- Only the selected campaign file moves; **no saved campaign
  progress, profiles or multiplayer settings** are touched.
- App package/signing certificate and network protocol remain
  unchanged from v34 (52). All prior bot and map changes are retained.
- Two or more friends' map inventories may differ; an imported map
  must pass the same validation on every device.

## Verification

Run the pure Java production classes and fixture:

```sh
JDK=/mnt/nomatoka-terrain-scratch/halo-opence-android/toolchains/jdk/bin
"$JDK/javac" -d /tmp/halo-v35-maptests \
 port/android/app/src/main/java/com/halo/decomp/SidewinderRepair.java \
 port/android/app/src/main/java/com/halo/decomp/CampaignMapTransfer.java \
 tools/lan/SidewinderRepairTest.java \
 tools/lan/CampaignMapTransferTest.java
"$JDK/java" -cp /tmp/halo-v35-maptests com.halo.decomp.SidewinderRepairTest
"$JDK/java" -cp /tmp/halo-v35-maptests com.halo.decomp.CampaignMapTransferTest
python3 -m unittest -v tools.test_campaign_map_transfer
```

Regression tests include all ten map IDs, wrong internal name, bad
version, invalid compressed stream, export SHA, byte-identical import,
backup/rollback and save-data preservation. The Android-only workflow
runs these and the existing LAN, vehicle, game modes and other tests
before the final signed Release build.

## Physical acceptance and next phase

1. Install v35 as an Android **Update** only, never Uninstall.
2. In MAP REPAIR, run **CHECK ALL CAMPAIGN MAPS**. Capture which
   IDs fail. Start with `b30`, `b40`, `c10` as previously identified
   damaged/uncertain levels.
3. Repair each failing original mission from verified Halo game
   assets. Check it again before hosting.
4. Privately export the verified files and import the same named
   missions on both friends' phones; have each run the audit.
5. Test a **single mission with one friend** in a LAN co-op lobby.
   Scale to three or four humans only after lobby and loading work.
6. If the dirty-disc dialog persists for a map that passes audit,
   export diagnostics immediately after the failure. Investigate
   tag/BSP/memory/cache-slot failure in the native loader next.

**Covenant and Flood in ordinary multiplayer** are a separate
server-authoritative AI feature: identify campaign actor/biped
dependencies in each multiplayer cache, define host-owned spawn
rules and teams, replicate NPC positions, damage and deaths across
the LAN, then test CPU/network budgets and friendly fire. Do not
globally enable campaign AI in ordinary multiplayer before the
co-op and loading issues are qualified. No enemy NPC spawns are
enabled by this release.
