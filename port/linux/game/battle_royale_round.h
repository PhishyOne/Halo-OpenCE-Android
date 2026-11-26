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

struct battle_royale_round
{
	struct battle_royale_zone zone;
	struct battle_royale_circle circle;
	enum battle_royale_phase phase;
	int remaining, winner_seat, last_tick, last_damage_tick, tick_open;
	uint32_t surviving[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
};

/* Nonzero identities in original engine seats. An invalid setup leaves round intact. */
int battle_royale_begin(struct battle_royale_round *round, const struct battle_royale_zone *zone,
	const uint32_t identities[HALO_PORT_MAXIMUM_NETWORK_PLAYERS]);
int battle_royale_alive(const struct battle_royale_round *round, int seat, uint32_t identity);

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
