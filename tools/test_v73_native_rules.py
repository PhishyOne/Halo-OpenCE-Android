"""Compile the real launcher rule adapter against the actual engine declarations."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest
from tools.harness import structure, enum_with

ROOT=Path(__file__).resolve().parents[1]
J=ROOT/"port/android/app/src/main/java/com/halo/decomp"
G=ROOT/"port/linux/game"

class NativeMatchRuleTest(unittest.TestCase):
    def test_native_rule_bridge(self):
        h=(ROOT/"source/game/game_engine.h").read_text()
        enums=["_game_variant_draw_object_in_motion_sensor_bit","game_engine_none","_friendly_fire_on",
            "_radar_players_all","_loadout_category","_loadout_weapon_none","_variant_vehicle_warthog","_game_mode_none","_gun_game_order_strongest_first"]
        structs=["universal_variant","ctf_variant","slayer_variant","king_variant","oddball_variant","race_variant"]
        types="typedef unsigned char byte; typedef unsigned short word; typedef unsigned char boolean; typedef float real;\n#define TRUE 1\n#define FALSE 0\n#define TICKS_PER_SECOND 30\n"
        types+="\n".join(enum_with(h,k) for k in enums)+"\n"
        types+="\n".join(structure(h,k) for k in structs)+"\n"
        types+=re.search(r"union game_engine_variant\s*\{.*?\n\};",h,re.S).group()+"\n"
        types+=structure(h,"game_variant")+"\n"+structure(h,"game_variant_options")+"\n"
        with tempfile.TemporaryDirectory(prefix="halo-native-rules-") as tmp:
            p=Path(tmp);(p/"launcher_rule_structs.inc").write_text(types)
            out=p/"rules"
            subprocess.run([os.environ.get("CC","cc"),"-std=gnu99","-Wall","-Wextra","-Werror",
                "-I"+str(p),"-I"+str(G),str(ROOT/"tools/lan/launcher_rules_test.c"),"-o",str(out)],check=True)
            subprocess.run([str(out)],check=True)
    def test_all_stock_builders_declared_and_rule_handoff(self):
        s=(G/"launcher_match_presets.h").read_text()
        h=(ROOT/"source/game/game_engine_playlist.h").read_text()
        names=re.findall(r'!strcmp\(base,"([^"]+)"\)\) (build_game_variant_\w+)\(variant\)',s)
        self.assertEqual(len(names),26)
        for k,builder in names:self.assertIn(builder+"(",h)
        self.assertIn("game_modes_gametype_by_name(base,variant,options)",s)
        self.assertEqual(len(re.findall(r'if\(!strcmp\(key,"[^"]+"\)\)',(G/"launcher_match_rules.h").read_text())),71)
        main=(ROOT/"source/main/main.c").read_text()
        self.assertIn('launcher_match_rules_apply(getenv("HALO_LAUNCHER_MATCH_RULES")',main)
        self.assertIn("launcher_match_preset_build(mode, &variant, &options)",main)
        self.assertIn("memcmp(&game->variant_options,&options,sizeof(options))",main)
        self.assertIn('argc == 9 && !strcmp(argv[1], "--launcher-match-rules")',(ROOT/"port/android/host/host_main.c").read_text())
        self.assertIn("LauncherMatch.offlinePreset(dataRoot, map.id, preset", (J/"LauncherActivity.java").read_text())
if __name__=="__main__":unittest.main()
