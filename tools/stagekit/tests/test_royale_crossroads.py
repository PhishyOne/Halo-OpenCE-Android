"""Walk the built sector with the engine's deterministic test player."""
PLAY = dict(seconds=90)


def test_crossroads_has_standing_and_movement(play):
    session = play('royale_crossroads')
    places = session.places()
    assert len(places) == 90 and session.deaths(0) == 0
    assert max(x for x,y,z in places) - min(x for x,y,z in places) > 1
    assert min(z for x,y,z in places) >= -0.01
    # The west wall is at 11 metres; the biped stops one radius away.
    face = 11/3.048 - .25
    assert abs(places[0][0] - face) < .005 and abs(places[1][0] - face) < .005


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
    n, surfaces = block(bsp + 0xf8)
    assert n == sum(map(len, scene.faces.values()))
    n, light = block(bsp + 0x104)
    assert n == 1
    n, material = block(light + 0x14)
    assert n == len(scene.faces)
    for i, faces in enumerate(scene.faces.values()):
        mat = material + 256*i
        assert struct.unpack_from('<I', data, mat+0xb4)[0] == 3*len(faces)
        v = 0x800 + struct.unpack_from('<I', data, mat+0xd8+12)[0] - base
        for j, tri in enumerate(faces):
            assert struct.unpack_from('<3H', data, surfaces) == (3*j, 3*j+2, 3*j+1)
            surfaces += 6
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


def test_host_seeds_pickups_from_baked_markers(stagekit, tmp_path):
    import os
    import struct
    import subprocess
    import runner
    import orchard
    scene = orchard.build()
    data = runner.generate(stagekit, 'royale_crossroads', tmp_path).read_bytes()
    rows = []
    for name, metres in scene.loot:
        field = name.encode().ljust(32, b'\0')
        assert data.count(field) == 1
        xyz = struct.unpack_from('<3f',data,data.index(field)+32)
        assert max(abs(a*3.048-b) for a,b in zip(xyz,metres)) < .001
        rows.append(name+' '+' '.join(map(str,xyz)))
    exe = tmp_path/'round'
    subprocess.run([os.environ.get('CC','cc'), '-std=c99', '-Werror','-Wall',
                    '-iquote','port/linux/include','-iquote','port/linux/game',
                    'tools/lan/battle_royale_sector_loot_test.c','port/linux/game/battle_royale_round.c',
                    '-o',str(exe)], cwd=runner.ROOT, check=True)
    subprocess.run([exe], input='\n'.join(rows)+'\n',text=True, check=True)


def test_every_building_is_searchable_standing():
    from collections import deque
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import orchard
    scene = orchard.build()
    radius, height, step = .25*3.048, 3.048, .20
    for number,(x,y,w,d) in enumerate(scene.buildings,1):
        boxes=[b for b in scene.solids if b[2]<height and b[2]+b[5]>0 and
               b[0]<x+w+2 and b[0]+b[3]>x-2 and b[1]<y+d+2 and b[1]+b[4]>y-2]
        def clear(px,py):
            for a,b,_,bw,bd,_ in boxes:
                dx=max(a-px,0,px-a-bw)
                dy=max(b-py,0,py-b-bd)
                if dx*dx+dy*dy < radius*radius: return False
            return True
        # Flood from the front doorstep using the standing biped's radius and height.
        base_x,base_y=x+w/2,y-1.6
        queue=deque([(0,0)])
        visited={(0,0)}
        positions=[]
        while queue:
            i,j=queue.popleft()
            px,py=base_x+step*i,base_y+step*j
            positions.append((px,py))
            for nr in ((i-1,j),(i+1,j),(i,j-1),(i,j+1)):
                if nr in visited: continue
                nx,ny=base_x+step*nr[0],base_y+step*nr[1]
                if x-1<nx<x+w+1 and base_y<=ny<y+d+.8 and clear(nx,ny):
                    visited.add(nr);queue.append(nr)
        points=[p for name,p in scene.loot if name.startswith(f'br_in_{number:02d}_')]
        assert len(points)==4
        for a,b,z in points:
            assert any((px-a)**2+(py-b)**2+z*z <= (3.048*.5)**2
                       for px,py in positions if x<px<x+w and y<py<y+d and
                       (py < y+d*.52)==(b < y+d*.52)), (number,a,b,z)

