/* Offline East Millworks uses Halo's own health, shields and one-life Slayer adjudication.
   The HUD is a read-only projection of the same circles that cause environmental damage. */
#include "cseries.h"
#include "math/real_math.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/game_globals.h"
#include "rasterizer/rasterizer.h"
#include "game/players.h"
#include "objects/objects.h"
#include "objects/damage.h"
#include "objects/damage_effect_definitions.h"
#include "units/units.h"
#include "battle_royale_island.h"
#include "battle_royale_storm.h"
#include "network_chat.h"
#include <math.h>

void platform_log(const char *format, ...);
const char *config_string(const char *name);
#ifdef HALO_ANDROID
void host_royale_map_publish(const float *values);
#endif

static struct {
	struct br_drop_route route;
	struct battle_royale_circle island;
	struct battle_royale_point finish;
	struct br_storm_view storm;
	long damage_tag;
	int elapsed, ticks;
	boolean active, landed, elimination, trace;
} island;

void battle_royale_island_reset(void)
{
	memset(&island, 0, sizeof(island));
#ifdef HALO_ANDROID
	host_royale_map_publish(NULL);
#endif
}

void battle_royale_island_begin(const struct br_drop_route *route, const struct br_drop_pool *pool, uint32_t seed)
{
	struct game_variant *variant = game_engine_get_variant();
	float half_x = (route->upper.x - route->lower.x) * .5f;
	float half_y = (route->upper.y - route->lower.y) * .5f;
	int i, candidates = 0;
	if (game_connection() != _game_connection_local || !pool->site_count || !(half_x > 0 && half_y > 0))
		return;
	island.route = *route;
	island.island.x = (route->upper.x + route->lower.x) * .5f;
	island.island.y = (route->upper.y + route->lower.y) * .5f;
	island.island.radius = square_root(half_x*half_x + half_y*half_y) + 1.f;
	seed ^= 0x41c6ce57u;
	if (!seed) seed = 1;
	for (i = 0; i < pool->site_count; ++i) {
		const struct battle_royale_point *p = &pool->sites[i];
		if (!(p->x >= route->lower.x && p->x <= route->upper.x && p->y >= route->lower.y && p->y <= route->upper.y))
			continue;
		if (battle_royale_random(&seed) % ++candidates == 0)
			island.finish = *p;
	}
	if (!candidates) return;
	island.active = TRUE;
	island.elimination = variant && variant->game_engine_index == game_engine_slayer &&
		!variant->universal_variant.teams && variant->universal_variant.lives == 1;
	island.damage_tag = tag_loaded(DAMAGE_EFFECT_DEFINITION_TAG, "globals\\falling");
	island.trace = *config_string("debug.network_test") && strcmp(config_string("debug.network_test"), "off");
	br_storm_sample(&island.island, &island.finish, 0, &island.storm);
	island.storm.state = island.elimination ? BR_INSERTION : BR_EXPLORING;
	platform_log("Halo Royale: offline map active; storm %d, finish %.2f %.2f, damage tag %08lx",
		island.elimination, island.finish.x, island.finish.y, (unsigned long)island.damage_tag);
}

void battle_royale_island_landed(void) { island.landed = TRUE; }

static void storm_damage_players(void)
{
	struct data_iterator it;
	struct player_datum *p;
	const struct damage_definition *definition;
	real base;
	if (island.damage_tag == NONE) return;
	definition = &damage_effect_definition_get(island.damage_tag)->damage;
	base = (definition->damage_lower_bound + definition->damage_upper_bound) * .5f;
	if (!(base > 0)) return;
	data_iterator_new(&it, player_data);
	while ((p = data_iterator_next(&it))) {
		struct unit_datum *unit = p->unit_index != NONE ? unit_try_and_get(p->unit_index) : NULL;
		real_point3d pos;
		struct damage_data damage;
		long id = p->unit_index;
		if (!unit || p->quit_out_of_game || TEST_FLAG(unit->object.damage_flags, _object_dead_bit)) continue;
		object_get_origin(id, &pos);
		if (!battle_royale_outside(&island.storm.current, pos.x, pos.y)) continue;
		damage_data_new(&damage, island.damage_tag);
		damage.origin = damage.epicenter = pos;
		damage.multiplier = island.storm.damage / base;
		object_cause_damage(&damage, id, NONE, NONE, NONE, NULL);
		if (island.trace) {
			unit = unit_try_and_get(id);
			platform_log("Halo Royale: storm hit seat %d stage %d at %d vitality %.3f/%.3f",
				DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index), island.storm.stage, island.elapsed,
				unit ? unit->object.body_vitality : 0.f, unit ? unit->object.shield_vitality : 0.f);
		}
	}
}

