#ifndef HALO_VIP_H
#define HALO_VIP_H

enum { VIP_WINS = 3, VIP_ROUNDS = 2 * VIP_WINS - 1 };

struct game_engine;
union real_point3d;
boolean vip_enabled(void);
struct game_engine *vip_engine_get(struct game_engine const *slayer);
boolean vip_player_is_vip(long player_index);
boolean vip_unit_is_vip(long unit_index);
boolean vip_staging(void);
boolean vip_allow_passenger(long unit_index,long vehicle_index,short seat_index);
boolean vip_bot_goal(long player_index,union real_point3d *point);
void vip_format_title(long player_index,wchar_t *title);
long vip_write_network_state(byte *buffer,long size);
boolean vip_read_network_state(byte const *buffer,long size,boolean first);
#endif
