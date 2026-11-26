"""Compile actual native Sticks & Stones preset and pickup rules in a small fake world."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest
from tools.harness import function, enum_with, structure

ROOT=Path(__file__).resolve().parents[1]
GAME=ROOT/"port/linux/game"

class SticksAndStonesTest(unittest.TestCase):
    def test_real_preset_builder_and_matching(self):
        header=(ROOT/"source/game/game_engine.h").read_text()
        modes=(GAME/"game_modes.c").read_text()
        enums="\n".join(enum_with(header,name) for name in (
            "_game_variant_draw_object_in_motion_sensor_bit = 0","game_engine_none = 0",
            "_friendly_fire_on = 0","_radar_players_all","_loadout_category = 0",
            "_loadout_weapon_none = 0","_variant_vehicle_warthog = 0","_game_mode_none = 0",
            "_gun_game_order_strongest_first = 0"))
        declarations="\n".join(structure(header,name) for name in (
            "universal_variant","ctf_variant","slayer_variant","king_variant","oddball_variant","race_variant"))
        union=header[header.index("union game_engine_variant\n{"):]
        declarations+="\n"+union[:union.index("};")+2]
        declarations+="\n"+structure(header,"game_variant")+"\n"+structure(header,"game_variant_options")
        constants=modes[modes.index("enum\n{\n\tGOAL_RADAR_NAV_POINT"):modes.index("/* the kill being recorded")]
        code="\n".join(function(modes,n) for n in (
            "game_modes_option","game_modes_options_default","game_modes_options_sanitize",
            "game_modes_no_motion_sensor","game_modes_sticks_and_stones_rules",
            "game_modes_sticks_and_stones","game_modes_sticks_and_stones_active",
            "game_modes_gametype","game_modes_gametype_count","game_modes_gametype_name",
            "game_modes_gametype_by_name"))
        # Exercise the real weapon permission function; its ordinary callback
        # remains effective outside the new rules.
        code+="\n"+function((ROOT/"source/game/game_engine.c").read_text(),"game_engine_allow_pick_up")
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp)
            (work/"declarations.inc").write_text(enums+"\n"+declarations+"\n"+constants)
            (work/"rules.inc").write_text(code)
            command=[os.environ.get("CC","cc"),"-std=gnu99","-Wall","-Wextra","-Werror",
                "-Wno-unused-function","-I",str(work),str(ROOT/"tools/harness/tests/sticks_and_stones.c"),
                "-o",str(work/"test")]
            build=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(build.returncode,0,build.stderr)
            result=subprocess.run([str(work/"test")],capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            print(result.stdout.strip())
    def test_native_spawn_pickup_and_bot_integration(self):
        engine=(ROOT/"source/game/game_engine.c").read_text()
        units=(ROOT/"source/units/units.c").read_text()
        menu=(GAME/"menu_functions.c").read_text()
        modes=(GAME/"game_modes.c").read_text()
        bots=(GAME/"bots.c").read_text()
        self.assertIn("unit_delete_all_weapons(unit_index);",function(engine,"game_engine_give_loadout"))
        self.assertIn("game_engine_infinite_grenades_internal()",function(engine,"game_engine_postspawn_player_update"))
        self.assertIn("!game_engine_infinite_grenades(unit->unit.player_index)",function(units,"unit_throw_grenade_move_to_hand"))
        self.assertIn("game_modes_sticks_and_stones_active()",function(engine,"game_engine_picking_up"))
        self.assertIn("options->no_map_weapons",modes)
        self.assertIn("game_modes_sticks_and_stones(variant, options)",function(modes,"game_modes_variant_is_opence"))
        self.assertIn("game_modes_sticks_and_stones(&variant, &options)",function(menu,"gametype_rules_text"))
        self.assertIn("game_modes_sticks_and_stones_active() ? 0.6f",function(bots,"bots_bot_fight"))
        self.assertIn("bots_level_grenade(bot, tick, reaction_ticks)",function(bots,"bots_bot_fight"))
        self.assertIn("bots_level_melee(bot, tick",function(bots,"bots_bot_fight"))
        self.assertIn("game_modes_sticks_and_stones_active()",bots)
        from tools import network_format
        self.assertEqual(network_format.check(),[])

if __name__=="__main__":
    unittest.main()
