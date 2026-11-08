/*
BOTS_LOBBY.C (test)

The real lobby of the bots (network_server_manager.c's
network_game_server_port_bots, with network_game_manager.c's players and
bots.c's machines and names): who joins a local game's lobby, on which
team, who leaves, and where no bot ever joins. test_bots_lobby.py takes the
constants and structures (config.inc) and the code (under_test.inc).
*/

#include "harness.h"

#include <wchar.h>

typedef unsigned short word;
#define VALID_INDEX(index, count) ((index) >= 0 && (index) < (count))
#define PIN(value, low, high) ((value) < (low) ? (low) : (value) > (high) ? (high) : (value))
#define NUMBEROF(array) (sizeof(array) / sizeof((array)[0]))
#define csmemset memset
#define csmemcpy memcpy
#define error(priority, ...) ((void)0)
#define network_event(...) ((void)0)

#include "config.inc"

#define MAXIMUM_NETWORK_MACHINE_COUNT HALO_PORT_MAXIMUM_NETWORK_MACHINES
#define MAXIMUM_NETWORK_PLAYER_COUNT HALO_PORT_MAXIMUM_NETWORK_PLAYERS
#define NETWORK_GAME_PLAYER_SLOTS HALO_PORT_MAXIMUM_NETWORK_PLAYERS

struct game_variant
{
	short game_engine_index;
};

struct network_machine
{
	char machine_index;
};

struct network_game_client_machine
{
	short machine_index;
};

struct network_game
{
	struct network_machine machines[HALO_PORT_MAXIMUM_NETWORK_MACHINES];
	struct game_variant variant;
	byte maximum_players;
	short player_count;
	struct network_player players[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
};

struct network_game_server
{
	word state;
	struct network_game_client_machine client_machines[HALO_PORT_MAXIMUM_NETWORK_MACHINES];
	struct network_game game;
	boolean sent_start_game_message;
};

/* the world the lobby asks */
static boolean local_game;
static long wanted;
static long system_link_wanted;
static boolean online_game;
static short teams_setting;
static long sends;
static short next_color;

static boolean network_game_is_splitscreen_local(void) { return local_game; }
static long bots_wanted_count(void) { return wanted; }
static long config_integer(char const *name) { (void)name; return system_link_wanted; }
static int config_boolean(char const *name) { (void)name; return online_game; }
static short bots_teams(void) { return teams_setting; }
static boolean network_game_player_slot_held(long player_index) { (void)player_index; return FALSE; }
static void get_unique_random_color(struct network_game_server *server, struct network_player *player)
{
	(void)server;
	player->primary_color_index = next_color++;
}
static boolean network_game_server_send_game_data_pregame(struct network_game_server *server)
{
	(void)server;
	sends++;
	return TRUE;
}

/* (the game's own code, as its build compiles it: a char index, a char
machine index against the session's 128) */
#pragma clang diagnostic ignored "-Wchar-subscripts"
#pragma clang diagnostic ignored "-Wtautological-constant-out-of-range-compare"
#include "under_test.inc"

static struct network_game_server server;

/* a pregame lobby of the game engine, room for `maximum`, and its players */
static void lobby(short game_engine_index, long maximum)
{
	long index;

	memset(&server, 0, sizeof(server));
	server.state = _network_game_server_state_pregame;
	server.game.variant.game_engine_index = game_engine_index;
	server.game.maximum_players = (byte)maximum;
	for (index = 0; index < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; index++)
	{
		network_game_invalidate_player(&server.game.players[index]);
		server.game.machines[index].machine_index = NONE;
		server.client_machines[index].machine_index = NONE;
	}
	local_game = TRUE;
	system_link_wanted = 0;
	online_game = FALSE;
	teams_setting = _bots_teams_even;
	sends = 0;
}

/* a player of this machine (0) on the team */
static struct network_player *player(long controller_index, long team_index)
{
	struct network_player joining;
	long index;

