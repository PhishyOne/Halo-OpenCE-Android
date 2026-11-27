"""East Millworks. A populated road from Orchard Crossing to East Farm, in metres."""
import math
import random

from island_ground import BED, TUNNEL_FLOOR, WATER

# Tag keys, XY position, facing in degrees. Yards and lay-bys, clear of the travel lanes.
VEHICLES = [('warthog',82,-14,0), ('warthog',470,-15,0), ('ghost',540,18,180),
            ('warthog',705,14,180), ('ghost',1284,432,24)]
# Open ground near the pump house, loading yard, shed and shelter entrance.
LANDINGS = [('mill_pump', 398, 74), ('mill_yard', 456, -32),
            ('mill_shed', 510, 16), ('shelter_steps', 598, 44)]
BUILDINGS = [(451,-61,30,23,5.6,'brick','slate','barn'), (501,24,23,16,4.8,'timber','roof','barn'),
             (464,32,9,7,3.5,'plaster','slate','house'), (492,-38,10,8,3.5,'brick','roof','house'),
             (548,-44,8,7,3.5,'plaster','roof','house'), (389,86,8,6,3.3,'brick','slate','utility')]


def ribbon(s, points, width, mat, z):
    # One joined strip, including the bends; a Warthog can follow its shoulders.
    normals=[]
    for (a,b),(c,d) in zip(points,points[1:]):
        length=math.hypot(c-a,d-b)
        normals.append(((b-d)/length,(c-a)/length))
    edges=[]
    for i,(x,y) in enumerate(points):
        n=normals[max(0,i-1)]
        m=normals[min(i,len(normals)-1)]
        scale=width*.5/(1+n[0]*m[0]+n[1]*m[1])
        dx,dy=(n[0]+m[0])*scale,(n[1]+m[1])*scale
        edges.append(((x+dx,y+dy,z),(x-dx,y-dy,z)))
    for (left,right),(next_left,next_right) in zip(edges,edges[1:]):
        s.face(mat,left,right,next_right,next_left)


def road(s,points):
    ribbon(s,points,12,'verge',.026)
    ribbon(s,points,9,'road',.042)
    for (x,y),(X,Y) in zip(points,points[1:]):
        length=math.hypot(X-x,Y-y)
        dx,dy=(X-x)/length,(Y-y)/length
        for n in range(4,int(length)-5,18):
            ribbon(s,[(x+dx*n,y+dy*n),(x+dx*(n+5),y+dy*(n+5))],.13,'stripe',.055)


def crossing(s):
    # Keep the bridge span out of the road mesh. Its load-bearing deck is solid.
    road(s,[(300,0),(349,0)])
    road(s,[(381,0),(710,0),(825,53),(1008,196),(1170,300),(1304,435),(1354,446)])
    s.box('concrete',348,-5.4,-.52,34,10.8,.57)
    s.slab('road',348,-4.5,34,9,.061)
    for y in (-5.4,5.1):
        s.box('concrete',348,y,.05,34,.3,.35)
        s.box('steel',348,y+.06,.94,34,.16,.13)
        for x in range(348,383,4):
            s.box('steel',x,y+.06,.4,.13,.16,.55,False)
    for x in (354,373):
        for y in (-4.4,3.35):
            s.box('concrete',x,y,BED,1.2,1.05,-.52-BED)
    # Two service stairways from the millrace bed to the bank, away from traffic.
    for i in range(15):
        top=-.24*(i+1)
        s.box('concrete',350+.82*i,34,BED,.82,4,top-BED+.015)
    for i in range(15):
        top=-.24*(i+1)
        s.box('concrete',380-.82*(i+1),102,BED,.82,4,top-BED+.015)


def shelter(s):
    # Earth-covered main gallery with two open stairs and a side storeroom.
    floor=TUNNEL_FLOOR
    for i in range(8):
        top=-(i+1)*(-floor/8)
        if top>floor:
            for x in (596+i*1.1,680-(i+1)*1.1):
                s.box('concrete',x,54,floor,1.1,8,top-floor)
    for x,y,w,d in [(605,54,66,8),(629,62,18,9)]:
        s.box('concrete',x,y,1.98,w,d,.36)
        s.box('bank',x,y,2.34,w,d,.92)
        s.slab('grass',x,y,w,d,3.27)
    # Backfill reaches the outside of the walls. Only the wells are open to daylight.
    for x,y,w,d in [(605,52,66,2),(605,62,24,2),(647,62,24,2),
                    (627,62,2,9),(647,62,2,9),(627,71,22,2)]:
        s.box('bank',x,y,0,w,d,3.26)
        s.slab('grass',x,y,w,d,3.27)
    for x,y,w,d in [(596,53.7,84,.3),(596,62,33,.3),(647,62,33,.3),
                    (628.7,62,.3,9),(647,62,.3,9),(629,71,18,.3)]:
        wall_height=2.0 if w<30 or x in (629,647) and d==9 else 1.98
        s.box('concrete',x,y,0,w,d,wall_height)
    # Passage into the store splits the gallery. A standing player can reach
    # the racks and supplies without stepping into the through lane.
    for x in (629,642):
        s.box('concrete',x,61.7,floor,5,.3,1.98-floor)
    for x in (631,636,642):
        s.box('steel',x,68.8,floor,2.6,.7,.86)
        s.box('timber',x+.16,68.85,floor+.86,.8,.54,.44,False)
    for x in (610,622,650,662):
        s.box('stripe',x,54.12,1.36,.8,.07,.17,False)
        s.box('steel',x,54.10,.52,1.5,.09,.08,False)
    for i,(x,y,z) in enumerate([(632,68.5,floor+.12),(639,65,floor+.1),
                                (644,68.4,floor+.1),(620,56.2,floor+.1)]):
        s.loot.append((f'br_in_{len(s.buildings)+1:02d}_{i}',(x,y,z)))


