"""Source-level checks for the standalone Android Halo launcher.

The actual Activity is compiled by the Android-only CI job. These checks
catch regression of the single Play footer and LAN/offline separation.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"


class HaloLauncherLayoutTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = LAUNCHER.read_text()

    def test_two_modes_above_one_play(self):
        s = self.source
        self.assertIn('offlineMode.setText("OFFLINE BOTS");', s)
        self.assertIn('lanMode.setText("LAN / SYSTEM LINK");', s)
        self.assertIn('play.setText("PLAY");', s)
        self.assertLess(s.index('footer.addView(choices);'), s.index('footer.addView(play, playParams);'))
        self.assertEqual(s.count('play.setText("PLAY");'), 1)
        self.assertNotIn('Save settings & Open Halo', s)

    def test_modes_do_not_overlap_and_standard_remains_available(self):
        s = self.source
        self.assertIn('if (selected)\n                lanMode.setChecked(false);', s)
        self.assertIn('if (selected)\n                offlineMode.setChecked(false);', s)
        self.assertIn('offlinePanel.setVisibility(offlineMode.isChecked()', s)
        self.assertIn('lanPanel.setVisibility(lanMode.isChecked()', s)
        self.assertIn('launcherPreferences.edit().putString("play_mode", "standard")', s)

    def test_offline_uses_original_native_arguments(self):
        s = self.source
        self.assertIn('getSharedPreferences("offline_bots", MODE_PRIVATE)', s)
        self.assertIn('HaloActivity.OFFLINE_BOT_ARGUMENTS', s)
        self.assertIn('"--offline-bots", Integer.toString(count)', s)
        self.assertIn('Boolean.toString(crews)', s)
        self.assertNotIn('showOfflineBotOptions(', s)
        self.assertNotIn('.setTitle("Offline Bot Match")', s)

    def test_lan_uses_same_validation(self):
        s = self.source
        self.assertIn('LanConfig.peerForRole(hostRole.isChecked()', s)
        self.assertIn('LanConfig.save(new File(dataRoot, "config.toml")', s)
        self.assertIn('count, skill, true, true);', s)
        self.assertNotIn('objectiveBotsLan', s)
        self.assertNotIn('vehicleSeatsLan', s)
        self.assertIn('if (!canStartGame(feedback, appSettings))', s)
        self.assertIn('if (invitePending)', s)

    def test_no_test_build_copy(self):
        s = self.source
        for phrase in ('4 bots have been tested', 'stress test', 'Experimental: bots', 'Play Together'):
            with self.subTest(phrase=phrase):
                self.assertNotIn(phrase, s)
        self.assertIn('hudSurface(', s)
        self.assertIn('hudCheck(', s)

    def test_sidewinder_repair_is_isolated(self):
        s = self.source
        self.assertIn('mapsToggle.setText("MAP REPAIR");', s)
        self.assertIn('repairChooseButton.setText("SELECT SIDEWINDER.MAP");', s)
        self.assertIn('startActivityForResult(picker, PICK_SIDEWINDER);', s)
        self.assertIn('SidewinderRepair.install(input, dataRoot)', s)
        self.assertIn('SidewinderRepair.restorePrevious(dataRoot)', s)
        self.assertIn('if (gameProcessStatus() != 0)', s)
        self.assertIn('playButton.setEnabled(false)', s)
        self.assertIn('if (repairBusy)', s)

    def test_silent_cartographer_repair_is_isolated(self):
        s = self.source
        self.assertIn('repairPanel.addView(hudSection("THE SILENT CARTOGRAPHER"));', s)
        self.assertIn('repairCartographerChooseButton.setText("SELECT B30.MAP");', s)
        self.assertIn('startActivityForResult(picker, PICK_CARTOGRAPHER);', s)
        self.assertIn('importCartographer(data.getData());', s)
        self.assertIn('repairCartographerRestoreButton.setText("RESTORE PREVIOUS B30.MAP");', s)
        self.assertIn('SidewinderRepair.install(\n                    input, dataRoot, SidewinderRepair.CARTOGRAPHER_MAP)', s)
        self.assertIn('SidewinderRepair.restorePrevious(dataRoot, SidewinderRepair.CARTOGRAPHER_MAP)', s)
        self.assertIn('repairCartographerChooseButton.setEnabled(false)', s)
        self.assertIn('repairCartographerRestoreButton.setEnabled(false)', s)
        self.assertIn('repairCartographerRestoreButton.setVisibility(', s)
        self.assertIn('PICK_SIDEWINDER = 2;', s)
        self.assertIn('PICK_CARTOGRAPHER = 3;', s)

    def test_custom_edition_import_via_document_picker(self):
        s = self.source
        self.assertIn('customMapsToggle.setText("CUSTOM MAPS");', s)
        self.assertIn('customMapImportButton.setText("IMPORT CUSTOM MAP FILES");', s)
        self.assertIn('picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);', s)
        self.assertIn('startActivityForResult(picker, PICK_CUSTOM_MAPS);', s)
        self.assertIn('if (requestCode == PICK_CUSTOM_MAPS)', s)
        self.assertIn('importCustomMaps(data);', s)
        self.assertIn('CustomMapImporter.install(input, dataRoot, name)', s)
        self.assertIn('customMapDisplayName(document)', s)
        self.assertIn('getContentResolver().query(document,', s)
        self.assertIn('OpenableColumns.DISPLAY_NAME', s)
        self.assertIn('customMapImportButton.setEnabled(false);', s)
        self.assertIn('customMapImportButton.setEnabled(true);', s)
        self.assertIn('runMapRepair("Importing "', s)

    def test_custom_map_folder_and_zip_fallback(self):
        s = self.source
        self.assertIn('customMapFolderButton.setText("IMPORT A MAP FOLDER");', s)
        self.assertIn('new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)', s)
        self.assertIn('PICK_CUSTOM_FOLDER', s)
        self.assertIn('DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)', s)
        self.assertIn('DocumentsContract.buildDocumentUriUsingTree(tree, documentId)', s)
        self.assertIn('input, dataRoot, document.filename)', s)
        self.assertIn('customMapZipButton.setText("IMPORT A MAP PACK ZIP");', s)
        self.assertIn('CustomMapPack.install(', s)
        self.assertIn('PICK_CUSTOM_ZIP', s)
        self.assertIn('if (gameProcessStatus() != 0)', s)
        self.assertIn('customMapFolderButton.setEnabled(false)', s)
        self.assertIn('customMapZipButton.setEnabled(false)', s)
        self.assertIn('customMapFolderButton.setEnabled(true)', s)
        self.assertIn('customMapZipButton.setEnabled(true)', s)

    def test_offline_ctf_king_uses_native_configuration(self):
        s = self.source
        self.assertIn('boolean crews = true;', s)
        self.assertIn('boolean objectives = true;', s)
        self.assertNotIn('objectiveBotsOffline', s)
        self.assertNotIn('vehicleSeatsOffline', s)
        self.assertIn('OfflineBotConfig.enable(new File(dataRoot, "config.toml"), count,', s)
        self.assertIn('team == 0 ? "even" : "against", crews, objectives)', s)
        self.assertIn('.putBoolean("objective_modes", objectives).apply()', s)
        self.assertIn('OfflineBotConfig.disable(new File(dataRoot, "config.toml"))', s)
        self.assertIn('LanConfig.save(new File(dataRoot, "config.toml")', s)
        self.assertIn('startGame(game);', s)

    def test_diagnostic_report_is_user_initiated_and_local(self):
        s = self.source
        self.assertIn('diagnostics.setText("SAVE DIAGNOSTIC REPORT");', s)
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT', s)
        self.assertIn('picker.putExtra(Intent.EXTRA_TITLE, "Halo-diagnostics.txt");', s)
        self.assertIn('startActivityForResult(picker, SAVE_DIAGNOSTICS);', s)
        self.assertIn('if (requestCode == SAVE_DIAGNOSTICS)', s)
        self.assertIn('HaloDiagnostics.write(dataRoot, output);', s)
        self.assertIn('Review it before sharing.', s)

    def test_two_independent_mode_switches(self):
        s = self.source
        self.assertIn('commanderToggle.setText("2142 COMMANDER: "', s)
        self.assertIn('killstreakToggle.setText("KILLSTREAK REWARDS: "', s)
        self.assertIn('CheckBox[] commanderChecks = new CheckBox[CommanderConfig.KEYS.length];', s)
        self.assertIn('CheckBox[] killstreakChecks = new CheckBox[KillstreakConfig.KEYS.length];', s)
        self.assertIn('commanderChecks[0].isChecked()', s)
        self.assertIn('killstreakChecks[0].isChecked()', s)
        self.assertIn('saveCommanderChoices(new File(dataRoot, "config.toml"),', s)
        self.assertIn('saveKillstreakChoices(new File(dataRoot, "config.toml"),', s)
        self.assertIn('CommanderConfig.save(file, selected);', s)
        self.assertIn('KillstreakConfig.save(file, selected);', s)
        self.assertIn('CommanderConfig.disable(file);', s)
        self.assertIn('KillstreakConfig.disable(file);', s)
        self.assertIn('hostRole.isChecked()', s)
        self.assertIn('!invitePending', s)
        self.assertIn('Switches only for now.', s)

    def test_disc_import_is_preserved(self):
        s = self.source
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', s)
        self.assertIn('XisoExtractor.extractMaps(channel, dataRoot', s)
        self.assertIn('pick.setText("IMPORT DISC DATA");', s)


if __name__ == "__main__":
    unittest.main()
