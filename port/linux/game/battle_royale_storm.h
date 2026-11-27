#ifndef BATTLE_ROYALE_STORM_H
#define BATTLE_ROYALE_STORM_H
#include "battle_royale_round.h"

/* The Android map reads these phase values. All positions use horizontal Halo world units. */
enum br_storm_state { BR_MAP_OFF, BR_EXPLORING, BR_INSERTION, BR_HOLDING, BR_CLOSING, BR_FINAL };
struct br_storm_view {
	struct battle_royale_circle current, next;
	enum br_storm_state state;
	int stage, seconds_left, damage;
};

void br_storm_sample(const struct battle_royale_circle *island, const struct battle_royale_point *finish,
	int elapsed_ticks, struct br_storm_view *view);
#endif
