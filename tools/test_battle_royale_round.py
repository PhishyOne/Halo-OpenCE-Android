"""Compile and exercise the asset-free Battle Royale host round prototype."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class BattleRoyaleRoundTest(unittest.TestCase):
    def test_host_round(self):
        with tempfile.TemporaryDirectory() as tmp:
            program = Path(tmp) / "battle-royale-round"
            subprocess.run([
                os.environ.get("CC", "cc"), "-std=c99", "-Wall", "-Wextra", "-Werror", "-O2",
                "-ffp-contract=off", "-iquote", "port/linux/game", "-iquote", "port/linux/include",
                "port/linux/game/battle_royale_round.c", "tools/lan/battle_royale_round_test.c",
                "-o", str(program),
            ], cwd=ROOT, check=True)
            subprocess.run([str(program)], check=True)


if __name__ == "__main__":
    unittest.main()
