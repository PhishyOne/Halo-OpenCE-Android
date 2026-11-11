/* Host-only, transient human killstreak rewards. Private presentation is
 * sent by killstreak_recon.c; powerups use normal engine replication. */
#ifndef HALO_KILLSTREAK_REWARDS_H
#define HALO_KILLSTREAK_REWARDS_H

void killstreak_rewards_start(void);
void killstreak_rewards_stop(void);
void killstreak_rewards_update(void);
void killstreak_rewards_reset_player(long player_index);
void killstreak_rewards_record_kill(long credited_player_index, long dead_player_index);
void killstreak_rewards_powerup(long player_index, short powerup_type);

#endif
