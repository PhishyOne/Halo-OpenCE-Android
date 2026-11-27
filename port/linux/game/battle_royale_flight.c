/* Echo 419 insertion. The route is authored in the scenario. Only the host
   moves the craft, releases each original biped and assists its first landing. */
#include "cseries.h"
#include "game/game.h"
#include "game/players.h"
#include "math/real_math.h"
#include "objects/objects.h"
#include "scenario/scenario.h"
#include "scenario/scenario_definitions.h"
#include "structures/structure_bsp_definitions.h"
#include "units/bipeds.h"
#include "units/vehicle_definitions.h"
#include "network_chat.h"
#include "network_distributed.h"
#include "battle_royale_flight.h"

char const *config_string(char const *name);
void platform_log(char const *format, ...);

enum { OPEN_TICK = 90, LAST_JUMP = 1800, EXIT_TICK = 1980 };
enum rider_state { EMPTY, ABOARD, FALLING };

struct rider {
	long player, biped;
	enum rider_state state;
	boolean overflow, was_visible, had_collisions, had_safe_fall, jump_held, requested;
};

static struct {
	real_point3d entry, exit;
	real_vector3d heading;
	long definition, ship;
	int tick;
	boolean enabled, started, fixture;
	struct rider riders[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
} flight;

void battle_royale_flight_reset(void)
{
	struct scenario *map = global_scenario_try_and_get();
	int starts = 0, ends = 0;
	long i;
	memset(&flight, 0, sizeof(flight));
	flight.ship = NONE;
	if (!map || (game_connection() != _game_connection_local &&
		game_connection() != _game_connection_network_server))
		return;
	for (i = 0; i < map->cutscene_flags.count; ++i) {
		struct scenario_cutscene_flag const *flag = TAG_BLOCK_GET_ELEMENT(
			&map->cutscene_flags, i, struct scenario_cutscene_flag);
		if (!strncmp(flag->name, "echo419_entry", sizeof(flag->name))) {
			flight.entry = flag->position;
			++starts;
		} else if (!strncmp(flag->name, "echo419_exit", sizeof(flag->name))) {
			flight.exit = flag->position;
			++ends;
		}
	}
	if (starts != 1 || ends != 1)
		return;
	flight.heading.i = flight.exit.x - flight.entry.x;
	flight.heading.j = flight.exit.y - flight.entry.y;
	if (!(normalize3d(&flight.heading) > 0))
		return;
	flight.definition = tag_loaded(VEHICLE_DEFINITION_TAG, "vehicles\\pelican\\pelican");
	/* Asset-free native test maps can exercise player physics in log mode. */
	flight.fixture = !strcmp(config_string("debug.network_test"), "log") &&
		(!strcmp(config_string("debug.test_input"), "echo419-jump") ||
		 !strcmp(config_string("debug.test_input"), "echo419-wait"));
	flight.enabled = flight.definition != NONE || flight.fixture;
}

static boolean board_roster(void)
{
	struct data_iterator it;
	struct player_datum *player;
	int count = 0;
	struct structure_bsp *bsp;
	if (global_structure_bsp_index_get() == NONE)
		return FALSE;
	bsp = global_structure_bsp_get();
	if (!point_in_rectangle3d(&flight.entry, &bsp->world_bounds) ||
		!point_in_rectangle3d(&flight.exit, &bsp->world_bounds)) {
		flight.enabled = FALSE;
		return FALSE;
	}
	data_iterator_new(&it, player_data);
	while ((player = data_iterator_next(&it))) {
		if (player->quit_out_of_game)
			continue;
		if (player->unit_index == NONE)
			return FALSE;
		++count;
	}
	if (!count)
		return FALSE;
	if (flight.definition != NONE) {
		struct object_placement_data placement;
		object_placement_data_new(&placement, flight.definition, NONE);
		placement.position = flight.entry;
		placement.forward = flight.heading;
		flight.ship = object_new(&placement);
		if (flight.ship == NONE)
			return FALSE;
		SET_FLAG(object_get(flight.ship)->object.damage_flags, _object_cannot_take_damage_bit, TRUE);
	}
	data_iterator_new(&it, player_data);
	while ((player = data_iterator_next(&it))) {
		struct rider *r = &flight.riders[DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index)];
		struct biped_datum *b;
		short seat;
		if (player->quit_out_of_game)
			continue;
		r->player = it.datum_index;
		r->biped = player->unit_index;
		b = biped_get(r->biped);
		r->had_safe_fall = TEST_FLAG(b->unit.flags, _unit_no_falling_damage_bit);
		r->was_visible = !TEST_FLAG(b->object.flags, _object_invisible_bit);
		r->had_collisions = !TEST_FLAG(b->object.flags, _object_no_collisions_bit);
		r->state = ABOARD;
		r->overflow = TRUE;
		if (flight.ship != NONE && vehicle_scripting_find_available_seats(flight.ship, "rider", NONE, &seat, 1))
			r->overflow = !unit_enter_seat(r->biped, flight.ship, seat);
		if (r->overflow) {
			/* The campaign model has fewer seats than LAN. Overflow views ride
			   inside the bay; coincident extra bodies never shove seated players. */
			object_set_visibility(r->biped, FALSE);
			SET_FLAG(b->object.flags, _object_no_collisions_bit, TRUE);
		}
		SET_FLAG(b->unit.flags, _unit_no_falling_damage_bit, TRUE);
	}
	platform_log("Echo 419: boarded %d", count);
	return TRUE;
}

/* Every passenger uses the existing Jump action over the normal input stream.
   Keep their look direction, but consume combat/use until they leave the bay. */
