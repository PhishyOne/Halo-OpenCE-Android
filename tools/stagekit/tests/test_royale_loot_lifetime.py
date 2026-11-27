"""Optional live test using the owner's imported East Millworks and title maps."""
import os
from pathlib import Path
import subprocess
import re
import time

import pytest
import runner


def test_unclaimed_loot_survives_the_flight(tmp_path):
    data = os.environ.get("HALO_ROYALE_TEST_DATA")
    if not data:
        pytest.skip("set HALO_ROYALE_TEST_DATA to the imported game data root")
    for rel in ("maps/ui.map", "custom_maps/halo_royale.map"):
        source = (Path(data) / rel).resolve(strict=True)
        target = tmp_path / rel
        target.parent.mkdir(exist_ok=True)
        target.symlink_to(source)

    # A single idle player stays aboard until tick 1800. Nothing can collect
    # supplies before the first 900-tick abandoned-item purge.
    proc = runner.launch(tmp_path, "idle", "127.27.0.10", "127.27.0.11",
                         r"local:custom_maps\halo_royale:slayer", HALO_BOTS="0",
                         HALO_NETWORK_TEST_START="1", **runner.cue_settings([(0, "game_speed 12")], 1))
    log_path = tmp_path / "game.log"
    try:
        deadline = time.monotonic() + 150
        with log_path.open(errors="replace") as stream:
            while time.monotonic() < deadline and proc.poll() is None:
                line = stream.readline()
                if not line:
                    time.sleep(.05)
                    continue
                sample = re.search(r"network test: tick (\d+) .*?\| items (\d+)", line)
                if sample and int(sample[1]) >= 1200:
                    log = log_path.read_text(errors="replace")
                    assert "boarded 1\n" in log and "spawned 57 of 57 native pickups" in log
                    assert "riding" in line.split("|", 1)[0]
                    assert int(sample[2]) == 57
                    return
        pytest.fail("did not observe the player aboard after the purge: " +
                    log_path.read_text(errors="replace")[-2500:])
    finally:
        if proc.poll() is None:
            proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
