"""Asset-free tests of the exact bot seat policy and normal player Action key."""
import sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from harness import build, function, read, run, mutated, CHECK_FAILED

CASES = (
    "gunner-empty-vehicle", "no-driver-control", "passenger-needs-driver",
    "occupied-seat", "disabled-vehicle", "dead-vehicle", "invalid-vehicle", "no-animation",
    "action-press", "wrong-action", "already-seated", "cooldown"
)

def generated(fault=None):
    source = read("port/linux/game/bots.c")
    seat = function(source, "bots_vehicle_seat_usable")
    action = function(source, "bots_bot_vehicle_action")
    if fault:
        target, original, replacement = fault
        if target == "seat":
            seat = mutated(seat, original, replacement)
        else:
            action = mutated(action, original, replacement)
    return (("under_test_seat.inc", seat), ("under_test_action.inc", action))


@pytest.mark.parametrize("case",CASES)
def test_vehicle_seat(case):
    rc,output = run(build("bot_vehicle_seats",generated()),case)
    assert rc == 0, output


@pytest.mark.parametrize("fault,case", [
    (("seat", "if (TEST_FLAG(seat->flags, _unit_seat_driver_bit))", "if (FALSE)"),
     "no-driver-control"),
    (("action", "tick->player->action_seat_index == bot->vehicle_seat_index",
      "tick->player->action_seat_index != bot->vehicle_seat_index"), "wrong-action"),
])
def test_negative_control(fault, case):
    rc,output = run(build("bot_vehicle_seats",generated(fault)),case)
    assert rc == CHECK_FAILED, f"mutation escaped detection: {fault[0]}: {output}"
