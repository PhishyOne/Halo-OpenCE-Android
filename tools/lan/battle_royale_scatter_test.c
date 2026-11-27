#include "battle_royale_scatter.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
	struct br_drop_route route = {
		{-3900/3.048f, -1400/3.048f, 240/3.048f}, {3900/3.048f, 1400/3.048f, 240/3.048f},
		{-4000/3.048f,-4000/3.048f,0},{4000/3.048f,4000/3.048f,100},0.9412f,0.3379f
	};
	struct br_drop_pool map = {0}, pool, repeat;
	struct br_drop_plan p, replay;
	int contested = 0, distinct = 0, village = 0, first_site[BR_MAX_CLEARINGS] = {0};
	int min_tick=BR_LAST_JUMP, max_tick=0, seed, slot, i;
	while (map.site_count < BR_MAX_CLEARINGS) {
		struct battle_royale_point *s = &map.sites[map.site_count];
		if (scanf("%f %f %f",&s->x,&s->y,&s->z)!=3) break;
		++map.site_count;
	}
	assert(map.site_count == 16);
	for (seed=0; seed<512; ++seed) {
		int tally[BR_MAX_CLEARINGS] = {0}, shared = 0;
		unsigned seen[BR_MAX_CLEARINGS] = {0};
		pool = map;
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
	assert(contested>0 && distinct>0 && village>1500 && village<2500);
	assert(min_tick<400 && max_tick>1600);
	printf("512 seeds, 127 bot plans each. Eight-bot openings: %d shared a clearing, %d all distinct; %d/4096 village drops. Ticks %d..%d.\n",
		contested,distinct,village,min_tick,max_tick);
	/* A malformed or unreachable marker must never steal another seat's spot. */
	pool=map; pool.site_count=3;
	pool.sites[0].x=20000.f; pool.sites[1].z=95.f;
	br_drop_shuffle_start(&pool,17);
	for (i=0; i<16; ++i) { assert(br_drop_choose(&pool,&route,i,&p)); assert(p.clearing==2); }
	assert(!br_drop_choose(&pool,&route,17,&p) && pool.remaining==32);
	return 0;
}
