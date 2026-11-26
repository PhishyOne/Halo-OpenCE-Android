#include "battle_royale_round.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#define SEATS HALO_PORT_MAXIMUM_NETWORK_PLAYERS

static int run_tick(struct battle_royale_round *round, int tick,
	const struct battle_royale_observation seats[SEATS], unsigned char due[SEATS])
{
	if (!battle_royale_open_tick(round, tick, seats, due))
		return 0;
	return battle_royale_finish_tick(round, tick, seats);
}

static void ring_and_lives(void)
{
	struct battle_royale_zone zone = {{0,0,100}, {20,0,20}, 30,60,10};
	struct battle_royale_round round = {0};
	struct battle_royale_observation seats[SEATS] = {{0}};
	uint32_t ids[SEATS] = {0};
	unsigned char due[SEATS];
	const int last = SEATS - 1;
	ids[0] = 11; ids[5] = 22; ids[last] = 33;
	assert(battle_royale_begin(&round, &zone, ids));
	seats[0] = (struct battle_royale_observation){11, BR_POSITION, 100, 0};
	seats[5] = (struct battle_royale_observation){22, BR_POSITION, 100.25f, 0};
	seats[last] = (struct battle_royale_observation){33, BR_POSITION, 20, 0};
	assert(run_tick(&round, 0, seats, due) && !due[5]);
	assert(run_tick(&round, 10, seats, due) && !due[0] && due[5] && !due[last]);
	assert(!run_tick(&round, 10, seats, due) && !due[5]);
	assert(!run_tick(&round, 9, seats, due) && round.last_tick == 10);
	seats[5].kind = BR_UNOBSERVED;
	run_tick(&round, 30, seats, due);
	assert(round.circle.radius == 100 && round.remaining == 3 && !due[5]);
	seats[5].kind = BR_OUT; seats[5].identity = 99;
	run_tick(&round, 60, seats, due);
	assert(round.circle.radius == 60 && round.circle.x == 10 && due[0] && round.remaining == 3);
	seats[5].identity = 22;
	run_tick(&round, 61, seats, due);
	assert(!battle_royale_alive(&round, 5, 22) && round.remaining == 2);
	seats[5] = (struct battle_royale_observation){99, BR_POSITION, 900, 0};
	run_tick(&round, 90, seats, due);
	assert(round.circle.radius == 20 && round.circle.x == 20 && round.remaining == 2 && !due[5]);
	assert(!battle_royale_alive(&round, 5, 99));
	seats[5].identity = 22;
	seats[last].kind = BR_OUT;
	run_tick(&round, 91, seats, due);
	assert(round.phase == BR_WON && round.winner_seat == 0 && round.remaining == 1 && !due[0]);
	seats[0].kind = BR_OUT;
	assert(!run_tick(&round, 92, seats, due) && round.phase == BR_WON);
}

static void simultaneous_outs_and_invalid_setup(void)
{
	struct battle_royale_zone zone = {{0,0,200}, {0,0,0}, 0,30,5};
	struct battle_royale_round round = {0};
	struct battle_royale_observation seats[SEATS] = {{0}};
	uint32_t ids[SEATS] = {41,42};
	unsigned char due[SEATS];
	assert(battle_royale_begin(&round, &zone, ids));
	ids[1] = ids[0];
	assert(!battle_royale_begin(&round, &zone, ids));
	ids[1] = 0;
	assert(!battle_royale_begin(&round, &zone, ids));
	ids[1] = 42;
	zone.end.x = 201;
	assert(!battle_royale_begin(&round, &zone, ids));
	zone.end.x = NAN;
	assert(!battle_royale_begin(&round, &zone, ids));
	assert(battle_royale_alive(&round, 1, 42));
	assert(!battle_royale_alive(&round, -1, 41) && !battle_royale_alive(&round, SEATS, 42));
	seats[0] = (struct battle_royale_observation){41,BR_POSITION,10,0};
	seats[1] = (struct battle_royale_observation){42,BR_OUT,0,0};
	assert(battle_royale_open_tick(&round, 31, seats, due) && due[0] && !due[1]);
	/* One loss from combat and one from lethal zone damage in the same tick. */
	seats[0].kind = BR_OUT;
	assert(!battle_royale_finish_tick(&round, 30, seats));
	assert(!battle_royale_open_tick(&round, 32, seats, due));
	assert(battle_royale_finish_tick(&round, 31, seats) && !battle_royale_finish_tick(&round, 31, seats));
	assert(round.phase == BR_DRAW && round.winner_seat == -1);
}

/* Synthetic host observations in world units across an 8 km square. This
   exercises rules at map scale; positions and health here are test inputs. */
