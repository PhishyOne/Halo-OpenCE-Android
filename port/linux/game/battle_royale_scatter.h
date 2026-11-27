#ifndef BATTLE_ROYALE_SCATTER_H
#define BATTLE_ROYALE_SCATTER_H

#include "battle_royale_round.h"

enum { BR_DROP_OPEN = 90, BR_LAST_JUMP = 1800, BR_ROUTE_TICKS = 1980,
	BR_MAX_CLEARINGS = 32, BR_CLEARING_SPOTS = 16 };

struct br_drop_route {
	struct battle_royale_point start, end, lower, upper;
	float forward_x, forward_y; /* horizontal unit vector */
};

struct br_drop_plan {
	struct battle_royale_point target;
	int tick, clearing, spot;
};

struct br_drop_pool {
	struct battle_royale_point sites[BR_MAX_CLEARINGS];
	int site_count, remaining;
	unsigned short available[BR_MAX_CLEARINGS * BR_CLEARING_SPOTS];
	uint32_t random;
};

float br_drop_aft(int slot);
float br_drop_side(int slot);
int br_pass_tick(const struct br_drop_route *route, struct battle_royale_point point, float aft);
void br_drop_shuffle_start(struct br_drop_pool *pool, uint32_t seed);
int br_drop_choose(struct br_drop_pool *pool, const struct br_drop_route *route, int slot, struct br_drop_plan *plan);

#endif
