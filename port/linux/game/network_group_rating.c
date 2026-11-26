/* Optional trusted-friends group rating messages. Existing career and
 * gameplay wire layouts are unchanged. Android-only, LAN-only. */
#ifdef HALO_ANDROID
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "memory/data.h"
#include "networking/network_game_globals.h"
#include "network_distributed.h"
#include "network_coop.h"
#include "network_group_rating.h"
#include "bots.h"
#include "vip.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#undef fopen
#undef rename

extern int config_boolean(const char *name);
#define GROUP_SEATS 16
#define GROUP_HANDSHAKE_TICKS 300
#define GROUP_LINE 1024

/* N=neutral, L=loss, D=draw, W=win, F=forfeit. Numeric wire enum. */
enum { GROUP_NEUTRAL, GROUP_LOSS, GROUP_DRAW, GROUP_WIN, GROUP_FORFEIT };
struct distributed_group_challenge_message {
    struct distributed_message_header header;
    char match[33], host[33];
};
struct distributed_group_identity_message {
    struct distributed_message_header header;
    char match[33], player[33];
};
struct distributed_group_receipt_message {
    struct distributed_message_header header;
    char match[33], host[33];
    uint32_t epoch;
    int32_t mode, ticks, count, abandoned;
    char players[16][33];
    int32_t teams[16], outcomes[16];
};
typedef char group_challenge_wire_size[sizeof(struct distributed_group_challenge_message)==76?1:-1];
typedef char group_identity_wire_size[sizeof(struct distributed_group_identity_message)==76?1:-1];
typedef char group_receipt_wire_size[sizeof(struct distributed_group_receipt_message)==752?1:-1];
struct group_seat {
    long datum, machine, team;
    int local, left;
    char identity[33];
};

static struct group_seat seats[GROUP_SEATS];
static struct distributed_group_receipt_message locked;
static char match_id[33], host_id[33];
static int seat_count, captured, rejected, compromised, is_locked, finalized;
static int host_ended, is_host, mode_at_start;
static long first_tick, last_challenge;

static int valid_id(const char *id) {
    int i;
    for (i=0;i<32;i++)
        if (!((id[i]>='0'&&id[i]<='9')||(id[i]>='a'&&id[i]<='f'))) return 0;
    return id[32]==0;
}
static const char *private_root(void) { return getenv("HALO_RATING_PRIVATE_ROOT"); }

static int read_identity(char out[33]) {
    const char *root=private_root();
    char path[1024], row[40];
    FILE *in;
    size_t n;
    if (!root || !*root) return 0;
    if (snprintf(path,sizeof(path),"%s/halo-lan-sharing-v1.txt",root)>=(int)sizeof(path)) return 0;
    in=fopen(path,"rb");
    if (!in) return 0;
    n=fread(row,1,sizeof(row),in); fclose(in);
    if (n!=8 || memcmp(row,"LS1|1|",6) || (row[6]!='0'&&row[6]!='1') || row[7]!='\n') return 0;
    if (snprintf(path,sizeof(path),"%s/halo-group-identity-v1.txt",root)>=(int)sizeof(path)) return 0;
    in=fopen(path,"rb");
    if (!in) return 0;
    n=fread(row,1,sizeof(row),in); fclose(in);
    if(n!=33 || row[32]!='\n') return 0;
    row[32]=0;
    if(!valid_id(row)) return 0;
    memcpy(out,row,33);
    return 1;
}

static int random_id(char out[33]) {
    unsigned char bytes[16];
    static const char digits[]="0123456789abcdef";
    FILE *in=fopen("/dev/urandom","rb");
    size_t n; int i;
    if(!in) return 0;
    n=fread(bytes,1,sizeof(bytes),in); fclose(in);
    if(n!=sizeof(bytes)) return 0;
    for(i=0;i<16;i++) {
        out[2*i]=digits[bytes[i]>>4]; out[2*i+1]=digits[bytes[i]&15];
    }
    out[32]=0; return 1;
}

static int lan_match(void) {
    return game_in_progress() && !network_coop_active() &&
        !network_game_is_splitscreen_local() && !config_boolean("network.online") &&
        (game_connection()==_game_connection_network_server || network_game_distributed_client());
}

