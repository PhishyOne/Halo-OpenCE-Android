"""Walk the built sector with the engine's deterministic test player."""
PLAY = dict(seconds=90)


def test_crossroads_has_standing_and_movement(play):
    session = play('royale_crossroads')
    places = session.places()
    assert len(places) == 90 and session.deaths(0) == 0
    assert max(x for x,y,z in places) - min(x for x,y,z in places) > 1
    assert min(z for x,y,z in places) >= -0.01


def test_baked_mesh_matches_authored_sector(stagekit, tmp_path):
    import struct
    import sys
    from pathlib import Path
    import pytest
    import runner
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import orchard
    scene = orchard.build()
    data = runner.generate(stagekit, "royale_crossroads", tmp_path).read_bytes()
    base = struct.unpack_from('<I', data, 0x800)[0] - 0x18
    bsp = 0x818
    def block(pos):
        n, ptr = struct.unpack_from('<iI', data, pos)
        return n, 0x800 + ptr - base
    n, _ = block(bsp + 0xf8)
    assert n == sum(map(len, scene.faces.values()))
    n, light = block(bsp + 0x104)
    assert n == 1
    n, material = block(light + 0x14)
    assert n == len(scene.faces)
    for i, faces in enumerate(scene.faces.values()):
        mat = material + 256*i
        assert struct.unpack_from('<I', data, mat+0xb4)[0] == 3*len(faces)
        v = 0x800 + struct.unpack_from('<I', data, mat+0xd8+12)[0] - base
        for tri in faces:
            for point in tri:
                xyz = struct.unpack_from('<3f', data, v)
                assert xyz == pytest.approx([a/3.048 for a in point], abs=.0001)
                v += 56


def test_barn_entry_and_vehicle_lane_are_open():
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import orchard
    boxes = orchard.build().solids
    def blocked(x,y,z):
        return any(a<=x<=a+w and b<=y<=b+d and c<=z<=c+h for a,b,c,w,d,h in boxes)
    assert all(not blocked(18,y,1.6) for y in range(-43,-23))
    assert blocked(12,-35.9,1.6) and blocked(11.1,-33,1.6)
    assert all(not blocked(x,y,1.5) for x in range(-75,76) for y in (-3,0,3))
