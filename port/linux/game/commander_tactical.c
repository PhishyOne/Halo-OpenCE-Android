/* v22 Android COMMAND laptop proof of concept.
 * A read-only, host-first-player overview of active BSP and allied units.
 * No enemy intel, actions, UAV, commander role assignment or network packets.
 * Only the Android host's explicit on-screen MAP control toggles this.
 */
#include "cseries.h"
#include "commander_tactical.h"
#if defined(HALO_ANDROID)
#include "commander_tactical_policy.h"
#endif
#include "commander_features.h"
#include "bots.h"
#include "camera/director.h"
#include "cutscene/cinematics.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "interface/hud.h"
#include "main/main.h"
#include "memory/data.h"
#include "rasterizer/rasterizer.h"
#include "render/render.h"
#include "scenario/scenario.h"
#include "structures/structure_bsp_definitions.h"
#include "text/draw_string.h"
#include "units/units.h"

#define COMMANDER_TACTICAL_MAX_MARKERS 48

#if defined(HALO_ANDROID)
/* Android guest host imports, declared in port/android/guest/runtime/guest_host.h.
 * Never read Android view state directly across the guest/host boundary. */
int host_touch_commander_take_toggle(void);
void host_touch_commander_state(int status);
#endif

static boolean tactical_open;

void commander_tactical_reset(void)
{
    tactical_open = FALSE;
#if defined(HALO_ANDROID)
    host_touch_commander_state(0);
#endif
}

#if defined(HALO_ANDROID)
/* The host alone gets the prototype. Non-host clients NEVER use their
 * replicated enemy/player coordinates to invent an unearned scan. */
static boolean tactical_available(short local)
{
    long id;
    struct player_datum *player;
    int host = game_connection() == _game_connection_local ||
        game_connection() == _game_connection_network_server;

    if (!game_in_progress() || !game_engine_in_play() ||
        main_menu_is_active() || cinematic_in_progress() ||
        !game_engine_has_teams() || local != 0)
        return FALSE;
    id = local_player_get_player_index(local);
    player = id != NONE ? player_try_and_get(id) : NULL;
    return player && !player->quit_out_of_game &&
        commander_tactical_host_can_view(commander_features_mask(),
            game_engine_has_teams(), host, local,
            !bots_machine_is_bot(player->network_player_data.machine_index));
}

/* All positions are taken from the active BSP and current valid live units.
 * Prioritize allied humans over bots, and bound scanning and drawing. */
static void tactical_draw(void)
{
    struct structure_bsp *bsp = global_structure_bsp_get();
    long viewer_id = local_player_get_player_index(0);
    struct player_datum *viewer = viewer_id != NONE ? player_try_and_get(viewer_id) : NULL;
    struct data_iterator it;
    struct player_datum *player;
    struct unit_datum *unit;
    rectangle2d box, map, marker, grid;
    real_argb_color color;
    long font_index;
    short width, height, panel_width, panel_height;
    short px, py;
    float u, v;
    int checked, drawn, pass;

    if (!viewer || !bsp || !player_data)
        return;
    width = (short)(render.camera.window_bounds.x1 - render.camera.window_bounds.x0);
    height = (short)(render.camera.window_bounds.y1 - render.camera.window_bounds.y0);
    if (width < 310 || height < 225)
        return; /* never overdraw tiny split-screen viewports */

    panel_width = (short)MIN(280, width * 44 / 100);
    panel_height = (short)MIN(236, height * 62 / 100);
    box.x0 = (short)(width - panel_width - 10);
    box.x1 = (short)(width - 10);
    box.y0 = 55;
    box.y1 = (short)(box.y0 + panel_height);
    if (box.y1 > height - 8)
    {
        box.y1 = (short)(height - 8);
        panel_height = box.y1 - box.y0;
    }
    if (panel_height < 90)
        return;

    draw_quad(&box, 0xCC0A1928);
    map = box;
    map.x0 += 13; map.x1 -= 13;
    map.y0 += 36; map.y1 -= 16;
    draw_quad(&map, 0xD50C3040);

    /* Static guide lines are purely graphical; no gameplay state changes. */
    grid = map;
    grid.x0 = (short)((map.x0 + map.x1) / 2);
    grid.x1 = (short)(grid.x0 + 1);
    draw_quad(&grid, 0x66518BA1);
    grid = map;
    grid.y0 = (short)((map.y0 + map.y1) / 2);
    grid.y1 = (short)(grid.y0 + 1);
    draw_quad(&grid, 0x66518BA1);

    checked = drawn = 0;
    for (pass = 0; pass < 2 && drawn < COMMANDER_TACTICAL_MAX_MARKERS; pass++)
    {
        data_iterator_new(&it, player_data);
        checked = 0;
        while (checked < HALO_PORT_MAXIMUM_NETWORK_PLAYERS &&
            drawn < COMMANDER_TACTICAL_MAX_MARKERS &&
            (player = data_iterator_next(&it)) != NULL)
        {
            boolean bot;
            checked++;
            if (player->quit_out_of_game || player->unit_index == NONE ||
                !commander_tactical_ally((int)viewer->team_index,
                    (int)player->team_index))
                continue;
            bot = bots_machine_is_bot(player->network_player_data.machine_index);
            if ((pass == 0 && bot) || (pass == 1 && !bot) ||
                (unit = unit_try_and_get(player->unit_index)) == NULL ||
                unit->unit.player_index != it.datum_index ||
                TEST_FLAG(unit->object.damage_flags, _object_dead_bit))
                continue;
            if (!commander_tactical_project(unit->object.position.x,
                unit->object.position.y, bsp->world_bounds.x0,
                bsp->world_bounds.x1, bsp->world_bounds.y0,
                bsp->world_bounds.y1, &u, &v))
                continue;
            px = (short)(map.x0 + 4 + u * (map.x1 - map.x0 - 8));
            py = (short)(map.y0 + 4 + v * (map.y1 - map.y0 - 8));
            marker.x0 = (short)(px - 3); marker.x1 = (short)(px + 3);
            marker.y0 = (short)(py - 3); marker.y1 = (short)(py + 3);
            draw_quad(&marker, it.datum_index == viewer_id ?
                0xFFFFDF68 : (bot ? 0xFF659CF4 : 0xFF49DCC1));
            drawn++;
        }
    }

    font_index = hud_get_font_index();
    if (font_index != NONE)
    {
        rectangle2d title = box;
        title.x0 += 13;
        title.y0 += 7;
        title.y1 = (short)(title.y0 + 21);
        color.alpha = 1.0f;
        color.red = 0.55f;
        color.green = 0.91f;
        color.blue = 0.99f;
        draw_string_set_draw_mode(font_index, NONE, 0, 0, &color);
        rasterizer_draw_unicode_string(&title, NULL, NULL, 0,
            L"COMMANDER: ALLIED TRACK");
    }
}

#endif /* HALO_ANDROID */

void commander_tactical_present(short local)
{
#if defined(HALO_ANDROID)
    if (!tactical_available(local))
    {
        commander_tactical_reset();
        return;
    }
    if (host_touch_commander_take_toggle())
        tactical_open = !tactical_open;
    host_touch_commander_state(tactical_open ? 3 : 1);
    if (tactical_open)
        tactical_draw();
#else
    /* Non-Android builds have no command button yet. Avoid an always-on
     * overlay and avoid a new shortcut conflicting with game controls. */
    (void)local;
#endif
}
