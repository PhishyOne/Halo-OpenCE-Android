/* The sparse first Battle Royale footprint. The stock StageKit biped
   starts near the southeast corner, facing the eastern collision wall. */
#include "../stagekit.h"

static void royale_build(struct stage *stage)
{
	real_point3d start = { { ROYALE_HALF_WIDTH - 1.25f, -ROYALE_HALF_WIDTH + 3.f, 0.f } };
	player_start(stage, &start);
}

struct stagekit_scenario const scenario_royale_footprint = {
	"royale_footprint", "an 8 km square with a player at the southeast edge",
	royale_build, ROYALE_HALF_WIDTH };