#ifdef HALO_ANDROID
static void publish_map(void)
{
	long id = local_player_get_player_index(0);
	struct player_datum *p = id == NONE ? NULL : player_try_and_get(id);
	struct unit_datum *u = p && p->unit_index != NONE ? unit_try_and_get(p->unit_index) : NULL;
	struct br_storm_view *s = &island.storm;
	real_point3d pos;
	float values[22];
	if (!u || TEST_FLAG(u->object.damage_flags, _object_dead_bit)) {
		host_royale_map_publish(NULL);
		return;
	}
	object_get_origin(p->unit_index, &pos);
	values[0] = (float)s->state; values[1] = (float)s->stage; values[2] = (float)s->seconds_left;
	values[3] = pos.x; values[4] = pos.y;
	values[5] = u->unit.aiming_vector.i; values[6] = u->unit.aiming_vector.j;
	values[7] = island.route.lower.x; values[8] = island.route.lower.y;
	values[9] = island.route.upper.x; values[10] = island.route.upper.y;
	values[11] = s->current.x; values[12] = s->current.y; values[13] = s->current.radius;
	values[14] = s->next.x; values[15] = s->next.y; values[16] = s->next.radius;
	values[17] = !island.landed;
	values[18] = island.route.start.x; values[19] = island.route.start.y;
	values[20] = island.route.end.x; values[21] = island.route.end.y;
	host_royale_map_publish(values);
}
#endif

void battle_royale_island_tick(void)
{
	if (!island.active) return;
	if (!game_engine_in_play()) {
		battle_royale_island_reset();
		return;
	}
	if (island.landed && island.elimination) {
		enum br_storm_state old = island.storm.state;
		br_storm_sample(&island.island, &island.finish, island.elapsed, &island.storm);
		if (old != island.storm.state) {
			char msg[96];
			if (island.storm.state == BR_HOLDING)
				sprintf(msg, "Zone %d closes in %d seconds.", island.storm.stage, island.storm.seconds_left);
			else if (island.storm.state == BR_CLOSING)
				sprintf(msg, "Zone %d is closing.", island.storm.stage);
			else strcpy(msg, "Final zone closed.");
			network_chat_host_notice(msg);
			platform_log("Halo Royale: %s At storm tick %d.", msg, island.elapsed);
		}
		if (island.elapsed && island.elapsed % 30 == 0) storm_damage_players();
		if (island.elapsed < 2000000000) ++island.elapsed;
	}
#ifdef HALO_ANDROID
	if (island.ticks % 3 == 0) publish_map();
#endif
	island.ticks = (island.ticks + 1) % 30;
}

/* On foot, rotate into the marked next circle ahead of the storm. Leave room
   for combat and looting once safely inside. The goal stays steady for pathfinding. */
boolean battle_royale_island_bot_goal(const real_point3d *position, real_point3d *goal)
{
	struct battle_royale_circle target;
	if (!island.active || !island.elimination || !island.landed) return FALSE;
	target = island.storm.next;
	target.radius *= .90f;
	if (!battle_royale_outside(&target, position->x, position->y)) return FALSE;
	goal->x = target.x; goal->y = target.y; goal->z = island.finish.z + 1.f;
	return TRUE;
}

/* Only the arc near this camera needs drawing on the flat island. Vertices are
   on the same horizontal circle used for exposure. The world's depth buffer
   keeps buildings and the shelter between the player and the boundary. */
void battle_royale_island_draw_edge(const real_point3d *eye)
{
	const struct battle_royale_circle *c = &island.storm.current;
	const real_rgb_color blue = {{.22f, .78f, 1.f}};
	real dx, dy, distance, center, half_arc;
	real_point3d low, high;
	int i;
	if (!island.active || !island.elimination || !island.landed || c->radius < .01f) return;
	dx = eye->x - c->x; dy = eye->y - c->y;
	distance = square_root(dx*dx + dy*dy);
	if (fabs(distance - c->radius) > 65.f) return;
	center = (real)atan2(dy, dx);
	half_arc = MIN(3.141593f, 130.f / c->radius);
	rasterizer_debug_immediate_begin();
	for (i = 0; i <= 20; ++i) {
		real a = center + half_arc * (i / 10.f - 1.f);
		real_point3d foot, top;
		foot.x = c->x + c->radius * (real)cos(a);
		foot.y = c->y + c->radius * (real)sin(a);
		foot.z = island.finish.z + .25f;
		top = foot; top.z += 5.f;
		if (i) {
			rasterizer_debug_immediate_line(&low, &foot, &blue, NULL);
			rasterizer_debug_immediate_line(&high, &top, &blue, NULL);
		}
		if (!(i % 4)) rasterizer_debug_immediate_line(&foot, &top, &blue, NULL);
		low = foot; high = top;
	}
	rasterizer_debug_immediate_end();
}
