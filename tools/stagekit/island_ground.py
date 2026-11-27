"""Walkable island relief in metres. Rendering and collision share each ground plane."""
import math
from bisect import bisect_right
import struct

HALF = 4000
CEILING = 304.8
UNIT = 3.048
WATER = -1.85
BED = -3.6
TUNNEL_FLOOR = -1.45
# (west, south, east, north), floor elevation, material. The pond feeds the millrace.
CUTS = [
    ((350, -95, 380, 150), BED, 'silt'),
    ((328, 150, 406, 232), BED, 'silt'),
    ((596, 54, 680, 62), TUNNEL_FLOOR, 'concrete'),
    ((629, 62, 647, 71), TUNNEL_FLOOR, 'concrete'),
]


# Surveyed villages and the mill road stay level. Broad slopes between them
# meet at shared vertices, so there is no step at a cell boundary.
RIDGES_X = [(-4000, 24), (-3360, 82), (-2670, 0), (-2530, 0), (-2150, 59),
            (-1470, 0), (-1330, 0), (-1030, 45), (-825, 0), (-690, 0),
            (-550, 22), (-320, 0), (1420, 0), (1570, 25), (1740, 0),
            (1860, 0), (2310, 81), (2780, 0), (2920, 0), (3450, 74), (4000, 20)]
RIDGES_Y = [(-4000, 25), (-3150, 69), (-2030, 0), (-1880, 0), (-1530, 58),
            (-985, 0), (-860, 0), (-695, 24), (-540, 0), (-405, 0), (-272, 19),
            (-150, 0), (525, 0), (744, 36), (936, 0), (1065, 0), (1670, 83),
            (2380, 0), (2520, 0), (3210, 96), (4000, 21)]


def ridge(knots, position):
    index = max(0, min(len(knots)-2, bisect_right(knots, (position, math.inf))-1))
    (start, a), (end, b) = knots[index:index+2]
    return a + (b-a) * (position-start) / (end-start)


def elevation(x, y):
    return ridge(RIDGES_X, x) + ridge(RIDGES_Y, y)


def signed_index(index):
    return index - (1 << 31)


