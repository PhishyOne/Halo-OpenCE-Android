#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#define NUMBEROF(a) (sizeof(a)/sizeof((a)[0]))
#define TRUE 1
#define FALSE 0
#define NONE (-1)
#define _game_connection_network_client 1
typedef int boolean;
struct game_variant { struct { int teams; } universal_variant; int engine; };
struct game_variant_options { int value; };
struct player_profile { wchar_t player_name[12]; short primary_color_index; };
struct network_player { wchar_t name[12]; short primary_color_index; int machine_index,controller_index,team_index; };
struct network_game {
    struct {char name[128];} map;
    struct game_variant variant; struct game_variant_options variant_options;
    struct { int machine_index; wchar_t name[32]; } machines[128]; struct network_player players[128];
};
struct network_game_server {int active;};
struct network_game_client {int active;};
static struct network_game world;
static struct network_game_server server;
static struct network_game_client client;
static struct player_profile profile;
static int missing, movie, quit,playing,joined,added,starts,held,countdown=-1,local_machine;
static int postgame, resets, reset_ok=1;
static unsigned long now;
static boolean launcher_match_preset_build(const char *mode,struct game_variant *v,struct game_variant_options *o){
    v->universal_variant.teams=!strcmp(mode,"ctf");v->engine=1;o->value=2;return TRUE;
}
static boolean launcher_match_rules_apply(const char *rules,const char *mode,struct game_variant *v,struct game_variant_options *o){
    (void)mode;(void)v;(void)o;return !rules||strcmp(rules,"invalid");
}
static boolean network_player_is_valid(struct network_player *p){return p->machine_index>=0;}
static boolean bots_machine_is_bot(int m){return m>=64;}
static void platform_request_quit(void){quit++;}
static unsigned long system_milliseconds(void){return now;}
static int game_engine_running(void){return playing;}
static int game_engine_in_play(void){return !postgame;}
static int game_engine_showing_postgame(void){return postgame==2;}
static int network_game_server_reset_to_pregame(struct network_game_server *s){assert(s==&server);resets++;return reset_ok;}
static int bink_playback_in_progress(void){return movie;}
static void bink_playback_stop(void){movie=0;}
static int ui_main_menu_music_active(void){return 1;}
static int network_game_is_active(void){return 0;}
static int cache_files_map_present(const char *p,int v){(void)p;(void)v;return !missing;}
static int cache_files_map_plays_multiplayer(const char *p,char *build){strcpy(build,!strncmp(p,"custom_maps",11)?"":"Xbox");return 1;}
static long player_ui_get_player1_last_used_profile_index(void){return 1;}
static int player_profile_get(long n,struct player_profile *p){(void)n;*p=profile;return 1;}
static void player_ui_get_active_player_profile(int n,struct player_profile *p){(void)n;*p=profile;}
static long player_ui_get_active_player_profile_index(int n){return n;}
static void player_ui_set_active_player_profile(int n,long index,struct player_profile *p){assert(n==0&&index==1);profile=*p;}
static void player_ui_local_player_joined_multiplayer_game(int n){assert(n==0);}
static void network_game_server_port_set_settings(const wchar_t *tag,long count){assert(tag[14]=='0'&&count>=2);}
static void player_ui_fast_setup_network_server(void){server.active=client.active=1;}
static struct network_game_server *global_network_game_server_get(void){return server.active?&server:NULL;}
static struct network_game_client *global_network_game_client_get(void){return client.active?&client:NULL;}
static void network_game_server_pause_countdown(struct network_game_server *s,int pause){assert(s==&server);held=pause;}
static void ui_widgets_close_all(void){}
static int create_global_network_game_client(void){return client.active=1;}
static void game_connection_set(int c){assert(c==_game_connection_network_client);}
static boolean network_game_client_join_launcher_game(const wchar_t *tag,unsigned long ip){assert(tag[14]=='0'&&ip==0xc0a82b01);return ++joined>1;}
static int ui_widget_load_by_name_or_tag(const char *path,int a,void *b,int c,int d,int e,int f){assert(strstr(path,"connected"));(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;return 1;}
static int network_game_client_get_state(struct network_game_client *c,void *s){(void)c;(void)s;return 2;}
static int network_game_server_port_bots_adjustable(struct network_game_server *s){return s!=NULL;}
static void network_game_server_change_map_name(struct network_game_server *s,const char *p){(void)s;strcpy(world.map.name,p);}
static void player_ui_set_game_variant(struct game_variant *v){(void)v;}
static void player_ui_set_game_variant_options(struct game_variant_options *o){world.variant_options=*o;}
static void network_game_server_change_game_variant(struct network_game_server *s,struct game_variant *v){(void)s;world.variant=*v;}
static struct network_game *network_game_get_game(void){return &world;}
static short network_game_client_get_local_machine_index(void){return (short)local_machine;}
static unsigned long network_game_server_machine_address(long m){return m==8?0xc0a82b03:0;}
static void network_game_client_add_player(struct network_game_client *c,int n){(void)c;(void)n;added++;}
static boolean launcher_lan_countdown_allowed(struct network_game *g);
static int network_game_server_port_ok_to_countdown(struct network_game_server *s){(void)s;return launcher_lan_countdown_allowed(&world);}
static int network_game_server_port_countdown_milliseconds(struct network_game_server *s){(void)s;return countdown;}
static void network_game_server_port_countdown_begin(struct network_game_server *s,long ms){assert(s==&server&&ms==3000);starts++;countdown=ms;held=0;}
#include "launcher_lan_match.h"

static void add_seat(int slot,int machine,int seat){
    struct network_player p={0};
    p.machine_index=machine;world.machines[machine].machine_index=machine;
    launcher_lan_tag(world.machines[machine].name,seat);
    assert(launcher_lan_admit(&world,&p));world.players[slot]=p;
}
int main(int argc,char **argv){
    const char *test=argv[1];int playlist=!strncmp(test,"playlist",8);
    int i,guest=!strcmp(test,"guest")||!strcmp(test,"playlist-guest"),ffa=!strcmp(test,"ffa");
    wchar_t own[32]={0};
    assert(argc==2);
    for(i=0;i<128;i++){world.machines[i].machine_index=-1;world.players[i].machine_index=-1;}
    if(!strcmp(test,"classic")) {
        launcher_lan_update(1);assert(!launcher_lan.active&&launcher_lan_countdown_allowed(&world)&&!quit);return 0;
    }
    setenv("HALO_LOBBY_ROUND","0123456789abc",1);
    setenv("HALO_LOBBY_PLAYLIST",playlist?"1":"0",1);
    setenv("HALO_LOBBY_MAP",!strcmp(test,"ce")?"ce:example":!strcmp(test,"path")?"ce:../bad":"bloodgulch",1);
    setenv("HALO_LOBBY_MODE",ffa?"slayer":"ctf",1);
    setenv("HALO_LOBBY_ROSTER",ffa?"20b0041,2070042":"00b0041,1070042",1);
    setenv("HALO_LOBBY_SEAT",guest?"1":"0",1);setenv("HALO_LOBBY_PEER",guest?"192.168.43.1":"",1);
    setenv("HALO_LOBBY_BINDINGS",guest?"":"00000000c0a82b03",1);
    setenv("HALO_LOBBY_BOTS","3",1);setenv("HALO_LOBBY_RED",ffa?"0":"2",1);
    if(!strcmp(test,"badrows"))setenv("HALO_LOBBY_ROSTER","00b0041,",1);
    if(!strcmp(test,"badseat"))setenv("HALO_LOBBY_SEAT","16",1);
    if(!strcmp(test,"badmode"))setenv("HALO_LOBBY_RULES","invalid",1);
    if(!strcmp(test,"peer")){setenv("HALO_LOBBY_SEAT","1",1);setenv("HALO_LOBBY_PEER","8.8.8.8",1);}
    missing=!strcmp(test,"missing");movie=1;
    launcher_lan_update(0);launcher_lan_update(1);launcher_lan_update(1);
    if(missing||!strncmp(test,"bad",3)||!strcmp(test,"path")||!strcmp(test,"peer")){
        assert(quit==1&&!client.active&&!starts);return 0;
    }
    assert(client.active&&!quit&&launcher_lan_machine_name(own)&&own[14]==(guest?'1':'0'));
    assert(profile.player_name[0]==(guest?'B':'A')&&profile.primary_color_index==(guest?7:11));
    now=1000;launcher_lan_update(1);
    if(guest){
        assert(!server.active&&joined==1&&!starts);launcher_lan_update(1);launcher_lan_update(1);
        assert(joined==2&&added==1);
        if(playlist){
            playing=1;postgame=2;launcher_lan_update(0);now+=19999;launcher_lan_update(0);
            assert(!resets&&!quit);now++;launcher_lan_update(0);assert(!resets&&quit==1);
        }
        return 0;
    }
    assert(held&&added==1&&!starts);
    if(!strcmp(test,"timeout")){now=120001;launcher_lan_update(1);assert(quit==1&&!starts);return 0;}
    {struct network_player stranger={0};stranger.machine_index=7;world.machines[7].machine_index=7;
     launcher_lan_tag(world.machines[7].name,1);assert(!launcher_lan_admit(&world,&stranger));}
    add_seat(6,8,1);add_seat(3,0,0);
    assert(!launcher_lan_countdown_allowed(&world)&&!starts);
    assert(world.players[6].name[0]=='B'&&world.players[6].primary_color_index==7);
    {struct network_player duplicate=world.players[6];assert(!launcher_lan_admit(&world,&duplicate));}
    for(i=0;i<3;i++){
        struct network_player *bot=&world.players[70+i];bot->machine_index=64+i;
        bot->team_index=ffa?0:launcher_lan_bot_team(i);
    }
    if(!ffa){
        world.players[70].team_index=1;assert(!launcher_lan_countdown_allowed(&world));world.players[70].team_index=0;
        world.players[6].team_index=0;assert(!launcher_lan_countdown_allowed(&world));world.players[6].team_index=1;
    }
    world.machines[8].name[0]='f';assert(!launcher_lan_countdown_allowed(&world));launcher_lan_tag(world.machines[8].name,1);
    world.variant_options.value++;assert(!launcher_lan_countdown_allowed(&world));world.variant_options.value--;
    launcher_lan_update(1);launcher_lan_update(1);assert(starts==1&&countdown==3000&&!held);
    playing=1;launcher_lan_update(0);now=200000;launcher_lan_update(0);assert(!quit);
    if(playlist){
        postgame=1;launcher_lan_update(0);now+=16000;launcher_lan_update(0);assert(!resets);
        postgame=2;launcher_lan_update(0);now+=7999;launcher_lan_update(0);assert(!resets&&!quit);
        if(!strcmp(test,"playlist-quit")){
            launcher_lan_decline_next();launcher_lan_update(1);assert(quit==1);return 0;
        }
        reset_ok=0;now++;launcher_lan_update(0);assert(resets==1&&!quit);
        reset_ok=1;now+=1000;launcher_lan_update(0);now+=1000;launcher_lan_update(0);
        assert(resets==2&&!quit);
    }else if(!strcmp(test,"quick-finish")){
        postgame=2;launcher_lan_update(0);now+=30000;launcher_lan_update(0);assert(!resets&&!quit);
    }
    launcher_lan_update(1);assert(quit==1);
    return 0;
}
