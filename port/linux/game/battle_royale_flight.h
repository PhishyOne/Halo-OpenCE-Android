#ifndef BATTLE_ROYALE_FLIGHT_H
#define BATTLE_ROYALE_FLIGHT_H

void battle_royale_flight_reset(void);
void battle_royale_flight_tick(void);
struct player_action;
void battle_royale_cabin_controls(long player, struct player_action *action);

#endif
