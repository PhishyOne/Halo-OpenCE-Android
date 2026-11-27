/* Read only the map's loot flags. Other named points remain available to scripts. */
#include "cseries.h"
#include "math/real_math.h"
#include "objects/objects.h"
#include "scenario/scenario.h"
#include "scenario/scenario_definitions.h"
#include "battle_royale_round.h"

void platform_log(char const *format, ...);

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


/* The authored scenario palette owns the set of stock pickups and their dependencies.
   Sampling is host-only. Native objects provide normal inventory, gravity and LAN state. */
void battle_royale_populate_pickups(struct scenario const *map)
{
    static struct battle_royale_round selection;
    struct tag_block const *palettes[] = {&map->weapon_palette, &map->equipment_palette};
    uint32_t items[BR_MAX_LOOT], seed;
    int count = 0, p, i, spawned = 0;
    for (p=0; p<NUMBEROF(palettes); ++p) {
        struct scenario_object_palette_entry const *entries = palettes[p]->address;
        for (i=0; i<palettes[p]->count && count<BR_MAX_LOOT; ++i) {
            long handle = entries[i].reference.index;
            if (handle != NONE && handle != 0) items[count++] = (uint32_t)handle;
        }
    }
    if (!count) return;
    seed = (uint32_t)local_random() << 16;
    seed |= local_random();
    memset(&selection, 0, sizeof(selection));
    selection.phase = BR_PLAYING;
    selection.last_tick = -1;
    if (!battle_royale_seed_scenario_loot(&selection, map, items, count, seed)) return;
    for (i=0; i<selection.loot_count; ++i) {
        struct battle_royale_pickup const *pickup = &selection.loot[i];
        struct object_placement_data placement;
        object_placement_data_new(&placement, (long)pickup->weapon, NONE);
        placement.position = (real_point3d){{pickup->position.x, pickup->position.y, pickup->position.z+.035f}};
        if (object_new(&placement) != NONE) ++spawned;
    }
    platform_log("Halo Royale: spawned %d of %d native pickups", spawned, selection.loot_count);
}
