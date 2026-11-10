"""Run asset-free C tests against the exact bot vehicle policy and Action code.

This intentionally avoids pytest, a 32-bit runtime and full Halo assets,
so it works on the GitHub Android-only runner and a tiny Forge TCC install.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from harness import function, mutated, read

ROOT = Path(__file__).resolve().parent.parent
SOURCE = read("port/linux/game/bots.c")
COMPILER = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
SEAT_CASES = (
    "gunner-empty-vehicle", "no-driver-control", "passenger-needs-driver",
    "occupied-seat", "disabled-vehicle", "dead-vehicle", "invalid-vehicle",
    "no-animation", "empty-seat-label", "action-press", "wrong-action",
    "already-seated", "cooldown", "flipped-seat-rejected", "flip-action",
    "flip-wrong-object", "flip-while-seated", "flip-cooldown", "flip-goal-only"
)
FLIP_CASES = (
    "overturned-eligible", "upright-rejected", "driver-rejected",
    "upending-rejected", "destroyed-rejected", "disabled-rejected",
    "rotating-rejected", "invalid-rejected", "one-rescuer",
    "other-vehicle-not-reserved", "ignore-unspawned-reservation",
    "ignore-other-goal", "ignore-own-reservation", "approach-flipped",
    "do-not-approach-upright", "do-not-crowd-reserved",
    "do-not-approach-moving", "do-not-approach-too-far",
    "do-not-approach-without-ground", "do-not-retry-ignored-wreck",
    "recovery-reservation-expires", "recovery-reservation-valid",
    "recovery-after-righting-ends", "release-reservation-cooldown"
)
SEAT_SOURCE = {
    "under_test_seat.inc": "bots_vehicle_seat_usable",
    "under_test_stop.inc": "bots_vehicle_stop_seeking",
    "under_test_action.inc": "bots_bot_vehicle_action",
}
FLIP_SOURCE = {
    "under_test_flip_eligible.inc": "bots_vehicle_can_flip",
    "under_test_flip_reserved.inc": "bots_vehicle_flip_reserved",
    "under_test_flip_pick.inc": "bots_vehicle_pick_flip",
    "under_test_flip_keep.inc": "bots_vehicle_keep_flip_goal",
    "under_test_flip_stop.inc": "bots_vehicle_stop_seeking",
}


def build(work, name, sources, fault=None):
    folder = work / name
    folder.mkdir()
    for output, symbol in sources.items():
        code = function(SOURCE, symbol)
        if fault and fault[0] == symbol:
            code = mutated(code, fault[1], fault[2])
        (folder / output).write_text(code)
    target = folder / name
    command = [
        COMPILER, "-std=gnu99", "-Wall", "-Werror",
        "-Wno-unused-function", "-Wno-unused-variable",
        "-I", str(ROOT / "tools/harness/include"), "-I", str(folder),
        str(ROOT / f"tools/harness/tests/{name}.c"),
        "-o", str(target), "-lm",
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(f"{name} compilation failed: {result.stderr}")
    return target


def run_case(binary, case, should_pass=True):
    result = subprocess.run(
        [str(binary), case], capture_output=True, text=True, timeout=20
    )
    if (result.returncode == 0) != should_pass:
        raise AssertionError(
            f"{binary.name} [{case}] expected {'pass' if should_pass else 'failure'}, "
            f"exit={result.returncode}: {result.stdout}{result.stderr}"
        )


def main():
    if not COMPILER:
        raise RuntimeError("C compiler required for bot vehicle policy tests")
    # Integration checks: a valid flip action has to be reachable through
    # the bot's goal planner and update loop, not just unit-test helpers.
    planner = function(SOURCE, "bots_choose_goal")
    update = function(SOURCE, "bots_bot_update")
    assert "bots_vehicle_pick_flip(bot, position)" in planner
    assert "bots_vehicle_keep_flip_goal(bot)" in planner
    assert "!fighting && now >= bot->next_vehicle_scan_tick" in planner
    assert "bot->goal_kind == _bots_goal_vehicle_flip" in update
    assert "bots_bot_vehicle_action(bot, &tick)" in update

    with tempfile.TemporaryDirectory(prefix="halo-bot-flip-") as td:
        root = Path(td)
        seat = build(root, "bot_vehicle_seats", SEAT_SOURCE)
        flip = build(root, "bot_vehicle_flip", FLIP_SOURCE)
        for case in SEAT_CASES:
            run_case(seat, case)
        for case in FLIP_CASES:
            run_case(flip, case)

    mutations = (
        ("bot_vehicle_seats", SEAT_SOURCE,
         ("bots_vehicle_seat_usable", "vehicle->object.up.k < 0.2f", "FALSE"),
         "flipped-seat-rejected"),
        ("bot_vehicle_seats", SEAT_SOURCE,
         ("bots_bot_vehicle_action",
          "tick->player->action_result == _player_action_result_flip_vehicle",
          "tick->player->action_result == _player_action_result_enter_vehicle"),
         "flip-action"),
        ("bot_vehicle_flip", FLIP_SOURCE,
         ("bots_vehicle_can_flip",
          "TEST_FLAG(vehicle->vehicle.flags, _vehicle_upending_bit)", "FALSE"),
         "upending-rejected"),
        ("bot_vehicle_flip", FLIP_SOURCE,
         ("bots_vehicle_flip_reserved", "other != claimant", "other == claimant"),
         "ignore-own-reservation"),
        ("bot_vehicle_flip", FLIP_SOURCE,
         ("bots_vehicle_pick_flip",
          "bots_vehicle_flip_reserved(bot, iterator.index)", "FALSE"),
         "do-not-crowd-reserved"),
        ("bot_vehicle_flip", FLIP_SOURCE,
         ("bots_vehicle_keep_flip_goal",
          "game_time_get() - bot->goal_tick < BOTS_VEHICLE_FLIP_RESERVATION_TICKS",
          "TRUE"), "recovery-reservation-expires"),
    )
    with tempfile.TemporaryDirectory(prefix="halo-bot-flip-mut-") as td:
        root = Path(td)
        for index, (name, sources, fault, case) in enumerate(mutations):
            child = root / str(index)
            child.mkdir()
            mutated_binary = build(child, name, sources, fault)
            run_case(mutated_binary, case, should_pass=False)

    print(
        f"Bot Warthog recovery PASS: {len(SEAT_CASES)} seat/action + "
        f"{len(FLIP_CASES)} flip policy + {len(mutations)} mutation controls"
    )


if __name__ == "__main__":
    main()
