/* Android's local, offline career ledger bridge. This never changes Halo's
 * network protocol or player_datum layout. */
#ifndef HALO_CAREER_RECORD_H
#define HALO_CAREER_RECORD_H

void career_record_start(void);
/* Only call after the engine has credited an enemy player kill. */
void career_record_kill(short local_slot, int bot_victim, long current_streak);
/* Once after a real multiplayer game's postgame screen becomes visible. */
void career_record_postgame(long game_type, const char *map_name);

#endif
