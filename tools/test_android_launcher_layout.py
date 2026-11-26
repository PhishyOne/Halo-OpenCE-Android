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
        self.assertIn('"Leaderboard", "Lobby", "Offline Match", "Tools"', s)
        self.assertIn('socialUi = new LanBoardUi(this, dataRoot, dashboardPanel, scroll)', s)
        self.assertIn('final boolean[] homeVisible = { !invitePending };', s)
        self.assertIn('Button[] modeTabs = new Button[modeNames.length];', s)
        self.assertIn('botMatch.setText("BOT MATCH");', s)
        self.assertIn('campaign.setText("CAMPAIGN");', s)
        self.assertIn('play.setText("PLAY");', s)
        self.assertLess(s.index('root.addView(modeBar);'), s.index('footer.addView(play, playParams);'))
        self.assertEqual(s.count('play.setText("PLAY");'), 1)
        self.assertNotIn('footer.addView(choices);', s)
        self.assertNotIn('Save settings & Open Halo', s)

    def test_modes_do_not_overlap_and_standard_remains_available(self):
        s = self.source
        self.assertIn('if (selected)\n                lanMode.setChecked(false);', s)
        self.assertIn('if (selected)\n                offlineMode.setChecked(false);', s)
        self.assertIn('offlinePanel.setVisibility(playing && !lanMode.isChecked()', s)
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
        self.assertIn('setupInput(manualHostPanel, "HOST\'S IP ADDRESS", hostIp);', s)
        self.assertNotIn('shareActions.addView', s)
        self.assertIn('game.putExtra(HaloActivity.LAN_DISCOVERY_HOST, true);', s)
        self.assertIn('toolsPage.addView(repairPanel);', s)
        self.assertIn('toolsPage.addView(campaignSavesPanel);', s)

    def test_offline_uses_original_native_arguments(self):
        s = self.source
        self.assertIn('getSharedPreferences("offline_bots", MODE_PRIVATE)', s)
        self.assertIn('HaloActivity.OFFLINE_BOT_ARGUMENTS', s)
        match = (ROOT / "port/android/app/src/main/java/com/halo/decomp/LauncherMatch.java").read_text()
        self.assertIn('match.arguments()', s)
        self.assertIn('"--offline-bots", Integer.toString(botCount)', match)
        self.assertIn('Boolean.toString(vehicleCrews)', match)
        self.assertNotIn('showOfflineBotOptions(', s)
        self.assertNotIn('.setTitle("Offline Bot Match")', s)

    def test_lan_uses_same_validation(self):
        s = self.source
        self.assertIn('LanConfig.peerForRole(hostRole.isChecked()', s)
        self.assertIn('LanConfig.save(new File(dataRoot, "config.toml")', s)
        self.assertIn('count, skill, true, true, hosting);', s)
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
        self.assertIn('repairPanel.addView(hudSection("MAP MANAGEMENT"));', s)
        self.assertIn('stockCampaignAuditButton.setText("CHECK MY MAPS")', s)
        self.assertIn('repairHostButton.setText("REPAIR FROM HOST")', s)
        self.assertIn('toolsPair(repairPanel, stockCampaignAuditButton, repairHostButton)', s)
        self.assertIn('CampaignMapTransfer.checks(dataRoot,', s)
        self.assertIn('if (check.passed) continue;', s)
        self.assertIn('if (gameProcessStatus() != 0)', s)
        self.assertIn('setMapToolsBusy(true)', s)
        self.assertIn('if (repairBusy)', s)
        repair = (LAUNCHER.parent / "SidewinderRepair.java").read_text()
        self.assertIn("moveInSameDirectory(target, previous)", repair)
        self.assertIn("restorePrevious(File dataRoot, String targetMap)", repair)

    def test_unified_map_import_via_document_picker(self):
        s = self.source
        self.assertIn('customMapImportButton.setText("IMPORT MAPS");', s)
        self.assertIn('picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);', s)
        self.assertIn('startActivityForResult(picker, PICK_CUSTOM_MAPS);', s)
        self.assertIn('if (requestCode == PICK_CUSTOM_MAPS)', s)
        self.assertIn('importCustomMaps(data);', s)
        self.assertIn('UnifiedMapImport.install(input, dataRoot, name,', s)
        self.assertIn('customMapDisplayName(document)', s)
        self.assertIn('getContentResolver().query(document,', s)
        self.assertIn('OpenableColumns.DISPLAY_NAME', s)
        self.assertIn('customMapImportButton,', s)
        self.assertIn('runMapRepair("Importing "', s)

    def test_folder_and_zip_buttons_removed_but_import_validators_remain(self):
        s = self.source
        self.assertNotIn('customMapFolderButton.setText(', s)
        self.assertNotIn('customMapZipButton.setText(', s)
        self.assertIn('CustomMapPack.install(', s)
        self.assertIn('CustomMapImporter.install(', s)
        self.assertIn('if (gameProcessStatus() != 0)', s)

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

    def test_diagnostic_report_removed_from_user_menu(self):
        s = self.source
        self.assertNotIn('diagnostics.setText("SAVE DIAGNOSTIC REPORT");', s)
        self.assertIn('HaloDiagnostics.write(dataRoot, output);', s)

    def test_commander_retired_and_phantom_settings_available(self):
        s = self.source
        self.assertNotIn('commanderToggle', s)
        self.assertNotIn('commanderChecks', s)
        self.assertNotIn('saveCommanderChoices(', s)
        self.assertIn('killstreakPanel.addView(hudSection("KILLSTREAK REWARDS"));', s)
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
        self.assertIn('killstreakPanel.setVisibility(available ? View.VISIBLE : View.GONE)', s)
        self.assertIn('toolsPair(repairPanel, shareMapsButton, receiveMapsButton);', s)
        self.assertIn('toolsPage.addView(repairPanel);', s)
        self.assertNotIn('toggleSection(', s)

    def test_disc_import_is_preserved(self):
        s = self.source
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', s)
        self.assertIn('XisoExtractor.extractMaps(channel, dataRoot', s)
        self.assertIn('pick.setText("IMPORT DISC DATA");', s)


if __name__ == "__main__":
    unittest.main()
