# Halo Android v27: campaign progress backups

**Status:** Isolated candidate based on signed v26 `7324ffd`.
The Android-only build, signature comparison, and physical document-picker
testing must pass before calling this a released APK.

## What causes campaign progress to disappear

Halo's save-file engine stores Xbox player profiles and campaign checkpoint
data in the native runtime's `HALO_SAVE_ROOT`, mapped by
`port/android/host/host_main.c` to the installed package's
`getExternalFilesDir(null)/save` tree:

- `save/u`: Xbox-style user profiles, including saved campaign progress
- `save/z`: Xbox-style checkpoints and working game-state saves

These folders are **not** in the APK and **are not erased by a normal
Android in-place Update**, as long as the package ID and signing identity
are unchanged and the installer really offers Update. Halo's
`UpdateReceiver` deletes only the obsolete cached installer APK.

In contrast, **Uninstall**, **Clear storage**, switching between
`com.halo.decomp` and `com.halo.decomp.experiment`, or a change in
signing identity can make existing progress unavailable. Uninstall
actually removes the app-owned `Android/data/<package>/files` tree.
Android OS backup is disabled by design for this large game and its
app-private data. If a previous profile was deleted without a backup,
v27 cannot reconstruct the lost save.

## Safe install procedure

1. Close Halo; do not select Uninstall or Clear storage.
2. Open the newer, correctly signed APK and tap **Update**.
3. If Android refuses to update, **Cancel**. Do not uninstall as a
   workaround until the campaign progress is exported and the backup
   ZIP has been verified in a location outside the app's folder.
4. Every v26/v27 experimental APK must use exactly
   `com.halo.decomp.experiment`, its permanent signing certificate,
   and an increasing Android `versionCode`.

## New launcher workflow

The main game launcher has a collapsed **CAMPAIGN SAVES** section, with
**BACK UP CAMPAIGN PROGRESS** and **RESTORE CAMPAIGN BACKUP**.

- Back up only when the `:game` process is closed. Halo uses Android's
  `ACTION_CREATE_DOCUMENT` picker to save
  `Halo-Campaign-Backup-YYYYMMDD-HHmm.zip` to a user-selected directory
  such as **Downloads**. Do not choose anything inside `Android/data`:
  such a backup would also be erased by Uninstall. A separate cloud
  sync is not required. Profiles and checkpoints are copied, not moved.
- The ZIP contains only normal files from `save/u` and `save/z`,
  with a strict version header. It deliberately excludes all game maps,
  mod content, credentials, `config.toml`, logs, temporary installers,
  and other app-private files. Backups may still contain player names,
  so don't share them publicly.
- A restore starts from `ACTION_OPEN_DOCUMENT` and requires explicit
  confirmation. It stages the entire ZIP under the app's save root's
  *parent*, validates every entry and size, then moves the current
  `save` directory aside to a unique hidden rollback location.
  Only a fully validated replacement becomes the active `save` tree.
  Existing maps, mods and configuration remain unchanged.
- Both actions hold the launcher Play control while processing. They
  refuse to operate when Halo's gameplay process is still running.
- The first backup and restore must be tested with actual Xbox
  `SaveMeta.xbx`, `blam.sav`, `blam.lst` and `savegame.bin` files
  on at least one Android phone. Pure-Java simulated files do not
  prove compatibility with every real profile revision.

## Validation and security bounds

The independent `CampaignSaveArchive` class uses no Android APIs or
untrusted cloud services. It can be compiled and tested with the JDK:

```sh
python3 -m unittest -v tools.test_campaign_save_backup
javac -d /tmp/halo-save-test \
  port/android/app/src/main/java/com/halo/decomp/CampaignSaveArchive.java \
  tools/lan/CampaignSaveArchiveTest.java
java -cp /tmp/halo-save-test com.halo.decomp.CampaignSaveArchiveTest
```

Archive entries must be strict `save/u/...` or `save/z/...` relative
paths, with no absolute paths, traversal, unsupported directory names,
duplicate case-folded entries, or links. The importer limits each save
file to 128 MiB, all data to 512 MiB, at most 4,096 files, and a
bounded path depth. Truncated, invalid and highly compressed oversized
archives are refused before changing the existing save tree. Tests
check the full round trip, rollback preservation and immunity of maps
and configuration to a save import.

## Acceptance checks before wider release

Use an existing profile *that has a real checkpoint*; export its backup
and confirm the ZIP is visible in Downloads; run an in-place APK update
and check that Continue still works **without restoring anything**.
Then restore the ZIP on a controlled test device or test profile,
confirm the saved level and difficulty, and confirm failed restores
don't affect existing progress. No other players' saved games should
be inspected or modified without their participation.
