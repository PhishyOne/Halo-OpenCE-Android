/* All entitlement and damage belong to the host. The request names a life
 * and a point, never a kill count, victim or damage amount. */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/game_allegiance.h"
#include "game/players.h"
#include "camera/director.h"
#include "camera/observer.h"
#include "cutscene/cinematics.h"
#include "input/input_abstraction.h"
#include "interface/hud_messaging.h"
#include "main/main.h"
#include "memory/data.h"
#include "objects/damage.h"
#include "physics/collisions.h"
#include "units/units.h"
#include "bots.h"
#include "commander_features.h"
#include "network_distributed.h"
#include "killstreak_recon.h"
#include "killstreak_rewards.h"
#include "killstreak_orbital.h"
#include <string.h>

#define ORBITAL_WARNING_TICKS 100
/* The HUD distance display uses 3.048 metres per engine world unit. */
#define ORBITAL_RANGE (128.0f / 3.048f)
#define ORBITAL_RADIUS (14.0f / 3.048f)
#define ORBITAL_GROUND_FLAGS (FLAG(_collision_test_structure_bit) | FLAG(_collision_test_front_facing_surfaces_bit))

struct orbital_message {
    struct distributed_message_header header;
    int player_slot;
    int unit_index;
    unsigned int serial; /* host-issued, non-reused capability for this life */
    real_point3d target;
    word kind; /* 0 revoke, 1 grant, 2 request, 3 public incoming */
    word reserved;
};
typedef char orbital_message_size[sizeof(struct orbital_message) == 36 ? 1 : -1];