def test_walk_through_each_front_and_inner_door(stagekit, tmp_path):
    import math
    import struct
    import runner
    import orchard
    # Each independent copy changes only the fixture player's start and facing.
    # Preserve the actual compiled walls, ceilings, furniture and collision.
    f32 = lambda v: struct.unpack('<f',struct.pack('<f',v))[0]
    unit = f32(3.048)
    original = struct.pack('<4f', f32(11/unit)-1.25, -33/unit, .1, 0)
    for i,(x,y,w,d) in enumerate(orchard.build().buildings):
        folder = tmp_path/str(i)
        path = runner.generate(stagekit,'royale_crossroads',folder)
        data = bytearray(path.read_bytes())
        assert data.count(original)==1
        struct.pack_into('<4f',data,data.index(original),(x+w/2)/unit,(y-2.3)/unit,.1,math.pi/2)
        path.write_bytes(data)
        session=runner.play(folder,seconds=12,input='walk-forward')
        positions=[py*unit for _,py,_ in session.places()]
        back_wall=y+d-.35-.25*unit
        assert max(positions) >= back_wall-.12, (i, positions)
        assert max(positions) <= back_wall+.12 and session.deaths(0)==0


def test_echo419_manual_and_last_drop(stagekit, tmp_path):
    import re
    import runner
    results = {}
    for action in ('jump', 'wait'):
        folder = tmp_path/action
        runner.generate(stagekit, 'royale_crossroads', folder)
        session = runner.play(folder, seconds=82, input='echo419-'+action)
        assert 'Echo 419: boarded 1' in session.log
        drops = re.findall(r'Echo 419: dropped seat 0 at tick (\d+)', session.log)
        landings = re.findall(r'Echo 419: landed at tick (\d+)', session.log)
        assert len(drops) == len(landings) == 1, (action, drops, landings)
        drop, landed = int(drops[0]), int(landings[0])
        assert 90 <= drop <= 1800 and drop + 60 < landed < drop + 450
        places = session.places()
        assert max(z for x, y, z in places) > 75
        assert any(15 < z < 65 for x, y, z in places)
        assert abs(places[-1][2]) < .05 and session.deaths(0) == 0
        # The regular move stick must influence the airborne biped after release.
        falling = [(x,y) for x,y,z in places if 5 < z < 74]
        assert max(x for x,y in falling)-min(x for x,y in falling) > 1
        results[action] = drop
    assert results['jump'] < 300 and results['wait'] == 1800, results


