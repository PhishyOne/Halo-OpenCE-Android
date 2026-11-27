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
                       for px,py in positions), (number,a,b,z)
