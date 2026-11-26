/* Included in main.c on Android, with the same engine declarations and
 * variant builders as the offline launcher. No gameplay packet structures change. */
#define LAUNCHER_LAN_SEATS 16
#define LAUNCHER_ROUND_CHARS 13
struct launcher_seat {
    wchar_t name[12];
    short team, color;
    unsigned long address;
};
static struct {
    boolean active, configured, played;
    int local, count, bots, red;
    unsigned long peer;
    char round[LAUNCHER_ROUND_CHARS+1], path[128];
    struct launcher_seat seats[LAUNCHER_LAN_SEATS];
    struct game_variant variant;
    struct game_variant_options options;
} launcher_lan;

void platform_request_quit(void);
boolean network_game_client_join_launcher_game(wchar_t const *name, unsigned long address);

static int launcher_hex(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static int launcher_decimal(char const *text, int maximum)
{
    int n = 0;
    if (!text || !*text || (text[0] == '0' && text[1])) return -1;
    for (; *text; text++) {
        if (*text < '0' || *text > '9') return -1;
        n = n * 10 + (*text - '0');
        if (n > maximum) return -1;
    }
    return n;
}

static boolean launcher_lan_map_path(char const *map)
{
    static char const *const stock[] = {
        "beavercreek","sidewinder","damnation","ratrace","prisoner","hangemhigh",
        "chillout","carousel","boardingaction","bloodgulch","wizard","putput","longest"
    };
    size_t i;
    if (!map) return FALSE;
    if (!strncmp(map, "ce:", 3)) {
        char const *name = map+3;
        size_t len = strlen(name);
        if (!len || len > 51 || strstr(name,"..")) return FALSE;
        for (i=0; i<len; i++) {
            char c=name[i];
            if ((c>='a' && c<='z') || (c>='A' && c<='Z') || (c>='0' && c<='9')) continue;
            if (!i || (c!='-' && c!='_' && c!='.')) return FALSE;
        }
        snprintf(launcher_lan.path,sizeof(launcher_lan.path),"custom_maps\\%s",name);
        return TRUE;
    }
    for (i=0; i<NUMBEROF(stock); i++)
        if (!strcmp(map,stock[i])) {
            snprintf(launcher_lan.path,sizeof(launcher_lan.path),"levels\\test\\%s\\%s",map,map);
            return TRUE;
        }
    return FALSE;
}

static boolean launcher_lan_decode(void)
{
    char const *round=getenv("HALO_LOBBY_ROUND"), *rows=getenv("HALO_LOBBY_ROSTER");
    char const *mode=getenv("HALO_LOBBY_MODE"), *peer=getenv("HALO_LOBBY_PEER");
    char const *bindings=getenv("HALO_LOBBY_BINDINGS");
    int i, local, bots, red;
    unsigned int a,b,c,d;
    char trailing;
    if (!round || strlen(round)!=LAUNCHER_ROUND_CHARS || !rows || !*rows || strlen(rows)>800 ||
        !mode || !peer || !launcher_lan_map_path(getenv("HALO_LOBBY_MAP"))) return FALSE;
    for (i=0; i<LAUNCHER_ROUND_CHARS; i++) if (launcher_hex(round[i])<0) return FALSE;
    local=launcher_decimal(getenv("HALO_LOBBY_SEAT"),LAUNCHER_LAN_SEATS-1);
    bots=launcher_decimal(getenv("HALO_LOBBY_BOTS"),64);
    red=launcher_decimal(getenv("HALO_LOBBY_RED"),64);
    if (local<0 || bots<0 || red<0 || red>bots || (local==0)!=(!*peer)) return FALSE;
    if (*peer) {
        if (sscanf(peer,"%3u.%3u.%3u.%3u%c",&a,&b,&c,&d,&trailing)!=4 ||
            b>255 || c>255 || d>255 || !(a==10 || (a==172 && b>=16 && b<=31) ||
            (a==192 && b==168))) return FALSE;
        launcher_lan.peer=(a<<24)|(b<<16)|(c<<8)|d;
    }
    if (!launcher_match_preset_build(mode,&launcher_lan.variant,&launcher_lan.options) ||
        !launcher_match_rules_apply(getenv("HALO_LOBBY_RULES"),mode,&launcher_lan.variant,&launcher_lan.options))
        return FALSE;
    while (*rows) {
        struct launcher_seat *seat;
        char const *end=strchr(rows,',');
        size_t len=end?(size_t)(end-rows):strlen(rows);
        int team=rows[0]-'0', hi,lo;
        if (launcher_lan.count>=LAUNCHER_LAN_SEATS || len<7 || len>47 || (len-3)%4) return FALSE;
        hi=launcher_hex(rows[1]);lo=launcher_hex(rows[2]);
        if (team<0 || team>2 || hi<0 || lo<0 || hi*16+lo>=18 ||
            (team!=2)!=(launcher_lan.variant.universal_variant.teams!=0)) return FALSE;
        seat=&launcher_lan.seats[launcher_lan.count++];
        seat->team=team==2?NONE:team;seat->color=hi*16+lo;
        for (i=3; i<(int)len; i+=4) {
            int unit=0,j;
            for (j=0;j<4;j++) {
                int digit=launcher_hex(rows[i+j]);
                if (digit<0) return FALSE;
                unit=unit*16+digit;
            }
            if (unit<32 || (unit>=127 && unit<=159)) return FALSE;
            seat->name[(i-3)/4]=(wchar_t)unit;
        }
        if (!end) break;
        rows=end+1;
        if (!*rows) return FALSE;
    }
    if (local>=launcher_lan.count || launcher_lan.count+bots<2) return FALSE;
    if (!bindings || strlen(bindings)!=(local?0u:(size_t)launcher_lan.count*8u)) return FALSE;
    for (i=0; !local && i<launcher_lan.count; i++) {
        int j;
        unsigned long address=0;
        for (j=0;j<8;j++) {
            int digit=launcher_hex(*bindings++);
            if (digit<0) return FALSE;
            address=(address<<4)|(unsigned)digit;
        }
        if ((i==0)!=(address==0)) return FALSE;
        launcher_lan.seats[i].address=address;
    }
    strcpy(launcher_lan.round,round);launcher_lan.local=local;
    launcher_lan.bots=bots;launcher_lan.red=red;launcher_lan.active=TRUE;
    return TRUE;
}

static void launcher_lan_tag(wchar_t *name, int seat)
{
    static char const hex[]="0123456789abcdef";
    int i;
    for (i=0;i<LAUNCHER_ROUND_CHARS;i++) name[i]=(wchar_t)launcher_lan.round[i];
    name[i++]=L'-'; name[i++]=(wchar_t)hex[seat]; name[i]=0;
}
boolean launcher_lan_machine_name(wchar_t *name)
{
    if (!launcher_lan.active) return FALSE;
    launcher_lan_tag(name,launcher_lan.local);
    return TRUE;
}
static int launcher_lan_player_seat(struct network_game *game,struct network_player const *player)
{
    wchar_t const *name;
    int i, seat, machine=player->machine_index;
    if (machine<0 || machine>=(int)NUMBEROF(game->machines) || player->controller_index!=0 ||
        game->machines[machine].machine_index!=machine) return -1;
    name=game->machines[machine].name;
    for (i=0;i<LAUNCHER_ROUND_CHARS;i++) if (name[i]!=(wchar_t)launcher_lan.round[i]) return -1;
    if (name[i]!=L'-' || name[i+1]>127 || name[i+2]!=0) return -1;
    seat=launcher_hex((char)name[i+1]);
    if (seat<0 || seat>=launcher_lan.count) return -1;
    if (!seat) return machine==network_game_client_get_local_machine_index()?0:-1;
    return network_game_server_machine_address(machine)==launcher_lan.seats[seat].address?seat:-1;
}
boolean launcher_lan_admit(struct network_game *game,struct network_player *player)
{
    int seat,index;
    if (!launcher_lan.active || launcher_lan.local!=0) return TRUE;
    seat=launcher_lan_player_seat(game,player);
    if (seat<0) return FALSE;
    for (index=0;index<(int)NUMBEROF(game->players);index++) {
        struct network_player *other=&game->players[index];
        if (network_player_is_valid(other) && launcher_lan_player_seat(game,other)==seat) return FALSE;
    }
    memcpy(player->name,launcher_lan.seats[seat].name,sizeof(player->name));
    player->primary_color_index=launcher_lan.seats[seat].color;
    if (launcher_lan.seats[seat].team!=NONE) player->team_index=(char)launcher_lan.seats[seat].team;
    return TRUE;
}
short launcher_lan_bot_team(long bot)
{
    if (!launcher_lan.active || launcher_lan.local!=0 || !launcher_lan.variant.universal_variant.teams)
        return NONE;
    return bot<launcher_lan.red?0:1;
}
boolean launcher_lan_countdown_allowed(struct network_game *game)
{
    unsigned int seats=0;
    int i,bots=0,red=0;
    if (!launcher_lan.active || launcher_lan.local!=0) return TRUE;
    if (!launcher_lan.configured || launcher_lan.played || strcmp(game->map.name,launcher_lan.path) ||
        memcmp(&game->variant,&launcher_lan.variant,sizeof(game->variant)) ||
        memcmp(&game->variant_options,&launcher_lan.options,sizeof(game->variant_options))) return FALSE;
    for (i=0;i<(int)NUMBEROF(game->players);i++) {
        struct network_player *p=&game->players[i];
        int machine=p->machine_index,seat;
        if (!network_player_is_valid(p)) continue;
        if (machine<0 || machine>=(int)NUMBEROF(game->machines)) return FALSE;
        if (bots_machine_is_bot(machine) && game->machines[machine].machine_index==NONE) {
            bots++;if (p->team_index==0) red++;
            continue;
        }
        seat=launcher_lan_player_seat(game,p);
        if (seat<0 || (seats&(1u<<seat)) || (launcher_lan.seats[seat].team!=NONE &&
            launcher_lan.seats[seat].team!=p->team_index) || p->primary_color_index!=launcher_lan.seats[seat].color ||
            memcmp(p->name,launcher_lan.seats[seat].name,sizeof(p->name))) return FALSE;
        seats|=1u<<seat;
    }
    return seats==((1u<<launcher_lan.count)-1) && bots==launcher_lan.bots &&
        (!launcher_lan.variant.universal_variant.teams || red==launcher_lan.red);
}

/* A fixed app-private receipt lets the launcher explain a failed handoff
 * after the game process exits. The launcher accepts only its own round. */
static void launcher_lan_return(char const *reason)
{
    char const *root=getenv("HALO_RATING_PRIVATE_ROOT"), *round=getenv("HALO_LOBBY_ROUND");
    char path[600];
    if (root && round && strlen(round)==LAUNCHER_ROUND_CHARS &&
        snprintf(path,sizeof(path),"%s/lobby-return.txt",root)<(int)sizeof(path)) {
        FILE *f=fopen(path,"w");
        if (f) {fprintf(f,"%s\n%s\n",round,reason);fclose(f);}
    }
    platform_request_quit();
}

static void launcher_lan_update(boolean menu)
{
    enum { unused, wait_menu, wait_client, wait_match, finished };
    static int state=unused;
    static unsigned long began,last_add;
    wchar_t tag[16];
    struct network_game_client *client;
    struct network_game_server *server;
    struct network_game *game;
    unsigned long now;
    short local_machine;
    int i;
    if (state==finished) return;
    if (state==unused) {
        if (!getenv("HALO_LOBBY_ROUND")) {state=finished;return;}
        if (!launcher_lan_decode()) {state=finished;launcher_lan_return("Invalid match settings.");return;}
        state=wait_menu;began=system_milliseconds();
    }
    now=system_milliseconds();
    if (state>=wait_client && !menu && game_engine_running()) launcher_lan.played=TRUE;
    if (launcher_lan.played) {
        if (menu) {state=finished;launcher_lan_return("Match ended.");}
        return;
    }
    if (now-began>120000UL) {
        state=finished;launcher_lan_return("Timed out waiting for Halo to load the selected map and roster.");return;
    }
    if (!menu) return;
    if (state==wait_menu) {
        char build[32];
        long profile_index;
        struct player_profile profile;
        if (bink_playback_in_progress()) {bink_playback_stop();return;}
        if (!ui_main_menu_music_active()) return;
        if (network_game_is_active() || !cache_files_map_present(launcher_lan.path,0) ||
            !cache_files_map_plays_multiplayer(launcher_lan.path,build) ||
            (!build[0] && strncmp(launcher_lan.path,"custom_maps\\",12))) {
            state=finished;launcher_lan_return("Halo could not open the selected multiplayer map.");return;
        }
        profile_index=player_ui_get_player1_last_used_profile_index();
        if (profile_index==NONE || !player_profile_get(profile_index,&profile)) {
            player_ui_get_active_player_profile(0,&profile);
            profile_index=player_ui_get_active_player_profile_index(0);
        }
        memcpy(profile.player_name,launcher_lan.seats[launcher_lan.local].name,sizeof(profile.player_name));
        profile.primary_color_index=launcher_lan.seats[launcher_lan.local].color;
        player_ui_set_active_player_profile(0,profile_index,&profile);
        player_ui_local_player_joined_multiplayer_game(0);
        launcher_lan_tag(tag,0);
        if (!launcher_lan.local) {
            network_game_server_port_set_settings(tag,launcher_lan.count+launcher_lan.bots);
            player_ui_fast_setup_network_server();
            server=global_network_game_server_get();
            if (!server || !global_network_game_client_get()) {
                state=finished;launcher_lan_return("Could not open the System Link host.");return;
            }
            network_game_server_pause_countdown(server,TRUE);
        } else {
            ui_widgets_close_all();
            if (!create_global_network_game_client()) {
                state=finished;launcher_lan_return("Could not create the System Link client.");return;
            }
            game_connection_set(_game_connection_network_client);
        }
        state=wait_client;return;
    }
    client=global_network_game_client_get();
    server=global_network_game_server_get();
    if (!client || (!launcher_lan.local && !server)) {
        state=finished;launcher_lan_return("Halo lost the System Link session.");return;
    }
    if (launcher_lan.local && state==wait_client) {
        launcher_lan_tag(tag,0);
        if (network_game_client_join_launcher_game(tag,launcher_lan.peer)) {
            ui_widget_load_by_name_or_tag("ui\\shell\\main_menu\\multiplayer_type_select\\connected\\pregame\\connected_pregame_screen",
                NONE,NULL,NONE,NONE,NONE,NONE);
            state=wait_match;
        }
        return;
    }
    if (network_game_client_get_state(client,NULL)!=2) return;
    if (!launcher_lan.local && !launcher_lan.configured && network_game_server_port_bots_adjustable(server)) {
        network_game_server_change_map_name(server,launcher_lan.path);
        player_ui_set_game_variant(&launcher_lan.variant);
        player_ui_set_game_variant_options(&launcher_lan.options);
        network_game_server_change_game_variant(server,&launcher_lan.variant);
        launcher_lan.configured=TRUE;state=wait_match;
    }
    game=network_game_get_game();local_machine=network_game_client_get_local_machine_index();
    if (!game || local_machine==NONE) return;
    for (i=0;i<(int)NUMBEROF(game->players);i++)
        if (network_player_is_valid(&game->players[i]) && game->players[i].machine_index==local_machine &&
            game->players[i].controller_index==0) break;
    if (i==(int)NUMBEROF(game->players) && now-last_add>=500) {
        last_add=now;network_game_client_add_player(client,0);
    }
    if (!launcher_lan.local && network_game_server_port_bots_adjustable(server) &&
        network_game_server_port_ok_to_countdown(server) && network_game_server_port_countdown_milliseconds(server)<0)
        network_game_server_port_countdown_begin(server,3000);
}
