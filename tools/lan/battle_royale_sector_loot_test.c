/* Read the markers extracted from the actual compiled map, then run host rounds. */
#include "battle_royale_round.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(void)
{
	struct battle_royale_loot_marker markers[BR_MAX_LOOT];
	struct battle_royale_round round;
	const struct battle_royale_zone zone = {{0,0,100},{0,0,10},15,90,10};
	const uint32_t weapons[] = {0xe0010001,0xe0020002}; /* fixture weapon handles */
	uint32_t ids[HALO_PORT_MAXIMUM_NETWORK_PLAYERS] = {11,22};
	unsigned wild_seen[BR_MAX_LOOT] = {0};
	int n=0, seed, i, j;
	while (n<BR_MAX_LOOT && scanf("%31s %f %f %f",markers[n].name,&markers[n].point.x,
		&markers[n].point.y,&markers[n].point.z)==4) ++n;
	assert(n==54);
	for (seed=0; seed<256; ++seed) {
		int rooms[8]={0}, fixed=0, wild=0;
		assert(battle_royale_begin(&round,&zone,ids));
		assert(battle_royale_place_marked_loot(&round,markers,n,weapons,2,3,4,seed));
		assert(round.loot_count==34);
		for (i=0; i<round.loot_count; ++i) {
			for (j=0;j<n;++j) if (memcmp(&markers[j].point,&round.loot[i].position,sizeof(markers[j].point))==0) break;
			assert(j<n);
			if (!strncmp(markers[j].name,"br_in_",6)) {
				int b=markers[j].name[7]-'1'; assert(b>=0 && b<8); ++rooms[b];
			} else if (!strncmp(markers[j].name,"br_fixed_",9)) ++fixed;
			else { ++wild; ++wild_seen[j]; }
		}
		for (i=0;i<8;++i) assert(rooms[i]==3);
		assert(fixed==6 && wild==4);
		/* A real claim through the normal host inventory API removes the pickup. */
		assert(battle_royale_take_loot(&round,0,11,round.loot[0].position,0)!=0);
	}
	for (i=0;i<n;++i) if (!strncmp(markers[i].name,"br_wild_",8))
		assert(wild_seen[i]>0 && wild_seen[i]<256);
	return 0;
}
