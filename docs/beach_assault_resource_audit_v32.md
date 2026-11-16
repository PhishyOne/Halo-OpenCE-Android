# Halo Android v32: Beach Assault installed-resource audit

## Why this update

A real multiplayer playtest found that `beach_assault.map` produces a
solid blue screen on all connected phones. The user's exact downloaded
map was copied read-only and hash-verified:
`16e30cf229fdabcbcf21b8f0b1e57138e0b0cb95f28735fad8bd9506ff00031e`.
It is a Halo Custom Edition v609 cache, 15,978,580 bytes, with 2,437
tags and one structure BSP.

The game's actual `cache_file_resource_maps_needed` / `resource_tag_load`
logic relies on external resource names and, for bitmaps and locale,
exact resource-list indices. A Python read-only check on the **downloaded**
map files found the following reference matches:

| External resource | Tags referenced | Matched |
|---|---:|---:|
| `bitmaps.map` | 426 | 426 |
| `sounds.map` | 336 | 336 |
| `loc.map` | 104 | 104 |
| **Total** | **866** | **866** |

**This does not establish that the app's installed `custom_maps`
copies match these files, nor that rendering works.** The complete
resource pack in Downloads can differ from app-owned external storage
which Android currently blocks Termux from accessing.

## v32 changes

- Fix v30's **CHECK BEACH ASSAULT RESOURCES** button, which mistakenly
  looked in the stock `maps` directory. The actual imported Halo CE
  files are in `getExternalFilesDir(null)/custom_maps`.
- Replace the file-existence-only check with a **read-only CE tag and
  resource compatibility audit**. The production pure-Java class
  `CeResourceAudit` reads the CE tag table, counts the external
  dependencies, and compares each tag path with the resource map's
  actual entry name. It also compares the exact index for external
  bitmap/font/text tags and checks the declared resource payload
  ranges. Sounds are selected by name, matching the native loader.
- The check runs in an Android worker thread, leaving the launcher UI
  responsive. It never edits maps, configurations or campaign saves.
  It tells the player whether their **installed** resources match
  the map, or reports the missing/incompatible files.
- Add the result to **SAVE DIAGNOSTIC REPORT** so it can be shared
  after reproducing the blue screen. Expand the relevant-log filters
  to recognize CE/map/shader/texture/BSP errors. Existing private-IP,
  email and credential redaction remains in place.
- Add a brief Blood Gulch vehicle-setting hint to the LAN host panel:
  select the native gametype **VEHICLES → PC**, not Default, to allow
  every vehicle actually placed on the map. This is a setting tip,
  not a forced vehicle override. It cannot create absent Banshees,
  and it does not change the experimental ground-driving AI.

## Technical boundaries

Map reads are capped at 256 MiB and tag data at 23 MiB, with at most
65,536 tag instances; a resource index contains at most 100,000 items
and 16 MiB of resource names. Names and offsets are validated before
access. Resource sample payloads and renderer textures are **not**
read or decoded. No raw map data or resource names are exported in
diagnostics. Failed or malformed audits display a clear error rather
than attempting repairs.

**v32 is a targeted diagnostic improvement, not a confirmed Beach
Assault renderer fix.** If the installed 866 resource references
match but the blue screen persists, inspect the saved diagnostic
log for graphics/structure/BSP errors and investigate the native
renderer next. Do not delete a map or blindly replace shaders.

## Validation

```sh
python3 -m unittest -q tools.test_bot_vehicle_driving \
  tools.test_android_launcher_layout \
  tools.test_lan_apk_update
javac -d /tmp/halo-v32-jdk \
  port/android/app/src/main/java/com/halo/decomp/CeResourceAudit.java \
  tools/lan/CeResourceAuditTest.java \
  port/android/app/src/main/java/com/halo/decomp/HaloDiagnostics.java \
  tools/lan/HaloDiagnosticsTest.java
java -cp /tmp/halo-v32-jdk com.halo.decomp.CeResourceAuditTest
java -cp /tmp/halo-v32-jdk com.halo.decomp.HaloDiagnosticsTest
```

The Android-only workflow includes the JDK source/stream tests ahead
of native Release compilation and signing. Tests use realistic CE v609
tag tables and resource index entries. Cases include all-three-match,
bitmap/locale index mismatch, missing sounds, bad map version, bad tag
pointer, out-of-bounds resource payloads and unchanged original files.

### Physical acceptance

1. Upgrade Halo with Android **Update**, preserving campaign data.
2. In the launcher, expand **CUSTOM MAPS**, tap
   **CHECK BEACH ASSAULT RESOURCES**, and record the installed counts.
3. If every installed reference matches, attempt Beach Assault only
   in a private test game (not an evening multiplayer rotation).
4. If it still goes blue, return to the launcher and tap
   **SAVE DIAGNOSTIC REPORT**. Review for sensitive information before
   sharing the text; it should include the compatibility summary and
   relevant recent CE/shader/BSP errors.
5. For Blood Gulch's missing Ghosts/Scorpions/Banshees, use the PC
   gametype vehicle preset and confirm whether the map actually has
   those placements. The host's ground-driving option stays opt-in.
6. The prior v31 LAN update-server work and network protocol 52
   are unchanged; other players need compatible protocol 52 builds.

No source test proves the real map visually renders on an Android GPU.
