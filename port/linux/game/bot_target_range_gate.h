#ifndef HALO_BOT_TARGET_RANGE_GATE_H
#define HALO_BOT_TARGET_RANGE_GATE_H

/*
 * Cheap conservative rejection before a bot's sqrt/atan2 target scoring.
 * A point whose x/y/z displacement exceeds the reach on any axis cannot
 * be in the spherical sight radius. The small margin protects against
 * float-rounding right at the boundary. Surviving points still use the
 * original distance and facing tests, unchanged.
 *
 * The game provides finite map coordinates and positive skill sight ranges.
 * NaN coordinates are deliberately NOT rejected by these comparisons so
 * the original game's nonfinite-score fallback remains authoritative.
 */
static int halo_bot_target_outside_reach_box(
    float dx, float dy, float dz, float reach)
{
    float limit = reach + 0.0625f;

    return dx > limit || dx < -limit ||
        dy > limit || dy < -limit ||
        dz > limit || dz < -limit;
}
#endif