void battle_royale_cabin_controls(long player, struct player_action *action)
{
	struct rider *r;
	boolean jump;
	if (!flight.started || !flight.enabled)
		return;
	r = &flight.riders[DATUM_INDEX_TO_ABSOLUTE_INDEX(player)];
	if (r->state != ABOARD || r->player != player)
		return;
	jump = TEST_FLAG(action->control_flags, _unit_control_jump_bit);
	if (flight.tick >= OPEN_TICK && jump && !r->jump_held)
		r->requested = TRUE;
	r->jump_held = jump;
	action->control_flags = 0;
	action->primary_trigger = 0;
}

static void cabin_notice(char const *message)
{
	if (game_connection() == _game_connection_network_server)
		distributed_send_notice(message);
	else
		network_chat_host_notice(message);
}

static void restore_rider(struct rider *r, struct biped_datum *b)
{
	if (r->overflow && r->state == ABOARD) {
		object_set_visibility(r->biped, r->was_visible);
		SET_FLAG(b->object.flags, _object_no_collisions_bit, !r->had_collisions);
	}
	SET_FLAG(b->unit.flags, _unit_no_falling_damage_bit, r->had_safe_fall);
	r->state = EMPTY;
}

static void release(struct rider *r, struct biped_datum *b, real_point3d position, int slot)
{
	real sideways = ((slot % 16) - 7.5f) * .62f;
	real aft = 5.f + (slot / 16) * .62f;
	if (b->object.parent_object_index == flight.ship && flight.ship != NONE)
		unit_exit_seat_end(r->biped);
	restore_rider(r, b);
	/* Clear the rear ramp. Spread simultaneous drops across a short formation. */
	position.x -= flight.heading.i * aft + flight.heading.j * sideways;
	position.y -= flight.heading.j * aft - flight.heading.i * sideways;
	position.z -= 1.5f;
	object_set_position(r->biped, &position, NULL, NULL);
	SET_FLAG(b->biped.flags, _biped_airborne_bit, TRUE);
	SET_FLAG(b->unit.flags, _unit_no_falling_damage_bit, TRUE);
	b->object.translational_velocity = (real_vector3d){0, 0, -.08f};
	r->state = FALLING;
	platform_log("Echo 419: dropped seat %d at tick %d", slot, flight.tick);
}

static void steer(struct rider *r, struct biped_datum *b)
{
	real_vector3d aim = b->unit.aiming_vector;
	if (!TEST_FLAG(b->biped.flags, _biped_airborne_bit)) {
		restore_rider(r, b);
		platform_log("Echo 419: landed at tick %d", flight.tick);
		return;
	}
	/* World units per tick. Use the normal biped collision for roofs,
	   terrain and obstacles; return all subsequent falls to normal rules. */
	aim.k = 0;
	if (normalize3d(&aim) > 0) {
		real forward = b->unit.throttle.i * .22f;
		real left = b->unit.throttle.j * .22f;
		b->object.translational_velocity.i += .12f * (aim.i*forward - aim.j*left - b->object.translational_velocity.i);
		b->object.translational_velocity.j += .12f * (aim.j*forward + aim.i*left - b->object.translational_velocity.j);
	}
	b->object.translational_velocity.k = MAX(-.52f, b->object.translational_velocity.k - .012f);
}

void battle_royale_flight_tick(void)
{
	struct object_datum *ship;
	real_point3d position;
	real t;
	int slot, active = 0;
	if (!flight.enabled)
		return;
	if (!flight.started) {
		if (!board_roster())
			return;
		flight.started = TRUE;
		cabin_notice("Aboard Echo 419. Stand by.");
	}
	if (flight.tick == OPEN_TICK)
		cabin_notice("Drop zone open. Press Jump to leave Echo 419.");
	t = MIN(flight.tick, EXIT_TICK) / (real)EXIT_TICK;
	position.x = flight.entry.x + (flight.exit.x-flight.entry.x)*t;
	position.y = flight.entry.y + (flight.exit.y-flight.entry.y)*t;
	position.z = flight.entry.z + (flight.exit.z-flight.entry.z)*t;
	ship = flight.ship != NONE ? object_try_and_get(flight.ship) : NULL;
	if (ship) {
		if (flight.tick < EXIT_TICK) {
			object_set_position(flight.ship, &position, &flight.heading, global_up3d);
			ship->object.translational_velocity = *global_zero_vector3d;
		} else {
			/* Despawn intact at the route exit, without damage or death effects. */
			object_delete(flight.ship);
			flight.ship = NONE;
		}
	}
	for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; ++slot) {
		struct rider *r = &flight.riders[slot];
		struct biped_datum *b;
		struct player_datum *player;
		if (r->state == EMPTY)
			continue;
		b = biped_try_and_get(r->biped);
		player = player_try_and_get(r->player);
		if (!b || !player || player->unit_index != r->biped || player->quit_out_of_game ||
			TEST_FLAG(b->object.damage_flags, _object_dead_bit)) {
			if (b) restore_rider(r, b);
			r->state = EMPTY;
			continue;
		}
		++active;
		if (r->state == FALLING) {
			steer(r, b);
			continue;
		}
		if (flight.tick >= LAST_JUMP || r->requested ||
			(!ship && !flight.fixture)) {
			release(r, b, position, slot);
		} else if (r->overflow) {
			real_point3d cabin = position;
			cabin.x -= flight.heading.i * 1.5f;
			cabin.y -= flight.heading.j * 1.5f;
			object_set_position(r->biped, &cabin, NULL, NULL);
			b->object.translational_velocity = *global_zero_vector3d;
		}
	}
	if (!active && flight.tick >= EXIT_TICK)
		flight.enabled = FALSE;
	++flight.tick;
}
