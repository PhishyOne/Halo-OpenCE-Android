/* Host round rules for the Battle Royale prototype. A seat is admitted
   after its initial spawn. Host-confirmed deaths and departures are final. */
#ifndef BATTLE_ROYALE_ROUND_H
#define BATTLE_ROYALE_ROUND_H

#include <stdint.h>
#include "halo_port_limits.h"

struct battle_royale_circle
{
	float x, y, radius; /* horizontal world units */
};

struct battle_royale_zone
{
	struct battle_royale_circle start, end;
	int hold_ticks, close_ticks, damage_interval_ticks;
};

enum battle_royale_phase { BR_IDLE, BR_PLAYING, BR_WON, BR_DRAW };
enum battle_royale_observation_kind { BR_UNOBSERVED, BR_POSITION, BR_OUT };

struct battle_royale_observation
{
	uint32_t identity;
	enum battle_royale_observation_kind kind;
	float x, y;
};

/* Map-authored positions and weapon tag handles. Zero means an empty slot. */
enum { BR_MAX_LOOT = 256, BR_WEAPON_SLOTS = 2 };
struct battle_royale_point { float x, y, z; };
struct battle_royale_pickup
{
	struct battle_royale_point position;
	uint32_t weapon;
};

struct battle_royale_round
{
	struct battle_royale_zone zone;
	struct battle_royale_circle circle;
	enum battle_royale_phase phase;
	int remaining, winner_seat, last_tick, last_damage_tick, tick_open;
	uint32_t surviving[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
	uint32_t weapons[HALO_PORT_MAXIMUM_NETWORK_PLAYERS][BR_WEAPON_SLOTS];
	struct battle_royale_pickup loot[BR_MAX_LOOT];
	int loot_count;
};

/* Advance one private, nonzero 32-bit random stream. */
uint32_t battle_royale_random(uint32_t *seed);

/* Nonzero identities in original engine seats. An invalid setup leaves round intact. */
int battle_royale_begin(struct battle_royale_round *round, const struct battle_royale_zone *zone,
	const uint32_t identities[HALO_PORT_MAXIMUM_NETWORK_PLAYERS]);
int battle_royale_alive(const struct battle_royale_round *round, int seat, uint32_t identity);

/* Before the first tick, seed up to site_count distinct places using the
   map's weapon table. A round starts each entrant with both weapon slots empty. */
int battle_royale_place_loot(struct battle_royale_round *round,
	const struct battle_royale_point *sites, int site_count,
	const uint32_t *weapons, int weapon_count, int count, uint32_t seed);

/* World-unit spawn markers from a scenario. br_in_NN_* belongs to building NN,
   br_fixed_* is a landmark, and br_wild_* is a possible outdoor spawn. */
struct battle_royale_loot_marker { char name[32]; struct battle_royale_point point; };
int battle_royale_place_marked_loot(struct battle_royale_round *round,
	const struct battle_royale_loot_marker *markers, int marker_count,
	const uint32_t *weapons, int weapon_count, int indoor_per_building, int random_outdoors, uint32_t seed);

struct scenario;
int battle_royale_seed_scenario_loot(struct battle_royale_round *round, const struct scenario *scenario,
	const uint32_t *weapons, int weapon_count, uint32_t seed);

/* The caller supplies the host's current unit position in world units.
   The same nearest-item query can guide a bot. A claim succeeds once. */
int battle_royale_nearest_loot(const struct battle_royale_round *round, int seat, uint32_t identity,
	struct battle_royale_point where, float range);
uint32_t battle_royale_take_loot(struct battle_royale_round *round, int seat, uint32_t identity,
	struct battle_royale_point where, int pickup);

/* Sample positions before applying damage. Each due flag requests one
   application through Halo's damage path. A stale call clears damage_due. */
int battle_royale_open_tick(struct battle_royale_round *round, int elapsed_ticks,
	const struct battle_royale_observation seats[HALO_PORT_MAXIMUM_NETWORK_PLAYERS],
	unsigned char damage_due[HALO_PORT_MAXIMUM_NETWORK_PLAYERS]);

/* After all damage has resolved, submit the tick's deaths and departures
   together. Missing positions never imply death. */
int battle_royale_finish_tick(struct battle_royale_round *round, int elapsed_ticks,
	const struct battle_royale_observation seats[HALO_PORT_MAXIMUM_NETWORK_PLAYERS]);

#endif