static int valid_receipt(const struct distributed_group_receipt_message *r, int start) {
    int i,j,host=0,teams=0,forfeits=0,normal=0;
    if(!valid_id(r->match)||!valid_id(r->host)||r->epoch<1500000000U||
       r->epoch>4102444800U||r->mode<1||r->mode>5||r->ticks<0||
       r->ticks>2592000||r->count<2||r->count>GROUP_SEATS||
       r->abandoned<0||r->abandoned>1||(start&&r->abandoned)) return 0;
    for(i=0;i<r->count;i++) {
        if(!valid_id(r->players[i])||r->teams[i]<-1||r->teams[i]>15||
           ((r->teams[i]<0)!=(r->teams[0]<0))||
           r->outcomes[i]<GROUP_NEUTRAL||r->outcomes[i]>GROUP_FORFEIT) return 0;
        if(start && r->outcomes[i]!=GROUP_NEUTRAL) return 0;
        if(!start && ((r->abandoned && r->outcomes[i]!=GROUP_NEUTRAL &&
            r->outcomes[i]!=GROUP_FORFEIT)||(!r->abandoned && r->outcomes[i]==GROUP_NEUTRAL))) return 0;
        for(j=0;j<i;j++) if(!strcmp(r->players[i],r->players[j])) return 0;
        if(!strcmp(r->players[i],r->host)) host++;
        if(r->teams[i]!=r->teams[0]) teams=1;
        if(r->outcomes[i]==GROUP_FORFEIT) forfeits++;
        if(r->outcomes[i]==GROUP_WIN||r->outcomes[i]==GROUP_DRAW) normal++;
    }
    return host==1 && (r->teams[0]<0||teams) &&
        (start || (r->abandoned ? forfeits>0 : normal>0));
}
static int same_roster(const struct distributed_group_receipt_message *a,
                       const struct distributed_group_receipt_message *b) {
    int i;
    if(strcmp(a->match,b->match)||strcmp(a->host,b->host)||a->epoch!=b->epoch||
       a->mode!=b->mode||a->count!=b->count||b->ticks<a->ticks) return 0;
    for(i=0;i<a->count;i++) if(strcmp(a->players[i],b->players[i])||a->teams[i]!=b->teams[i]) return 0;
    return 1;
}
static int has_identity(const struct distributed_group_receipt_message *r,const char *id) {
    int i;
    for(i=0;i<r->count;i++) if(!strcmp(r->players[i],id)) return 1;
    return 0;
}
static int write_row(const struct distributed_group_receipt_message *r, int pending) {
    const char *root=private_root();
    char path[1024], temp[1024], row[GROUP_LINE], name[90];
    int n,i,m,ok;
    FILE *out;
    if(!root||!*root||!valid_receipt(r,0)) return 0;
    if(pending) strcpy(name,"group-host-pending-v1.txt");
    else snprintf(name,sizeof(name),"group-native-v1-%s.txt",r->match);
    if(snprintf(path,sizeof(path),"%s/%s",root,name)>=(int)sizeof(path)||
       snprintf(temp,sizeof(temp),"%s/%s.tmp",root,name)>=(int)sizeof(temp)) return 0;
    n=snprintf(row,sizeof(row),"GR1|%s|%s|%lu|%ld|%ld|%ld|%ld",
       r->match,r->host,(unsigned long)r->epoch,(long)r->mode,(long)r->ticks,
       (long)r->abandoned,(long)r->count);
    if(n<0||n>=(int)sizeof(row)) return 0;
    for(i=0;i<r->count;i++) {
        m=snprintf(row+n,sizeof(row)-n,"|%s:%ld:%ld",r->players[i],
            (long)r->teams[i],(long)r->outcomes[i]);
        if(m<0||m>=(int)(sizeof(row)-n)) return 0;
        n+=m;
    }
    if(n+1>=(int)sizeof(row)) return 0;
    row[n++]='\n';
    out=fopen(temp,"wb"); if(!out) return 0;
    ok=fwrite(row,1,(size_t)n,out)==(size_t)n && fflush(out)==0 && fsync(fileno(out))==0;
    if(fclose(out)!=0) ok=0;
    if(ok) ok=rename(temp,path)==0;
    if(!ok) unlink(temp);
    if(ok) {
        FILE *dir=fopen(root,"r");
        if(dir) { (void)fsync(fileno(dir)); fclose(dir); }
    }
    return ok;
}
static void remove_pending(void) {
    const char *root=private_root();
    char path[1024];
    if(root && snprintf(path,sizeof(path),"%s/group-host-pending-v1.txt",root)<(int)sizeof(path))
        unlink(path);
}
/* At native restart, do not overwrite an unresolved old host pending record.
 * Launcher recovery handles it when Android confirms the game is stopped.
 * A normal in-process map change finalizes the old pending first. */
