#ifndef HALO_NETWORK_VIP_H
#define HALO_NETWORK_VIP_H
#include <stdint.h>

/* Fixed-width game-state payload, separate from ordinary Slayer. */
struct distributed_vip_state
{
    int32_t game_number, revision, phase, round_index, escort_team;
    int32_t phase_tick, vip_slot;
    uint32_t vip_identity;
    int32_t score[2], reason;
};

typedef char verify_distributed_vip_state_size[sizeof(struct distributed_vip_state) == 44 ? 1 : -1];
#endif
