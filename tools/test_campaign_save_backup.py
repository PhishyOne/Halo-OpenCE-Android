"""Regressions for Halo's portable campaign-save backup controls.

Only portable files in save/u + save/z are included. A same-package Android
upgrade preserves app storage; uninstall or Clear Storage does not. These
source checks don't replace the runnable JDK ZIP/restore test or phone QA.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"
LAUNCHER = JAVA / "LauncherActivity.java"
ARCHIVE = JAVA / "CampaignSaveArchive.java"
MANIFEST = ROOT / "port/android/app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "port/android/app/build.gradle"
UPDATER = JAVA / "UpdateReceiver.java"

class CampaignSaveBackupTest(unittest.TestCase):
    def test_launcher_uses_android_file_picker_and_confirmation(self):
        s = LAUNCHER.read_text()
        for item in (
            'savesToggle.setText("CAMPAIGN SAVES")',
            'campaignBackupButton.setText("BACK UP CAMPAIGN PROGRESS")',
            'campaignRestoreButton.setText("RESTORE CAMPAIGN BACKUP")',
            'Intent.ACTION_CREATE_DOCUMENT',
            'Intent.ACTION_OPEN_DOCUMENT',
            'PICK_CAMPAIGN_BACKUP = 8',
            'PICK_CAMPAIGN_RESTORE = 9',
            'application/zip',
            'Halo-Campaign-Backup-',
            'getContentResolver().openOutputStream(target, "w")',
            'getContentResolver().openInputStream(source)',
            'CampaignSaveArchive.exportTo(dataRoot, output)',
            'CampaignSaveArchive.restoreFrom(dataRoot, input)',
            '.setTitle("Restore campaign progress?")',
            'save directory will be preserved separately on',
        ):
            self.assertIn(item, s, item)

    def test_game_must_be_stopped_and_data_will_not_be_deleted(self):
        s = LAUNCHER.read_text()
        start = s.index('private void runCampaignSaveOperation(')
        end = s.index('private interface RepairOperation {', start)
        backup = s[start:end]
        for item in (
            'if (repairBusy)',
            'if (gameProcessStatus() != 0)',
            'repairBusy = true',
            'playButton.setEnabled(false)',
            'campaignBackupButton.setEnabled(false)',
            'campaignRestoreButton.setEnabled(false)',
            'CampaignSaveArchive.exportTo',
            'CampaignSaveArchive.restoreFrom',
            'repairBusy = false',
            'playButton.setEnabled(true)',
        ):
            self.assertIn(item, backup, item)
        self.assertIn('if (repairBusy) {', s)
        self.assertNotIn('getExternalStoragePublicDirectory', backup)
        self.assertNotIn('deleteRecursively', backup)

    def test_save_archive_only_covers_profiles_and_checkpoints(self):
        s = ARCHIVE.read_text()
        for name in (
            'SAVE_ROOTS = { "u", "z" }',
            'name.startsWith("save/u/")',
            'name.startsWith("save/z/")',
            'MAX_FILES = 4096',
            'MAX_FILE_BYTES = 128L * 1024 * 1024',
            'MAX_TOTAL_BYTES = 512L * 1024 * 1024',
            'Files.isSymbolicLink',
            'ZipInputStream',
            'ZipOutputStream',
            'ZipEntry',
            'getNextEntry()',
            'zip.closeEntry()',
            'StandardCopyOption.ATOMIC_MOVE',
            'Files.createTempDirectory(base, ".halo-save-stage-")',
            'UUID.randomUUID()',
            'move(save, previous)',
            'move(replacement, save)',
            'move(previous, save)',
        ):
            self.assertIn(name, s, name)
        self.assertNotIn('resolve("config.toml")', s)
        self.assertNotIn('"maps"', s)
        self.assertNotIn('rm -rf', s)

    def test_same_app_identity_and_update_receiver_do_not_erase_saves(self):
        g = GRADLE.read_text()
        r = UPDATER.read_text()
        self.assertIn('"com.halo.decomp.experiment"', g)
        self.assertIn('versionCode androidVersionCode', g)
        self.assertIn('signingConfig signingKey ? signingConfigs.halo', g)
        self.assertIn('UpdateProvider.APK).delete()', r)
        self.assertNotIn('getExternalFilesDir', r)
        self.assertNotIn('"save"', r)
        self.assertIn('android:allowBackup="false"', MANIFEST.read_text())
        self.assertIn('Never uninstall Halo', LAUNCHER.read_text())

if __name__ == "__main__":
    unittest.main()