static int pending_exists(void) {
    const char *root=private_root();
    char path[1024];
    FILE *in;
    if(!root||snprintf(path,sizeof(path),"%s/group-host-pending-v1.txt",root)>=(int)sizeof(path)) return 1;
    in=fopen(path,"rb"); if(!in) return 0;
    fclose(in); return 1;
}
static void abandonment(struct distributed_group_receipt_message *r) {
    int i;
    *r=locked; r->abandoned=1;
    for(i=0;i<r->count;i++)
        r->outcomes[i]=(seats[i].local||seats[i].left)?GROUP_FORFEIT:GROUP_NEUTRAL;
}
static int persist_pending(void) {
    struct distributed_group_receipt_message r;
    abandonment(&r);
    return write_row(&r,1);
}
void group_rating_abandon(void) {
    struct distributed_group_receipt_message r;
    if(!is_host||!is_locked||finalized) return;
    abandonment(&r);
    if(write_row(&r,0)) { finalized=1; remove_pending(); }
}
void group_rating_start(void) {
    group_rating_abandon();
    memset(seats,0,sizeof(seats)); memset(&locked,0,sizeof(locked));
    memset(match_id,0,sizeof(match_id)); memset(host_id,0,sizeof(host_id));
    seat_count=captured=rejected=compromised=is_locked=finalized=host_ended=is_host=mode_at_start=0;
    first_tick=last_challenge=-1;
}
void group_rating_host_end(void) {
    if(is_host && is_locked && !finalized) {
        host_ended=1;
        /* Mark abandonment now, before postgame UI can be interrupted. */
        (void)persist_pending();
    }
}
static int initial_roster(void) {
    struct data_iterator it;
    struct player_datum *p;
    int i,n=0,local=0;
    data_iterator_new(&it,player_data);
    while((p=(struct player_datum*)data_iterator_next(&it))!=NULL) {
        long machine=p->network_player_data.machine_index;
        if(p->quit_out_of_game||bots_machine_is_bot(machine)||n>=GROUP_SEATS||
           (machine<0 && p->local_player_index==NONE) ||
           machine>=HALO_PORT_MAXIMUM_NETWORK_MACHINES) return -1;
        for(i=0;i<n;i++) if(seats[i].machine==machine) return -1;
        seats[n].datum=it.datum_index;
        seats[n].machine=machine;
        seats[n].local=p->local_player_index!=NONE;
        if(seats[n].local && (p->local_player_index!=0 || ++local>1)) return -1;
        seats[n].team=game_engine_has_teams()?p->team_index:-1;
        if(game_engine_has_teams()&&(seats[n].team<0||seats[n].team>15)) return -1;
        n++;
    }
    if(n<2||local!=1) return 0;
    seat_count=n;
    return 1;
}
static int observe_roster(void) {
    struct data_iterator it;
    struct player_datum *p;
    int seen[GROUP_SEATS]={0},i,changed=0;
    data_iterator_new(&it,player_data);
    while((p=(struct player_datum*)data_iterator_next(&it))!=NULL) {
        long machine=p->network_player_data.machine_index;
        for(i=0;i<seat_count;i++)
            if(seats[i].datum==it.datum_index && seats[i].machine==machine) break;
        if(i==seat_count||bots_machine_is_bot(machine)) { compromised=1; continue; }
        if(p->quit_out_of_game) continue;
        seen[i]=1;
        if((game_engine_has_teams()?p->team_index:-1)!=seats[i].team) compromised=1;
    }
    for(i=0;i<seat_count;i++) if(!seen[i]&&!seats[i].left) {
        seats[i].left=1; changed=1;
    }
    if(changed&&is_locked) (void)persist_pending();
    if(!is_locked && (changed||compromised)) rejected=1;
    return changed;
}
static void challenge(void) {
    struct distributed_group_challenge_message msg;
    int i;
    memset(&msg,0,sizeof(msg));
    memcpy(msg.match,match_id,33); memcpy(msg.host,host_id,33);
    for(i=0;i<seat_count;i++) if(!seats[i].local&&!seats[i].left&&!seats[i].identity[0])
        distributed_send_to_machine_reliably(seats[i].machine,&msg,
            _distributed_message_group_challenge,1,sizeof(msg));
}
static void send_receipt(struct distributed_group_receipt_message *r, int type) {
    int i;
    for(i=0;i<seat_count;i++) if(!seats[i].local&&!seats[i].left)
        distributed_send_to_machine_reliably(seats[i].machine,r,(byte)type,1,sizeof(*r));
}
static void try_lock(long tick) {
    int i;
    char current[33];
    time_t now;
    if(is_locked||rejected) return;
    if(!read_identity(current)||strcmp(current,host_id)) { rejected=1; return; }
    for(i=0;i<seat_count;i++) if(!valid_id(seats[i].identity)) return;
    memset(&locked,0,sizeof(locked));
    memcpy(locked.match,match_id,33); memcpy(locked.host,host_id,33);
    now=time(NULL);
    if(now<1500000000LL || (uint64_t)now>4102444800ULL) { rejected=1; return; }
    locked.epoch=(uint32_t)now; locked.mode=mode_at_start; locked.ticks=(int32_t)tick; locked.count=seat_count;
    for(i=0;i<seat_count;i++) {
        memcpy(locked.players[i],seats[i].identity,33);
        locked.teams[i]=(int32_t)seats[i].team;
    }
    if(!valid_receipt(&locked,1)||pending_exists()||!persist_pending()) { rejected=1; return; }
    is_locked=1;
    send_receipt(&locked,_distributed_message_group_lock);
}
/* If all opposing players have actually departed, award the surviving
 * side a forfeit victory immediately. No need for the host to remain in an
 * empty match or accept a penalty for leaving after the outcome is settled.
 * This does not force the native game itself to end. */
