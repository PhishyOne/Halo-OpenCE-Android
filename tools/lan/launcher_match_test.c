#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef int boolean;
#define TRUE 1
#define FALSE 0
#define NONE (-1)
#define NUMBEROF(a) (sizeof(a)/sizeof((a)[0]))
#define first_usable_game_engine_index 1
#define last_usable_game_engine_index 5
#define _game_connection_local 0
#define _game_connection_network_server 2
#define _error_silent 0
struct game_variant { long game_engine_index; struct { boolean teams; } universal_variant; };
struct game_variant_options { int mode; int hardcore; };
struct player_profile { int value; };
struct network_player { int valid, machine_index, controller_index; };
struct network_game {
    struct { char name[128]; } map;
    struct game_variant variant;
    struct game_variant_options variant_options;
    int player_count;
    struct network_player players[128];
};
struct network_game_server { int value; };
struct network_game_client { int value; };
static struct network_game roster;
static struct network_game_server server;
static struct network_game_client client;
static struct game_variant_options chosen_options;
static int server_live, client_live, remote = 1, paused, music, movie;
static int server_failure, client_failure, map_present = 1, map_valid = 1;
static int client_state = 2, ready = 1, bots = 3, starts, joins, opens, errors;
static int restored_profile, returned_menu, mode_changes, map_changes, skipped;
static unsigned long now;
static boolean game_modes_gametype_by_name(const char *name, struct game_variant *v, struct game_variant_options *o) {
    if (strcmp(name,"gun_game")) return 0;
    v->game_engine_index = 2; v->universal_variant.teams = 0; o->mode=3; return 1;
}
static struct game_variant *game_engine_get_variant_by_name(struct game_variant *v, const char *name) {
    memset(v,0,sizeof(*v));
    if (!strcmp(name,"team_slayer")) { v->game_engine_index=2; v->universal_variant.teams=1; }
    return v;
}
static void game_variant_options_default(struct game_variant *v, struct game_variant_options *o) {
    (void)v; memset(o,0,sizeof(*o));
}
static boolean launcher_match_preset_build(const char *name,struct game_variant *v,struct game_variant_options *o){
    if(game_modes_gametype_by_name(name,v,o))return TRUE;
    game_engine_get_variant_by_name(v,name);
    if(!v->game_engine_index)return FALSE;
    game_variant_options_default(v,o);return TRUE;
}
static boolean launcher_match_rules_apply(const char *rules,const char *base,struct game_variant *v,struct game_variant_options *o){
    (void)base;(void)v;(void)o;return !rules||strcmp(rules,"invalid");
}
static unsigned long system_milliseconds(void) { return now; }
static struct network_game_server *global_network_game_server_get(void) { return server_live?&server:NULL; }
static struct network_game_client *global_network_game_client_get(void) { return client_live?&client:NULL; }
static boolean network_game_server_port_bots_adjustable(struct network_game_server *s) { return s && server_live; }
static void network_game_server_pause_countdown(struct network_game_server *s, boolean value) { assert(s); paused=value; }
static void error(int level, const char *text) { (void)level; assert(text); errors++; }
static boolean bink_playback_in_progress(void) { return movie; }
static void bink_playback_stop(void) { movie=0; skipped++; }
static boolean ui_main_menu_music_active(void) { return music; }
static boolean network_game_is_active(void) { return server_live||client_live; }
static boolean cache_files_map_present(const char *path, unsigned long version) {
    assert(!strcmp(path,"levels\\test\\bloodgulch\\bloodgulch")); assert(!version); return map_present;
}
static boolean cache_files_map_plays_multiplayer(const char *path, char build[32]) {
    assert(!strcmp(path,"levels\\test\\bloodgulch\\bloodgulch")); strcpy(build,map_valid?"valid":""); return map_valid;
}
static long player_ui_get_player1_last_used_profile_index(void) { return 7; }
static boolean player_profile_get(long id, struct player_profile *profile) { assert(id==7); profile->value=42; return 1; }
static void player_ui_set_active_player_profile(short i, long id, struct player_profile *profile) {
    assert(i==0 && id==7 && profile->value==42); restored_profile++;
}
static void ui_widgets_close_all(void) {}
static void network_game_accept_remote_connections(boolean value) { remote=value; }
static void game_engine_playlist_initialize(void) {}
static boolean create_global_network_game_server(void) {
    assert(!remote && !server_live); if (server_failure) return 0; server_live=1; opens++; return 1;
}
static void game_engine_playlist_begin(void) { assert(server_live); }
static void game_connection_set(int mode) { assert(mode==0||mode==2); }
static boolean create_global_network_game_client(void) { if (client_failure) return 0; client_live=1; return 1; }
static void dispose_global_network_game_server(void) { server_live=0; }
static void dispose_global_network_game_client(void) { client_live=0; }
static void main_goto_main_menu(void) { returned_menu++; }
static void network_game_set_quickstart_local(void) { assert(!remote&&server_live); }
static void player_ui_local_player_joined_multiplayer_game(short i) { assert(i==0); }
static void *ui_widget_load_by_name_or_tag(const char *name, long a, void *b, long c, long d, long e, long f) {
    (void)a;(void)b;(void)c;(void)d;(void)e;(void)f;
    assert(strstr(name,"splitscreen_pregame_wrapper_normal")); return &server;
}
static boolean network_game_is_splitscreen_local(void) { return server_live&&!remote; }
static short network_game_client_get_state(struct network_game_client *c, short *unused) { (void)unused;assert(c);return client_state; }
static void network_game_server_change_map_name(struct network_game_server *s,const char *path) {
    assert(s && paused); strcpy(roster.map.name,path); map_changes++;
}
static void player_ui_set_game_variant(struct game_variant *v) { assert(v); }
static void player_ui_set_game_variant_options(const struct game_variant_options *o) { chosen_options=*o; }
static void network_game_server_change_game_variant(struct network_game_server *s, struct game_variant *v) {
    assert(s && paused); roster.variant=*v; roster.variant_options=chosen_options; mode_changes++;
}
static struct network_game *network_game_get_game(void) { return &roster; }
static short network_game_client_get_local_machine_index(void) { return 0; }
static boolean network_player_is_valid(struct network_player *p) { return p->valid; }
static boolean network_game_client_add_player(struct network_game_client *c,short i) { assert(c && i==0); joins++; return 1; }
static long bots_wanted_count(void) { return bots; }
static boolean network_game_server_port_ok_to_countdown(struct network_game_server *s) { assert(s); return ready; }
static void network_game_client_request_immediate_start(void) { assert(!paused && roster.player_count>=1+bots); starts++; }

