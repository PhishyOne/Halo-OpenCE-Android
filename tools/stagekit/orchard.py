"""Orchard Crossing. Geometry and solid boxes in metres, Z up."""
import math
import random
from collections import defaultdict
from pathlib import Path

COLORS = dict(meadow=0x748351, field=0xaf995f, furrow=0x978551, verge=0x948771,
              road=0x545857, lane=0xb5a184, plaster=0xc3b8a3, brick=0x945e48,
              roof=0x904d39, slate=0x53616a, timber=0x705545, trim=0xe0d6bc,
              bark=0x59493a, pine=0x355440, needle=0x44634a, leaf=0x617d45, crown=0x7e904e,
              floorboard=0x96795f, plank=0xa4876d, concrete=0x8b8a83)


# Each clearing accommodates a four-by-four group of landing positions.
LANDINGS = [
    ('west_cottage', -39, 1), ('east_cottage', -17, 0),
    ('south_cottage', -32, -33), ('barnyard', 18, -45),
    ('pump_house', 37, -29), ('water_tower', 31, -49),
    ('field_gate', 18, 10), ('orchard_lane', -54, 24),
]


class Sector:
    def __init__(self):
        self.faces = defaultdict(list)
        self.solids = []
        self.loot = []
        self.buildings = []

    def face(self, mat, *points):
        for i in range(1, len(points)-1):
            self.faces[mat].append((points[0], points[i], points[i+1]))

    def slab(self, mat, x, y, w, d, z):
        self.face(mat, (x,y,z), (x+w,y,z), (x+w,y+d,z), (x,y+d,z))

    def box(self, mat, x, y, z, w, d, h, solid=True):
        base = [(x,y,z), (x+w,y,z), (x+w,y+d,z), (x,y+d,z)]
        top = [(a,b,z+h) for a,b,_ in base]
        self.face(mat, *top)
        self.face(mat, *reversed(base))
        for i in range(4):
            j = (i+1) % 4
            self.face(mat, base[i], base[j], top[j], top[i])
        if solid:
            self.solids.append((x,y,z,w,d,h))

    def building(self, x, y, w, d, h, mat, roof, barn=False):
        # The door faces south. The side window is a real opening cut into the wall.
        t = 0.35
        door = 3.8 if barn else 1.9
        lintel = 3.5 if barn else 3.15
        side = (w-door)/2
        self.box(mat,x,y,0,side,t,h)
        self.box(mat,x+side+door,y,0,side,t,h)
        self.box(mat,x+side,y,lintel,door,t,h-lintel)
        self.box(mat,x,y+d-t,0,w,t,h)
        for xx in (x,x+w-t):
            end = (d-2.4)/2
            self.box(mat,xx,y+t,0,t,end-t,h)
            self.box(mat,xx,y+d-end,0,t,end-t,h)
            self.box(mat,xx,y+end,0,t,2.4,1.1)
            self.box(mat,xx,y+end,2.45,t,2.4,h-2.45)
            self.box('trim',xx-0.06,y+end-0.12,1.04,t+0.12,2.64,0.10,False)
        # A ceiling caps the walkable interior beneath the pitched shell.
        self.box('timber',x,y,h,w,d,0.18)
        lo, hi = x-0.45, x+w+0.45
        front, back, ridge = y-0.45,y+d+0.45,x+w/2
        peak = h+w*0.31
        for yy,rev in [(front,False),(back,True)]:
            pts=[(lo,yy,h),(hi,yy,h),(ridge,yy,peak)]
            self.face(mat, *(pts[::-1] if rev else pts))
        self.face(roof,(lo,front,h),(ridge,front,peak),(ridge,back,peak),(lo,back,h))
        self.face(roof,(ridge,front,peak),(hi,front,h),(hi,back,h),(ridge,back,peak))
        if not barn:
            self.box('brick',x+w*0.69,y+d*0.7,h,0.8,0.8,w*0.30+0.8,False)

    def furnish(self, number, x, y, w, d, h, kind):
        self.buildings.append((x,y,w,d))
        # Thin floor finishes sit on the continuous walkable ground plane.
        if kind == 'house':
            for row in range(16):
                self.slab('plank' if row % 3 == 0 else 'floorboard',
                          x, y+row*d/16, w, d/16, .025)
        else:
            self.slab('concrete' if kind == 'utility' else 'lane', x, y, w, d, .025)
        # Each front room opens into a second room. Keep the center aisle clear.
        gap = 3 if kind == 'barn' else 2.35
        side = (w-gap)/2
        divider = y+d*.52
        for xx in (x+.35,x+side+gap):
            self.box('plaster' if kind=='house' else 'timber',xx,divider,0,side-.35,.25,h)
        self.box('timber',x+side,divider,3.15,gap,.25,h-3.15)
        # A low kitchen counter or workbench, and an accessible pickup near its edge.
        self.box('timber',x+.50,y+.85,0,.80,2.0,.72)
        self.box('trim',x+.44,y+.80,.72,.90,2.1,.09)
        if kind=='house':
            self.box('timber',x+.60,y+d-2.75,0,1.30,2.1,.38)
            self.box('slate',x+.60,y+d-2.75,.38,1.30,2.1,.17)
            self.box('trim',x+.65,y+d-1.13,.55,1.20,.40,.12,False)
        elif kind=='barn':
            for i in range(3):
                self.box('field',x+.65+i*1.32,y+d-2.35,0,1.21,1.7,.85)
            self.box('field',x+.85,y+d-2.22,.85,1.25,1.45,.70)
        else:
            self.box('slate',x+.55,y+d-1.88,0,1.4,1.20,.95)
            self.box('bark',x+.80,y+d-1.65,.95,.38,.74,.45)
        self.box('timber',x+w-1.15,y+d-1.85,0,.68,1.25,.60)
        points=[(x+1.28,y+1.72,.84),(x+w-1.55,y+1.55,.08),
                (x+(5.4 if kind=="barn" else 2.35),y+d-1.5,.08),(x+w-1.08,y+d-1.20,.64)]
        for i,pos in enumerate(points):
            self.loot.append((f'br_in_{number:02d}_{i}',pos))

    def fence(self, x, y, spans):
        for i in range(spans+1):
            self.box('timber',x+i*3,y,0,.18,.18,1.20)
        for z in (.40,.92):
            self.box('timber',x,y,z,spans*3,.15,.15)

    def water_tower(self,x,y):
        # Four exposed legs keep the approach underneath open.
        for dx in (-2,2):
            for dy in (-2,2):
                self.box('timber',x+dx-.18,y+dy-.18,0,.36,.36,8.3)
        self.box('slate',x-2.7,y-2.7,8.2,5.4,5.4,.3)
        n=12
        rim=[(x+2.6*math.cos(i*2*math.pi/n),y+2.6*math.sin(i*2*math.pi/n),8.5) for i in range(n)]
        top=[(a,b,12.2) for a,b,_ in rim]
        for i in range(n):
            j=(i+1)%n
            self.face('slate',rim[i],rim[j],top[j],top[i])
            self.face('roof',top[i],top[j],(x,y,13.2))

    def tree(self,x,y,pine,rng):
        h=rng.uniform(8,11) if pine else rng.uniform(5,7)
        self.box('bark',x-.26,y-.26,0,.52,.52,h*.76)
        n=7
        for k in range(2):
            radius=(2.45 if pine else 2.65)*(1-.25*k)*rng.uniform(.9,1.1)
            mid=h*(.43+.26*k)
            ring=[(x+radius*math.cos(i*2*math.pi/n+.31*k),
                   y+radius*math.sin(i*2*math.pi/n+.31*k),mid+rng.uniform(-.28,.28)) for i in range(n)]
            for i in range(n):
                j=(i+1)%n
                self.face(('needle' if k else 'pine') if pine else ('crown' if k else 'leaf'),
                          ring[i],ring[j],(x+.18,y,h*(.77+.23*k)))
                self.face('pine' if pine else 'leaf',ring[j],ring[i],(x,y,mid-h*.16))