def test_landing_clearings_are_open_and_close_to_loot():
    import math
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import orchard
    scene = orchard.build()
    # Conservative overhead clearance: even a canopy's bounding box must
    # miss a full standing capsule at every formation position.
    overhead = [(min(p[0] for p in tri), max(p[0] for p in tri),
                 min(p[1] for p in tri), max(p[1] for p in tri))
                for faces in scene.faces.values() for tri in faces if max(p[2] for p in tri) > .1]
    # Enclose the entire random offset in each axis as well as the biped.
    radius = (.25 + .06) * 3.048
    positions = []
    for name, x, y in orchard.LANDINGS:
        for cell in range(16):
            px = x + (cell % 4 - 1.5) * .75 * 3.048
            py = y + (cell // 4 - 1.5) * .75 * 3.048
            assert not any(a-radius <= px <= b+radius and c-radius <= py <= d+radius
                           for a, b, c, d in overhead), (name, cell)
            assert min(math.hypot(px-lx, py-ly) for _, (lx, ly, _) in scene.loot) <= 25
            assert all(math.hypot(px-ox, py-oy) > 2*radius for ox, oy in positions)
            positions.append((px, py))
    assert len(positions) == 576


def check_flown_route(session):
    import math
    import re
    found = re.findall(r'Echo 419: route ([-.\d]+) ([-.\d]+) to ([-.\d]+) ([-.\d]+)', session.log)
    assert len(found) == 1 and 'using authored fallback' not in session.log
    ax, ay, bx, by = map(float, found[0])
    length = math.hypot(bx-ax, by-ay)
    assert length*3.048 >= 7199.9
    hx, hy = (bx-ax)/length, (by-ay)/length
    # Measure the real riding biped's first seconds before it can leave.
    cabin = [(x,y) for x,y,z in session.places() if z > 77][:3]
    assert len(cabin) >= 2
    assert all(abs((y-ay)*hx-(x-ax)*hy) < .1 for x,y in cabin)
    assert (cabin[1][0]-cabin[0][0])*hx + (cabin[1][1]-cabin[0][1])*hy > 10
    # The pilot calls Orchard five seconds before the actual closest pass.
    approach = int(((-ax*hx-ay*hy)/length)*1980+.5)
    near = abs(ay*hx-ax*hy) < 60 and 240 <= approach < 1800
    notices = re.findall(r'Orchard Crossing approach at tick (\d+)', session.log)
    assert [int(tick) for tick in notices] == ([approach-150] if near else [])
    return found[0]


def test_guided_drops_land_near_loot(stagekit, tmp_path):
    import math
    import re
    import struct
    import runner
    import orchard
    # Leave just one named clearing at a time, forcing normal host assignment
    # to exercise it. The stem of each flag is eight bytes in both versions.
    for name, x, y in orchard.LANDINGS:
        folder = tmp_path/name
        path = runner.generate(stagekit, 'royale_crossroads', folder)
        data = bytearray(path.read_bytes())
        for other, ox, oy in orchard.LANDINGS:
            field = ('br_land_'+other).encode().ljust(32, b'\0')
            assert data.count(field) == 1
            offset = data.index(field)
            assert all(abs(a*3.048-b) < .001 for a,b in
                       zip(struct.unpack_from('<3f', data, offset+32), (ox, oy, 0)))
            if other != name:
                data[offset:offset+8] = b'qa_skip_'
        path.write_bytes(data)
        session = runner.play(folder, seconds=70, input='echo419-guide')
        check_flown_route(session)
        drops = re.findall(r'dropped seat 0 at tick (\d+)', session.log)
        assert len(drops) == 1 and 90 <= int(drops[0]) < 1800
        selected = re.findall(r'planned clearing 0 spot (\d+) at ([-.\d]+) ([-.\d]+)', session.log)
        assert len(selected) == 1
        cell, tx, ty = int(selected[0][0]), float(selected[0][1]), float(selected[0][2])
        assert abs(tx-(x/3.048+(cell%4-1.5)*.75)) < .061
        assert abs(ty-(y/3.048+(cell//4-1.5)*.75)) < .061
        landings = re.findall(r'guided touchdown at ([-.\d]+) ([-.\d]+) ([-.\d]+)', session.log)
        assert len(landings) == 1, (name, session.log[-2000:])
        px, py, pz = map(float, landings[0])
        distance = math.hypot(px-tx, py-ty)*3.048
        print(f'{name}: jumped at tick {drops[0]}, landed {distance:.2f} metres from target')
        assert distance < 3 and abs(pz) < .05 and session.deaths(0) == 0, (name, distance, pz)


def test_complete_map_replays_seeded_drops(stagekit, tmp_path):
    import re
    import runner
    results = []
    for index, seed in enumerate([27, 419, 27]):
        folder = tmp_path/str(index)
        runner.generate(stagekit, 'royale_crossroads', folder)
        session = runner.play(folder, seconds=70, input=f'echo419-guide:{seed}')
        result = re.findall(r'planned clearing (\d+) spot (\d+) at ([-.\d]+) ([-.\d]+)', session.log)
        assert len(result) == 1 and 'guided touchdown at' in session.log
        assert f'drop seed {seed}' in session.log
        results.append((check_flown_route(session), result[0]))
    assert results[0] == results[2]
    assert results[0][0] != results[1][0] and results[0][1] != results[1][1]


def test_scatter_over_many_rosters(tmp_path):
    import os
    import sys
    import subprocess
    from pathlib import Path
    root = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(root/'tools/stagekit'))
    import orchard
    exe = tmp_path/'scatter'
    subprocess.run([os.environ.get('CC','cc'), '-std=c99', '-Wall', '-Wextra', '-Werror', '-O2',
                    '-ffp-contract=off', '-iquote','port/linux/include','-iquote','port/linux/game',
                    'tools/lan/battle_royale_scatter_test.c', 'port/linux/game/battle_royale_scatter.c',
                    'port/linux/game/battle_royale_route.c', 'port/linux/game/battle_royale_round.c', '-lm', '-o', str(exe)], cwd=root, check=True)
    sites = ''.join(f'{x/3.048} {y/3.048} 0\n' for _, x, y in orchard.LANDINGS)
    subprocess.run([exe], input=sites, text=True, check=True)
