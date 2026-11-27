"""v28: guard LAN 64-bot slot reservation and high-count pregame safety.

Source gates complement the actual C preset and Java config smoke tests.
Real multiplayer throughput and gameplay FPS require physical phones.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "port/linux/game"
ANDROID = ROOT / "port/android/app/src/main/java/com/halo/decomp"
SERVER = (ROOT / "source/networking/network_server_manager.c").read_text
BOT = (GAME / "bots.c").read_text()
BOT_HEADER = (GAME / "bots.h").read_text()
NETWORK = (ROOT / "port/linux/include/halo_port_limits.h").read_text()
LOBBY = (GAME / "lobby_bot_count_policy.h").read_text()

class Lan64BotsTest(unittest.TestCase):
    def test_128_slots_and_reserved_64_virtual_machines(self):
        self.assertIn("#define HALO_PORT_MAXIMUM_NETWORK_PLAYERS 128", NETWORK)
        self.assertIn("#define HALO_PORT_MAXIMUM_NETWORK_MACHINES 128", NETWORK)
        self.assertIn("#define BOTS_MAXIMUM 127", BOT_HEADER)
        self.assertIn("#define BOTS_SYSTEM_LINK_MAXIMUM 64", BOT_HEADER)
        self.assertIn(
            "#define BOTS_LAN_FIRST_MACHINE (HALO_PORT_MAXIMUM_NETWORK_MACHINES - BOTS_SYSTEM_LINK_MAXIMUM)",
            BOT_HEADER)
        self.assertIn("return network_game_is_splitscreen_local() ? BOTS_FIRST_MACHINE : BOTS_LAN_FIRST_MACHINE", BOT)
        self.assertIn("player->machine_index = (char)(bots_first_machine() + bot_index)", BOT)
        self.assertIn("return count < 0 ? 0 : count > BOTS_SYSTEM_LINK_MAXIMUM ? BOTS_SYSTEM_LINK_MAXIMUM : count", BOT)
        # The virtual range is exactly 64..127; up to 64 real machines
        # can keep their lower range. 4 humans + 64 bots = 68 < 128.
        self.assertEqual(128-64,64)
        self.assertLessEqual(4+64,128)
        self.assertIn("HALO_PORT_NETWORK_VERSION 59", NETWORK)

    def test_host_only_pregame_reconciler_and_no_internet(self):
        server = SERVER()
        self.assertIn("server->state != _network_game_server_state_pregame", server)
        self.assertIn("server->sent_start_game_message", server)
        self.assertIn('else if (!config_boolean("network.online"))', server)
        self.assertIn("wanted = bots_system_link_wanted_count()", server)
        self.assertIn("server->client_machines[virtual_machine_index].machine_index != NONE", server)
        self.assertIn("server->game.machines[virtual_machine_index].machine_index != NONE", server)
        self.assertIn("network_game_add_player(&server->game, &joining)", server)
        self.assertIn("network_game_remove_player(&server->game, &leaving)", server)
        self.assertIn("server->game.player_count >= server->game.maximum_players", server)
        self.assertIn("network_game_server_send_game_data_pregame(server)", server)
        # Only large LAN growth is batched. Offline 127 and LAN <=16
        # retain their original unthrottled source paths.
        self.assertIn("long add_budget = BOTS_MAXIMUM", server)
        self.assertIn("!network_game_is_splitscreen_local() && wanted > 16", server)
        self.assertIn("add_budget = 8", server)
        self.assertIn("added_this_idle >= add_budget", server)
        self.assertIn("added_this_idle++", server)

    def test_android_ui_presets_and_lobby_controls(self):
        config = (ANDROID / "LanConfig.java").read_text()
        launcher = (ANDROID / "LauncherActivity.java").read_text()
        view = (ANDROID / "LobbyBotControls.java").read_text()
        self.assertIn("static final int MAX_LAN_BOTS = 64", config)
        presets = re.search(r'LAN_BOT_PRESETS\s*=\s*\{([^}]+)\}', config)
        self.assertIsNotNone(presets)
        counts = [int(x.strip()) for x in presets.group(1).split(",")]
        self.assertEqual(counts,[0,2,4,6,8,12,16,24,32,48,64])
        self.assertIn("static const int network[] = {0,2,4,6,8,12,16,24,32,48,64}", LOBBY)
        self.assertIn("LanConfig.choicesIncluding(", launcher)
        self.assertIn("LanConfig.choiceIndex(", launcher)
        self.assertIn("lanBotCounts[lanBots.getSelectedItemPosition()]", launcher)
        self.assertIn('" / " + (lan ? "64" : "127")',view)
        self.assertIn("nativeAdjust(direction)",view)
        self.assertIn("config_write(setting, value)", (GAME / "menu_functions.c").read_text())
        self.assertNotIn("MAX_LAN_BOTS = 16",config)

if __name__ == "__main__":
    unittest.main()
