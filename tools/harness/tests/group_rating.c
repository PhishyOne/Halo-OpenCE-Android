/* Production Android group rating code with a fake network/world. */
#define HALO_ANDROID 1
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include "network_group_rating.h"

#define TRUE 1
#define NONE (-1)
#define HALO_PORT_MAXIMUM_NETWORK_MACHINES 128
#define _game_connection_network_server 2
#define _game_connection_network_client 1
#define _distributed_message_group_challenge 103
#define _distributed_message_group_identity 104
#define _distributed_message_group_lock 105
#define _distributed_message_group_receipt 106
#define _distributed_to_host_reliably 3
#define GET_MESSAGE_TYPE(n) ((n)&3)
typedef unsigned char byte;
struct distributed_message_header { uint16_t header; uint8_t type,count; int32_t game_time; };
struct player_datum {
    int local_player_index,quit_out_of_game,team_index;
    struct { int machine_index; } network_player_data;
};
struct data_iterator { int position; long datum_index; };
struct game_variant { int game_engine_index; };
static struct game_variant variant={2};
static struct player_datum players[4];
static int present[4], outcomes[4];
static int conn=2, online=0, coop=0, teams=0, split=0, configured_bots=0;
static long tick=1;
static void *player_data;
static int challenge_count, lock_count, final_count, identity_count;
static unsigned short challenge_size,lock_size,final_size,identity_size;
static unsigned char challenge_packet[1024],lock_packet[1024],final_packet[1024],identity_packet[1024];
static const char *HOST="11111111111111111111111111111111";
static const char *GUEST="22222222222222222222222222222222";
static const char *THIRD="33333333333333333333333333333333";
static int game_in_progress(void) { return 1; }
static int network_coop_active(void) { return coop; }
static int network_game_is_splitscreen_local(void) { return split; }
static int game_connection(void) { return conn; }
static int network_game_distributed_client(void) { return conn==1; }
static long game_time_get(void) { return tick; }
static int bots_machine_is_bot(long m) { return m>=64; }
static long bots_system_link_wanted_count(void) { return configured_bots; }
static int game_engine_has_teams(void) { return teams; }
static struct game_variant *game_engine_get_variant(void) { return &variant; }
int config_boolean(const char *name) { assert(!strcmp(name,"network.online")); return online; }
static long game_engine_did_player_win(long datum) {
    assert(datum>=100&&datum<104); return outcomes[datum-100];
}
static void data_iterator_new(struct data_iterator *it, void *unused) {
    (void)unused; it->position=0;
}
static void *data_iterator_next(struct data_iterator *it) {
    while(it->position<4) {
        int n=it->position++;
        if(present[n]) { it->datum_index=100+n; return &players[n]; }
    }
    return NULL;
}
static void capture(void *data,byte type,unsigned short size) {
    struct distributed_message_header *h=data;
    h->header=2; h->type=type; h->count=1; h->game_time=(int32_t)tick;
    assert(size<=1024);
    switch(type) {
    case 103: challenge_count++; challenge_size=size; memcpy(challenge_packet,data,size); break;
    case 104: identity_count++; identity_size=size; memcpy(identity_packet,data,size); break;
    case 105: lock_count++; lock_size=size; memcpy(lock_packet,data,size); break;
    case 106: final_count++; final_size=size; memcpy(final_packet,data,size); break;
    default: assert(0);
    }
}
static void distributed_send_to_machine_reliably(long machine,void *data,byte type,short count,unsigned short size) {
    assert(machine>=1&&machine<=3&&count==1); capture(data,type,size);
}
static void distributed_send(void *data,byte type,short count,unsigned short size,short destination) {
    assert(type==104&&count==1&&destination==_distributed_to_host_reliably);
    capture(data,type,size);
}
#include "group.inc"

