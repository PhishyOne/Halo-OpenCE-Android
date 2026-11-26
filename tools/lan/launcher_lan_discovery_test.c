#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>
#define TRUE 1
#define FALSE 0
#define _network_game_client_state_searching 1
#define NETWORK_GAME_NAME_LENGTH 16
#define NETWORK_GAME_SERVER_PORT 5150
#define MAXIMUM_NETWORK_ADVERTISED_GAMES 4
#define csmemset memset
#define ustrncmp wcsncmp
typedef int boolean;
typedef unsigned long XNADDR;
typedef int XNKEY;
typedef int XNKID;
struct network_advertised_game {int valid,platform,open,compatible;wchar_t game_name[16];XNADDR xnaddr;XNKEY key;XNKID key_id;};
struct network_game_client {int state,join_in_progress,*connection;struct network_advertised_game available_games[4];};
struct transport_address {struct {unsigned long long_words[1];} address;int port;};
struct network_join_parameters {char join_token[4];};
static struct network_game_client client;
static int network_game_client_incompatibility_told,selected=-1,connected;
static struct network_game_client *global_network_game_client_get(void){return &client;}
static int network_connection_connected(int *c){return *c;}
static int network_game_client_advertised_game_is_valid(struct network_advertised_game *g){return g->valid;}
static int network_game_get_local_platform(void){return 3;}
static int network_game_client_advertised_game_compatible(struct network_game_client *c,struct network_advertised_game *g,int tell){(void)c;(void)tell;return g->compatible;}
static void transport_client_start(const XNADDR *x,const XNKEY *k,const XNKID *id,int port,struct transport_address *out){(void)k;(void)id;out->address.long_words[0]=*x;out->port=port;}
static void network_game_generate_join_game_token(char *token){token[0]=1;}
static int network_game_client_initiate_join_game(struct network_game_client *c,struct network_advertised_game *g,struct network_join_parameters *j,struct transport_address *a){
    assert(j->join_token[0]&&a->port==5150);selected=(int)(g-c->available_games);return 1;
}
#include "launcher_join.inc"
int main(void){
    wchar_t const *round=L"0123456789abc-0";int i;
    client.state=1;client.connection=&connected;
    for(i=0;i<4;i++){struct network_advertised_game *g=&client.available_games[i];g->valid=g->open=g->compatible=1;g->platform=3;g->xnaddr=0xc0a82b01;wcscpy(g->game_name,round);}
    wcscpy(client.available_games[0].game_name,L"Another lobby");
    client.available_games[1].xnaddr=0xc0a82b02;
    client.available_games[2].compatible=0;
    client.available_games[3].open=0;
    assert(!network_game_client_join_matching_game(round,0xc0a82b01)&&selected==-1);
    client.available_games[3].open=1;
    assert(network_game_client_join_matching_game(round,0xc0a82b01)&&selected==3);
    assert(network_game_client_join_matching_game(NULL,0)&&selected==0);
    connected=1;assert(!network_game_client_join_matching_game(round,0xc0a82b01));return 0;
}
