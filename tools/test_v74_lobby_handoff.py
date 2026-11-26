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
                         "badmode", "path", "peer", "missing", "timeout", "playlist-host",
                         "playlist-guest", "playlist-quit", "quick-finish"):
                with self.subTest(case=case):
                    receipt = Path(temp) / "lobby-return.txt"
                    receipt.unlink(missing_ok=True)
                    subprocess.run([str(binary), case], check=True, timeout=5,
                                   env={**os.environ, "HALO_RATING_PRIVATE_ROOT": temp})
                    if case in ("playlist-host", "playlist-guest", "playlist-quit", "quick-finish", "host", "missing", "timeout"):
                        lines = receipt.read_text().splitlines()
                        self.assertEqual(lines[0], "0123456789abc")
                        self.assertEqual(lines[1], "complete" if case in (
                            "playlist-host", "playlist-guest", "quick-finish") else "stopped")

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
