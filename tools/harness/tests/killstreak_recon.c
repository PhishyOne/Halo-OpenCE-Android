/* Fake world for the complete production recon module, stock-audio helper
 * and HUD marker function. The packet retains its real 28-byte layout. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <wchar.h>
#define NONE (-1)
#define TRUE 1
#define FALSE 0
#define MAXIMUM_LOCAL_PLAYERS 4
#define TEST_FLAG(v,b) ((v) & (1u << (b)))
#define _object_dead_bit 0
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"line %d: %s\n",__LINE__,#x); return 1; } } while (0)
typedef int boolean;
typedef unsigned short word;
typedef unsigned char byte;
typedef unsigned int pixel32;
#include "message_header.h"
#include "constants.inc"
#include "commander_feature_policy.h"
#include "killstreak_recon.h"
#define HALO_ANDROID 1 /* compile the actual Android guest reward path */
struct player_datum { long unit_index, team_index; int quit_out_of_game;
    short local_player_index; struct { int machine_index; } network_player_data; };
struct unit_datum { struct { unsigned int damage_flags; } object;
    struct { long player_index; } unit; };
static struct player_datum players[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static struct unit_datum units[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static long ids[HALO_PORT_MAXIMUM_NETWORK_PLAYERS], unit_ids[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static long locals[MAXIMUM_LOCAL_PLAYERS];
static int connection, in_game, in_play, now, menu, cinematic, unreliable;
static int coop, teams, count, sent, sent_machine, texts, sounds, last_sound, last_text_local;
static int speech_available;
static unsigned int policy;
static byte packet[128];
static struct player_datum *player_try_and_get(long id) {
    int i; for(i=0;i<count;i++) if(ids[i]==id && id!=NONE) return &players[i]; return NULL;
}
static struct unit_datum *unit_try_and_get(long id) {
    int i; for(i=0;i<count;i++) if(unit_ids[i]==id && id!=NONE) return &units[i]; return NULL;
}
#define DATUM_INDEX_TO_ABSOLUTE_INDEX(id) ((id)&0xffff)
static long distributed_player_from_byte(byte slot) {
    return slot<count && slot!=0xff ? ids[slot] : NONE;
}
static long local_player_get_player_index(short local) { assert(local>=0 && local<4); return locals[local]; }
static short local_player_get_next(short local) {
    while (++local < 4) {
        if (locals[local] != NONE)
            return local;
    }
    return NONE;
}
static int game_connection(void) { return connection; }
static int game_in_progress(void) { return in_game; }
static int game_engine_in_play(void) { return in_play; }
static int main_menu_is_active(void) { return menu; }
static int cinematic_in_progress(void) { return cinematic; }
static long game_time_get(void) { assert(in_game); return now; }
static int bots_machine_is_bot(long machine) { return machine>=112; }
static int killstreak_feature_allowed(unsigned int feature) {
    return killstreak_policy_allows(commander_policy_effective(policy,teams,!coop),feature);
}
static int network_connection_last_read_was_unreliable(void) { return unreliable; }
static void distributed_send_to_machine_reliably(long machine,void *data,byte type,short n,word size) {
    struct distributed_message_header *h=data;
    assert(size<=sizeof(packet)); h->type=type; h->count=(byte)n; h->game_time=now;
    h->header=(word)((size<<4)|(2<<2)); memcpy(packet,data,size); sent++; sent_machine=(int)machine;
}
static void hud_print_message(short local,wchar_t const *text) {
    assert(wcsstr(text,L"kills:")); texts++; last_text_local=local;
}
struct tag_block { int count; void *address; };
struct tag_reference { long index; };
struct game_globals { struct tag_block multiplayer_information; };
struct game_globals_multiplayer_information { struct tag_block sounds; };
static struct game_globals globals;
static struct game_globals_multiplayer_information information;
static struct tag_reference clips[NUMBER_OF_MULTIPLAYER_INFORMATION_SOUNDS];
#define TAG_BLOCK_GET_ELEMENT(block,index,type) (&((type *)(block)->address)[index])
static struct game_globals *scenario_get_game_globals(void) { return &globals; }
static int host_android_announce_killstreak(int award) {
    if (award != 3 && award != 5)
        return 0;
    if (!speech_available)
        return 0;
    sounds++; last_sound=award; return 1;
}
typedef struct { float x,y,z; } real_point3d;
typedef struct { float x,y; } real_point2d;
typedef struct { short x0,y0,x1,y1; } rectangle2d;
static struct { short local_player_index;
    struct { rectangle2d window_bounds,viewport_bounds; } camera;
    struct { int world_to_view; } frustum; } render;
struct data_iterator { int next; long datum_index; };
static void *player_data;
static int checked, projected, quads, last_target, offscreen, hidden;
static void data_iterator_new(struct data_iterator *i,void *data) { (void)data; i->next=0; }
static void *data_iterator_next(struct data_iterator *i) {
    if(i->next>=count) return NULL;
    i->datum_index=ids[i->next]; checked++; return &players[i->next++];
}
static int game_team_is_enemy(short a,short b) { return a!=b; }
static void unit_get_head_position(long unit,real_point3d *p) {
    p->x=200; p->y=200; p->z=1; last_target=(int)(unit&0xffff);
}
static void matrix4x3_transform_point(void *m,real_point3d *p,real_point3d *v) { (void)m; *v=*p; }
static int render_camera_view_to_screen(void *camera,void *frustum,real_point3d *p,real_point2d *s) {
    (void)camera; (void)frustum; projected++; s->x=offscreen?1.0e20f:p->x; s->y=p->y;
    return !hidden;
}
static long fast_ftol(float x) { return (long)x; }
static void draw_quad(rectangle2d *r,pixel32 color) {
    assert(color==0xEFFF2020 && r->x0>=0 && r->x1<=640 && r->y0>=0 && r->y1<=480); quads++;
}
#include "production.inc"

static void setup(void) {
    int i;
    memset(players,0,sizeof(players)); memset(units,0,sizeof(units));
    count=128; now=300; in_game=in_play=teams=1; menu=cinematic=unreliable=coop=0;
    connection=_game_connection_network_client;
    policy=KILLSTREAK_ENABLED|KILLSTREAK_ANNOUNCEMENTS;
    sent=texts=sounds=checked=projected=quads=offscreen=hidden=0; sent_machine=NONE;
    speech_available=1;
    for(i=0;i<count;i++) {
        ids[i]=0x10000+i; unit_ids[i]=0x20000+i;
        players[i].unit_index=unit_ids[i]; players[i].team_index=i;
        players[i].local_player_index=NONE; players[i].network_player_data.machine_index=i;
        units[i].unit.player_index=ids[i];
    }
    for(i=0;i<4;i++) locals[i]=NONE;
    locals[0]=ids[0]; players[0].local_player_index=0;
    globals.multiplayer_information.count=1; globals.multiplayer_information.address=&information;
    information.sounds.count=NUMBER_OF_MULTIPLAYER_INFORMATION_SOUNDS; information.sounds.address=clips;
    for(i=0;i<NUMBER_OF_MULTIPLAYER_INFORMATION_SOUNDS;i++) clips[i].index=100+i;
    render.local_player_index=0;
    render.camera.window_bounds.x0=render.camera.viewport_bounds.x0=0;
    render.camera.window_bounds.y0=render.camera.viewport_bounds.y0=0;
    render.camera.window_bounds.x1=render.camera.viewport_bounds.x1=640;
    render.camera.window_bounds.y1=render.camera.viewport_bounds.y1=480;
    killstreak_recon_reset();
}
static struct killstreak_recon_message message(int award,unsigned int serial) {
    struct killstreak_recon_message m;
    memset(&m,0,sizeof(m)); m.header.header=(sizeof(m)<<4)|(2<<2);
    m.header.type=_distributed_message_killstreak_recon; m.header.count=1; m.header.game_time=now;
    m.player_index=(int)DATUM_INDEX_TO_ABSOLUTE_INDEX(ids[0]); m.unit_index=(int)unit_ids[0]; m.serial=serial;
    m.award=(word)award; m.recon_end=award==5 ? now+900 : 0; return m;
}
static void receive(struct killstreak_recon_message *m) { killstreak_recon_handle_message(NONE,(word *)m,sizeof(*m)); }
static void award(int value,unsigned int serial) { struct killstreak_recon_message m=message(value,serial); receive(&m); }

int main(int argc,char **argv) {
    int i; struct killstreak_recon_message m;
    assert(argc==2); setup();
    if(!strcmp(argv[1],"wire")) {
        CHECK(sizeof(m)==28); m=message(5,1);
        CHECK(killstreak_recon_handles_message((word *)&m,sizeof(m)));
        for(i=0;i<(int)sizeof(m);i++) {
            killstreak_recon_handle_message(NONE,(word *)&m,(word)i); CHECK(!killstreak_recon_active(0));
        }
        killstreak_recon_handle_message(NONE,(word *)&m,sizeof(m)+1); CHECK(!killstreak_recon_active(0));
        killstreak_recon_handle_message(0,(word *)&m,sizeof(m)); CHECK(!killstreak_recon_active(0));
        unreliable=1; receive(&m); CHECK(!killstreak_recon_active(0)); unreliable=0;
        connection=_game_connection_network_server; receive(&m); CHECK(!killstreak_recon_active(0));
        connection=_game_connection_network_client;
        for(i=0;i<12;i++) {
            m=message(5,1);
            if(i==0) m.header.header^=4;
            if(i==1) m.header.header^=16;
            if(i==2) m.header.header|=1;
            if(i==3) m.header.type--;
            if(i==4) m.header.count=0;
            if(i==5) m.header.count=2;
            if(i==6) m.reserved=1;
            if(i==7) m.award=10;
            if(i==8) m.recon_end++;
            if(i==9) m.header.game_time=-1;
            if(i==10) m.header.game_time=0x7fffffff;
            if(i==11) m.serial=0;
            receive(&m); CHECK(!killstreak_recon_active(0));
        }
        m=message(3,1); m.recon_end=now+900; receive(&m); CHECK(texts==0 && recon_local[0].pending==0);
        award(5,1); CHECK(killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"identity")) {
        for(i=0;i<11;i++) {
            setup(); m=message(5,1);
            if(i==0) m.player_index=1; /* valid slot but not this player */
            if(i==1) m.unit_index+=0x10000;
            if(i==2) players[0].local_player_index=NONE;
            if(i==3) players[0].local_player_index=4;
            if(i==4) locals[0]=ids[1];
            if(i==5) units[0].unit.player_index=ids[1];
            if(i==6) units[0].object.damage_flags=1;
            if(i==7) players[0].quit_out_of_game=1;
            if(i==8) players[0].unit_index=NONE;
            if(i==9) m.player_index=128; /* invalid absolute player slot */
            if(i==10) m.player_index=-1;
            receive(&m); CHECK(recon_local[0].recon_end==0 && recon_local[0].pending==0);
        }
        /* Host slot 0 stays constant while the guest's salted player datum
         * differs. Only local slot owner may use the entitlement. */
        setup(); m=message(5,1);
        ids[0]=0x50000; locals[0]=ids[0]; units[0].unit.player_index=ids[0];
        receive(&m);
        CHECK(killstreak_recon_active(0));
        CHECK(recon_local[0].player_index==ids[0]);
        CHECK(!killstreak_recon_active(1));
        setup(); locals[1]=ids[1]; players[1].local_player_index=1;
        award(5,1); CHECK(killstreak_recon_active(0) && !killstreak_recon_active(1));
        CHECK(!killstreak_recon_active(-1) && !killstreak_recon_active(4));
    } else if(!strcmp(argv[1],"replay")) {
        award(3,1); CHECK(!killstreak_recon_active(0)); killstreak_recon_present(0); CHECK(texts==1 && sounds==1);
        award(3,1); award(3,2); now+=90; killstreak_recon_present(0); CHECK(texts==1);
        award(5,3); CHECK(killstreak_recon_active(0)); killstreak_recon_present(0); CHECK(texts==2 && sounds==2);
        now+=90; award(5,3); award(5,4); killstreak_recon_present(0); CHECK(texts==2);
        award(0,5); CHECK(!killstreak_recon_active(0)); award(5,4); CHECK(!killstreak_recon_active(0));
        award(3,6); CHECK(recon_local[0].awarded==3); award(5,7); CHECK(killstreak_recon_active(0));
        now+=900; CHECK(!killstreak_recon_active(0)); award(5,8); CHECK(!killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"time")) {
        award(5,1); now+=899; CHECK(killstreak_recon_active(0));
        for(i=0;i<50;i++) CHECK(killstreak_recon_active(0)); /* paused ticks */
        now++; CHECK(!killstreak_recon_active(0));
        setup(); m=message(5,1); now+=450; receive(&m); CHECK(killstreak_recon_active(0));
        now=1200; CHECK(!killstreak_recon_active(0));
        setup(); m=message(5,1); now+=900; receive(&m); CHECK(!killstreak_recon_active(0));
        setup(); m=message(5,1); now-=16; receive(&m); CHECK(!killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"lifecycle")) {
        for(i=0;i<9;i++) {
            setup(); award(5,1); CHECK(killstreak_recon_active(0));
            if(i==0) units[0].object.damage_flags=1;
            if(i==1) players[0].quit_out_of_game=1;
            if(i==2) players[0].unit_index+=0x10000;
            if(i==3) ids[0]+=0x10000;
            if(i==4) in_play=0;
            if(i==5) policy=0;
            if(i==6) in_game=0;
            if(i==7) killstreak_recon_reset_player(ids[0]);
            if(i==8) killstreak_recon_stop();
            CHECK(!killstreak_recon_active(0)); killstreak_recon_present(0); CHECK(texts==0 && sounds==0);
        }
        setup(); m=message(5,1); killstreak_recon_reset_player(ids[0]);
        unit_ids[0]+=0x10000; players[0].unit_index=unit_ids[0]; receive(&m);
        CHECK(!killstreak_recon_active(0)); award(5,2); CHECK(killstreak_recon_active(0));
        m=message(0,3); m.unit_index-=0x10000; receive(&m); CHECK(killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"policy")) {
        award(5,1); CHECK(killstreak_recon_active(0)); /* Commander OFF */
        policy|=COMMANDER_FEATURE_MASK; CHECK(killstreak_recon_active(0));
        CHECK(!killstreak_recon_active(1));
        setup(); policy=KILLSTREAK_ENABLED; award(5,1); CHECK(killstreak_recon_active(0));
        killstreak_recon_present(0); CHECK(texts==0 && sounds==0);
        setup(); award(5,1); policy&=~KILLSTREAK_ANNOUNCEMENTS;
        killstreak_recon_present(0); CHECK(texts==0 && sounds==0 && killstreak_recon_active(0));
        for(i=0;i<5;i++) {
            setup(); if(i==0) policy=COMMANDER_FEATURE_MASK;
            if(i==1) coop=1;
            if(i==2) menu=1;
            if(i==3) connection=_game_connection_film_playback;
            if(i==4) in_play=0;
            award(5,1); CHECK(!killstreak_recon_active(0));
        }
        setup(); teams=0; award(5,1); CHECK(killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"unicast")) {
        connection=_game_connection_network_server;
        killstreak_recon_award(ids[1],unit_ids[1],3); CHECK(sent==1 && sent_machine==1 && texts==0 && sounds==0);
        memcpy(&m,packet,sizeof(m)); CHECK(m.award==3 && m.player_index==1 && m.unit_index==unit_ids[1]);
        killstreak_recon_award(ids[1],unit_ids[1],5); CHECK(sent==2 && sent_machine==1);
        killstreak_recon_revoke(ids[1],unit_ids[1]); CHECK(sent==3 && sent_machine==1);
        killstreak_recon_award(ids[0],unit_ids[0],5); CHECK(sent==3 && killstreak_recon_active(0));
        killstreak_recon_award(ids[112],unit_ids[112],5); CHECK(sent==3);
        connection=_game_connection_network_client;
        killstreak_recon_award(ids[2],unit_ids[2],5); killstreak_recon_revoke(ids[2],unit_ids[2]); CHECK(sent==3);
        setup(); connection=_game_connection_local; killstreak_recon_award(ids[0],unit_ids[0],5);
        CHECK(sent==0 && killstreak_recon_active(0));
    } else if(!strcmp(argv[1],"presentation")) {
        award(3,1); award(5,2); CHECK(texts==0 && sounds==0);
        killstreak_recon_present(0); CHECK(texts==1 && sounds==1 && last_sound==3);
        killstreak_recon_present(0); CHECK(texts==1); now+=90;
        killstreak_recon_present(0); CHECK(texts==2 && sounds==2 && last_sound==5);
        setup(); locals[1]=ids[1]; players[1].local_player_index=1;
        award(5,1); killstreak_recon_present(1); CHECK(texts==0 && sounds==0);
        killstreak_recon_present(0); CHECK(texts==1 && last_text_local==0 && sounds==0);
        for(i=0;i<5;i++) {
            setup(); award(3,1);
            if(i==0) globals.multiplayer_information.count=0;
            if(i==1) globals.multiplayer_information.address=NULL;
            if(i==2) information.sounds.count=0;
            if(i==3) information.sounds.address=NULL;
            if(i==4) clips[_multiplayer_sound_triple_kill].index=NONE;
            /* Stock sound tags are deliberately not needed for original
             * device speech callouts; even maps lacking them get audio. */
            killstreak_recon_present(0); CHECK(texts==1 && sounds==1 && last_sound==3);
        }
        setup(); speech_available=0; award(3,1);
        killstreak_recon_present(0); CHECK(texts==1 && sounds==0);
        setup(); award(5,1); cinematic=1; killstreak_recon_present(0); CHECK(texts==0 && sounds==0);
    } else if(!strcmp(argv[1],"hud")) {
        count=4; award(5,1); players[1].team_index=0; units[2].object.damage_flags=1;
        players[3].network_player_data.machine_index=112;
        hud_draw_personal_recon(); CHECK(quads==1 && last_target==3);
        setup(); award(5,1); hud_draw_personal_recon(); CHECK(quads==24 && checked<=128 && projected==24);
        setup(); award(5,1); hidden=1; hud_draw_personal_recon(); CHECK(quads==0 && checked==128);
        setup(); award(5,1); offscreen=1; hud_draw_personal_recon(); CHECK(quads==0 && checked==128);
        setup(); hud_draw_personal_recon(); CHECK(quads==0 && checked==0);
        award(5,1); locals[1]=ids[1]; players[1].local_player_index=1; render.local_player_index=1;
        hud_draw_personal_recon(); CHECK(quads==0 && checked==0);
        render.local_player_index=0; now+=900; hud_draw_personal_recon(); CHECK(quads==0);
    } else return 2;
    printf("PASS private recon %s\n",argv[1]); return 0;
}
