"""Actual VIP game engine and state policy, fake world and integration guards."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest
from tools.harness import function

ROOT=Path(__file__).resolve().parents[1]
GAME=ROOT/"port/linux/game"

class VipTests(unittest.TestCase):
    def test_native_engine_and_wire(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory)
            src=(GAME/"vip.c").read_text()
            src=re.sub(r'^#include .*$', '',src,flags=re.M)
            (work/"vip_impl.inc").write_text(src)
            cmd=[os.environ.get("CC","cc"),"-std=gnu99","-Wall","-Wextra","-Werror",
                 "-Wno-unused-function","-I",str(work),"-I",str(GAME),
                 str(ROOT/"tools/harness/tests/vip.c"),"-o",str(work/"vip-test")]
            built=subprocess.run(cmd,capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            run=subprocess.run([str(work/"vip-test")],capture_output=True,text=True,timeout=10)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            print(run.stdout.strip())

    def test_game_integration(self):
        engine=(ROOT/"source/game/game_engine.c").read_text()
        units=(ROOT/"source/units/units.c").read_text()
        players=(ROOT/"source/game/players.c").read_text()
        damage=(ROOT/"source/objects/damage.c").read_text()
        modes=(GAME/"game_modes.c").read_text()
        bots=(GAME/"bots.c").read_text()
        self.assertIn("return vip_engine_get(engine)",modes)
        self.assertIn("vip_write_network_state",function(engine,"game_engine_write_network_state"))
        self.assertIn("vip_read_network_state",function(engine,"game_engine_read_network_state"))
        self.assertIn("infection_enabled() || vip_enabled()",function(engine,"game_engine_should_end_game"))
        self.assertIn("vip_format_title",function(engine,"game_engine_generate_title_string"))
        self.assertIn("!vip_player_is_vip(player_index)",function(engine,"game_engine_infinite_grenades"))
        self.assertIn("vip_allow_passenger",function(units,"unit_can_enter_seat"))
        self.assertIn("vip_unit_is_vip",function(units,"unit_add_grenade_to_inventory"))
        self.assertIn("vip_unit_is_vip",function(units,"unit_add_equipment_to_inventory"))
        self.assertIn("UNIT_CONTROL_GUNNER_MASK | FLAG(_unit_control_use_equipment_bit)",function(units,"unit_control"))
        self.assertIn("vip_staging() || vip_player_is_vip(player_index)",function(players,"player_examine_nearby_item"))
        self.assertIn("vip_player_is_vip(damage->owner_player_index)",function(damage,"object_cause_damage"))
        self.assertIn("_damage_no_statistics_bit",function(damage,"object_cause_damage"))
        self.assertIn("vip_bot_goal(player_index, &point)",function(bots,"bots_choose_objective_goal"))
        self.assertIn("vip_enabled()",function((GAME/"network_group_rating.c").read_text(),"group_rating_update"))
        self.assertIn("vip_enabled()",function((GAME/"killstreak_rewards.c").read_text(),"killstreak_rewards_record_kill"))
        from tools import network_format
        self.assertEqual(network_format.version(),59)
        self.assertEqual(network_format.check(),[])

if __name__=="__main__":
    unittest.main()
