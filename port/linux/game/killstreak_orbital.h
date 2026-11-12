/* Personal orbital strikes, independent of Commander and private recon. */
#ifndef HALO_KILLSTREAK_ORBITAL_H
#define HALO_KILLSTREAK_ORBITAL_H
void killstreak_orbital_start(void);
void killstreak_orbital_stop(void);
void killstreak_orbital_reset_player(long player_index);
void killstreak_orbital_award(long player_index, long unit_index);
void killstreak_orbital_update(void);
void killstreak_orbital_present(short local_player_index);
boolean killstreak_orbital_handles_message(word const *data, word size);
void killstreak_orbital_handle_message(long machine_index, word const *data, word size);
#endif