static int sole_remaining_side(void) {
    int i, first=-1, alive=0, opposition_left=0;
    if(compromised) return 0;
    for(i=0;i<seat_count;i++) if(!seats[i].left) {
        if(first<0) first=i;
        else if(seats[first].team<0||seats[first].team!=seats[i].team) return 0;
        alive++;
    }
    if(!alive) return 0;
    for(i=0;i<seat_count;i++) if(seats[i].left &&
        (seats[first].team<0||seats[first].team!=seats[i].team)) opposition_left=1;
    return opposition_left;
}

void group_rating_update(long mode) {
    long tick;
    int i, roster;
    if(finalized||rejected||!lan_match()||vip_enabled()||mode<1||mode>5) return;
    if(network_game_distributed_client()) return; /* reliable challenge drives clients */
    is_host=1;
    tick=game_time_get();
    if(tick<0) return;
    if(first_tick<0) first_tick=tick;
    if(!captured) {
        if(tick-first_tick>60||bots_system_link_wanted_count()>0) { rejected=1; return; }
        roster=initial_roster();
        if(roster<0) { rejected=1; return; }
        if(!roster) return;
        if(!read_identity(host_id)||!random_id(match_id)) { rejected=1; return; }
        mode_at_start=(int)mode;
        for(i=0;i<seat_count;i++) if(seats[i].local) memcpy(seats[i].identity,host_id,33);
        captured=1;
    }
    if(mode!=mode_at_start) compromised=1;
    observe_roster();
    if(rejected) return;
    if(is_locked) {
        if(sole_remaining_side()) group_rating_finish(mode);
        return;
    }
    if(tick-first_tick>GROUP_HANDSHAKE_TICKS) { rejected=1; return; }
    if(last_challenge<0||tick-last_challenge>=30) {
        last_challenge=tick;
        challenge();
    }
    try_lock(tick);
}
void group_rating_finish(long mode) {
    struct distributed_group_receipt_message r;
    char current_id[33];
    int i;
    if(!is_host||!is_locked||finalized||mode!=mode_at_start) return;
    observe_roster();
    if(sole_remaining_side()) {
        r=locked;
        r.ticks=(int32_t)game_time_get();
        for(i=0;i<r.count;i++)
            r.outcomes[i]=seats[i].left?GROUP_FORFEIT:GROUP_WIN;
    } else if(host_ended||compromised) {
        abandonment(&r);
        /* A changed roster without a voluntary host end must not invent a
         * normal result. Only known departures are charged, not a player
         * who merely hosted a match disrupted by a late join. */
        if(!host_ended) for(i=0;i<r.count;i++)
            if(seats[i].local&&!seats[i].left) r.outcomes[i]=GROUP_NEUTRAL;
    } else {
        r=locked;
        r.ticks=(int32_t)game_time_get();
        for(i=0;i<r.count;i++) {
            long result;
            if(seats[i].left) { r.outcomes[i]=GROUP_FORFEIT; continue; }
            result=game_engine_did_player_win(seats[i].datum);
            r.outcomes[i]=result==TRUE?GROUP_WIN:result==NONE?GROUP_DRAW:GROUP_LOSS;
        }
    }
    if(r.ticks<locked.ticks) r.ticks=locked.ticks;
    if(!valid_receipt(&r,0)) {
        /* No meaningful normal outcome: keep only actual forfeits. */
        r=locked; r.abandoned=1;
        for(i=0;i<r.count;i++) r.outcomes[i]=(seats[i].left||(host_ended&&seats[i].local))?
            GROUP_FORFEIT:GROUP_NEUTRAL;
        if(!valid_receipt(&r,0)) { finalized=1; remove_pending(); return; }
    }
    if(!write_row(&r,0)) return; /* durable first */
    finalized=1; remove_pending();
    if(read_identity(current_id)&&!strcmp(current_id,host_id))
        send_receipt(&r,_distributed_message_group_receipt);
}
int group_rating_handle(long machine,const void *data,unsigned short size,int reliable) {
    struct distributed_message_header header;
    char local[33];
    int i;
    if(size<sizeof(header)) return 0;
    memcpy(&header,data,sizeof(header));
    if(header.type<_distributed_message_group_challenge||
       header.type>_distributed_message_group_receipt) return 0;
    if(!reliable||GET_MESSAGE_TYPE(header.header)!=2||header.count!=1||!lan_match()) return 1;
    if(header.type==_distributed_message_group_identity) {
        struct distributed_group_identity_message msg;
        if(machine==NONE||game_connection()!=_game_connection_network_server||
           !captured||rejected||is_locked||size!=sizeof(msg)) return 1;
        memcpy(&msg,data,sizeof(msg));
        if(!valid_id(msg.match)||strcmp(msg.match,match_id)||!valid_id(msg.player)||
           !strcmp(msg.player,host_id)) return 1;
        for(i=0;i<seat_count;i++) if(seats[i].machine==machine&&!seats[i].local&&!seats[i].left) {
            if(seats[i].identity[0]&&strcmp(seats[i].identity,msg.player)) rejected=1;
            else memcpy(seats[i].identity,msg.player,33);
            break;
        }
        return 1;
    }
    if(machine!=NONE||!network_game_distributed_client()||!read_identity(local)) return 1;
    if(header.type==_distributed_message_group_challenge) {
        struct distributed_group_challenge_message msg;
        struct distributed_group_identity_message reply;
        if(size!=sizeof(msg)||is_locked||finalized) return 1;
        memcpy(&msg,data,sizeof(msg));
        if(!valid_id(msg.match)||!valid_id(msg.host)||!strcmp(local,msg.host)) return 1;
        if(match_id[0]&&(strcmp(match_id,msg.match)||strcmp(host_id,msg.host))) return 1;
        memcpy(match_id,msg.match,33); memcpy(host_id,msg.host,33);
        memset(&reply,0,sizeof(reply));
        memcpy(reply.match,match_id,33); memcpy(reply.player,local,33);
        distributed_send(&reply,_distributed_message_group_identity,1,sizeof(reply),_distributed_to_host_reliably);
        return 1;
    }
    {
        struct distributed_group_receipt_message r;
        if(size!=sizeof(r)||!match_id[0]||finalized) return 1;
        memcpy(&r,data,sizeof(r));
        if(!valid_receipt(&r,header.type==_distributed_message_group_lock)||
           strcmp(r.match,match_id)||strcmp(r.host,host_id)||!has_identity(&r,local)||
           !game_engine_get_variant() ||
           r.mode!=game_engine_get_variant()->game_engine_index) return 1;
        if(header.type==_distributed_message_group_lock) {
            if(!is_locked) { locked=r; is_locked=1; }
        } else if(is_locked&&same_roster(&locked,&r)) {
            if(write_row(&r,0)) finalized=1;
        }
    }
    return 1;
}
#endif /* HALO_ANDROID */