static void sixty_four_entrants(void)
{
	const float half_width = 4000.f / 3.048f;
	struct battle_royale_zone zone = {{0,0,half_width}, {0,0,4}, 30,180,30};
	struct battle_royale_round round = {0};
	struct battle_royale_observation seats[SEATS] = {{0}};
	uint32_t ids[SEATS] = {0};
	unsigned char due[SEATS];
	int hits[64] = {0};
	int i, tick;
	for (i = 0; i < 64; ++i)
	{
		ids[i] = (uint32_t)(4000+i);
		seats[i].identity = ids[i];
		seats[i].kind = BR_POSITION;
		seats[i].x = (i%8 - 3.5f) * (2*half_width/7);
		seats[i].y = (i/8 - 3.5f) * (2*half_width/7);
	}
	seats[42].x = seats[42].y = 0;
	assert(battle_royale_begin(&round, &zone, ids));
	for (tick = 0; tick <= 450 && round.phase == BR_PLAYING; ++tick)
	{
		assert(battle_royale_open_tick(&round, tick, seats, due));
		for (i = 0; i < 64; ++i)
			if (due[i] && ++hits[i] == 3)
				seats[i].kind = BR_OUT;
		assert(battle_royale_finish_tick(&round, tick, seats));
	}
	assert(round.phase == BR_WON && round.remaining == 1 && round.winner_seat == 42 && hits[42] == 0);
	for (i = 0; i < 64; ++i)
		assert(battle_royale_alive(&round, i, ids[i]) == (i == 42));
	printf("64 simulated entrants, 63 eliminated; seat %d won at tick %d.\n", round.winner_seat, round.last_tick);
}

static void unarmed_loot(void)
{
	const struct battle_royale_zone zone = {{0,0,100}, {0,0,10}, 15,90,10};
	struct battle_royale_round a, b;
	struct battle_royale_point sites[8], raised;
	const uint32_t weapons[] = { 0xe1120001, 0xe1120002, 0xe1120003 };
	uint32_t ids[SEATS] = {11,22,33};
	struct battle_royale_observation seats[SEATS] = {{0}};
	unsigned char due[SEATS];
	uint32_t weapon;
	int i, j;
	for (i = 0; i < 8; ++i)
		sites[i] = (struct battle_royale_point){(float)i*2, 4, 1};
	assert(battle_royale_begin(&a, &zone, ids));
	assert(battle_royale_begin(&b, &zone, ids));
	for (i = 0; i < SEATS; ++i)
		assert(!a.weapons[i][0] && !a.weapons[i][1]);
	sites[7] = sites[6];
	assert(!battle_royale_place_loot(&a, sites, 8, weapons, 3, 5, 77) && !a.loot_count);
	sites[7].x = 14;
	assert(battle_royale_place_loot(&a, sites, 8, weapons, 3, 5, 77));
	assert(battle_royale_place_loot(&b, sites, 8, weapons, 3, 5, 77));
	assert(!memcmp(a.loot, b.loot, sizeof(a.loot)));
	for (i = 0; i < a.loot_count; ++i)
		for (j = 0; j < i; ++j)
			assert(a.loot[i].position.x != a.loot[j].position.x);
	assert(!battle_royale_place_loot(&a, sites, 8, weapons, 3, 5, 12));
	raised = a.loot[0].position;
	raised.z += 1;
	assert(!battle_royale_take_loot(&a, 0, 11, raised, 0));
	assert(battle_royale_nearest_loot(&a, 0, 11, raised, 0.5f) == -1);
	assert(battle_royale_nearest_loot(&a, 0, 11, raised, 1.0f) == 0);
	assert(!battle_royale_take_loot(&a, 0, 999, a.loot[0].position, 0));
	weapon = a.loot[0].weapon;
	assert(battle_royale_take_loot(&a, 0, 11, a.loot[0].position, 0) == weapon);
	assert(a.weapons[0][0] == weapon && !a.weapons[0][1]);
	assert(!battle_royale_take_loot(&a, 1, 22, a.loot[0].position, 0));
	assert(battle_royale_take_loot(&a, 0, 11, a.loot[1].position, 1));
	assert(!battle_royale_take_loot(&a, 0, 11, a.loot[2].position, 2));
	seats[1] = (struct battle_royale_observation){22, BR_OUT, 0, 0};
	assert(run_tick(&a, 0, seats, due));
	assert(!battle_royale_take_loot(&a, 1, 22, a.loot[2].position, 2));
	assert(!battle_royale_take_loot(&a, 1, 222, a.loot[2].position, 2));
	assert(battle_royale_take_loot(&a, 2, 33, a.loot[2].position, 2));
	assert(battle_royale_begin(&a, &zone, ids) && !a.weapons[0][0] && !a.loot_count);
	assert(run_tick(&a, 0, seats, due));
	assert(!battle_royale_place_loot(&a, sites, 8, weapons, 3, 5, 12));
	puts("Unarmed starts, seeded loot, 3D reach, contested pickups and eliminated entrants passed.");
}

int main(void)
{
	unarmed_loot();
	ring_and_lives();
	simultaneous_outs_and_invalid_setup();
	sixty_four_entrants();
	puts("Battle Royale host round checks passed.");
	return 0;
}
