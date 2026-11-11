/* Opt-in 2142-style commander and support features.
 * The host decides the effective feature mask for all players. */
#ifndef HALO_COMMANDER_FEATURES_H
#define HALO_COMMANDER_FEATURES_H

#include "commander_feature_policy.h"

unsigned int commander_features_mask(void);
int commander_feature_allowed(unsigned int feature);
int killstreak_feature_allowed(unsigned int feature);
void commander_features_new_game(void);
void commander_features_update(void);
void commander_features_send_to_machine(long machine_index);
boolean commander_features_handles_message(word const *data, word size);
void commander_features_handle_message(long machine_index, word const *data, word size);

#endif