class Ground:
    def __init__(self):
        self.xs = sorted({k for k, _ in RIDGES_X} | {r[i] for r, _, _ in CUTS for i in (0, 2)})
        self.ys = sorted({k for k, _ in RIDGES_Y} | {r[i] for r, _, _ in CUTS for i in (1, 3)})
        self.cells = {}
        for i,(x0,x1) in enumerate(zip(self.xs, self.xs[1:])):
            for j,(y0,y1) in enumerate(zip(self.ys, self.ys[1:])):
                self.cells[i,j] = next(((z,m) for (a,b,c,d),z,m in CUTS
                                        if a<=x0<x1<=c and b<=y0<y1<=d), (0,'meadow'))

    def faces(self, i, j):
        x,X = self.xs[i:i+2]
        y,Y = self.ys[j:j+2]
        z, mat = self.cells[i,j]
        floor = [(a,b,z + elevation(a,b)) for a,b in ((x,y),(X,y),(X,Y),(x,Y))]
        yield 2,z,1,mat,floor
        t = CEILING
        yield 2,t,-1,None,[(x,Y,t),(X,Y,t),(X,y,t),(x,y,t)]
        for di,dj,axis,k,sign in [(-1,0,0,x,1),(1,0,0,X,-1),(0,-1,1,y,1),(0,1,1,Y,-1)]:
            neighbor = self.cells.get((i+di,j+dj))
            top = neighbor[0] if neighbor else CEILING
            if top <= z:
                continue
            ring = ([(k,y,z),(k,Y,z),(k,Y,top),(k,y,top)] if axis==0 else
                    [(x,k,z),(x,k,top),(X,k,top),(X,k,z)])
            if not neighbor:
                ring = [(a,b,c if c == CEILING else c + elevation(a,b)) for a,b,c in ring]
            yield axis,k,sign,('concrete' if mat=='concrete' else 'bank') if neighbor else None,ring[::sign]

    def draw(self, scene):
        for i,j in self.cells:
            for _,_,_,mat,ring in self.faces(i,j):
                if mat:
                    scene.face(mat, *ring)
            if self.cells[i,j][0] == BED:
                x,X=self.xs[i:i+2]; y,Y=self.ys[j:j+2]
                scene.slab('water',x,y,X-x,Y-y,WATER)

    def collision(self):
        planes, nodes, leaves, refs, surfaces, edges, vertices = [], [], [], [], [], [], []
        plane_ids, vertex_ids, edge_ids = {}, {}, {}
        leaf_ids = {}
        def plane(axis,k):
            if (axis,k) not in plane_ids:
                plane_ids[axis,k] = len(planes)
                planes.append((*[float(i==axis) for i in range(3)],k/UNIT))
            return plane_ids[axis,k]
        floor_designators = {}
        def floor_plane(ring):
            (x,y,z),(X,_,east),(_,Y,north),_ = ring
            dx,dy = (east-z)/(X-x), (north-east)/(Y-y)
            if dx == 0 and dy == 0:
                return plane(2,z)
            length = math.sqrt(dx*dx + dy*dy + 1)
            designator = len(planes)
            planes.append((-dx/length,-dy/length,1/length,(z-dx*x-dy*y)/(UNIT*length)))
            return designator
        for (i,j) in self.cells:
            leaf_ids[i,j] = signed_index(len(leaves))
            first = len(refs)
            for axis,k,sign,mat,ring in self.faces(i,j):
                if axis == 2 and sign > 0:
                    designator = floor_designators[i,j] = floor_plane(ring)
                else:
                    designator = plane(axis,k)
                if sign<0: designator=signed_index(designator)
                surface = len(surfaces)
                refs.append((designator,signed_index(surface)))
                ids = []
                for point in ring:
                    if point not in vertex_ids:
                        vertex_ids[point]=len(vertices)
                        vertices.append([*(v/UNIT for v in point),-1])
                    ids.append(vertex_ids[point])
                sides = []
                for a,b in zip(ids,ids[1:]+ids[:1]):
                    key = (min(a,b),max(a,b))
                    if key in edge_ids:
                        eid=edge_ids[key]; e=edges[eid]
                        assert (e[0],e[1],e[5])==(b,a,-1)
                        e[5]=surface
                        sides.append((eid,1))
                    else:
                        eid=len(edges); edge_ids[key]=eid
                        edges.append([a,b,-1,-1,surface,-1])
                        sides.append((eid,0))
                    if vertices[a][3]<0: vertices[a][3]=eid
                for n,(eid,side) in enumerate(sides):
                    edges[eid][2+side]=sides[(n+1)%4][0]
                surfaces.append((designator,sides[0][0],0,255,1 if mat == "concrete" else 0))
            leaves.append((0,len(refs)-first,first))

        # The engine starts at node zero. Outside the four shores or ceiling is
        # solid. Inside, a balanced XY tree selects the cell and its real floor.
        boundary=[(0,-HALF,1),(0,HALF,0),(1,-HALF,1),(1,HALF,0),(2,CEILING,0)]
        for axis,k,inside in boundary:
            n=len(nodes); children=[-1,-1]; children[inside]=n+1
            nodes.append((plane(axis,k),*children))
        def split(i,I,j,J):
            n=len(nodes); nodes.append(None)
            if I-i>1:
                mid=(i+I)//2
                nodes[n]=(plane(0,self.xs[mid]),split(i,mid,j,J),split(mid,I,j,J))
            elif J-j>1:
                mid=(j+J)//2
                nodes[n]=(plane(1,self.ys[mid]),split(i,I,j,mid),split(i,I,mid,J))
            else:
                nodes[n]=(floor_designators[i,j],-1,leaf_ids[i,j])
            return n
        split(0,len(self.xs)-1,0,len(self.ys)-1)
        assert all(e[5]>=0 for e in edges), 'ground boundary has an unpaired face'
        return dict(planes=('4f',planes),nodes=('3i',nodes),leaves=('Hhi',leaves),
                    refs=('2i',refs),surfaces=('iiBBh',surfaces),edges=('6i',edges),vertices=('3fi',vertices))

    def write_include(self, f):
        # StageKit builds 32-bit little-endian CE cache structures. Packing them
        # explicitly avoids host pointer sizes or Python floating-point layout.
        f.write(f"#define ORCHARD_WATER_LEVEL ({WATER}f/3.048f)\n#define ORCHARD_BED_LEVEL ({BED}f/3.048f)\n")
        for name,(fmt,rows) in self.collision().items():
            pack=struct.Struct('<'+fmt).pack
            f.write(f'static const unsigned char ground_{name}[{len(rows)}][{struct.calcsize("<"+fmt)}] = {{\n')
            for row in rows:
                f.write('{'+','.join(map(str,pack(*row)))+'},\n')
            f.write('};\n')
