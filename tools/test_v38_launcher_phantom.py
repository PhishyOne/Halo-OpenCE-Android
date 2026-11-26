"""v38 source + offline WAV checks for UI cleanup and public Phantom."""
import struct
import unittest
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "port/android/app/src/main/java/com/halo/decomp"
NATIVE = ROOT / "port/linux/game"


class V38Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.launcher = (APP / "LauncherActivity.java").read_text()
        cls.android = (APP / "HaloActivity.java").read_text()
        cls.career = (APP / "CareerRecords.java").read_text()
        cls.ui = (APP / "CareerProfile.java").read_text()
        cls.reward = (NATIVE / "killstreak_rewards.c").read_text()
        cls.policy = (NATIVE / "commander_features.c").read_text()
        cls.chat = (NATIVE / "network_chat.c").read_text()
        cls.voice_script = (ROOT / "tools/generate_phantom_audio.py").read_text()

    def test_profile_is_primary_screen(self):
        self.assertIn('"Leaderboard", "Offline Match", "Host", "Join", "Tools"',
                      self.launcher)
        self.assertIn("socialUi = new LanBoardUi(this, dataRoot, dashboardPanel, scroll)", self.launcher)
        self.assertIn("final boolean[] homeVisible = { !invitePending };", self.launcher)
        self.assertIn("dashboardPanel.setVisibility(homeVisible[0]", self.launcher)
        self.assertIn("updateMode.run(); // hide PLAY on the default leaderboard screen",
                      self.launcher)

    def test_clean_host_join_and_accurate_map_importers(self):
        self.assertIn('"YOUR IP: " + android.text.TextUtils.join', self.launcher)
        for removed in ("shareActions", "hostManualToggle", "manualHostToggle",
                        "repairCartographerChooseButton", "PICK_SIDEWINDER"):
            self.assertNotIn(removed, self.launcher)
        self.assertIn('setupInput(manualHostPanel, "HOST\'S IP ADDRESS", hostIp)', self.launcher)
        self.assertIn("UnifiedMapImport.install(input, dataRoot, name,", self.launcher)
        self.assertIn("CampaignRepairImport.install(input, dataRoot,", self.launcher)
        self.assertIn("CustomMapImporter.install(", self.launcher)
        self.assertIn("SidewinderRepair.SIDEWINDER_MAP.equals(name)",
                      (APP / "CampaignMapTransfer.java").read_text())
        self.assertNotIn("b40.map and c10.map failed", self.launcher)

    def test_selected_tabs_and_visible_sections(self):
        self.assertIn("modeTabs[i].setSelected(i == chosen);", self.launcher)
        action_style = self.launcher.split("private void hudButton(", 1)[1].split("private void ", 1)[0]
        self.assertIn("HaloTheme.menuButtonColors(button)", action_style)
        self.assertNotIn("setSelected", action_style)
        self.assertNotIn("FOCUS", action_style)
        theme = (APP / "HaloTheme.java").read_text()
        self.assertIn("fill.addState(selected, surface(FOCUS, FOCUS,", theme)
        self.assertIn("fill.addState(StateSet.WILD_CARD, surface(CONTROL_TOP, CONTROL_BOTTOM,", theme)
        self.assertLess(theme.index("fill.addState(disabled"), theme.index("fill.addState(selected"))
        self.assertNotIn("hostHintsToggle", self.launcher)
        self.assertNotIn("toggleSection(", self.launcher)
        self.assertIn('advanced.addView(hudSection("NETWORK OPTIONS"))', self.launcher)
        self.assertIn("killstreakPanel.setVisibility(available ? View.VISIBLE : View.GONE)", self.launcher)
        for panel in ("repairPanel", "campaignSavesPanel"):
            self.assertIn("toolsPage.addView(" + panel + ");", self.launcher)
            self.assertNotIn(panel + ".setVisibility(View.GONE)", self.launcher)

    def test_commander_and_orbital_actually_disabled(self):
        self.assertNotIn("commanderToggle", self.launcher)
        self.assertNotIn("commanderChecks", self.launcher)
        self.assertIn("CommanderConfig.disable(new File(dataRoot,", self.launcher)
        self.assertIn('!key.equals("orbital") && checks[i].isChecked()', self.launcher)
        self.assertIn("(KILLSTREAK_ENABLED | KILLSTREAK_ANNOUNCEMENTS)", self.policy)
        self.assertNotIn("killstreak_orbital_award(", self.reward)
        self.assertNotIn("killstreak_orbital_update(", self.reward)

    def test_phantom_host_only_60_seconds(self):
        self.assertIn("state->immunity_end = now + 1800;", self.reward)
        self.assertIn("_player_powerup_active_camouflage, 1800", self.reward)
        self.assertIn("_object_cannot_take_damage_bit, TRUE", self.reward)
        self.assertIn("_object_cannot_take_damage_bit, FALSE", self.reward)
        self.assertIn("distributed_send_notice(KILLSTREAK_PHANTOM_WARNING)", self.reward)
        self.assertIn("killstreak_feature_allowed(KILLSTREAK_ANNOUNCEMENTS)", self.reward)
        self.assertIn("!csstrcmp(text, KILLSTREAK_PHANTOM_WARNING)", self.chat)
        self.assertIn("game_engine_play_killstreak_sound(local, 14)", self.chat)
        self.assertIn("hud_print_message(local", self.chat)

    def test_alarm_then_british_woman_phrase(self):
        self.assertIn('engine.create("God Mode Enabled."', self.voice_script)
        self.assertIn('voice="bf_emma"', self.voice_script)
        self.assertIn('lang="en-gb"', self.voice_script)
        self.assertIn("R.raw.halo_reward_phantom_warning", self.android)
        self.assertIn("award != 3 && award != 5 && award != 14", self.android)
        audio = ROOT / "port/android/app/src/main/res/raw/halo_reward_phantom_warning.wav"
        with wave.open(str(audio)) as sound:
            self.assertEqual(sound.getnchannels(), 1)
            self.assertEqual(sound.getsampwidth(), 2)
            self.assertEqual(sound.getframerate(), 24000)
            self.assertGreater(sound.getnframes(), 24000 * 4)
            self.assertLess(sound.getnframes(), 24000 * 10)
            # There is audible material after the 2.5-second alarm,
            # not merely a sound-effect file with no speech segment.
            sound.setpos(int(24000 * 3.4))
            late = sound.readframes(24000)
            self.assertTrue(any(v != 0 for v in late))


if __name__ == "__main__":
    unittest.main()
