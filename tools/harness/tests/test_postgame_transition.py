"""Use the extracted real client transition handler to check postgame -> lobby.
Zero assets and no network; regressions here can disconnect a guest client."""
import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from harness import build, function, mutated, read, run, CHECK_FAILED  # noqa: E402

CASES = ("normal", "duplicate", "malformed-postgame", "malformed-duplicate", "foreign", "ingame")
ENGINE = "source/networking/network_client_message_handler.c"


def generated(fault=None):
    extracted = function(read(ENGINE),
                         "network_game_client_handle_message_server_switch_to_pregame")
    if fault:
        extracted = mutated(extracted, *fault)
    return (("under_test.inc", extracted),)


@pytest.mark.parametrize("case", CASES)
def test_postgame_handler(case):
    result, output = run(build("postgame_transition", generated()), case)
    assert result == 0, output


@pytest.mark.parametrize("fault,case", [
    (('if (state == _network_game_client_state_pregame)', 'if (FALSE)'), 'duplicate'),
    (('return FALSE;\n\t\t\t}', 'return TRUE;\n\t\t\t}'), 'malformed-postgame'),
])
def test_regression_controls(fault, case):
    result, output = run(build("postgame_transition", generated(fault)), case)
    assert result == CHECK_FAILED, f"Fault {case!r} escaped: {output}"


def test_touch_guest_offers_non_disconnecting_wait():
    source = read("port/linux/game/touch_postgame.c")
    game = read("source/game/game_engine.c")
    assert 'L"WAIT FOR HOST"' in source
    assert 'touch_postgame.shown[index] = TRUE;' in source
    assert 'host ? host_labels[index] : guest_labels[index]' in source
    assert 'if (global_network_game_server_get())' in game
    assert 'network_game_server_reset_to_pregame(' in game
    assert 'network_game_abort();' in game
