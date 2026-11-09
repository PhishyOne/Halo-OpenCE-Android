/* bots.h: computer players for local multiplayer games (port/linux/game/bots.c)

A bot is a player of a local (split screen) game whose controls come from
code instead of a controller: it is in the lobby, the scores and the game's
rules as any player is, spawns, respawns and scores. Each bot is the one
player of a machine of its own at the top of the machine indices. Offline
bots use machine indices 1..127 (the only real machine is 0), while LAN
bots use 112..127 and yield if a real guest already occupies one. The bots have no view and no controller: nothing is
drawn for them. */
#ifndef HALO_BOTS_H
#define HALO_BOTS_H

struct player_action;
struct network_player;

/* Offline stress-test ceiling: 127 synthetic machines + host machine 0.
   LAN bots are a SEPARATE sixteen-bot experiment and reserve only
   their upper 16 slots, keeping 111 guest machine slots available. */
#define BOTS_MAXIMUM 127
#define BOTS_SYSTEM_LINK_MAXIMUM 16
/* offline local split screen only: simulated machine indices 1..127 */
#define BOTS_FIRST_MACHINE (HALO_PORT_MAXIMUM_NETWORK_MACHINES - BOTS_MAXIMUM)
/* LAN only: synthetic machine indices 112..127; other clients use lower slots */
#define BOTS_LAN_FIRST_MACHINE (HALO_PORT_MAXIMUM_NETWORK_MACHINES - BOTS_SYSTEM_LINK_MAXIMUM)

enum
{
	/* bots.teams: in a team game, the bots on both teams (evening them
	with the players), or all on the team the players are not on */
	_bots_teams_even = 0,
	_bots_teams_against,
};

/* the settings (bots.count, bots.skill, bots.teams) */
long bots_wanted_count(void);
/* port: opt-in count for LAN host; CTF/King require bots.objective_modes. */
long bots_system_link_wanted_count(void);
short bots_skill(void);
short bots_teams(void);

/* Bot machine-number range is mode-specific: local 1..127, LAN 112..127. */
long bots_first_machine(void);
/* a machine is in that mode's synthetic bot range */
int bots_machine_is_bot(long machine_index);
/* the network player for bot number `bot_index` (0 up): its machine,
controller, name and no team or colour yet */
void bots_network_player(long bot_index, struct network_player *player);

/* the game tick (players_update_before_game): every bot's controls this
tick, into the actions of the players by their index; nothing outside a
local game */
void bots_update_actions(struct player_action *actions);

/* a map's start and end (players.c) */
void bots_initialize_for_new_map(void);
void bots_dispose_from_old_map(void);

#endif