#include "launcher_match_function.inc"

int main(int argc,char **argv) {
    assert(argc==2);
    const char *test=argv[1];
    unsetenv("HALO_LAUNCHER_MATCH_MAP"); unsetenv("HALO_LAUNCHER_MATCH_MODE"); unsetenv("HALO_LAUNCHER_MATCH_RULES");
    if (!strcmp(test,"classic")) {
        launcher_match_update(1); assert(!opens && !starts); return 0;
    }
    setenv("HALO_LAUNCHER_MATCH_MAP",!strcmp(test,"badmap")?"../bad":"bloodgulch",1);
    setenv("HALO_LAUNCHER_MATCH_MODE",!strcmp(test,"badmode")?"unknown":!strcmp(test,"gun")?"gun_game":"team_slayer",1);
    if (!strcmp(test,"badmap")||!strcmp(test,"badmode")) {
        music=1; launcher_match_update(1); assert(!opens && !starts); return 0;
    }
    if (!strcmp(test,"missing")) map_present=0;
    if (!strcmp(test,"serverfail")) server_failure=1;
    if (!strcmp(test,"clientfail")) client_failure=1;
    launcher_match_update(0); assert(!opens);
    movie=1; launcher_match_update(1); assert(skipped==1&&!opens);
    launcher_match_update(1); assert(!opens);
    music=1; launcher_match_update(1);
    if (!strcmp(test,"missing")) { assert(!opens && errors==1); return 0; }
    if (!strcmp(test,"serverfail")||!strcmp(test,"clientfail")) {
        assert(!server_live && !client_live && returned_menu==1); return 0;
    }
    assert(server_live && client_live && paused && restored_profile==1);
    client_state=1; launcher_match_update(1); assert(!map_changes);
    client_state=2; launcher_match_update(1); assert(map_changes==1 && mode_changes==1 && !starts);
    if (!strcmp(test,"gun")) assert(roster.variant_options.mode==3);
    if (!strcmp(test,"changed")) {
        strcpy(roster.map.name,"other"); launcher_match_update(1); assert(!starts && !paused && errors==1); return 0;
    }
    if (!strcmp(test,"timeout")) {
        now=46000; launcher_match_update(1); assert(!starts && !paused && errors==1); return 0;
    }
    launcher_match_update(1); assert(joins==1 && !starts);
    roster.players[0].valid=1; roster.players[0].machine_index=0; roster.players[0].controller_index=0;
    roster.player_count=1; launcher_match_update(1); assert(!starts);
    roster.player_count=4; ready=0; launcher_match_update(1); assert(!starts);
    ready=1; launcher_match_update(1); assert(starts==1 && !paused);
    launcher_match_update(1); launcher_match_update(0); launcher_match_update(1);
    assert(starts==1 && opens==1 && map_changes==1);
    puts("PASS direct launch state machine");
    return 0;
}