def build():
    s=Sector()
    s.slab('meadow',-4000,-4000,8000,8000,0)
    s.slab('verge',-300,-6.0,600,12.0,.018)
    s.slab('road',-300,-4.4,600,8.8,.035)
    s.slab('lane',-5,-105,7,101,.027)
    s.slab('lane',-5,4,7,108,.027)
    s.slab('field',12,16,83,64,.02)
    for i in range(13):
        s.slab('furrow',14+i*6.1,16,.65,64,.028)
    for x,y,w,d,h,wall,roof,barn in [
        (-43,13,10,8,3.5,'plaster','roof',False),(-22,15,9,8,3.5,'brick','slate',False),
        (-36,-26,10,8,3.5,'plaster','roof',False),(11,-36,14,17,4.5,'timber','roof',True),
        (35,-21,7,6,3.2,'brick','slate',False)]:
        s.building(x,y,w,d,h,wall,roof,barn)
        kind = 'barn' if barn else 'utility' if w==7 else 'house'
        s.furnish(len(s.buildings)+1,x,y,w,d,h,kind)
        s.slab('lane',x+w*.5-1.5,y-3,3,3,.024)
    s.fence(-55,8,4)
    s.fence(-34,8,6)
    s.fence(28,-10,5)
    s.water_tower(36,-41)
    rng=random.Random(6217)
    for row in range(4):
        for col in range(6):
            s.tree(-68+col*8+rng.uniform(-.6,.6),33+row*8+rng.uniform(-.6,.6),False,rng)
    for x,y in [(-69,-31),(-61,-43),(-53,-36),(-65,-55),(-40,-47),(-22,-48),
                (49,-29),(58,-38),(55,-50),(43,-46),(74,8),(86,12),(101,23)]:
        s.tree(x,y,True,rng)
    # Known outdoor supply points have an item every round. Other outdoor
    # sites form a vetted pool; the round seed decides which are occupied.
    for name,x,y in [('tower',40,-40),('barnyard',10,-42),('field_gate',12,10)]:
        s.box('timber',x,y,0,1.3,.85,.65)
        s.loot.append(('br_fixed_'+name,(x+.22,y+.43,.69)))
    for i,(x,y) in enumerate([(-54,37),(-40,54),(-29,40),(-65,48),
            (17,24),(35,42),(64,58),(85,70),(-54,-34),(-57,-50),(51,-35),(44,-43),
            (-74,10),(70,-10),(-13,30),(26,-54)]):
        s.loot.append((f'br_wild_{i:02d}',(x,y,.08)))
    return s