def barrel(s,x,y,z):
    sides=8; radius=.32
    ring=[(x+radius*math.cos(i*2*math.pi/sides),y+radius*math.sin(i*2*math.pi/sides)) for i in range(sides)]
    for low,high,mat in [(0,.29,'drum'),(.29,.34,'steel'),(.34,.72,'drum'),(.72,.77,'steel'),(.77,.87,'drum')]:
        for i,(a,b) in enumerate(ring):
            c,d=ring[(i+1)%sides]
            s.face(mat,(a,b,z+low),(c,d,z+low),(c,d,z+high),(a,b,z+high))
    s.face('steel',*((a,b,z+.87) for a,b in ring))


def populate(s):
    rng=random.Random(41983)
    crossing(s)
    ribbon(s,[(438,-4),(457,-13),(520,-13),(552,-22)],6,'lane',.027)
    ribbon(s,[(457,4),(465,15),(531,16),(557,9)],5.5,'lane',.027)
    ribbon(s,[(391,4),(394,34),(392,80)],3.4,'lane',.027)
    ribbon(s,[(561,4),(570,25),(587,57),(596,58)],3.4,'lane',.027)
    ribbon(s,[(680,58),(697,43),(705,17),(707,4)],3.4,'lane',.027)
    for x,y,w,d,h,wall,roof,kind in BUILDINGS:
        s.building(x,y,w,d,h,wall,roof,kind=='barn')
        s.furnish(len(s.buildings)+1,x,y,w,d,h,kind)
        s.slab('lane',x+w/2-1.75,y-4,3.5,4,.028)
        if w>18:
            for a in (x+4,x+w-8):
                for b in (y+2.5,y+d-5):
                    s.box('steel',a,b,0,2.8,.8,.85)
                    s.box('timber',a+.2,b+.08,.85,.72,.57,.51,False)
            for i in range(5):
                s.box('trim',x+2+5*i,y+.03,h-.4,2.1,.07,.13,False)
    shelter(s)
    # Exterior piles cluster around loading bays and sheds, leaving room to turn.
    for cx,cy in [(445,-32),(487,-51),(519,-24),(528,33),(552,-52),(408,90),(611,47),(663,77)]:
        for i in range(8):
            x=cx+(i%4)*1.05+rng.uniform(-.12,.12)
            y=cy+(i//4)*1.1+rng.uniform(-.12,.12)
            if rng.randrange(3)==0:
                barrel(s,x,y,0)
            else:
                h=rng.choice([.32,.52,.7])
                s.box('timber',x-.39,y-.31,0,.78,.62,h,False)
                s.box('trim',x-.39,y-.25,h,.78,.09,.026,False)
                if rng.randrange(4)==0:
                    s.box('plank',x-.26,y-.24,h,.49,.44,.28,False)
        # A low, continuous collision footprint for the pile needs only one instance.
        s.solids.append((cx-.48,cy-.46,0,4.1,2.0,.48))
    for i,(x,y) in enumerate([(445,-30),(488,-47),(518,-26),(530,32),(552,-49),(408,88),
                               (611,45),(665,75),(343,42),(388,112),(699,41),(688,-18)]):
        s.loot.append((f'br_wild_mill_{i:02d}',(x,y,.1)))
    for name,x,y in [('loading_bay',486,-36),('bridge_store',397,79)]:
        s.box('timber',x,y,0,1.3,.85,.65)
        s.loot.append(('br_fixed_'+name,(x+.22,y+.43,.69)))
    # Open fields between woodlots. A private map seed fixes trees for every peer.
    for cx,cy,rx,ry,count in [(218,61,68,29,24),(282,-65,46,22,18),(245,200,44,34,16),
                             (439,136,27,37,18),(585,-107,64,30,28),(730,125,42,35,19),
                             (952,54,73,28,24),(1030,320,90,38,28),(1220,178,46,23,16)]:
        planted=[]
        while len(planted)<count:
            x,y=cx+rng.uniform(-rx,rx),cy+rng.uniform(-ry,ry)
            if ((x-cx)/rx)**2+((y-cy)/ry)**2>1: continue
            if any((a-x)**2+(b-y)**2<4.8**2 for a,b in planted): continue
            planted.append((x,y))
            s.tree(x,y,rng.random()<.58,rng)
    # Static reeds and light streaks sit on the water, with daylight fixed.
    for _ in range(38):
        x,y=rng.uniform(331,401),rng.uniform(153,228)
        s.slab('ripple',x,y,rng.uniform(.8,2.9),.12,WATER+.014)
    for y in range(-78,131,7):
        x=350.8+rng.uniform(0,1.6)
        s.face('needle',(x,y,WATER),(x+.29,y,WATER),(x+.36,y+.05,WATER+.71))
