"""Exercise the locally cut ground, bridge and buried rooms on the native engine."""
import math
import struct
import sys
from pathlib import Path

import pytest
import runner

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import orchard
from millworks import VEHICLES

UNIT = struct.unpack('<f', struct.pack('<f', 3.048))[0]


def play_from(stagekit, folder, origin, yaw, seconds, action='walk-forward'):
    path = runner.generate(stagekit, 'royale_crossroads', folder)
    data = bytearray(path.read_bytes())
    first_x = struct.unpack('<f', struct.pack('<f', 11/UNIT))[0] - 1.25
    start = struct.pack('<4f', first_x, -33/UNIT, .1, 0)
    assert data.count(start) == 1
    struct.pack_into('<4f', data, data.index(start), *(p/UNIT for p in origin), yaw)
    path.write_bytes(data)
    session = runner.play(folder, seconds=seconds, input=action)
    assert session.deaths(0) == 0 and len(session.places()) == seconds
    return session, [(x*UNIT, y*UNIT, z*UNIT) for x,y,z in session.places()]


@pytest.mark.parametrize('x,yaw,other', [(338,0,386),(392,math.pi,344)])
def test_bridge_crossing(stagekit, tmp_path, x, yaw, other):
    _, points = play_from(stagekit, tmp_path, (x,0,.3), yaw, 25)
    assert min(p[2] for p in points) >= -.1
    assert any(353 < px < 377 and abs(py)<.1 for px,py,_ in points)
    assert (points[-1][0]-other)*math.cos(yaw) >= 0


@pytest.mark.parametrize('x,yaw,other', [(590,0,682),(686,math.pi,594)])
def test_both_shelter_entrances(stagekit, tmp_path, x, yaw, other):
    session, points = play_from(stagekit, tmp_path, (x,58,.3), yaw, 48)
    assert min(p[2] for p in points) == pytest.approx(-1.45, abs=.1)
    assert (points[-1][0]-other)*math.cos(yaw) >= 0
    assert points[-1][2] > -.1 and ' underwater' not in ''.join(session.logged())


def test_shelter_store_and_millpond(stagekit, tmp_path):
    session, points = play_from(stagekit, tmp_path/'store', (638,57,-1.22), math.pi/2, 10)
    assert max(y for _,y,_ in points) >= 67 and points[-1][2] < -1.3
    assert ' underwater' not in ''.join(session.logged())
    for i,(x,y) in enumerate([(365,26),(372,191)]):
        session, points = play_from(stagekit, tmp_path/str(i), (x,y,.3), 0, 5, 'idle')
        assert points[-1][2] == pytest.approx(-3.6, abs=.1)
        assert ' underwater' in session.logged()[-1]


def test_vehicle_pads_and_woodlots(stagekit, tmp_path):
    scene = orchard.build()
    data = runner.generate(stagekit, 'royale_crossroads', tmp_path).read_bytes()
    assert [v[0] for v in VEHICLES].count('warthog') == 3
    assert [v[0] for v in VEHICLES].count('ghost') == 2
    for i,(name,x,y,angle) in enumerate(VEHICLES):
        key = f'br_vehicle_{name}_{i}'.encode().ljust(32,b'\0')
        assert data.count(key)==1
        px,py,pz,yaw = struct.unpack_from('<4f', data, data.index(key)+32)
        assert (px*UNIT,py*UNIT,pz*UNIT,yaw) == pytest.approx((x,y,.75,math.radians(angle)),abs=.001)
        assert not any(a < x+3 and x-3 < a+w and b < y+1.8 and y-1.8 < b+d and z < 2.1 and z+h > .1
                       for a,b,z,w,d,h in scene.solids)
    trees=[(x,y) for x,y,_,w,d,_ in scene.solids if w==d==.52 and 140<x<1320]
    assert len(trees)==191
