#ifndef HALO_LAUNCHER_LAN_H
#define HALO_LAUNCHER_LAN_H
/* Android's one-match ticket. Other games keep the normal System Link path. */
struct network_game;
struct network_player;
boolean launcher_lan_machine_name(wchar_t *name);
boolean launcher_lan_admit(struct network_game *game, struct network_player *player);
boolean launcher_lan_countdown_allowed(struct network_game *game);
short launcher_lan_bot_team(long bot);
#endif