	memset(&joining, 0, sizeof(joining));
	joining.name[0] = L'P';
	joining.machine_index = 0;
	joining.controller_index = (char)controller_index;
	joining.team_index = (char)team_index;
	joining.player_list_index = NONE;
	CHECK(network_game_add_player(&server.game, &joining), "player %ld not added", controller_index);
	for (index = 0; index < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; index++)
	{
		if (server.game.players[index].machine_index == 0 && server.game.players[index].controller_index == controller_index)
			return &server.game.players[index];
	}
	return NULL;
}

/* the bots in the lobby, and on each team */
static long bots_in(long *team0, long *team1)
{
	long index, count = 0;

	*team0 = *team1 = 0;
	for (index = 0; index < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; index++)
	{
		struct network_player *slot = &server.game.players[index];

		if (!network_player_is_valid(slot) || !bots_machine_is_bot(slot->machine_index) ||
			server.game.machines[(short)slot->machine_index].machine_index != NONE)
			continue;
		count++;
		if (slot->team_index == 0)
			(*team0)++;
		else if (slot->team_index == 1)
			(*team1)++;
	}
	return count;
}

static boolean bot_in(long bot_index)
{
	long index;

	for (index = 0; index < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; index++)
	{
		if (network_player_is_valid(&server.game.players[index]) &&
			server.game.players[index].machine_index == bots_first_machine() + bot_index)
		{
			return TRUE;
		}
	}
	return FALSE;
}

int main(int argc, char **argv)
{
	char const *case_name = argc > 1 ? argv[1] : "";
	long team0, team1, count;

	/* as many as asked for, once: unchanged after; 127 maximum bots in
	   a 128-player local lobby, with one human on machine zero. */
	CASE("fills")
	{
		lobby(game_engine_slayer, 128);
		player(0, 0);
		wanted = 6;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 6, "%ld bots, not 6", bots_in(&team0, &team1));
		CHECK(server.game.player_count == 7, "%d players, not 7", server.game.player_count);
		CHECK(sends == 1, "the lobby sent %ld times, not once", sends);
		network_game_server_port_bots(&server);
		CHECK(sends == 1 && server.game.player_count == 7, "the lobby changed again (%ld sends)", sends);
		wanted = 99;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 99, "%ld bots for 99, not 99", bots_in(&team0, &team1));
		wanted = 255;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 127, "%ld bots for 255, not 127", bots_in(&team0, &team1));
		CHECK(server.game.player_count == 128 && bot_in(126), "127-bot match did not fill 128 player slots");
		return 0;
	}
	/* as far as the game has room */
	CASE("room")
	{
		lobby(game_engine_slayer, 4);
		player(0, 0);
		wanted = 6;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 3, "%ld bots in a game of 4, not 3", bots_in(&team0, &team1));
		CHECK(server.game.player_count == 4, "%d players in a game of 4", server.game.player_count);
		return 0;
	}
	/* fewer asked for: the last leave */
	CASE("fewer")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		wanted = 6;
		network_game_server_port_bots(&server);
		wanted = 2;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 2, "%ld bots, not 2", bots_in(&team0, &team1));
		CHECK(bot_in(0) && bot_in(1) && !bot_in(2) && !bot_in(5), "not the first two that stayed");
		CHECK(server.game.player_count == 3, "%d players, not 3", server.game.player_count);
		wanted = 0;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0 && server.game.player_count == 1, "bots stayed for none");
		return 0;
	}
	/* "even": the teams evened with the players */
	CASE("even")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		player(1, 0);
		wanted = 5;
		network_game_server_port_bots(&server);
		count = bots_in(&team0, &team1);
		CHECK(count == 5, "%ld bots, not 5", count);
		CHECK(team0 + 2 - team1 <= 1 && team1 - (team0 + 2) <= 1, "teams of %ld and %ld", team0 + 2, team1);
		return 0;
	}
	/* "against": every bot on the team the players are not on */
	CASE("against")
	{
		lobby(game_engine_slayer, 16);
		player(0, 1);
		teams_setting = _bots_teams_against;
		wanted = 4;
		network_game_server_port_bots(&server);
		count = bots_in(&team0, &team1);
		CHECK(count == 4 && team0 == 4, "%ld of %ld bots against the players' team 1", team0, count);
		return 0;
	}
	/* the players change teams in the lobby: the bots follow */
	CASE("follows-players")
	{
		struct network_player *human;

		lobby(game_engine_slayer, 16);
		human = player(0, 0);
		teams_setting = _bots_teams_against;
		wanted = 3;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 3 && team1 == 3, "%ld bots on team 1, not 3", team1);
		human->team_index = 1;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 3 && team0 == 3, "%ld bots on team 0 once the player moved, not 3", team0);
		CHECK(sends == 2, "the move sent %ld times", sends - 1);
		return 0;
	}
	/* a game other machines may join (system link, internet): none, and any
	there leave */
	CASE("never-network")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		wanted = 6;
		local_game = FALSE;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots in a network game", bots_in(&team0, &team1));
		CHECK(sends == 0, "a network game's lobby sent for bots");
		local_game = TRUE;
		network_game_server_port_bots(&server);
		local_game = FALSE;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots stayed in a network game", bots_in(&team0, &team1));
		return 0;
	}
	/* Slayer alone (the other gametypes' goals are not the bots' yet): none
	in CTF, and those of a Slayer lobby leave when it becomes CTF */
	CASE("slayer-only")
	{
		lobby(game_engine_ctf, 16);
		player(0, 0);
		wanted = 6;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots in CTF", bots_in(&team0, &team1));
		server.game.variant.game_engine_index = game_engine_slayer;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 6, "%ld bots in Slayer, not 6", bots_in(&team0, &team1));
		server.game.variant.game_engine_index = game_engine_king;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots stayed for King of the Hill", bots_in(&team0, &team1));
		return 0;
	}
	/* nothing changes once the game is started, nor in the game */
	CASE("not-once-started")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		wanted = 6;
		server.sent_start_game_message = TRUE;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots joined a starting game", bots_in(&team0, &team1));
		server.sent_start_game_message = FALSE;
		server.state = _network_game_server_state_ingame;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "%ld bots joined a game in progress", bots_in(&team0, &team1));
		return 0;
	}
	/* no bots before a player is in */
	CASE("no-player-no-bots")
	{
		lobby(game_engine_slayer, 16);
		wanted = 6;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0 && server.game.player_count == 0, "bots in a lobby with no player");
		return 0;
	}
	/* each bot the one player of a machine no real machine is, named, a
	colour of its own */
	CASE("names-and-machines")
	{
		long index, named = 0;

		lobby(game_engine_slayer, 128);
		player(0, 0);
		wanted = 127;
		network_game_server_port_bots(&server);
		for (index = 0; index < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; index++)
		{
			struct network_player *slot = &server.game.players[index];

			if (!network_player_is_valid(slot) || !bots_machine_is_bot(slot->machine_index))
				continue;
			CHECK(slot->controller_index == 0, "bot of machine %d on controller %d", slot->machine_index,
				slot->controller_index);
			CHECK(slot->machine_index >= BOTS_FIRST_MACHINE && slot->machine_index < BOTS_FIRST_MACHINE + BOTS_MAXIMUM,
				"bot of machine %d", slot->machine_index);
			CHECK(slot->primary_color_index != NONE, "bot of machine %d has no colour", slot->machine_index);
			if (slot->name[0])
			{
				long previous;
				for (previous = 0; previous < index; previous++)
				{
					struct network_player *other = &server.game.players[previous];
					if (network_player_is_valid(other) && bots_machine_is_bot(other->machine_index))
						CHECK(wcscmp(slot->name, other->name) != 0,
							"duplicate bot name at slots %ld and %ld", previous, index);
				}
				named++;
			}
		}
		CHECK(named == 127, "%ld of 127 bots named", named);
		CHECK(!bots_machine_is_bot(0) && !bots_machine_is_bot(BOTS_FIRST_MACHINE - 1), "a real machine is a bot");
		return 0;
	}
	/* port: reserve room for two humans even at the 128-player ceiling. */
	CASE("cap-two-humans")
	{
		lobby(game_engine_slayer, 128);
		player(0, 0);
		player(1, 0);
		wanted = 127;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 126,
			"%ld bots with two humans in 128 slots, expected 126", bots_in(&team0, &team1));
		CHECK(server.game.player_count == 128, "the 128-player lobby is not filled");
		CHECK(bot_in(125) && !bot_in(126), "the final bot took a human's player slot");
		return 0;
	}
	/* port: network bots stay off until the LAN host opts in. */
	CASE("lan-off")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		local_game = FALSE;
		wanted = 6;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "network bots started without opt-in");
		return 0;
	}
	CASE("lan-one")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		local_game = FALSE;
		system_link_wanted = 1;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 1 && server.game.player_count == 2,
			"one LAN bot was not put in the lobby");
		CHECK(sends == 1, "LAN bot roster was not sent");
		return 0;
	}
	CASE("lan-four")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		player(1, 0);
		local_game = FALSE;
		system_link_wanted = 99;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 4, "LAN bot limit did not clamp to four");
		CHECK(server.game.player_count == 6, "LAN bot roster has wrong player count");
		return 0;
	}
	CASE("internet-never")
	{
		lobby(game_engine_slayer, 16);
		player(0, 0);
		local_game = FALSE;
		system_link_wanted = 4;
		online_game = TRUE;
		network_game_server_port_bots(&server);
		CHECK(bots_in(&team0, &team1) == 0, "LAN bots entered an internet lobby");
		CHECK(sends == 0, "internet bot roster unexpectedly sent");
		return 0;
	}
	CASE("high-slot")
	{
		struct network_player joining;
		long fake_slot = BOTS_LAN_FIRST_MACHINE;
		lobby(game_engine_slayer, 16);
		player(0, 0);
		local_game = FALSE;
		system_link_wanted = 1;
		memset(&joining, 0, sizeof(joining));
		joining.machine_index = (char)fake_slot;
		joining.controller_index = 0;
		joining.team_index = 0;
		joining.player_list_index = NONE;
		server.game.machines[fake_slot].machine_index = (char)fake_slot;
		server.client_machines[fake_slot].machine_index = (short)fake_slot;
		CHECK(network_game_add_player(&server.game, &joining), "high-index real player did not join");
		network_game_server_port_bots(&server);
		CHECK(server.game.player_count == 2, "host replaced a real high-index client with a bot");
		CHECK(bots_in(&team0, &team1) == 0, "fake bot collided with a real machine");
		return 0;
	}
	/* port: the 127-offline cap MUST NOT reserve a normal guest's LAN
	   machine slot 1: only machines 124..127 are synthetic in LAN mode. */
	CASE("lan-low-real-slot")
	{
		struct network_player joining;
		lobby(game_engine_slayer, 16);
		player(0, 0);
		local_game = FALSE;
		system_link_wanted = 4;
		CHECK(!bots_machine_is_bot(1), "the first LAN guest machine is reserved for bots");
		CHECK(bots_machine_is_bot(BOTS_LAN_FIRST_MACHINE) &&
			!bots_machine_is_bot(BOTS_LAN_FIRST_MACHINE - 1),
			"LAN bot machine range differs from four high slots");
		memset(&joining, 0, sizeof(joining));
		joining.name[0] = L'G';
		joining.machine_index = 1;
		joining.controller_index = 0;
		joining.team_index = 0;
		joining.player_list_index = NONE;
		server.game.machines[1].machine_index = 1;
		server.client_machines[1].machine_index = 1;
		CHECK(network_game_add_player(&server.game, &joining), "guest on machine 1 cannot join");
		network_game_server_port_bots(&server);
		CHECK(server.game.player_count == 6, "two humans + four LAN bots did not fit");
		CHECK(bots_in(&team0, &team1) == 4, "LAN bots not present alongside guest");
		CHECK(bot_in(0) && bot_in(3), "LAN bots not placed in high reserved machines");
		return 0;
	}
	fprintf(stderr, "unknown case: %s\n", case_name);
	return 2;
}
