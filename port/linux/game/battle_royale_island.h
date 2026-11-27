#ifndef BATTLE_ROYALE_ISLAND_H
#define BATTLE_ROYALE_ISLAND_H
#include "battle_royale_scatter.h"

boolean battle_royale_island_active(void);
void battle_royale_island_reset(void);
void battle_royale_island_begin(const struct br_drop_route *route, const struct br_drop_pool *pool, uint32_t seed);
void battle_royale_island_landed(void);
void battle_royale_island_tick(void);
void battle_royale_island_draw_edge(const real_point3d *eye);
boolean battle_royale_island_bot_goal(const real_point3d *position, real_point3d *goal);
#endif
