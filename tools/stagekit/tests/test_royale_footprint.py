"""Real Halo physics at the corner of the 8 km collision footprint."""
import struct

import pytest
import runner

HALF = 4000 / 3.048
PLAY = dict(seconds=90)


def test_serialized_bounds_and_collision_vertices(stagekit, tmp_path):
    data = runner.generate(stagekit, "royale_footprint", tmp_path).read_bytes()
    # StageKit writes the BSP immediately after the cache header. Its header
    # and structure are consecutive (stage_build), with 32-bit tag pointers.
    start = 0x800
    pointer, = struct.unpack_from("<I", data, start)
    bsp = start + 0x18
    base = pointer - (bsp - start)
    bounds = struct.unpack_from("<6f", data, bsp + 0xc8)
    assert bounds == pytest.approx((-HALF, HALF, -HALF, HALF, 0, 100))
    count, collision = struct.unpack_from("<iI", data, bsp + 0xb0)
    assert count == 1
    count, vertices = struct.unpack_from("<iI", data, start + collision - base + 0x54)
    assert count == 8
    corners = [struct.unpack_from("<fff", data, start + vertices - base + 16*i) for i in range(count)]
    expected = [(x, y, z) for x in (-HALF, HALF) for y in (-HALF, HALF) for z in (0, 100)]
    for pos, wanted in zip(sorted(corners), sorted(expected)):
        assert pos == pytest.approx(wanted)


def test_player_walks_jumps_and_hits_boundary(play):
    session = play("royale_footprint")
    places = session.places()
    assert len(places) == len(session.seconds) and session.deaths(0) == 0
    xs, ys, zs = zip(*places)
    assert min(xs) > HALF-200 and max(ys) < -HALF+200
    assert max(xs)-min(xs) > 0.5 and max(ys)-min(ys) > 0.5
    assert min(zs) == 0 and max(zs) > 0.2
    # Positions in the trace are rounded. The biped's radius is 0.25.
    assert max(xs) == pytest.approx(HALF-0.25, abs=0.025)
    assert min(ys) >= -HALF+0.25-0.025
