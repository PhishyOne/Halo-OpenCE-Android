/* Original Halo effect tags, no new maps or particle assets required. */
#ifndef HALO_KILLSTREAK_BREACH_VFX_H
#define HALO_KILLSTREAK_BREACH_VFX_H

/* Called only by the game thread on a host-approved indoor detonation.
 * Never causes damage or changes player state. */
void killstreak_breach_vfx_spawn(real_point3d const *world_position);

#endif
