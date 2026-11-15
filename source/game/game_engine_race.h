/*
GAME_ENGINE_RACE.H
*/

#ifndef __GAME_ENGINE_RACE_H
#define __GAME_ENGINE_RACE_H
#pragma once

/* ---------- headers */

#include "game/game_engine.h"

/* ---------- structures */

struct race_globals;

/* ---------- prototypes/GAME_ENGINE_RACE.C */

void race_flags_make_unique(
	void);

/* Read-only, authoritative checkpoint choices for a bot player.
 * Uses the same race_goal_matches_player scoring eligibility as humans.
 * No checkpoint index is invented or marked complete here. */
unsigned long race_engine_bot_eligible_goals(long player_index);

/* ---------- globals */

extern struct game_engine race_engine;

#endif // __GAME_ENGINE_RACE_H
