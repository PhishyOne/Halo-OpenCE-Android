/* The host chooses Echo 419's crossing, moves the craft and releases each original biped. */
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
#include "battle_royale_scatter.h"
#include "bots.h"

char const *config_string(char const *name);
void platform_log(char const *format, ...);

enum { POI_LEAD = 150 };
enum rider_state { EMPTY, ABOARD, FALLING };

struct rider {
	long player, biped;
	enum rider_state state;
	real_point3d target;
	int release_tick; /* zero for a human or a rider without a reachable clearing */
	boolean overflow, was_visible, had_collisions, had_safe_fall, jump_held, requested;
};

static struct {
	real_point3d entry, exit;
	real_vector3d heading;
	long definition, ship;
	int tick, poi_tick;
	real_point3d orchard;
	struct br_drop_route route;
	struct br_drop_pool drops;
	uint32_t fixture_seed;
	boolean has_orchard, guide_fixture, populate_pickups;
	boolean enabled, started, fixture, route_selected;
	struct rider riders[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
} flight;

static struct battle_royale_point drop_point(real_point3d const *position)
{
	struct battle_royale_point point = {position->x, position->y, position->z};
	return point;
}

void battle_royale_flight_reset(void)
{
	struct scenario *map = global_scenario_try_and_get();
	int starts = 0, ends = 0;
	long i;
	char const *input;
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
		} else if (!strncmp(flag->name, "br_land_", 8) && flight.drops.site_count < BR_MAX_CLEARINGS) {
			flight.drops.sites[flight.drops.site_count++] = drop_point(&flag->position);
		} else if (!strncmp(flag->name, "br_poi_orchard_crossing", sizeof(flag->name))) {
			flight.orchard = flag->position;
			flight.has_orchard = TRUE;
		}
	}
	if (starts != 1 || ends != 1)
		return;
	flight.populate_pickups = flight.has_orchard;
	flight.heading.i = flight.exit.x - flight.entry.x;
	flight.heading.j = flight.exit.y - flight.entry.y;
	if (!(normalize3d(&flight.heading) > 0))
		return;
	flight.route.start = drop_point(&flight.entry);
	flight.route.end = drop_point(&flight.exit);
	flight.route.forward_x = flight.heading.i;
	flight.route.forward_y = flight.heading.j;
	flight.definition = tag_loaded(VEHICLE_DEFINITION_TAG, "vehicles\\pelican\\pelican");
	/* Asset-free native test maps can exercise player physics in log mode. */
	if (!strcmp(config_string("debug.network_test"), "log")) {
		input = config_string("debug.test_input");
		flight.guide_fixture = !strcmp(input, "echo419-guide") || !strncmp(input, "echo419-guide:", 14);
		flight.fixture_seed = !strncmp(input, "echo419-guide:", 14) ? (uint32_t)strtoul(input+14, NULL, 10) : 1u;
		flight.fixture = flight.guide_fixture || !strcmp(input, "echo419-jump") || !strcmp(input, "echo419-wait");
	}
	flight.enabled = flight.definition != NONE || flight.fixture;
}

static void select_flight(struct structure_bsp const *bsp)
{
	uint32_t seed;
	flight.route.lower = (struct battle_royale_point){bsp->world_bounds.x0, bsp->world_bounds.y0, bsp->world_bounds.z0};
	flight.route.upper = (struct battle_royale_point){bsp->world_bounds.x1, bsp->world_bounds.y1, bsp->world_bounds.z1};
	/* Offline games can reuse the same map seed. Take fresh host-local entropy
	   once per successful boarding, then isolate the entire manifest's draws. */
	if (flight.fixture) {
		seed = flight.fixture_seed;
	} else {
		seed = (uint32_t)local_random() << 16;
		seed |= local_random();
	}
	if (!br_choose_route(&flight.route, &flight.drops, seed))
		platform_log("Echo 419: using authored fallback route");
	flight.entry = (real_point3d){{flight.route.start.x, flight.route.start.y, flight.route.start.z}};
	flight.exit = (real_point3d){{flight.route.end.x, flight.route.end.y, flight.route.end.z}};
	flight.heading.i = flight.route.forward_x;
	flight.heading.j = flight.route.forward_y;
	if (flight.has_orchard && point_in_rectangle3d(&flight.orchard, &bsp->world_bounds)) {
		real across = (flight.orchard.y-flight.entry.y)*flight.heading.i -
			(flight.orchard.x-flight.entry.x)*flight.heading.j;
		int pass = br_pass_tick(&flight.route, drop_point(&flight.orchard), 0);
		if (pass >= BR_DROP_OPEN + POI_LEAD && across > -60.f && across < 60.f)
			flight.poi_tick = pass - POI_LEAD;
	}
	br_drop_shuffle_start(&flight.drops, seed);
	platform_log("Echo 419: drop seed %u", (unsigned)seed);
	platform_log("Echo 419: route %.3f %.3f to %.3f %.3f", flight.entry.x, flight.entry.y, flight.exit.x, flight.exit.y);
	flight.route_selected = TRUE;
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
	if (!flight.route_selected)
		select_flight(bsp);
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
		if ((player->local_player_index == NONE && bots_machine_is_bot(player->network_player_data.machine_index)) ||
			flight.guide_fixture) {
			struct br_drop_plan plan;
			if (br_drop_choose(&flight.drops, &flight.route, DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index), &plan)) {
				r->release_tick = plan.tick;
				r->target = (real_point3d){{plan.target.x, plan.target.y, plan.target.z}};
				if (flight.guide_fixture)
					platform_log("Echo 419: planned clearing %d spot %d at %.4f %.4f", plan.clearing,
						plan.spot, r->target.x, r->target.y);
			}
		}
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
	if (r->player != player)
		return;
	if (r->state == FALLING && r->release_tick)
		action->throttle.i = action->throttle.j = 0;
	if (r->state != ABOARD)
		return;
	jump = TEST_FLAG(action->control_flags, _unit_control_jump_bit);
	if (!r->release_tick && flight.tick >= BR_DROP_OPEN && jump && !r->jump_held)
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
	real sideways = br_drop_side(slot);
	real aft = br_drop_aft(slot);
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

