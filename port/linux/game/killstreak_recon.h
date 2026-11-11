/* Private, transient presentation of host-earned rewards. */
#ifndef HALO_KILLSTREAK_RECON_H
#define HALO_KILLSTREAK_RECON_H

void killstreak_recon_reset(void);
void killstreak_recon_stop(void);
void killstreak_recon_reset_player(long player_index);
void killstreak_recon_award(long player_index, long unit_index, unsigned short award);
void killstreak_recon_revoke(long player_index, long unit_index);
void killstreak_recon_update(void);
int killstreak_recon_active(short local_player_index);
void killstreak_recon_present(short local_player_index);
boolean killstreak_recon_handles_message(word const *data, word size);
void killstreak_recon_handle_message(long machine_index, word const *data, word size);
/* Stock map clips, immediate and local; no shared multiplayer sound queue. */
void game_engine_play_killstreak_sound(short local_player_index, unsigned short award);

#endif
