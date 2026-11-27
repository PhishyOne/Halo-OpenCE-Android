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

static int valid_point(struct battle_royale_point point)
{
	return finite_coordinate(point.x) && finite_coordinate(point.y) && finite_coordinate(point.z);
}

/* Local stream: loot and landings each keep their own seed. */
uint32_t battle_royale_random(uint32_t *seed)
{
	*seed ^= *seed << 13;
	*seed ^= *seed >> 17;
	*seed ^= *seed << 5;
	return *seed;
}

int battle_royale_place_loot(struct battle_royale_round *round,
	const struct battle_royale_point *sites, int site_count, const uint32_t *weapons,
	int weapon_count, int count, uint32_t seed)
{
	unsigned short order[BR_MAX_LOOT];
	int i, j;
	if (round->phase != BR_PLAYING || round->last_tick >= 0 || round->loot_count ||
		!sites || !weapons || site_count < 1 || site_count > BR_MAX_LOOT ||
		weapon_count < 1 || weapon_count > BR_MAX_LOOT || count < 1 || count > site_count)
		return 0;
	for (i = 0; i < weapon_count; ++i)
		if (!weapons[i] || weapons[i] == UINT32_MAX)
			return 0;
	for (i = 0; i < site_count; ++i)
	{
		if (!valid_point(sites[i]))
			return 0;
		for (j = 0; j < i; ++j)
			if (sites[i].x == sites[j].x && sites[i].y == sites[j].y && sites[i].z == sites[j].z)
				return 0;
		order[i] = (unsigned short)i;
	}
	if (!seed)
		seed = UINT32_C(0x9e3779b9);
	for (i = 0; i < count; ++i)
	{
		int next = i + (int)(battle_royale_random(&seed) % (uint32_t)(site_count - i));
		round->loot[i].position = sites[order[next]];
		order[next] = order[i];
		round->loot[i].weapon = weapons[battle_royale_random(&seed) % (uint32_t)weapon_count];
	}
	round->loot_count = count;
	return 1;
}

int battle_royale_place_marked_loot(struct battle_royale_round *round,
	const struct battle_royale_loot_marker *markers, int marker_count,
	const uint32_t *weapons, int weapon_count, int indoor_per_building, int random_outdoors, uint32_t seed)
{
	struct battle_royale_point chosen[BR_MAX_LOOT];
	int building_counts[100] = {0};
	int order[BR_MAX_LOOT], category[BR_MAX_LOOT];
	int i, j, count = 0, wild = 0;
	if (!markers || marker_count < 1 || marker_count > BR_MAX_LOOT ||
		indoor_per_building < 1 || indoor_per_building > marker_count ||
		random_outdoors < 0 || random_outdoors > marker_count) return 0;
	for (i=0; i<marker_count; ++i) {
		const char *name = markers[i].name;
		struct battle_royale_point p = markers[i].point;
		if (!memchr(name, 0, sizeof(markers[i].name)) || !valid_point(p)) return 0;
		for (j=0; j<i; ++j) {
			struct battle_royale_point q = markers[j].point;
			if (!strcmp(name,markers[j].name) || (p.x==q.x && p.y==q.y && p.z==q.z)) return 0;
		}
		if (!strncmp(name,"br_fixed_",9) && name[9]) category[i] = -1;
		else if (!strncmp(name,"br_wild_",8) && name[8]) category[i] = 0;
		else if (!strncmp(name,"br_in_",6) && name[6]>='0' && name[6]<='9' &&
			name[7]>='0' && name[7]<='9' && name[8]=='_' && name[9]) {
			category[i] = (name[6]-'0')*10 + name[7]-'0';
			if (!category[i]) return 0;
		} else return 0;
		order[i] = i;
	}
	if (!seed) seed = UINT32_C(0x9e3779b9);
	for (i=0; i<marker_count; ++i) {
		int next = i + battle_royale_random(&seed) % (uint32_t)(marker_count-i);
		int index = order[next], group = category[index];
		order[next] = order[i];
		if (group == 0) {
			if (wild >= random_outdoors) continue;
			++wild;
		} else if (group > 0) {
			if (building_counts[group] >= indoor_per_building) continue;
			++building_counts[group];
		}
		chosen[count++] = markers[index].point;
	}
	if (wild != random_outdoors) return 0;
	return battle_royale_place_loot(round,chosen,count,weapons,weapon_count,count,seed);
}

static int can_take(const struct battle_royale_round *round, int seat, uint32_t identity)
{
	return round->phase == BR_PLAYING && battle_royale_alive(round, seat, identity) &&
		!round->weapons[seat][BR_WEAPON_SLOTS-1];
}

static double separation_squared(struct battle_royale_point a, struct battle_royale_point b)
{
	double x = (double)a.x - b.x, y = (double)a.y - b.y, z = (double)a.z - b.z;
	return x*x + y*y + z*z;
}

int battle_royale_nearest_loot(const struct battle_royale_round *round, int seat, uint32_t identity,
	struct battle_royale_point where, float range)
{
	int i, best = -1;
	double limit = (double)range * range;
	if (!can_take(round, seat, identity) || !valid_point(where) || !finite_coordinate(range) || range < 0)
		return -1;
	for (i = 0; i < round->loot_count; ++i)
	{
		double d;
		if (!round->loot[i].weapon)
			continue;
		d = separation_squared(where, round->loot[i].position);
		if (d <= limit && (best < 0 || d < limit))
		{
			best = i;
			limit = d;
		}
	}
	return best;
}

uint32_t battle_royale_take_loot(struct battle_royale_round *round, int seat, uint32_t identity,
	struct battle_royale_point where, int pickup)
{
	/* Prototype interaction radius: half a world unit (about 1.52 m). */
	const double reach_squared = 0.25;
	uint32_t weapon;
	int slot;
	if (!can_take(round, seat, identity) || pickup < 0 || pickup >= round->loot_count ||
		!valid_point(where) || separation_squared(where, round->loot[pickup].position) > reach_squared)
		return 0;
	weapon = round->loot[pickup].weapon;
	if (!weapon)
		return 0;
	slot = round->weapons[seat][0] ? 1 : 0;
	round->weapons[seat][slot] = weapon;
	round->loot[pickup].weapon = 0;
	return weapon;
}

struct battle_royale_circle battle_royale_circle_at(const struct battle_royale_zone *zone, int tick)
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

int battle_royale_outside(const struct battle_royale_circle *circle, float x, float y)
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
	round->circle = battle_royale_circle_at(&round->zone, tick);
	if (tick - round->last_damage_tick >= round->zone.damage_interval_ticks)
	{
		round->last_damage_tick = tick;
		for (i = 0; i < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; ++i)
			if (seats[i].kind == BR_POSITION && battle_royale_alive(round, i, seats[i].identity) &&
				finite_coordinate(seats[i].x) && finite_coordinate(seats[i].y))
				damage_due[i] = battle_royale_outside(&round->circle, seats[i].x, seats[i].y);
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
