/* Read only the map's loot flags. Other named points remain available to scripts. */
#include "cseries.h"
#include "math/real_math.h"
#include "scenario/scenario_definitions.h"
#include "battle_royale_round.h"

int battle_royale_seed_scenario_loot(struct battle_royale_round *round, const struct scenario *scenario,
	const uint32_t *weapons, int weapon_count, uint32_t seed)
{
	struct battle_royale_loot_marker markers[BR_MAX_LOOT];
	int count = 0;
	long i;
	if (!scenario) return 0;
	for (i=0; i<scenario->cutscene_flags.count; ++i) {
		const struct scenario_cutscene_flag *flag = TAG_BLOCK_GET_ELEMENT(
			&scenario->cutscene_flags,i,struct scenario_cutscene_flag);
		if (strncmp(flag->name,"br_in_",6) && strncmp(flag->name,"br_fixed_",9) &&
			strncmp(flag->name,"br_wild_",8)) continue;
		if (count == BR_MAX_LOOT) return 0;
		memcpy(markers[count].name,flag->name,sizeof(markers[count].name));
		markers[count].point.x = flag->position.x;
		markers[count].point.y = flag->position.y;
		markers[count].point.z = flag->position.z;
		++count;
	}
	return battle_royale_place_marked_loot(round,markers,count,weapons,weapon_count,3,4,seed);
}
