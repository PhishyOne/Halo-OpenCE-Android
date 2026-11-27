"""Exercise production direct-match state machine with a fake native lobby."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from tools.harness import function

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class DirectMatchTest(unittest.TestCase):
    def test_native_state_machine(self):
        source = (ROOT/"source/main/main.c").read_text()
        with tempfile.TemporaryDirectory(prefix="halo-launcher-match-") as temp:
            temp = Path(temp)
            maps = (ROOT/"port/linux/game/launcher_lan_match.h").read_text()
            (temp/"launcher_match_function.inc").write_text(
                function(maps, "launcher_map_path") + "\n" +
                function(maps, "launcher_map_playable") + "\n" +
                function(source, "launcher_match_update"))
            binary=temp/"launcher_match"
            subprocess.run([os.environ.get("CC","cc"),"-std=gnu99","-Wall","-Wextra","-Werror",
                "-Wno-sign-compare","-I"+str(temp),str(ROOT/"tools/lan/launcher_match_test.c"),
                "-o",str(binary)],check=True)
            for case in ("classic","badmap","badmode","missing","serverfail","clientfail","valid","gun","changed","timeout",
                         "millworks","badcustom","emptybuild"):
                with self.subTest(case=case):
                    subprocess.run([str(binary),case],check=True,timeout=10)

    def test_launch_arguments_and_scope(self):
        launcher=(J/"LauncherActivity.java").read_text()
        match=(J/"LauncherMatch.java").read_text()
        host=(ROOT/"port/android/host/host_main.c").read_text()
        main=(ROOT/"source/main/main.c").read_text()
        self.assertIn("LauncherMatch.installedMaps(dataRoot)",launcher)
        self.assertIn('directMap.onSelectionChanged',launcher)
        self.assertIn('game.putExtra(HaloActivity.OFFLINE_BOT_ARGUMENTS, match.arguments())',launcher)
        self.assertIn('new String[] {"--launcher-match", map, mode,',match)
        self.assertIn('new String[] {"--offline-bots", Integer.toString(botCount)',match)
        self.assertIn('argc == 8 && !strcmp(argv[1], "--launcher-match")',host)
        self.assertIn('offline_bots = argv + 4;',host)
        self.assertIn('environment_set(&environment, "HALO_NETWORK_TEST", "");',host)
        self.assertIn('launcher_match_update(main_globals.main_menu_scenario_loaded);',main)
        body=function(main,"launcher_match_update")
        self.assertNotIn("network_test_update(",body)
        self.assertIn("network_game_accept_remote_connections(FALSE)",body)
        self.assertIn("network_game_server_port_ok_to_countdown(server)",body)
        self.assertIn("game->player_count < 1 + bots_wanted_count()",body)
        self.assertIn("network_game_server_change_game_variant(server, &variant)",body)
        self.assertIn("player_ui_set_game_variant_options(&options)",body)
        self.assertIn('player_ui_set_active_player_profile(0, profile_index, &profile)',body)
        self.assertIn("network_game_server_pause_countdown(server, FALSE)",body)

if __name__=="__main__":
    unittest.main()