/* Supply the target velocity to Halo's airborne acceleration step so ordinary
   stick braking and bot guidance do not fight each other before collision. */
boolean battle_royale_guided_velocity(long biped_index, real_vector2d *desired)
{
	struct biped_datum *b;
	struct rider const *r;
	real dx, dy, distance, scale;
	long player;
	if (!flight.enabled || !flight.started)
		return FALSE;
	b = biped_get(biped_index);
	player = b->unit.player_index;
	if (player == NONE)
		return FALSE;
	r = &flight.riders[DATUM_INDEX_TO_ABSOLUTE_INDEX(player)];
	if (r->state != FALLING || !r->release_tick || r->player != player || r->biped != biped_index)
		return FALSE;
	dx = r->target.x - b->object.position.x;
	dy = r->target.y - b->object.position.y;
	distance = square_root(dx*dx + dy*dy);
	scale = .22f / MAX(1.25f, distance);
	desired->i = dx * scale;
	desired->j = dy * scale;
	return TRUE;
}

static void steer(struct rider *r, struct biped_datum *b)
{
	real_vector3d aim = b->unit.aiming_vector;
	if (!TEST_FLAG(b->biped.flags, _biped_airborne_bit)) {
		if (r->release_tick)
			platform_log("Echo 419: guided touchdown at %.3f %.3f %.3f", b->object.position.x,
				b->object.position.y, b->object.position.z);
		restore_rider(r, b);
		platform_log("Echo 419: landed at tick %d", flight.tick);
		return;
	}
	/* World units per tick. Use the normal biped collision for roofs,
	   terrain and obstacles; return all subsequent falls to normal rules. */
	aim.k = 0;
	if (!r->release_tick && normalize3d(&aim) > 0) {
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
	if (flight.populate_pickups && global_structure_bsp_index_get() != NONE) {
		flight.populate_pickups = FALSE;
		battle_royale_populate_pickups(global_scenario_try_and_get());
	}
	if (!flight.enabled)
		return;
	if (!flight.started) {
		static const char *const sides[] = {"west", "east", "south", "north"};
		char message[96];
		if (!board_roster())
			return;
		flight.started = TRUE;
		sprintf(message, "Halo Royale. Echo 419 inbound from the %s, exiting %s.",
			sides[br_route_edge(&flight.route, flight.route.start)], sides[br_route_edge(&flight.route, flight.route.end)]);
		cabin_notice(message);
	}
	if (flight.tick == BR_DROP_OPEN)
		cabin_notice("Drop zone open. Press Jump to leave Echo 419.");
	if (flight.poi_tick && flight.tick == flight.poi_tick) {
		cabin_notice("Echo 419: Orchard Crossing in five seconds.");
		platform_log("Echo 419: Orchard Crossing approach at tick %d", flight.tick);
	}
	t = MIN(flight.tick, BR_ROUTE_TICKS) / (real)BR_ROUTE_TICKS;
	position.x = flight.entry.x + (flight.exit.x-flight.entry.x)*t;
	position.y = flight.entry.y + (flight.exit.y-flight.entry.y)*t;
	position.z = flight.entry.z + (flight.exit.z-flight.entry.z)*t;
	ship = flight.ship != NONE ? object_try_and_get(flight.ship) : NULL;
	if (ship) {
		if (flight.tick < BR_ROUTE_TICKS) {
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
		if (flight.tick >= BR_LAST_JUMP || r->requested ||
			(r->release_tick && flight.tick >= r->release_tick) ||
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
	if (!active && flight.tick >= BR_ROUTE_TICKS)
		flight.enabled = FALSE;
	++flight.tick;
}
