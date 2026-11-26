"""Run the actual launcher-to-System-Link adapter against a native pregame harness."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from tools.harness import function

ROOT = Path(__file__).resolve().parents[1]

class NativeLobbyHandoffTest(unittest.TestCase):
    def test_handoff(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / "lobby"
            subprocess.run([os.environ.get("CC", "cc"), "-std=gnu99", "-Wall", "-Wextra",
                "-Werror", "-I" + str(ROOT / "port/linux/game"),
                str(ROOT / "tools/lan/launcher_lan_test.c"), "-o", str(binary)], check=True)
            for case in ("classic", "host", "guest", "ce", "ffa", "badrows", "badseat",
                         "badmode", "path", "peer", "missing", "timeout"):
                with self.subTest(case=case):
                    subprocess.run([str(binary), case], check=True, timeout=5)

    def test_named_host_discovery(self):
        source = (ROOT / "source/networking/network_client_manager.c").read_text()
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            (work / "launcher_join.inc").write_text(function(source, "network_game_client_join_matching_game"))
            binary = work / "join"
            subprocess.run([os.environ.get("CC", "cc"), "-std=gnu99", "-Wall", "-Wextra", "-Werror",
                "-Wno-missing-field-initializers", "-I" + temp,
                str(ROOT / "tools/lan/launcher_lan_discovery_test.c"), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)