static void identity(const char *root,const char *id,int enabled) {
    char path[1024];
    FILE *out;
    assert(setenv("HALO_RATING_PRIVATE_ROOT",root,1)==0);
    snprintf(path,sizeof(path),"%s/halo-lan-sharing-v1.txt",root);
    out=fopen(path,"wb"); assert(out);
    assert(fputs(enabled?"LS1|1|0\n":"LS1|0|1\n",out)>=0); assert(fclose(out)==0);
    snprintf(path,sizeof(path),"%s/halo-group-identity-v1.txt",root);
    out=fopen(path,"wb"); assert(out);
    assert(fprintf(out,"%s\n",id)==33); assert(fclose(out)==0);
}
static void setup(const char *root,int count) {
    int i;
    identity(root,HOST,1);
    memset(players,0,sizeof(players)); memset(present,0,sizeof(present));
    for(i=0;i<count;i++) {
        present[i]=1; players[i].local_player_index=i?NONE:0;
        players[i].network_player_data.machine_index=i;
        players[i].team_index=i%2; outcomes[i]=i?0:1;
    }
    conn=2; tick=1; teams=online=coop=split=configured_bots=0;
    challenge_count=lock_count=final_count=identity_count=0;
    group_rating_start();
}
static void offer(int machine,const char *id,int reliable,int wrong) {
    struct distributed_group_identity_message m;
    memset(&m,0,sizeof(m));
    m.header.header=2;m.header.type=104;m.header.count=1;
    memcpy(m.match,wrong?HOST:match_id,33);memcpy(m.player,id,33);
    assert(group_rating_handle(machine,&m,sizeof(m),reliable));
}
static void row(const char *root,const char *name,char *out) {
    char path[1024];FILE *in;
    snprintf(path,sizeof(path),"%s/%s",root,name);
    in=fopen(path,"rb");assert(in);
    assert(fgets(out,1024,in));fclose(in);
}
static void lock_match(int count) {
    group_rating_update(2); assert(challenge_count==count-1);
    offer(1,GUEST,0,0);offer(1,GUEST,1,1);
    assert(!seats[1].identity[0]);
    offer(1,GUEST,1,0);
    if(count==3) offer(2,THIRD,1,0);
    tick=2;group_rating_update(2);
    assert(is_locked&&lock_count==count-1&&pending_exists());
}
static void final_row(const char *root,char *out) {
    char name[96];snprintf(name,sizeof(name),"group-native-v1-%s.txt",match_id);
    row(root,name,out);
}
int main(int argc,char **argv) {
    const char *test,*root;
    char result[1024],saved_match[33];
    assert(argc==3);test=argv[1];root=argv[2];
    setup(root,(!strcmp(test,"quit_rejoin")||!strcmp(test,"host_crash_observed")||!strcmp(test,"team_quit"))?3:2);
    if(!strcmp(test,"bots")) {
        players[1].network_player_data.machine_index=64;
        group_rating_update(2);assert(rejected&&!is_locked&&!challenge_count&&!pending_exists());
    } else if(!strcmp(test,"configured_bots")) {
        configured_bots=1;group_rating_update(2);assert(rejected&&!pending_exists());
    } else if(!strcmp(test,"online")) {
        online=1;group_rating_update(2);assert(!captured&&!pending_exists());
    } else if(!strcmp(test,"optout")) {
        identity(root,HOST,0);group_rating_update(2);assert(rejected&&!pending_exists());
    } else if(!strcmp(test,"unknown")) {
        group_rating_update(2);tick=302;group_rating_update(2);
        assert(rejected&&!is_locked&&!pending_exists());
    } else if(!strcmp(test,"prelock_leave")) {
        group_rating_update(2);present[1]=0;tick=2;group_rating_update(2);
        assert(rejected&&!pending_exists());
    } else if(!strcmp(test,"duplicate")) {
        group_rating_update(2);offer(1,HOST,1,0);tick=2;group_rating_update(2);
        assert(!is_locked&&!pending_exists());
    } else if(!strcmp(test,"normal")) {
        lock_match(2);tick=900;group_rating_finish(2);assert(finalized&&final_count==1&&!pending_exists());
        final_row(root,result);assert(strstr(result,":-1:3|")&&strstr(result,":-1:1\n"));
        group_rating_finish(2);assert(final_count==1);
    } else if(!strcmp(test,"draw")) {
        lock_match(2);outcomes[0]=outcomes[1]=NONE;tick=900;group_rating_finish(2);
        final_row(root,result);assert(strstr(result,":-1:2|")&&strstr(result,":-1:2\n"));
    } else if(!strcmp(test,"quit")) {
        lock_match(2);tick=45;present[1]=0;group_rating_update(2);
        assert(finalized&&!pending_exists());final_row(root,result);
        assert(strstr(result,":-1:3|")&&strstr(result,":-1:4\n"));
        group_rating_abandon();assert(finalized);
    } else if(!strcmp(test,"quit_rejoin")) {
        lock_match(3);present[1]=0;tick=31;group_rating_update(2);
        assert(seats[1].left&&is_locked&&!finalized);
        present[1]=1;tick=32;group_rating_update(2);assert(seats[1].left);
        outcomes[2]=1;outcomes[0]=0;tick=900;group_rating_finish(2);
        final_row(root,result);assert(strstr(result,":-1:4|")&&strstr(result,":-1:3\n"));
    } else if(!strcmp(test,"team_quit")) {
        teams=1;lock_match(3);present[1]=0;tick=40;group_rating_update(2);
        assert(finalized);final_row(root,result);
        assert(strstr(result,":1:4|")&&strstr(result,":0:3\n"));
    } else if(!strcmp(test,"manual_end")) {
        lock_match(2);group_rating_host_end();tick=90;group_rating_finish(2);
        final_row(root,result);assert(strstr(result,":-1:4|")&&strstr(result,":-1:0\n"));
        assert(!pending_exists());
    } else if(!strcmp(test,"host_abandon")) {
        lock_match(2);group_rating_abandon();final_row(root,result);
        assert(strstr(result,":-1:4|")&&strstr(result,":-1:0\n")&&!pending_exists());
    } else if(!strcmp(test,"host_crash_observed")) {
        lock_match(3);present[1]=0;tick=31;group_rating_update(2);
        assert(!finalized&&pending_exists());
        row(root,"group-host-pending-v1.txt",result);
        assert(strstr(result,":-1:4|22222222222222222222222222222222:-1:4|")&&
            strstr(result,":-1:0\n"));
        /* Stop without dispose: Java recovery must keep both forfeits. */
    } else if(!strcmp(test,"late_join")) {
        lock_match(2);present[2]=1;players[2].local_player_index=NONE;
        players[2].network_player_data.machine_index=2;tick=31;group_rating_update(2);
        assert(compromised&&!finalized);
        present[1]=0;tick=32;group_rating_update(2);group_rating_finish(2);
        final_row(root,result);assert(strstr(result,":-1:0|")&&strstr(result,":-1:4\n"));
    } else if(!strcmp(test,"guest")) {
        char child[1024],host_result[1024];
        lock_match(2);tick=900;group_rating_finish(2);final_row(root,host_result);
        memcpy(saved_match,match_id,33);
        snprintf(child,sizeof(child),"%s/client",root);assert(mkdir(child,0700)==0);
        group_rating_start();identity(child,GUEST,1);conn=1;
        assert(group_rating_handle(NONE,challenge_packet,challenge_size,0));assert(!identity_count);
        assert(group_rating_handle(NONE,challenge_packet,challenge_size,1));assert(identity_count==1);
        assert(!strcmp(match_id,saved_match));
        assert(group_rating_handle(NONE,final_packet,final_size,1));assert(!finalized);
        assert(group_rating_handle(NONE,lock_packet,lock_size,1));assert(is_locked);
        assert(group_rating_handle(NONE,final_packet,final_size,1));assert(finalized);
        final_row(child,result);assert(!strcmp(result,host_result));
        assert(group_rating_handle(NONE,final_packet,final_size,1));assert(finalized);
    } else assert(0);
    puts("PASS native group ranking");
    return 0;
}
