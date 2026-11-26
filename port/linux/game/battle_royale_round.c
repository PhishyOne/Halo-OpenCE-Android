#include "battle_royale_round.h"
#include <float.h>
#include <string.h>

static int finite_coordinate(float value)
{
	return value >= -FLT_MAX && value <= FLT_MAX;
}

static int zone_valid(const struct battle_royale_zone *zone)
{
	double dx, dy, margin;
	if (!finite_coordinate(zone->start.x) || !finite_coordinate(zone->start.y) ||
		!finite_coordinate(zone->end.x) || !finite_coordinate(zone->end.y) ||
		!finite_coordinate(zone->start.radius) || !finite_coordinate(zone->end.radius) ||
		zone->start.radius <= 0 || zone->end.radius < 0 || zone->end.radius > zone->start.radius ||
		zone->hold_ticks < 0 || zone->close_ticks <= 0 || zone->damage_interval_ticks <= 0)
		return 0;
	dx = (double)zone->end.x - zone->start.x;
	dy = (double)zone->end.y - zone->start.y;
	margin = (double)zone->start.radius - zone->end.radius;
	return dx * dx + dy * dy <= margin * margin;
}

int battle_royale_begin(struct battle_royale_round *round, const struct battle_royale_zone *zone,
	const uint32_t identities[HALO_PORT_MAXIMUM_NETWORK_PLAYERS])
{
	struct battle_royale_round next = {0};
	int i, j;
	if (!zone_valid(zone))
		return 0;
	for (i = 0; i < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; ++i)
	{
		if (!identities[i])
			continue;
		for (j = 0; j < i; ++j)
			if (identities[j] == identities[i])
				return 0;
		next.surviving[i] = identities[i];
		++next.remaining;
	}
	if (next.remaining < 2)
		return 0;
	next.zone = *zone;
	next.circle = zone->start;
	next.phase = BR_PLAYING;
	next.winner_seat = -1;
	next.last_tick = -1;
	*round = next;
	return 1;
}

int battle_royale_alive(const struct battle_royale_round *round, int seat, uint32_t identity)
{
	return seat >= 0 && seat < HALO_PORT_MAXIMUM_NETWORK_PLAYERS && identity && round->surviving[seat] == identity;
}

static struct battle_royale_circle circle_at(const struct battle_royale_zone *zone, int tick)
{
	struct battle_royale_circle circle;
	double t;
	if (tick <= zone->hold_ticks)
		return zone->start;
	tick -= zone->hold_ticks;
	if (tick >= zone->close_ticks)
		return zone->end;
	t = (double)tick / zone->close_ticks;
	circle.x = (float)(zone->start.x + ((double)zone->end.x - zone->start.x) * t);
	circle.y = (float)(zone->start.y + ((double)zone->end.y - zone->start.y) * t);
	circle.radius = (float)(zone->start.radius + ((double)zone->end.radius - zone->start.radius) * t);
	return circle;
}

static int outside(const struct battle_royale_circle *circle, float x, float y)
{
	double dx = (double)x - circle->x;
	double dy = (double)y - circle->y;
	return dx * dx + dy * dy > (double)circle->radius * circle->radius;
}

int battle_royale_open_tick(struct battle_royale_round *round, int tick,
	const struct battle_royale_observation seats[HALO_PORT_MAXIMUM_NETWORK_PLAYERS],
	unsigned char damage_due[HALO_PORT_MAXIMUM_NETWORK_PLAYERS])
{
	int i;
	memset(damage_due, 0, HALO_PORT_MAXIMUM_NETWORK_PLAYERS * sizeof(*damage_due));
	if (round->phase != BR_PLAYING || round->tick_open || tick <= round->last_tick)
		return 0;
	round->tick_open = 1;
	round->last_tick = tick;
	round->circle = circle_at(&round->zone, tick);
	if (tick - round->last_damage_tick >= round->zone.damage_interval_ticks)
	{
		round->last_damage_tick = tick;
		for (i = 0; i < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; ++i)
			if (seats[i].kind == BR_POSITION && battle_royale_alive(round, i, seats[i].identity) &&
				finite_coordinate(seats[i].x) && finite_coordinate(seats[i].y))
				damage_due[i] = outside(&round->circle, seats[i].x, seats[i].y);
	}
	return 1;
}

int battle_royale_finish_tick(struct battle_royale_round *round, int tick,
	const struct battle_royale_observation seats[HALO_PORT_MAXIMUM_NETWORK_PLAYERS])
{
	int i, last_seat = -1;
	if (!round->tick_open || tick != round->last_tick)
		return 0;
	round->tick_open = 0;
	for (i = 0; i < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; ++i)
	{
		if (!round->surviving[i])
			continue;
		if (seats[i].kind == BR_OUT && seats[i].identity == round->surviving[i])
		{
			round->surviving[i] = 0;
			--round->remaining;
		}
		else
			last_seat = i;
	}
	if (round->remaining <= 1)
	{
		round->winner_seat = last_seat;
		round->phase = last_seat < 0 ? BR_DRAW : BR_WON;
	}
	return 1;
}