def write_include(path):
    s=build()
    def point(p): return '{'+','.join(f'{v:.5f}' for v in p)+'}'
    with Path(path).open('w') as f:
        f.write('static const struct paint paints[] = {\n')
        for name,faces in s.faces.items():
            f.write('{"%s",0x%06x,%d},\n'%(name,COLORS[name],len(faces)))
        f.write('};\nstatic const float triangles[][3][3] = {\n')
        for faces in s.faces.values():
            for tri in faces: f.write('{'+','.join(map(point,tri))+'},\n')
        triangles=[tri for faces in s.faces.values() for tri in faces]
        # The renderer accepts 128 surface references per subcluster. Keep nearby
        # triangles together so a view of the orchard can skip the barn's groups.
        cells=defaultdict(list)
        for index,tri in enumerate(triangles):
            center=[sum(p[k] for p in tri)/3 for k in (0,1)]
            cells[tuple(math.floor(v/24) for v in center)].append(index)
        batches=[ids[i:i+128] for ids in cells.values() for i in range(0,len(ids),128)]
        f.write('};\nstatic const struct cell cells[] = {\n')
        for batch in batches:
            points=[p for idx in batch for p in triangles[idx]]
            bounds=[v for axis in range(3) for v in
                    (min(p[axis] for p in points), max(p[axis] for p in points))]
            f.write('{'+point(bounds)+','+str(len(batch))+'},\n')
        f.write('};\nstatic const long cell_faces[] = {\n')
        for batch in batches: f.write(','.join(map(str,batch))+',\n')
        f.write('};\nstatic const float solids[][6] = {\n')
        for box in s.solids: f.write(point(box)+',\n')
        f.write('};\nstatic const struct map_point landing_sites[] = {\n')
        for name,x,y in LANDINGS: f.write('{"br_land_'+name+'",'+point((x,y,0))+'},\n')
        f.write('};\nstatic const struct map_point loot_sites[] = {\n')
        for name,pos in s.loot: f.write('{"'+name+'",'+point(pos)+'},\n')
        f.write('};\n')
