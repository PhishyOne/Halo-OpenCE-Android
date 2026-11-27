#include "battle_royale_scatter.h"
#include <assert.h>
#include <stdio.h>
#include <math.h>
#include <string.h>

int main(void)
{
	struct br_drop_route route = {
		{-3900/3.048f, -1400/3.048f, 240/3.048f}, {3900/3.048f, 1400/3.048f, 240/3.048f},
		{-4000/3.048f,-4000/3.048f,0},{4000/3.048f,4000/3.048f,100},0.9412f,0.3379f
	};
	struct br_drop_route authored=route, same, routes[512];
	struct br_drop_pool map = {0}, pool, repeat;
	struct br_drop_plan p, replay;
	int contested = 0, distinct = 0, village = 0, first_site[BR_MAX_CLEARINGS] = {0};
	int entries[4]={0}, exits[4]={0}, bypass=0, different=0, k;
	float shortest=1.e9f, longest=0.f;
	int min_tick=BR_LAST_JUMP, max_tick=0, seed, slot, i;
	while (map.site_count < BR_MAX_CLEARINGS) {
		struct battle_royale_point *s = &map.sites[map.site_count];
		if (scanf("%f %f %f",&s->x,&s->y,&s->z)!=3) break;
		++map.site_count;
	}
	assert(map.site_count == 32);
	for (seed=0; seed<512; ++seed) {
		int tally[BR_MAX_CLEARINGS] = {0}, shared = 0;
		unsigned seen[BR_MAX_CLEARINGS] = {0};
		float dx,dy,length,across;
		int new_line=1;
		pool = map;
		route=authored; same=authored;
		assert(br_choose_route(&route,&map,seed));
		assert(br_choose_route(&same,&map,seed));
		assert(!memcmp(&same,&route,sizeof(route)));
		dx=route.end.x-route.start.x; dy=route.end.y-route.start.y;
		length=(float)sqrt(dx*dx+dy*dy);
		assert(length*3.048f>=7199.9f);
		assert(length*(BR_LAST_JUMP-BR_DROP_OPEN)/BR_ROUTE_TICKS*3.048f>6200.f);
		if (length<shortest) shortest=length;
		if (length>longest) longest=length;
		assert(fabs(route.forward_x-dx/length)<.0001f && fabs(route.forward_y-dy/length)<.0001f);
		assert(route.start.x>route.lower.x && route.start.x<route.upper.x &&
		       route.end.x>route.lower.x && route.end.x<route.upper.x &&
		       route.start.y>route.lower.y && route.start.y<route.upper.y &&
		       route.end.y>route.lower.y && route.end.y<route.upper.y);
		assert(br_route_edge(&route,route.start)!=br_route_edge(&route,route.end));
		++entries[br_route_edge(&route,route.start)]; ++exits[br_route_edge(&route,route.end)];
		across=route.start.y*route.forward_x-route.start.x*route.forward_y;
		if (fabs(across)*3.048f>500.f) ++bypass;
		for (k=0; k<seed; ++k)
			if (fabs(route.start.x-routes[k].start.x)<10.f && fabs(route.start.y-routes[k].start.y)<10.f &&
			    fabs(route.end.x-routes[k].end.x)<10.f && fabs(route.end.y-routes[k].end.y)<10.f)
				new_line=0;
		different+=new_line;
		routes[seed]=route;
		br_drop_shuffle_start(&pool,seed);
		repeat = pool;
		for (slot=1; slot<=127; ++slot) {
			assert(br_drop_choose(&pool,&route,slot,&p));
			assert(br_drop_choose(&repeat,&route,slot,&replay));
			assert(p.tick==replay.tick && p.spot==replay.spot && p.clearing==replay.clearing &&
				!memcmp(&p.target,&replay.target,sizeof(p.target)));
			assert(!(seen[p.clearing] & (1u<<p.spot)));
			seen[p.clearing] |= 1u<<p.spot;
			assert(p.tick>=BR_DROP_OPEN && p.tick<BR_LAST_JUMP);
			if (p.tick<min_tick) min_tick=p.tick;
			if (p.tick>max_tick) max_tick=p.tick;
			if (slot==1) ++first_site[p.clearing];
			if (slot<=8) {
				if (++tally[p.clearing]>1) shared=1;
				if (p.clearing<8) ++village;
			}
		}
		contested += shared;
		if (!shared) ++distinct;
	}
	for (i=0; i<map.site_count; ++i) assert(first_site[i]>0);
	assert(contested>0 && distinct>0 && village>0 && village<4096);
	for (i=0; i<4; ++i) assert(entries[i]>10 && exits[i]>10);
	assert(bypass>50 && bypass<462 && different>128);
	printf("512 route seeds: %.2f..%.2f km, %d materially different directed crossings, %d pass over 500 m from Orchard.\n",
		shortest*.003048f,longest*.003048f,different,bypass);
	assert(min_tick<400 && max_tick>1600);
	printf("512 seeds, 127 bot plans each. Eight-bot openings: %d shared a clearing, %d all distinct; %d/4096 village drops. Ticks %d..%d.\n",
		contested,distinct,village,min_tick,max_tick);
	/* A malformed or unreachable marker must never steal another seat's spot. */
	route=authored;
	pool=map; pool.site_count=3;
	pool.sites[0].x=20000.f; pool.sites[1].z=95.f;
	br_drop_shuffle_start(&pool,17);
	for (i=0; i<16; ++i) { assert(br_drop_choose(&pool,&route,i,&p)); assert(p.clearing==2); }
	assert(!br_drop_choose(&pool,&route,17,&p) && pool.remaining==32);
	/* No-landings maps still get a long flight. Invalid or impossible fixtures
	   leave the input route intact so an existing authored map can still run. */
	pool.site_count=0; assert(br_choose_route(&route,&pool,419));
	route=authored; same=route;
	pool=map; pool.site_count=1; pool.sites[0].z=route.upper.z+1.f;
	assert(!br_choose_route(&route,&pool,27) && !memcmp(&same,&route,sizeof(route)));
	return 0;
}
