/* v26: borrow a stock effect from the *active map's* grenade projectile.
 * No new asset is embedded, and visual creation never inflicts damage.
 * Plasma detonation is preferred; fragmentation is a safe stock fallback.
 * Custom maps without a valid referenced effect simply retain HUD/audio.
 */
#include "cseries.h"
#include "effects/effects.h"
#include "effects/effect_definitions.h"
#include "game/game_globals.h"
#include "items/projectile_definitions.h"
#include "scenario/scenario.h"
#include "tag_files/tag_groups.h"
#include "units/units.h"
#include "killstreak_breach_vfx.h"

static long breach_grenade_detonation(short grenade_type)
{
    struct game_globals *globals = scenario_get_game_globals();
    struct game_globals_grenade *grenade;
    struct projectile_definition *projectile;

    if (!globals || grenade_type < 0 ||
        grenade_type >= globals->grenades.count ||
        !globals->grenades.address)
        return NONE;
    grenade = TAG_BLOCK_GET_ELEMENT(&globals->grenades, grenade_type,
        struct game_globals_grenade);
    if (!grenade || grenade->projectile.index == NONE ||
        grenade->projectile.group_tag != PROJECTILE_DEFINITION_TAG)
        return NONE;
    projectile = projectile_definition_get(grenade->projectile.index);
    if (!projectile ||
        projectile->projectile.effect.group_tag != EFFECT_DEFINITION_TAG ||
        projectile->projectile.effect.index == NONE)
        return NONE;
    return projectile->projectile.effect.index;
}

void killstreak_breach_vfx_spawn(real_point3d const *world_position)
{
    real_point3d points[2];
    real_vector3d directions[2];
    char const *markers[2] = { "", "gravity" };
    long effect_index;
    if (!world_position)
        return;

    effect_index = breach_grenade_detonation(_unit_grenade_covenant_plasma);
    if (effect_index == NONE)
        effect_index = breach_grenade_detonation(_unit_grenade_human_fragmentation);
    if (effect_index == NONE)
        return;

    points[0] = points[1] = *world_position;
    directions[0].i = 0.0f;
    directions[0].j = 0.0f;
    directions[0].k = 1.0f;
    directions[1].i = 0.0f;
    directions[1].j = 0.0f;
    directions[1].k = -1.0f;

    /* Halo's own projectile detonation spawns two effect markers the
     * same way. No projectile/damage object is created, and gameplay
     * attribution still belongs exclusively to the authoritative host. */
    effect_new_unattached_from_markers(effect_index, NONE, NULL, 2,
        markers, points, directions, 0.0f, 0.0f, NULL, NULL, TRUE);
}
