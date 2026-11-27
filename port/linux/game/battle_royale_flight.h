#ifndef BATTLE_ROYALE_FLIGHT_H
#define BATTLE_ROYALE_FLIGHT_H

boolean battle_royale_island_map(void);
void battle_royale_flight_reset(void);
void battle_royale_flight_tick(void);
union real_vector2d;
boolean battle_royale_guided_velocity(long biped_index, union real_vector2d *desired);
struct player_action;
void battle_royale_cabin_controls(long player, struct player_action *action);

#endif
