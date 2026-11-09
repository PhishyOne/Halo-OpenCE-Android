"""Compile and test the exact source-only C policy. No commercial maps needed."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "port/linux/game/bots_objectives_policy.h"

C_TEST = r"""
#include <stdio.h>
#include "bots_objectives_policy.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while(0)
int main(void) {
    CHECK(bots_king_goal_slot(0, 0) == -1);
    CHECK(bots_king_goal_slot(0, 1u) == 0);
    CHECK(bots_king_goal_slot(1, 1u) == 0);
    CHECK(bots_king_goal_slot(0, (1u << 0) | (1u << 2) | (1u << 4)) == 0);
    CHECK(bots_king_goal_slot(1, (1u << 0) | (1u << 2) | (1u << 4)) == 2);
    CHECK(bots_king_goal_slot(2, (1u << 0) | (1u << 2) | (1u << 4)) == 4);
    CHECK(bots_king_goal_slot(3, (1u << 0) | (1u << 2) | (1u << 4)) == 0);
    CHECK(bots_king_goal_slot(16, (1u << 2)) == 2);

    CHECK(bots_ctf_goal_slot(0, -1, 0, 15u) == -1);
    CHECK(bots_ctf_goal_slot(0, 2, 0, 15u) == -1);
    /* Team red 0: defend flag zero; attack flag one; carry to index three. */
    CHECK(bots_ctf_goal_slot(0, 0, 0, 15u) == 0);
    CHECK(bots_ctf_goal_slot(1, 0, 0, 15u) == 1);
    CHECK(bots_ctf_goal_slot(2, 0, 0, 15u) == 1);
    CHECK(bots_ctf_goal_slot(3, 0, 0, 15u) == 0);
    CHECK(bots_ctf_goal_slot(2, 0, 1, 15u) == 3);
    /* Team blue 1: defend flag one; attack flag zero; carry to index two. */
    CHECK(bots_ctf_goal_slot(0, 1, 0, 15u) == 1);
    CHECK(bots_ctf_goal_slot(1, 1, 0, 15u) == 0);
    CHECK(bots_ctf_goal_slot(4, 1, 1, 15u) == 2);
    /* Never send a flag carrier back to the enemy flag on missing state. */
    CHECK(bots_ctf_goal_slot(1, 0, 1, 3u) == 0);
    CHECK(bots_ctf_goal_slot(1, 0, 1, 0u) == -1);
    CHECK(bots_ctf_goal_slot(1, 0, 0, 1u) == 0);
    CHECK(bots_ctf_goal_slot(0, 1, 0, 1u) == 0);
    CHECK(bots_ctf_goal_slot(0, 1, 0, 0u) == -1);
    puts("bot objective policy: 23 cases passed");
    return 0;
}
"""


class BotObjectivePolicyTest(unittest.TestCase):
    def test_policy_compiles_and_works(self):
        compiler = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        if not compiler:
            self.skipTest("no C compiler")
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "test.c"
            binary = Path(temp) / "test"
            source.write_text(C_TEST)
            subprocess.run([compiler, "-I", str(HEADER.parent), str(source), "-o", str(binary)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertIn("23 cases passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
