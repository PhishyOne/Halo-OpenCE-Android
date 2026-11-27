/* Only the host draws lots. Every authored clearing contributes sixteen spots.
   A village with several clearings attracts more traffic than an isolated farm. */
#include "battle_royale_scatter.h"

float br_drop_aft(int slot) { return 5.f + (slot / 16) * .62f; }
float br_drop_side(int slot) { return ((slot % 16) - 7.5f) * .62f; }

int br_pass_tick(const struct br_drop_route *route, struct battle_royale_point point, float aft)
{
	float length = (route->end.x-route->start.x)*route->forward_x +
		(route->end.y-route->start.y)*route->forward_y;
	float along = (point.x-route->start.x)*route->forward_x + (point.y-route->start.y)*route->forward_y;
	float tick;
	if (!(length > 0.f)) return 0;
	tick = (along + aft) * BR_ROUTE_TICKS / length;
	return tick >= BR_DROP_OPEN && tick < BR_LAST_JUMP ? (int)(tick + .5f) : 0;
}

void br_drop_shuffle_start(struct br_drop_pool *pool, uint32_t seed)
{
	int i;
	pool->remaining = pool->site_count * BR_CLEARING_SPOTS;
	for (i=0; i<pool->remaining; ++i)
		pool->available[i] = (unsigned short)i;
	pool->random = seed ? seed : 0x9e3779b9u;
}

static int reachable(const struct br_drop_route *route, struct battle_royale_point target,
	int slot, int tick)
{
	float t = tick/(float)BR_ROUTE_TICKS;
	float aft = br_drop_aft(slot), side = br_drop_side(slot);
	float x = route->start.x + (route->end.x-route->start.x)*t - aft*route->forward_x - side*route->forward_y;
	float y = route->start.y + (route->end.y-route->start.y)*t - aft*route->forward_y + side*route->forward_x;
	float z = route->start.z + (route->end.z-route->start.z)*t - 1.5f;
	float dx = target.x-x, dy = target.y-y;
	float reach = (z-target.z)*(.22f/.52f) - 3.f;
	return tick >= BR_DROP_OPEN && tick < BR_LAST_JUMP && reach > 0 && dx*dx + dy*dy <= reach*reach;
}

static float jitter(uint32_t *random)
{
	/* Smaller than the safety margin between adjacent standing capsules. */
	return ((int)(battle_royale_random(random)%129u)-64) * (.06f/64.f);
}

int br_drop_choose(struct br_drop_pool *pool, const struct br_drop_route *route, int slot, struct br_drop_plan *plan)
{
	int i;
	/* Sample without replacement within this search. Unreachable candidates
	   stay in the pool for a different seat, whose ramp offset may differ. */
	for (i=pool->remaining-1; i>=0; --i) {
		int j = battle_royale_random(&pool->random) % (uint32_t)(i+1);
		int id = pool->available[j], spot = id % BR_CLEARING_SPOTS;
		struct battle_royale_point target = pool->sites[id / BR_CLEARING_SPOTS];
		int tick, varied;
		pool->available[j] = pool->available[i];
		pool->available[i] = (unsigned short)id;
		target.x += ((spot%4)-1.5f)*.75f + jitter(&pool->random);
		target.y += ((spot/4)-1.5f)*.75f + jitter(&pool->random);
		if (!(target.x >= route->lower.x && target.x <= route->upper.x &&
		      target.y >= route->lower.y && target.y <= route->upper.y &&
		      target.z >= route->lower.z && target.z <= route->upper.z))
			continue;
		tick = br_pass_tick(route, target, br_drop_aft(slot));
		if (!tick || !reachable(route, target, slot, tick)) continue;
		varied = tick + (int)(battle_royale_random(&pool->random)%31u) - 15;
		plan->tick = reachable(route, target, slot, varied) ? varied : tick;
		plan->target = target;
		plan->clearing = id / BR_CLEARING_SPOTS;
		plan->spot = spot;
		pool->available[i] = pool->available[--pool->remaining];
		return 1;
	}
	return 0;
}
