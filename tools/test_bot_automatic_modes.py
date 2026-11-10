"""Automatic bot options must be isolated to bot AI and objective gametypes."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
CONFIG=ROOT/"port/linux/src/port_config.c"
BOTS=ROOT/"port/linux/game/bots.c"
SERVER=ROOT/"source/networking/network_server_manager.c"
LAUNCHER=ROOT/"port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"
ANDROID=ROOT/"port/android/app/src/main/java/com/halo/decomp/OfflineBotConfig.java"

class BotAutomaticModesTest(unittest.TestCase):
    def test_config_defaults_true(self):
        text=CONFIG.read_text()
        self.assertIn('{ "bots.vehicle_seats", _config_boolean, "true"', text)
        self.assertIn('{ "bots.objective_modes", _config_boolean, "true"', text)

    def test_objectives_gated_by_engine(self):
        bots=BOTS.read_text()
        server=SERVER.read_text()
        self.assertIn('variant->game_engine_index == game_engine_ctf ||', bots)
        self.assertIn('variant->game_engine_index == game_engine_king', bots)
        self.assertIn('config_boolean("bots.objective_modes") && variant',bots)
        self.assertIn('server->game.variant.game_engine_index == game_engine_slayer ||',server)
        self.assertIn('server->game.variant.game_engine_index == game_engine_ctf ||',server)
        self.assertIn('server->game.variant.game_engine_index == game_engine_king',server)

    def test_launcher_both_true_with_no_toggles(self):
        source=LAUNCHER.read_text()
        self.assertIn('boolean crews = true;',source)
        self.assertIn('boolean objectives = true;',source)
        self.assertIn('count, skill, true, true);',source)
        self.assertNotIn('vehicleSeatsOffline =',source)
        self.assertNotIn('vehicleSeatsLan =',source)
        self.assertNotIn('objectiveBotsOffline =',source)
        self.assertNotIn('objectiveBotsLan =',source)
        self.assertIn('OfflineBotConfig.enable(new File(dataRoot, "config.toml"), count,',source)
        self.assertIn('OfflineBotConfig.disable(new File(dataRoot, "config.toml"))',source)

    def test_no_offline_or_lan_flags_leaked(self):
        source=ANDROID.read_text()
        self.assertIn('changed.put("objective_modes", "true")',source)
        self.assertIn('changed.put("vehicle_seats", "true")',source)
        self.assertIn('changed.put("count", "0")',source)
        self.assertIn('changed.put("teams", "\\"even\\"")',source)

if __name__=="__main__":
    unittest.main()
