#ifndef HALO_VIP_POLICY_H
#define HALO_VIP_POLICY_H
#include "network_vip.h"

enum { VIP_WAITING, VIP_ACTIVE, VIP_INTERMISSION, VIP_FINISHED };
enum { VIP_NONE, VIP_EXTRACTED, VIP_LOST, VIP_TIMEOUT, VIP_DEFENDERS_LEFT, VIP_ABORTED };
enum { VIP_ROUND_TICKS=5400, VIP_STAGE_TICKS=90, VIP_WAIT_LIMIT=900 };

/* The same policy is used by the game and the native fixture. The VIP's
 * death takes precedence over extraction on the same simulation tick. */
static int vip_round_outcome(int living, int arrived, int defenders, int elapsed)
{
    if (!living) return VIP_LOST;
    if (arrived) return VIP_EXTRACTED;
    if (!defenders) return VIP_DEFENDERS_LEFT;
    if (elapsed >= VIP_ROUND_TICKS) return VIP_TIMEOUT;
    return VIP_NONE;
}

static int vip_state_valid(const struct distributed_vip_state *s, int game,
                           int minimum_revision, int maximum_players)
{
    int total;
    if (s->game_number!=game || s->revision<minimum_revision || s->revision<0 ||
        s->phase<VIP_WAITING || s->phase>VIP_FINISHED ||
        s->round_index<0 || s->round_index>1 || s->escort_team<0 || s->escort_team>1 ||
        s->escort_team!=((game+s->round_index)&1) ||
        s->phase_tick<0 || s->phase_tick>2592000 ||
        s->score[0]<0 || s->score[0]>2 || s->score[1]<0 || s->score[1]>2 ||
        s->reason<VIP_NONE || s->reason>VIP_ABORTED) return 0;
    total=s->score[0]+s->score[1];
    if (s->phase==VIP_ACTIVE)
        return s->vip_slot>=0 && s->vip_slot<maximum_players && s->vip_identity!=0 &&
            s->reason==VIP_NONE && total==s->round_index;
    if (s->vip_slot!=-1 || s->vip_identity!=0) return 0;
    if (s->phase==VIP_WAITING) return s->reason==VIP_NONE && total==s->round_index;
    if (s->phase==VIP_INTERMISSION)
        return s->round_index==0 && total==1 && s->reason>=VIP_EXTRACTED && s->reason<=VIP_DEFENDERS_LEFT;
    return s->reason==VIP_ABORTED ? total<=1 :
        s->round_index==1 && total==2 && s->reason>=VIP_EXTRACTED && s->reason<=VIP_DEFENDERS_LEFT;
}

static int vip_start_round(struct distributed_vip_state *s, int tick, int slot, uint32_t identity)
{
    if (s->phase!=VIP_WAITING || slot<0 || !identity) return 0;
    s->phase=VIP_ACTIVE; s->phase_tick=tick; s->vip_slot=slot; s->vip_identity=identity;
    s->reason=VIP_NONE; s->revision++;
    return 1;
}

static int vip_end_round(struct distributed_vip_state *s,int tick,int reason)
{
    int winning;
    if(s->phase!=VIP_ACTIVE || reason<VIP_EXTRACTED || reason>VIP_DEFENDERS_LEFT) return 0;
    winning=(reason==VIP_EXTRACTED||reason==VIP_DEFENDERS_LEFT)?s->escort_team:1-s->escort_team;
    s->score[winning]++;
    s->phase=s->round_index==0?VIP_INTERMISSION:VIP_FINISHED;
    s->reason=reason; s->phase_tick=tick; s->vip_slot=-1; s->vip_identity=0; s->revision++;
    return 1;
}

static int vip_next_round(struct distributed_vip_state *s,int tick)
{
    if(s->phase!=VIP_INTERMISSION||s->round_index!=0) return 0;
    s->round_index=1; s->escort_team=1-s->escort_team; s->phase=VIP_WAITING;
    s->phase_tick=tick; s->reason=VIP_NONE; s->revision++;
    return 1;
}

static void vip_abort(struct distributed_vip_state *s,int tick)
{
    if(s->phase==VIP_FINISHED) return;
    s->phase=VIP_FINISHED;s->phase_tick=tick;s->vip_slot=-1;s->vip_identity=0;
    s->reason=VIP_ABORTED;s->revision++;
}
#endif
