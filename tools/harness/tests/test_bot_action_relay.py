"""The real host-only bot action queue bridge, with negative controls.

Uses source/game/player_queues_new.c directly; no copy of the function
is maintained in this test. The C test supplies only a fake update queue
and a simulated single tick, not maps or any game's asset files.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from harness import CHECK_FAILED, build, function, mutated, read, run  # noqa: E402


CASES = [
    "host", "client", "bad-action", "invalid-source", "wrong-update",
    "missing-update", "no-tick", "null-action", "beyond-count",
    "negative-index", "after-array",
]

NEGATIVE_CONTROLS = {
    "host-only": (
        ("game_connection() != _game_connection_network_server ||", ""),
        "client",
    ),
    "valid-action-only": (
        ("!distributed_action_valid(action) ||", ""),
        "bad-action",
    ),
    "right-tick-only": (
        ("update->update_number != update_number ||", ""),
        "wrong-update",
    ),
    "roster-limit": (
        ("absolute_index >= update->update.action_count", "FALSE"),
        "beyond-count",
    ),
}


def generated(fault=None):
    queue = read("source/game/player_queues_new.c")
    code = function(queue, "update_server_note_bot_action")
    if fault:
        code = mutated(code, *fault)
    return (("under_test.inc", code),)


@pytest.mark.parametrize("case", CASES)
def test_bot_action_relay(case):
    status, output = run(build("bot_action_relay", generated()), case)
    assert status == 0, output


@pytest.mark.parametrize("control", NEGATIVE_CONTROLS)
def test_fault_rejected(control):
    fault, case = NEGATIVE_CONTROLS[control]
    status, output = run(build("bot_action_relay", generated(fault)), case)
    assert status == CHECK_FAILED, (
        f"A deliberately broken queue bridge ({control}) escaped {case}: {output}"
    )
