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

    def test_compact_mode_navigation_above_one_play(self):
        s = self.source
        self.assertIn('"Profile", "Offline Match", "Campaign", "Host", "Join", "Tools"', s)
        self.assertIn('CareerProfile.dashboard(this, dataRoot, dashboardPanel)', s)
        self.assertIn('final boolean[] homeVisible = { !invitePending };', s)
        self.assertIn('Button[] modeTabs = new Button[modeNames.length];', s)
        self.assertIn('offlineMode.setText("OFFLINE BOTS");', s)
        self.assertIn('lanMode.setText("LAN / SYSTEM LINK");', s)
        self.assertIn('play.setText("PLAY");', s)
        self.assertLess(s.index('root.addView(modeBar);'), s.index('footer.addView(play, playParams);'))
        self.assertEqual(s.count('play.setText("PLAY");'), 1)
        self.assertNotIn('footer.addView(choices);', s)
        self.assertNotIn('Save settings & Open Halo', s)

    def test_modes_do_not_overlap_and_standard_remains_available(self):
        s = self.source
        self.assertIn('if (selected)\n                lanMode.setChecked(false);', s)
        self.assertIn('if (selected)\n                offlineMode.setChecked(false);', s)
        self.assertIn('offlinePanel.setVisibility(playing && offlineMode.isChecked()', s)
        self.assertIn('lanPanel.setVisibility(playing && lanMode.isChecked()', s)
        self.assertIn('toolsPage.setVisibility(toolsVisible[0] ? View.VISIBLE : View.GONE);', s)
        self.assertIn('dashboardPanel.setVisibility(homeVisible[0] ?', s)
        self.assertIn('launcherPreferences.edit().putString("play_mode", "standard")', s)

    def test_auto_discovery_keeps_manual_fallback(self):
        s = self.source
        self.assertIn('LanHostPicker hostPicker = new LanHostPicker(', s)
        self.assertIn('hostPicker.search(true, () -> play.performClick());', s)
        self.assertIn('hostPicker.search(false, null);', s)
        self.assertNotIn('manualHostToggle', s)
        self.assertIn('manualHostPanel.addView(hostIp);', s)
        self.assertNotIn('shareActions.addView', s)
        self.assertIn('game.putExtra(HaloActivity.LAN_DISCOVERY_HOST, true);', s)
        self.assertIn('toolsPage.addView(mapsToggle);', s)
        self.assertIn('toolsPage.addView(diagnostics);', s)

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
        self.assertIn('count, skill, true, true, hosting && driverBots.isChecked());', s)
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

    def test_xbox_maps_consolidated_without_removing_verification(self):
        s = self.source
        self.assertIn('mapsToggle.setText("MAP REPAIR");', s)
        self.assertNotIn('repairChooseButton.setText(', s)
        self.assertNotIn('repairCartographerChooseButton.setText(', s)
        self.assertIn('mapNames[mapNames.length - 1] = "Sidewinder  (sidewinder.map)";', s)
        self.assertIn('return selected == names.length ?', s)
        self.assertIn('SidewinderRepair.SIDEWINDER_MAP :', s)
        self.assertIn('CampaignRepairImport.install(input, dataRoot,', s)
        self.assertIn('SidewinderRepair.restorePrevious(dataRoot, selected)', s)
        self.assertIn('stockCampaignImportButton.setText("IMPORT MAPS OR REPAIR ZIP")', s)
        self.assertIn('stockCampaignAuditButton.setText("CHECK MY MAPS")', s)
        self.assertIn('if (gameProcessStatus() != 0)', s)
        self.assertIn('playButton.setEnabled(false)', s)
        self.assertIn('if (repairBusy)', s)

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

    def test_commander_retired_and_phantom_settings_available(self):
        s = self.source
        self.assertNotIn('commanderToggle', s)
        self.assertNotIn('commanderChecks', s)
        self.assertNotIn('saveCommanderChoices(', s)
        self.assertIn('killstreakToggle.setText("KILLSTREAK REWARDS: "', s)
        self.assertIn('CheckBox[] killstreakChecks = new CheckBox[KillstreakConfig.KEYS.length];', s)
        self.assertIn('killstreakChecks[0].isChecked()', s)
        self.assertIn('God Mode (60s)', s)
        self.assertIn('selected.set(key, checked);', s)
        self.assertIn('!key.equals("orbital") && checks[i].isChecked()', s)
        self.assertIn('saveKillstreakChoices(new File(dataRoot, "config.toml"),', s)
        self.assertIn('CommanderConfig.disable(new File(dataRoot, "config.toml"));', s)
        self.assertIn('KillstreakConfig.save(file, selected);', s)
        self.assertIn('KillstreakConfig.disable(file);', s)
        self.assertNotIn('targeted orbital strike.', s)
        self.assertIn('toggleSection(killstreakToggle, killstreakPanel)', s)
        self.assertIn('toggleSection(customMapsToggle, customMapsPanel)', s)
        self.assertIn('toggleSection(mapsToggle, repairPanel)', s)

    def test_disc_import_is_preserved(self):
        s = self.source
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', s)
        self.assertIn('XisoExtractor.extractMaps(channel, dataRoot', s)
        self.assertIn('pick.setText("IMPORT DISC DATA");', s)


if __name__ == "__main__":
    unittest.main()
