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
    /* Oddball: each bot goes after a live ball, without chasing its own
       ball when carrying. Distribution supports up to 16 balls. */
    CHECK(bots_oddball_goal_slot(0, 0u, 0) == -1);
    CHECK(bots_oddball_goal_slot(0, 1u, 0) == 0);
    CHECK(bots_oddball_goal_slot(15, 1u, 0) == 0);
    CHECK(bots_oddball_goal_slot(0, (1u<<1)|(1u<<4)|(1u<<15), 0) == 1);
    CHECK(bots_oddball_goal_slot(1, (1u<<1)|(1u<<4)|(1u<<15), 0) == 4);
    CHECK(bots_oddball_goal_slot(2, (1u<<1)|(1u<<4)|(1u<<15), 0) == 15);
    CHECK(bots_oddball_goal_slot(3, (1u<<1)|(1u<<4)|(1u<<15), 0) == 1);
    CHECK(bots_oddball_goal_slot(1, 0xFFFFu, 1) == -1);
    CHECK(bots_oddball_goal_slot(-1, 1u, 0) == -1);

    /* Race: bot index chooses between only legal checkpoints provided
       by Race's authoritative per-player progress and variant rules. */
    CHECK(bots_race_goal_slot(0, 0u) == -1);
    CHECK(bots_race_goal_slot(0, (1u<<8)) == 8);
    CHECK(bots_race_goal_slot(18, (1u<<8)) == 8);
    CHECK(bots_race_goal_slot(0, (1u<<2)|(1u<<7)|(1u<<31)) == 2);
    CHECK(bots_race_goal_slot(1, (1u<<2)|(1u<<7)|(1u<<31)) == 7);
    CHECK(bots_race_goal_slot(2, (1u<<2)|(1u<<7)|(1u<<31)) == 31);
    CHECK(bots_race_goal_slot(3, (1u<<2)|(1u<<7)|(1u<<31)) == 2);
    CHECK(bots_race_goal_slot(-1, (1u<<31)) == -1);
    puts("bot objective policy: CTF, King, Oddball, Race passed");
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
            self.assertIn("CTF, King, Oddball, Race passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
