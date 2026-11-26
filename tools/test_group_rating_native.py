"""Run the exact Android group receipt logic against a fake ordered game transport."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
GAME=ROOT/"port/linux/game"

class GroupNativeTest(unittest.TestCase):
    def test_native_roster_lock_and_forfeits(self):
        cases=("bots","configured_bots","online","optout","unknown","prelock_leave",
               "duplicate","normal","draw","quit","quit_rejoin","team_quit",
               "manual_end","host_abandon","host_crash_observed","late_join","guest")
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp)
            source=(GAME/"network_group_rating.c").read_text()
            source=source[source.index("extern int config_boolean"):source.rindex("#endif")]
            (work/"group.inc").write_text(source)
            build=subprocess.run([os.environ.get("CC","cc"),"-std=gnu89","-Wall","-Wextra","-Werror",
                "-I",str(work),"-I",str(GAME),str(ROOT/"tools/harness/tests/group_rating.c"),
                "-o",str(work/"test")],capture_output=True,text=True)
            self.assertEqual(build.returncode,0,build.stderr)
            for case in cases:
                with self.subTest(case=case):
                    data=work/case;data.mkdir()
                    run=subprocess.run([str(work/"test"),case,str(data)],capture_output=True,text=True,timeout=10)
                    self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            # Parse the real native output with the production Java reader.
            java=ROOT/"port/android/app/src/main/java/com/halo/decomp"
            subprocess.run(["javac","-d",str(work),*[str(java/n) for n in (
                "CareerRecords.java","LanRating.java","LanBoardProtocol.java","GroupRatingReceipt.java",
                "GroupRatingLedger.java","GroupRatingWire.java")],
                str(ROOT/"tools/lan/GroupRatingTest.java")],check=True)
            subprocess.run(["java","-cp",str(work),"com.halo.decomp.GroupRatingTest",str(work)],check=True)

    def test_reliable_native_integration_and_compatibility(self):
        engine=(ROOT/"source/game/game_engine.c").read_text()
        dispatch=(GAME/"network_distributed.c").read_text()
        menu=(GAME/"menu_functions.c").read_text()
        self.assertIn("group_rating_update(global_variant.game_engine_index)",engine)
        self.assertIn("group_rating_finish(global_variant.game_engine_index)",engine)
        self.assertIn("group_rating_abandon();",engine)
        self.assertIn("group_rating_host_end();",menu)
        guard=dispatch[dispatch.index("if (group_rating_handle("):]
        self.assertIn("!distributed_handling_batch",guard[:guard.index("return;")])
        self.assertIn("distributed_handling_stream_message",guard[:guard.index("return;")])
        from tools import network_format
        self.assertEqual(network_format.check(),[])

if __name__=="__main__":
    unittest.main()