struct orbital_host_state {
    long player_index, unit_index, team, machine;
    unsigned int serial;
    long impact_tick, last_request_tick;
    real_point3d target;
    boolean active, consumed;
};
struct orbital_local_state {
    long player_index, unit_index, next_notice, request_timeout;
    unsigned int serial;
    boolean ready, pending, requested;
};
struct orbital_warning {
    unsigned int serial;
    long end;
    unsigned int pending; /* one notice per local viewport */
    boolean sound_pending;
    real_point3d target;
};
static struct orbital_host_state orbital_host[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static struct orbital_local_state orbital_local[MAXIMUM_LOCAL_PLAYERS];
static struct orbital_warning orbital_warnings[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static unsigned int orbital_serial; /* not reset on map change */
static boolean orbital_running;

static int orbital_authority(void)
{
    return game_connection() == _game_connection_local ||
        game_connection() == _game_connection_network_server;
}
static int orbital_playing(void)
{
    return orbital_running && game_in_progress() && game_engine_in_play() &&
        !main_menu_is_active() && killstreak_feature_allowed(KILLSTREAK_ENABLED) &&
        killstreak_feature_allowed(KILLSTREAK_ORBITAL);
}
static int orbital_point_valid(real_point3d const *p)
{
    /* Bit checks also reject NaN under builds that optimize float compares. */
    unsigned int bits[3];
    memcpy(bits, p, sizeof(bits));
    return (bits[0] & 0x7f800000u) != 0x7f800000u &&
        (bits[1] & 0x7f800000u) != 0x7f800000u &&
        (bits[2] & 0x7f800000u) != 0x7f800000u &&
        p->x >= -32768.0f && p->x <= 32768.0f &&
        p->y >= -32768.0f && p->y <= 32768.0f &&
        p->z >= -32768.0f && p->z <= 32768.0f;
}
static int orbital_near(real_point3d const *a, real_point3d const *b, real radius)
{
    real x, y, z;
    if (!orbital_point_valid(a) || !orbital_point_valid(b))
        return FALSE;
    x = a->x - b->x; y = a->y - b->y; z = a->z - b->z;
    return x*x + y*y + z*z <= radius*radius;
}
static struct player_datum *orbital_living(long player_index, long unit_index)
{
    struct player_datum *player = player_try_and_get(player_index);
    struct unit_datum *unit;
    if (!player || player->quit_out_of_game || unit_index == NONE ||
        player->unit_index != unit_index ||
        !(unit = unit_try_and_get(unit_index)) ||
        unit->unit.player_index != player_index ||
        TEST_FLAG(unit->object.damage_flags, _object_dead_bit))
        return NULL;
    return player;
}
static int orbital_recent(struct orbital_message const *m)
{
    long now = game_time_get();
    return now >= 0 && m->header.game_time >= 0 &&
        m->header.game_time <= 0x7fffffff - ORBITAL_WARNING_TICKS &&
        (m->header.game_time <= now || m->header.game_time - now <= 15) &&
        (m->header.game_time >= now || now - m->header.game_time < 300);
}
static void orbital_receive(struct orbital_message const *m)
{
    long id;
    short local;
    struct player_datum *player;
    struct orbital_local_state *s;
    struct orbital_warning *w;
    if (m->player_slot < 0 || m->player_slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS || !m->serial)
        return;
    if (m->kind == 3)
    {
        if (!orbital_playing() || !orbital_recent(m) ||
            !orbital_point_valid(&m->target) ||
            game_time_get() >= m->header.game_time + ORBITAL_WARNING_TICKS)
            return;
        w = &orbital_warnings[m->player_slot];
        if (m->serial <= w->serial)
            return;
        w->serial = m->serial;
        w->target = m->target;
        w->end = m->header.game_time + ORBITAL_WARNING_TICKS;
        w->pending = (1u << MAXIMUM_LOCAL_PLAYERS) - 1;
        w->sound_pending = TRUE;
        for (local = 0; local < MAXIMUM_LOCAL_PLAYERS; local++)
            if (orbital_local[local].serial == m->serial)
                orbital_local[local].ready = orbital_local[local].pending = FALSE;
        return;
    }
    id = distributed_player_from_byte((byte)m->player_slot);
    player = id == NONE ? NULL : player_try_and_get(id);
    if (!player || (local = player->local_player_index) < 0 ||
        local >= MAXIMUM_LOCAL_PLAYERS || local_player_get_player_index(local) != id)
        return;
    s = &orbital_local[local];
    if (m->serial <= s->serial)
        return;
    s->serial = m->serial;
    if (m->kind == 0)
    {
        if (s->player_index == id && s->unit_index == m->unit_index)
        {
            s->ready = s->pending = s->requested = FALSE;
            s->request_timeout = 0;
        }
        return;
    }
    if (!orbital_playing() || !orbital_recent(m) ||
        !orbital_living(id, m->unit_index) ||
        bots_machine_is_bot(player->network_player_data.machine_index))
        return;
    s->player_index = id; s->unit_index = m->unit_index;
    s->ready = s->pending = TRUE;
    s->requested = FALSE;
    s->request_timeout = 0;
    s->next_notice = 0;
}
static void orbital_private(long id, long unit, unsigned int serial, word kind)
{
    struct player_datum *p = player_try_and_get(id);
    struct orbital_message m;
    long machine;
    if (!p)
        return;
    machine = p->network_player_data.machine_index;
    memset(&m, 0, sizeof(m));
    m.header.game_time = game_time_get();
    m.player_slot = (int)DATUM_INDEX_TO_ABSOLUTE_INDEX(id);
    m.unit_index = (int)unit; m.serial = serial; m.kind = kind;
    if (p->local_player_index != NONE)
        orbital_receive(&m);
    else if (game_connection() == _game_connection_network_server &&
        machine >= 0 && machine < HALO_PORT_MAXIMUM_NETWORK_MACHINES &&
        !bots_machine_is_bot(machine))
        distributed_send_to_machine_reliably(machine,
            &m, _distributed_message_orbital_award, 1, (word)sizeof(m));
}
void killstreak_orbital_reset_player(long id)
{
    unsigned int slot = (unsigned short)id;
    short local;
    struct orbital_host_state *s;
    for (local = 0; local < MAXIMUM_LOCAL_PLAYERS; local++)
        if (orbital_local[local].player_index == id)
            orbital_local[local].ready = orbital_local[local].pending = FALSE;
    if (id == NONE || slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return;
    s = &orbital_host[slot];
    if (!s->active || s->player_index != id)
        return;
    if (orbital_authority() && game_in_progress() && orbital_serial != 0xffffffffu)
        orbital_private(id, s->unit_index, ++orbital_serial, 0);
    memset(s, 0, sizeof(*s));
}
void killstreak_orbital_start(void)
{
    memset(orbital_host, 0, sizeof(orbital_host));
    memset(orbital_local, 0, sizeof(orbital_local));
    memset(orbital_warnings, 0, sizeof(orbital_warnings));
    orbital_running = TRUE;
}
void killstreak_orbital_stop(void)
{
    short slot;
    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
        if (orbital_host[slot].active)
            killstreak_orbital_reset_player(orbital_host[slot].player_index);
    orbital_running = FALSE;
    killstreak_orbital_update();
}
void killstreak_orbital_award(long id, long unit)
{
    unsigned int slot = (unsigned short)id;
    struct orbital_host_state *s;
    struct player_datum *p;
    if (!orbital_authority() || !orbital_playing() || id == NONE ||
        slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS ||
        !killstreak_rewards_has_ten(id, unit) ||
        !(p = orbital_living(id, unit)) ||
        bots_machine_is_bot(p->network_player_data.machine_index) ||
        orbital_serial == 0xffffffffu)
        return;
    s = &orbital_host[slot];
    if (s->active && s->player_index == id && s->unit_index == unit)
        return;
    memset(s, 0, sizeof(*s));
    s->active = TRUE; s->player_index = id; s->unit_index = unit;
    s->team = p->team_index; s->machine = p->network_player_data.machine_index;
    s->serial = ++orbital_serial;
    s->last_request_tick = -1;
    orbital_private(id, unit, s->serial, 1);
}
/* Recheck a ground surface near the point, then the path from the living
 * unit's head. Structure-only floor tests reject sky, walls and objects. */
static int orbital_geometry(long unit, real_point3d const *target)
{
    struct collision_result hit;
    real_point3d head, above, aim;
    real_vector3d ray;
    unit_get_head_position(unit, &head);
    if (!orbital_near(&head, target, ORBITAL_RANGE))
        return FALSE;
    above = *target; above.z += 0.25f;
    ray.i = ray.j = 0; ray.k = -0.5f;
    if (!collision_test_vector(ORBITAL_GROUND_FLAGS, &above, &ray, unit, &hit) ||
        hit.type != _collision_result_structure || !(hit.plane.n.k > 0.5f) ||
        !orbital_near(&hit.point, target, 0.3f))
        return FALSE;
    aim = hit.point; aim.z += 0.1f;
    vector_from_points3d(&head, &aim, &ray);
    return !collision_test_vector(_collision_test_for_line_of_sight_flags,
        &head, &ray, unit, &hit);
}
static void orbital_accept(long machine, struct orbital_message const *m)
{
    struct orbital_host_state *s;
    struct player_datum *p;
    struct orbital_message warning;
    long id, now;
    if (!orbital_authority() || !orbital_playing() || !orbital_recent(m) ||
        m->player_slot < 0 || m->player_slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return;
    id = distributed_player_from_byte((byte)m->player_slot);
    p = orbital_living(id, m->unit_index);
    if (!p || !killstreak_rewards_has_ten(id, m->unit_index) ||
        bots_machine_is_bot(p->network_player_data.machine_index))
        return;
    if (machine == NONE)
    {
        if (p->local_player_index < 0 || p->local_player_index >= MAXIMUM_LOCAL_PLAYERS ||
            local_player_get_player_index(p->local_player_index) != id)
            return;
    }
    else if (machine < 0 || machine >= HALO_PORT_MAXIMUM_NETWORK_MACHINES ||
        p->network_player_data.machine_index != machine ||
        !distributed_machine_has_player(machine, (short)m->player_slot))
        return;
    s = &orbital_host[m->player_slot];
    now = game_time_get();
    if (!s->active || s->consumed || s->player_index != id ||
        s->unit_index != m->unit_index || s->serial != m->serial ||
        s->team != p->team_index || s->machine != p->network_player_data.machine_index ||
        now > 0x7fffffff - ORBITAL_WARNING_TICKS ||
        s->last_request_tick == now || !orbital_point_valid(&m->target))
        return;
    s->last_request_tick = now;
    if (!orbital_geometry(m->unit_index, &m->target))
        return;
    s->consumed = TRUE; s->target = m->target;
    s->impact_tick = now + ORBITAL_WARNING_TICKS;
    warning = *m; warning.kind = 3; warning.header.game_time = now;
    orbital_receive(&warning);
    if (game_connection() == _game_connection_network_server)
        distributed_send(&warning, _distributed_message_orbital_incoming, 1,
            (word)sizeof(warning), _distributed_to_clients_reliably);
}
boolean killstreak_orbital_handles_message(word const *data, word size)
{
    struct distributed_message_header h;
    if (size < sizeof(h))
        return FALSE;
    memcpy(&h, data, sizeof(h));
    return h.type >= _distributed_message_orbital_award && h.type <= _distributed_message_orbital_incoming;
}
void killstreak_orbital_handle_message(long machine, word const *data, word size)
{
    struct orbital_message m;
    if (size != sizeof(m) || network_connection_last_read_was_unreliable() || !game_in_progress())
        return;
    memcpy(&m, data, sizeof(m));
    if (GET_MESSAGE_TYPE(m.header.header) != 2 || GET_MESSAGE_SIZE(m.header.header) != sizeof(m) ||
        GET_MESSAGE_FLAGS(m.header.header) != 0 || m.header.count != 1 || m.reserved ||
        !m.serial || m.player_slot < 0 || m.player_slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return;
    if (m.header.type == _distributed_message_orbital_request && m.kind == 2)
    {
        /* NONE identifies host-to-client traffic, never a remote requester. */
        if (game_connection() == _game_connection_network_server && machine != NONE)
            orbital_accept(machine, &m);
    }
    else if (game_connection() == _game_connection_network_client && machine == NONE &&
        ((m.header.type == _distributed_message_orbital_award && m.kind <= 1 &&
          m.target.x == 0 && m.target.y == 0 && m.target.z == 0) ||
         (m.header.type == _distributed_message_orbital_incoming && m.kind == 3)))
        orbital_receive(&m);
}
static void orbital_impact(struct orbital_host_state *s)
{
    short slot;
    long id;
    struct player_datum *p, *owner;
    struct orbital_host_state strike = *s;
    struct unit_datum *unit;
    real_point3d center, head;
    real_vector3d ray;
    struct collision_result hit;
    /* No effect asset lookup or client-side victim changes. The engine's
     * instant-kill helper credits the owner and replicates ordinary damage. */
    s->impact_tick = 0;
    center = strike.target; center.z += 0.2f;
    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
    {
        /* Death callbacks may revoke another strike or this one. Keep the
         * accepted point/owner snapshot, and stop if that life is gone. */
        owner = orbital_living(strike.player_index, strike.unit_index);
        if (!orbital_playing() || !owner || owner->team_index != strike.team ||
            owner->network_player_data.machine_index != strike.machine)
            break;
        id = distributed_player_from_byte((byte)slot);
        p = id == NONE ? NULL : player_try_and_get(id);
        if (!p || id == strike.player_index || !orbital_living(id, p->unit_index) ||
            !game_team_is_enemy(p->team_index, (short)strike.team))
            continue;
        unit = unit_try_and_get(p->unit_index);
        if (!orbital_near(&unit->object.position, &strike.target, ORBITAL_RADIUS))
            continue;
        unit_get_head_position(p->unit_index, &head);
        if (!orbital_point_valid(&head))
            continue;
        vector_from_points3d(&center, &head, &ray);
        if (!collision_test_vector(ORBITAL_GROUND_FLAGS, &center, &ray, NONE, &hit))
            damage_kill_object_for_player(p->unit_index, strike.player_index);
    }
}
static void orbital_input(short local)
{
    long id;
    struct player_datum *p;
    struct orbital_local_state *s = &orbital_local[local];
    struct observer_result const *camera;
    struct collision_result hit;
    struct orbital_message m;
    real_point3d head, direction;
    real_vector3d ray;
    if (!game_in_progress())
        return;
    id = local_player_get_player_index(local);
    p = id == NONE ? NULL : player_try_and_get(id);
    if (!p || !input_abstraction_orbital_chord(local) ||
        !orbital_playing() || !s->ready || s->requested || s->player_index != id ||
        !orbital_living(id, s->unit_index) || cinematic_in_progress() ||
        player_control_get_unit_index(local) != s->unit_index ||
        director_get_perspective(local) != _director_perspective_first_person ||
        director_inhibited_input(local) || (director_camera_scripted && *director_camera_scripted))
        return;
    camera = observer_get_camera(local);
    if (!camera)
        return;
    unit_get_head_position(s->unit_index, &head);
    direction.x = camera->forward.i; direction.y = camera->forward.j; direction.z = camera->forward.k;
    if (!orbital_near(&head, &camera->position, 2.0f) || !orbital_point_valid(&direction) ||
        !(direction.x*direction.x + direction.y*direction.y + direction.z*direction.z >= 0.99f) ||
        !(direction.x*direction.x + direction.y*direction.y + direction.z*direction.z <= 1.01f))
        return;
    scale_vector3d(&camera->forward, ORBITAL_RANGE, &ray);
    if (!collision_test_vector(ORBITAL_GROUND_FLAGS, &camera->position, &ray, s->unit_index, &hit) ||
        hit.type != _collision_result_structure || !(hit.plane.n.k > 0.5f) ||
        !orbital_geometry(s->unit_index, &hit.point))
        return;
    memset(&m, 0, sizeof(m));
    m.header.game_time = game_time_get(); m.player_slot = (int)DATUM_INDEX_TO_ABSOLUTE_INDEX(id);
    m.unit_index = (int)s->unit_index; m.serial = s->serial; m.target = hit.point; m.kind = 2;
    /* No automatic resend: the player must intentionally tap again.
     * If the host rejects (or never receives) the request, restore the
     * ability to manually retry after 90 ticks, with a fresh input edge.
     * A consumed host entitlement still rejects duplicate packets. */
    if (game_time_get() > 0x7fffffff - 90)
        return;
    s->requested = TRUE;
    s->request_timeout = game_time_get() + 90;
    if (orbital_authority())
        orbital_accept(NONE, &m);
    else if (game_connection() == _game_connection_network_client)
        distributed_send(&m, _distributed_message_orbital_request, 1,
            (word)sizeof(m), _distributed_to_host_reliably);
}
void killstreak_orbital_update(void)
{
    short slot, local;
    int impacts = 0;
    int playing = orbital_playing();
    struct orbital_host_state *s;
    struct player_datum *p;
    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
    {
        s = &orbital_host[slot];
        if (s->active)
        {
            p = orbital_living(s->player_index, s->unit_index);
            if (!playing || !orbital_authority() || !p || p->team_index != s->team ||
                p->network_player_data.machine_index != s->machine ||
                bots_machine_is_bot(p->network_player_data.machine_index))
                killstreak_orbital_reset_player(s->player_index);
            else if (s->impact_tick && game_time_get() >= s->impact_tick && impacts < 4)
            {
                orbital_impact(s);
                impacts++;
            }
        }
        if (!playing)
            orbital_warnings[slot].pending = orbital_warnings[slot].sound_pending = 0;
    }
    for (local = 0; local < MAXIMUM_LOCAL_PLAYERS; local++)
    {
        if (!playing || local_player_get_player_index(local) != orbital_local[local].player_index ||
            !orbital_living(orbital_local[local].player_index, orbital_local[local].unit_index))
            orbital_local[local].ready = orbital_local[local].pending = FALSE;
        /* A rejected designation is recoverable, but never retried
         * automatically. The next tap must be a new flashlight edge. */
        if (playing && orbital_local[local].ready &&
            orbital_local[local].requested &&
            game_time_get() >= orbital_local[local].request_timeout)
            orbital_local[local].requested = FALSE;
        orbital_input(local);
    }
}
/* Only called on the HUD/main thread. Packet and kill callbacks queue state. */
void killstreak_orbital_present(short local)
{
    struct orbital_local_state *s;
    struct orbital_warning *w;
    short slot;
    if (local < 0 || local >= MAXIMUM_LOCAL_PLAYERS || !orbital_playing() || cinematic_in_progress())
        return;
    s = &orbital_local[local];
    if (s->ready && local_player_get_player_index(local) == s->player_index &&
        orbital_living(s->player_index, s->unit_index))
    {
        if (s->pending)
        {
            s->pending = FALSE;
            if (killstreak_feature_allowed(KILLSTREAK_ANNOUNCEMENTS))
                game_engine_play_killstreak_sound(local, 10);
        }
        if (game_time_get() >= s->next_notice)
        {
            hud_print_message(local, s->requested ? L"ORBITAL: DESIGNATION SENT" :
                L"ORBITAL READY: HOLD ZOOM + TAP FLASHLIGHT");
            s->next_notice = game_time_get() + 90;
        }
    }
    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
    {
        w = &orbital_warnings[slot];
        if (w->end > game_time_get() && (w->pending & (1u << local)))
        {
            w->pending &= ~(1u << local);
            hud_print_message(local, L"Orbital bombardment incoming");
            if (w->sound_pending && local_player_get_next(NONE) == local)
            {
                w->sound_pending = FALSE;
                if (killstreak_feature_allowed(KILLSTREAK_ANNOUNCEMENTS))
                    game_engine_play_killstreak_sound(local, 11);
            }
        }
    }
}
